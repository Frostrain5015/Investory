**[English](./README.md) | [中文](./README_zh-CN.md)**

<div align="center">

# 📈 Investory

**A full-stack personal investment platform — portfolio tracking, quantitative analysis, strategy backtesting, and an in-app AI investment assistant.**

Tracks A-shares (Shanghai / Shenzhen), Hong Kong, and US equities with multi-currency P&L.

[Version](backend/pom.xml) · Java 17 · Spring Boot 3.3.5 · React 19 · Electron 33

🌐 **Live demo: [investory.frostrain.tech](https://investory.frostrain.tech)**

</div>

---

## ✨ Highlights

- **🧠 AI assistant "Guanlan" (观澜)** — streaming chat with tool-use and a morph animation system; can analyze your portfolio and generate strategies mid-conversation.
- **📊 Quantitative analysis and backtesting** — independent historical risk statistics, portfolio optimization, and a strategy backtester (SMA/EMA/RSI/MACD/Bollinger/KDJ rules or custom Python), with walk-forward validation and parameter optimization.
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
- Floating chat, token-by-token streaming (SSE), tool execution timeline
- Tool use mid-conversation; saves generated strategies to the backtest library
- Pluggable provider: DeepSeek, OpenAI, Anthropic Claude, Moonshot, Zhipu GLM, Alibaba Bailian, or any OpenAI-compatible endpoint

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
| **Quant / Data** | Python 3.10+, shared Hithink/history gateways, AI agent, independent analysis and backtest workers |

---

## 📚 Architecture and development

See [current architecture](docs/architecture.md) for module boundaries, runtime flows, entry points, validation, and limitations. The [cleanup record](docs/code-hygiene-2026-10-20.md) documents verified changes. Assessments and plans live in [docs](docs/README.md).

---

## 📂 Project Structure

```
investory/
├── backend/                          # Spring Boot (Maven) — served at domain root via Nginx
│   ├── pom.xml                       # spring-boot-starter-parent 3.3.5, Java 17
│   ├── src/main/java/com/investory/
│   │   ├── controller/  (+ api/, McpController, OAuthController)
│   │   ├── dao/         # @Repository + JdbcTemplate (BaseDao)
│   │   ├── service/     # auth, cost calc, PnL ledger, model configuration
│   │   ├── crawler/     # @Scheduled market-data sync & FX refresh
│   │   ├── web/         # LoginInterceptor, CORS, GlobalExceptionHandler
│   │   └── config/
│   └── src/main/resources/
│       ├── application.properties
│       ├── db/migration/             # Flyway V2..V7 (legacy baseline)
│       └── static/                   # built frontend (Vite output)
├── frontend/                         # React + TS + Vite
│   └── src/{pages,features,providers,components,hooks,services,i18n,types}
├── desktop/                          # Electron (main.js, preload.js, electron-builder)
├── script/                           # data crawlers, AI agent, backtest engine, agent_skills/
├── docs/                             # architecture, assessments and plans
└── deploy.py                         # manual cloud deployment + desktop publication
```

---

## 🚀 Getting Started

### Prerequisites
Java 17 · Maven 3.9+ · Node.js 22.12+ · Python 3.10+ · MySQL 8

### 1. Database
```sql
CREATE DATABASE investory CHARACTER SET utf8mb4;
```
Set credentials via env vars (`DB_USER`, `DB_PASSWORD`, …) — see `application.properties`. Flyway maintains incremental migrations from V2; a new database still needs the initial business schema, as described in [storage boundaries](docs/architecture.md#6-身份与存储).

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
```

### 5. Desktop client
```bash
cp frontend/.env.electron.example frontend/.env.electron  # set the actual backend URL
cd desktop && npm install
npm run build:frontend && npm start          # dev
npm run build:exe                             # NSIS installer
```

---

## Model configuration

Open **Settings → 观澜** and add up to three independent models with API protocol, Base URL, Model ID and context window. Switch the active model at the bottom right of Guanlan; each outgoing message binds its own selected configuration. Cloud APIs and local OpenAI-compatible/Anthropic services use the same form. A local service without authentication may leave its API key empty. The context budget includes reply space and trims older turns; it does not configure the model server's GPU allocation. No shared model or credential fallback is used.

External MCP access was removed on 2026-10-22. Existing MCP tokens no longer authenticate requests. Frost ID login remains available.

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
| Quant / Backtest | `/api/quant/*`, CRUD `/api/backtest/strategies` · `GET /api/backtest/stream` (SSE) |
| AI | `POST /api/ai/chat` · `GET /api/ai/stream` (SSE) · `/api/ai/settings` |
| Admin | `/api/admin/status` · `/users` · `/crawl-history` · `/crawl/{market}` |

---

## 📄 License

MIT
