package com.investory.dao;

import com.investory.model.User;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

public class UserDao extends BaseDao {

    private static final UserDao INSTANCE = new UserDao();
    public static UserDao get() { return INSTANCE; }

    private User map(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setEmail(rs.getString("email"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) u.setCreatedAt(ts.toLocalDateTime());
        return u;
    }

    public User findByUsername(String username) throws SQLException {
        return queryOne("SELECT * FROM users WHERE username = ?", this::map, username);
    }

    public User findById(long id) throws SQLException {
        return queryOne("SELECT * FROM users WHERE id = ?", this::map, id);
    }

    public long insert(User user) throws SQLException {
        return insert(
            "INSERT INTO users (username, password_hash, email) VALUES (?, ?, ?)",
            user.getUsername(), user.getPasswordHash(), user.getEmail()
        );
    }

    public boolean usernameExists(String username) throws SQLException {
        return queryOne("SELECT id FROM users WHERE username = ?",
                rs -> rs.getLong("id"), username) != null;
    }
}
