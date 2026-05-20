# Investory (盈亏鉴)

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
& "C:\tmp\maven\apache-maven-3.9.16\bin\mvn.cmd" -f "d:\Java Projects\investory\backend\pom.xml" spring-boot:run -DskipTests
```

### Bash equivalent

```bash
JAVA_HOME="E:/Java/jdk-17" "C:/tmp/maven/apache-maven-3.9.16/bin/mvn" -f "d:/Java Projects/investory/backend/pom.xml" spring-boot:run -DskipTests
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
- **App URL**: http://localhost:8080/investory/

## Key conventions

- No web.xml — Spring Boot auto-configuration
- DAOs extend `BaseDao` which provides `JdbcTemplate` via `@Autowired`
- Frontend uses `useSettings()` for currency formatting, color scheme, and unit conversion
- TypeScript: no `any`, always define proper interfaces
