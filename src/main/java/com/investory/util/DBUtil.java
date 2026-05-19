package com.investory.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class DBUtil {

    private static HikariDataSource ds;

    public static void init() {
        HikariConfig config = new HikariConfig("/hikari.properties");
        ds = new HikariDataSource(config);
    }

    public static void shutdown() {
        if (ds != null && !ds.isClosed()) ds.close();
    }

    public static Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

    /** Silently close any AutoCloseable resources (Connection, Statement, ResultSet). */
    public static void close(AutoCloseable... resources) {
        for (AutoCloseable r : resources) {
            if (r != null) {
                try { r.close(); } catch (Exception ignored) {}
            }
        }
    }
}
