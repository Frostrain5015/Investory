package com.investory.model;

import java.time.LocalDateTime;

/**
 * 投资组合实体类。
 * <p>
 * 对应数据库 portfolio 表，是系统的核心聚合根之一。
 * 一个用户可以创建多个投资组合，每个组合拥有独立的持仓（Holding）、
 * 交易记录（Transaction）、分红记录（Dividend）以及每日净值快照（DailyValue）。
 * </p>
 */
public class Portfolio {

    /** 数据库自增主键 */
    private Long id;

    /** 所属用户 ID，外键引用 user 表 */
    private Long userId;

    /** 投资组合名称，由用户自定义，例如 "主账户"、"港股仓位" */
    private String name;

    /** 投资组合创建时间，由数据库或业务层在插入时自动赋值 */
    private LocalDateTime createdAt;

    /** 无参构造器，供 JdbcTemplate RowMapper 及序列化框架使用 */
    public Portfolio() {}

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
     * 获取所属用户 ID。
     *
     * @return user 表主键
     */
    public Long getUserId() { return userId; }

    /**
     * 设置所属用户 ID。
     *
     * @param userId user 表主键
     */
    public void setUserId(Long userId) { this.userId = userId; }

    /**
     * 获取投资组合名称。
     *
     * @return 用户自定义的组合名称
     */
    public String getName() { return name; }

    /**
     * 设置投资组合名称。
     *
     * @param name 用户自定义的组合名称
     */
    public void setName(String name) { this.name = name; }

    /**
     * 获取创建时间。
     *
     * @return 投资组合的创建时间戳
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * 设置创建时间。
     *
     * @param createdAt 投资组合的创建时间戳
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
