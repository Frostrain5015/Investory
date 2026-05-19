package com.investory.dao;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public abstract class BaseDao {

    @Autowired
    protected JdbcTemplate jdbc;

    @FunctionalInterface
    protected interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    protected <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return jdbc.query(sql, (rs, rowNum) -> mapper.map(rs), params);
    }

    protected <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> results = jdbc.query(sql, (rs, rowNum) -> mapper.map(rs), params);
        return results.isEmpty() ? null : results.get(0);
    }

    protected int update(String sql, Object... params) {
        return jdbc.update(sql, params);
    }

    protected long insert(String sql, Object... params) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1;
    }
}
