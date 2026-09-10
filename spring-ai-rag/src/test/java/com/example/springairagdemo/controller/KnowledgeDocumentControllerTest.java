package com.example.springairagdemo.controller;

import com.example.springairagdemo.entity.KnowledgeBaseEntity;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskEntity;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskStatus;
import com.example.springairagdemo.security.ForbiddenException;
import com.example.springairagdemo.security.KbRole;
import com.example.springairagdemo.service.FileStorageService;
import com.example.springairagdemo.service.KbAuthorizationService;
import com.example.springairagdemo.service.KnowledgeBaseService;
import com.example.springairagdemo.service.KnowledgeDocumentEntityService;
import com.example.springairagdemo.service.KnowledgeDocumentService;
import com.example.springairagdemo.service.KnowledgeEmbeddingTaskService;
import com.example.springairagdemo.service.RagRetrievalService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeDocumentController} 单元测试（Mockito 纯单测，不启动 Spring 上下文）。
 *
 * <p>MyBatis-Plus 链式查询（lambdaQuery().eq().one() 等）通过
 * {@link Answers#RETURNS_DEEP_STUBS} 模拟；服务层依赖全部 mock，无数据库/Milvus/Redis 依赖。
 */
@DisplayName("KnowledgeDocumentController 单元测试")
class KnowledgeDocumentControllerTest {

    private static final Long KB_ID = 10L;
    private static final Long DOC_ID = 20L;

    @Mock
    private KnowledgeDocumentEntityService documentEntityService;
    @Mock
    private KnowledgeEmbeddingTaskService embeddingTaskService;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private KnowledgeDocumentService knowledgeDocumentService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private KbAuthorizationService kbAuthorizationService;

    private AutoCloseable mocks;
    private KnowledgeDocumentController controller;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        controller = new KnowledgeDocumentController(knowledgeDocumentService, documentEntityService,
                knowledgeBaseService, fileStorageService, kbAuthorizationService, embeddingTaskService);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    // ==================== 测试数据构造 ====================

    private KnowledgeDocumentEntity doc(Long id, Long kbId, String fileName, String filePath, Integer version) {
        KnowledgeDocumentEntity d = new KnowledgeDocumentEntity();
        d.setId(id);
        d.setKnowledgeId(kbId);
        d.setFileName(fileName);
        d.setFilePath(filePath);
        d.setFileType("pdf");
        d.setFileSize(1024L);
        d.setChunkCount(5);
        d.setStatus(3);
        d.setVersion(version == null ? 1 : version);
        d.setCreateTime(new Date());
        d.setUpdateTime(new Date());
        return d;
    }

    private KnowledgeEmbeddingTaskEntity task(Long id, String taskNo, Long documentId,
                                              KnowledgeEmbeddingTaskStatus status) {
        KnowledgeEmbeddingTaskEntity t = new KnowledgeEmbeddingTaskEntity();
        t.setId(id);
        t.setTaskNo(taskNo);
        t.setDocumentId(documentId);
        t.setStatus(status);
        t.setTotalChunk(10);
        t.setSuccessChunk(8);
        t.setFailChunk(2);
        t.setParseProgress(100);
        t.setSplitProgress(null);
        t.setChunkProgress(50);
        t.setEmbedProgress(null);
        t.setMilvusProgress(30);
        t.setRetryCount(1);
        t.setErrorMessage("boom");
        t.setCostTime(1000L);
        t.setStartTime(new Date());
        t.setFinishTime(new Date());
        t.setCreateTime(new Date());
        return t;
    }

    private KnowledgeBaseEntity kb(Long id, String name) {
        KnowledgeBaseEntity k = new KnowledgeBaseEntity();
        k.setId(id);
        k.setName(name);
        k.setDescription("desc-" + id);
        k.setStatus(1);
        return k;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    /**
     * 构造 MyBatis-Plus 链式查询 wrapper mock：所有链式方法返回自身（与链式 API 语义一致），
     * 由测试用例按需 stub 终结方法（one()/list()）。
     * 不用 RETURNS_DEEP_STUBS：其对 Func.eq/in 等泛型 Children 返回类型解析不稳定。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> LambdaQueryChainWrapper<T> mockChain() {
        LambdaQueryChainWrapper<T> wrapper = (LambdaQueryChainWrapper<T>) mock(LambdaQueryChainWrapper.class);
        when(wrapper.eq(any(SFunction.class), any())).thenReturn(wrapper);
        when(wrapper.like(any(SFunction.class), any())).thenReturn(wrapper);
        // in 有 Collection/varargs 两个重载，控制器传 List 时走 Collection 重载，都要 stub
        when(wrapper.in(any(SFunction.class), anyCollection())).thenReturn(wrapper);
        when(wrapper.in(any(SFunction.class), any(Object[].class))).thenReturn(wrapper);
        when(wrapper.orderByDesc(any(SFunction.class))).thenReturn(wrapper);
        when(wrapper.last(any(String.class))).thenReturn(wrapper);
        when(wrapper.and(any())).thenReturn(wrapper);
        return wrapper;
    }

    private KnowledgeDocumentService.TaskSubmitResult submitResult(String taskNo, int version) {
        return new KnowledgeDocumentService.TaskSubmitResult(1L, taskNo, DOC_ID, "a.pdf", version);
    }

    // ==================== upload ====================

    @Nested
    @DisplayName("POST /upload 上传")
    class Upload {

        @Test
        @DisplayName("空文件 → 400")
        void emptyFileReturns400() {
            MockMultipartFile file = new MockMultipartFile("file", new byte[0]);
            ResponseEntity<Map<String, Object>> resp = controller.upload(file, KB_ID);
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
            assertEquals(false, body(resp).get("success"));
        }

        @Test
        @DisplayName("非 PDF 扩展名 → 400")
        void nonPdfReturns400() {
            MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
            ResponseEntity<Map<String, Object>> resp = controller.upload(file, KB_ID);
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
            assertTrue(String.valueOf(body(resp).get("message")).contains("pdf"));
        }

        @Test
        @DisplayName("无扩展名（getFileExtension=null）→ 400")
        void noExtensionReturns400() {
            MockMultipartFile file = new MockMultipartFile("file", "noext", "application/pdf", "x".getBytes());
            ResponseEntity<Map<String, Object>> resp = controller.upload(file, KB_ID);
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        }

        @Test
        @DisplayName("大写扩展名 .PDF 可通过校验，version>1 → isUpdate=true")
        void successUppercaseExtension() throws IOException {
            MockMultipartFile file = new MockMultipartFile("file", "手册.PDF", "application/pdf", "pdf".getBytes());
            when(knowledgeDocumentService.submitIngest(any(), eq(KB_ID)))
                    .thenReturn(submitResult("T-001", 2));
            ResponseEntity<Map<String, Object>> resp = controller.upload(file, KB_ID);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(true, body(resp).get("success"));
            assertEquals("T-001", body(resp).get("taskNo"));
            assertEquals(DOC_ID, body(resp).get("documentId"));
            assertEquals(2, body(resp).get("version"));
            assertEquals(true, body(resp).get("isUpdate"));
            assertEquals(KB_ID, body(resp).get("knowledgeBaseId"));
        }

        @Test
        @DisplayName("submitIngest 抛 IOException → 500")
        void ioExceptionReturns500() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "pdf".getBytes());
            when(knowledgeDocumentService.submitIngest(any(), eq(KB_ID))).thenThrow(new IOException("磁盘满"));
            ResponseEntity<Map<String, Object>> resp = controller.upload(file, KB_ID);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
            assertTrue(String.valueOf(body(resp).get("message")).contains("磁盘满"));
        }

        @Test
        @DisplayName("submitIngest 抛 RuntimeException → 500")
        void runtimeExceptionReturns500() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "pdf".getBytes());
            when(knowledgeDocumentService.submitIngest(any(), eq(KB_ID)))
                    .thenThrow(new IllegalStateException("无权限"));
            ResponseEntity<Map<String, Object>> resp = controller.upload(file, KB_ID);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
            assertEquals("无权限", body(resp).get("message"));
        }
    }

    // ==================== GET /task/{taskNo} ====================

    @Nested
    @DisplayName("GET /task/{taskNo} 任务状态")
    class TaskStatus {

        @Test
        @DisplayName("任务不存在 → 404")
        void notFoundReturns404() {
            LambdaQueryChainWrapper<KnowledgeEmbeddingTaskEntity> wrapper = mockChain();
            when(embeddingTaskService.lambdaQuery()).thenReturn(wrapper);
            when(wrapper.one()).thenReturn(null);
            ResponseEntity<Map<String, Object>> resp = controller.taskStatus("no-such");
            assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        }

        @Test
        @DisplayName("任务存在 → 200，字段映射完整，null 进度兜底为 0")
        void foundMapsAllFields() {
            KnowledgeEmbeddingTaskEntity t = task(1L, "T-1", DOC_ID, KnowledgeEmbeddingTaskStatus.FAILED);
            LambdaQueryChainWrapper<KnowledgeEmbeddingTaskEntity> wrapper = mockChain();
            when(embeddingTaskService.lambdaQuery()).thenReturn(wrapper);
            when(wrapper.one()).thenReturn(t);
            ResponseEntity<Map<String, Object>> resp = controller.taskStatus("T-1");
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(true, body(resp).get("success"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) body(resp).get("data");
            assertEquals(1L, data.get("taskId"));
            assertEquals("T-1", data.get("taskNo"));
            assertEquals(DOC_ID, data.get("documentId"));
            assertEquals(3, data.get("status"));
            assertEquals("失败", data.get("statusText"));
            assertEquals(10, data.get("totalChunk"));
            assertEquals(8, data.get("successChunk"));
            assertEquals(2, data.get("failChunk"));
            // null 进度兜底为 0
            assertEquals(0, data.get("splitProgress"));
            assertEquals(0, data.get("embedProgress"));
            assertEquals(100, data.get("parseProgress"));
            assertEquals("boom", data.get("errorMessage"));
        }
    }

    // ==================== POST /task/{taskNo}/retry ====================

    @Nested
    @DisplayName("POST /task/{taskNo}/retry 重试任务")
    class RetryTask {

        private void stubTaskAndDoc() {
            KnowledgeEmbeddingTaskEntity t = task(1L, "T-1", DOC_ID, KnowledgeEmbeddingTaskStatus.FAILED);
            LambdaQueryChainWrapper<KnowledgeEmbeddingTaskEntity> wrapper = mockChain();
            when(embeddingTaskService.lambdaQuery()).thenReturn(wrapper);
            when(wrapper.one()).thenReturn(t);
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "a.pdf", "p/a.pdf", 1));
        }

        @Test
        @DisplayName("任务不存在 → 404")
        void taskNotFound404() {
            LambdaQueryChainWrapper<KnowledgeEmbeddingTaskEntity> wrapper = mockChain();
            when(embeddingTaskService.lambdaQuery()).thenReturn(wrapper);
            when(wrapper.one()).thenReturn(null);
            assertEquals(HttpStatus.NOT_FOUND, controller.retryTask("x").getStatusCode());
        }

        @Test
        @DisplayName("关联文档不存在 → 404")
        void docNotFound404() {
            KnowledgeEmbeddingTaskEntity t = task(1L, "T-1", DOC_ID, KnowledgeEmbeddingTaskStatus.FAILED);
            LambdaQueryChainWrapper<KnowledgeEmbeddingTaskEntity> wrapper = mockChain();
            when(embeddingTaskService.lambdaQuery()).thenReturn(wrapper);
            when(wrapper.one()).thenReturn(t);
            when(documentEntityService.getById(DOC_ID)).thenReturn(null);
            assertEquals(HttpStatus.NOT_FOUND, controller.retryTask("T-1").getStatusCode());
        }

        @Test
        @DisplayName("无 EDITOR 权限 → 403")
        void forbidden403() {
            stubTaskAndDoc();
            doThrow(new ForbiddenException("无权限")).when(kbAuthorizationService)
                    .assertRole(KB_ID, KbRole.EDITOR);
            ResponseEntity<Map<String, Object>> resp = controller.retryTask("T-1");
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
            assertEquals("无权限", body(resp).get("message"));
            verify(knowledgeDocumentService, never()).retryTask(any());
        }

        @Test
        @DisplayName("重试成功 → 200")
        void success200() {
            stubTaskAndDoc();
            doNothing().when(knowledgeDocumentService).retryTask(1L);
            ResponseEntity<Map<String, Object>> resp = controller.retryTask("T-1");
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(true, body(resp).get("success"));
            verify(knowledgeDocumentService).retryTask(1L);
        }

        @Test
        @DisplayName("业务冲突（IllegalStateException）→ 409")
        void conflict409() {
            stubTaskAndDoc();
            doThrow(new IllegalStateException("任务非失败状态")).when(knowledgeDocumentService).retryTask(1L);
            ResponseEntity<Map<String, Object>> resp = controller.retryTask("T-1");
            assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
            assertEquals("任务非失败状态", body(resp).get("message"));
        }

        @Test
        @DisplayName("其他异常 → 500")
        void otherException500() {
            stubTaskAndDoc();
            doThrow(new RuntimeException("MQ 不可用")).when(knowledgeDocumentService).retryTask(1L);
            ResponseEntity<Map<String, Object>> resp = controller.retryTask("T-1");
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        }
    }

    // ==================== GET /tasks 任务列表 ====================

    @Nested
    @DisplayName("GET /tasks 任务列表")
    class Tasks {

        @Test
        @DisplayName("用户无任何可见知识库 → 空结果且不查库")
        void noVisibleKbReturnsEmpty() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(List.of());
            ResponseEntity<Map<String, Object>> resp = controller.tasks(null, null, null);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(0, body(resp).get("total"));
            verify(embeddingTaskService, never()).list(any(Wrapper.class));
        }

        @Test
        @DisplayName("指定不可见知识库 → 空结果")
        void specifiedKbNotVisibleReturnsEmpty() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(List.of(99L));
            ResponseEntity<Map<String, Object>> resp = controller.tasks(KB_ID, null, null);
            assertEquals(0, body(resp).get("total"));
            verify(embeddingTaskService, never()).list(any(Wrapper.class));
        }

        @Test
        @DisplayName("ADMIN（visible=null）→ 返回全部映射结果")
        void adminSeesAll() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(null);
            when(embeddingTaskService.list(any(Wrapper.class)))
                    .thenReturn(List.of(task(1L, "T-1", DOC_ID, KnowledgeEmbeddingTaskStatus.PROCESSING)));
            when(documentEntityService.listByIds(anyCollection()))
                    .thenReturn(List.of(doc(DOC_ID, KB_ID, "a.pdf", "p/a.pdf", 3)));
            when(knowledgeBaseService.listByIds(anyCollection())).thenReturn(List.of(kb(KB_ID, "人事库")));

            ResponseEntity<Map<String, Object>> resp = controller.tasks(null, 1, null);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(1, body(resp).get("total"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) body(resp).get("data");
            assertEquals("T-1", data.get(0).get("taskNo"));
            assertEquals("a.pdf", data.get(0).get("documentName"));
            assertEquals("人事库", data.get(0).get("kbName"));
            assertEquals(3, data.get(0).get("version"));
            assertEquals(1, data.get(0).get("status"));
            assertEquals("处理中", data.get(0).get("statusText"));
            assertEquals(0, data.get(0).get("splitProgress"));
        }

        @Test
        @DisplayName("keyword 匹配任务号 OR 文档名 → 正常返回")
        void keywordSearch() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(null);
            LambdaQueryChainWrapper<KnowledgeDocumentEntity> docWrapper = mockChain();
            when(documentEntityService.lambdaQuery()).thenReturn(docWrapper);
            when(docWrapper.list()).thenReturn(List.of(doc(DOC_ID, KB_ID, "年假制度.pdf", "p/a.pdf", 1)));
            when(embeddingTaskService.list(any(Wrapper.class)))
                    .thenReturn(List.of(task(1L, "T-1", DOC_ID, KnowledgeEmbeddingTaskStatus.SUCCESS)));
            when(documentEntityService.listByIds(anyCollection()))
                    .thenReturn(List.of(doc(DOC_ID, KB_ID, "年假制度.pdf", "p/a.pdf", 1)));
            when(knowledgeBaseService.listByIds(anyCollection())).thenReturn(List.of(kb(KB_ID, "HR")));

            ResponseEntity<Map<String, Object>> resp = controller.tasks(null, null, "年假");
            assertEquals(1, body(resp).get("total"));
            verify(documentEntityService).lambdaQuery();
        }

        @Test
        @DisplayName("文档已被删除的任务被跳过")
        void deletedDocTaskSkipped() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(null);
            when(embeddingTaskService.list(any(Wrapper.class)))
                    .thenReturn(List.of(task(1L, "T-1", DOC_ID, KnowledgeEmbeddingTaskStatus.SUCCESS)));
            when(documentEntityService.listByIds(anyCollection())).thenReturn(List.of());
            when(knowledgeBaseService.listByIds(anyCollection())).thenReturn(List.of());

            ResponseEntity<Map<String, Object>> resp = controller.tasks(null, null, null);
            assertEquals(0, body(resp).get("total"));
        }

        @Test
        @DisplayName("非可见知识库下的任务被过滤（数据源头防泄露）")
        void hiddenKbFiltered() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(List.of(99L));
            when(embeddingTaskService.list(any(Wrapper.class)))
                    .thenReturn(List.of(task(1L, "T-1", DOC_ID, KnowledgeEmbeddingTaskStatus.SUCCESS)));
            when(documentEntityService.listByIds(anyCollection()))
                    .thenReturn(List.of(doc(DOC_ID, KB_ID, "a.pdf", "p/a.pdf", 1)));
            when(knowledgeBaseService.listByIds(anyCollection())).thenReturn(List.of());

            ResponseEntity<Map<String, Object>> resp = controller.tasks(null, null, null);
            assertEquals(0, body(resp).get("total"));
        }
    }

    // ==================== POST /chat/clear-memory ====================

    @Nested
    @DisplayName("POST /chat/clear-memory 清空会话记忆")
    class ClearChatMemory {

        @Test
        @DisplayName("无 sessionId → clearMemory(null)")
        void noSessionId() {
            ResponseEntity<Map<String, Object>> resp = controller.clearChatMemory(new HashMap<>());
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            verify(knowledgeDocumentService).clearMemory(null);
        }

        @Test
        @DisplayName("sessionId 去空白")
        void trimsSessionId() {
            Map<String, Object> req = Map.of("sessionId", "  abc  ");
            controller.clearChatMemory(new HashMap<>(req));
            verify(knowledgeDocumentService).clearMemory("abc");
        }

        @Test
        @DisplayName("超长 sessionId 截断到 128")
        void truncatesSessionId() {
            Map<String, Object> req = Map.of("sessionId", "x".repeat(130));
            controller.clearChatMemory(new HashMap<>(req));
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(knowledgeDocumentService).clearMemory(captor.capture());
            assertEquals(128, captor.getValue().length());
        }
    }

    // ==================== POST /chat 问答 ====================

    @Nested
    @DisplayName("POST /chat 问答")
    class Chat {

        private Map<String, Object> chatReq(Object kbId, String question, Object sessionId, Object stream) {
            Map<String, Object> req = new HashMap<>();
            if (question != null) {
                req.put("question", question);
            }
            if (kbId != null) {
                req.put("knowledgeBaseId", kbId);
            }
            if (sessionId != null) {
                req.put("sessionId", sessionId);
            }
            if (stream != null) {
                req.put("stream", stream);
            }
            return req;
        }

        @Test
        @DisplayName("问题为空 → 400")
        void blankQuestion400() {
            Object resp = controller.chat(chatReq(KB_ID, "  ", null, null));
            ResponseEntity<?> r = (ResponseEntity<?>) resp;
            assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        }

        @Test
        @DisplayName("知识库 ID 缺失 → 400")
        void missingKbId400() {
            Object resp = controller.chat(chatReq(null, "什么是年假", null, null));
            assertEquals(HttpStatus.BAD_REQUEST, ((ResponseEntity<?>) resp).getStatusCode());
        }

        @Test
        @DisplayName("知识库 ID 非数字 → 400")
        void badKbIdFormat400() {
            Object resp = controller.chat(chatReq("abc", "问", null, null));
            assertEquals(HttpStatus.BAD_REQUEST, ((ResponseEntity<?>) resp).getStatusCode());
        }

        @Test
        @DisplayName("字符串数字知识库 ID + stream=false → 同步返回 answer/sources")
        void syncChatMapsSources() {
            KnowledgeDocumentService.SourceInfo src =
                    new KnowledgeDocumentService.SourceInfo(DOC_ID, "年假.pdf", 2, "年假 5 天", 1);
            when(knowledgeDocumentService.chat(eq("什么是年假"), eq(KB_ID), eq("s1")))
                    .thenReturn(new KnowledgeDocumentService.ChatResult("年假 5 天[来源1]", List.of(src)));

            ResponseEntity<?> resp = (ResponseEntity<?>) controller.chat(chatReq("10", "什么是年假", "s1", false));
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertEquals("年假 5 天[来源1]", body.get("answer"));
            assertEquals(KB_ID, body.get("knowledgeBaseId"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) body.get("sources");
            assertEquals(1, sources.size());
            assertEquals(DOC_ID, sources.get(0).get("documentId"));
            assertEquals("年假.pdf", sources.get(0).get("documentName"));
            assertEquals(2, sources.get(0).get("pageNo"));
            assertEquals(1, sources.get(0).get("refIndex"));
        }

        @Test
        @DisplayName("同步问答异常 → 500")
        void syncChatException500() {
            when(knowledgeDocumentService.chat(any(), any(), any()))
                    .thenThrow(new RuntimeException("AI 不可用"));
            Object resp = controller.chat(chatReq(KB_ID, "问", null, false));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ((ResponseEntity<?>) resp).getStatusCode());
        }

        @Test
        @DisplayName("sessionId 超长截断到 128（同步路径验证）")
        void sessionIdTruncatedTo128() {
            when(knowledgeDocumentService.chat(any(), any(), any()))
                    .thenReturn(new KnowledgeDocumentService.ChatResult("答", List.of()));
            controller.chat(chatReq(KB_ID, "问", "y".repeat(130), false));
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(knowledgeDocumentService).chat(any(), any(), captor.capture());
            assertEquals(128, captor.getValue().length());
        }

        @Test
        @DisplayName("流式默认 SSE：delta → final → sources → done 事件序列")
        void streamSseEventSequence() throws Exception {
            KnowledgeDocumentService.ChatStreamResult.AnswerContext ctx =
                    new KnowledgeDocumentService.ChatStreamResult.AnswerContext(
                            "年假 5 天[来源1]",
                            List.of(new KnowledgeDocumentService.SourceInfo(DOC_ID, "a.pdf", 1, "片段", 1)));
            KnowledgeDocumentService.ChatStreamResult result = new KnowledgeDocumentService.ChatStreamResult(
                    Flux.just("年", "假"),
                    Mono.just(ctx),
                    Sinks.many().unicast().onBackpressureBuffer());
            when(knowledgeDocumentService.chatStream(eq("什么是年假"), eq(KB_ID), eq(null)))
                    .thenReturn(result);

            @SuppressWarnings("unchecked")
            ResponseEntity<Flux<ServerSentEvent<Map<String, Object>>>> resp =
                    (ResponseEntity<Flux<ServerSentEvent<Map<String, Object>>>>) (ResponseEntity<?>) controller.chat(chatReq(KB_ID, "什么是年假", null, null));
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(MediaType.TEXT_EVENT_STREAM, resp.getHeaders().getContentType());

            List<Map<String, Object>> events = resp.getBody()
                    .map(ServerSentEvent::data)
                    .collectList()
                    .block(java.time.Duration.ofSeconds(5));
            assertNotNull(events);
            List<String> types = events.stream().map(e -> String.valueOf(e.get("type")))
                    .collect(Collectors.toList());
            assertEquals(List.of("delta", "delta", "final", "sources", "done"), types);
            assertEquals("年", events.get(0).get("content"));
            assertEquals("年假 5 天[来源1]", events.get(2).get("content"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) events.get(3).get("sources");
            assertEquals(1, sources.size());
        }

        @Test
        @DisplayName("final 无 [来源N] 引用 → sources 事件下发空列表")
        void streamNoSourceRefsEmptySources() throws Exception {
            KnowledgeDocumentService.ChatStreamResult.AnswerContext ctx =
                    new KnowledgeDocumentService.ChatStreamResult.AnswerContext(
                            "抱歉，知识库中没有相关信息", List.of());
            KnowledgeDocumentService.ChatStreamResult result = new KnowledgeDocumentService.ChatStreamResult(
                    Flux.just("抱歉"),
                    Mono.just(ctx),
                    Sinks.many().unicast().onBackpressureBuffer());
            when(knowledgeDocumentService.chatStream(any(), any(), any())).thenReturn(result);

            @SuppressWarnings("unchecked")
            ResponseEntity<Flux<ServerSentEvent<Map<String, Object>>>> resp =
                    (ResponseEntity<Flux<ServerSentEvent<Map<String, Object>>>>) (ResponseEntity<?>) controller.chat(chatReq(KB_ID, "问", null, null));
            List<Map<String, Object>> events = resp.getBody()
                    .map(ServerSentEvent::data)
                    .collectList()
                    .block(java.time.Duration.ofSeconds(5));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) events.get(2).get("sources");
            assertNotNull(sources);
            assertTrue(sources.isEmpty());
        }

        @Test
        @DisplayName("工具调用事件映射为 SSE tool 事件（args/result null 兜底空串）")
        void streamToolEventMapped() throws Exception {
            Sinks.Many<RagRetrievalService.ToolEvent> sink =
                    Sinks.many().unicast().onBackpressureBuffer();
            KnowledgeDocumentService.ChatStreamResult result = new KnowledgeDocumentService.ChatStreamResult(
                    Flux.just("答"),
                    Mono.just(new KnowledgeDocumentService.ChatStreamResult.AnswerContext("答[来源1]", List.of())),
                    sink);
            when(knowledgeDocumentService.chatStream(any(), any(), any())).thenReturn(result);

            @SuppressWarnings("unchecked")
            ResponseEntity<Flux<ServerSentEvent<Map<String, Object>>>> resp =
                    (ResponseEntity<Flux<ServerSentEvent<Map<String, Object>>>>) (ResponseEntity<?>) controller.chat(chatReq(KB_ID, "问", null, null));
            // 订阅前先发一个工具事件（unicast sink 缓冲）
            sink.tryEmitNext(new RagRetrievalService.ToolEvent(
                    "searchKnowledge", RagRetrievalService.ToolEvent.STATUS_RUNNING, "查年假", null));

            List<Map<String, Object>> events = resp.getBody()
                    .map(ServerSentEvent::data)
                    .collectList()
                    .block(java.time.Duration.ofSeconds(5));
            Map<String, Object> toolEvent = events.stream()
                    .filter(e -> "tool".equals(e.get("type")))
                    .findFirst().orElse(null);
            assertNotNull(toolEvent, "应包含 tool 类型事件");
            assertEquals("searchKnowledge", toolEvent.get("name"));
            assertEquals("running", toolEvent.get("status"));
            assertEquals("查年假", toolEvent.get("args"));
            assertEquals("", toolEvent.get("result"));
        }
    }

    // ==================== GET /{id}/download ====================

    @Nested
    @DisplayName("GET /{id}/download 下载")
    class Download {

        @Test
        @DisplayName("文档不存在 → 404")
        void docNotFound404() {
            when(documentEntityService.getById(DOC_ID)).thenReturn(null);
            assertEquals(HttpStatus.NOT_FOUND, controller.download(DOC_ID).getStatusCode());
        }

        @Test
        @DisplayName("无 VIEWER 权限 → 403")
        void forbidden403() {
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "a.pdf", "p/a.pdf", 1));
            doThrow(new ForbiddenException("无权限")).when(kbAuthorizationService)
                    .assertRole(KB_ID, KbRole.VIEWER);
            assertEquals(HttpStatus.FORBIDDEN, controller.download(DOC_ID).getStatusCode());
        }

        @Test
        @DisplayName("文件路径未记录 → 404")
        void filePathBlank404() {
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "a.pdf", "  ", 1));
            assertEquals(HttpStatus.NOT_FOUND, controller.download(DOC_ID).getStatusCode());
        }

        @Test
        @DisplayName("存储中文件不存在 → 404")
        void fileNotExists404() {
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "a.pdf", "p/a.pdf", 1));
            when(fileStorageService.exists("p/a.pdf")).thenReturn(false);
            assertEquals(HttpStatus.NOT_FOUND, controller.download(DOC_ID).getStatusCode());
        }

        @Test
        @DisplayName("下载成功 → 200，PDF Content-Type + attachment 响应头")
        void success200() throws Exception {
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "手册.pdf", "p/a.pdf", 1));
            when(fileStorageService.exists("p/a.pdf")).thenReturn(true);
            when(fileStorageService.getInputStream("p/a.pdf"))
                    .thenReturn(new ByteArrayInputStream("%PDF-1.4".getBytes()));
            ResponseEntity<?> resp = controller.download(DOC_ID);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(MediaType.APPLICATION_PDF, resp.getHeaders().getContentType());
            String disposition = resp.getHeaders().getFirst("Content-Disposition");
            assertNotNull(disposition);
            assertTrue(disposition.contains("attachment"));
        }

        @Test
        @DisplayName("非 PDF 扩展名 → application/octet-stream")
        void nonPdfOctetStream() throws Exception {
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "a.txt", "p/a.txt", 1));
            when(fileStorageService.exists("p/a.txt")).thenReturn(true);
            when(fileStorageService.getInputStream("p/a.txt"))
                    .thenReturn(new ByteArrayInputStream("x".getBytes()));
            ResponseEntity<?> resp = controller.download(DOC_ID);
            assertEquals(MediaType.APPLICATION_OCTET_STREAM, resp.getHeaders().getContentType());
        }

        @Test
        @DisplayName("存储读取异常 → 500")
        void storageError500() throws Exception {
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "a.pdf", "p/a.pdf", 1));
            when(fileStorageService.exists("p/a.pdf")).thenReturn(true);
            when(fileStorageService.getInputStream("p/a.pdf")).thenThrow(new RuntimeException("MinIO 挂了"));
            ResponseEntity<?> resp = controller.download(DOC_ID);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertTrue(String.valueOf(body.get("message")).contains("MinIO 挂了"));
        }
    }

    // ==================== GET /list 文档列表 ====================

    @Nested
    @DisplayName("GET /list 文档列表")
    class DocumentList {

        @Test
        @DisplayName("ADMIN（visible=null）→ 返回全部文档")
        void adminSeesAll() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(null);
            when(documentEntityService.list(any(Wrapper.class)))
                    .thenReturn(List.of(doc(DOC_ID, KB_ID, "年假.pdf", "p/a.pdf", 2)));
            ResponseEntity<Map<String, Object>> resp = controller.list(null, null);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(1, body(resp).get("total"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) body(resp).get("data");
            assertEquals("年假.pdf", data.get(0).get("fileName"));
            assertEquals(2, data.get(0).get("version"));
            assertEquals(3, data.get(0).get("status"));
            assertNotNull(data.get(0).get("statusText"));
        }

        @Test
        @DisplayName("指定不可见知识库 → 空结果且不查库")
        void specifiedKbNotVisibleEmpty() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(List.of(99L));
            ResponseEntity<Map<String, Object>> resp = controller.list(KB_ID, null);
            assertEquals(0, body(resp).get("total"));
            verify(documentEntityService, never()).list(any(Wrapper.class));
        }

        @Test
        @DisplayName("无可见知识库且未指定 → 空结果")
        void noVisibleKbEmpty() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(List.of());
            ResponseEntity<Map<String, Object>> resp = controller.list(null, null);
            assertEquals(0, body(resp).get("total"));
            verify(documentEntityService, never()).list(any(Wrapper.class));
        }

        @Test
        @DisplayName("keyword 过滤正常返回")
        void keywordFilter() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(List.of(KB_ID));
            when(documentEntityService.list(any(Wrapper.class)))
                    .thenReturn(List.of(doc(DOC_ID, KB_ID, "年假.pdf", "p/a.pdf", 1)));
            ResponseEntity<Map<String, Object>> resp = controller.list(KB_ID, "年假");
            assertEquals(1, body(resp).get("total"));
        }
    }

    // ==================== GET /knowledge-bases ====================

    @Nested
    @DisplayName("GET /knowledge-bases 可见知识库下拉")
    class KnowledgeBases {

        @Test
        @DisplayName("ADMIN（visible=null）→ 全部启用知识库")
        void adminAllActive() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(null);
            LambdaQueryChainWrapper<KnowledgeBaseEntity> wrapper = mockChain();
            when(knowledgeBaseService.lambdaQuery()).thenReturn(wrapper);
            when(wrapper.list()).thenReturn(List.of(kb(KB_ID, "人事库")));
            ResponseEntity<Map<String, Object>> resp = controller.knowledgeBases();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) body(resp).get("data");
            assertEquals(1, data.size());
            assertEquals("人事库", data.get(0).get("name"));
        }

        @Test
        @DisplayName("无可见知识库 → 空数据，不查库")
        void emptyVisibleNoQuery() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(List.of());
            ResponseEntity<Map<String, Object>> resp = controller.knowledgeBases();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) body(resp).get("data");
            assertTrue(data.isEmpty());
            verify(knowledgeBaseService, never()).list(any(Wrapper.class));
        }

        @Test
        @DisplayName("有可见知识库 → in 过滤查询")
        void visibleFiltered() {
            when(kbAuthorizationService.visibleKbIds()).thenReturn(List.of(KB_ID));
            LambdaQueryChainWrapper<KnowledgeBaseEntity> wrapper = mockChain();
            when(knowledgeBaseService.lambdaQuery()).thenReturn(wrapper);
            when(wrapper.list()).thenReturn(List.of(kb(KB_ID, "HR")));
            ResponseEntity<Map<String, Object>> resp = controller.knowledgeBases();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) body(resp).get("data");
            assertEquals(1, data.size());
            assertEquals(KB_ID, data.get(0).get("id"));
        }
    }

    // ==================== DELETE /{id} ====================

    @Nested
    @DisplayName("DELETE /{id} 删除文档")
    class Delete {

        @Test
        @DisplayName("文档不存在 → 404")
        void docNotFound404() {
            when(documentEntityService.getById(DOC_ID)).thenReturn(null);
            assertEquals(HttpStatus.NOT_FOUND, controller.delete(DOC_ID).getStatusCode());
        }

        @Test
        @DisplayName("无 EDITOR 权限 → 403，且不执行删除")
        void forbidden403() {
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "a.pdf", "p/a.pdf", 1));
            doThrow(new ForbiddenException("无权限")).when(kbAuthorizationService)
                    .assertRole(KB_ID, KbRole.EDITOR);
            assertEquals(HttpStatus.FORBIDDEN, controller.delete(DOC_ID).getStatusCode());
            verify(knowledgeDocumentService, never()).deleteDocument(any());
        }

        @Test
        @DisplayName("删除成功 → 200，并落审计日志")
        void success200AndAudited() {
            KnowledgeDocumentEntity d = doc(DOC_ID, KB_ID, "年假.pdf", "p/a.pdf", 1);
            when(documentEntityService.getById(DOC_ID)).thenReturn(d);
            doNothing().when(knowledgeDocumentService).deleteDocument(DOC_ID);
            ResponseEntity<Map<String, Object>> resp = controller.delete(DOC_ID);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(true, body(resp).get("success"));
            verify(kbAuthorizationService).audit(eq("DELETE_DOC"), eq(KB_ID), eq(DOC_ID), any());
        }

        @Test
        @DisplayName("业务冲突（有进行中任务）→ 409")
        void conflict409() {
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "a.pdf", "p/a.pdf", 1));
            doThrow(new IllegalStateException("仍有处理中任务")).when(knowledgeDocumentService).deleteDocument(DOC_ID);
            ResponseEntity<Map<String, Object>> resp = controller.delete(DOC_ID);
            assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
            assertEquals("仍有处理中任务", body(resp).get("message"));
        }

        @Test
        @DisplayName("其他异常 → 500")
        void otherException500() {
            when(documentEntityService.getById(DOC_ID)).thenReturn(doc(DOC_ID, KB_ID, "a.pdf", "p/a.pdf", 1));
            doThrow(new RuntimeException("Milvus 不可用")).when(knowledgeDocumentService).deleteDocument(DOC_ID);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, controller.delete(DOC_ID).getStatusCode());
        }
    }
}
