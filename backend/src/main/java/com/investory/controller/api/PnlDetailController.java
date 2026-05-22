package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.Stock;
import com.investory.model.StockPrice;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class PnlDetailController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;

    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    private Map<String, BigDecimal> loadRates() {
        Map<String, BigDecimal> toCny = new HashMap<>(); toCny.put("CNY", BigDecimal.ONE);
        try {
            for (Map<String, Object> r : jdbc.queryForList("SELECT currency, rate FROM exchange_rates")) {
                String c = (String) r.get("currency");
                BigDecimal rate = (BigDecimal) r.get("rate");
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) toCny.put(c, BigDecimal.ONE.divide(rate, 8, java.math.RoundingMode.HALF_UP));
            }
        } catch (Exception ignored) {}
        return toCny;
    }

    @GetMapping("/daily-detail")
    public Map<String, Object> daily(@RequestParam String date, HttpServletRequest req) {
        long pid = getPortfolioId(req); LocalDate day = LocalDate.parse(date), prev = day.minusDays(1);
        Map<String, BigDecimal> toCny = loadRates();
        Map<Long, BigDecimal> shares = resolveShares(pid, day);
        return buildDetail(pid, date, shares, toCny, day, prev);
    }

    @GetMapping("/monthly-detail")
    public Map<String, Object> monthly(@RequestParam int year, @RequestParam int month, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        LocalDate sm = LocalDate.of(year, month, 1), em = sm.withDayOfMonth(sm.lengthOfMonth()), epm = sm.minusDays(1);
        Map<String, BigDecimal> toCny = loadRates();
        Map<Long, BigDecimal> shares = resolveShares(pid, epm);
        return buildDetail(pid, year + "-" + String.format("%02d", month), shares, toCny, em, epm);
    }

    private Map<Long, BigDecimal> resolveShares(long pid, LocalDate cutoff) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (Map<String, Object> t : jdbc.queryForList("SELECT stock_id, type, shares FROM transactions WHERE portfolio_id=? AND type IN ('BUY','SELL') AND trade_date <= ? ORDER BY trade_date", pid, java.sql.Date.valueOf(cutoff))) {
            long sid = ((Number) t.get("stock_id")).longValue();
            BigDecimal sh = (BigDecimal) t.get("shares"); if (sh == null) continue;
            m.put(sid, "BUY".equals(t.get("type")) ? m.getOrDefault(sid, BigDecimal.ZERO).add(sh) : m.getOrDefault(sid, BigDecimal.ZERO).subtract(sh));
        }
        return m;
    }

    private Map<String, Object> buildDetail(long pid, String label, Map<Long, BigDecimal> shares, Map<String, BigDecimal> toCny, LocalDate endDay, LocalDate prevDay) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("date", label);
        List<Map<String, Object>> holdings = new ArrayList<>(); BigDecimal totalPnl = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : shares.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;
            Stock st = stockDao.findById(e.getKey()); if (st == null) continue;
            BigDecimal rate = toCny.getOrDefault(st.getCurrency(), BigDecimal.ONE);
            List<StockPrice> tp = stockPriceDao.findRange(e.getKey(), endDay, endDay);
            List<StockPrice> pp = stockPriceDao.findRange(e.getKey(), prevDay, prevDay);
            if (tp.isEmpty() || pp.isEmpty()) continue;
            BigDecimal ct = tp.get(0).getClose(), cp = pp.get(0).getClose();
            if (ct == null || cp == null) continue;
            BigDecimal pnl = ct.subtract(cp).multiply(e.getValue()).multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal pct = cp.compareTo(BigDecimal.ZERO) > 0 ? ct.subtract(cp).divide(cp, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
            holdings.add(Map.of("stockName", st.getName(), "symbol", st.getSymbol(), "pnl", pnl, "priceChange", pct));
            totalPnl = totalPnl.add(pnl);
        }
        result.put("totalPnl", totalPnl.setScale(2, java.math.RoundingMode.HALF_UP)); result.put("holdings", holdings);
        result.put("transactions", jdbc.queryForList("SELECT t.type, s.name AS stockName, t.shares, t.price FROM transactions t LEFT JOIN stocks s ON t.stock_id=s.id WHERE t.portfolio_id=? AND t.trade_date=?", pid, java.sql.Date.valueOf(endDay)));
        return result;
    }
}
