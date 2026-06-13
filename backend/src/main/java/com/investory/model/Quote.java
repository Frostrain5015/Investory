package com.investory.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 实时行情报价记录类（不可变值对象）。
 * <p>
 * 使用 Java 16+ {@code record} 语法定义，封装从行情数据源拉取到的某只股票的实时价格
 * 及对应的抓取时刻。该对象由行情服务（QuoteService / 爬虫组件）创建，
 * 并传递给 HoldingService 用于计算持仓快照（HoldingSnapshot）中的当前市值和浮动盈亏。
 * </p>
 *
 * @param price     实时成交价格（以股票原始货币计价，例如 CNY / HKD / USD）
 * @param fetchedAt 价格数据从数据源抓取时的 UTC 时间戳（ISO-8601 Instant），
 *                  用于判断行情是否过期及在前端展示数据时效性
 */
public record Quote(BigDecimal price, Instant fetchedAt) {}
