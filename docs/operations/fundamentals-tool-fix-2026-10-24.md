# 观澜基本面工具修复

## 原因

`get_fundamentals`（界面名称“读取基本面数据”）在 StockSage 退役时重新接入了工具分发，但实现仍查询 `stock_fundamentals`。生产库没有此表，因此报 MySQL 1146。先前回归仅模拟整个工具函数，未覆盖真实取数实现。

## 本地修复

- 删除该工具的财务表查询。A股代码直接访问同花顺；中文名称仅通过保留的证券身份表解析。
- 估值使用 `/api/a-share/valuations/snapshot`。最新财报通过 `/api/a-share/financials/income-statements?period=quarterly&limit=1` 确认已披露报告期，再读取 `/api/a-share/financials/indicators`。
- 分开输出估值快照时间、财报截止日和披露日。财报累计 EPS 不冒充 TTM；百分比不重复乘100；缺失数据保留 null。归母净利润增长率与净利润增长率使用不同字段。
- 财报标的、报告期和日期校验失败时不接受该部分。部分接口失败保留其他结果并输出 limitations；全部失败才标记工具错误。没有旧数据库回退。
- 财务指标响应为 `data.abilities`，因此与既有 `data.item` 列表接口分别校验，仍共用鉴权、超时和限流保护。

## 验证

本地 Python 全套85项通过；观澜 smoke 28项通过、15项需完整对话环境而跳过。新增6项测试通过实际工具分发和传输解析，禁止代码查询路径使用数据库，覆盖名称解析释放连接、部分/全部失败、错误标的/未来披露日、对象响应及百分比口径。

真实只读取数：圣泉集团 `605589.SH`、贵州茅台 `600519.SH` 均返回估值和2026年中报；本次验证只调用金融API，不涉及交易。原始公共行情结果保存在 `D:/Attention/reports/fundamentals-fix-2026-10-24/live-tool-results.json`，不含密钥。

实测财务指标的营收同比字段为 `calculate_operating_income_yoy_growth_ratio`，与文档示例不同，已覆盖实际字段并兼容文档字段；归母同比单独读取 `calculate_parent_holder_net_profit_yoy_growth_ratio`，不替代缺失的净利润同比。

此修复纳入 v6.4.0；实际部署状态以云端 `/opt/investory/release.json` 和本地发布回执为准。
