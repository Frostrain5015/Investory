# 2026-10-20 全仓架构梳理与代码清理

范围：本地工作树的 Java、React/TypeScript、Python、Electron、构建清单和文档。保留开始时已有的桌面 `www` 路径/配置、行情引用 token 功能、AI 提示词改动，以及用户未完成的空 `AppConfig.java`。没有提交、部署、重启服务、修改数据库或执行真实交易。

## 1. 已完成的清理

| 类别 | 实际变更 | 原因/效果 |
| --- | --- | --- |
| Python 死代码 | 删除 3,617 行旧 `factors/technical.py` | 与生效的同名 package 重叠；比对导入路径和实现后保留 package，避免两套实现漂移 |
| Java 死代码 | 删除 `SseHelper`、`GlobalModelAdvice` | 前者无调用，后者提供的旧模板属性没有消费方 |
| 前端死代码 | 删除未引用的 Watchlist、badge/input/table、stockNames loader、react-simple-maps 声明 | 已核对路由、静态/动态导入；没有删除仍使用的自选股业务、Hero 动效或研究筛选器 |
| 冗余页面耦合 | 拆出 RiskSection/BacktestSection，删除无实际路由的 Quant 外壳 | Research 直接依赖研究功能模块，不再借用另一个大页面的导出 |
| Context 耦合 | 拆分九组 Provider/Context/Hook | Provider 组件与消费 API 分离，不再通过禁用规则绕过 Fast Refresh 检查 |
| 聊天页面耦合 | 提取 types、MarkdownBody、store | 保留行情引用和 Markdown；消息订阅改用 React 外部存储接口 |
| 重复基础设施 | 七个脚本复用 `db.py` | 统一配置优先级、连接选项，保留原先各脚本 autocommit 差异 |
| 模型传输耦合 | 提取 `model_transport.py`，删除无调用的模型路由常量/复杂度判定 | localhost/私网推理不再误走海外代理，国内域名按真实主机边界匹配 |
| 文件残留 | 删除内容仅为 `MySQL` 的根目录 `start` 和 `frontend/query` | 不是构建/运行入口 |

静态资源和导出 API 没有仅凭名字或零文本命中盲删；Spring 自动装配、Thymeleaf、Python 公开导出和全局类型声明分别检查。动态加载情况下仍应通过行为验证判断使用情况。

## 2. 已修复的实际缺陷

### 回测执行与会话

- 原回测状态是所有用户共用的 singleton 状态；现在按用户隔离，旧任务不能结束或污染同用户的新任务。
- 同用户忙碌时在创建数据库记录前拒绝启动，返回 HTTP 409，避免空结果残留。
- 将进程执行、输出解析、结果持久化从 HTTP 控制器移到 `BacktestExecutionService`。
- 修复读取输出阻塞导致等待超时不生效；静默进程也能被计时终止，异常、拒绝提交和中断都释放运行状态。
- 失败信息通过 ObjectMapper 序列化，包含引号/换行时依然是有效 JSON；输入、结果、错误临时文件在 finally 清理。
- 完成事件可向稍晚连接的 SSE 客户端重放；移除前端先 fetch 同一个 SSE 地址、再创建 EventSource 的重复订阅。
- 无效 JSON/回测参数返回 400，未登录启动返回 401，补充 HTTP 边界回归。

### 前端状态与类型

- 清理 Hook 依赖、渲染期时间计算、同步 effect 重设状态、状态直接修改及未使用声明，保持 ESLint 规则开启。
- 持仓/日历/仪表盘/交易按组合重建，后台分析缓存使用组合 key；取消后台分析时同时取消请求及定时器。
- 补充可取消的异步资源 Hook，旧请求不覆盖新请求；修复股票详情、行情引用、图表与持仓缓存的失效依赖。
- 用显式浏览器语音识别类型替代 `any`。
- 确认框/输入框不再在 React 状态 updater 中执行 Promise 回调；同一批确认请求固定各自 ID，替换输入请求及卸载时结束待处理 Promise。
- API 遇到 401 或空 JSON 响应抛出明确错误，不再把 `{}` 伪装成任意业务类型。
- 修复设置中的汇率读取：前端调用的 `/api/market/exchange-rates` 原本没有实现，且按扁平字段读取响应。补上只读接口并按 `rates` 读取，返回数据库已有的“1 CNY 对应外币数量”；原表没有时间戳，`updatedAt` 明确返回 null，不伪造新鲜度。
- 新增 `npm run check`：类型检查、零 warning lint、应用模块可达性检查。

### Python 数据采集

- `fetch_fundamentals.py` 原先有无法解析的 try/缩进错误，修复后可正常解析。
- 修正 `116.00700 → 0700.HK`、`105.AAPL → AAPL` 的 Yahoo 符号转换；坏行不会中断后续股票。
- 代理变量在异常/中断后恢复，避免影响后续市场。
- dry-run 不再执行建表或提交；成功的正常采集显式 commit，所有路径关闭共享连接，避免原来的临时连接泄漏/未提交数据。
- 新闻采集使用的 `feedparser` 补入依赖清单。

### 桌面与交付

- 标题生成的 Key 改通过环境变量传入，不再暴露在进程命令行；同时使用真实 15 秒超时与 finally 清理。
- 移除 Electron 全局忽略证书错误的参数。
- 补齐桌面 start/pack/build:exe 与已安装版本对应的开发依赖，前端构建包含类型检查，打包默认不发布。
- 修正 packaged 图标路径；必要 preload、图标及前端配置示例不再被整体 `desktop/` 忽略规则漏掉。
- 同步依赖锁文件的根版本信息；未更换应用框架版本。
- Maven 不再忽略前端构建失败。README 的源目录、版本、Python/Node 前提及“交易台账”的描述同步修正。

## 3. 验证结果

| 验证 | 结果 | 覆盖范围 |
| --- | --- | --- |
| Java Maven 离线测试 | 28 项，25 通过、3 跳过、0 失败/错误 | 认证、盈亏公式、AI 会话、回测隔离/并发、服务失败处理、HTTP/SSE、汇率接口、静默进程超时 |
| Python unittest | 10 项全部通过 | 源码解析/同名 package 遮蔽、配置覆盖、autocommit、代理分类、基本面符号/代理恢复/dry-run/提交 |
| 前端类型检查与 ESLint | 通过，0 错误、0 warning | app 与工具配置类型、React Hook、Fast Refresh、显式 any 等规则 |
| 前端导入图 | 72 个应用模块全部可达 | main.tsx 可达的 TS/TSX 静态及字面量动态导入 |
| Vite Web 构建 | 通过 | 后端 static 目录的生产前端产物 |
| 桌面前端构建 | 通过 | `desktop/www` 输出及桌面构建脚本链 |
| Electron 主进程/preload 语法 | 通过 | Node 语法检查；不是安装包/更新/登录的端到端验证 |

原来的三项采集测试在数据库认证失败时也可能“通过”，现在必须显式设置 `INVESTORY_INTEGRATION_TESTS=true` 才会运行，并要求采集历史状态为 `ok`。本轮最终验证不依赖真实数据库或行情网络，跳过项不算通过。

## 4. 未覆盖事项

- 没有完整运行真实浏览器页面、Electron 安装包、登录与更新链路。
- 没有执行供应商计费 API 或智能体 eval，也没有验证生产环境。
- 没有调整财务口径、回测收益假设或自动升级依赖；既有少量盈亏公式测试不能证明全部财务逻辑正确。
- AI 控制器/主脚本、其他子进程调用点、运行时 DDL 和多实例任务管理仍是需要后续分阶段拆分的边界，见 [当前架构](architecture.md)。
- Web 构建保留图表/主入口大包提示，未通过调高阈值隐藏。
