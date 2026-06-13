-- V3: StockSage Alpha 缓存表
-- 每日扫描结果、逐股票因子、市场环境、筹码分布缓存

-- 每日扫描结果缓存（按 scan_type + scan_date 唯一）
CREATE TABLE IF NOT EXISTS stocksage_scan_cache (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    scan_type   VARCHAR(32)  NOT NULL COMMENT '扫描类型: main/chip/hot/golden_cross',
    scan_date   DATE         NOT NULL COMMENT '扫描日期',
    stock_symbol VARCHAR(16)  NOT NULL COMMENT '股票代码',
    stock_name  VARCHAR(64)  DEFAULT NULL COMMENT '股票名称',
    buy_score   DECIMAL(5,1) DEFAULT NULL COMMENT '买入分 0-100',
    sell_score  DECIMAL(5,1) DEFAULT NULL COMMENT '卖出分 0-100',
    total_score DECIMAL(5,1) DEFAULT NULL COMMENT '综合评分 0-100',
    regime      VARCHAR(16)  DEFAULT NULL COMMENT '当前市场环境',
    bullish     JSON         DEFAULT NULL COMMENT '看涨理由列表',
    bearish     JSON         DEFAULT NULL COMMENT '看跌理由列表',
    factors_json JSON        DEFAULT NULL COMMENT '逐因子评分详情',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scan_date_type (scan_date, scan_type),
    INDEX idx_scan_stock (stock_symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 逐股票因子详情缓存
CREATE TABLE IF NOT EXISTS stocksage_factor_cache (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_symbol  VARCHAR(16)   NOT NULL COMMENT '股票代码',
    factor_name   VARCHAR(64)   NOT NULL COMMENT '因子名称',
    factor_group  VARCHAR(32)   DEFAULT NULL COMMENT '因子组: value/growth/momentum/quality/technical/event/social',
    factor_value  DECIMAL(12,4) DEFAULT NULL COMMENT '因子原始值',
    buy_score     DECIMAL(5,1)  DEFAULT NULL COMMENT '买入分 0-10',
    sell_score    DECIMAL(5,1)  DEFAULT NULL COMMENT '卖出分 0-10',
    description   VARCHAR(256)  DEFAULT NULL COMMENT '因子描述',
    computed_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_factor_symbol (stock_symbol),
    INDEX idx_factor_group (factor_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每日市场环境记录
CREATE TABLE IF NOT EXISTS stocksage_regime_cache (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    regime_date   DATE          NOT NULL COMMENT '环境日期',
    regime        VARCHAR(16)   NOT NULL COMMENT '环境: NORMAL/CAUTION/CRISIS/BULL/EXTREME_BULL/BEAR',
    confidence    DECIMAL(5,1)  DEFAULT NULL COMMENT '置信度 0-100',
    description   VARCHAR(256)  DEFAULT NULL COMMENT '环境描述',
    indicators_json JSON        DEFAULT NULL COMMENT '指标详情',
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_regime_date (regime_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 筹码分布缓存
CREATE TABLE IF NOT EXISTS stocksage_chip_cache (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_symbol  VARCHAR(16)   NOT NULL COMMENT '股票代码',
    chip_data_json JSON         NOT NULL COMMENT '筹码分布数据',
    computed_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chip_symbol (stock_symbol),
    INDEX idx_chip_computed (computed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每日推荐选股
CREATE TABLE IF NOT EXISTS stocksage_daily_picks (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    pick_date     DATE          NOT NULL COMMENT '推荐日期',
    stock_symbol  VARCHAR(16)   NOT NULL COMMENT '股票代码',
    stock_name    VARCHAR(64)   DEFAULT NULL COMMENT '股票名称',
    buy_score     DECIMAL(5,1)  DEFAULT NULL COMMENT '买入分',
    sell_score    DECIMAL(5,1)  DEFAULT NULL COMMENT '卖出分',
    total_score   DECIMAL(5,1)  DEFAULT NULL COMMENT '综合评分',
    strategy_type VARCHAR(32)   DEFAULT NULL COMMENT '策略类型: main/chip/hot/golden_cross',
    regime        VARCHAR(16)   DEFAULT NULL COMMENT '推荐时市场环境',
    reason_text   VARCHAR(512)  DEFAULT NULL COMMENT '推荐理由',
    factors_json  JSON          DEFAULT NULL COMMENT '因子详情',
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_picks_date (pick_date),
    INDEX idx_picks_stock (stock_symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户反馈
CREATE TABLE IF NOT EXISTS stocksage_pick_feedback (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    pick_id    BIGINT       NOT NULL COMMENT '关联 stocksage_daily_picks.id',
    user_id    BIGINT       NOT NULL COMMENT '用户 id',
    liked      TINYINT(1)   DEFAULT NULL COMMENT '1=喜欢, 0=不喜欢',
    ignored    TINYINT(1)   DEFAULT 0  COMMENT '已忽略',
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_feedback_pick (pick_id),
    INDEX idx_feedback_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
