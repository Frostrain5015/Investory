package com.investory.service;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthService 纯单元测试（不启动 Spring 容器）。
 *
 * 跑法：mvn -f backend/pom.xml test
 *
 * 流程：
 *   1. Maven 找到 src/test/java 下所有 *Test.java
 *   2. 每个 @Test 方法独立运行
 *   3. @BeforeEach 在每个测试前重建干净的 H2 内存库
 *   4. 断言失败 = 测试红色 = 有 bug
 */
class AuthServiceTest {

    private AuthService authService;
    private UserDao userDao;

    @BeforeEach
    void setUp() {
        // 创建 H2 内存数据源（不碰真实 MySQL）
        DataSource ds = new DriverManagerDataSource(
            "jdbc:h2:mem:test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 建表（IF NOT EXISTS 避免重复建表；timestamp 不设默认值，避免 H2 误识别为自增列）
        jdbc.execute("CREATE TABLE IF NOT EXISTS users ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "username VARCHAR(64) NOT NULL, "
            + "password_hash VARCHAR(256) NOT NULL, "
            + "email VARCHAR(128), "
            + "is_admin INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS portfolios ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "user_id BIGINT NOT NULL, "
            + "name VARCHAR(128))");
        jdbc.execute("DELETE FROM portfolios");
        jdbc.execute("DELETE FROM users");

        // 手工组装依赖链（反射注入，不依赖 Spring 容器）
        userDao = new UserDao();
        inject(userDao, "jdbc", jdbc);
        PortfolioDao portfolioDao = new PortfolioDao();
        inject(portfolioDao, "jdbc", jdbc);
        authService = new AuthService();
        inject(authService, "userDao", userDao);
        inject(authService, "portfolioDao", portfolioDao);
    }

    @Test
    void registerSuccess() {
        assertNull(authService.register("alice", "secret123", null)); // returns null = success
        assertTrue(userDao.usernameExists("alice"));
    }

    @Test
    void registerBlankUsername() {
        assertEquals("用户名不能为空", authService.register("", "pw123456", null));
        assertEquals("用户名不能为空", authService.register("   ", "pw123456", null));
    }

    @Test
    void registerShortPassword() {
        assertEquals("密码至少6位", authService.register("bob", "12345", null));
    }

    @Test
    void registerDuplicate() {
        authService.register("carol", "password1", null);
        assertEquals("用户名已被使用", authService.register("carol", "password2", null));
    }

    @Test
    void registerWithEmail() {
        assertNull(authService.register("dave", "pw654321", "dave@test.com"));
        assertTrue(userDao.usernameExists("dave"));
    }

    // ── helper ──

    private static void inject(Object target, String fieldName, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
