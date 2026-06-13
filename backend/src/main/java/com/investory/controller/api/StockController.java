package com.investory.controller.api;

import com.investory.crawler.RealtimeQuoteService;
import com.investory.dao.*;
import com.investory.model.*;
import com.investory.server.AppContext;
import com.investory.service.*;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.*;
import java.util.*;

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
        resp.setContentType("application/json;charset=UTF-8");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshots", holdingService.getSnapshots(getPortfolioId(req)));
        resp.getWriter().write(JsonUtil.toJson(result));
    }

    public void handleClosedPositions(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JsonUtil.toJson(analysisService.getClosedPositions(getPortfolioId(req))));
    }

    public void handleDetail(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        String symbol = (String) req.getAttribute("symbol");
        resp.setContentType("application/json;charset=UTF-8");
        if (pid == 0) {
            resp.getWriter().write("{\"error\":\"No portfolio\"}");
            return;
        }
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) {
            resp.getWriter().write("{\"error\":\"Stock not found\"}");
            return;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stock", stock);
        r.put("holding", holdingDao.findByPortfolioAndStock(pid, stock.getId()));
        r.put("transactions", transactionDao.findByPortfolioAndStock(pid, stock.getId()));
        r.put("dividends", dividendDao.findByPortfolioAndStock(pid, stock.getId()));
        Quote q = quoteService.getQuote(stock);
        r.put("livePrice", q != null ? q.price() : null);
        r.put("livePriceTs", q != null ? q.fetchedAt().toString() : (stockPriceDao.findLatest(stock.getId()) != null ? stockPriceDao.findLatest(stock.getId()).getTradeDate().toString() : null));
        resp.getWriter().write(JsonUtil.toJson(r));
    }

    public void handleQuote(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbol = (String) req.getAttribute("symbol");
        resp.setContentType("application/json;charset=UTF-8");
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) {
            resp.getWriter().write("{\"error\":\"Stock not found\"}");
            return;
        }
        java.math.BigDecimal price = quoteService.getPrice(stock);
        java.math.BigDecimal cached = stockPriceDao.findLatestClose(stock.getId());
        resp.getWriter().write(JsonUtil.toJson(Map.of("symbol", symbol, "price", price != null ? price : cached, "live", price != null)));
    }

    public void handleRefresh(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String symbol = (String) req.getAttribute("symbol");
        resp.setContentType("application/json;charset=UTF-8");
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) {
            resp.getWriter().write("{\"error\":\"Stock not found\"}");
            return;
        }
        quoteService.getPrice(stock);
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    public void handleRefreshPortfolio(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long pid = getPortfolioId(req);
        resp.setContentType("application/json;charset=UTF-8");
        if (pid == 0) {
            resp.getWriter().write("{\"error\":\"No portfolio\"}");
            return;
        }
        List<HoldingSnapshot> snaps = holdingService.getSnapshots(pid);
        for (HoldingSnapshot snap : snaps) {
            Stock s = stockDao.findBySymbol(snap.getStockSymbol());
            if (s != null) quoteService.getPrice(s);
        }
        resp.getWriter().write("{\"status\":\"ok\",\"count\":\"" + snaps.size() + "\"}");
    }
}
