package com.investory.servlet.api;

import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.*;
import com.investory.service.HoldingService;
import com.investory.service.PortfolioAnalysisService;
import com.investory.util.JsonUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * JSON API endpoints consumed by ECharts on the frontend.
 *
 * ?type=price&symbol=1.600519&days=180
 * ?type=allocation&portfolioId=1
 * ?type=pnl_rank&portfolioId=1
 * ?type=pnl_calendar&portfolioId=1&year=2025
 * ?type=cumulative_return&portfolioId=1&days=365
 */
@WebServlet("/api/chart")
public class ChartDataServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");

        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            resp.setStatus(401);
            resp.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }

        String type = req.getParameter("type");
        try {
            String json = switch (type != null ? type : "") {
                case "price"            -> priceData(req);
                case "allocation"       -> allocationData(req, session);
                case "pnl_rank"         -> pnlRankData(req, session);
                case "pnl_calendar"     -> pnlCalendarData(req, session);
                case "cumulative_return"-> cumulativeReturnData(req, session);
                default -> "{\"error\":\"unknown type\"}";
            };
            resp.getWriter().write(json);
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private String priceData(HttpServletRequest req) throws Exception {
        String symbol = req.getParameter("symbol");
        int days = paramInt(req, "days", 180);
        Stock stock = StockDao.get().findBySymbol(symbol);
        if (stock == null) return "[]";

        LocalDate to   = LocalDate.now();
        LocalDate from = to.minusDays(days);
        List<StockPrice> prices = StockPriceDao.get().findRange(stock.getId(), from, to);

        List<Map<String, Object>> result = new ArrayList<>();
        for (StockPrice p : prices) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date",   p.getTradeDate().toString());
            m.put("open",   p.getOpen());
            m.put("close",  p.getClose());
            m.put("high",   p.getHigh());
            m.put("low",    p.getLow());
            m.put("volume", p.getVolume());
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }

    private String allocationData(HttpServletRequest req, HttpSession session) throws Exception {
        long portfolioId = portfolioId(req, session);
        List<HoldingSnapshot> snapshots = HoldingService.get().getSnapshots(portfolioId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (HoldingSnapshot s : snapshots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",        s.getStockName());
            m.put("symbol",      s.getStockSymbol());
            m.put("value",       s.getMarketValue());
            m.put("pct",         s.getMarketValue()); // ECharts computes pct from values
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }

    private String pnlRankData(HttpServletRequest req, HttpSession session) throws Exception {
        long portfolioId = portfolioId(req, session);
        List<HoldingSnapshot> snapshots = HoldingService.get().getSnapshots(portfolioId);
        snapshots.sort(Comparator.comparing(HoldingSnapshot::getUnrealizedPnl));

        List<Map<String, Object>> result = new ArrayList<>();
        for (HoldingSnapshot s : snapshots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",      s.getStockName());
            m.put("symbol",    s.getStockSymbol());
            m.put("pnl",       s.getUnrealizedPnl());
            m.put("pnlPct",    s.getUnrealizedPnlPct());
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }

    private String pnlCalendarData(HttpServletRequest req, HttpSession session) throws Exception {
        long portfolioId = portfolioId(req, session);
        int year = paramInt(req, "year", LocalDate.now().getYear());
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to   = LocalDate.of(year, 12, 31);

        List<DailyValue> values = PortfolioAnalysisService.get().getDailyValues(portfolioId, from, to);
        List<Object[]> result = new ArrayList<>();
        for (DailyValue v : values) {
            result.add(new Object[]{ v.getSnapshotDate().toString(), v.getDailyPnl() });
        }
        return JsonUtil.toJson(result);
    }

    private String cumulativeReturnData(HttpServletRequest req, HttpSession session) throws Exception {
        long portfolioId = portfolioId(req, session);
        int days = paramInt(req, "days", 365);
        LocalDate from = LocalDate.now().minusDays(days);
        LocalDate to   = LocalDate.now();

        List<DailyValue> values = PortfolioAnalysisService.get().getDailyValues(portfolioId, from, to);
        List<Map<String, Object>> result = new ArrayList<>();
        for (DailyValue v : values) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", v.getSnapshotDate().toString());
            BigDecimal retPct = v.getTotalCost().compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : v.getTotalValue().subtract(v.getTotalCost())
                        .divide(v.getTotalCost(), 4, java.math.RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            m.put("return", retPct);
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }

    private long portfolioId(HttpServletRequest req, HttpSession session) {
        String param = req.getParameter("portfolioId");
        if (param != null) return Long.parseLong(param);
        Object id = session.getAttribute("portfolioId");
        return id != null ? (Long) id : 0L;
    }

    private int paramInt(HttpServletRequest req, String name, int def) {
        String v = req.getParameter(name);
        try { return v != null ? Integer.parseInt(v) : def; } catch (NumberFormatException e) { return def; }
    }
}
