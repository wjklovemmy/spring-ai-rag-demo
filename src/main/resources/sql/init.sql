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

    status TINYINT NOT NULL DEFAULT 0 COMMENT '0上传中 1解析中 2Embedding中 3成功 4失败',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_knowledge_id (knowledge_id),

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

ALTER TABLE knowledge_document
  ADD COLUMN version     INT        DEFAULT 1   COMMENT '文档版本号，同名多次上传递增',
  ADD COLUMN expire_time DATETIME   DEFAULT NULL COMMENT '过期时间，旧版本平滑下线用',
  ADD COLUMN is_active   TINYINT    DEFAULT 1   COMMENT '是否启用：1-启用 0-禁用';



