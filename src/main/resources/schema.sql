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
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_user (user_id),
    INDEX idx_session_device (device_id),
    INDEX idx_session_created (created_at)
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
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_action_session (session_id)
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
    -- 长期保留;LLM 窗口重建取最近 20 条(ConversationHistoryService 固定 20 条窗口)
    created_at DATETIME NOT NULL,
    INDEX idx_message_session (session_id),
    INDEX idx_message_created (created_at)
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
