package com.investory.dao;

import com.investory.model.User;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

@Repository
public class UserDao extends BaseDao {

    private User map(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setEmail(rs.getString("email"));
        try { u.setAdmin(rs.getBoolean("is_admin")); } catch (SQLException ignored) {}
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) u.setCreatedAt(ts.toLocalDateTime());
        return u;
    }

    public User findByUsername(String username) {
        return queryOne("SELECT * FROM users WHERE username = ?", this::map, username);
    }

    public User findById(long id) {
        return queryOne("SELECT * FROM users WHERE id = ?", this::map, id);
    }

    public long insert(User user) {
        return insert(
            "INSERT INTO users (username, password_hash, email) VALUES (?, ?, ?)",
            user.getUsername(), user.getPasswordHash(), user.getEmail()
        );
    }

    public void updatePassword(long userId, String hash) {
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", hash, userId);
    }

    public boolean usernameExists(String username) {
        return queryOne("SELECT id FROM users WHERE username = ?",
                rs -> rs.getLong("id"), username) != null;
    }

    /** Cascade-delete user and all associated data. */
    public void delete(long userId) {
        jdbc.update("DELETE FROM daily_portfolio_value WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM dividends WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM transactions WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM holdings WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM cash_balances WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM watchlist WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ai_settings WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM backtest_results WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM backtest_strategies WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }
}
