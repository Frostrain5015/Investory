-- V5: MCP OAuth 最小集（让 Claude Desktop 等连接器原生连接）
-- Investory 后端作为 MCP 的 OAuth 2.1 授权服务器，登录委托上游 Frost ID，
-- 签发自己的、audience 绑定到 MCP 端点的 token。

-- 动态注册的 OAuth 客户端（RFC7591，公共客户端，无 secret）
CREATE TABLE IF NOT EXISTS mcp_oauth_clients (
    client_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    redirect_uris TEXT         NOT NULL COMMENT '换行分隔的允许回调 URI',
    client_name   VARCHAR(128) DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 授权码（PKCE 授权码流，短 TTL，一次性）
CREATE TABLE IF NOT EXISTS mcp_oauth_codes (
    code            VARCHAR(64)  NOT NULL PRIMARY KEY,
    client_id       VARCHAR(64)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    portfolio_id    BIGINT       DEFAULT NULL,
    redirect_uri    VARCHAR(512) NOT NULL,
    code_challenge  VARCHAR(128) NOT NULL COMMENT 'PKCE S256 challenge',
    resource        VARCHAR(256) DEFAULT NULL COMMENT 'RFC8707 目标 MCP 资源',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed        TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_mcp_oauth_codes_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- token 的 audience（绑定到具体 MCP 资源 URI），用于 RFC8707 校验
ALTER TABLE mcp_tokens ADD COLUMN audience VARCHAR(256) DEFAULT NULL COMMENT '绑定的 MCP 资源 URI' AFTER label;
