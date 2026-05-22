package com.investory.service;

import com.investory.dao.PortfolioDao;
import com.investory.dao.UserDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    @Autowired private UserDao userDao;
    @Autowired private PortfolioDao portfolioDao;

    @Transactional(rollbackFor = Exception.class)
    public String register(String username, String password, String email) {
        if (username == null || username.isBlank()) return "用户名不能为空";
        if (password == null || password.length() < 6) return "密码至少6位";
        if (userDao.usernameExists(username)) return "用户名已被使用";

        User user = new User();
        user.setUsername(username.trim());
        user.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setEmail(email != null ? email.trim() : null);
        long userId = userDao.insert(user);

        Portfolio portfolio = new Portfolio();
        portfolio.setUserId(userId);
        portfolio.setName("我的投资组合");
        portfolioDao.insert(portfolio);
        return null;
    }

    public User login(String username, String password) {
        if (username == null || password == null) return null;
        User user = userDao.findByUsername(username.trim());
        if (user == null) return null;
        return BCrypt.checkpw(password, user.getPasswordHash()) ? user : null;
    }

    public boolean changePassword(long userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) return false;
        User user = userDao.findById(userId);
        if (user == null) return false;
        if (!BCrypt.checkpw(oldPassword, user.getPasswordHash())) return false;
        userDao.updatePassword(userId, BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        return true;
    }
}
