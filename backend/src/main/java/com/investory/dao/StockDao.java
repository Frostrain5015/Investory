package com.investory.dao;

import com.investory.model.Stock;
import com.investory.util.PinyinUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 股票基础信息数据访问对象（DAO）。
 *
 * <p>操作数据表：{@code stocks}（股票基础信息表）</p>
 *
 * <p>该表存储股票代码、名称、所属市场和交易货币，以 {@code symbol} 作为唯一标识。
 * 额外字段 {@code name_pinyin} 保存中文名称的拼音首字母缩写，用于前端拼音搜索功能。</p>
 *
 * <p>支持的市场示例：A（A股）、HK（港股）、US（美股）等。</p>
 */
public class StockDao extends BaseDao {

    /**
     * 将结果集当前行映射为 {@link Stock} 对象。
     *
     * @param rs 已定位到目标行的结果集
     * @return 填充好字段的 {@link Stock} 实例
     * @throws SQLException 读取字段时可能抛出的数据库异常
     */
    private Stock map(ResultSet rs) throws SQLException {
        Stock s = new Stock();
        s.setId(rs.getLong("id"));             // 股票主键 ID
        s.setSymbol(rs.getString("symbol"));   // 股票代码（如 "600519"、"00700.HK"）
        s.setName(rs.getString("name"));       // 股票名称（中文或英文）
        s.setMarket(rs.getString("market"));   // 市场标识（如 "A"、"HK"、"US"）
        s.setCurrency(rs.getString("currency")); // 交易货币（如 "CNY"、"HKD"、"USD"）
        return s;
    }

    /**
     * 按股票代码精确查询股票信息。
     *
     * <p>SQL 逻辑：{@code symbol} 字段有唯一索引，最多返回一条记录。</p>
     *
     * @param symbol 股票代码（大小写敏感，与存储格式一致）
     * @return 对应的 {@link Stock} 对象，不存在时返回 {@code null}
     */
    public Stock findBySymbol(String symbol) {
        return queryOne("SELECT * FROM stocks WHERE symbol = ?", this::map, symbol);
    }

    /**
     * 按主键 ID 查询股票信息。
     *
     * @param id 股票主键 ID
     * @return 对应的 {@link Stock} 对象，不存在时返回 {@code null}
     */
    public Stock findById(long id) {
        return queryOne("SELECT * FROM stocks WHERE id = ?", this::map, id);
    }

    /**
     * 根据关键词搜索股票，最多返回 8 条结果，并按相关度排序。
     *
     * <p>搜索策略分两路：</p>
     * <ul>
     *   <li><b>纯英文字母关键词</b>：同时搜索 symbol、name、name_pinyin（拼音首字母）三列，
     *       排序优先级：精确匹配代码 > 代码前缀 > 名称前缀 > 拼音前缀 > 名称包含 > 拼音包含</li>
     *   <li><b>含中文或数字的关键词</b>：搜索 symbol 和 name 两列，
     *       排序优先级：精确匹配代码 > 代码前缀 > 名称前缀 > 名称包含</li>
     * </ul>
     *
     * <p>ORDER BY CASE...END 实现多级优先级排序：数字越小优先级越高；
     * 同优先级内再按 name 字母顺序稳定排序。</p>
     *
     * @param keyword 用户输入的搜索关键词（前后空白会被 trim 处理）
     * @return 最多 8 条按相关度排序的股票列表
     */
    public List<Stock> search(String keyword) {
        String k = keyword.trim();
        // 构造"包含"和"前缀"两种 LIKE 模式
        String contains = "%" + k + "%";
        String starts   = k + "%";

        // 纯 ASCII 字母 → 同时搜索拼音首字母缩写列（name_pinyin）
        if (k.matches("[a-zA-Z]+")) {
            String py = k.toLowerCase();                        // 拼音搜索统一转小写
            String pyStarts   = py + "%";
            String pyContains = "%" + py + "%";
            return query("""
                SELECT * FROM stocks
                WHERE symbol LIKE ? OR name LIKE ?
                   OR (name_pinyin IS NOT NULL AND name_pinyin LIKE ?)
                ORDER BY CASE
                  WHEN symbol = ?      THEN 1
                  WHEN symbol LIKE ?   THEN 2
                  WHEN name   LIKE ?   THEN 3
                  WHEN name_pinyin LIKE ? THEN 4
                  WHEN name   LIKE ?   THEN 5
                  WHEN name_pinyin LIKE ? THEN 6
                  ELSE 7 END, name LIMIT 8
                """, this::map,
                // WHERE 条件参数：symbol包含 / name包含 / pinyin包含
                contains, contains, pyContains,
                // ORDER BY CASE 参数：精确代码 / 代码前缀 / 名称前缀 / 拼音前缀 / 名称包含 / 拼音包含
                k, starts, starts, pyStarts, contains, pyContains);
        }
        // 含中文或数字的关键词：仅搜索 symbol 和 name
        return query("""
            SELECT * FROM stocks
            WHERE symbol LIKE ? OR name LIKE ?
            ORDER BY CASE
              WHEN symbol = ?    THEN 1
              WHEN symbol LIKE ? THEN 2
              WHEN name   LIKE ? THEN 3
              WHEN name   LIKE ? THEN 4
              ELSE 5 END, name LIMIT 8
            """, this::map,
            // WHERE 条件参数：symbol包含 / name包含
            contains, contains,
            // ORDER BY CASE 参数：精确代码 / 代码前缀 / 名称前缀 / 名称包含
            k, starts, starts, contains);
    }

    /**
     * 查询所有股票，按市场和名称排序。
     *
     * <p>用于管理员后台或批量处理场景，生产环境股票数量较多时谨慎使用。</p>
     *
     * @return 全量股票列表，先按 market 分组，再按 name 字母顺序排列
     */
    public List<Stock> findAll() {
        return query("SELECT * FROM stocks ORDER BY market, name", this::map);
    }

    /**
     * 插入新股票记录，若 symbol 已存在则直接返回现有记录的 ID（幂等操作）。
     *
     * <p>逻辑步骤：
     * <ol>
     *   <li>先按 symbol 查询是否已存在 → 存在则直接返回其 ID（避免重复插入）</li>
     *   <li>调用 {@link PinyinUtil#toAbbr} 将中文名称转换为拼音首字母缩写</li>
     *   <li>执行 INSERT，若拼音为空字符串则存 {@code NULL}（避免存入无意义的空串）</li>
     * </ol>
     * </p>
     *
     * @param stock 待插入的股票对象（symbol、name、market、currency 不可为空）
     * @return 新插入记录的主键 ID；若 symbol 已存在，返回原有记录的 ID
     */
    public long upsert(Stock stock) {
        // 幂等检查：symbol 已存在则直接返回已有 ID
        Stock existing = findBySymbol(stock.getSymbol());
        if (existing != null) return existing.getId();
        // 生成拼音首字母缩写，用于模糊搜索
        String pinyin = PinyinUtil.toAbbr(stock.getName());
        return insert("INSERT INTO stocks (symbol, name, market, currency, name_pinyin) VALUES (?, ?, ?, ?, ?)",
                stock.getSymbol(), stock.getName(), stock.getMarket(), stock.getCurrency(),
                // 空字符串时存 NULL，避免无用数据
                pinyin.isEmpty() ? null : pinyin);
    }
}
