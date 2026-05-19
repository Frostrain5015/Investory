package com.investory.controller.api;

import com.investory.dao.StockDao;
import com.investory.dao.StockPriceDao;
import com.investory.model.*;
import com.investory.service.HoldingService;
import com.investory.service.PortfolioAnalysisService;
import com.investory.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ChartDataController {

    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;
    @Autowired private HoldingService holdingService;
    @Autowired private PortfolioAnalysisService analysisService;

    @GetMapping(value = "/chart", produces = MediaType.APPLICATION_JSON_VALUE)
    public String chart(@RequestParam(required = false) String type,
                        @RequestParam(required = false) String symbol,
                        @RequestParam(required = false) Integer days,
                        @RequestParam(required = false) Long portfolioId,
                        @RequestParam(required = false) Integer year,
                        HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            resp.setStatus(401);
            return "{\"error\":\"unauthorized\"}";
        }
        long pid = resolvePortfolioId(portfolioId, session);
        try {
            return switch (type != null ? type : "") {
                case "price"             -> priceData(symbol, days != null ? days : 180);
                case "allocation"        -> allocationData(pid);
                case "pnl_rank"          -> pnlRankData(pid);
                case "pnl_calendar"      -> pnlCalendarData(pid, year);
                case "cumulative_return" -> cumulativeReturnData(pid, days != null ? days : 365);
                default -> "{\"error\":\"unknown type\"}";
            };
        } catch (Exception e) {
            resp.setStatus(500);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String priceData(String symbol, int days) {
        Stock stock = stockDao.findBySymbol(symbol);
        if (stock == null) return "[]";
        LocalDate to   = LocalDate.now();
        LocalDate from = to.minusDays(days);
        List<StockPrice> prices = stockPriceDao.findRange(stock.getId(), from, to);
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

    private String allocationData(long portfolioId) {
        List<HoldingSnapshot> snapshots = holdingService.getSnapshots(portfolioId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (HoldingSnapshot s : snapshots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",   s.getStockName());
            m.put("symbol", s.getStockSymbol());
            m.put("value",  s.getMarketValue());
            m.put("pct",    s.getMarketValue());
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }

    private String pnlRankData(long portfolioId) {
        List<HoldingSnapshot> snapshots = holdingService.getSnapshots(portfolioId);
        snapshots.sort(Comparator.comparing(HoldingSnapshot::getUnrealizedPnl));
        List<Map<String, Object>> result = new ArrayList<>();
        for (HoldingSnapshot s : snapshots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",   s.getStockName());
            m.put("symbol", s.getStockSymbol());
            m.put("pnl",    s.getUnrealizedPnl());
            m.put("pnlPct", s.getUnrealizedPnlPct());
            result.add(m);
        }
        return JsonUtil.toJson(result);
    }

    private String pnlCalendarData(long portfolioId, Integer year) {
        int y = year != null ? year : LocalDate.now().getYear();
        LocalDate from = LocalDate.of(y, 1, 1);
        LocalDate to   = LocalDate.of(y, 12, 31);
        List<DailyValue> values = analysisService.getDailyValues(portfolioId, from, to);
        List<Object[]> result = new ArrayList<>();
        for (DailyValue v : values) {
            result.add(new Object[]{ v.getSnapshotDate().toString(), v.getDailyPnl() });
        }
        return JsonUtil.toJson(result);
    }

    private String cumulativeReturnData(long portfolioId, int days) {
        LocalDate from = LocalDate.now().minusDays(days);
        LocalDate to   = LocalDate.now();
        List<DailyValue> values = analysisService.getDailyValues(portfolioId, from, to);
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

    private long resolvePortfolioId(Long param, HttpSession session) {
        if (param != null) return param;
        Object id = session.getAttribute("portfolioId");
        return id != null ? (Long) id : 0L;
    }
}
