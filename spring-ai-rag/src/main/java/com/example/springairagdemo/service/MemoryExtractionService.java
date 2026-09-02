package com.example.springairagdemo.service;

import com.example.springairagdemo.config.AiConfig;
import com.example.springairagdemo.config.RagConfigProperties;
import com.example.springairagdemo.entity.ChatSessionMemoryEntity;
import com.example.springairagdemo.memory.RedisChatMemory;
import com.example.springairagdemo.memory.RedisChatMemory.StoredConversation;
import com.example.springairagdemo.memory.RedisChatMemory.StoredMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用户长期记忆自动抽取服务（Phase 2）。
 *
 * <p>在会话问答结束后，从 Redis 中该会话的近期对话原文（或管理端最近若干会话摘要）抽取
 * "值得长期记住的用户事实/偏好"，交给 DeepSeek 输出 JSON 列表，再逐条经
 * {@link MemoryService#save} 去重落库（文本 + 向量）。
 *
 * <p>设计约束：
 * <ul>
 *   <li>后台单线程 + 用户级防抖（{@code rag.memory.long-term.auto-extract-interval-minutes}），
 *       绝不阻塞主问答链路，任何失败仅告警；</li>
 *   <li>LLM 调用复用 {@code deepSeekChatModel} 并受 {@code ai-chat} 熔断保护；</li>
 *   <li>抽取输入为 Redis 原始存储（摘要 + 窗口原文），避免再把摘要回读注入模型。</li>
 * </ul>
 */
@Slf4j
@Service
public class MemoryExtractionService {

    private static final String EXTRACT_SYSTEM_PROMPT = """
            你是「用户长期记忆抽取助手」。从给出的近期对话中，提取值得长期记住的稳定个人事实/偏好/经历。
            可抽取示例：生日/年龄、职业/岗位、公司/团队、项目角色、饮食/生活习惯、兴趣爱好、
            家庭成员、重要目标与计划、已确认的安排等（用户主动透露、反复强调或明确确认的才抽取）。

            只输出一个 JSON 数组，不要输出任何解释、前缀或 Markdown 代码块。数组元素格式：
            {"content": "一句话陈述该记忆（不含称谓与时间客套语）", "category": "fact|preference|interest|goal|event", "importance": 5}

            规则：
            1. 仅抽取用户明确表达的信息，禁止猜测、推断或编造；
            2. 知识库业务问答本身不抽（如“入职流程是什么”这类咨询不算个人记忆）；
            3. 一条记录 = 一个独立事实，尽量简短具体；
            4. category 取值：fact 事实 / preference 偏好 / interest 兴趣 / goal 目标 / event 经历；
            5. importance 为 1-10 整数：生日、身份、岗位等稳定信息 9-10；一般偏好 5-7；次要细节 1-4；
            6. 最多输出 %d 条；没有可抽取内容时输出 []。
            """;

    private static final String EXTRACT_USER_TEMPLATE = """
            【近期对话】
            %s
            """;

    /** 输入文本过短则不抽取（避免把客套语/单句问候误抽成记忆） */
    private static final int MIN_TRANSCRIPT_CHARS = 40;
    /** 手工抽取取最近多少个会话摘要 */
    private static final int MANUAL_RECENT_SESSIONS = 8;

    private final MemoryService memoryService;
    private final RedisChatMemory redisChatMemory;
    private final ChatSessionMemoryService chatSessionMemoryService;
    private final ChatModel chatModel;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final RagConfigProperties ragConfig;
    private final ObjectMapper objectMapper;

    /** 用户级防抖：userId -> 当前生效的自动抽取防抖窗口截止时间戳（本次 now 未越过该截止则让出） */
    private final Map<Long, Long> lastAutoExtractAt = new ConcurrentHashMap<>();

    /** 最近一次自动抽取结果：userId -> 结果（供聊天页"自动沉淀完成"提醒），每次抽取结束覆盖 */
    private final Map<Long, AutoExtractResult> lastAutoExtractResults = new ConcurrentHashMap<>();

    /** 后台单线程抽取执行器（daemon 线程，低频触发，不阻塞主问答链路） */
    private final ExecutorService extractionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "user-long-term-memory-extract");
        thread.setDaemon(true);
        return thread;
    });

    public MemoryExtractionService(MemoryService memoryService,
                                   RedisChatMemory redisChatMemory,
                                   ChatSessionMemoryService chatSessionMemoryService,
                                   @Qualifier("deepSeekChatModel") ChatModel chatModel,
                                   CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                                   RagConfigProperties ragConfig,
                                   ObjectMapper objectMapper) {
        this.memoryService = memoryService;
        this.redisChatMemory = redisChatMemory;
        this.chatSessionMemoryService = chatSessionMemoryService;
        this.chatModel = chatModel;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.ragConfig = ragConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 会话结束后调用：满足开关/防抖条件时，把抽取任务提交到后台线程后立即返回（不阻塞）。
     */
    public void tryAutoExtract(Long userId, String sessionId, Long knowledgeBaseId) {
        RagConfigProperties.LongTerm lt = longTerm();
        if (lt == null || !lt.isEnabled() || !lt.isAutoExtractEnabled()) {
            return;
        }
        if (userId == null) {
            return;
        }
        // Redis 会话 key 形如 rag:chat:memory:{userId}:{sessionId}，空会话 ID 落 default
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        long intervalMs = Math.max(1, lt.getAutoExtractIntervalMinutes()) * 60_000L;
        long now = System.currentTimeMillis();
        // 占位式防抖（值 = 窗口截止时间戳）：compute 原子推进，仅当本次 now 已越过旧窗口截止时
        // 才把窗口推进到 now+intervalMs 并赢得本轮；同窗口内并发请求（含同毫秒）一律让出
        Long windowUntil = lastAutoExtractAt.compute(userId,
                (k, old) -> old != null && now < old ? old : now + intervalMs);
        if (windowUntil == null || windowUntil.longValue() != now + intervalMs) {
            return;
        }
        extractionExecutor.execute(() -> {
            try {
                int saved = extractFromSession(userId, sid);
                log.info("用户长期记忆自动抽取完成：userId={}, sessionId={}, 新增记忆 {} 条",
                        userId, sid, saved);
                // 记录结果供聊天页轮询提醒（saved=0 也记录，表示本轮已执行但无可沉淀内容）
                lastAutoExtractResults.put(userId,
                        new AutoExtractResult(System.currentTimeMillis(), saved));
            } catch (Exception e) {
                log.warn("用户长期记忆自动抽取失败（不影响问答）：userId={}, sessionId={}, err={}",
                        userId, sid, e.getMessage());
            }
        });
    }

    /**
     * 管理端"立即沉淀"：基于最近若干会话摘要做一次抽取（同步执行，供控制器直接返回结果）。
     */
    public int extractNow(Long userId) {
        if (userId == null) {
            return 0;
        }
        try {
            String transcript = recentSessionTranscript(userId);
            if (transcript.length() < MIN_TRANSCRIPT_CHARS) {
                log.debug("长期记忆手工抽取：近期会话内容过短，跳过。userId={}", userId);
                return 0;
            }
            return extract(userId, transcript, "manual");
        } catch (Exception e) {
            log.warn("用户长期记忆手工抽取失败：userId={}, err={}", userId, e.getMessage());
            return 0;
        }
    }

    /** 从单个会话的 Redis 对话原文抽取（自动路径） */
    private int extractFromSession(Long userId, String sessionId) {
        StoredConversation conversation = redisChatMemory.readStored(userId + ":" + sessionId);
        if (conversation == null || conversation.messages() == null || conversation.messages().isEmpty()) {
            return 0;
        }
        String transcript = transcriptOf(conversation.messages(), extractMaxChars());
        if (transcript.length() < MIN_TRANSCRIPT_CHARS) {
            return 0;
        }
        return extract(userId, transcript, userId + ":" + sessionId);
    }

    /** 组装近期会话摘要文本（手工路径） */
    private String recentSessionTranscript(Long userId) {
        List<ChatSessionMemoryEntity> recent = chatSessionMemoryService.lambdaQuery()
                .eq(ChatSessionMemoryEntity::getUserId, userId)
                .orderByDesc(ChatSessionMemoryEntity::getUpdateTime)
                .last("limit " + MANUAL_RECENT_SESSIONS)
                .list();
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int maxChars = extractMaxChars();
        for (ChatSessionMemoryEntity session : recent) {
            String summary = session.getSummary();
            if (summary == null || summary.isBlank()) {
                continue;
            }
            String line = "会话摘要：" + summary + "\n";
            if (builder.length() + line.length() > maxChars) {
                break;
            }
            builder.append(line);
        }
        return builder.toString();
    }

    /** 核心抽取：LLM 生成记忆 JSON → 解析 → 逐条去重保存，返回新增条数 */
    private int extract(Long userId, String transcript, String sourceSession) {
        RagConfigProperties.LongTerm lt = longTerm();
        if (lt == null || !lt.isEnabled()) {
            return 0;
        }
        if (memoryService.count(userId) >= lt.getMaxPerUser()) {
            log.debug("用户长期记忆已达上限（{}），跳过抽取。userId={}", lt.getMaxPerUser(), userId);
            return 0;
        }
        String answer;
        try {
            answer = circuitBreakerFactory.create(AiConfig.AI_CHAT_RESOURCE).run(
                    () -> {
                        String text = chatModel.call(new Prompt(
                                        new SystemMessage(EXTRACT_SYSTEM_PROMPT.formatted(lt.getExtractMaxFacts())),
                                        new UserMessage(EXTRACT_USER_TEMPLATE.formatted(transcript))))
                                .getResult().getOutput().getText();
                        return text == null ? null : text.trim();
                    },
                    throwable -> {
                        log.warn("长期记忆抽取调用熔断降级：{}", throwable.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("长期记忆抽取调用异常：{}", e.getMessage());
            return 0;
        }
        if (answer == null || answer.isBlank()) {
            return 0;
        }
        List<ExtractedFact> facts = parseFacts(answer);
        if (facts.isEmpty()) {
            log.debug("长期记忆抽取未产出有效事实。userId={}, answer={}", userId, summaryOf(answer));
            return 0;
        }
        int saved = 0;
        for (ExtractedFact fact : facts) {
            MemoryService.SaveResult result = memoryService.save(userId, fact.content(), fact.category(),
                    fact.importance(), sourceSession);
            if (result != null && result.id() != null && !result.duplicate()) {
                saved++;
            } else if (result != null && log.isDebugEnabled()) {
                log.debug("长期记忆抽取去重/跳过：userId={}, result={}", userId, result.message());
            }
        }
        return saved;
    }

    /** 从用户输入消息组装对话文本（保留最新内容，受 extractMaxChars 约束） */
    private String transcriptOf(List<StoredMessage> messages, int maxChars) {
        int maxTail = maxChars > 0 ? maxChars : 4000;
        List<String> lines = new ArrayList<>();
        int userTurns = 0;
        int total = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            StoredMessage message = messages.get(i);
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            String type = message.type() == null ? "" : message.type().toLowerCase();
            if (!"user".equals(type) && !"assistant".equals(type)) {
                continue;
            }
            String line = ("user".equals(type) ? "用户：" : "助手：") + message.content() + "\n";
            if (total + line.length() > maxTail) {
                break;
            }
            lines.add(line);
            total += line.length();
            if ("user".equals(type)) {
                userTurns++;
            }
        }
        if (userTurns == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(total);
        for (int i = lines.size() - 1; i >= 0; i--) {
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    /** 解析模型输出的 JSON 数组（容忍 Markdown 代码块包裹） */
    private List<ExtractedFact> parseFacts(String answer) {
        List<ExtractedFact> facts = new ArrayList<>();
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return facts;
        }
        try {
            JsonNode root = objectMapper.readTree(answer.substring(start, end + 1));
            if (root == null || !root.isArray()) {
                return facts;
            }
            for (JsonNode node : root) {
                if (node == null || !node.isObject()) {
                    continue;
                }
                String content = textOf(node.get("content"));
                if (content.isBlank()) {
                    continue;
                }
                if (content.length() > 500) {
                    content = content.substring(0, 500);
                }
                String category = textOf(node.get("category"));
                int importance = 5;
                JsonNode importanceNode = node.get("importance");
                if (importanceNode != null && importanceNode.isIntegralNumber()) {
                    importance = Math.max(1, Math.min(10, importanceNode.asInt()));
                }
                facts.add(new ExtractedFact(content, category, importance));
            }
        } catch (Exception e) {
            log.warn("长期记忆抽取结果解析失败，按空处理：{}", e.getMessage());
            return List.of();
        }
        return facts;
    }

    private static String textOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        String text = node.asText();
        return text == null ? "" : text.trim();
    }

    private RagConfigProperties.LongTerm longTerm() {
        return ragConfig == null || ragConfig.getMemory() == null ? null : ragConfig.getMemory().getLongTerm();
    }

    private int extractMaxChars() {
        RagConfigProperties.LongTerm lt = longTerm();
        return lt == null ? 4000 : lt.getExtractMaxChars();
    }

    private static String summaryOf(String text) {
        if (text == null || text.length() <= 120) {
            return text;
        }
        return text.substring(0, 120) + "…";
    }

    /** 抽取到的候选记忆（LLM JSON 解析产物） */
    private record ExtractedFact(String content, String category, int importance) {
    }

    /** 查询最近一次自动抽取结果（可能为 null：从未抽取过或抽取失败） */
    public AutoExtractResult latestAutoExtract(Long userId) {
        return userId == null ? null : lastAutoExtractResults.get(userId);
    }

    /** 自动抽取结果（供前端提示"自动沉淀新增 N 条"） */
    public record AutoExtractResult(long finishedAtMs, int saved) {
    }
}
