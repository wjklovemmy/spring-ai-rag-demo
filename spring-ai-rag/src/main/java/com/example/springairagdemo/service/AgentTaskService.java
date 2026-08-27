package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.springairagdemo.entity.AgentTaskEntity;
import com.example.springairagdemo.entity.AgentTaskStepEntity;
import com.example.springairagdemo.mapper.AgentTaskMapper;
import com.example.springairagdemo.mapper.AgentTaskStepMapper;
import com.example.springairagdemo.tools.KbQueryTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 任务轨迹落库服务：一次提问 = 一条 agent_task，工具调用每一步落 agent_task_step。
 * 供流式问答链路调用——失败不阻塞问答，由调用方捕获告警。
 */
@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);

    /** 任务状态：执行中 */
    public static final int STATUS_RUNNING = 0;
    /** 任务状态：成功 */
    public static final int STATUS_SUCCESS = 1;
    /** 任务状态：失败 */
    public static final int STATUS_FAILED = 2;

    /** 步骤类型：工具调用 */
    public static final String STEP_TYPE_TOOL_CALL = "TOOL_CALL";

    @Autowired
    private AgentTaskMapper agentTaskMapper;

    @Autowired
    private AgentTaskStepMapper agentTaskStepMapper;

    /** 步骤耗时统计：taskId -> (工具名 -> running 事件时间戳)，done/error 事件到达时计算差值并清理 */
    private final ConcurrentHashMap<Long, Map<String, Long>> stepStartTimes = new ConcurrentHashMap<>();

    /**
     * 创建任务（status=执行中），返回 taskId；落库失败由调用方决定是否继续问答。
     *
     * @param prompt LLM 实际输入（系统提示+问题，可观测性审计，允许超长自动截断）
     * @param model  LLM 模型名
     */
    public Long startTask(Long userId, String sessionId, Long kbId, String question, String prompt, String model) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setUserId(userId);
        task.setSessionId(sessionId);
        task.setKbId(kbId);
        task.setQuestion(question);
        task.setPrompt(truncate(prompt, 200_000));
        task.setModel(model);
        task.setStatus(STATUS_RUNNING);
        task.setToolCount(0);
        task.setStartMs(System.currentTimeMillis());
        task.setCreateTime(new Date());
        agentTaskMapper.insert(task);
        return task.getId();
    }

    /**
     * 记录一步工具调用轨迹（工具回调线程写入；每条 SSE tool 事件落一行，running/done 成对）。
     * running 事件缓存时间戳，done/error 事件到达时回填该步耗时 latency_ms。
     */
    public void recordStep(Long taskId, KbQueryTools.ToolEvent evt) {
        if (taskId == null || evt == null) return;
        long now = System.currentTimeMillis();
        AgentTaskStepEntity step = new AgentTaskStepEntity();
        step.setTaskId(taskId);
        step.setType(STEP_TYPE_TOOL_CALL);
        step.setToolName(evt.name());
        step.setStatus(evt.status());
        step.setArgs(evt.args());
        step.setResult(evt.result());
        step.setCreateTime(new Date());
        if (KbQueryTools.ToolEvent.STATUS_RUNNING.equals(evt.status())) {
            // 记录起点；同一工具多轮调用时覆盖为最近一次（耗时精度可接受）
            stepStartTimes.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>()).put(evt.name(), now);
        } else {
            Map<String, Long> starts = stepStartTimes.get(taskId);
            Long start = starts == null ? null : starts.remove(evt.name());
            if (start != null) {
                step.setLatencyMs(Math.max(0, now - start));
            }
        }
        agentTaskStepMapper.insert(step);
    }

    /**
     * 结束任务：更新最终回答/引用来源/状态/耗时/工具调用次数/token 用量。
     */
    public void finishTask(Long taskId, String answer, String sourcesJson, boolean success, String errorMsg,
                           Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        if (taskId == null) return;
        AgentTaskEntity existing = agentTaskMapper.selectById(taskId);
        if (existing == null) return;

        AgentTaskEntity update = new AgentTaskEntity();
        update.setId(taskId);
        update.setAnswer(answer);
        update.setSources(sourcesJson);
        update.setPromptTokens(promptTokens);
        update.setCompletionTokens(completionTokens);
        update.setTotalTokens(totalTokens);
        update.setStatus(success ? STATUS_SUCCESS : STATUS_FAILED);
        update.setErrorMsg(errorMsg);
        update.setFinishTime(new Date());
        if (existing.getStartMs() != null) {
            update.setCostMs(System.currentTimeMillis() - existing.getStartMs());
        }
        // toolCount = 实际完成的工具调用次数：running/done 成对落库，仅统计 done/error 事件，
        // 避免一次工具调用（running + done 两行 step）被计为 2 次
        long stepCount = agentTaskStepMapper.selectCount(Wrappers.<AgentTaskStepEntity>lambdaQuery()
                .eq(AgentTaskStepEntity::getTaskId, taskId)
                .in(AgentTaskStepEntity::getStatus,
                        KbQueryTools.ToolEvent.STATUS_DONE, KbQueryTools.ToolEvent.STATUS_ERROR));
        update.setToolCount(Math.toIntExact(stepCount));
        agentTaskMapper.updateById(update);
        // 任务结束，释放步骤耗时统计缓存
        stepStartTimes.remove(taskId);
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen);
    }

    // ==================== 查询接口支撑 ====================

    /**
     * 分页查询任务列表（创建时间倒序）。调用方负责数据权限过滤（非 ADMIN 传 userId）。
     */
    public List<AgentTaskEntity> listTasks(Long userId, Long kbId, Integer status, String keyword,
                                           int page, int size) {
        return agentTaskMapper.selectList(buildQuery(userId, kbId, status, keyword)
                .orderByDesc(AgentTaskEntity::getCreateTime)
                .last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size));
    }

    /** 任务总数（与 listTasks 同一套筛选条件） */
    public long countTasks(Long userId, Long kbId, Integer status, String keyword) {
        return agentTaskMapper.selectCount(buildQuery(userId, kbId, status, keyword));
    }

    /** 任务详情 */
    /**
     * 按用户 + 会话查询任务（id 升序，一次问答一条，与历史 assistant 消息一一对应）。
     * 用于 ChatSessionController 回补历史消息的引用来源（sources 快照）。
     */
    public List<AgentTaskEntity> listBySession(Long userId, String sessionId) {
        return agentTaskMapper.selectList(Wrappers.<AgentTaskEntity>lambdaQuery()
                .eq(userId != null, AgentTaskEntity::getUserId, userId)
                .eq(sessionId != null, AgentTaskEntity::getSessionId, sessionId)
                .orderByAsc(AgentTaskEntity::getId));
    }

    public AgentTaskEntity getTask(Long taskId) {
        return agentTaskMapper.selectById(taskId);
    }

    /** 任务的工具步骤轨迹（按 id 升序，还原执行顺序） */
    public List<AgentTaskStepEntity> listSteps(Long taskId) {
        return agentTaskStepMapper.selectList(Wrappers.<AgentTaskStepEntity>lambdaQuery()
                .eq(AgentTaskStepEntity::getTaskId, taskId)
                .orderByAsc(AgentTaskStepEntity::getId));
    }

    private LambdaQueryWrapper<AgentTaskEntity> buildQuery(Long userId, Long kbId, Integer status, String keyword) {
        return Wrappers.<AgentTaskEntity>lambdaQuery()
                .eq(userId != null, AgentTaskEntity::getUserId, userId)
                .eq(kbId != null, AgentTaskEntity::getKbId, kbId)
                .eq(status != null, AgentTaskEntity::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), AgentTaskEntity::getQuestion, keyword);
    }

    // ==================== 逻辑删除（删除会话级联） ====================

    /**
     * 逻辑删除某用户某会话下的全部任务及其步骤轨迹（删除会话时级联调用）。
     * 只将 deleted 置 1 并记录删除人/时间，数据保留供审计追溯；MyBatis-Plus 查询自动过滤已删数据。
     *
     * @return 受影响的任务数
     */
    public int logicalDeleteBySession(Long userId, String sessionId, Long operatorId) {
        List<AgentTaskEntity> tasks = agentTaskMapper.selectList(Wrappers.<AgentTaskEntity>lambdaQuery()
                .eq(AgentTaskEntity::getUserId, userId)
                .eq(AgentTaskEntity::getSessionId, sessionId));
        if (tasks.isEmpty()) {
            return 0;
        }
        List<Long> taskIds = tasks.stream().map(AgentTaskEntity::getId).toList();
        Date now = new Date();
        // 步骤轨迹：按 task_id 批量逻辑删除（与所属任务一致的删除人/时间）
        agentTaskStepMapper.update(null, Wrappers.<AgentTaskStepEntity>lambdaUpdate()
                .set(AgentTaskStepEntity::getDeleted, 1)
                .set(AgentTaskStepEntity::getDeletedBy, operatorId)
                .set(AgentTaskStepEntity::getDeleteTime, now)
                .in(AgentTaskStepEntity::getTaskId, taskIds));
        return agentTaskMapper.update(null, Wrappers.<AgentTaskEntity>lambdaUpdate()
                .set(AgentTaskEntity::getDeleted, 1)
                .set(AgentTaskEntity::getDeletedBy, operatorId)
                .set(AgentTaskEntity::getDeleteTime, now)
                .eq(AgentTaskEntity::getUserId, userId)
                .eq(AgentTaskEntity::getSessionId, sessionId));
    }
}
