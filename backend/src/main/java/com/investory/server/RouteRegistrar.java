package com.investory.server;

import com.investory.controller.*;
import com.investory.controller.api.*;

/**
 * Registers ALL controller routes to the ServletRouter.
 * Called during application startup.
 */
public class RouteRegistrar {

    public static void register(ServletRouter router) {

        // ── SpaController ──
        SpaController spa = new SpaController();
        String[] spaRoutes = {"/", "/login", "/register", "/market", "/watchlist", "/dashboard",
            "/portfolio", "/holdings", "/transactions", "/transactions/*",
            "/dividends", "/dividends/*", "/stock", "/pnl-calendar", "/admin"};
        for (String r : spaRoutes) {
            router.addRoute("GET", r, spa::handleServeSpa);
        }
        router.addRoute("GET", "/logout", spa::handleLogout);

        // ── SessionController ──
        SessionController session = new SessionController();
        router.addRoute("GET",  "/api/session", session::handleGetSession);
        // Auth endpoints (also handled by SpaController originally)
        // The SessionController handles these
        router.addRoute("POST", "/login", session::handleTestLogin);
        router.addRoute("POST", "/register", session::handleTestLogin);

        // ── StockSearchApiController ──
        StockSearchApiController search = new StockSearchApiController();
        router.addRoute("GET", "/api/stock/search", search::handleSearch);

        // ── WatchlistController ──
        WatchlistController watchlist = new WatchlistController();
        router.addRoute("GET",    "/api/watchlist", watchlist::handleGetWatchlist);
        router.addRoute("POST",   "/api/watchlist", watchlist::handleAdd);
        router.addRoute("DELETE", "/api/watchlist/{stockId}", watchlist::handleRemove);
        router.addRoute("PUT",    "/api/watchlist/reorder", watchlist::handleReorder);

        // ── AiSettingsController ──
        AiSettingsController aiSettings = new AiSettingsController();
        router.addRoute("GET",    "/api/ai/settings", aiSettings::handleGetSettings);
        router.addRoute("POST",   "/api/ai/settings", aiSettings::handleSaveSettings);
        router.addRoute("DELETE", "/api/ai/settings", aiSettings::handleResetSettings);
        router.addRoute("GET",    "/api/ai/models", aiSettings::handleListModels);

        // ── StrategyApiController ──
        StrategyApiController strategy = new StrategyApiController();
        router.addRoute("GET",    "/api/backtest/strategies", strategy::handleList);
        router.addRoute("POST",   "/api/backtest/strategies", strategy::handleSave);
        router.addRoute("GET",    "/api/backtest/strategies/{id}", strategy::handleGet);
        router.addRoute("DELETE", "/api/backtest/strategies/{id}", strategy::handleDelete);

        // ── PnlDetailController ──
        PnlDetailController pnl = new PnlDetailController();
        router.addRoute("GET", "/api/daily-detail", pnl::handleDaily);
        router.addRoute("GET", "/api/monthly-detail", pnl::handleMonthly);

        // ── MarketIndexController ──
        MarketIndexController market = new MarketIndexController();
        router.addRoute("GET", "/api/market/indices", market::handleGetIndices);
        router.addRoute("GET", "/api/market/news", market::handleGetNews);
        router.addRoute("GET", "/api/market/world", market::handleGetWorldData);

        // ── DividendController ──
        DividendController div = new DividendController();
        router.addRoute("GET",    "/api/dividends", div::handleList);
        router.addRoute("POST",   "/api/dividends", div::handleCreate);
        router.addRoute("GET",    "/api/dividends/{id}", div::handleGetOne);
        router.addRoute("PUT",    "/api/dividends/{id}", div::handleUpdate);
        router.addRoute("DELETE", "/api/dividends/{id}", div::handleDelete);

        // ── TransactionController ──
        TransactionController txn = new TransactionController();
        router.addRoute("GET",    "/api/transactions", txn::handleList);
        router.addRoute("POST",   "/api/transactions", txn::handleCreate);
        router.addRoute("GET",    "/api/transactions/{id}", txn::handleGetOne);
        router.addRoute("PUT",    "/api/transactions/{id}", txn::handleUpdate);
        router.addRoute("DELETE", "/api/transactions/{id}", txn::handleDelete);

        // ── StockController ──
        StockController stock = new StockController();
        router.addRoute("GET",    "/api/holdings", stock::handleHoldings);
        router.addRoute("GET",    "/api/closed-positions", stock::handleClosedPositions);
        router.addRoute("GET",    "/api/stocks/{symbol}", stock::handleDetail);
        router.addRoute("GET",    "/api/quote/{symbol}", stock::handleQuote);
        router.addRoute("POST",   "/api/stocks/{symbol}/refresh", stock::handleRefresh);
        router.addRoute("POST",   "/api/portfolio/refresh", stock::handleRefreshPortfolio);

        // ── PortfolioController ──
        PortfolioController portfolio = new PortfolioController();
        router.addRoute("GET",    "/api/portfolios", portfolio::handleList);
        router.addRoute("POST",   "/api/portfolios", portfolio::handleCreate);
        router.addRoute("PUT",    "/api/portfolios/{id}", portfolio::handleUpdate);
        router.addRoute("DELETE", "/api/portfolios/{id}", portfolio::handleDelete);
        router.addRoute("GET",    "/api/dashboard", portfolio::handleDashboard);
        router.addRoute("GET",    "/api/cash", portfolio::handleCash);
        router.addRoute("POST",   "/api/admin/backfill", portfolio::handleAdminBackfill);

        // ── ChartDataController ──
        ChartDataController chart = new ChartDataController();
        router.addRoute("GET", "/api/chart", chart::handleChart);

        // ── Session / Account ──
        router.addRoute("DELETE", "/api/account", session::handleTestLogin);

        // ── AiApiController ──
        AiApiController ai = new AiApiController();
        router.addRoute("POST",   "/api/ai/chat", ai::handleChat);
        router.addRoute("GET",    "/api/ai/suggestions", ai::handleSuggestions);
        router.addRoute("GET",    "/api/ai/stream", ai::handleStream);
        router.addRoute("GET",    "/api/ai/status", ai::handleStatus);
        router.addRoute("POST",   "/api/ai/clear", ai::handleClear);
        router.addRoute("POST",   "/api/ai/cancel", ai::handleCancel);
        router.addRoute("GET",    "/api/ai/history", ai::handleHistory);
        router.addRoute("POST",   "/api/ai/conversations", ai::handleCreateConversation);
        router.addRoute("GET",    "/api/ai/conversations", ai::handleListConversations);
        router.addRoute("GET",    "/api/ai/conversations/{id}", ai::handleGetConversation);
        router.addRoute("DELETE", "/api/ai/conversations/{id}", ai::handleDeleteConversation);
        router.addRoute("POST",   "/api/ai/answer", ai::handleAnswer);
        router.addRoute("GET",    "/api/ai/morning", ai::handleMorningGreeting);

        // ── AdminController ──
        AdminController admin = new AdminController();
        router.addRoute("GET",    "/api/admin/status", admin::handleGetStatus);
        router.addRoute("GET",    "/api/admin/users", admin::handleGetUsers);
        router.addRoute("POST",   "/api/admin/impersonate/{userId}", admin::handleImpersonate);
        router.addRoute("DELETE", "/api/admin/users/{userId}", admin::handleDeleteUser);
        router.addRoute("GET",    "/api/admin/crawl-history", admin::handleGetCrawlHistory);
        router.addRoute("DELETE", "/api/admin/crawl-history", admin::handleClearCrawlHistory);
        router.addRoute("GET",    "/api/admin/crawl/status", admin::handleCrawlStatus);
        router.addRoute("GET",    "/api/admin/crawl/{market}", admin::handleStartCrawl);
        router.addRoute("POST",   "/api/admin/crawl/stop", admin::handleStopCrawl);
        router.addRoute("POST",   "/api/admin/crawl/pause", admin::handlePauseCrawl);
        router.addRoute("POST",   "/api/admin/crawl/resume", admin::handleResumeCrawl);

        // ── BacktestApiController ──
        BacktestApiController backtest = new BacktestApiController();
        router.addRoute("POST",   "/api/backtest/start", backtest::handleStartBacktest);
        router.addRoute("GET",    "/api/backtest/status", backtest::handleGetStatus);
        router.addRoute("GET",    "/api/backtest/stream", backtest::handleStream);
        router.addRoute("GET",    "/api/backtest/history", backtest::handleHistory);
        router.addRoute("GET",    "/api/backtest/{id}", backtest::handleGetResult);
        router.addRoute("DELETE", "/api/backtest/{id}", backtest::handleDeleteResult);
        router.addRoute("POST",   "/api/backtest/compare", backtest::handleCompare);

        // ── QuantApiController ──
        QuantApiController quant = new QuantApiController();
        router.addRoute("GET", "/api/quant/holdings-metrics", quant::handleGetHoldingsMetrics);
        router.addRoute("GET", "/api/quant/portfolio-scenario", quant::handleGetPortfolioScenario);
        router.addRoute("GET", "/api/quant/portfolio-style", quant::handleGetPortfolioStyle);
        router.addRoute("GET", "/api/quant/refresh", quant::handleStartRefresh);
        router.addRoute("GET", "/api/quant/optimize", quant::handleOptimize);
        router.addRoute("GET", "/api/quant/context-summary", quant::handleContextSummary);
        router.addRoute("GET", "/api/quant/holdings-correlation", quant::handleHoldingsCorrelation);

        // ── StocksageApiController ──
        StocksageApiController stocksage = new StocksageApiController();
        router.addRoute("GET",  "/api/stocksage/factor-scores", stocksage::handleGetFactorScores);
        router.addRoute("GET",  "/api/stocksage/factor-breakdown", stocksage::handleGetFactorBreakdown);
        router.addRoute("GET",  "/api/stocksage/scan-results", stocksage::handleGetScanResults);
        router.addRoute("GET",  "/api/stocksage/regime", stocksage::handleGetRegime);
        router.addRoute("POST", "/api/stocksage/refresh", stocksage::handleRefresh);
        router.addRoute("GET",  "/api/stocksage/daily-picks", stocksage::handleGetDailyPicks);
        router.addRoute("GET",  "/api/stocksage/pick-history", stocksage::handleGetPickHistory);
        router.addRoute("POST", "/api/stocksage/pick-feedback", stocksage::handleSubmitPickFeedback);
        router.addRoute("POST", "/api/stocksage/analyze-portfolio", stocksage::handleAnalyzePortfolio);
        router.addRoute("GET",  "/api/stocksage/analyze-portfolio-stream", stocksage::handleAnalyzePortfolioStream);
        router.addRoute("GET",  "/api/stocksage/stock-analysis", stocksage::handleGetStockAnalysis);

        // ── McpController ──
        McpController mcp = new McpController();
        router.addRoute("POST", "/api/mcp", mcp::handlePost);
        router.addRoute("GET",  "/api/mcp/{sessionId}", mcp::handleGet);

        // ── McpAuthController ──
        McpAuthController mcpAuth = new McpAuthController();
        router.addRoute("POST",   "/api/mcp/auth/create", mcpAuth::handleCreate);
        router.addRoute("GET",    "/api/mcp/auth/list", mcpAuth::handleList);
        router.addRoute("POST",   "/api/mcp/auth/revoke", mcpAuth::handleRevoke);

        // ── McpOAuthController ──
        McpOAuthController mcpOAuth = new McpOAuthController();
        router.addRoute("GET",  "/api/mcp/oauth/.well-known/oauth-authorization-server", mcpOAuth::handleAuthServerMetadata);
        router.addRoute("POST", "/api/mcp/oauth/register", mcpOAuth::handleRegister);
        router.addRoute("GET",  "/api/mcp/oauth/authorize", mcpOAuth::handleAuthorize);
        router.addRoute("POST", "/api/mcp/oauth/token", mcpOAuth::handleToken);
        router.addRoute("GET",  "/api/mcp/oauth/resource", mcpOAuth::handleProtectedResource);

        // ── OAuthController ──
        OAuthController oauth = new OAuthController();
        router.addRoute("GET", "/api/oauth/frostid/login", oauth::handleFrostIdLogin);
        router.addRoute("GET", "/api/oauth/frostid/callback", oauth::handleFrostIdCallback);
    }
}
