package com.investory.service;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.SQLException;

public class AuthService {

    private static final AuthService INSTANCE = new AuthService();
    public static AuthService get() { return INSTANCE; }

    /** Register a new user. Returns error message or null on success. */
    public String register(String username, String password, String email) throws SQLException {
        if (username == null || username.isBlank()) return "用户名不能为空";
        if (password == null || password.length() < 6) return "密码至少6位";
        if (UserDao.get().usernameExists(username)) return "用户名已被使用";

        User user = new User();
        user.setUsername(username.trim());
        user.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setEmail(email != null ? email.trim() : null);
        long userId = UserDao.get().insert(user);

        // Create default portfolio
        Portfolio portfolio = new Portfolio();
        portfolio.setUserId(userId);
        portfolio.setName("我的投资组合");
        PortfolioDao.get().insert(portfolio);
        return null;
    }

    /** Authenticate. Returns User on success, null on failure. */
    public User login(String username, String password) throws SQLException {
        if (username == null || password == null) return null;
        User user = UserDao.get().findByUsername(username.trim());
        if (user == null) return null;
        return BCrypt.checkpw(password, user.getPasswordHash()) ? user : null;
    }
}
