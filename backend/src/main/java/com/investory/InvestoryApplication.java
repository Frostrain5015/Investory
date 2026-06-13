package com.investory;

import com.investory.server.*;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class InvestoryApplication {
    private static final Logger log = Logger.getLogger(InvestoryApplication.class.getName());

    public static void main(String[] args) throws Exception {
        ConfigLoader.load("application.properties");
        DatabaseManager.init();
        initComponents();
        startTomcat();
    }

    private static void initComponents() {
        log.info("Initializing components...");
        com.investory.dao.UserDao userDao = new com.investory.dao.UserDao();
        com.investory.dao.PortfolioDao portfolioDao = new com.investory.dao.PortfolioDao();
        com.investory.dao.HoldingDao holdingDao = new com.investory.dao.HoldingDao();
        com.investory.dao.TransactionDao transactionDao = new com.investory.dao.TransactionDao();
        com.investory.dao.StockDao stockDao = new com.investory.dao.StockDao();
        com.investory.dao.StockPriceDao stockPriceDao = new com.investory.dao.StockPriceDao();
        com.investory.dao.DividendDao dividendDao = new com.investory.dao.DividendDao();
        com.investory.dao.DailyPortfolioValueDao dailyPvDao = new com.investory.dao.DailyPortfolioValueDao();
        com.investory.dao.BacktestDao backtestDao = new com.investory.dao.BacktestDao();
        com.investory.dao.StrategyDao strategyDao = new com.investory.dao.StrategyDao();
        com.investory.dao.QuantCacheDao quantCacheDao = new com.investory.dao.QuantCacheDao();
        com.investory.dao.McpTokenDao mcpTokenDao = new com.investory.dao.McpTokenDao();
        com.investory.dao.StocksageCacheDao stocksageCacheDao = new com.investory.dao.StocksageCacheDao();
        AppContext.register(com.investory.dao.UserDao.class, userDao);
        AppContext.register(com.investory.dao.PortfolioDao.class, portfolioDao);
        AppContext.register(com.investory.dao.HoldingDao.class, holdingDao);
        AppContext.register(com.investory.dao.TransactionDao.class, transactionDao);
        AppContext.register(com.investory.dao.StockDao.class, stockDao);
        AppContext.register(com.investory.dao.StockPriceDao.class, stockPriceDao);
        AppContext.register(com.investory.dao.DividendDao.class, dividendDao);
        AppContext.register(com.investory.dao.DailyPortfolioValueDao.class, dailyPvDao);
        AppContext.register(com.investory.dao.BacktestDao.class, backtestDao);
        AppContext.register(com.investory.dao.StrategyDao.class, strategyDao);
        AppContext.register(com.investory.dao.QuantCacheDao.class, quantCacheDao);
        AppContext.register(com.investory.dao.McpTokenDao.class, mcpTokenDao);
        AppContext.register(com.investory.dao.StocksageCacheDao.class, stocksageCacheDao);

        com.investory.crawler.CrawlSessionManager crawlSession = new com.investory.crawler.CrawlSessionManager();
        com.investory.crawler.AiSessionManager aiSession = new com.investory.crawler.AiSessionManager();
        com.investory.crawler.BacktestSessionManager backtestSession = new com.investory.crawler.BacktestSessionManager();
        AppContext.register(com.investory.crawler.CrawlSessionManager.class, crawlSession);
        AppContext.register(com.investory.crawler.AiSessionManager.class, aiSession);
        AppContext.register(com.investory.crawler.BacktestSessionManager.class, backtestSession);

        com.investory.crawler.EastMoneyCrawler eastMoneyCrawler = new com.investory.crawler.EastMoneyCrawler();
        com.investory.crawler.RealtimeQuoteService realtimeQuoteService = new com.investory.crawler.RealtimeQuoteService();
        AppContext.register(com.investory.crawler.EastMoneyCrawler.class, eastMoneyCrawler);
        AppContext.register(com.investory.crawler.RealtimeQuoteService.class, realtimeQuoteService);

        com.investory.service.PortfolioAnalysisService portfolioAnalysisService = new com.investory.service.PortfolioAnalysisService();
        com.investory.service.CostCalculationService costCalcService = new com.investory.service.CostCalculationService();
        com.investory.service.AuthService authService = new com.investory.service.AuthService();
        com.investory.service.HoldingService holdingService = new com.investory.service.HoldingService();
        com.investory.service.PortfolioValueCalculator portfolioValueCalc = new com.investory.service.PortfolioValueCalculator();
        com.investory.service.PnlLedgerService pnlLedgerService = new com.investory.service.PnlLedgerService();
        com.investory.service.StocksageAlphaService stocksageAlphaService = new com.investory.service.StocksageAlphaService();
        com.investory.service.McpConfirmStore mcpConfirmStore = new com.investory.service.McpConfirmStore();
        com.investory.service.McpToolRegistry mcpToolRegistry = new com.investory.service.McpToolRegistry();
        com.investory.service.StockSearchIndexService stockSearchIndexService = new com.investory.service.StockSearchIndexService();
        AppContext.register(com.investory.service.PortfolioAnalysisService.class, portfolioAnalysisService);
        AppContext.register(com.investory.service.CostCalculationService.class, costCalcService);
        AppContext.register(com.investory.service.AuthService.class, authService);
        AppContext.register(com.investory.service.HoldingService.class, holdingService);
        AppContext.register(com.investory.service.PortfolioValueCalculator.class, portfolioValueCalc);
        AppContext.register(com.investory.service.PnlLedgerService.class, pnlLedgerService);
        AppContext.register(com.investory.service.StocksageAlphaService.class, stocksageAlphaService);
        AppContext.register(com.investory.service.McpConfirmStore.class, mcpConfirmStore);
        AppContext.register(com.investory.service.McpToolRegistry.class, mcpToolRegistry);
        AppContext.register(com.investory.service.StockSearchIndexService.class, stockSearchIndexService);

        ExecutorService indexExecutor = Executors.newFixedThreadPool(25, r -> {
            Thread t = new Thread(r, "index-fetcher"); t.setDaemon(true); return t;
        });
        AppContext.register(ExecutorService.class, indexExecutor);

        com.investory.crawler.CrawlerScheduler crawlerScheduler = new com.investory.crawler.CrawlerScheduler();
        com.investory.crawler.StocksageScheduler stocksageScheduler = new com.investory.crawler.StocksageScheduler();
        AppContext.register(com.investory.crawler.CrawlerScheduler.class, crawlerScheduler);
        AppContext.register(com.investory.crawler.StocksageScheduler.class, stocksageScheduler);

        new com.investory.util.StocksageAlphaExecutor();
        SchedulerService schedulerService = new SchedulerService();
        schedulerService.registerAll(crawlerScheduler, stocksageScheduler);
        schedulerService.start();
        new com.investory.crawler.StockDataInitializer().init();

        log.info("All components registered.");
        AppContext.markInitialized();
    }

    private static void startTomcat() throws Exception {
        int port = ConfigLoader.getInt("server.port", 8443);
        String contextPath = ConfigLoader.get("server.servlet.context-path", "/investory");
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.getConnector();
        String baseDir = System.getProperty("java.io.tmpdir") + "/tomcat-investory";
        new File(baseDir).mkdirs();
        Context ctx = tomcat.addWebapp(contextPath, new File("src/main/webapp").getAbsolutePath());
        StandardRoot resources = new StandardRoot(ctx);
        File classesDir = new File("target/classes");
        if (classesDir.exists()) resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes", classesDir.getAbsolutePath(), "/"));
        File staticDir = new File("src/main/resources/static");
        if (staticDir.exists()) resources.addPreResources(new DirResourceSet(resources, "/static", staticDir.getAbsolutePath(), "/"));
        ctx.setResources(resources);
        ServletRouter router = new ServletRouter();
        RouteRegistrar.register(router);
        Tomcat.addServlet(ctx, "router", router);
        ctx.addServletMappingDecoded("/*", "router");
        jakarta.servlet.FilterRegistration.Dynamic cors = ctx.addFilter("cors", new CorsFilter());
        cors.addMappingForUrlPatterns(null, false, "/*");
        jakarta.servlet.FilterRegistration.Dynamic auth = ctx.addFilter("auth", new AuthFilter());
        auth.addMappingForUrlPatterns(null, false, "/*");
        jakarta.servlet.FilterRegistration.Dynamic err = ctx.addFilter("error", new ErrorFilter());
        err.addMappingForUrlPatterns(null, false, "/*");
        log.info("Starting Tomcat on port " + port + ", contextPath=" + contextPath);
        tomcat.start();
        log.info("Investory running at http://localhost:" + port + contextPath + "/");
        tomcat.getServer().await();
    }
}
