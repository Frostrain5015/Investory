package com.investory.dao;
import java.sql.*;
import java.util.List;
import java.util.ArrayList;
public abstract class BaseDao {
    @FunctionalInterface
    protected interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }
    protected <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        List<T> results = new ArrayList<>();
        try (Connection conn = com.investory.server.DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) results.add(mapper.map(rs)); }
        } catch (SQLException e) { throw new RuntimeException("Query failed", e); }
        return results;
    }
    protected <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> r = query(sql, mapper, params); return r.isEmpty() ? null : r.get(0);
    }
    protected int update(String sql, Object... params) {
        try (Connection conn = com.investory.server.DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params); return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Update failed", e); }
    }
    protected long insert(String sql, Object... params) {
        try (Connection conn = com.investory.server.DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setParams(ps, params); ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        } catch (SQLException e) { throw new RuntimeException("Insert failed", e); }
    }
    protected List<java.util.Map<String,Object>> queryForList(String sql, Object... params) {
        List<java.util.Map<String,Object>> results = new ArrayList<>();
        try (Connection conn = com.investory.server.DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData(); int cols = md.getColumnCount();
                while (rs.next()) {
                    java.util.Map<String,Object> row = new java.util.LinkedHashMap<>();
                    for (int i=1; i<=cols; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    results.add(row);
                }
            }
        } catch (SQLException e) { throw new RuntimeException("queryForList failed", e); }
        return results;
    }
    private void setParams(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }
}
