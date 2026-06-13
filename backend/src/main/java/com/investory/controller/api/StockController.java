package com.investory.controller.api;

import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.model.Quote;
import com.investory.service.*;
import com.investory.server.AppContext;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * 股票行情与持仓快照控制器
 *
 * <p>负责模块：当前持仓列表查询、历史已平仓头寸查询、
 *   个股详情（含实时报价）、单只股票/全组合行情刷新。
 * <p>API 基础路径：/api
 *
 * <p>所有接口均从 HttpSession 中隐式读取当前用户的 portfolioId，
 * 无需在请求参数中显式传递，确保数据隔离。
 */
public class StockController {

    private final StockDao stockDao = AppContext.get(StockDao.class);
    private final StockPriceDao stockPriceDao = AppContext.get(StockPriceDao.class);
    private final HoldingDao holdingDao = AppContext.get(HoldingDao.class);
    private final HoldingService holdingService = AppContext.get(HoldingService.class);
    private final TransactionDao transactionDao = AppContext.get(TransactionDao.class);
    private final DividendDao dividendDao = AppContext.get(DividendDao.class);
    private final RealtimeQuoteService quoteService = AppContext.get(RealtimeQuoteService.class);
    private final PortfolioAnalysisService analysisService = AppContext.get(PortfolioAnalysisService.class);

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    public void handleHoldings(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> result = Map.of("snapshots", holdingService.getSnapshots(getPortfolioId(req)));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleClosedPositions(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        List<Map<String, Object>> result = analysisService.getClosedPositions(getPortfolioId(req));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleDetail(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        String symbol = (String) req.getAttribute("symbol");
        if (pid == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "No portfolio")));
            return;
        }
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "Stock not found")));
            return;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stock", stock); r.put("holding", holdingDao.findByPortfolioAndStock(pid, stock.getId()));
        r.put("transactions", transactionDao.findByPortfolioAndStock(pid, stock.getId()));
        r.put("dividends", dividendDao.findByPortfolioAndStock(pid, stock.getId()));
        Quote q = quoteService.getQuote(stock);
        r.put("livePrice", q != null ? q.price() : null);
        r.put("livePriceTs", q != null ? q.fetchedAt().toString() : (stockPriceDao.findLatest(stock.getId()) != null ? stockPriceDao.findLatest(stock.getId()).getTradeDate().toString() : null));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(r));
    }

    public void handleQuote(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbol = (String) req.getAttribute("symbol");
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "Stock not found")));
            return;
        }
        BigDecimal price = quoteService.getPrice(stock);
        BigDecimal cached = stockPriceDao.findLatestClose(stock.getId());
        Map<String, Object> result = Map.of("symbol", symbol, "price", price != null ? price : cached, "live", price != null);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleRefresh(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbol = (String) req.getAttribute("symbol");
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "Stock not found")));
            return;
        }
        quoteService.getPrice(stock);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok")));
    }

    public void handleRefreshPortfolio(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        if (pid == 0) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(JsonUtil.toJson(Map.of("error", "No portfolio")));
            return;
        }
        List<HoldingSnapshot> snaps = holdingService.getSnapshots(pid);
        for (HoldingSnapshot snap : snaps) {
            Stock s = stockDao.findBySymbol(snap.getStockSymbol());
            if (s != null) quoteService.getPrice(s);
        }
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(Map.of("status", "ok", "count", String.valueOf(snaps.size()))));
    }
}
