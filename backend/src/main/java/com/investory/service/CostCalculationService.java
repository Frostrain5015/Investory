package com.investory.service;

import com.investory.model.Holding;
import com.investory.model.Transaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 成本计算服务
 *
 * <p>负责根据交易记录计算持仓的核心财务指标，包括：
 * <ul>
 *   <li>累计持仓股数（totalShares）</li>
 *   <li>平均成本价（avgCost）—— 加权平均法，含买入手续费</li>
 *   <li>累计投入资金（totalInvested）—— 净现金占用，卖出后扣除回笼资金</li>
 *   <li>摊薄成本（dilutedCost）—— 扣除历史分红后的实际持仓成本</li>
 * </ul>
 *
 * <p>核心算法：买入时将手续费并入成本，采用加权平均法更新均价；
 * 卖出时按实际回笼现金（卖价×数量－手续费）从投入中扣减，
 * 持仓清零后成本归零。
 */
@Service
public class CostCalculationService {

    /** BigDecimal 零值常量，避免重复构造 */
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * 根据交易流水重建持仓的成本数据。
     *
     * <p>算法思路（加权平均成本法）：
     * <ol>
     *   <li>按时间顺序遍历所有买入/卖出记录</li>
     *   <li>买入：将本次买入成本（价格×数量＋手续费）加入总投入，
     *       用新的总投入除以新的总股数得到最新均价</li>
     *   <li>卖出：从总投入中扣除本次卖出回笼现金（价格×数量－手续费），
     *       持仓股数减少；若清仓则全部归零</li>
     * </ol>
     *
     * @param transactions 按时间升序排列的交易记录列表（仅处理 BUY / SELL 类型）
     * @return 填充了 totalShares、avgCost、totalInvested、totalDividends、dilutedCost 的
     *         {@link Holding} 对象（dividends 初始化为 0，需调用 applyDividends 后更新）
     */
    public Holding rebuild(List<Transaction> transactions) {
        BigDecimal totalShares   = ZERO; // 当前累计持仓股数
        BigDecimal totalInvested = ZERO; // 当前累计净投入资金（含手续费，扣卖出回笼）
        BigDecimal avgCost       = ZERO; // 当前加权平均成本价

        for (Transaction t : transactions) {
            if ("BUY".equals(t.getType())) {
                // 第1步（买入）：计算本次买入的总成本 = 价格 × 数量 + 手续费
                BigDecimal cost = t.getShares().multiply(t.getPrice()).add(t.getFee());

                // 第2步（买入）：买入后的新持仓总股数
                BigDecimal newShares = totalShares.add(t.getShares());

                // 第3步（买入）：用新总投入除以新总股数，得到更新后的加权平均成本
                if (newShares.compareTo(ZERO) > 0) {
                    avgCost = totalInvested.add(cost).divide(newShares, 6, RoundingMode.HALF_UP);
                }
                totalShares   = newShares;
                totalInvested = totalInvested.add(cost);

            } else if ("SELL".equals(t.getType())) {
                // Subtract at actual sell proceeds to reflect net cash deployed
                // 第1步（卖出）：计算本次卖出回笼的净现金 = 卖出价 × 数量 − 手续费
                BigDecimal cashBack = t.getPrice().multiply(t.getShares()).subtract(t.getFee());

                // 第2步（卖出）：减少持仓股数，并从总投入中扣除回笼现金
                totalShares   = totalShares.subtract(t.getShares());
                totalInvested = totalInvested.subtract(cashBack);

                // 第3步（卖出）：若持仓已清零，则将所有成本指标归零，防止负值残留
                if (totalShares.compareTo(ZERO) <= 0) {
                    totalShares   = ZERO;
                    totalInvested = ZERO;
                    avgCost       = ZERO;
                }
            }
        }

        // 第4步：将计算结果写入 Holding 对象，精度保留 4 位小数
        Holding h = new Holding();
        h.setTotalShares(totalShares.setScale(4, RoundingMode.HALF_UP));
        h.setAvgCost(avgCost.setScale(4, RoundingMode.HALF_UP));
        h.setTotalInvested(totalInvested.setScale(4, RoundingMode.HALF_UP));
        h.setTotalDividends(ZERO);                                   // 分红数据由 applyDividends 单独填充
        h.setDilutedCost(avgCost.setScale(4, RoundingMode.HALF_UP)); // 摊薄成本初始等于均价，待分红更新
        return h;
    }

    /**
     * 将历史累计分红金额应用到持仓，更新摊薄成本（dilutedCost）。
     *
     * <p>摊薄成本计算公式：
     * <pre>
     *   摊薄成本 = (总投入 − 累计分红) / 当前持仓股数
     * </pre>
     * 即：把已收到的分红视为降低了持仓的实际成本，反映"分红即返还本金"的投资逻辑。
     *
     * @param h              需要更新的持仓对象（由 {@link #rebuild} 生成）
     * @param totalDividends 该持仓历史累计分红总金额（可为 null，等同于 0）
     */
    public void applyDividends(Holding h, BigDecimal totalDividends) {
        if (totalDividends == null) totalDividends = ZERO;

        // 第1步：写入分红金额（精度保留 4 位）
        h.setTotalDividends(totalDividends.setScale(4, RoundingMode.HALF_UP));

        if (h.getTotalShares().compareTo(ZERO) > 0) {
            // 第2步：用（总投入 − 累计分红）作为有效投入，除以持仓股数得到摊薄成本
            BigDecimal effectiveInvested = h.getTotalInvested().subtract(totalDividends); // 有效净投入
            BigDecimal diluted = effectiveInvested.divide(h.getTotalShares(), 6, RoundingMode.HALF_UP);
            h.setDilutedCost(diluted.setScale(4, RoundingMode.HALF_UP));
        } else {
            // 第3步：若已清仓，摊薄成本归零
            h.setDilutedCost(ZERO);
        }
    }
}
