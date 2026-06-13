**[English](./README.md) | [中文](./README_zh-CN.md)**

<div align="center">

# 📈 Investory

**A full-stack personal investment platform — portfolio tracking, quantitative analysis, strategy backtesting, and an AI investment assistant that any MCP-capable agent can drive.**

Tracks A-shares (Shanghai / Shenzhen), Hong Kong, and US equities with multi-currency P&L.

`v6.3.0` · Java 17 · Spring Boot 3.3.5 · React 19 · Electron 33 · ~58k LOC

🌐 **Live demo: [investory.frostrain.tech](https://investory.frostrain.tech)**

</div>

---

## ✨ Highlights

- **🤖 MCP server** — exposes **46 tools** at `/mcp` so Claude / Cursor / Cline and any MCP client can read your dashboard, run backtests, and place (confirmed) trades. Zero logic rewrite: each tool replays your existing REST API with an injected identity.
- **🧠 AI assistant "Guanlan" (观澜)** — streaming chat with tool-use, deep-thinking mode, and a morph animation system; can analyze your portfolio and generate strategies mid-conversation.
- **📊 Quantitative engine (StockSage Alpha)** — factor scoring, market-regime detection, portfolio style/risk analysis, and a strategy backtester (SMA/EMA/RSI/MACD/Bollinger/KDJ rules or custom Python), with walk-forward validation and parameter optimization.
- **🌍 Multi-market, multi-currency** — A-shares, HK, US; CNY / HKD / USD with daily FX refresh and per-currency cash tracking.
- **💻 Cross-platform** — responsive web (tested from 320px phones to 21:9 ultra-wide) + a native Electron desktop client with auto-update.

---

## 🎯 Features

### Portfolio & Transactions
- Multiple independent portfolios per user
- BUY / SELL / TRANSFER_IN / TRANSFER_OUT transactions, dividend recording
- Average-cost & dividend-diluted cost basis (BigDecimal, FIFO-style sell replay)
- Cash balance by currency · closed-positions history

### Dashboard
- Total-asset-value curve (1M / 6M / 1Y / all / custom ranges)
- Today's & cumulative P&L cards · realized vs. unrealized split
- Allocation chart (pie ⇄ word cloud) · P&L ranking across holdings

### Holdings & Watchlist
- Drag-to-reorder watchlist · 30-day price sparklines
- Optional quant columns: Beta, volatility, historical percentile

### Market Overview
- Interactive world map colored by country-level index performance
- Live global indices · real-time FX rates · geolocated finance/geopolitics news pins

### P&L Calendar
- Yearly heat-map (12 months) + monthly day-level calendar
- Color intensity by P&L amount or return % · click any cell for per-stock attribution

### Quantitative Analysis & Backtesting
- **Risk**: portfolio style diagnosis (growth/value/defensive), weighted Beta, optimization advice
- **Backtest**: visual rule builder or custom Python · live SSE progress · equity curve with buy/sell markers, Sharpe, max drawdown, win rate, profit factor · walk-forward & grid optimization

### AI Assistant — Guanlan (观澜)
- Floating chat, token-by-token streaming (SSE), deep-thinking mode
- Tool use mid-conversation; saves generated strategies to the backtest library
- Pluggable provider: DeepSeek, OpenAI, Anthropic Claude, Moonshot, Zhipu GLM, Alibaba Bailian, or any OpenAI-compatible endpoint

### MCP Server
- Streamable-HTTP MCP endpoint at `/mcp` with **46 tools**
- OAuth 2.1 + PKCE token flow · SHA-256-hashed tokens · two-step confirm for write ops
- Self-service token management in **Settings → MCP**

### Settings & Admin
- Light/dark/system theme · red-up/green-down (A-share) or international color scheme · base currency
- Admin: live DB status, per-market crawler control (start/pause/resume/stop), crawl audit log, user management

---

## 🔐 Authentication & Sessions

- **Frost ID OAuth 2.1** single sign-on (browser flow, with `investory://` deep-link handoff for the desktop client)
- Passwords are **BCrypt**-hashed; **Spring Session JDBC** persists sessions to MySQL, so a backend restart no longer logs users out

---

## 🧱 Tech Stack

| Layer | Technology |
|---|---|
| **Backend** | Java 17 · Spring Boot 3.3.5 · MySQL (JdbcTemplate, no ORM) · Flyway · Spring Session JDBC · BCrypt · Gson |
| **Frontend** | React 19 · TypeScript · Vite 8 · React Router 7 · Tailwind CSS 4 · Recharts 3 · ECharts 6 · Framer Motion 12 |
| **Desktop** | Electron 33 · electron-builder · electron-updater (auto-update via GitHub Releases) |
| **Quant / Data** | Python 3.8+ · StockSage Alpha engine · Yahoo / Sina / East Money data sources · custom AI agent & backtest engine |
| **Integration** | MCP (Model Context Protocol) server, 46 tools, Streamable HTTP + OAuth 2.1/PKCE |

---

## 📏 Project Scale

| Module | Files | Lines |
|---|--:|--:|
| Backend — Java (main) | 76 | 14,453 |
| Backend — Java (tests) | 4 | 517 |
| Frontend — TS/TSX | 62 | 13,657 |
| Frontend — CSS | 1 | 216 |
| Python — StockSage Alpha engine | 29 | 19,516 |
| Python — data / AI scripts | 17 | 8,683 |
| Python — deploy / stress | 2 | 711 |
| Electron desktop (JS) | 7 | 550 |
| SQL migrations | 9 | 245 |
| Thymeleaf template | 1 | 72 |
| **Total** | **208** | **~58,620** |

By language: **Java ~14.9k · TypeScript ~13.7k · Python ~28.9k**.

---

## 📂 Project Structure

```
investory/
├── backend/                          # Spring Boot (Maven) — served at domain root via Nginx
│   ├── pom.xml                       # spring-boot-starter-parent 3.3.5, Java 17
│   ├── src/main/java/com/investory/
│   │   ├── controller/  (+ api/, McpController, OAuthController)
│   │   ├── dao/         # @Repository + JdbcTemplate (BaseDao)
│   │   ├── service/     # auth, cost calc, PnL ledger, MCP tool registry
│   │   ├── crawler/     # @Scheduled market-data sync & FX refresh
│   │   ├── web/         # LoginInterceptor, CORS, GlobalExceptionHandler
│   │   └── config/
│   └── src/main/resources/
│       ├── application.properties
│       ├── db/migration/             # Flyway V1..V7
│       ├── python/stocksage_alpha/   # resident quant engine
│       └── static/                   # built frontend (Vite output)
├── frontend/                         # React + TS + Vite
│   └── src/{pages,components,hooks,services,i18n,types}
├── desktop/                          # Electron (main.js, preload.js, electron-builder)
├── script/                           # data crawlers, AI agent, backtest engine, agent_skills/
└── deploy.py                         # safe deploy + GitHub desktop release
```

---

## 🚀 Getting Started

### Prerequisites
Java 17 · Maven 3.9+ · Node.js 20+ · Python 3.8+ · MySQL 8

### 1. Database
```sql
CREATE DATABASE investory CHARACTER SET utf8mb4;
```
Set credentials via env vars (`DB_USER`, `DB_PASSWORD`, …) — see `application.properties`. Flyway applies migrations on startup.

### 2. Backend (builds the frontend automatically)
The Maven build runs `tsc -b && vite build` and copies the output into `static/` before packaging.
```bash
export JAVA_HOME=/path/to/jdk-17
mvn -f backend/pom.xml spring-boot:run -DskipTests          # dev
# or
mvn -f backend/pom.xml package -DskipTests
java -jar backend/target/investory.jar                      # prod JAR
```

### 3. Frontend dev server (hot-reload)
```bash
cd frontend && npm install && npm run dev   # http://localhost:5173, proxies /api → backend
```

### 4. Python scripts
```bash
cd script && pip install -r requirements.txt
cp config.ini.example config.ini            # set MySQL + proxy
python fetch_stocks.py --market a|hk|us      # crawl market data
```

### 5. Desktop client
```bash
cd desktop && npm install
npm run build:frontend && npm start          # dev
npm run build:exe                             # NSIS installer
```

---

## 🔌 Connecting an MCP Agent

1. Open the app → **Settings → MCP** → generate a token.
2. Add to your client (e.g. `.mcp.json` for Claude Code / Cursor):

```json
{
  "mcpServers": {
    "investory": {
      "type": "http",
      "url": "https://investory.frostrain.tech/mcp",
      "headers": { "Authorization": "Bearer <your-token>" }
    }
  }
}
```

3. Reload the client and run `/mcp` — `investory` (46 tools) should appear. The domain uses a valid Let's Encrypt certificate, so **no extra CA setup is needed**.

---

## 📡 API Overview

| Group | Endpoints |
|---|---|
| Auth / Session | `GET /api/session` · `GET/POST /oauth/frost-id/*` · `DELETE /api/account` |
| Portfolios | CRUD `/api/portfolios` · `POST /api/portfolio/refresh` |
| Dashboard | `GET /api/dashboard` · `/api/cash` · `/api/closed-positions` |
| Holdings / Transactions / Dividends | `GET /api/holdings` · CRUD `/api/transactions` · CRUD `/api/dividends` |
| Stocks | `GET /api/stock/search` · `/api/chart` · `/api/stocks/{symbol}` |
| P&L Calendar | `GET /api/chart?type=pnl_calendar` · `/api/daily-detail` · `/api/monthly-detail` |
| Watchlist | CRUD `/api/watchlist` · `PUT /api/watchlist/reorder` |
| Market | `GET /api/market/indices` · `/exchange-rates` · `/news` |
| Quant / Backtest | `/api/quant/*` · `/api/stocksage/*` · CRUD `/api/backtest/strategies` · `GET /api/backtest/stream` (SSE) |
| AI | `POST /api/ai/chat` · `GET /api/ai/stream` (SSE) · `/api/ai/settings` |
| MCP | `POST /mcp` (JSON-RPC) · `/oauth/mcp/*` (OAuth 2.1 + PKCE) |
| Admin | `/api/admin/status` · `/users` · `/crawl-history` · `/crawl/{market}` |

---

## 📄 License

MIT
