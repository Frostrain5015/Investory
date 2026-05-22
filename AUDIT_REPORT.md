# Investory 代码质量审计报告

> 审计日期: 2026-05-22 | 基线版本: 原始问题清单

---

## Phase 5 — 前端代码质量问题

### 1. Quant.tsx God Component

| 项目 | 状态 |
|------|------|
| **当前状态** | **NOT FIXED** |

**详情:**
- **行数**: 733 行 (原 721 行, 反而增加了)
- **文件未拆分**: `pages/quant/` 目录不存在, 仍然在 `pages/Quant.tsx` 单文件
- **useState 数量**: 40 个 (原 20+, 翻倍)
- **文件内组件**: 3 个 (`Quant`, `RiskSection`, `BacktestSection`) + 1 个辅助组件 (`RuleEditor`)
- **未拆分为独立文件**: `RiskSection` 和 `BacktestSection` 各自包含完整逻辑, 理应拆分

**改善**: 主组件 `Quant` 本身已精简为 tab 切换壳, `RiskSection` / `BacktestSection` 拆为了函数组件, 但仍在同一文件内。

---

### 2. Admin.tsx God Component

| 项目 | 状态 |
|------|------|
| **当前状态** | **PARTIALLY FIXED** |

**已改善:**
- **行数**: 594 行 (原 592 行, 基本不变)
- **未拆分到 `pages/admin/` 目录**: 目录不存在
- **全局变量改进**: 引入了 `useCrawlStore()` hook + `crawlListeners` 发布订阅模式, 组件通过 hook 访问全局状态, 实现了 SPA 导航后状态持久化

**仍存在的问题:**
- 7 个模块级全局变量依然存在: `gCrawling`, `gProgress`, `gLogs`, `gDoneMsg`, `gPaused`, `gEsRef`, `gHeartbeat` (另增 `gLastBump`)
- 全局变量并非通过 React context 或状态管理库管理, 而是通过自定义 hook 间接读取/写入裸变量
- SSE 事件绑定代码在 `startCrawl` 和 mount effect 中重复出现 (约 60 行重复)

---

### 3. 直接 fetch 调用绕过 api.ts

| 项目 | 状态 |
|------|------|
| **当前状态** | **PARTIALLY FIXED** |

**api.ts 现状:**
- 导出 ~45 个 API 函数, 覆盖: Auth, Dashboard, Holdings, Transactions, Dividends, StockDetail, Portfolios, Charts, Cash, StockSearch, Quant, Backtest, Admin, Watchlist, AI, Market, Account
- 有统一的 `request<T>()` 封装, 含 401 自动跳转、错误处理

**仍存在的直接 fetch 调用 (排除 api.ts):**

| 文件 | 直接 fetch 数 | 说明 |
|------|-------------|------|
| Admin.tsx | 9 | status, users, crawl-history, crawl/status, stop, pause/resume, impersonate, delete user, clear history |
| Holdings.tsx | 6 | reorder, holdings, watchlist, refresh, add/remove watchlist |
| Portfolio.tsx | 5 | portfolios CRUD |
| AddTransaction.tsx | 4 | 股票搜索、添加交易 |
| Settings.tsx | 4 | AI settings, password, delete account |
| StockDetail.tsx | 4 | watchlist, stock refresh |
| Quant.tsx | 5 | portfolio-style, backtest/status, strategies CRUD |
| ChatPanel.tsx | 3 | AI chat, clear, strategies |
| Watchlist.tsx | 4 | watchlist CRUD, refresh |
| Dividends.tsx | 2 | dividends, delete |
| Transactions.tsx | 2 | transactions, delete |
| Market.tsx | 3 | indices, news, world.json |
| ClosedPositions.tsx | 1 | closed-positions |
| use-settings.tsx | 1 | exchange-rates |
| PnlCalendar.tsx | 1 | pnl-calendar |
| Dashboard.tsx | 2 | portfolio refresh (2处) |
| **合计** | **57** | api.ts 已有对应函数但未被使用的居多 |

**注意**: api.ts 已导出 `adminGetStatus()`, `adminGetUsers()`, `adminCrawlStop()`, `searchStocks()`, `getExchangeRates()` 等函数, 但页面仍使用原始 fetch。问题核心是: api.ts 有了函数, 但页面没有迁移去用它们。

---

### 4. 空 catch 块

| 项目 | 状态 |
|------|------|
| **当前状态** | **NOT FIXED** |

**统计:**
- `.catch(() => {})` 模式: **22 处**
  - Layout.tsx (1), use-auth.tsx (1), use-settings.tsx (1), AddTransaction.tsx (1), Admin.tsx (3), ChatPanel.tsx (1), Dashboard.tsx (1), Holdings.tsx (3), Market.tsx (1), Quant.tsx (7), Settings.tsx (1), StockDetail.tsx (1)
- `.catch(() => ({}))` 模式: **2 处** (AddTransaction.tsx, 用于 res.json 回退, 可接受)
- `catch {}` 空 try-catch: **4 处** (Admin.tsx 2处, Quant.tsx 2处)
- **总计空 catch**: **28 处** (与原始 28 处完全一致)

---

### 5. 显式 `any` 类型

| 项目 | 状态 |
|------|------|
| **当前状态** | **NOT FIXED** |

**统计:**
- 页面文件中 `:any` / `as any` / `<any>`: 约 43 处
  - Quant.tsx: 22 处 (最严重)
  - StockDetail.tsx: 6 处
  - api.ts: 23 处 (大量 `Promise<any>` 返回类型)
  - Market.tsx: 4 处
  - Holdings.tsx: 2 处
  - 其他: 若干
- 原始 24 处 `:any` 注解 — 现在不减反增

---

### 6. tsconfig.app.json strict 模式

| 项目 | 状态 |
|------|------|
| **当前状态** | **NOT FIXED** |

**当前配置:**
```jsonc
{
  "noUnusedLocals": true,       // ✅ 已启用
  "noUnusedParameters": true,   // ✅ 已启用
  "noFallthroughCasesInSwitch": true,  // ✅ 已启用
  "strict": false,              // ❌ 未启用
  "noImplicitAny": false,       // ❌ 未启用 (未显式设置)
  "strictNullChecks": false     // ❌ 未启用 (未显式设置)
}
```

启用了部分 lint 规则, 但核心 strict 系列全部未开启。

---

### 7. Dashboard.tsx 重复代码

| 项目 | 状态 |
|------|------|
| **当前状态** | **PARTIALLY FIXED** |

**分析:**
- 文件 535 行, 包含桌面表格 (`hidden lg:block`) 和移动卡片 (`lg:hidden`) 两套持仓展示
- 桌面表格: ~第 417-472 行 (~55 行)
- 移动卡片: ~第 474-513 行 (~40 行)
- 两者共享排序逻辑和价格格式化, 但渲染完全不同
- **原始 ~96 行重复** → 当前约 **95 行**双端渲染, 基本未变
- 改善: 抽取了 `ClosedPositions` 为独立组件, `CloudChart` 为独立组件

---

### 8. Prettier / ESLint / Husky 配置

| 项目 | 状态 |
|------|------|
| **当前状态** | **PARTIALLY FIXED** |

| 工具 | 状态 | 详情 |
|------|------|------|
| ESLint | ✅ 已有 | package.json 有 `eslint`, `typescript-eslint`, `react-hooks`, `react-refresh` 插件 |
| Prettier | ❌ 未配置 | 无 `.prettierrc`, package.json 无 prettier 依赖 |
| Husky | ❌ 未配置 | 无 `.husky/` 目录 |
| lint-staged | ❌ 未配置 | 无 `.lintstagedrc` |

有 lint 脚本 (`npm run lint`), 但无自动格式化、无 pre-commit hook。

---

## Phase 6 — 测试

### 9. 后端测试目录

| 项目 | 状态 |
|------|------|
| **当前状态** | **PARTIALLY FIXED** |

**已改善:**
- `src/test/` 目录已创建
- 测试文件: `AuthServiceTest.java` — 纯单元测试, 5 个测试用例 (registerSuccess, registerBlankUsername, registerShortPassword, registerDuplicate, registerWithEmail)
- `application-test.properties` 配置 H2 内存库 + Flyway 禁用
- pom.xml 包含 `spring-boot-starter-test` + `h2` (test scope)

**不足:**
- 只有 1 个测试类, 只覆盖 AuthService
- 使用反射注入 (`inject()`) 而非 Spring 注入, 测试风格为手工组装
- Controller 层、DAO 层、其他 Service 层均无测试

---

### 10. 前端测试设置

| 项目 | 状态 |
|------|------|
| **当前状态** | **NOT FIXED** |

- `vitest.config.ts`: **不存在**
- `*.test.ts` / `*.test.tsx`: **不存在**
- package.json 无 `vitest`, `@testing-library/react`, `jsdom` 等依赖
- 前端零测试覆盖

---

## Phase 7 — 数据库迁移与工具

### 11. Flyway

| 项目 | 状态 |
|------|------|
| **当前状态** | **PARTIALLY FIXED** |

**已改善:**
- pom.xml 包含 `flyway-core` + `flyway-mysql` 依赖 ✅
- `application-test.properties` 中 `spring.flyway.enabled=false` ✅

**未完成:**
- `db/migration/` 目录 **不存在**
- 无任何 SQL 迁移文件 (V0__init.sql 等)
- Flyway 依赖已就位但未实际使用

---

### 12. pom.xml 质量插件

| 项目 | 状态 |
|------|------|
| **当前状态** | **NOT FIXED** |

- **checkstyle**: ❌ 不存在
- **spotbugs**: ❌ 不存在
- **pmd**: ❌ 不存在
- **jacoco (覆盖率)**: ❌ 不存在
- 唯一插件: `spring-boot-maven-plugin` + `maven-antrun-plugin` (前端构建)

---

### 13. pom.xml 硬编码代理

| 项目 | 状态 |
|------|------|
| **当前状态** | **NOT FIXED** |

**当前配置 (pom.xml 第 75 行):**
```xml
<jvmArguments>-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=7897</jvmArguments>
```

仍然硬编码。应改为环境变量引用, 如:
```xml
<jvmArguments>-DsocksProxyHost=${proxy.host} -DsocksProxyPort=${proxy.port}</jvmArguments>
```

---

## Phase 8 — Python 问题

### 14. 共享 DB 模块

| 项目 | 状态 |
|------|------|
| **当前状态** | **PARTIALLY FIXED** |

**已改善:**
- `script/db.py` 已创建, 提供 `get_conn(cfg)` 和 `load_config()` 函数
- 支持环境变量优先、config.ini 回退、默认值兜底

**未完成:**
- **没有任何脚本 import db.py** — 所有脚本仍自己定义 `get_conn()` / `get_db_conn()`
  - `ai_agent.py`: 自定义 `get_db_conn()` (第 76-92 行)
  - `analyze_quant.py`: 自定义 `get_conn()` (第 80 行)
  - `backtest_engine.py`: 自定义 `get_conn()` (第 59 行)
  - `fetch_fundamentals.py`: 自定义 `get_conn()` (第 56 行)
  - `fetch_stocks.py`: 自定义 `get_conn()` (第 99 行)
  - `optimizer.py`: 自定义 `get_conn()` (第 44 行)
  - `portfolio_style_analyzer.py`: 自定义 `get_conn()` (第 46 行)
- **7 个脚本各自重复实现** `get_conn()`, 未使用共享模块

---

### 15. Python 空 catch

| 项目 | 状态 |
|------|------|
| **当前状态** | **NOT FIXED** |

**裸 `except:` 统计 (共 12 处):**

| 文件 | 行号 | 代码 |
|------|------|------|
| ai_agent.py | 18 | `except: pass` (加载知识库) |
| ai_agent.py | 83 | `except: return d` (config 读取) |
| ai_agent.py | 302 | `except: metrics = {}` |
| ai_agent.py | 304 | `except: trades = []` |
| ai_agent.py | 306 | `except: curve = []` |
| ai_agent.py | 324 | `except: pass` (日期解析) |
| ai_agent.py | 395 | `except: total_ret = None; sharpe = None` |
| ai_agent.py | 410 | `except: return {"error": ...}` |
| ai_agent.py | 756 | `except: return ""` (proxy 读取) |
| ai_agent.py | 813 | `except: args = {}` (JSON 解析) |
| optimizer.py | 35 | `except: return default` |
| portfolio_style_analyzer.py | 36 | `except: return default` |

所有裸 `except:` 均无异常类型指定, 无日志记录。ai_agent.py 占 10 处。

---

### 16. requirements.txt

| 项目 | 状态 |
|------|------|
| **当前状态** | **FIXED** |

**当前内容:**
```
pymysql>=1.1,<2
numpy>=1.26,<2
baostock>=0.9,<1
yfinance>=0.2,<1
openai>=1.0,<2
anthropic>=0.40,<1
httpx>=0.25,<1
duckduckgo-search>=4.0,<6
python-dateutil>=2.8,<3
```

- ✅ 文件存在于 `script/requirements.txt`
- ✅ 所有包均有版本范围约束 (使用 `>=min,<max` 格式)
- ✅ 覆盖主要依赖

---

## 总结

| # | 问题 | 状态 | 关键数据 |
|---|------|------|----------|
| 1 | Quant.tsx God Component | **NOT FIXED** | 733行, 40个useState, 未拆分目录 |
| 2 | Admin.tsx God Component | **PARTIALLY FIXED** | 全局变量改为hook间接访问但仍在, 未拆分目录, SSE代码重复 |
| 3 | 直接 fetch 绕过 api.ts | **PARTIALLY FIXED** | api.ts 有45+函数, 但页面仍有57处直接fetch |
| 4 | 空 catch 块 | **NOT FIXED** | 28处, 与原数一致 |
| 5 | 显式 any 类型 | **NOT FIXED** | ~43处 (页面) + 23处 (api.ts), 比原24处更多 |
| 6 | strict 模式 | **NOT FIXED** | strict/noImplicitAny/strictNullChecks 均未启用 |
| 7 | Dashboard 重复代码 | **PARTIALLY FIXED** | 桌面/移动双端渲染~95行, ClosedPositions已抽出 |
| 8 | Prettier/Husky/lint-staged | **PARTIALLY FIXED** | 有ESLint, 无Prettier/Husky/lint-staged |
| 9 | 后端测试 | **PARTIALLY FIXED** | 1个测试类5个用例, 只覆盖AuthService |
| 10 | 前端测试 | **NOT FIXED** | 零测试文件, 无vitest配置 |
| 11 | Flyway | **PARTIALLY FIXED** | 依赖已就位, 无迁移SQL文件 |
| 12 | 质量插件 | **NOT FIXED** | 无checkstyle/spotbugs/pmd/jacoco |
| 13 | 硬编码代理 | **NOT FIXED** | 仍硬编码 127.0.0.1:7897 |
| 14 | 共享 DB 模块 | **PARTIALLY FIXED** | db.py 已创建但无脚本使用, 7个脚本仍各自重复实现 |
| 15 | Python 空 catch | **NOT FIXED** | 12处裸 except:, 无日志 |
| 16 | requirements.txt | **FIXED** | 存在且有版本范围约束 |

### 汇总统计

| 状态 | 数量 |
|------|------|
| FIXED | 1 (6.25%) |
| PARTIALLY FIXED | 7 (43.75%) |
| NOT FIXED | 8 (50%) |

### 优先修复建议

1. **高优先级**: #3 (迁移57处fetch到api.ts) — 纯机械重构, 风险低收益大
2. **高优先级**: #13 (代理硬编码) — 安全风险, 一行改动
3. **高优先级**: #14 (7个脚本迁移到db.py) — 减少代码重复, db.py已就位
4. **中优先级**: #15 (Python裸except) — 12处, 可批量修复
5. **中优先级**: #1 (Quant.tsx拆分) — 拆为3个文件即可
6. **中优先级**: #4 (空catch块) — 28处, 添加console.error
7. **低优先级**: #6, #5 (strict + any) — 渐进式启用
8. **低优先级**: #8, #12 (工具链) — 基础设施改善
