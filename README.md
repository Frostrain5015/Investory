# 盈亏鉴 Investory

个人股票投资组合管理工具。支持多市场持仓追踪、成本计算、盈亏日历、K 线走势等功能。

## 技术栈

| 层 | 技术 |
|---|------|
| 后端 | Java 17, Spring Boot 3.3.5, Spring MVC, JdbcTemplate |
| 前端 | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Recharts |
| 数据库 | MySQL 8+ |
| 数据源 | BaoStock, Yahoo Finance (yfinance), 腾讯财经, 东方财富, 新浪财经 |

## 项目结构

```
investory/
├── backend/                     # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/investory/
│       ├── InvestoryApplication.java
│       ├── controller/          # REST API 控制器
│       │   ├── SpaController.java
│       │   └── api/             # 数据 API
│       ├── dao/                 # 数据访问层
│       ├── service/             # 业务逻辑
│       ├── crawler/             # 数据抓取与调度
│       ├── model/               # 数据模型
│       └── web/                 # 拦截器与配置
├── frontend/                    # React 前端
│   ├── src/
│   │   ├── components/          # UI 组件
│   │   ├── pages/               # 页面
│   │   ├── hooks/               # React Hooks
│   │   ├── services/            # API 调用
│   │   └── lib/                 # 工具函数
│   └── package.json
└── script/                      # Python 数据抓取脚本
    ├── fetch_a_stock.py         # A股（BaoStock）
    ├── fetch_hk_stock.py        # 港股（腾讯财经）
    └── fetch_us_stock_yf.py     # 美股（Yahoo Finance）
```

## 功能

- **总览**：总资产曲线、持仓占比（扇形/云图）、盈亏排行
- **持仓明细**：实时市价、平均/摊薄成本、浮动盈亏
- **交易记录**：买卖与分红统一时间线，支持增删改
- **个股详情**：K 线走势、成本线、BS 点标记、国旗标识
- **盈亏日历**：年度月度视图，金额/涨跌幅切换
- **组合管理**：多组合创建与切换
- **设置**：红涨绿跌/绿涨红跌、本位币（CNY/HKD/USD）、账户管理
- **实时报价**：东财/新浪/Yahoo 三源并发赛马，0.5 秒响应
- **全市场搜索**：A 股 + 港股 + 美股，5200+ 股票

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- MySQL 8+
- Python 3.10+（数据抓取脚本）

### 数据库

创建数据库并导入表结构：

```sql
CREATE DATABASE investory CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

表结构参考 `backend/src/main/resources/db/schema.sql`。

### 后端配置

编辑 `backend/src/main/resources/application.properties`：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/investory?...
spring.datasource.username=root
spring.datasource.password=your_password
```

### 启动

```bash
# 前端构建 + 后端打包
cd backend && mvn package -DskipTests

# 启动
java -jar backend/target/investory.jar
```

访问 `http://localhost:8080/investory`

### 数据抓取

历史数据导入后，每日收盘价由 Python 脚本自动更新（Java 调度器触发）：

| 脚本 | 时间 | 数据源 |
|------|------|--------|
| `fetch_a_stock.py` | 15:30 CST | BaoStock |
| `fetch_hk_stock.py` | 16:30 CST | 腾讯财经 |
| `fetch_us_stock_yf.py` | 05:00 CST | Yahoo Finance |

## License

MIT
