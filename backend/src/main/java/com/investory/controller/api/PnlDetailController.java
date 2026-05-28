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

/**
 * 盈亏明细 REST 控制器，路径前缀 /api。
 *
 * 核心职责：
 * 计算并返回特定日期（单日）或特定月份的持仓盈亏明细，
 * 包含每只股票的盈亏金额、涨跌幅、市值及权重占比，
 * 以及当日/当月的交易记录列表。
 *
 * 汇率换算：所有金额统一折算为人民币（CNY）进行汇总，
 * 换算比例来自 exchange_rates 表（1/rate 即 CNY 等值系数）。
 */
@RestController
@RequestMapping("/api")
public class PnlDetailController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StockDao stockDao;
    @Autowired private StockPriceDao stockPriceDao;

    /**
     * 从 Session 中获取当前用户的活跃组合 id。
     *
     * @param req HTTP 请求
     * @return 组合 id，未登录或无组合时返回 0
     */
    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    /**
     * 加载外汇换算表：从 exchange_rates 表读取各货币对 CNY 的换算系数。
     * exchange_rates 存储的是"1 CNY 等于多少外币"（rate），
     * 因此 CNY→外币 系数为 1/rate。
     *
     * @return 货币代码 → CNY 换算系数的 Map，CNY 本身系数为 1.0
     */
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

    /**
     * 返回指定日期的持仓盈亏明细。
     * 以该日收盘价与前一日收盘价之差计算单日盈亏，并附上当日的交易记录。
     *
     * @param date 日期字符串（yyyy-MM-dd）
     * @param req  HTTP 请求（用于获取组合 id）
     * @return 盈亏明细 Map，包含 date、totalPnl、holdings、transactions
     */
    @GetMapping("/daily-detail")
    public Map<String, Object> daily(@RequestParam String date, HttpServletRequest req) {
        long pid = getPortfolioId(req); LocalDate day = LocalDate.parse(date), prev = day.minusDays(1);
        Map<String, BigDecimal> toCny = loadRates();
        // 计算截至当日持有的各股份额（截至前一日 EOD 持仓）
        Map<Long, BigDecimal> shares = resolveShares(pid, day);
        return buildDetail(pid, date, shares, toCny, day, prev);
    }

    /**
     * 返回指定年月的持仓盈亏明细。
     * 以月末收盘价与月初前一日（上月末）收盘价之差计算月度盈亏。
     *
     * @param year  年份（如 2026）
     * @param month 月份（1-12）
     * @param req   HTTP 请求（用于获取组合 id）
     * @return 盈亏明细 Map，包含 date（"yyyy-MM" 格式）、totalPnl、holdings、transactions
     */
    @GetMapping("/monthly-detail")
    public Map<String, Object> monthly(@RequestParam int year, @RequestParam int month, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        // sm=月初, em=月末, epm=上月末（用作"前一日"基准价）
        LocalDate sm = LocalDate.of(year, month, 1), em = sm.withDayOfMonth(sm.lengthOfMonth()), epm = sm.minusDays(1);
        Map<String, BigDecimal> toCny = loadRates();
        // 以上月末持仓作为月初参考份额
        Map<Long, BigDecimal> shares = resolveShares(pid, epm);
        return buildDetail(pid, year + "-" + String.format("%02d", month), shares, toCny, em, epm);
    }

    /**
     * 计算截至指定日期（含）的各股累计持有份额。
     * 遍历该日期及之前所有 BUY/SELL 交易，BUY 累加，SELL 扣减。
     *
     * @param pid    组合 id
     * @param cutoff 截止日期（含）
     * @return stockId → 持有份额 的 Map（份额为 0 或负数的股票不过滤，由调用方处理）
     */
    private Map<Long, BigDecimal> resolveShares(long pid, LocalDate cutoff) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (Map<String, Object> t : jdbc.queryForList("SELECT stock_id, type, shares FROM transactions WHERE portfolio_id=? AND type IN ('BUY','SELL') AND trade_date <= ? ORDER BY trade_date", pid, java.sql.Date.valueOf(cutoff))) {
            long sid = ((Number) t.get("stock_id")).longValue();
            BigDecimal sh = (BigDecimal) t.get("shares"); if (sh == null) continue;
            m.put(sid, "BUY".equals(t.get("type")) ? m.getOrDefault(sid, BigDecimal.ZERO).add(sh) : m.getOrDefault(sid, BigDecimal.ZERO).subtract(sh));
        }
        return m;
    }

    /**
     * 构建完整的盈亏明细结果。
     * 对每只持仓股票：
     * 1. 查询期末日和期初日（prevDay）的收盘价；
     * 2. 计算盈亏金额 = (endPrice - prevPrice) × shares × fxRate；
     * 3. 计算涨跌幅 = (endPrice - prevPrice) / prevPrice × 100；
     * 4. 计算市值 = endPrice × shares × fxRate；
     * 5. 全部汇总后计算各股权重占比。
     *
     * @param pid     组合 id（用于查询交易记录）
     * @param label   日期标签（yyyy-MM-dd 或 yyyy-MM），写入结果的 date 字段
     * @param shares  各股截止参考日的持有份额
     * @param toCny   货币 → CNY 换算系数
     * @param endDay  期末日（取收盘价用）
     * @param prevDay 期初前一日（取基准价用）
     * @return 包含 date、totalPnl、holdings（每股明细）、transactions（当期交易）的 Map
     */
    private Map<String, Object> buildDetail(long pid, String label, Map<Long, BigDecimal> shares, Map<String, BigDecimal> toCny, LocalDate endDay, LocalDate prevDay) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("date", label);
        List<Map<String, Object>> holdings = new ArrayList<>();
        BigDecimal totalPnl = BigDecimal.ZERO, totalMv = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : shares.entrySet()) {
            // 跳过份额为零或负数的持仓
            if (e.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;
            Stock st = stockDao.findById(e.getKey()); if (st == null) continue;
            BigDecimal rate = toCny.getOrDefault(st.getCurrency(), BigDecimal.ONE);
            // 查询期末收盘价与期初收盘价
            List<StockPrice> tp = stockPriceDao.findRange(e.getKey(), endDay, endDay);
            List<StockPrice> pp = stockPriceDao.findRange(e.getKey(), prevDay, prevDay);
            if (tp.isEmpty() || pp.isEmpty()) continue;
            BigDecimal ct = tp.get(0).getClose(), cp = pp.get(0).getClose();
            if (ct == null || cp == null) continue;
            // 盈亏金额（已折算为 CNY）
            BigDecimal pnl = ct.subtract(cp).multiply(e.getValue()).multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
            // 涨跌幅（百分比）
            BigDecimal pct = cp.compareTo(BigDecimal.ZERO) > 0 ? ct.subtract(cp).divide(cp, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
            // 市值（已折算为 CNY）
            BigDecimal mv = ct.multiply(e.getValue()).multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stockName", st.getName()); row.put("symbol", st.getSymbol());
            row.put("pnl", pnl); row.put("priceChange", pct); row.put("marketValue", mv);
            holdings.add(row);
            totalPnl = totalPnl.add(pnl); totalMv = totalMv.add(mv);
        }
        // 计算各股权重占比（市值 / 总市值）
        if (totalMv.compareTo(BigDecimal.ZERO) > 0) {
            for (Map<String, Object> row : holdings) {
                BigDecimal mv = (BigDecimal) row.get("marketValue");
                row.put("weightPct", mv.divide(totalMv, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(1, java.math.RoundingMode.HALF_UP));
            }
        }
        result.put("totalPnl", totalPnl.setScale(2, java.math.RoundingMode.HALF_UP)); result.put("holdings", holdings);
        // 附上期末日（月度模式为月末当天）的所有交易记录
        result.put("transactions", jdbc.queryForList("SELECT t.type, s.name AS stockName, t.shares, t.price FROM transactions t LEFT JOIN stocks s ON t.stock_id=s.id WHERE t.portfolio_id=? AND t.trade_date=?", pid, java.sql.Date.valueOf(endDay)));
        return result;
    }
}
