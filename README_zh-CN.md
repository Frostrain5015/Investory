**[English](./README.md) | [中文](./README_zh-CN.md)**

# Investory

功能完备的个人投资组合跟踪器，集成量化分析、策略回测与 AI 智能投资助手。

支持 A 股（上证/深证）、港股和美股，提供多币种盈亏跟踪。

---

## 在线体验

本项目已部署至云服务器，可直接访问：

**https://116.62.179.231:8443/investory/**

> **提示**：服务器使用自签名 SSL 证书，浏览器可能会显示安全警告 — 点击"高级" → "继续前往"即可。

---

## 核心特性

### 投资组合管理
- 每用户多个独立投资组合
- 买入 / 卖出 / 转入 / 转出交易记录
- 分红记录（支持每股金额追踪）
- 现金余额按币种分类（人民币 / 港币 / 美元）
- 已平仓历史记录

### 仪表盘
- 总资产曲线，支持时间范围选择（1月 / 6月 / 1年 / 全部 / 自定义）
- 今日盈亏和累计盈亏摘要卡片
- 仓位分布 — 饼图和词云切换
- 持仓盈亏排名 — 累计或单日

### 持仓与自选股
- 拖拽排序自选股列表
- 每只股票 30 天价格迷你图
- 可选量化指标列：Beta、波动率、历史百分位
- 直观区分持仓股票和仅关注股票

### 市场概览
- 交互式世界地图，按国家显示指数涨跌
- 实时指数：上证指数、恒生指数、标普 500 等
- 实时汇率（人民币 / 港币 / 美元）
- 地理位置新闻标记（财经 / 地缘政治），可点击查看详情

### 盈亏日历
- 年度热力图（12 个月）和月度日级日历
- 颜色深浅映射盈亏金额或收益率
- 点击任意单元格查看详情：个股贡献和当日交易

### 量化分析
- **风险标签页**：组合风格诊断（成长 / 价值 / 防御）、加权 Beta、资产配置图、优化建议
- **回测标签页**：
  - 简单规则构建器 — SMA、EMA、RSI、MACD、布林带、成交量、KDJ、止损、止盈条件
  - 高级 Python 策略编辑器，支持自定义逻辑
  - 回测执行过程实时进度流（SSE）
  - 权益曲线带买卖标记，夏普比率、最大回撤、胜率、盈亏比

### AI 助手 — 观澜
- 浮动聊天面板，支持多轮对话
- SSE 流式逐字输出响应
- 深度思考模式用于复杂分析
- 工具调用：对话中可触发组合分析和回测
- 直接将 AI 生成的策略保存到回测库
- 可配置供应商：阿里云百炼（默认，`qwen-plus`）、OpenAI、DeepSeek、Moonshot、智谱 GLM、Anthropic Claude 或任何 OpenAI 兼容端点

### 设置
- 浅色 / 深色 / 跟随系统主题
- 配色方案：红涨绿跌（A 股惯例）或绿涨红跌（国际惯例）
- 基础货币选择
- 头像上传
- 密码修改和账户删除

### 管理面板
- 数据库实时状态：表大小、股票数量、各市场价格行数
- 数据抓取控制：按市场启动 / 暂停 / 恢复 / 停止
- 抓取历史审计日志
- 用户管理：列表、删除、模拟登录

---

## 技术栈

### 后端

| | |
|---|---|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.3.5 |
| 数据库 | MySQL（JdbcTemplate — 无 ORM） |
| JSON | Gson 2.10.1 |
| 密码加密 | jBCrypt 0.4 |
| 拼音搜索 | Pinyin4j 2.5.1 |
| 构建工具 | Maven 3.x |

### 前端

| | |
|---|---|
| 框架 | React 19 |
| 语言 | TypeScript |
| 构建工具 | Vite 8 |
| 路由 | React Router 7 |
| 样式 | Tailwind CSS 4 |
| 组件库 | Radix UI |
| 图表 | Recharts 3 · ECharts 6 |
| 动画 | Framer Motion 12 |
| 图标 | Lucide React |

### 桌面端

| | |
|---|---|
| 框架 | Electron 33 |
| 自动更新 | electron-updater |
| 构建工具 | electron-builder |

### Python 脚本

| | |
|---|---|
| 运行环境 | Python 3.8+ |
| 数据源 | 雅虎财经、新浪财经、东方财富 |
| AI Agent | 自定义 Agent 框架（支持工具调用） |
| 回测引擎 | 自定义回测引擎 |

---

## 项目结构

```
investory/
├── backend/                          # Spring Boot（Maven）
│   ├── pom.xml                       # 父模块：spring-boot-starter-parent 3.3.5，Java 17
│   ├── src/main/java/com/investory/
│   │   ├── InvestoryApplication.java # 主类，@SpringBootApplication
│   │   ├── controller/               # 页面控制器 + api/ REST 控制器
│   │   ├── dao/                      # @Repository + JdbcTemplate（通过 BaseDao）
│   │   ├── service/                  # @Service
│   │   ├── model/                    # POJO
│   │   ├── crawler/                  # @Component，@Scheduled
│   │   └── config/                   # WebConfig（拦截器、静态资源）
│   └── src/main/resources/
│       ├── application.properties    # 数据源，server.servlet.context-path=/investory
│       ├── templates/                # Thymeleaf HTML
│       └── static/                   # 前端构建输出（Vite 写入此处）
├── frontend/
│   ├── package.json                  # 脚本：dev, build (tsc -b && vite build)
│   ├── vite.config.ts                # 代理 /investory → localhost:8443，base: /investory/
│   └── src/
│       ├── pages/                    # Dashboard, Holdings, Transactions, PnlCalendar, StockDetail, Portfolio, Settings
│       ├── components/               # Layout, CloudChart, ui/*
│       ├── hooks/                    # useAuth, useSettings
│       ├── services/api.ts           # API 客户端（前缀 /investory）
│       └── types/index.ts            # TypeScript 接口定义
├── desktop/
│   ├── main.js                       # Electron 主进程
│   ├── preload.js                    # 预加载脚本（上下文隔离）
│   ├── package.json                  # electron-builder 配置
│   └── assets/                       # 应用图标和安装程序图片
└── script/
    ├── ai_agent.py                   # AI 投资助手（观澜）
    ├── backtest_engine.py            # 策略回测引擎
    ├── fetch_stocks.py               # 市场数据抓取（A 股、港股、美股）
    ├── fetch_news.py                 # 财经新闻聚合
    ├── analyze_quant.py              # 量化分析
    ├── portfolio_style_analyzer.py   # 组合风格诊断
    ├── optimizer.py                  # 组合优化
    ├── config.ini                    # 配置文件（从 config.ini.example 复制）
    └── agent_skills/                 # AI Agent 工具定义
```

---

## 快速开始

### 环境依赖

- Java 17
- Maven 3.9+
- Node.js 20+ / npm
- Python 3.8+
- MySQL 8

### 数据库

创建数据库并更新 `backend/src/main/resources/application.properties` 中的凭据：

```sql
CREATE DATABASE investory_db CHARACTER SET utf8mb4;
```

### 后端（Spring Boot）

Maven 构建会自动编译前端（`tsc -b && vite build`），将输出复制到 `backend/src/main/resources/static/`，然后编译 Java。

**完整构建 + 运行：**

```bash
export JAVA_HOME=/path/to/jdk-17
mvn -f backend/pom.xml spring-boot:run -DskipTests
```

**构建生产 JAR：**

```bash
mvn -f backend/pom.xml package -DskipTests
java -jar backend/target/investory.jar
```

应用访问地址：`https://localhost:8443/investory/`

### 前端（Vite）

**开发模式（热更新）：**

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173/investory/
# API 请求代理到 localhost:8443
```

**仅构建：**

```bash
cd frontend
npm run build
# 输出到 ../backend/src/main/resources/static/
```

### Python 脚本

安装依赖：

```bash
cd script
pip install -r requirements.txt
```

配置数据库和代理：

```bash
cp config.ini.example config.ini
# 编辑 config.ini，填写 MySQL 凭据和代理设置
```

运行市场数据抓取：

```bash
python fetch_stocks.py --market a      # A 股
python fetch_stocks.py --market hk     # 港股
python fetch_stocks.py --market us     # 美股
```

运行 AI 助手：

```bash
python ai_agent.py
```

### 桌面端（Electron）

**开发模式：**

```bash
cd desktop
npm install
npm run build:frontend    # 为 Electron 构建前端
npm start                 # 启动 Electron 应用
```

**构建安装程序：**

```bash
cd desktop
npm run build:exe         # 构建 NSIS 安装程序（.exe）
npm run build:msi         # 构建 MSI 安装程序
```

---

## API 概览

| 分组 | 端点 |
|---|---|
| 认证 | `GET /api/session` · `POST /api/password` · `DELETE /api/account` |
| 组合 | CRUD `/api/portfolios` · `POST /api/portfolio/refresh` |
| 仪表盘 | `GET /api/dashboard` · `GET /api/cash` · `GET /api/closed-positions` |
| 持仓 | `GET /api/holdings` |
| 交易 | CRUD `/api/transactions` |
| 分红 | CRUD `/api/dividends` |
| 股票 | `GET /api/search` · `GET /api/quote/{symbol}` · `GET /api/chart` · `GET /api/stocks/{symbol}` |
| 盈亏 | `GET /api/daily-detail` · `GET /api/monthly-detail` |
| 自选 | CRUD `/api/watchlist` · `PUT /api/watchlist/reorder` |
| 市场 | `GET /api/market/indices` · `/exchange-rates` · `/news` |
| 量化 | `GET /api/quant/holdings-metrics` · `/portfolio-style` · `/portfolio-scenario` |
| 回测 | CRUD `/api/backtest/strategies` · `POST /api/backtest/start` · `GET /api/backtest/stream`（SSE） |
| AI | `POST /api/ai/chat` · `GET /api/ai/stream`（SSE） · `GET/POST /api/ai/settings` |
| 管理 | `/api/admin/status` · `/users` · `/crawl-history` · `/crawl/{market}` |

---

## 许可证

MIT
