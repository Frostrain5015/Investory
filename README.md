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
- Configurable provider: Alibaba Cloud Bailian (default, `qwen-plus`), OpenAI, DeepSeek, Moonshot, Zhipu GLM, Anthropic Claude, or any OpenAI-compatible endpoint

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

The Maven build automatically compiles the frontend (`tsc -b && vite build`) and copies the output into `backend/src/main/resources/static/` before packaging the JAR.

---

## Project Structure

```
investory/
├── backend/
│   ├── pom.xml
│   └── src/main/java/com/investory/
│       ├── InvestoryApplication.java
│       ├── controller/
│       │   ├── api/          # REST endpoints
│       │   └── page/         # Thymeleaf page controllers
│       ├── dao/              # JdbcTemplate repositories
│       ├── service/
│       ├── model/
│       ├── crawler/          # Scheduled market data crawlers
│       └── config/
└── frontend/
    ├── package.json
    ├── vite.config.ts
    └── src/
        ├── pages/            # Dashboard, Holdings, Transactions, Market, Quant, ...
        ├── components/       # Layout, ChatPanel, CloudChart, ui/*
        ├── hooks/            # useAuth, useSettings, useTheme
        ├── services/api.ts
        └── types/index.ts
```

---

## Getting Started

### Prerequisites

- Java 17
- Maven 3.9+
- Node.js 20+ / npm
- MySQL 8

### Database

Create a schema and update credentials in `backend/src/main/resources/application.properties`:

```sql
CREATE DATABASE investory_db CHARACTER SET utf8mb4;
```

### Run in development

**Full stack via Spring Boot:**

```bash
export JAVA_HOME=/path/to/jdk-17
mvn -f backend/pom.xml spring-boot:run -DskipTests
```

**Frontend hot-reload (Vite dev server):**

```bash
cd frontend && npm install && npm run dev
# → http://localhost:5173/investory/
# API calls are proxied to localhost:8080
```

### Build production JAR

```bash
mvn -f backend/pom.xml package -DskipTests
java -jar backend/target/investory.jar
```

App is served at `https://localhost:8443/investory/`.

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
