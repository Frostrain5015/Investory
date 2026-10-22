# 观澜 BaoStock 工具路径迁移

日期：2026-10-22。本记录描述本地迁移与验证阶段；本批纳入 v6.3.7，部署结果以云端 release.json 为准。

## 迁移范围

| 入口 | 新路径 | 口径 |
| --- | --- | --- |
| 观澜 `get_stock_price_history`，A 股分支 | 共享 Python 适配器 → 同花顺日线 | 不复权，保留原工具价格口径；股票名称仍通过业务库解析 |
| StockSage 研究、因子、扫描共用的 `get_price_history` | 同花顺日线 | 明确 `adjust=forward`，不再先尝试通达信、东方财富等历史价格来源 |
| StockSage 估值读取 | `get_valuation_snapshot` → 同花顺估值快照 | 最新 PE TTM、PB MRQ、PS TTM、PCF TTM；缺失保留为空，负值保留 |
| `fetcher.get_trade_calendar` | 同花顺交易日历 | 仅最近一年至今天；不推断未来日历 |

共享适配器为 `script/hithink_finance.py`。StockSage 的常驻 HTTP 引擎与 CLI 后备执行都通过同一 fetcher 使用它。仅调用只读 REST API，不接入 MCP，不涉及交易接口。

已移除 StockSage 中 BaoStock 的导入、登录、单连接锁、后台超时查询和重连逻辑。去掉了 bridge 中重复覆盖估值因子分数的补丁，由 research 使用新快照完成一次完整评分。

## 数据语义与边界

- 同花顺[估值接口](https://fuyao.aicubes.cn/docs/api-reference/valuations/)只提供当前快照，没有历史估值序列。代码不生成历史 PE/PB 分位或历史估值区间；评分仍可使用已有行业比较或绝对阈值，报告明确列出历史分位不可用。
- 同花顺[日线接口](https://fuyao.aicubes.cn/docs/api-reference/prices/)的 `volume` 单位为股，`turnover` 是成交额，映射为引擎 `amount`，不能映射为换手率。依赖历史换手率的因子缺少输入，报告列出限制。
- 日线校验日期范围、重复日期、OHLC 完整性及高低价关系。空响应保持为空；失败不静默切回 BaoStock、不填零、不混入旧数据库历史行情。
- 原始日线工具仍返回 `symbol/count/points/truncated/note`，补充来源、复权、币种、成交量单位和上游时间。单次历史范围上限为 3650 个自然日。
- 同花顺[交易日历](https://fuyao.aicubes.cn/docs/api-reference/calendar/)为固定最近一年窗口。`fetcher.is_trading_day` 对超范围日期或日历不可用抛出明确错误，避免把未知误判为休市或普通工作日。
- 上游 `timestamp` 保留原义，不视为单标的行情延迟保证。原有 IC 校准记录保留，并注明尚未用新数据源重新验证。

## 配置、缓存与故障处理

复用 Spring 已使用的私密 `hithink-data.local.properties`：项目根目录及 `backend/`，后者优先。部署时共享文件为 `/opt/investory/hithink-data.local.properties`，与当前进程工作目录无关。

环境变量优先：`HITHINK_FINANCE_ENABLED`、`HITHINK_FINANCE_API_KEY`、`HITHINK_FINANCE_BASE_URL`、`HITHINK_FINANCE_TIMEOUT_MS`。默认关闭且无密钥，不提供内置凭据。修改配置后需要重新启动使用它的 Python 进程；本次未重启任何服务。

密钥只放请求头；不跟随重定向，不走系统代理，不输出上游响应正文或密钥。校验 HTTP 与业务 `code`。进程内串行请求、请求开始间隔至少 250ms；网络或业务失败后冷却 60 秒，不自动重试。限流属于单个 Python 进程，不是 Java/Python 跨进程的全局配额管理。

日线缓存使用来源、复权、日期和范围组成的新键，TTL 为一小时；估值快照缓存十五分钟；日历缓存按当天分键、TTL 一小时。旧 BaoStock/其他来源缓存不读取、不删除。业务缓存与前端页面三十秒报价刷新是不同路径，本次未修改实时刷新策略。

## 验证

执行 `python -X utf8 -m unittest discover -s script/tests -v`：48 项通过，其中新增 16 项覆盖适配器、配置、故障、路由、缓存隔离、快照评分和报告缺失项。没有调用真实券商或发送邮件。

2026-10-22 17:54（收盘后）进行了 6 次真实读取检查，全部成功：

| 检查 | 结果 |
| --- | --- |
| 贵州茅台前复权日线 | 365 根，2025-04-21 至 2026-10-22 |
| 平安银行前复权日线 | 365 根，2025-04-21 至 2026-10-22 |
| 贵州茅台估值快照 | PE/PB/PS/PCF 均有值 |
| 交易日历 | 243 个交易日，2025-10-22 至 2026-10-22 |
| 贵州茅台不复权日线 | 请求范围内 81 根 |
| 引擎 `get_price_history('600519.SH', 60)` | 返回 60 根，最新日期 2026-10-22，成交额映射正确 |

单次观察耗时约 178–258ms，部分包含进程内节流等待。它们是收盘后的有限连通性检查，不能代表盘中持续可靠性、全市场覆盖率或服务 SLA。完整研究报告使用隔离数据完成本地回归，未向其他旧数据源发起全量研究请求。

原始记录：`D:/Attention/reports/guanlan-hithink-migration-2026-10-22/live-probe.json`，不含密钥。

## 保留的旧路径

本次没有全局移除 BaoStock 依赖。`script/fetch_stocks.py`、`script/fetch_fundamentals.py` 的批量抓取仍使用 BaoStock；其他财务、资金、实时行情及 `trading_calendar.py` 的既有 Sina/Tushare 路径尚未迁移。观澜的相关性计算等其他工具仍可能读取 `stock_prices`。

没有断开 MySQL、删除行情表、修改业务表或运行远程命令。以上边界意味着本次迁移不能作为删除本地行情数据库的验收依据。
