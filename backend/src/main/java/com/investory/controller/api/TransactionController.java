package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 交易记录管理控制器
 *
 * <p>负责模块：交易流水（买入/卖出/资金划转）与股息的增删改查，
 *   同时维护现金余额（cash_balances）的变更，确保每笔操作后账户余额准确。
 * <p>API 基础路径：/api
 *
 * <p>支持的交易类型：
 * <ul>
 *   <li>BUY（买入）：扣减现金，增加持仓</li>
 *   <li>SELL（卖出）：增加现金，减少持仓</li>
 *   <li>TRANSFER_IN（转入）：增加现金余额，不影响持仓</li>
 *   <li>TRANSFER_OUT（转出）：扣减现金余额，不影响持仓</li>
 * </ul>
 *
 * <p>所有写操作（创建、修改、删除）均使用 @Transactional 保证原子性，
 *   现金变更与交易记录变更要么同时成功，要么同时回滚。
 */
@RestController
@RequestMapping("/api")
public class TransactionController {

    @Autowired private TransactionDao transactionDao;
    @Autowired private DividendDao dividendDao;
    @Autowired private StockDao stockDao;
    @Autowired private HoldingDao holdingDao;
    @Autowired private HoldingService holdingService;
    @Autowired private PortfolioValueCalculator valueCalculator;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 从 Session 中读取当前用户的组合 ID
     *
     * <p>校验规则：Session 不存在或未设置 portfolioId 时返回 0。
     *
     * @param req HTTP 请求
     * @return 组合 ID，未登录或未选择组合时为 0
     */
    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false); if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    /**
     * 查询当前组合的全部交易与股息流水（合并列表）
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/transactions
     * <p>功能说明：将普通交易记录（买卖/划转）与股息记录合并为一个统一的流水列表，
     *   按日期倒序排列，用于交易历史页面展示。
     *   股息记录在列表中以 type="DIV" 标识，字段结构与普通交易有所不同。
     *
     * <p>请求参数：无（portfolioId 从 Session 读取）
     *
     * <p>响应格式：List&lt;Map&gt;，按 date 倒序排列。
     *   普通交易字段：id, date, type, stockName, stockSymbol, stockMarket, shares, price, fee, note
     *   股息字段：id, date, type="DIV", stockName, stockSymbol, amountPerShare, sharesHeld, totalAmount
     *
     * @param req HTTP 请求，用于读取 Session 中的 portfolioId
     * @return 合并后的流水列表
     */
    @GetMapping("/transactions")
    public List<Map<String, Object>> list(HttpServletRequest req) {
        long pid = getPortfolioId(req);
        List<Map<String, Object>> list = new ArrayList<>();
        // 遍历普通交易记录，逐条转换为前端友好的 Map 格式（日期转字符串）
        for (Transaction t : transactionDao.findByPortfolio(pid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId()); m.put("date", t.getTradeDate().toString());
            m.put("type", t.getType()); m.put("stockName", t.getStockName());
            m.put("stockSymbol", t.getStockSymbol()); m.put("stockMarket", t.getStockMarket());
            m.put("shares", t.getShares()); m.put("price", t.getPrice());
            m.put("fee", t.getFee()); m.put("note", t.getNote());
            list.add(m);
        }
        // 遍历股息记录，以 type="DIV" 标识，合并进同一流水列表
        for (Dividend d : dividendDao.findByPortfolio(pid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId()); m.put("date", d.getRecordDate().toString());
            m.put("type", "DIV"); m.put("stockName", d.getStockName());
            m.put("stockSymbol", d.getStockSymbol()); m.put("amountPerShare", d.getAmountPerShare());
            m.put("sharesHeld", d.getSharesHeld()); m.put("totalAmount", d.getTotalAmount());
            list.add(m);
        }
        // 按日期字符串倒序排列（利用 ISO 8601 格式字符串可直接字典序比较的特性）
        list.sort((a, b) -> ((String) b.get("date")).compareTo((String) a.get("date")));
        return list;
    }

    /**
     * 查询单条交易记录详情（用于编辑表单回显）
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/transactions/{id}
     * <p>功能说明：返回单条交易记录的完整信息，包含货币类型，
     *   主要用于编辑表单的数据回填。
     *
     * <p>请求参数：
     *   - id（路径参数）：交易记录 ID
     *
     * <p>响应格式：
     * <pre>
     * {
     *   "id", "stockId", "stockName", "stockSymbol", "stockMarket",
     *   "currency",  // 来自关联股票，默认 CNY
     *   "date", "type", "shares", "price", "fee", "note"
     * }
     * </pre>
     *
     * @param id  交易记录 ID（路径参数）
     * @param req HTTP 请求，用于读取 Session 中的 portfolioId
     * @return 交易详情 Map，或含 error 字段的错误 Map
     */
    @GetMapping("/transactions/{id}")
    public Map<String, Object> getOne(@PathVariable long id, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        Transaction t = transactionDao.findById(id);
        // 校验规则：记录不存在，或记录所属组合与当前用户不匹配，均返回 404 语义错误
        if (t == null || t.getPortfolioId() != pid) return Map.of("error", "Not found");
        // 默认货币为 CNY；若有关联股票则从股票信息中取实际货币类型
        String cur = "CNY";
        if (t.getStockId() != null && t.getStockId() > 0) {
            Stock s = stockDao.findById(t.getStockId()); cur = s != null ? s.getCurrency() : "CNY";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId()); m.put("stockId", t.getStockId()); m.put("stockName", t.getStockName());
        m.put("stockSymbol", t.getStockSymbol()); m.put("stockMarket", t.getStockMarket());
        // currency 字段来源于关联股票表，而非交易表本身（交易表可能无此字段）
        m.put("currency", cur); m.put("date", t.getTradeDate().toString()); m.put("type", t.getType());
        m.put("shares", t.getShares()); m.put("price", t.getPrice()); m.put("fee", t.getFee());
        m.put("note", t.getNote());
        return m;
    }

    /**
     * 新增一条交易记录
     *
     * <p>HTTP 方法：POST
     * <p>路径：/api/transactions
     * <p>功能说明：新增一条买入/卖出/资金划转记录，同步更新现金余额、
     *   重新计算持仓，并从交易日起重新回填组合历史净值曲线。
     *
     * <p>请求参数（均为 form 参数）：
     *   - stockId（long）：关联股票 ID；划转类交易可传 0
     *   - type（String）：交易类型 BUY / SELL / TRANSFER_IN / TRANSFER_OUT
     *   - shares（BigDecimal）：股数（划转类表示金额）
     *   - price（BigDecimal）：成交价格
     *   - fee（String，可选）：手续费，空白时默认为 0
     *   - tradeDate（String）：交易日期，格式 yyyy-MM-dd
     *   - currency（String，可选）：货币代码，默认 CNY
     *   - note（String，可选）：备注
     *
     * <p>响应格式：
     * <pre>
     * 成功：{ "id": Long }  // 新记录的数据库 ID
     * 余额不足：{ "error": "INSUFFICIENT_CASH", "balance": BigDecimal,
     *              "required": BigDecimal, "currency": String }
     * </pre>
     *
     * @return 200 含新 ID，或 400 含余额不足错误
     */
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/transactions")
    public ResponseEntity<Map<String, Object>> create(@RequestParam long stockId, @RequestParam String type,
            @RequestParam BigDecimal shares, @RequestParam BigDecimal price,
            @RequestParam(required = false) String fee, @RequestParam String tradeDate,
            @RequestParam(required = false, defaultValue = "CNY") String currency,
            @RequestParam(required = false) String note, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        // 费用为空或空白字符串时默认为 0，避免后续计算 NPE
        BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;
        // 资金划转类型不关联股票，直接更新 cash_balances 并插入交易记录
        if ("TRANSFER_IN".equals(type) || "TRANSFER_OUT".equals(type)) {
            // 余额守卫：转出前检查余额是否足够
            if ("TRANSFER_OUT".equals(type) && !checkCash(pid, currency, shares))
                return ResponseEntity.badRequest().body(cashError(pid, currency, shares));
            // TRANSFER_IN 增加现金（正数），TRANSFER_OUT 减少现金（负数）
            BigDecimal amount = "TRANSFER_IN".equals(type) ? shares : shares.negate();
            // ON DUPLICATE KEY UPDATE 确保同币种只有一行余额记录，不重复插入
            jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, currency, amount, amount);
            Transaction t = buildTx(pid, null, type, shares, BigDecimal.ZERO, BigDecimal.ZERO, tradeDate, note);
            t.setCurrency(currency);
            long id = transactionDao.insert(t);
            // 划转影响资金曲线，从交易日起回填组合净值历史
            valueCalculator.backfillFrom(pid, LocalDate.parse(tradeDate));
            return ResponseEntity.ok(Map.of("id", id));
        }
        Stock stock = stockDao.findById(stockId);
        // 从股票信息取货币类型（A 股 CNY，港股 HKD，美股 USD 等）
        String cur = stock != null ? stock.getCurrency() : "CNY";
        // 买入总成本 = 股数 × 价格 + 手续费
        BigDecimal cost = "BUY".equals(type) ? shares.multiply(price).add(feeVal) : BigDecimal.ZERO;
        // 校验规则：买入时检查现金余额是否足够支付总成本；不足则返回 400 含明细
        if ("BUY".equals(type) && stock != null && !checkCash(pid, cur, cost))
            return ResponseEntity.badRequest().body(cashError(pid, cur, cost));
        // 根据交易类型对应增减现金余额
        applyCash(pid, cur, type, shares, price, feeVal);
        Transaction t = buildTx(pid, stockId, type, shares, price, feeVal, tradeDate, note);
        t.setCurrency(cur);
        long id = transactionDao.insert(t);
        // 重新计算该股票的持仓（合并所有买卖记录，更新均价和总股数）
        holdingService.rebuildHolding(pid, stockId);
        // 从交易日起重新回填组合历史净值曲线（含此次交易的价格影响）
        if (stock != null) valueCalculator.backfillFrom(pid, LocalDate.parse(tradeDate), stockId, price, shares);
        return ResponseEntity.ok(Map.of("id", id));
    }

    /**
     * 修改一条已有的交易记录
     *
     * <p>HTTP 方法：PUT
     * <p>路径：/api/transactions/{id}
     * <p>功能说明：修改指定交易记录的所有字段，
     *   先回滚旧记录对现金的影响，再按新数据重新应用，
     *   并重新计算持仓和历史净值曲线。
     *
     * <p>请求参数（均为 form 参数）：同 POST /transactions，另加：
     *   - id（路径参数）：要修改的交易记录 ID
     *
     * <p>响应格式：
     * <pre>
     * 成功：{ "status": "ok" }
     * 余额不足：400 + { "error": "INSUFFICIENT_CASH", ... }
     * 记录不存在：400 + { "error": "Not found" }
     * </pre>
     *
     * @param id  要修改的交易 ID（路径参数）
     * @return 200 或 400
     */
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/transactions/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id, @RequestParam long stockId,
            @RequestParam String type, @RequestParam BigDecimal shares, @RequestParam BigDecimal price,
            @RequestParam(required = false) String fee, @RequestParam String tradeDate,
            @RequestParam(required = false) String currency, @RequestParam(required = false) String note,
            HttpServletRequest req) {
        long pid = getPortfolioId(req);
        BigDecimal feeVal = (fee != null && !fee.isBlank()) ? new BigDecimal(fee) : BigDecimal.ZERO;
        Transaction old = transactionDao.findById(id);
        // 校验规则：记录不存在或不属于当前组合，拒绝修改
        if (old == null || old.getPortfolioId() != pid) return ResponseEntity.badRequest().body(Map.of("error", "Not found"));
        LocalDate oldTradeDate = old.getTradeDate();
        LocalDate newTradeDate = LocalDate.parse(tradeDate);
        Long oldStockId = old.getStockId();
        // 先回滚旧交易对现金余额的影响，使余额恢复到交易前状态
        reverseCash(pid, old);
        // 货币优先取请求参数，否则从关联股票取，最终兜底为 CNY
        String cur = (currency != null && !currency.isBlank()) ? currency : (stockId > 0 ? getCur(stockId) : "CNY");
        // 校验规则：新交易为 BUY 时，检查回滚后的余额是否足够；不足则还原并返回 400
        if ("BUY".equals(type)) { BigDecimal c = shares.multiply(price).add(feeVal); if (!checkCash(pid, cur, c)) { applyCashDirect(pid, old); return ResponseEntity.badRequest().body(cashError(pid, cur, c)); } }
        // 校验规则：新交易为 TRANSFER_OUT 时，检查余额是否足够划出
        if ("TRANSFER_OUT".equals(type) && !checkCash(pid, cur, shares)) { applyCashDirect(pid, old); return ResponseEntity.badRequest().body(cashError(pid, cur, shares)); }
        // 按新交易数据重新应用现金变更
        applyCash(pid, cur, type, shares, price, feeVal);
        Transaction t = buildTx(pid, stockId, type, shares, price, feeVal, tradeDate, note); t.setId(id); t.setCurrency(cur);
        transactionDao.update(t);
        // 重新计算该股票的持仓聚合数据
        if (stockId > 0) holdingService.rebuildHolding(pid, stockId);
        if (oldStockId != null && oldStockId > 0 && oldStockId.longValue() != stockId) {
            holdingService.rebuildHolding(pid, oldStockId);
        }
        // 从（可能更早的）交易日重新回填净值曲线，确保历史数据一致性
        LocalDate fromDate = oldTradeDate != null && oldTradeDate.isBefore(newTradeDate) ? oldTradeDate : newTradeDate;
        valueCalculator.backfillFrom(pid, fromDate);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 删除一条交易记录
     *
     * <p>HTTP 方法：DELETE
     * <p>路径：/api/transactions/{id}
     * <p>功能说明：删除指定交易记录，同步回滚其对现金余额的影响，
     *   并重新计算相关股票的持仓数据。
     *
     * <p>请求参数：
     *   - id（路径参数）：要删除的交易记录 ID
     *
     * <p>响应格式：{ "status": "ok" }（幂等操作，记录不存在也返回 ok）
     *
     * @param id  要删除的交易 ID（路径参数）
     * @param req HTTP 请求，用于读取 Session 中的 portfolioId
     * @return 操作结果 Map
     */
    @Transactional(rollbackFor = Exception.class)
    @DeleteMapping("/transactions/{id}")
    public Map<String, String> delete(@PathVariable long id, HttpServletRequest req) {
        long pid = getPortfolioId(req);
        // 在当前组合的交易列表中查找目标记录，找到后执行删除逻辑
        transactionDao.findByPortfolio(pid).stream().filter(t -> t.getId() == id).findFirst().ifPresent(old -> {
            // 删除前先回滚该交易对现金余额的影响
            reverseCash(pid, old);
            transactionDao.delete(id);
            // 若该交易关联了股票，重新计算对应股票的持仓数据
            if (old.getStockId() != null && old.getStockId() > 0) holdingService.rebuildHolding(pid, old.getStockId());
            if (old.getTradeDate() != null) valueCalculator.backfillFrom(pid, old.getTradeDate());
        });
        return Map.of("status", "ok");
    }

    // ─────────────────────────── 私有工具方法 ───────────────────────────

    /**
     * 构建 Transaction 实体对象（工厂方法，避免重复 setter 代码）
     *
     * @param pid   组合 ID
     * @param sid   股票 ID（划转类可为 null）
     * @param type  交易类型
     * @param sh    股数/金额
     * @param pr    成交价格
     * @param fee   手续费
     * @param date  交易日期字符串（yyyy-MM-dd）
     * @param note  备注
     * @return 构建好的 Transaction 对象（未设置 id，由数据库自增）
     */
    private Transaction buildTx(long pid, Long sid, String type, BigDecimal sh, BigDecimal pr, BigDecimal fee, String date, String note) {
        Transaction t = new Transaction(); t.setPortfolioId(pid); t.setStockId(sid); t.setType(type);
        t.setShares(sh); t.setPrice(pr); t.setFee(fee); t.setTradeDate(LocalDate.parse(date)); t.setNote(note);
        t.setCurrency("CNY");
        return t;
    }

    /**
     * 检查指定组合在某货币下的现金余额是否满足需求
     *
     * <p>校验规则：查询 cash_balances 表中对应 portfolio_id + currency 行的余额，
     *   若不存在或余额 < 需求金额，则返回 false。
     *
     * @param pid  组合 ID
     * @param cur  货币代码
     * @param need 所需金额
     * @return true 表示余额充足，false 表示余额不足
     */
    private boolean checkCash(long pid, String cur, BigDecimal need) {
        List<BigDecimal> rows = jdbc.queryForList("SELECT amount FROM cash_balances WHERE portfolio_id=? AND currency=?", BigDecimal.class, pid, cur);
        BigDecimal bal = rows.isEmpty() ? BigDecimal.ZERO : rows.get(0); if (bal == null) bal = BigDecimal.ZERO;
        return bal.compareTo(need) >= 0;
    }

    /**
     * 构建余额不足错误响应 Map
     *
     * <p>响应数据结构：error="INSUFFICIENT_CASH", balance（当前余额）,
     *   required（需求金额）, currency（货币代码）。
     *   前端据此展示具体的缺口金额，引导用户先转入资金。
     *
     * @param pid  组合 ID
     * @param cur  货币代码
     * @param need 所需金额
     * @return 含余额详情的错误 Map
     */
    private Map<String, Object> cashError(long pid, String cur, BigDecimal need) {
        List<BigDecimal> rows = jdbc.queryForList("SELECT amount FROM cash_balances WHERE portfolio_id=? AND currency=?", BigDecimal.class, pid, cur);
        BigDecimal bal = rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
        Map<String, Object> err = new LinkedHashMap<>();
        // error 字段供前端判断错误类型，balance/required 供显示余额缺口
        err.put("error", "INSUFFICIENT_CASH"); err.put("balance", bal); err.put("required", need); err.put("currency", cur);
        return err;
    }

    /**
     * 根据交易类型对现金余额执行对应的增减操作
     *
     * <p>各类型影响规则：
     * <ul>
     *   <li>BUY：扣减 shares × price + fee</li>
     *   <li>SELL：增加 shares × price - fee（使用 ON DUPLICATE KEY 确保行存在）</li>
     *   <li>TRANSFER_IN：增加 shares</li>
     *   <li>TRANSFER_OUT：扣减 shares</li>
     * </ul>
     *
     * @param pid   组合 ID
     * @param cur   货币代码
     * @param type  交易类型
     * @param sh    股数/金额
     * @param pr    成交价格
     * @param fee   手续费
     */
    private void applyCash(long pid, String cur, String type, BigDecimal sh, BigDecimal pr, BigDecimal fee) {
        if ("BUY".equals(type)) jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", sh.multiply(pr).add(fee), pid, cur);
        else if ("SELL".equals(type)) jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, sh.multiply(pr).subtract(fee), sh.multiply(pr).subtract(fee));
        else if ("TRANSFER_IN".equals(type)) jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, sh, sh);
        else if ("TRANSFER_OUT".equals(type)) jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", sh, pid, cur);
    }

    /**
     * 回滚一条旧交易记录对现金余额的影响（与 applyCash 逻辑完全相反）
     *
     * <p>用途：修改或删除交易时，先调用此方法撤销旧交易的余额影响，
     *   再按新数据重新应用，确保余额始终准确。
     *
     * @param pid 组合 ID
     * @param old 需要回滚的旧交易记录
     */
    private void reverseCash(long pid, Transaction old) {
        // 货币优先从交易记录本身取；无则从关联股票取；最终兜底为 CNY
        String cur = old.getCurrency(); if (cur == null && old.getStockId() != null && old.getStockId() > 0) cur = getCur(old.getStockId()); if (cur == null) cur = "CNY";
        // 旧 BUY 的回滚：将原扣减金额返还给现金余额
        if ("BUY".equals(old.getType())) jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, old.getShares().multiply(old.getPrice()).add(old.getFee()), old.getShares().multiply(old.getPrice()).add(old.getFee()));
        // 旧 SELL 的回滚：将原增加金额从现金余额中扣回
        else if ("SELL".equals(old.getType())) jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", old.getShares().multiply(old.getPrice()).subtract(old.getFee()), pid, cur);
        // 旧 TRANSFER_IN 的回滚：将转入金额从余额中扣回
        else if ("TRANSFER_IN".equals(old.getType())) jdbc.update("UPDATE cash_balances SET amount = amount - ? WHERE portfolio_id=? AND currency=?", old.getShares(), pid, cur);
        // 旧 TRANSFER_OUT 的回滚：将转出金额返还给余额
        else if ("TRANSFER_OUT".equals(old.getType())) jdbc.update("INSERT INTO cash_balances (portfolio_id, currency, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?", pid, cur, old.getShares(), old.getShares());
    }

    /**
     * 将一条已有交易记录的现金影响重新应用（用于余额校验失败时的还原）
     *
     * <p>用途：修改接口中，若新数据余额校验失败，需要将已通过 reverseCash 回滚的
     *   旧交易影响重新应用，恢复到回滚前的状态，保持数据一致性。
     *
     * @param pid 组合 ID
     * @param t   需要重新应用的旧交易记录
     */
    private void applyCashDirect(long pid, Transaction t) {
        String cur = t.getCurrency(); if (cur == null && t.getStockId() != null && t.getStockId() > 0) cur = getCur(t.getStockId()); if (cur == null) cur = "CNY";
        applyCash(pid, cur, t.getType(), t.getShares(), t.getPrice(), t.getFee());
    }

    /**
     * 根据股票 ID 查询其货币代码
     *
     * @param sid 股票 ID
     * @return 货币代码（股票不存在时默认返回 "CNY"）
     */
    private String getCur(long sid) { Stock s = stockDao.findById(sid); return s != null ? s.getCurrency() : "CNY"; }
}
