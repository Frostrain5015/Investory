-- V4: MCP（Model Context Protocol）对外工具链鉴权表
-- 外部 AI 客户端（Claude Desktop / Cursor / 观澜）通过 loopback OAuth 流程绑定，
-- 后端签发长期 token 映射到 Investory user_id，供 /api/* 复用现有 session 控制器逻辑。

-- 长期 MCP token（hash 存储，绑定到 user + 当前活跃组合）
CREATE TABLE IF NOT EXISTS mcp_tokens (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    token_hash    VARCHAR(64)  NOT NULL COMMENT 'SHA-256(token) 十六进制，不存明文',
    user_id       BIGINT       NOT NULL COMMENT '映射到的 Investory 用户',
    portfolio_id  BIGINT       DEFAULT NULL COMMENT '绑定时的活跃组合，可空',
    label         VARCHAR(128) DEFAULT NULL COMMENT '客户端标识/备注',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at  DATETIME     DEFAULT NULL,
    revoked       TINYINT(1)   NOT NULL DEFAULT 0,
    UNIQUE KEY uk_mcp_token_hash (token_hash),
    INDEX idx_mcp_tokens_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 一次性授权码（loopback 流程：浏览器授权后回传，短 TTL，换取长期 token）
CREATE TABLE IF NOT EXISTS mcp_auth_codes (
    code          VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '一次性随机码',
    user_id       BIGINT       NOT NULL,
    portfolio_id  BIGINT       DEFAULT NULL,
    redirect_port INT          NOT NULL COMMENT '本地 loopback 回调端口',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed      TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_mcp_auth_codes_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
