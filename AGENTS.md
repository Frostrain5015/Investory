# Investory

Spring Boot 3.3.5 + React + TypeScript + Vite portfolio tracker.

## Project structure

```
investory/
├── backend/                          # Spring Boot (Maven)
│   ├── pom.xml                       # parent: spring-boot-starter-parent 3.3.5, Java 17
│   ├── src/main/java/com/investory/
│   │   ├── InvestoryApplication.java # main class, @SpringBootApplication
│   │   ├── controller/               # page controllers + api/ REST controllers
│   │   ├── dao/                      # @Repository + JdbcTemplate (via BaseDao)
│   │   ├── service/                  # @Service
│   │   ├── model/                    # POJOs
│   │   ├── crawler/                  # @Component, @Scheduled
│   │   └── config/                   # WebConfig (interceptors, static resources)
│   └── src/main/resources/
│       ├── application.properties    # datasource, server.servlet.context-path=/investory
│       ├── templates/                # Thymeleaf HTML
│       └── static/                   # built frontend output (Vite writes here)
└── frontend/
    ├── package.json                  # scripts: dev, build (tsc -b && vite build)
    ├── vite.config.ts                # proxy /investory → localhost:8080, base: /investory/
    └── src/
        ├── pages/                    # Dashboard, Holdings, Transactions, PnlCalendar, StockDetail, Portfolio, Settings
        ├── components/               # Layout, CloudChart, ui/*
        ├── hooks/                    # useAuth, useSettings
        ├── services/api.ts           # API client (prefix /investory)
        └── types/index.ts            # TypeScript interfaces
```

## Build & Run

The Maven build automatically compiles the frontend (via `antrun` plugin running `tsc -b && vite build` in `frontend/`), copies output to `backend/src/main/resources/static/`, then compiles Java.

### Full build + run (Spring Boot)

```powershell
$env:JAVA_HOME = "E:\Java\jdk-17"
& "C:\tmp\maven\apache-maven-3.9.16\bin\mvn.cmd" -f "d:\Java Projects\investory\backend\pom.xml" spring-boot:run -DskipTests "-Dspring-boot.run.jvmArguments=-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=7897"
```

### Bash equivalent

```bash
JAVA_HOME="E:/Java/jdk-17" "C:/tmp/maven/apache-maven-3.9.16/bin/mvn" -f "d:/Java Projects/investory/backend/pom.xml" spring-boot:run -DskipTests "-Dspring-boot.run.jvmArguments=-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=7897"
```

### Quick frontend-only build (for static file changes)

```bash
cd frontend && npx vite build
# Output goes to ../backend/src/main/resources/static/
```

Then restart the Spring Boot process to pick up new static files.

### Vite dev server (frontend hot-reload)

```bash
cd frontend && npm run dev
# Runs on http://localhost:5173/investory/
# Proxies /investory API calls to localhost:8080
```

### Run from JAR (production)

```bash
JAVA_HOME="E:/Java/jdk-17"
java -DsocksProxyHost=127.0.0.1 -DsocksProxyPort=7897 -jar backend/target/investory.jar
```

## Environment

- **Java**: 17 (E:\Java\jdk-17)
- **Maven**: 3.9.16 (C:\tmp\maven\apache-maven-3.9.16)
- **MySQL**: localhost:3306, investory_db, credentials in application.properties
- **App URL**: https://localhost:8443/investory/

## Cloud deployment

- **Server**: Alibaba Cloud, `root@116.62.179.231`, SSH port 22
- **JAR path**: `/opt/investory/investory.jar`
- **Service**: `systemctl restart investory`
- **Logs**: `/opt/investory/app.log`
- **Deploy script**: `python deploy.py` (safe deploy with crawl-running check)
- **SSH**: always use **paramiko** — `sshpass` is unreliable on this machine and must not be used

```python
import paramiko
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("116.62.179.231", username="root", password=..., timeout=15)
```

Credentials are in memory file `server_credentials.md`. Never restart the service if a crawl is running (`ps aux | grep fetch_stocks.py`).

## Key conventions

- 产品版本号只修改 `backend/pom.xml` 中 Investory 的项目版本。前端显示及部署直接读取此处；`node tools/version.mjs` 自动同步前端/桌面端 package 与 lock 文件，开发、构建、安装和发布入口均调用同步。提交前运行 `node tools/version.mjs --check`；不得从提交标题推断版本，也不要手改派生版本号。第三方依赖及历史文档中的版本号不参与同步。
- No web.xml — Spring Boot auto-configuration
- DAOs extend `BaseDao` which provides `JdbcTemplate` via `@Autowired`
- Frontend uses `useSettings()` for currency formatting, color scheme, and unit conversion
- TypeScript: no `any`, always define proper interfaces
- 前端文案应直接说明功能或状态，不得使用“个人量化工具 · Agent接入”这类标签拼接式宣传文案；不得随意用“·”连接标题、副标题或功能描述。优先使用自然短句、逗号或独立标签。
- 字段的补充说明优先放在选项栏或标题旁的 ⓘ 提示中，悬停、键盘聚焦及手机点击均可查看；不要在表单中铺成长段说明。错误、必填和影响操作结果的状态仍须直接可见。
- 行情列表和卡片不逐条铺陈来源、详细时间或“保留报价”小字。昨日收盘价、休市等正常延迟不展示任何符号或说明；注意符号仅用于获取失败后保留的旧报价、报价不可用、成本估值等异常。异常提示中的接口更新时间与成交时间必须区分，时间转为明确标注的北京时间，不直接展示原始ISO/UTC字符串。当前活动页面展示的标的每停留30秒刷新一次报价，隐藏页面和离开页面后停止轮询；后端共享缓存不能使正常轮询退化为更长周期。
- 组件数据首次出现、恢复和刷新沿用轻量淡化及插值过渡，约320ms，不重建整页或地图，不把缺失价格当作0参与动画；遵循系统减少动态效果偏好。
- 历史行情优先复用共享缓存，已收盘的不复权日线长期保留并只补齐缺口；当日未定稿数据短时缓存。复权行情须考虑后续除权除息带来的历史重算，不能与不复权价格混用。缓存命中、层级及过期策略属于内部细节，不在前端逐条展示。
- 页面过渡统一由公共路由层处理，保留中央圆形遮罩展开。页面代码加载使用轻量蓝紫细弧，不使用实心呼吸圆或全页灰色加载环；灰色加载环仅用于局部组件。数据刷新保留已有内容，不重播整页过渡；遵循系统减少动态效果偏好。
- 旧日线价格表不属于应用运行路径。一次性归档/清理仅使用 `script/retire_price_store.py`，先校验回滚备份及5–10年指数归档，再清理国内行情；不得清除证券身份、用户台账或历史任务记录。`data/index-history.sqlite` 是持久归档，不属于可淘汰缓存；代码回滚不能替代行情数据恢复。维护流程见 `docs/operations/price-retirement-2026-10-24.md`。
- StockSage 引擎、桥接、扫描任务及观澜相关工具已退役，不得重新引入其常驻服务或子进程回退。量化指标、组合优化和回测使用独立模块；历史对话/报告及已应用的 Flyway V3 迁移保留。发布脚本中的旧路径/服务名只用于退役和失败回滚，不属于当前运行依赖。
