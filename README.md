**[English](./README.md) | [中文](./README_zh-CN.md)**

# Investory

A full-featured personal investment portfolio tracker with quantitative analysis, strategy backtesting, and an AI-powered investment assistant.

Supports A-shares (Shanghai/Shenzhen), Hong Kong stocks, and US equities with multi-currency P&L tracking.

---

## Features

### Portfolio Management
- Multiple independent portfolios per user
- BUY / SELL / TRANSFER_IN / TRANSFER_OUT transactions
- Dividend recording with per-share amount tracking
- Cash balance breakdown by currency (CNY / HKD / USD)
- Closed positions history

### Dashboard
- Total asset value curve with selectable time ranges (1M / 6M / 1Y / all / custom)
- Today's P&L and cumulative P&L summary cards
- Position allocation — toggle between pie chart and word cloud
- P&L ranking across holdings — cumulative or daily

### Holdings & Watchlist
- Drag-to-reorder watchlist
- 30-day price sparkline per stock
- Optional quantitative columns: Beta, volatility, historical percentile
- Inline distinction between held positions and watched-only stocks

### Market Overview
- Interactive world map with country-level index performance coloring
- Live indices: Shanghai Composite, Hang Seng, S&P 500 and more
- Real-time exchange rates (CNY / HKD / USD)
- Geolocated news pins (finance / geopolitics) with click-through links

### P&L Calendar
- Yearly heat-map grid (12 months) and monthly day-level calendar
- Color intensity mapped to P&L amount or return percentage
- Click any cell for a breakdown: per-stock contribution and transactions that day

### Quantitative Analysis
- **Risk tab**: portfolio style diagnosis (growth / value / defensive), weighted Beta, allocation chart, optimization recommendations
- **Backtest tab**:
  - Simple rule builder — SMA, EMA, RSI, MACD, Bollinger Bands, Volume, KDJ, stop-loss, take-profit conditions
  - Advanced Python strategy editor for custom logic
  - Real-time progress stream during backtest execution (SSE)
  - Equity curve with buy/sell markers, Sharpe ratio, max drawdown, win rate, profit factor

### AI Assistant — Guanlan
- Floating chat panel with multi-turn conversation
- Streaming token-by-token responses via SSE
- Deep thinking mode for complex analysis
- Tool use: can trigger portfolio analysis and backtests mid-conversation
- Saves AI-generated strategies directly to the backtest library
- Configurable provider: Alibaba Cloud Bailian, OpenAI, DeepSeek, Moonshot, Zhipu GLM, Anthropic Claude, or any OpenAI-compatible endpoint

### Settings
- Light / dark / system theme
- Color scheme: red-up/green-down (A-share convention) or green-up/red-down (international)
- Base currency selection
- Avatar upload
- Password change and account deletion

### Admin Panel
- Live database status: table sizes, stock counts, price row counts per market
- Data crawler control: start / pause / resume / stop per market
- Crawl history audit log
- User management: list, delete, impersonate

---

## Tech Stack

### Backend

| | |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.3.5 |
| Database | MySQL (JdbcTemplate — no ORM) |
| JSON | Gson 2.10.1 |
| Password hashing | jBCrypt 0.4 |
| Pinyin search | Pinyin4j 2.5.1 |
| Build | Maven 3.x |

### Frontend

| | |
|---|---|
| Framework | React 19 |
| Language | TypeScript |
| Build tool | Vite 8 |
| Routing | React Router 7 |
| Styling | Tailwind CSS 4 |
| Component primitives | Radix UI |
| Charts | Recharts 3 · ECharts 6 |
| Animation | Framer Motion 12 |
| Icons | Lucide React |

### Desktop

| | |
|---|---|
| Framework | Electron 33 |
| Auto-update | electron-updater |
| Build tool | electron-builder |

### Python Scripts

| | |
|---|---|
| Runtime | Python 3.8+ |
| Data fetching | Yahoo Finance, Sina Finance, East Money |
| AI Agent | Custom agent framework with tool use |
| Backtesting | Custom backtest engine |

---

## Project Structure

```
investory/
├── backend/                          # Spring Boot (Maven)
│   ├── pom.xml                       # parent: spring-boot-starter-parent 3.3.5, Java 17
│   ├── src/main/java/com/investory/
│   │   ├── InvestoryApplication.java # main class, @SpringBootApplication
│   │   ├── controller/               # page controllers + api/ REST controllers
│   │   ├── dao/                      # @Repository + JdbcTemplate (via BaseDao)
│   │   ├── service/                  # @Service
│   │   ├── model/                    # POJOs
│   │   ├── crawler/                  # @Component, @Scheduled
│   │   └── config/                   # WebConfig (interceptors, static resources)
│   └── src/main/resources/
│       ├── application.properties    # datasource, server.servlet.context-path=/investory
│       ├── templates/                # Thymeleaf HTML
│       └── static/                   # built frontend output (Vite writes here)
├── frontend/
│   ├── package.json                  # scripts: dev, build (tsc -b && vite build)
│   ├── vite.config.ts                # proxy /investory → localhost:8443, base: /investory/
│   └── src/
│       ├── pages/                    # Dashboard, Holdings, Transactions, PnlCalendar, StockDetail, Portfolio, Settings
│       ├── components/               # Layout, CloudChart, ui/*
│       ├── hooks/                    # useAuth, useSettings
│       ├── services/api.ts           # API client (prefix /investory)
│       └── types/index.ts            # TypeScript interfaces
├── desktop/
│   ├── main.js                       # Electron main process
│   ├── preload.js                    # Preload script (context isolation)
│   ├── package.json                  # electron-builder configuration
│   └── assets/                       # App icons and installer images
└── script/
    ├── ai_agent.py                   # AI investment assistant (Guanlan)
    ├── backtest_engine.py            # Strategy backtesting engine
    ├── fetch_stocks.py               # Market data crawler (A-shares, HK, US)
    ├── fetch_news.py                 # Financial news aggregator
    ├── analyze_quant.py              # Quantitative analysis
    ├── portfolio_style_analyzer.py   # Portfolio style diagnosis
    ├── optimizer.py                  # Portfolio optimization
    ├── config.ini                    # Configuration (copy from config.ini.example)
    └── agent_skills/                 # AI agent tool definitions
```

---

## Live Demo

The project is deployed on a cloud server. You can access it directly at:

**https://116.62.179.231:8443/investory/**

> **Note**: The server uses a self-signed SSL certificate. Your browser may show a security warning — click "Advanced" → "Proceed" to continue.

---

## Getting Started

### Prerequisites

- Java 17
- Maven 3.9+
- Node.js 20+ / npm
- Python 3.8+
- MySQL 8

### Database

Create a schema and update credentials in `backend/src/main/resources/application.properties`:

```sql
CREATE DATABASE investory_db CHARACTER SET utf8mb4;
```

### Backend (Spring Boot)

The Maven build automatically compiles the frontend (`tsc -b && vite build`) and copies the output into `backend/src/main/resources/static/` before packaging the JAR.

**Full stack build + run:**

```bash
export JAVA_HOME=/path/to/jdk-17
mvn -f backend/pom.xml spring-boot:run -DskipTests
```

**Build production JAR:**

```bash
mvn -f backend/pom.xml package -DskipTests
java -jar backend/target/investory.jar
```

App is served at `https://localhost:8443/investory/`.

### Frontend (Vite)

**Development (hot-reload):**

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173/investory/
# API calls are proxied to localhost:8443
```

**Build only:**

```bash
cd frontend
npm run build
# Output goes to ../backend/src/main/resources/static/
```

### Python Scripts

Install dependencies:

```bash
cd script
pip install -r requirements.txt
```

Configure database and proxy settings:

```bash
cp config.ini.example config.ini
# Edit config.ini with your MySQL credentials and proxy settings
```

Run market data crawler:

```bash
python fetch_stocks.py --market a      # A-shares
python fetch_stocks.py --market hk     # Hong Kong
python fetch_stocks.py --market us     # US stocks
```

Run AI assistant:

```bash
python ai_agent.py
```

### Desktop (Electron)

**Development:**

```bash
cd desktop
npm install
npm run build:frontend    # Build frontend for Electron
npm start                 # Launch Electron app
```

**Build installer:**

```bash
cd desktop
npm run build:exe         # Build NSIS installer (.exe)
npm run build:msi         # Build MSI installer
```

---

## API Overview

| Group | Endpoints |
|---|---|
| Auth | `GET /api/session` · `POST /api/password` · `DELETE /api/account` |
| Portfolios | CRUD `/api/portfolios` · `POST /api/portfolio/refresh` |
| Dashboard | `GET /api/dashboard` · `GET /api/cash` · `GET /api/closed-positions` |
| Holdings | `GET /api/holdings` |
| Transactions | CRUD `/api/transactions` |
| Dividends | CRUD `/api/dividends` |
| Stocks | `GET /api/search` · `GET /api/quote/{symbol}` · `GET /api/chart` · `GET /api/stocks/{symbol}` |
| P&L | `GET /api/daily-detail` · `GET /api/monthly-detail` |
| Watchlist | CRUD `/api/watchlist` · `PUT /api/watchlist/reorder` |
| Market | `GET /api/market/indices` · `/exchange-rates` · `/news` |
| Quant | `GET /api/quant/holdings-metrics` · `/portfolio-style` · `/portfolio-scenario` |
| Backtest | CRUD `/api/backtest/strategies` · `POST /api/backtest/start` · `GET /api/backtest/stream` (SSE) |
| AI | `POST /api/ai/chat` · `GET /api/ai/stream` (SSE) · `GET/POST /api/ai/settings` |
| Admin | `/api/admin/status` · `/users` · `/crawl-history` · `/crawl/{market}` |

---

## License

MIT
