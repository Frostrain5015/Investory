package com.investory.model;

/**
 * 股票基础信息实体类。
 * <p>
 * 对应数据库 stock 表，存储股票的标识符、名称、所属市场及交易货币等静态属性。
 * 该实体被 Holding、Transaction、Dividend 等业务对象通过 stockId 外键关联引用。
 * </p>
 */
public class Stock {

    /** 数据库自增主键 */
    private Long id;

    /**
     * 股票唯一标识符，格式为 "市场代码.股票代码"。
     * 示例：
     * <ul>
     *   <li>"1.600519"  — 沪市贵州茅台</li>
     *   <li>"116.00700" — 港股腾讯控股</li>
     *   <li>"105.AAPL"  — 美股苹果公司</li>
     * </ul>
     */
    private String symbol;   // e.g. "1.600519", "116.00700", "105.AAPL"

    /** 股票中文（或英文）名称，例如 "贵州茅台"、"Apple Inc." */
    private String name;

    /** 所属交易市场，枚举值：SH（沪市）/ SZ（深市）/ HK（港股）/ US（美股） */
    private String market;   // SH / SZ / HK / US

    /** 交易货币，枚举值：CNY（人民币）/ HKD（港元）/ USD（美元） */
    private String currency; // CNY / HKD / USD

    /** 无参构造器，供 JdbcTemplate RowMapper 及序列化框架使用 */
    public Stock() {}

    /**
     * 获取主键 ID。
     *
     * @return 数据库自增主键
     */
    public Long getId() { return id; }

    /**
     * 设置主键 ID。
     *
     * @param id 数据库自增主键
     */
    public void setId(Long id) { this.id = id; }

    /**
     * 获取股票标识符（含市场前缀）。
     *
     * @return 形如 "1.600519" 的股票 symbol
     */
    public String getSymbol() { return symbol; }

    /**
     * 设置股票标识符。
     *
     * @param symbol 形如 "1.600519" 的股票 symbol
     */
    public void setSymbol(String symbol) { this.symbol = symbol; }

    /**
     * 获取股票名称。
     *
     * @return 股票中英文名称
     */
    public String getName() { return name; }

    /**
     * 设置股票名称。
     *
     * @param name 股票中英文名称
     */
    public void setName(String name) { this.name = name; }

    /**
     * 获取所属交易市场。
     *
     * @return SH / SZ / HK / US 之一
     */
    public String getMarket() { return market; }

    /**
     * 设置所属交易市场。
     *
     * @param market SH / SZ / HK / US 之一
     */
    public void setMarket(String market) { this.market = market; }

    /**
     * 获取交易货币。
     *
     * @return CNY / HKD / USD 之一
     */
    public String getCurrency() { return currency; }

    /**
     * 设置交易货币。
     *
     * @param currency CNY / HKD / USD 之一
     */
    public void setCurrency(String currency) { this.currency = currency; }
}
