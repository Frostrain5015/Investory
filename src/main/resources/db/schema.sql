-- 盈亏鉴 / Investory — Database Schema
-- Run once to initialize: mysql -u root -p < schema.sql

CREATE DATABASE IF NOT EXISTS investory CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE investory;

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(60)  NOT NULL,
    email         VARCHAR(100),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS stocks (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol   VARCHAR(20)  NOT NULL UNIQUE COMMENT 'secid format: 1.600519, 116.00700, 105.AAPL',
    name     VARCHAR(100) NOT NULL,
    market   ENUM('SH','SZ','HK','US') NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY'
);

CREATE TABLE IF NOT EXISTS stock_prices (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id   BIGINT       NOT NULL,
    trade_date DATE         NOT NULL,
    open       DECIMAL(15,4),
    close      DECIMAL(15,4),
    high       DECIMAL(15,4),
    low        DECIMAL(15,4),
    volume     BIGINT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_stock_date (stock_id, trade_date),
    FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE IF NOT EXISTS portfolios (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS transactions (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id BIGINT       NOT NULL,
    stock_id     BIGINT       NOT NULL,
    type         ENUM('BUY','SELL') NOT NULL,
    shares       DECIMAL(15,4) NOT NULL,
    price        DECIMAL(15,4) NOT NULL,
    fee          DECIMAL(10,4) NOT NULL DEFAULT 0,
    trade_date   DATE          NOT NULL,
    note         TEXT,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (portfolio_id) REFERENCES portfolios(id),
    FOREIGN KEY (stock_id)     REFERENCES stocks(id)
);

CREATE TABLE IF NOT EXISTS dividends (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id     BIGINT         NOT NULL,
    stock_id         BIGINT         NOT NULL,
    amount_per_share DECIMAL(10,6)  NOT NULL,
    shares_held      DECIMAL(15,4)  NOT NULL,
    total_amount     DECIMAL(15,4)  NOT NULL,
    record_date      DATE           NOT NULL,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (portfolio_id) REFERENCES portfolios(id),
    FOREIGN KEY (stock_id)     REFERENCES stocks(id)
);

CREATE TABLE IF NOT EXISTS holdings (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id     BIGINT        NOT NULL,
    stock_id         BIGINT        NOT NULL,
    total_shares     DECIMAL(15,4) NOT NULL DEFAULT 0,
    avg_cost         DECIMAL(15,4) NOT NULL DEFAULT 0,
    diluted_cost     DECIMAL(15,4) NOT NULL DEFAULT 0,
    total_invested   DECIMAL(15,4) NOT NULL DEFAULT 0,
    total_dividends  DECIMAL(15,4) NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_portfolio_stock (portfolio_id, stock_id),
    FOREIGN KEY (portfolio_id) REFERENCES portfolios(id),
    FOREIGN KEY (stock_id)     REFERENCES stocks(id)
);

CREATE TABLE IF NOT EXISTS daily_portfolio_value (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id  BIGINT        NOT NULL,
    snapshot_date DATE          NOT NULL,
    total_value   DECIMAL(20,4) NOT NULL DEFAULT 0,
    total_cost    DECIMAL(20,4) NOT NULL DEFAULT 0,
    daily_pnl     DECIMAL(20,4) NOT NULL DEFAULT 0,
    UNIQUE KEY uq_portfolio_date (portfolio_id, snapshot_date),
    FOREIGN KEY (portfolio_id) REFERENCES portfolios(id)
);

-- ── Pre-loaded stock list ──────────────────────────────────────────────────────
INSERT IGNORE INTO stocks (symbol, name, market, currency) VALUES
-- A股 沪市
('1.600519', '贵州茅台',   'SH', 'CNY'),
('1.601318', '中国平安',   'SH', 'CNY'),
('1.600036', '招商银行',   'SH', 'CNY'),
('1.600900', '长江电力',   'SH', 'CNY'),
('1.601888', '中国中免',   'SH', 'CNY'),
('1.600276', '恒瑞医药',   'SH', 'CNY'),
('1.601166', '兴业银行',   'SH', 'CNY'),
('1.600030', '中信证券',   'SH', 'CNY'),
('1.601939', '建设银行',   'SH', 'CNY'),
('1.600028', '中国石化',   'SH', 'CNY'),
('1.601857', '中国石油',   'SH', 'CNY'),
('1.601988', '中国银行',   'SH', 'CNY'),
('1.600050', '中国联通',   'SH', 'CNY'),
-- A股 深市
('0.000858', '五粮液',     'SZ', 'CNY'),
('0.000333', '美的集团',   'SZ', 'CNY'),
('0.300750', '宁德时代',   'SZ', 'CNY'),
('0.002594', '比亚迪',     'SZ', 'CNY'),
('0.000001', '平安银行',   'SZ', 'CNY'),
('0.000002', '万科A',      'SZ', 'CNY'),
('0.000651', '格力电器',   'SZ', 'CNY'),
('0.000725', '京东方A',    'SZ', 'CNY'),
('0.002415', '海康威视',   'SZ', 'CNY'),
('0.300059', '东方财富',   'SZ', 'CNY'),
-- 港股
('116.00700', '腾讯控股',  'HK', 'HKD'),
('116.09988', '阿里巴巴',  'HK', 'HKD'),
('116.03690', '美团',      'HK', 'HKD'),
('116.01211', '农业银行',  'HK', 'HKD'),
('116.02318', '中国平安',  'HK', 'HKD'),
('116.00941', '中国移动',  'HK', 'HKD'),
('116.01810', '小米集团',  'HK', 'HKD'),
('116.09618', '京东集团',  'HK', 'HKD'),
('116.01024', '快手',      'HK', 'HKD'),
-- 美股
('105.AAPL',  'Apple Inc.',           'US', 'USD'),
('105.MSFT',  'Microsoft Corp.',      'US', 'USD'),
('105.GOOGL', 'Alphabet Inc.',        'US', 'USD'),
('105.AMZN',  'Amazon.com Inc.',      'US', 'USD'),
('105.NVDA',  'NVIDIA Corp.',         'US', 'USD'),
('105.TSLA',  'Tesla Inc.',           'US', 'USD'),
('105.META',  'Meta Platforms',       'US', 'USD'),
('105.BRK.B', 'Berkshire Hathaway B', 'US', 'USD'),
('105.JPM',   'JPMorgan Chase',       'US', 'USD'),
('105.V',     'Visa Inc.',            'US', 'USD'),
('105.JNJ',   'Johnson & Johnson',    'US', 'USD'),
('105.WMT',   'Walmart Inc.',         'US', 'USD');
