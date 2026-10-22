# 同花顺金融API只读数据通道

更新日期：2026-10-22，v6.3.5。独立只读通道已经接入世界市场页面的三个 A 股指数；现有持仓、历史 K 线、观澜行情工具及回测的数据源尚未切换。不交易、不发送通知、不写入行情数据库。

## 世界市场接入范围

普通用户通过已有登录入口读取 `GET /api/market/indices`，由后端选择数据源。调用链为 `Market.tsx → MarketIndexController → WorldMarketService → HithinkDataClient / LegacyWorldMarketClient`。

| 标的范围 | 本版数据源 | 说明 |
| --- | --- | --- |
| 上证指数、深证成指、创业板指 | 同花顺金融 API 优先 | 一次请求 `/api/a-share-index/prices/snapshot`；不能误用个股快照接口 |
| 其余 17 个境外指数 | 保留 Yahoo Finance | 已核对的接口契约未覆盖这些海外指数实时报价；同名基金不是指数报价 |
| 美元指数、黄金期货、比特币、WTI 原油期货 | 保留 Yahoo Finance | 黄金为 COMEX 黄金期货 `GC=F`，原油为 `CL=F`，不是现货；页面已明确 |

同花顺失败或缺行时，仅对缺失指数尝试新浪。所有来源失败时，优先保留进程内最近 24 小时收到的有效结果，再尝试旧库的最后收盘记录；仍无结果返回 null，不补零。旧库按完整代码精确匹配，避免指数与同名股票串号。

后端全用户共享 90 秒缓存，并发访问只发起一次刷新；页面每 5 分钟请求一次。按页面访问触发，没有新增全天采集。失败也等待下个缓存周期，不即时重试。保留报价的接收时间不随失败刷新重置，24 小时从最初收到有效结果起算。

固定返回 20 个指数、4 个指标，逐条提供 `source`、`state`、`quoteTime`、`timestampScope`、`asOfDate`、`receivedAt` 和 `fallbackReason`。页面标记实际来源、保留或缺失状态，详细说明放在 ⓘ。`updatedAt` 只代表快照完成时间；同花顺批次时间为 BATCH，不能据此承诺每只指数新鲜。

冷刷新等待预算为同花顺请求（默认 5 秒）加备用采集（13 秒）；旧库查询还取决于数据库响应。Yahoo 仅在 JVM 明确配置 `socksProxyHost` 时使用 SOCKS，不再强制连接本机 7897；否则使用 curl 的直连或环境代理配置。

## 已实现边界

- A 股中文名称/代码检索、1–50 只显式指定标的的批量报价、单只股票历史日线，以及有界的 A 股指数批量快照。
- `HithinkDataClient` 是可复用业务适配器；`HithinkDataController` 仅为初步联调提供管理员入口。服务内部未来可以复用适配器，不需要让观澜直接持有供应商密钥或管理员会话。
- 不提供全市场遍历、定时采集、自动重试、历史价格覆盖、财务/资讯接口、研究数据下载或回测执行。自动换源与共享缓存仅限上述世界市场路径。
- 不依赖额外 Node/MCP/CLI 服务。普通客户端、前端构建、提示词和命令参数都不接收供应商密钥。

## 配置

独立的 `hithink-data.properties` 由配置类加载，支持标准 Spring 配置覆盖；默认关闭且无启动时网络请求。

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `HITHINK_FINANCE_ENABLED` | `false` | 显式启用独立数据通道 |
| `HITHINK_FINANCE_API_KEY` | 空 | 供应商签发的 API Key，仅留在后端运行环境 |
| `HITHINK_FINANCE_BASE_URL` | `https://fuyao.aicubes.cn` | 可信服务根地址；不含路径/查询/凭据；HTTP 只允许本机测试 |
| `HITHINK_FINANCE_TIMEOUT_MS` | `5000` | 单次完整请求等待上限，100–30000ms |

源码默认关闭。开发环境可在被 Git 忽略的 `backend/hithink-data.local.properties` 中配置 `investory.hithink.enabled=true` 和 `investory.hithink.api-key`，通过 Spring 外部配置导入。生产使用后端私密配置文件或上述环境变量。密钥不进入前端、Git 或发布日志；本版部署显式启用，原 DeepSeek 配置继续保留。

## 查询接口

独立联调入口基础路径：`/api/admin/market-data/hithink`。如配置了 `/investory` 上下文路径，在此前加 `/investory`。要求管理员登录；匿名 401、普通用户 403。外部 MCP 已移除，旧 Bearer 不再提供身份。世界市场业务接口沿用普通应用登录权限。

| 方法与路径 | 参数 | 用途 |
| --- | --- | --- |
| `GET /status` | 无 | 返回 enabled、configured 和 source；configured 只表示密钥非空，不代表已验证权限 |
| `GET /search` | `query=贵州茅台&limit=5` | 仅检索 `a-share`；关键词 1–100 字、结果数 1–20 |
| `GET /snapshot` | `symbols=600519.SH,605589.SH,000001.SZ` | 显式指定 1–50 个标的；不提供省略参数的全市场模式 |
| `GET /history` | `symbol=600519.SH&start=2026-09-21&end=2026-10-21&adjust=none` | 单只日线；闭区间、上海时区、单次不超过十年 |

支持完整 `600519.SH` / `000001.SZ` / `430047.BJ` 格式，并兼容旧库 `1.600519` / `0.000001`。裸代码不猜测交易所，按名称检索后选定完整代码。`adjust` 为 `none` / `forward` / `backward`，此接口默认 **none**；该默认只适用于新通道，未覆盖旧库口径。

响应中 `source=hithink_finance_api`；`providerTimestamp` 和 `receivedAt` 分开保存，缺失时间返回 null。`timestampScope=BATCH` 表示报价时间来自供应商批次，不能据此保证每只股票新鲜；历史为 `SERIES`，检索为 `UNKNOWN`。价格使用 CNY；涨跌幅已经是百分比数值，`1.74` 表示 1.74%，不再乘 100。数值用 BigDecimal 解析，供应商空值保持 null，不补零。

历史复权口径来自请求契约；REST 响应不一定回显标的、周期和复权参数，若回显则验证一致。历史 K 线日期须处于请求区间，重复或乱序数据拒绝为格式错误。报价响应不一定覆盖每个请求标的，消费者必须对照返回 symbol 检查缺失，不能把缺行当零价格。

## 错误与限流

同时检查 HTTP 和业务 `code`；不把 HTTP 200 当作业务成功。

| 应用错误 | HTTP | 处理 |
| --- | --- | --- |
| `DATA_DISABLED` / `DATA_NOT_CONFIGURED` | 503 | 显式启用或配置密钥 |
| `DATA_RATE_LIMITED` | 429 | HTTP 429 或业务 4001；稍后再试，不即时重发 |
| `DATA_ACCESS_DENIED` | 502 | 业务 2001/2003/2004；检查密钥和接口权限，不换协议绕过 |
| `DATA_PROVIDER_ERROR` / `DATA_UPSTREAM_ERROR` | 502 | 上游未完成；不回传上游原始错误正文 |
| `DATA_INVALID_RESPONSE` / `DATA_UNAVAILABLE` | 502 | 格式、口径、连接或超时异常 |
| 参数非法 | 400 | 修正标的、日期、数量与复权值 |

客户端不跟随重定向、不记录密钥/上游错误正文，也不缓存失败为成功。解析前拒绝超过 4MiB 的完整响应；这不是流式下载或全市场导出入口。适配器自身没有持久缓存或自动重试；世界市场服务提供上述共享缓存，独立管理员查询不共用页面缓存。

## 验证

本地自动测试通过本机临时 HTTP 服务验证认证头、中文检索、代码转换、BigDecimal 精度、百分比、null、批次时间、上海日期、复权、权限/限流、重定向禁止及超时。配置测试不加载数据库，MockMvc 验证管理员权限和错误状态。

`HithinkDataLiveTest` 默认跳过，须显式设置 `HITHINK_LIVE_TESTS=true` 并通过环境注入密钥。原测试验证检索、股票报价和日线；新测试 `verifiesIndexBatchThroughWorldMarketEndpoint` 将真实指数适配器接入世界市场服务和 MockMvc 业务端点，检查三个指数来源、价格和批次时间。其旧来源与数据库为模拟对象，不代表整页海外行情覆盖率或持续稳定性。

当前验收统计和实际查询结果见 [2026-10-22 验证记录](data-and-search-validation-2026-10-22.md)。调用成功仍不代表商业展示/缓存/历史保存/多用户共享许可已取得，也不代表 3GB 行情库可以删除。

## 后续替换前的关口

继续确认更大范围的数据许可、预算、覆盖和整场稳定性，再扩展到持仓与历史 K 线。旧数据库、现有价格源和回测引擎继续保留。供应商的基金在线回测接口不能等同于现有 A 股任意策略回测引擎，需另行评估。

参考：[同花顺金融API官方文档](https://fuyao.aicubes.cn/llms-full.txt)。
