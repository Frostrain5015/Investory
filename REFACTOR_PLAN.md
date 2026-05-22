# Investory 全栈代码质量优化方案

> 项目路径: `D:/Java Projects/investory`
> 优先级: Security > Data Integrity > Testability > Code Quality
> 原则: 单人开发者可行, 每阶段可独立部署, 不做企业级过度设计
> 实施节奏: 逐 Phase 推进, 每个 Phase 完成后验证再继续

---

## 项目现状概要

| 维度 | 数据 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.3 + JdbcTemplate + MySQL, 54 个 Java 文件 |
| 前端 | React 19 + TypeScript + Vite + Tailwind v4, 39 个 TS/TSX 文件 |
| Python | 数据抓取 + AI Agent + 回测引擎, 10 个 .py 文件 |
| @Transactional 使用数 | **0** |
| 测试文件数 | **0** |
| 控制器中直接 JdbcTemplate 调用 | **92 处** |
| 后端空 catch 块 | **32 处** |
| 前端空 catch 块 | **28 处** |
| 前端直接 fetch 绕过 api.ts | **53 处** (68%) |
| 无管理线程池 | **5 个** |

---

## Phase 1: 安全加固 (12-16h)

> 最高优先级。当前任何人都可无认证访问全部 API, API Key 可被明文读取, SSL 密码硬编码。

### 1.1 修复认证绕过 [4h] -- 最关键的安全漏洞

**问题**: `WebConfig.java` 中 `excludePathPatterns("/api/**")` 导致 `LoginInterceptor` 对所有 API 端点失效。

**修改文件**:
- `backend/src/main/java/com/investory/web/WebConfig.java`

**具体变更**:
```
- 从 excludePathPatterns 中移除 "/api/**"
- 仅保留公开端点白名单: "/api/session", "/api/stock/search"
- SSE 端点(/api/*/stream) 由 LoginInterceptor 放行已认证用户
```

**配套修改**:
- `LoginInterceptor.java` -- 对 API 请求返回 401 JSON 而非 redirect
- `SessionController.java` -- 确认 `/api/session` 保持公开
- `StockSearchApiController.java` -- 确认 `/api/stock/search` 保持公开
- 所有 Controller 中 `getUserId()`/`getPortfolioId()` -- userId=0 时返回 401 而非静默返回空数据

**需检查认证逻辑的文件**:
- DataApiController -- getPortfolioId() 静默返回 0
- WatchlistController -- getUserId() 静默返回 0
- StrategyApiController -- getUserId() 静默返回 0
- AdminController -- 依赖 session 的 isAdmin, 但 API 层无拦截
- AiSettingsController -- API Key 端点无保护
- ChartDataController, AiApiController, BacktestApiController, QuantApiController -- 手动检查 session

### 1.2 修复 API Key 明文泄露 [2h]

**问题**: `AiSettingsController` 的 `POST /api/ai/key` 直接返回 api_key 明文。

**修改文件**:
- `AiSettingsController.java` -- 删除或改造 getKey() 端点, 不再返回明文
- `AiApiController.java` -- 修改 ProcessBuilder 传参方式 (见 1.5)
- `frontend/src/pages/Settings.tsx` -- 移除显示 API Key 的逻辑

### 1.3 敏感信息外移 [2h]

**问题**: application.properties 中 SSL 密钥密码硬编码; script/config.ini 包含明文 DB 密码。

**具体变更**:
- `application.properties`: `server.ssl.key-store-password=${SSL_KEY_PASSWORD:changeme}`
- `script/config.ini`: 添加到 .gitignore, 仅保留 config.ini.example
- `deploy.py`: 当前从环境变量/文件读取可接受, 但 memory 文件路径不应硬编码

### 1.4 Admin 端点权限加固 [2h]

**问题**: AdminController 所有端点无额外角色检查。

**具体变更**:
- 所有 Admin 端点增加 isAdmin 检查, 返回 403
- 考虑提取 @AdminOnly 注解或在 LoginInterceptor 中增加角色检查

### 1.5 API Key 传递方式改进 [2h]

**问题**: AiApiController 将 API Key 作为命令行参数传给 Python 进程, `ps aux` 可见。

**具体变更**:
- 改用环境变量: `processBuilder.environment().put("AI_API_KEY", key)`
- `ai_agent.py` 相应修改: 从环境变量读取

---

## Phase 2: 数据完整性 (10-14h)

> 当前所有多表写操作均无事务保护, 部分失败会导致数据不一致。

### 2.1 添加 @Transactional 到关键写操作 [6h]

**策略**: 渐进式 -- 先在 Controller 写方法上加 @Transactional, 后续 Phase 4 拆 Service 时再迁移。

**需要 @Transactional 的关键方法**:

```
DataApiController:
- createTransaction() -- 涉及 transactions + cash_balances + holdings + daily_portfolio_value
- updateTransaction() -- 同上
- deleteTransaction() -- 同上
- refreshPortfolio() -- 批量更新 daily_portfolio_value

AdminController:
- deleteUser() -- 6 条 DELETE

AuthService (已有 Service):
- register() -- userDao.insert + portfolioDao.insert
```

**修改文件**:
- `AuthService.java` -- register() 加 @Transactional
- `DataApiController.java` -- 写方法加 @Transactional
- `AdminController.java` -- deleteUser() 加 @Transactional

**删除手动回滚代码**:
- DataApiController 中 reverseCashEffect()/applyCashEffect() 是手动事务补偿, 加 @Transactional 后可移除

### 2.2 添加全局异常处理器 [3h]

**新增文件**: `backend/src/main/java/com/investory/web/GlobalExceptionHandler.java`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAll(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(500).body(Map.of("error", "服务器内部错误"));
    }
    // DataAccessException, 数据未找到, 参数校验失败 等
}
```

**配套修改**: 逐个审查 32 个 `catch(Exception ignored){}`, 改为 log.warn 或抛出特定异常

### 2.3 消除运行时 DDL [1h]

**问题**: StockSearchIndexService 在 @PostConstruct 中执行 ALTER TABLE。

**具体变更**:
- 移除 ALTER TABLE 逻辑
- 在数据库中手动执行一次 (一次性操作)
- 后续 Phase 7 引入 Flyway 时纳入版本管理

---

## Phase 3: 线程安全与资源管理 (6-8h)

> 当前 5 个无管理线程池/线程, 其中 4 个 newCachedThreadPool 可无限创建线程。

### 3.1 统一线程池管理 [4h]

**新增文件**: `backend/src/main/java/com/investory/config/AsyncConfig.java`

```java
@Configuration
public class AsyncConfig {
    @Bean("sseExecutor")
    public ExecutorService sseExecutor() {
        return new ThreadPoolExecutor(
            2, 10, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
```

**修改文件**: 移除各自的 newCachedThreadPool, 注入共享 sseExecutor
- AdminController, AiApiController, BacktestApiController, QuantApiController
- MarketIndexController -- 移除每次请求创建的 25 线程池
- DataApiController -- 移除 `new Thread().start()`
- StockSearchIndexService -- 移除 `new Thread().start()`

### 3.2 SSE 辅助方法去重 [2h]

**新增文件**: `backend/src/main/java/com/investory/controller/api/SseHelper.java`

```java
public class SseHelper {
    public static void emit(SseEmitter emitter, String event, Object data) { ... }
    public static long getUserId(HttpServletRequest req) { ... }
    public static long getPortfolioId(HttpServletRequest req) { ... }
}
```

**修改文件**: 4 个 Controller 中删除重复的 emit()/getUserId()/getPortfolioId()

---

## Phase 4: 后端代码结构优化 (14-18h)

### 4.1 拆分 DataApiController (God Class) [8h]

**问题**: 846 行, 25+ 端点, 7 个领域职责混合, ~50 处直接 jdbc 调用。

**拆分方案** (保持扁平结构, 所有文件在 controller/api/ 下):

```
DataApiController (846行) 拆分为:

1. PortfolioController.java    -- /api/portfolios, /api/dashboard
   - getDashboard(), getPortfolios(), createPortfolio(), setActivePortfolio(), deletePortfolio()

2. TransactionController.java  -- /api/transactions
   - getTransactions(), createTransaction(), updateTransaction(), deleteTransaction()

3. DividendController.java     -- /api/dividends
   - getDividends(), createDividend(), deleteDividend()

4. CashController.java        -- /api/cash
   - getCashBalances(), addCash(), updateCashBalance()

5. StockDetailController.java -- /api/stocks/{symbol}
   - getStockDetail()

6. DailyDetailController.java -- /api/daily-detail, /api/monthly-detail
   - getDailyDetail(), getMonthlyDetail()
```

### 4.2 Controller SQL 下沉到 DAO 层 [4h]

**问题**: 92 处 Controller 直接使用 JdbcTemplate, 绕过已有 DAO 层。

**统计**:
- DataApiController: ~50 处
- AdminController: ~15 处
- ChartDataController: ~10 处
- MarketIndexController: ~7 处
- WatchlistController: ~5 处
- AiSettingsController: ~5 处

**策略**: 与 4.1 同步进行, 拆分 Controller 时同时将 SQL 迁入 DAO

### 4.3 新增 DAO 文件 [2h]

**新增文件**:
- `CashBalanceDao.java` -- cash_balances 表操作
- `AiSettingsDao.java` -- ai_settings 表操作
- `WatchlistDao.java` -- 从 WatchlistController 的 jdbc 调用提取

### 4.4 StockSearchIndexService 优化 [2h]

**具体变更**:
- 索引构建改为 @EventListener(ApplicationReadyEvent.class)
- 使用共享线程池, 构建失败不影响应用启动

---

## Phase 5: 前端代码质量 (12-16h)

### 5.1 拆分 Quant.tsx (God Component) [4h]

**问题**: 721 行, 20+ useState, 4 个组件, 9 处 any, 8 处空 catch, 5 处直接 fetch。

```
pages/Quant.tsx (721行) 拆分为:

pages/quant/
  QuantPage.tsx          -- 顶层 tab 切换 (~50行)
  RiskSection.tsx        -- 风险分析 + 风格诊断 (~150行)
  BacktestSection.tsx    -- 回测主逻辑 (~250行)
  StrategyEditor.tsx     -- 策略编辑器 (~100行)
  RuleEditor.tsx         -- 规则编辑子组件 (~80行)
  useQuantData.ts        -- 共享状态 hook (~80行)
```

### 5.2 拆分 Admin.tsx + 消除全局变量 [4h]

**问题**: 592 行, 7 个模块级全局变量, SSE 代码重复 ~50 行 x2。

```
pages/Admin.tsx (592行) 拆分为:

pages/admin/
  AdminPage.tsx          -- 主页面 (~100行)
  CrawlPanel.tsx         -- 数据抓取面板 (~200行)
  UserManagement.tsx     -- 用户管理面板 (~100行)
  useCrawlSse.ts         -- SSE 状态 hook, 替代全局变量 (~60行)
```

**消除全局变量**: 用 useRef + 自定义 hook 替代 gCrawling, gProgress, gLogs, gDoneMsg, gPaused, gEsRef, gHeartbeat

### 5.3 API 调用统一到 api.ts [4h]

**问题**: 53 处直接 fetch 调用绕过 api.ts。

**api.ts 需新增**:
```typescript
// Admin API
export function adminGetStatus(): Promise<AdminStatus>
export function adminCrawlStart(market: string): Promise<Response>
export function adminCrawlPause(): Promise<Response>
export function adminCrawlResume(): Promise<Response>
export function adminCrawlStop(): Promise<Response>
export function adminGetUsers(): Promise<User[]>
export function adminDeleteUser(id: number): Promise<{status: string}>
export function adminSseStream(): EventSource

// Watchlist API
export function addToWatchlist(stockId: number): Promise<{status: string}>
export function removeFromWatchlist(stockId: number): Promise<{status: string}>
export function reorderWatchlist(items: OrderItem[]): Promise<{status: string}>

// AI API
export function aiChat(messages: Message[], deepThink: boolean): Promise<Response>
export function aiClear(): Promise<void>

// Portfolio API
export function refreshPortfolio(): Promise<Response>
```

### 5.4 修复空 catch 块 + any 类型 [2h]

- 空 catch: 统一添加 toast 提示或 console.error
- any 类型: 为 strategies/styleData/stockSearchResults 等创建具体接口
- tsconfig.app.json: 先开启 noImplicitAny, 再逐步开启 strictNullChecks

### 5.5 Dashboard.tsx 重复渲染优化 [2h]

- 提取共享 HoldingRow/HoldingCard 组件, 通过 isMobile prop 切换

---

## Phase 6: 测试基础设施 (8-10h)

### 6.1 后端测试框架搭建 [3h]

**新增依赖** (pom.xml):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

**新增文件**:
- `AuthServiceTest.java` -- 注册/登录逻辑单元测试
- `TransactionDaoTest.java` -- DAO 层测试 (H2 内存库)
- `application-test.properties` -- 测试配置

**测试优先级**:
1. AuthService (纯单元测试)
2. TransactionDao / HoldingDao (H2 内存库)
3. 关键写操作的事务性测试

### 6.2 前端测试框架搭建 [3h]

**新增依赖** (package.json):
```json
{
  "devDependencies": {
    "vitest": "^3.x",
    "@testing-library/react": "^16.x",
    "@testing-library/jest-dom": "^6.x",
    "jsdom": "^25.x"
  }
}
```

**新增文件**:
- `vitest.config.ts`
- `api.test.ts` -- request<T>() 函数单元测试
- `format.test.ts` -- 纯函数测试

### 6.3 关键业务逻辑测试 [4h]

**后端**: AuthServiceTest (注册验证/登录验证), CostCalculationServiceTest (均价/成本计算)

**前端**: api.test.ts (401 跳转/非 200 抛错), format.test.ts (数字格式化/汇率转换)

---

## Phase 7: 数据库迁移与项目基建 (6-8h)

### 7.1 引入 Flyway [4h]

**新增依赖** (pom.xml):
```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

**新增文件**:
- `db/migration/V1__initial_schema.sql` -- 从现有数据库导出当前 schema
- `db/migration/V2__add_name_pinyin.sql` -- 替代运行时 ALTER TABLE

**配置** (application.properties):
```properties
spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=1
```

### 7.2 代码质量工具 [2h]

**后端**: pom.xml 添加 maven-checkstyle-plugin

**前端新增** (package.json):
```json
{
  "devDependencies": {
    "prettier": "^3.x",
    "eslint-config-prettier": "^9.x",
    "husky": "^9.x",
    "lint-staged": "^15.x"
  },
  "scripts": {
    "format": "prettier --write 'src/**/*.{ts,tsx}'",
    "test": "vitest",
    "type-check": "tsc --noEmit",
    "prepare": "husky"
  }
}
```

**新增配置**: `.prettierrc`, `.husky/pre-commit` (lint-staged)

### 7.3 移除硬编码代理配置 [2h]

- `pom.xml`: `<jvmArguments>${JVM_PROXY_ARGS}</jvmArguments>`
- `script/config.ini`: 代理 URL 仅从环境变量读取

---

## Phase 8: Python 脚本质量 (4-6h)

### 8.1 统一数据库连接管理 [2h]

**新增文件**: `script/db.py` (共享 get_conn() 和 load_config()), `script/config.py` (统一配置加载)

**修改文件**: ai_agent.py, fetch_stocks.py, fetch_news.py, fetch_fundamentals.py -- 移除内联 get_db_conn()

### 8.2 空 catch 清理 [1h]

- ai_agent.py: `except: pass` 改为 `except Exception as e: logging.warning(...)`
- fetch_news.py: bare except 改为 except Exception

### 8.3 requirements.txt 规范化 [1h]

- 确保所有依赖有版本锁定
- 考虑 pip-tools 生成

---

## 实施路线图

```
Week 1:  Phase 1 (安全)              -- 12-16h
         Phase 2 (数据完整性)         -- 10-14h

Week 2:  Phase 3 (线程安全)          -- 6-8h
         Phase 4.3-4.4 (DAO 新建/优化) -- 4h

Week 3:  Phase 4.1-4.2 (God Class 拆分 + SQL 下沉) -- 12h

Week 4:  Phase 5 (前端代码质量)       -- 12-16h

Week 5:  Phase 6 (测试基础设施)       -- 8-10h
         Phase 7 (数据库迁移 + 基建)  -- 6-8h

Week 6:  Phase 8 (Python 优化)       -- 4-6h
         回归测试 + 文档更新
```

**总工时估算**: 72-96 小时

---

## 依赖新增汇总

### 后端 (pom.xml)

| 依赖 | 用途 | Phase |
|------|------|-------|
| spring-boot-starter-test | 测试框架 | 6 |
| h2 | 内存数据库测试 | 6 |
| flyway-core + flyway-mysql | 数据库迁移 | 7 |

### 前端 (package.json)

| 依赖 | 用途 | Phase |
|------|------|-------|
| vitest | 测试框架 | 6 |
| @testing-library/react | React 组件测试 | 6 |
| @testing-library/jest-dom | DOM 断言 | 6 |
| jsdom | DOM 环境 | 6 |
| prettier | 代码格式化 | 7 |
| eslint-config-prettier | ESLint/Prettier 兼容 | 7 |
| husky | Git hooks | 7 |
| lint-staged | 暂存区 lint | 7 |

---

## 风险与注意事项

1. **Phase 1 认证修复是破坏性变更**: 前端 53 处直接 fetch 需逐一检查是否携带 credentials: 'include'
2. **DataApiController 拆分需同步前端**: API 路径可能变化, 需确认前端兼容
3. **Flyway baseline-on-migrate**: 对已有数据库需正确设置 baseline, 否则会执行全量 migration
4. **@Transactional 添加顺序**: 必须先加全局异常处理器 (Phase 2.2), 再加事务注解 (Phase 2.1), 否则事务回滚的异常会被 500 直接返回
5. **线程池统一**: 改为有界队列后, 高并发 SSE 请求可能被拒绝, CallerRunsPolicy 是安全兜底但会阻塞请求线程
6. **前端 strict 模式**: 一次性开启 strict 可能导致大量编译错误, 逐步开启 (先 noImplicitAny, 再 strictNullChecks)
