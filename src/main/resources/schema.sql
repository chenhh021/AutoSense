-- AutoSense 数据库 schema(data-model.md §1~§10,2026-09-04 刷新)
-- 全部对话数据长期保留,不设清理任务(FR-013,2026-08-22 修订)
CREATE TABLE IF NOT EXISTS device (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    sn CHAR(13) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(128) NOT NULL,
    simulator_name VARCHAR(128) NOT NULL,
    simulator_device_id BIGINT NOT NULL,
    device_type_code VARCHAR(32) NOT NULL,
    device_type_id BIGINT NOT NULL,
    device_model_code VARCHAR(32) NOT NULL,
    device_model_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_device_sn (sn),
    INDEX idx_device_user (user_id),
    INDEX idx_device_type_model (device_type_code, device_model_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS repair_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    -- FIXED / UNFIXED_MANUAL_GUIDE / UNFIXED_AFTERSALES / DEVICE_UNREACHABLE /
    -- ANSWERED(常识直答) / AFTERSALES_PROVIDED(独立网点查询)
    conclusion_type VARCHAR(32) NULL,
    conclusion TEXT NULL,
    conclusion_extra JSON NULL,
    processing_message_id BIGINT NULL,
    processing_deadline_at DATETIME NULL,
    active_workflow_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_user (user_id),
    INDEX idx_session_device (device_id),
    INDEX idx_session_created (created_at),
    INDEX idx_session_active_workflow (active_workflow_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS problem_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    -- 会话内轮次号(终态续聊每轮新建一条,R17)
    round INT NOT NULL DEFAULT 1,
    -- 路由意图(FR-019):COMMON_SENSE / DEVICE_ACTION / AFTERSALES_QUERY
    -- (MODEL_SPECIFIC 本期并入 COMMON_SENSE)
    intent VARCHAR(32) NULL,
    raw_text TEXT NOT NULL,
    device_type VARCHAR(32) NULL,
    symptom VARCHAR(512) NULL,
    reproduction VARCHAR(512) NULL,
    clarifications TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_report_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS diagnostic_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    round INT NOT NULL DEFAULT 1,
    phase VARCHAR(8) NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_snapshot_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS repair_action_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    params JSON NULL,
    result VARCHAR(16) NOT NULL,
    message VARCHAR(1024) NULL,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    step_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    command_execution_id VARCHAR(36) NULL,
    attempt_id VARCHAR(128) NULL,
    actor_user_id BIGINT NULL,
    event_type VARCHAR(64) NULL,
    operation_kind VARCHAR(64) NULL,
    event_sequence BIGINT NULL,
    event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_code VARCHAR(64) NULL,
    chat_message_ref BIGINT NULL,
    schema_version INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_action_session (session_id),
    UNIQUE KEY uk_audit_workflow_sequence (request_id, event_sequence),
    UNIQUE KEY uk_audit_workflow_key (request_id, event_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS repair_knowledge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_type VARCHAR(32) NOT NULL,
    problem_pattern VARCHAR(512) NOT NULL,
    solution_content TEXT NOT NULL,
    auto_executable TINYINT(1) NOT NULL DEFAULT 0,
    repair_action_code VARCHAR(64) NULL,
    -- 保留字段(确认门已扩为全部操作,FR-008 2026-08-22)
    disruptive TINYINT(1) NOT NULL DEFAULT 0,
    manual_steps TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_knowledge_type (device_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    workflow_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    step_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    output_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    -- 长期保留;LLM 窗口重建取最近 20 条(ConversationHistoryService 固定 20 条窗口)
    created_at DATETIME NOT NULL,
    INDEX idx_message_session (session_id),
    INDEX idx_message_created (created_at),
    UNIQUE KEY uk_message_workflow_output (workflow_request_id, output_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户表(data-model.md §10,2026-08-29 新增,US3;camelCase 列名为用户给定 DDL 原样)
CREATE TABLE IF NOT EXISTS user (
    id           BIGINT AUTO_INCREMENT COMMENT 'id' PRIMARY KEY,
    userAccount  VARCHAR(256)                           NOT NULL COMMENT '账号',
    userPassword VARCHAR(512)                           NOT NULL COMMENT '密码(BCrypt 散列)',
    userName     VARCHAR(256)                           NULL COMMENT '用户昵称',
    userAvatar   VARCHAR(1024)                          NULL COMMENT '用户头像',
    userProfile  VARCHAR(512)                           NULL COMMENT '用户简介',
    userRole     VARCHAR(256) DEFAULT 'user'            NOT NULL COMMENT '用户角色:user/admin',
    editTime     DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '编辑时间',
    createTime   DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    updateTime   DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete     TINYINT      DEFAULT 0                 NOT NULL COMMENT '是否删除(禁用)',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';

CREATE TABLE IF NOT EXISTS workflow_execution (
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    origin_message_id BIGINT NOT NULL,
    latest_input_message_id BIGINT NOT NULL,
    graph_version VARCHAR(32) NOT NULL,
    schema_version INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    suspended_status VARCHAR(32) NULL,
    current_step_index INT NOT NULL DEFAULT 0,
    plan_json JSON NULL,
    failure_code VARCHAR(64) NULL,
    input_request_id VARCHAR(36) NULL,
    prompt VARCHAR(1024) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(64) NULL,
    fence BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    last_event_sequence BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    INDEX idx_workflow_session (session_id, created_at),
    INDEX idx_workflow_owner (user_id, session_id),
    INDEX idx_workflow_status (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_step (
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json JSON NULL,
    input_hash CHAR(64) CHARACTER SET ascii NULL,
    result_json JSON NULL,
    failure_code VARCHAR(64) NULL,
    certainty VARCHAR(16) NOT NULL DEFAULT 'NOT_SENT',
    retries_used INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL,
    active_attempt_id VARCHAR(128) NULL,
    command_execution_id VARCHAR(36) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (request_id, step_id),
    UNIQUE KEY uk_step_ordinal (request_id, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_approval (
    approval_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    scope_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    operation_kind VARCHAR(64) NOT NULL,
    device_refs JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    decision_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    current_step_key VARCHAR(104) CHARACTER SET ascii COLLATE ascii_bin
      GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','APPROVED') THEN CONCAT(request_id, ':', step_id) ELSE NULL END) STORED,
    UNIQUE KEY uk_current_approval (current_step_key),
    INDEX idx_approval_step (request_id, step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_checkpoint (
    thread_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkpoint_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_checkpoint_id VARCHAR(64) NULL,
    node_id VARCHAR(128) NULL,
    next_node VARCHAR(128) NULL,
    state_payload MEDIUMTEXT NOT NULL,
    schema_version INT NOT NULL,
    graph_version VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    fence BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (thread_id, checkpoint_id),
    UNIQUE KEY uk_checkpoint_version (thread_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS command_execution (
    command_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    canonical_params JSON NOT NULL,
    params_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    approval_id VARCHAR(36) NOT NULL,
    scope_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    status VARCHAR(16) NOT NULL,
    certainty VARCHAR(16) NOT NULL,
    result_json JSON NULL,
    failure_code VARCHAR(64) NULL,
    active_attempt_id VARCHAR(128) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    retries_used INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    fence BIGINT NOT NULL,
    remote_operation_id VARCHAR(128) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_command_step (request_id, step_id),
    UNIQUE KEY uk_command_operation (operation_key),
    INDEX idx_command_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
