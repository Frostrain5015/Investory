package com.investory.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

public class DatabaseManager {
    private static final Logger log = Logger.getLogger(DatabaseManager.class.getName());
    private static HikariDataSource dataSource;

    public static synchronized void init() {
        if (dataSource != null) return;
        String url = ConfigLoader.get("spring.datasource.url",
            "jdbc:mysql://localhost:3306/investory?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true");
        String username = ConfigLoader.get("spring.datasource.username", "root");
        String password = ConfigLoader.get("spring.datasource.password", "");

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(username);
        hc.setPassword(password);
        hc.setMaximumPoolSize(ConfigLoader.getInt("spring.datasource.hikari.maximum-pool-size", 10));
        hc.setMinimumIdle(ConfigLoader.getInt("spring.datasource.hikari.minimum-idle", 2));
        hc.setConnectionTimeout(ConfigLoader.getInt("spring.datasource.hikari.connection-timeout", 30000));
        hc.setConnectionTestQuery("SELECT 1");
        hc.setPoolName("InvestoryPool");
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        dataSource = new HikariDataSource(hc);
        log.info("DB pool ready");
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) throw new SQLException("DatabaseManager not initialized");
        return dataSource.getConnection();
    }

    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }
}
