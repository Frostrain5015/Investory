面向对象的Web应用开发技术（Java）
期末项目设计报告

题    目：Investory——多市场投资组合管理与量化分析系统

姓    名：__________________
班    级：__________________
学    号：__________________

年          月


目  录

第一章 项目需求 ................................................. - 2 -
    1.1、概要说明 .............................................. - 2 -
    1.2、功能描述 .............................................. - 2 -
第二章 项目设计 ................................................. - 3 -
    2.1、系统结构图 ............................................ - 3 -
    2.2、核心流程图 ............................................ - 3 -
    2.3、ER图及数据字典 ........................................ - 3 -
第三章 项目开发过程 ............................................. - 4 -
    3.1、模块结构说明 .......................................... - 4 -
    3.2、模块开发配置 .......................................... - 4 -
第四章 功能实现 ................................................. - 5 -
    1、投资组合持仓管理的实现 .................................. - 5 -
    2、实时行情数据抓取的实现 .................................. - 5 -
    3、AI 投资助手的实现 ....................................... - 5 -
    4、量化回测引擎的实现 ...................................... - 5 -
第五章 团队协作与分工 ........................................... - 6 -
    1、团队分工 ................................................ - 6 -
    2、团队协作 ................................................ - 6 -
第六章 总结与展望 ............................................... - 7 -
    1、总结 .................................................... - 7 -
    2、展望 .................................................... - 7 -


第一章 项目需求

1.1、概要说明

Investory（盈亏鉴）是一个面向个人投资者的多市场投资组合管理与量化分析系统。随着中国资本市场对外开放和居民理财需求的增长，越来越多的投资者同时持有A股、港股、美股等多个市场的股票。然而，现有的大多数投资管理工具仅支持单一市场，且缺乏量化分析和回测能力。

本项目旨在构建一个全栈投资管理平台，支持用户对沪深A股、港股、美股等多个市场的投资组合进行统一管理。系统采用 Java Servlet + JDBC 的后端架构（符合工程课程要求），配合 React 前端和 Python 量化引擎，提供投资组合跟踪、实时行情监控、多币种盈亏计算、量化指标分析、策略回测以及AI投资助手等一站式功能。

项目选题目的：通过开发一个真实的金融投资管理平台，将 Java Web 开发技术（Servlet、JDBC、会话管理、MVC模式等）综合应用于实际场景，同时融合前端工程化和Python数据分析能力，构建完整的全栈应用。

1.2、功能描述

本系统包含以下主要功能模块：

（1）用户认证与账户管理：用户注册、登录、密码修改、账户删除，支持会话保持和安全管理。

（2）投资组合管理：支持创建多个独立投资组合（portfolio），每个组合可添加不同市场的股票持仓，支持持仓 CRUD 操作。

（3）交易记录管理：记录买卖（BUY/SELL）、资金转入转出（TRANSFER_IN/OUT）等交易，支持增删改查。

（4）持仓盈亏计算：基于加权平均成本法计算持仓成本，支持多币种（CNY/HKD/USD）自动汇率换算，实时计算未实现盈亏、已实现盈亏、累计收益等。

（5）分红记录管理：记录股票分红信息，自动调整持仓摊薄成本。

（6）实时行情数据抓取：通过定时任务从新浪财经、腾讯财经、Yahoo Finance 等多个数据源实时获取A股、港股、美股的行情数据。

（7）量化指标分析：计算持仓股票的贝塔值、波动率、历史分位数、最大回撤等风险指标。

（8）策略回测引擎：支持SMA、EMA、RSI、MACD、布林带等技术指标的策略回测，包含完整的绩效评估指标（夏普比率、最大回撤、胜率等）。

（9）AI 投资助手：集成大语言模型，支持自然语言对话分析投资组合、生成回测策略、提供投资建议。

（10）管理员面板：数据库状态监控、数据抓取控制、用户管理、爬虫历史审计。


第二章 项目设计

2.1、系统结构图

系统采用前后端分离的 B/S 架构，整体分为四层：

┌──────────────────────────────────────┐
│           前端 (React + Vite)          │
│   Dashboard · Holdings · Transactions │
│   Market · Quant · ChatPanel · Admin  │
└──────────────┬───────────────────────┘
               │ HTTP REST + SSE
┌──────────────▼───────────────────────┐
│         Java 后端 (Servlet + JDBC)     │
│  ┌─────────────────────────────────┐  │
│  │  ServletRouter（统一路由分发）    │  │
│  │  ┌──────┐ ┌──────┐ ┌────────┐  │  │
│  │  │Controller│ │Service│ │ DAO   │  │  │
│  │  └──────┘ └──────┘ └────────┘  │  │
│  │  ┌──────────────────────────┐    │  │
│  │  │ Crawler / Scheduler / SSE │   │  │
│  │  └──────────────────────────┘    │  │
│  │  ┌──────────────────────────┐    │  │
│  │  │ DatabaseManager (HikariCP)│   │  │
│  │  └──────────────────────────┘    │  │
│  └─────────────────────────────────┘  │
└──────────────┬───────────────────────┘
               │ JDBC
┌──────────────▼───────────────────────┐
│           MySQL 数据库                │
│  users · portfolios · holdings ·      │
│  transactions · stocks · stock_prices │
│  dividends · watchlist · etc.         │
└──────────────────────────────────────┘
               │ 进程调用
┌──────────────▼───────────────────────┐
│        Python 量化引擎                │
│  fetch_stocks · ai_agent ·           │
│  backtest_engine · analyze_quant     │
└──────────────────────────────────────┘

2.2、核心流程图

（1）用户登录流程：
用户请求 → LoginController → AuthService.login() → UserDao.findByUsername()
→ BCrypt密码验证 → 设置Session属性(userId, username, isAdmin) → 返回首页

（2）持仓盈亏计算流程：
用户请求 /api/dashboard → PortfolioController.dashboard()
→ HoldingService.getSnapshots() → 从数据库加载持仓
→ RealtimeQuoteService 并发获取实时行情(新浪/腾讯/Yahoo三源竞速)
→ 加载汇率表进行多币种CNY换算
→ 计算每股未实现盈亏、今日涨跌幅
→ 聚合计算总投资额、总市值、总盈亏、累计收益率
→ 返回前端Dashboard渲染

（3）数据爬取流程：
CrawlerScheduler (@Scheduled) → 启动 fetch_stocks.py 脚本
→ 读取数据写入 stock_prices 表 → 更新 crawl_history 记录
→ 通过 SSE 实时推送进度到 Admin 面板

2.3、ER图及数据字典

系统核心数据库表结构：

（1）users（用户表）
- id: BIGINT PK 自增
- username: VARCHAR(50) 用户名（唯一）
- password_hash: VARCHAR(60) BCrypt哈希密码
- email: VARCHAR(100) 邮箱
- is_admin: BOOLEAN 是否管理员
- created_at: TIMESTAMP 创建时间

（2）portfolios（投资组合表）
- id: BIGINT PK 自增
- user_id: BIGINT FK → users.id
- name: VARCHAR(100) 组合名称
- created_at: TIMESTAMP

（3）holdings（持仓表）
- id: BIGINT PK
- portfolio_id: BIGINT FK
- stock_id: BIGINT FK → stocks.id
- total_shares: DECIMAL 总持股数
- avg_cost: DECIMAL 加权平均成本
- diluted_cost: DECIMAL 摊薄成本
- total_invested: DECIMAL 总投资额
- total_dividends: DECIMAL 累计分红

（4）transactions（交易记录表）
- id: BIGINT PK
- portfolio_id: BIGINT FK
- stock_id: BIGINT FK
- type: ENUM(BUY/SELL/TRANSFER_IN/TRANSFER_OUT)
- shares: DECIMAL 股数
- price: DECIMAL 成交价
- fee: DECIMAL 手续费
- trade_date: DATE 交易日期
- note: TEXT 备注

（5）stocks（股票信息表）
- id: BIGINT PK
- symbol: VARCHAR(20) 代码（如 1.600519）
- name: VARCHAR(100) 名称
- market: VARCHAR(10) SH/SZ/HK/US
- currency: VARCHAR(3) CNY/HKD/USD
- name_pinyin: VARCHAR(100) 拼音首字母搜索

（6）stock_prices（股票价格表）
- id: BIGINT PK
- stock_id: BIGINT FK
- trade_date: DATE
- open/close/high/low/volume: DECIMAL


第三章 项目开发过程

3.1、模块结构说明

项目后端采用标准的 Java Servlet + JDBC 架构，遵循 MVC 设计模式。

backend/                                  # Java 后端
├── pom.xml                               # Maven 构建配置
└── src/main/java/com/investory/
    ├── InvestoryApplication.java         # 主入口：内嵌Tomcat启动
    ├── server/                           # 基础设施层
    │   ├── ServletRouter.java            # 核心路由分发器
    │   ├── DatabaseManager.java          # HikariCP连接池管理
    │   ├── ConfigLoader.java             # 配置文件加载
    │   ├── AppContext.java               # 简易依赖注入容器
    │   ├── CorsFilter.java               # CORS跨域滤器
    │   ├── AuthFilter.java               # 会话认证过滤器
    │   ├── ErrorFilter.java              # 全局异常过滤器
    │   ├── SessionHelper.java            # 会话工具类
    │   ├── SseClient.java                # SSE推送客户端
    │   ├── SchedulerService.java         # 定时任务调度器
    │   └── RouteRegistrar.java           # 路由注册中心
    ├── controller/                       # 控制器层
    │   ├── SpaController.java            # SPA页面路由
    │   ├── OAuthController.java          # OAuth登录
    │   ├── McpController.java            # MCP协议接口
    │   └── api/                          # REST API控制器
    │       ├── SessionController.java    # 会话管理
    │       ├── StockController.java      # 持仓与股票
    │       ├── PortfolioController.java  # 投资组合
    │       ├── TransactionController.java# 交易记录
    │       ├── DividendController.java   # 分红管理
    │       ├── ChartDataController.java  # 图表数据
    │       ├── MarketIndexController.java# 市场指数
    │       ├── AiApiController.java      # AI对话
    │       ├── AdminController.java      # 管理员面板
    │       ├── BacktestApiController.java# 回测引擎
    │       └── QuantApiController.java   # 量化分析
    ├── service/                          # 业务逻辑层
    │   ├── AuthService.java              # 认证服务
    │   ├── HoldingService.java           # 持仓服务
    │   ├── PortfolioAnalysisService.java # 组合分析
    │   └── PortfolioValueCalculator.java # 日净值计算
    ├── dao/                              # 数据访问层
    │   ├── BaseDao.java                  # DAO基类
    │   ├── UserDao.java                  # 用户DAO
    │   ├── StockDao.java                 # 股票DAO
    │   ├── HoldingDao.java               # 持仓DAO
    │   └── ...                           # 其他DAO
    ├── model/                            # 数据模型(POJO)
    └── crawler/                          # 数据爬虫层
        ├── CrawlerScheduler.java         # 定时抓取调度
        ├── EastMoneyCrawler.java         # 东方财富爬虫
        └── RealtimeQuoteService.java     # 实时行情服务

3.2、模块开发配置

（1）数据库连接配置（application.properties）：
- 数据库：MySQL 8，JDBC URL 支持环境变量覆盖
- 连接池：HikariCP，最大10连接，最小2空闲
- SSL：自签名证书 keystore.p12，端口 8443

（2）定时任务配置：
- A股收盘抓取：工作日 15:30
- 港股收盘抓取：工作日 16:30
- 美股收盘抓取：周二至周六 09:00
- 汇率刷新：每日 09:30
- 量化指标计算：每日 02:00

（3）用户请求处理流程：
所有请求 → CorsFilter（跨域） → AuthFilter（认证检查，排除/login、/register、/api/session等公开路径） → ServletRouter（方法+路径匹配路由） → 对应Controller方法处理 → 写入JSON响应

（4）SSE实时通信配置：
AiApiController、AdminController、BacktestApiController使用SSE（Server-Sent Events）实现实时数据推送。采用自定义SseClient类替代Spring的SseEmitter，通过HttpServletResponse直接写入event-stream格式的数据。


第四章 功能实现

1、投资组合持仓管理的实现

核心实现在 PortfolioController 和 HoldingService 中。当用户请求 /api/dashboard 时，系统按以下步骤处理：

（1）从Session获取当前portfolioId
（2）HoldingService.getSnapshots() 加载持仓列表
（3）对每个持仓股票，通过 RealtimeQuoteService 并发获取三个数据源的实时报价
（4）使用汇率表将外币持仓转换为CNY，计算市场价值
（5）通过加权平均法计算未实现盈亏 = (当前价 - 均价) × 持股数
（6）聚合计算总市值、总投资额、总盈亏、累计收益率
（7）计算今日盈亏 = Σ(每股涨跌幅 × 持股数)

关键技术：使用 Java 并发（ExecutorService）同时请求三个行情源，3秒超时竞速，确保行情获取的可靠性和速度。

2、实时行情数据抓取的实现

CrawlerScheduler 管理所有定时任务，使用 ScheduledExecutorService 替代 Spring 的 @Scheduled 注解。

数据抓取流程：
（1）执行 fetch_stocks.py Python 脚本，通过进程间通信获取数据
（2）实时解析Python脚本输出的进度日志，通过SseClient推送到Admin面板
（3）将行情数据写入 stock_prices 表的合适位置
（4）更新 crawl_history 表记录运行状态

实时行情服务 RealtimeQuoteService 采用"三源竞速"模式：同时请求新浪财经、腾讯财经、Yahoo Finance三个来源，取最先返回的有效数据，大幅提高行情获取的成功率和响应速度。

3、AI 投资助手的实现

AiApiController 接收用户消息，调用 ai_agent.py Python脚本与大语言模型API交互。

工作流程：
（1）用户发送消息到 POST /api/ai/chat
（2）后端将消息序列化为JSON临时文件
（3）启动 ai_agent.py 进程，传递provider/model/api_key等参数
（4）Python进程流式输出结果（每行为一个事件：[TOKEN]、[STRATEGY]、[ASK]、[CONFIRM]等）
（5）Java后端通过SseClient逐行解析并推送到前端
（6）前端ChatPanel组件实时渲染AI回复

支持的AI供应商：阿里云百炼、OpenAI、DeepSeek、Moonshot、智谱GLM、Anthropic Claude等。

4、量化回测引擎的实现

BacktestApiController 提供策略回测的完整REST API。用户可在前端搭建策略规则（SMA/EMA/RSI等），提交到后端执行回测。

回测流程：
（1）用户构建策略并提交到 POST /api/backtest/start
（2）策略保存到 backtest_strategies 表
（3）启动 Python backtest_engine.py 进程
（4）实时通过SSE推送回测进度
（5）回测完成后，结果（权益曲线、绩效指标、交易日志）写入 backtest_results 表
（6）用户可查看历史回测记录和详细绩效指标


第五章 团队协作与分工

1、团队分工

（此处填写小组成员信息及分工）

2、团队协作

（此处填写团队协作的描述）


第六章 总结与展望

1、总结

通过本次项目实践，我们完整地经历了一个全栈Web应用从需求分析、系统设计、编码实现到测试部署的全过程。

在技术层面，我们深入实践了 Java Servlet + JDBC 架构的 Web 开发模式，理解了 Servlet 容器的工作原理、请求路由分发、会话管理、过滤器链等核心技术。通过从 Spring Boot 到 Servlet + JDBC 的架构迁移，加深了对底层 Web 技术的理解。

在工程层面，我们实践了前后端分离开发模式、RESTful API 设计规范、SSE 实时通信、多数据源并发采集等技术方案。项目集成了 Python 量化引擎和 AI 大语言模型，体现了跨语言、跨平台系统集成的能力。

（此处每位成员分别补充个人总结）

2、展望

当前系统已完成核心功能，但在以下方面仍有改进空间：

（1）实时行情的延迟较高，后续可接入 WebSocket 实现更低延迟的实时推送。
（2）量化分析功能可以进一步丰富，加入更多因子模型和风险模型。
（3）移动端适配目前仅依赖 Web 响应式，后续可开发原生移动应用。
（4）可以增加投资组合的分享和社交功能，支持用户之间交流投资策略。
（5）AI 助手的知识库可以进一步扩展，支持更专业的金融数据分析。



PAGE   \* MERGEFORMAT - 1 -
PAGE  - 6 -
