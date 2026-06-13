package com.investory.service;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import com.investory.server.AppContext;
import com.investory.server.DatabaseManager;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;

/**
 * 认证服务
 *
 * <p>处理用户账户的核心身份认证逻辑，包括：
 * <ul>
 *   <li>用户注册：校验输入参数、BCrypt 加密密码、创建用户及默认投资组合</li>
 *   <li>用户登录：查找用户并使用 BCrypt 校验密码哈希</li>
 *   <li>修改密码：先校验旧密码，再用 BCrypt 重新哈希新密码并持久化</li>
 * </ul>
 *
 * <p>密码存储采用 BCrypt 单向哈希，不可逆，原始密码不落库。
 * 注册与默认投资组合创建在同一事务中执行，任一步骤失败则全部回滚。
 */
public class AuthService {

    private final UserDao userDao;
    private final PortfolioDao portfolioDao;

    public AuthService() {
        this.userDao = AppContext.get(UserDao.class);
        this.portfolioDao = AppContext.get(PortfolioDao.class);
    }

    /**
     * 注册新用户，并自动为其创建一个默认投资组合。
     *
     * <p>整个操作在事务中执行，若插入用户或创建投资组合任一失败，均会完整回滚。
     *
     * <p>校验规则（按顺序）：
     * <ol>
     *   <li>用户名不能为空</li>
     *   <li>密码长度至少 6 位</li>
     *   <li>用户名在数据库中不能已存在</li>
     * </ol>
     *
     * @param username 用户名（前后空白会被 trim）
     * @param password 明文密码（长度 ≥ 6）
     * @param email    邮箱地址，可为 null
     * @return 注册失败时返回中文错误描述字符串；注册成功时返回 {@code null}
     */
    public String register(String username, String password, String email) {
        // 第1步：参数合法性校验
        if (username == null || username.isBlank()) return "用户名不能为空";
        if (password == null || password.length() < 6) return "密码至少6位";
        if (userDao.usernameExists(username)) return "用户名已被使用";

        // 第2步：构建用户对象，使用 BCrypt 对明文密码加盐哈希后存储
        User user = new User();
        user.setUsername(username.trim());
        user.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setEmail(email != null ? email.trim() : null);

        Connection conn = null;
        try {
            conn = DatabaseManager.getConnection();
            conn.setAutoCommit(false);

            // 第3步：将用户写入数据库，获取自增主键
            long userId = userDao.insert(user);

            // 第4步：为新用户创建默认投资组合（名称"我的投资组合"）
            Portfolio portfolio = new Portfolio();
            portfolio.setUserId(userId);
            portfolio.setName("我的投资组合");
            portfolioDao.insert(portfolio);

            conn.commit();
            return null;
        } catch (Exception e) {
            try { if (conn != null) conn.rollback(); } catch (Exception ignored) {}
            throw new RuntimeException("注册失败", e);
        } finally {
            try { if (conn != null) { conn.setAutoCommit(true); conn.close(); } } catch (Exception ignored) {}
        }
    }

    /**
     * 用户登录校验。
     *
     * <p>先按用户名查找用户，再用 BCrypt 校验明文密码与数据库哈希是否匹配。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 登录成功返回对应的 {@link User} 对象；用户不存在或密码错误时返回 {@code null}
     */
    public User login(String username, String password) {
        if (username == null || password == null) return null;

        User user = userDao.findByUsername(username.trim());
        if (user == null) return null;

        return BCrypt.checkpw(password, user.getPasswordHash()) ? user : null;
    }

    /**
     * 修改用户密码。
     *
     * @param userId      用户 ID
     * @param oldPassword 当前（旧）明文密码
     * @param newPassword 新明文密码（长度 ≥ 6）
     * @return {@code true} 表示修改成功
     */
    public boolean changePassword(long userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) return false;

        User user = userDao.findById(userId);
        if (user == null) return false;

        if (!BCrypt.checkpw(oldPassword, user.getPasswordHash())) return false;

        userDao.updatePassword(userId, BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        return true;
    }
}
