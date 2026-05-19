package com.investory.dao;

import com.investory.util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared JDBC helpers inherited by all DAO classes.
 */
public abstract class BaseDao {

    @FunctionalInterface
    protected interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    protected <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> list = new ArrayList<>();
        try (Connection con = DBUtil.getConnection();
             PreparedStatement ps = prepare(con, sql, params);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapper.map(rs));
        }
        return list;
    }

    protected <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (Connection con = DBUtil.getConnection();
             PreparedStatement ps = prepare(con, sql, params);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? mapper.map(rs) : null;
        }
    }

    protected int update(String sql, Object... params) throws SQLException {
        try (Connection con = DBUtil.getConnection();
             PreparedStatement ps = prepare(con, sql, params)) {
            return ps.executeUpdate();
        }
    }

    /** Execute INSERT and return the generated key. */
    protected long insert(String sql, Object... params) throws SQLException {
        try (Connection con = DBUtil.getConnection();
             PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setParams(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        return -1;
    }

    private PreparedStatement prepare(Connection con, String sql, Object... params) throws SQLException {
        PreparedStatement ps = con.prepareStatement(sql);
        setParams(ps, params);
        return ps;
    }

    private void setParams(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
