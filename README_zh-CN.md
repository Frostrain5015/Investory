**[English](./README.md) | [中文](./README_zh-CN.md)**

<div align="center">

# 📈 Investory

**全栈个人投资平台 —— 投资组合跟踪、量化分析、策略回测，以及一个任意 MCP 智能体都能驱动的 AI 投资助手。**

支持 A 股（上证 / 深证）、港股、美股，多币种盈亏跟踪。

`v6.3.0` · Java 17 · Spring Boot 3.3.5 · React 19 · Electron 33 · 约 5.8 万行代码

🌐 **在线体验：[investory.frostrain.tech](https://investory.frostrain.tech)**

</div>

---

## ✨ 项目亮点

- **🤖 MCP 服务端** —— 在 `/mcp` 暴露 **46 个工具**，让 Claude / Cursor / Cline 等任意 MCP 客户端读取仪表盘、运行回测、下单（带二次确认）。零逻辑重写：每个工具携带注入的用户身份回放现有 REST 接口。
- **🧠 AI 助手「观澜」** —— 流式对话 + 工具调用 + 深度思考模式，配套形态变形动效；可在对话中分析组合、生成策略。
- **📊 量化引擎（StockSage Alpha）** —— 因子打分、市场状态识别、组合风格 / 风险分析，以及策略回测器（SMA/EMA/RSI/MACD/布林/KDJ 规则或自定义 Python），支持步进式（walk-forward）验证与参数优化。
- **🌍 多市场多币种** —— A 股 / 港股 / 美股；CNY / HKD / USD，每日汇率刷新与分币种现金跟踪。
- **💻 跨平台** —— 响应式 Web（已覆盖 320px 手机到 21:9 超宽屏测试）+ 原生 Electron 桌面客户端，支持自动更新。

---

## 🎯 功能

### 投资组合与交易
- 单用户多组合相互独立
- 买入 / 卖出 / 转入 / 转出交易，分红记录
- 平均成本与分红摊薄成本（BigDecimal 精确计算，卖出按 FIFO 回放成本）
- 分币种现金余额 · 已平仓历史

### 仪表盘
- 总资产曲线（1月 / 6月 / 1年 / 全部 / 自定义）
- 今日盈亏与累计盈亏卡片 · 已实现 / 未实现拆分
- 仓位分布图（饼图 ⇄ 词云）· 持仓盈亏排行

### 持仓与自选
- 自选股拖拽排序 · 30 日价格迷你走势线
- 可选量化列：Beta、波动率、历史分位

### 全球市场
- 交互式世界地图，按各国指数涨跌着色
- 实时全球指数 · 实时汇率 · 按地理位置标注的财经 / 地缘新闻

### 盈亏日历
- 年度热力网格（12 个月）+ 月度日历
- 颜色深浅映射盈亏金额或收益率 · 点击任意单元格查看个股归因

### 量化分析与回测
- **风控**：组合风格诊断（成长 / 价值 / 防御）、加权 Beta、优化建议
- **回测**：可视化规则构建器或自定义 Python · SSE 实时进度 · 带买卖标记的净值曲线、夏普比率、最大回撤、胜率、盈亏比 · 步进式验证与网格优化

### AI 助手「观澜」
- 悬浮对话窗，逐字流式输出（SSE），深度思考模式
- 对话中调用工具；可将生成的策略直接保存到回测库
- 可插拔模型供应商：DeepSeek、OpenAI、Anthropic Claude、Moonshot、智谱 GLM、阿里云百炼，或任意 OpenAI 兼容端点

### MCP 服务端
- Streamable HTTP 的 MCP 端点 `/mcp`，含 **46 个工具**
- OAuth 2.1 + PKCE 令牌流 · 令牌 SHA-256 哈希存储 · 写操作两步确认
- 在 **设置 → MCP 接入** 中自助管理令牌

### 设置与后台
- 浅色 / 深色 / 跟随系统 · 红涨绿跌（A 股习惯）或国际配色 · 基准货币
- 后台：实时数据库状态、按市场控制爬虫（启动 / 暂停 / 恢复 / 停止）、抓取审计日志、用户管理

---

## 🔐 认证与会话

- **Frost ID OAuth 2.1** 单点登录（浏览器流程，桌面端通过 `investory://` 深链交接令牌）
- 密码使用 **BCrypt** 哈希；**Spring Session JDBC** 将会话持久化到 MySQL，后端重启不再导致用户掉线

---

## 🧱 技术栈

| 层 | 技术 |
|---|---|
| **后端** | Java 17 · Spring Boot 3.3.5 · MySQL（JdbcTemplate，无 ORM）· Flyway · Spring Session JDBC · BCrypt · Gson |
| **前端** | React 19 · TypeScript · Vite 8 · React Router 7 · Tailwind CSS 4 · Recharts 3 · ECharts 6 · Framer Motion 12 |
| **桌面端** | Electron 33 · electron-builder · electron-updater（经 GitHub Releases 自动更新） |
| **量化 / 数据** | Python 3.8+ · StockSage Alpha 引擎 · Yahoo / 新浪 / 东方财富数据源 · 自研 AI agent 与回测引擎 |
| **集成** | MCP（模型上下文协议）服务端，46 个工具，Streamable HTTP + OAuth 2.1/PKCE |

---

## 📏 代码规模

| 模块 | 文件数 | 行数 |
|---|--:|--:|
| 后端 — Java（主代码） | 76 | 14,453 |
| 后端 — Java（测试） | 4 | 517 |
| 前端 — TS/TSX | 62 | 13,657 |
| 前端 — CSS | 1 | 216 |
| Python — StockSage Alpha 引擎 | 29 | 19,516 |
| Python — 数据 / AI 脚本 | 17 | 8,683 |
| Python — 部署 / 压测 | 2 | 711 |
| Electron 桌面端（JS） | 7 | 550 |
| SQL 迁移 | 9 | 245 |
| Thymeleaf 模板 | 1 | 72 |
| **合计** | **208** | **约 58,620** |

按语言：**Java 约 1.49 万 · TypeScript 约 1.37 万 · Python 约 2.89 万**。

---

## 📂 项目结构

```
investory/
├── backend/                          # Spring Boot（Maven）—— 经 Nginx 在域名根路径提供服务
│   ├── pom.xml                       # spring-boot-starter-parent 3.3.5, Java 17
│   ├── src/main/java/com/investory/
│   │   ├── controller/  (+ api/、McpController、OAuthController)
│   │   ├── dao/         # @Repository + JdbcTemplate（BaseDao）
│   │   ├── service/     # 认证、成本计算、盈亏台账、MCP 工具注册表
│   │   ├── crawler/     # @Scheduled 行情同步与汇率刷新
│   │   ├── web/         # LoginInterceptor、CORS、全局异常处理
│   │   └── config/
│   └── src/main/resources/
│       ├── application.properties
│       ├── db/migration/             # Flyway V1..V7
│       ├── python/stocksage_alpha/   # 常驻量化引擎
│       └── static/                   # 构建后的前端（Vite 输出）
├── frontend/                         # React + TS + Vite
│   └── src/{pages,components,hooks,services,i18n,types}
├── desktop/                          # Electron（main.js、preload.js、electron-builder）
├── script/                           # 数据爬虫、AI agent、回测引擎、agent_skills/
└── deploy.py                         # 安全部署 + 桌面端 GitHub 发布
```

---

## 🚀 快速开始

### 环境要求
Java 17 · Maven 3.9+ · Node.js 20+ · Python 3.8+ · MySQL 8

### 1. 数据库
```sql
CREATE DATABASE investory CHARACTER SET utf8mb4;
```
通过环境变量（`DB_USER`、`DB_PASSWORD` 等）配置凭据 —— 见 `application.properties`。启动时 Flyway 自动执行迁移。

### 2. 后端（构建时自动编译前端）
Maven 构建会运行 `tsc -b && vite build` 并把产物复制到 `static/` 后再打包。
```bash
export JAVA_HOME=/path/to/jdk-17
mvn -f backend/pom.xml spring-boot:run -DskipTests          # 开发
# 或
mvn -f backend/pom.xml package -DskipTests
java -jar backend/target/investory.jar                      # 生产 JAR
```

### 3. 前端开发服务器（热重载）
```bash
cd frontend && npm install && npm run dev   # http://localhost:5173，/api 代理到后端
```

### 4. Python 脚本
```bash
cd script && pip install -r requirements.txt
cp config.ini.example config.ini            # 配置 MySQL + 代理
python fetch_stocks.py --market a|hk|us      # 抓取行情数据
```

### 5. 桌面客户端
```bash
cd desktop && npm install
npm run build:frontend && npm start          # 开发
npm run build:exe                             # 打包 NSIS 安装包
```

---

## 🔌 接入 MCP 智能体

1. 打开应用 → **设置 → MCP 接入** → 生成令牌。
2. 添加到你的客户端（如 Claude Code / Cursor 的 `.mcp.json`）：

```json
{
  "mcpServers": {
    "investory": {
      "type": "http",
      "url": "https://investory.frostrain.tech/mcp",
      "headers": { "Authorization": "Bearer <你的令牌>" }
    }
  }
}
```

3. 重载客户端并运行 `/mcp` —— 应出现 `investory`（46 个工具）。域名使用有效的 Let's Encrypt 证书，**无需额外配置 CA 证书**。

---

## 📡 API 概览

| 分组 | 端点 |
|---|---|
| 认证 / 会话 | `GET /api/session` · `GET/POST /oauth/frost-id/*` · `DELETE /api/account` |
| 投资组合 | CRUD `/api/portfolios` · `POST /api/portfolio/refresh` |
| 仪表盘 | `GET /api/dashboard` · `/api/cash` · `/api/closed-positions` |
| 持仓 / 交易 / 分红 | `GET /api/holdings` · CRUD `/api/transactions` · CRUD `/api/dividends` |
| 股票 | `GET /api/stock/search` · `/api/chart` · `/api/stocks/{symbol}` |
| 盈亏日历 | `GET /api/chart?type=pnl_calendar` · `/api/daily-detail` · `/api/monthly-detail` |
| 自选 | CRUD `/api/watchlist` · `PUT /api/watchlist/reorder` |
| 市场 | `GET /api/market/indices` · `/exchange-rates` · `/news` |
| 量化 / 回测 | `/api/quant/*` · `/api/stocksage/*` · CRUD `/api/backtest/strategies` · `GET /api/backtest/stream`（SSE） |
| AI | `POST /api/ai/chat` · `GET /api/ai/stream`（SSE） · `/api/ai/settings` |
| MCP | `POST /mcp`（JSON-RPC） · `/oauth/mcp/*`（OAuth 2.1 + PKCE） |
| 后台 | `/api/admin/status` · `/users` · `/crawl-history` · `/crawl/{market}` |

---

## 📄 许可证

MIT
