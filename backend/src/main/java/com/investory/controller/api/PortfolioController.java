package com.investory.controller.api;

import com.investory.dao.*;
import com.investory.model.*;
import com.investory.service.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * 组合（Portfolio）管理与仪表盘数据控制器
 *
 * <p>负责模块：
 * <ul>
 *   <li>组合的增删改查（用户可拥有多个组合，Session 中保存当前活跃组合）</li>
 *   <li>仪表盘聚合数据（总市值、总盈亏、今日盈亏、仓位分布等）</li>
 *   <li>现金余额查询</li>
 *   <li>用户密码修改</li>
 * </ul>
 * <p>API 基础路径：/api
 *
 * <p>组合切换通过 PUT /portfolios/{id}（不传 name 参数时）实现，
 * 会更新 Session 中的 portfolioId，后续所有接口自动使用新组合上下文。
 */
@RestController
@RequestMapping("/api")
public class PortfolioController {

    @Autowired private PortfolioDao portfolioDao;
    @Autowired private HoldingService holdingService;
    @Autowired private PortfolioAnalysisService analysisService;
    @Autowired private AuthService authService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 从 Session 中读取当前用户的活跃组合 ID
     *
     * <p>校验规则：Session 不存在或未设置 portfolioId 时返回 0，
     *   接口方法应判断返回值为 0 时拒绝处理。
     *
     * @param req HTTP 请求
     * @return 组合 ID，未登录或未选择组合时为 0
     */
    private long getPortfolioId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return 0;
        Object pid = s.getAttribute("portfolioId");
        return pid instanceof Number ? ((Number) pid).longValue() : 0;
    }

    /**
     * 查询当前用户的所有组合列表
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/portfolios
     * <p>功能说明：返回当前登录用户名下的所有投资组合，
     *   前端用于组合切换下拉菜单的数据源。
     *
     * <p>请求参数：无（userId 从 Session 读取）
     *
     * <p>响应格式：List&lt;Portfolio&gt;，每项含 id、name 等基础字段
     *
     * @param req HTTP 请求，用于读取 Session 中的 userId
     * @return 该用户的组合列表
     */
    @GetMapping("/portfolios")
    public List<Portfolio> list(HttpServletRequest req) {
        // 直接从 Session 取 userId（未判空，因登录拦截器已保证此处必有值）
        Long userId = (Long) req.getSession().getAttribute("userId");
        return portfolioDao.findByUser(userId);
    }

    /**
     * 创建新的投资组合
     *
     * <p>HTTP 方法：POST
     * <p>路径：/api/portfolios
     * <p>功能说明：为当前用户创建一个新组合，创建成功后自动将该组合设为
     *   Session 中的活跃组合（portfolioId），后续操作默认使用新组合。
     *
     * <p>请求参数（form 参数）：
     *   - name（String）：组合名称，将自动去除首尾空白
     *
     * <p>响应格式：
     * <pre>
     * { "id": Long, "name": String }
     * </pre>
     *
     * @param name 组合名称
     * @param req  HTTP 请求，用于读取/写入 Session
     * @return 含新组合 id 和 name 的 Map
     */
    @PostMapping("/portfolios")
    public Map<String, Object> create(@RequestParam String name, HttpServletRequest req) {
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        Portfolio p = new Portfolio(); p.setUserId(userId); p.setName(name.trim());
        long id = portfolioDao.insert(p);
        // 创建后立即切换到新组合，更新 Session 中的 portfolioId
        s.setAttribute("portfolioId", id);
        return Map.of("id", id, "name", name.trim());
    }

    /**
     * 修改组合名称或切换当前活跃组合
     *
     * <p>HTTP 方法：PUT
     * <p>路径：/api/portfolios/{id}
     * <p>功能说明：该接口承担两个职责（由 name 参数是否存在区分）：
     * <ol>
     *   <li>传入 name：修改指定组合的名称</li>
     *   <li>不传 name（或空白）：仅切换 Session 中的活跃组合为该 id</li>
     * </ol>
     *
     * <p>请求参数（form 参数）：
     *   - id（路径参数）：目标组合 ID
     *   - name（String，可选）：新组合名称
     *
     * <p>响应格式：
     * <pre>
     * 成功：{ "status": "ok" }
     * 无权限：{ "error": "not your portfolio" }
     * </pre>
     *
     * @param id   目标组合 ID（路径参数）
     * @param name 新名称（可选）
     * @param req  HTTP 请求，用于读取/写入 Session
     * @return 操作结果 Map
     */
    @PutMapping("/portfolios/{id}")
    public Map<String, String> update(@PathVariable long id, @RequestParam(required = false) String name,
                                       HttpServletRequest req) {
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        // 校验规则：目标组合必须属于当前用户，防止越权修改他人组合
        if (!portfolioDao.isOwner(id, userId)) return Map.of("error", "not your portfolio");
        if (name != null && !name.isBlank()) {
            // 传入了有效 name：执行重命名操作
            portfolioDao.updateName(id, name.trim());
        } else {
            // 未传 name：仅切换活跃组合，更新 Session 中的 portfolioId
            s.setAttribute("portfolioId", id);
        }
        return Map.of("status", "ok");
    }

    /**
     * 删除指定投资组合
     *
     * <p>HTTP 方法：DELETE
     * <p>路径：/api/portfolios/{id}
     * <p>功能说明：删除当前用户名下的指定组合（含其所有持仓、交易、股息等关联数据），
     *   删除后自动将 Session 切换到剩余组合中的第一个；若无剩余组合则设为 null。
     *
     * <p>请求参数：
     *   - id（路径参数）：要删除的组合 ID
     *
     * <p>响应格式：
     * <pre>
     * 成功：{ "status": "ok" }
     * 无权限：{ "error": "not your portfolio" }
     * </pre>
     *
     * @param id  要删除的组合 ID（路径参数）
     * @param req HTTP 请求，用于读取/写入 Session
     * @return 操作结果 Map
     */
    @DeleteMapping("/portfolios/{id}")
    public Map<String, String> delete(@PathVariable long id, HttpServletRequest req) {
        HttpSession s = req.getSession();
        Long userId = (Long) s.getAttribute("userId");
        // 校验规则：只能删除属于自己的组合，防止越权删除
        if (!portfolioDao.isOwner(id, userId)) return Map.of("error", "not your portfolio");
        portfolioDao.delete(id);
        // 删除后查询剩余组合，自动切换到第一个；若全部删光则将 portfolioId 置 null
        List<Portfolio> remaining = portfolioDao.findByUser(userId);
        s.setAttribute("portfolioId", remaining.isEmpty() ? null : remaining.get(0).getId());
        return Map.of("status", "ok");
    }

    /**
     * 查询仪表盘聚合数据
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/dashboard
     * <p>功能说明：一次性返回仪表盘所需的全部聚合指标，包括：
     *   总市值、总投入成本、未实现盈亏、已实现盈亏、累计盈亏、
     *   现金余额（按货币分组）、今日盈亏及百分比、持仓收益率、仓位分布数据。
     *
     * <p>请求参数：无（portfolioId 从 Session 读取）
     *
     * <p>响应格式（主要字段）：
     * <pre>
     * {
     *   "snapshots":         [ HoldingSnapshot ],    // 各持仓快照
     *   "totalMarketValue":  BigDecimal,             // 持仓总市值（CNY）
     *   "totalInvested":     BigDecimal,             // 历史累计投入
     *   "totalPnl":          BigDecimal,             // 当前未实现盈亏
     *   "realizedPnl":       BigDecimal,             // 历史已实现盈亏
     *   "cumulativePnl":     BigDecimal,             // 累计盈亏 = 未实现 + 已实现
     *   "cashBalance":       BigDecimal,             // 现金余额（折算 CNY 合计）
     *   "cashByCurrency":    [ {currency, amount} ], // 各货币现金明细
     *   "totalReturnPct":    BigDecimal,             // 持仓综合收益率 %
     *   "cumulativeReturnPct": BigDecimal,           // 累计收益率 %
     *   "todayPnl":          BigDecimal,             // 今日浮动盈亏
     *   "todayPnlPct":       BigDecimal,             // 今日涨跌幅 %
     *   "allocation":        [ {name,symbol,value,currency} ] // 仓位分布
     * }
     * </pre>
     *
     * @param req HTTP 请求，用于读取 Session 中的 portfolioId
     * @return 仪表盘聚合数据 Map，或含 error 字段的错误 Map
     */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(HttpServletRequest req) {
        long pid = getPortfolioId(req);
        // 校验规则：portfolioId 为 0 表示未登录或未选择组合
        if (pid == 0) return Map.of("error", "No portfolio");
        List<HoldingSnapshot> snaps = holdingService.getSnapshots(pid);
        // 未实现盈亏：当前市值 - 成本
        BigDecimal holdingPnl = analysisService.totalUnrealizedPnl(snaps);
        // 已实现盈亏：历史所有已平仓头寸的盈亏合计
        BigDecimal realized = analysisService.totalRealizedPnl(pid);

        // 现金余额：将所有非 CNY 货币按汇率表折算为 CNY 后求和
        // NULLIF(e.rate, 0) 防止汇率为 0 时除零异常
        BigDecimal cash = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN c.currency='CNY' THEN c.amount ELSE c.amount / NULLIF(e.rate, 0) END), 0) FROM cash_balances c LEFT JOIN exchange_rates e ON c.currency=e.currency WHERE c.portfolio_id=?", BigDecimal.class, pid);
        cash = cash != null ? cash : BigDecimal.ZERO;

        // 今日盈亏：将所有持仓的当日涨跌金额（changeToday 字段）求和
        BigDecimal todayPnl = BigDecimal.ZERO;
        for (HoldingSnapshot s : snaps) if (s.getChangeToday() != null) todayPnl = todayPnl.add(s.getChangeToday());

        Map<String, Object> r = new LinkedHashMap<>();
        BigDecimal totalMV = analysisService.totalMarketValue(snaps);
        BigDecimal totalInvested = analysisService.totalInvested(snaps);
        BigDecimal totalDiv = analysisService.totalDividends(snaps);
        // 累计盈亏 = 当前未实现盈亏 + 历史已实现盈亏
        BigDecimal cumulativePnl = holdingPnl.add(realized);
        r.put("snapshots", snaps);
        r.put("totalMarketValue", totalMV);
        r.put("totalInvested", totalInvested);
        r.put("totalPnl", holdingPnl);
        r.put("realizedPnl", realized);
        r.put("cumulativePnl", cumulativePnl);
        r.put("cashBalance", cash);
        // cashByCurrency：各货币原始余额明细，供前端多币种展示
        r.put("cashByCurrency", jdbc.queryForList("SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", pid));
        // totalReturnPct：考虑股息后的综合持仓收益率
        r.put("totalReturnPct", analysisService.holdingReturnRate(totalMV, totalInvested, totalDiv));
        r.put("cumulativeReturnPct", analysisService.cumulativeReturnRate(cumulativePnl, totalInvested));
        r.put("todayPnl", todayPnl);
        // todayPnlPct：今日涨跌幅 = 今日盈亏 / 昨日总市值 × 100
        // 昨日总市值 = 当前总市值 - 今日盈亏；分母为 0 时返回 0 避免除零
        BigDecimal prev = analysisService.totalMarketValue(snaps).subtract(todayPnl);
        r.put("todayPnlPct", prev.compareTo(BigDecimal.ZERO) != 0 ? todayPnl.divide(prev, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);

        // allocation：仓位分布数据，每项含股票名称、代码、市值、货币，用于饼图展示
        List<Map<String, Object>> alloc = new ArrayList<>();
        for (HoldingSnapshot s : snaps) { Map<String, Object> a = new LinkedHashMap<>(); a.put("name", s.getStockName()); a.put("symbol", s.getStockSymbol()); a.put("value", s.getMarketValue()); a.put("currency", s.getCurrency() != null ? s.getCurrency() : "CNY"); alloc.add(a); }
        r.put("allocation", alloc);
        return r;
    }

    /**
     * 查询当前组合的现金余额明细
     *
     * <p>HTTP 方法：GET
     * <p>路径：/api/cash
     * <p>功能说明：返回当前组合在各币种下的现金余额列表，
     *   供资金管理页面展示多币种余额。
     *
     * <p>请求参数：无（portfolioId 从 Session 读取）
     *
     * <p>响应格式：
     * <pre>
     * { "balances": [ { "currency": String, "amount": BigDecimal }, ... ] }
     * </pre>
     *
     * @param req HTTP 请求，用于读取 Session 中的 portfolioId
     * @return 含 balances 列表的 Map
     */
    @GetMapping("/cash")
    public Map<String, Object> cash(HttpServletRequest req) {
        long pid = getPortfolioId(req);
        // 直接查询 cash_balances 表，返回该组合所有货币的余额记录
        return Map.of("balances", jdbc.queryForList("SELECT currency, amount FROM cash_balances WHERE portfolio_id=?", pid));
    }
}
