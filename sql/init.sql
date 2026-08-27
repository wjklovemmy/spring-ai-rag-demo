CREATE TABLE IF NOT EXISTS knowledge_base (
                                              id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
                                              name VARCHAR(100) NOT NULL COMMENT '知识库名称',
    description VARCHAR(500) DEFAULT NULL COMMENT '知识库描述',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    create_user BIGINT DEFAULT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_name (name)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库';

CREATE TABLE IF NOT EXISTS knowledge_document (
                                                  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',

                                                  knowledge_id BIGINT NOT NULL COMMENT '所属知识库',

                                                  file_name VARCHAR(255) NOT NULL COMMENT '文件名称',

    file_path VARCHAR(500) NOT NULL COMMENT '文件路径',

    file_size BIGINT DEFAULT 0 COMMENT '文件大小(Byte)',

    file_type VARCHAR(20) NOT NULL COMMENT '文件类型',

    chunk_count INT DEFAULT 0 COMMENT 'Chunk数量',

    embedding_model VARCHAR(100) DEFAULT NULL COMMENT 'Embedding模型',

    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0上传中 1解析中 2向量化中 3成功 4失败 5已废弃(被新版顶替) 6已过期',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    version INT DEFAULT 1 COMMENT '文档版本号，同名多次上传递增',

    expire_time DATETIME DEFAULT NULL COMMENT '过期时间，旧版本平滑下线用',

    is_active TINYINT DEFAULT 1 COMMENT '是否启用：1-启用 0-禁用',

    INDEX idx_knowledge_id (knowledge_id),

    -- 同名文档并发上传防重号：同一知识库下 (库, 文件名, 版本) 唯一
    UNIQUE KEY uk_kb_file_version (knowledge_id, file_name, version),

    CONSTRAINT fk_document_base
    FOREIGN KEY (knowledge_id)
    REFERENCES knowledge_base(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档';

CREATE TABLE IF NOT EXISTS knowledge_chunk (

                                               id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',

                                               document_id BIGINT NOT NULL COMMENT '所属文档',

                                               chunk_index INT NOT NULL COMMENT 'Chunk序号',

                                               content LONGTEXT NOT NULL COMMENT 'Chunk内容',

                                               content_hash CHAR(64) DEFAULT NULL COMMENT 'Chunk内容Hash',

    token_count INT DEFAULT 0 COMMENT 'Token数量',

    page_no INT DEFAULT NULL COMMENT 'PDF页码',

    milvus_id BIGINT DEFAULT NULL COMMENT 'Milvus主键',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    INDEX idx_document_id (document_id),

    INDEX idx_hash (content_hash),

    -- 同一文档内 chunk 序号唯一：防止任务被并发/重复处理时产生重复 chunk
    UNIQUE KEY uk_document_index (document_id, chunk_index),

    CONSTRAINT fk_chunk_document
    FOREIGN KEY (document_id)
    REFERENCES knowledge_document(id)

    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识Chunk';

CREATE TABLE IF NOT EXISTS knowledge_embedding_task (

                                                        id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',

                                                        task_no VARCHAR(64) NOT NULL COMMENT '任务编号',

    document_id BIGINT NOT NULL COMMENT '文档ID',

    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2成功 3失败',

    total_chunk INT DEFAULT 0 COMMENT 'Chunk总数',

    success_chunk INT DEFAULT 0 COMMENT '成功Chunk数',

    fail_chunk INT DEFAULT 0 COMMENT '失败Chunk数',

    parse_progress TINYINT DEFAULT 0 COMMENT '阶段进度-PDF解析(0-100)',

    split_progress TINYINT DEFAULT 0 COMMENT '阶段进度-文本切片(0-100)',

    chunk_progress TINYINT DEFAULT 0 COMMENT '阶段进度-Chunk入库MySQL(0-100)',

    embed_progress TINYINT DEFAULT 0 COMMENT '阶段进度-Embedding向量化(0-100)',

    milvus_progress TINYINT DEFAULT 0 COMMENT '阶段进度-Milvus写入(0-100)',

    retry_count INT DEFAULT 0 COMMENT '重试次数',

    error_message VARCHAR(2000) DEFAULT NULL COMMENT '失败原因',

    start_time DATETIME DEFAULT NULL COMMENT '开始时间',

    finish_time DATETIME DEFAULT NULL COMMENT '结束时间',

    cost_time BIGINT DEFAULT NULL COMMENT '耗时(ms)',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_task_no (task_no),

    INDEX idx_document_id (document_id),

    INDEX idx_status (status),

    CONSTRAINT fk_task_document
    FOREIGN KEY (document_id)
    REFERENCES knowledge_document(id)

    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Embedding任务';


-- ============================================================
-- RAG 数据授权（水平/数据权限）与安全审计
-- 执行环境：知识库所在 MySQL（knowledge_base 库）
-- 说明：
--   1. 用户表（sys_user）与全局角色（sys_role / sys_permission /
--      sys_user_role / sys_role_permission）已随用户域迁移至独立库
--      spring_ai_user，建表与种子见 sql/user.sql；
--      此处 kb_member / kb_access_log 中的 user_id 为跨库逻辑引用（无外键约束）
--   2. kb_member    —— 知识库成员授权（水平/数据权限），唯一权威
--   3. kb_access_log —— 安全审计日志（含越权拒绝）
--   角色语义：OWNER(可授权/删除知识库) > EDITOR(可上传/删除文档) > VIEWER(可问答/检索)
-- ============================================================

-- 知识库成员授权（数据权限核心）
CREATE TABLE IF NOT EXISTS `kb_member` (
                                           `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
                                           `kb_id`       BIGINT      NOT NULL COMMENT '知识库 ID',
                                           `user_id`     BIGINT      NOT NULL COMMENT '用户 ID',
                                           `role`        VARCHAR(20) NOT NULL COMMENT '角色：OWNER / EDITOR / VIEWER',
    `grant_user`  BIGINT COMMENT '授权人（用户 ID）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_kb_user` (`kb_id`, `user_id`),
    KEY `idx_user` (`user_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库成员授权表';

-- 安全审计日志（含越权拒绝）
CREATE TABLE IF NOT EXISTS `kb_access_log` (
                                               `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
                                               `user_id`     BIGINT COMMENT '操作人用户 ID',
                                               `username`    VARCHAR(100) COMMENT '操作人用户名',
    `action`      VARCHAR(50)  NOT NULL COMMENT '操作类型：CREATE_KB / DELETE_KB / GRANT / REVOKE / UPLOAD_DOC / DELETE_DOC / QUERY / ACCESS_DENIED',
    `kb_id`       BIGINT COMMENT '知识库 ID',
    `document_id` BIGINT COMMENT '文档 ID',
    `ip`          VARCHAR(64) COMMENT '来源 IP',
    `detail`      VARCHAR(500) COMMENT '详情',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_kb` (`kb_id`),
    KEY `idx_user` (`user_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库安全审计日志';

-- ============================================================
-- 聊天会话（多轮对话会话管理）
-- 说明：
--   1. session_id 由后端生成（UUID），作为 Redis 记忆 key 的后缀
--      （rag:chat:memory:{userId}:{sessionId}），与 KnowledgeDocumentService 拼装规则一致
--   2. 消息历史仍存 Redis（ChatMemory，TTL 7 天）；本表仅存会话元数据
--      （标题 / 关联知识库 / 时间），支撑前端会话列表、切换、删除
-- ============================================================
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`           BIGINT      NOT NULL COMMENT '所属用户 ID',
    `session_id`        VARCHAR(64) NOT NULL COMMENT '会话标识（后端生成 UUID，Redis 记忆 key 后缀）',
    `title`             VARCHAR(100) DEFAULT '' COMMENT '会话标题（取首个问题截断）',
    `knowledge_base_id` BIGINT      DEFAULT NULL COMMENT '会话关联知识库 ID',
    `create_time`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 已删除（删除会话时置 1，MyBatis-Plus 自动过滤）',
    `deleted_by`        BIGINT      DEFAULT NULL COMMENT '删除人用户 ID',
    `delete_time`       DATETIME    DEFAULT NULL COMMENT '删除时间',
    UNIQUE KEY `uk_user_session` (`user_id`, `session_id`),
    KEY `idx_user_update` (`user_id`, `update_time`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天会话';

-- ============================================================
-- Agent 任务（一次提问的执行审计单元）
-- 说明：
--   1. 一次提问 = 一条 agent_task，记录问题/最终回答/状态/耗时/工具调用次数，
--      支撑 Agent 执行审计、失败排查、耗时统计
--   2. 工具调用过程（模型自主调用 KbQueryTools 的每一步）落在 agent_task_step，
--      按 task_id + id 顺序还原推理轨迹
-- ============================================================
CREATE TABLE IF NOT EXISTS `agent_task` (
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`          BIGINT       NOT NULL COMMENT '发起用户 ID',
    `session_id`       VARCHAR(64)  DEFAULT NULL COMMENT '会话 ID（chat_session.session_id）',
    `kb_id`            BIGINT       DEFAULT NULL COMMENT '知识库 ID',
    `question`         VARCHAR(2000) NOT NULL COMMENT '用户问题',
    `answer`           LONGTEXT     DEFAULT NULL COMMENT '最终回答（引用对齐后全文）',
    `sources`          LONGTEXT     DEFAULT NULL COMMENT '引用来源快照（JSON 数组）',
    `prompt`           LONGTEXT     DEFAULT NULL COMMENT 'LLM 实际输入（系统提示+问题，可观测性审计）',
    `model`            VARCHAR(100) DEFAULT NULL COMMENT 'LLM 模型名',
    `prompt_tokens`    INT          DEFAULT NULL COMMENT '输入 token 数',
    `completion_tokens` INT         DEFAULT NULL COMMENT '输出 token 数',
    `total_tokens`     INT          DEFAULT NULL COMMENT '总 token 数',
    `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 执行中 / 1 成功 / 2 失败',
    `tool_count`       INT          NOT NULL DEFAULT 0 COMMENT '工具调用次数（running/done 成对落库，仅统计 done/error 事件，一次调用记 1）',
    `cost_ms`          BIGINT       DEFAULT NULL COMMENT '总耗时（毫秒）',
    `error_msg`        VARCHAR(2000) DEFAULT NULL COMMENT '失败原因',
    `start_ms`         BIGINT       NOT NULL COMMENT '开始时间戳（毫秒）',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    `finish_time`      DATETIME     DEFAULT NULL COMMENT '结束时间',
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 已删除（随所属会话删除置 1，MyBatis-Plus 自动过滤）',
    `deleted_by`       BIGINT       DEFAULT NULL COMMENT '删除人用户 ID',
    `delete_time`      DATETIME     DEFAULT NULL COMMENT '删除时间',
    KEY `idx_user_time` (`user_id`, `create_time`),
    KEY `idx_session` (`session_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 任务';

-- ============================================================
-- Agent 任务步骤轨迹（工具调用过程）
-- ============================================================
CREATE TABLE IF NOT EXISTS `agent_task_step` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`     BIGINT       NOT NULL COMMENT '任务 ID（agent_task.id）',
    `type`        VARCHAR(20)  NOT NULL DEFAULT 'TOOL_CALL' COMMENT '步骤类型：TOOL_CALL',
    `tool_name`   VARCHAR(100) DEFAULT NULL COMMENT '工具名',
    `status`      VARCHAR(20)  NOT NULL COMMENT '状态：running / done / error',
    `args`        TEXT         DEFAULT NULL COMMENT '工具入参摘要',
    `result`      TEXT         DEFAULT NULL COMMENT '工具返回结果',
    `latency_ms`  BIGINT       DEFAULT NULL COMMENT '该步耗时（毫秒，done 时回填）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 已删除（随所属任务删除置 1，MyBatis-Plus 自动过滤）',
    `deleted_by`  BIGINT       DEFAULT NULL COMMENT '删除人用户 ID',
    `delete_time` DATETIME     DEFAULT NULL COMMENT '删除时间',
    KEY `idx_task` (`task_id`, `id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 任务步骤轨迹';

-- 内置 ADMIN 角色 / 权限种子 / admin 管理员账号及其绑定关系，
-- 已全部迁移至用户域独立库脚本 sql/user.sql（应用启动时 UserDataInitializer 也会自动补齐）。

