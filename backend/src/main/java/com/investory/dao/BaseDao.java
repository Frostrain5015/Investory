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

/**
 * 所有 DAO 类的抽象基类。
 *
 * <p>职责：封装 {@link JdbcTemplate} 的常用操作（查询列表、查询单条、更新、插入并返回主键），
 * 统一简化子类对数据库的访问方式，避免重复的 RowMapper 适配器样板代码。</p>
 *
 * <p>所有具体 DAO 均继承此类，并通过 {@code protected} 方法调用底层 JDBC 操作。</p>
 */
public abstract class BaseDao {

    /** Spring 注入的 JDBC 模板，子类可直接使用 */
    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * 自定义函数式接口，用于将 {@link ResultSet} 的当前行映射为目标对象。
     *
     * <p>与 Spring 内置的 {@code RowMapper} 类似，但签名更简洁：
     * 不接收 rowNum 参数，专注于单行数据的字段提取与对象构建。</p>
     *
     * @param <T> 映射目标类型
     */
    @FunctionalInterface
    protected interface RowMapper<T> {
        /**
         * 将结果集当前行映射为对象。
         *
         * @param rs 当前行的结果集（已定位到对应行，不需要调用 next()）
         * @return 映射后的目标对象
         * @throws SQLException 读取字段时可能抛出的数据库异常
         */
        T map(ResultSet rs) throws SQLException;
    }

    /**
     * 执行查询并将结果集映射为对象列表。
     *
     * <p>内部将自定义 {@link RowMapper} 适配为 Spring 的 {@code RowMapper}（增加 rowNum 参数）。</p>
     *
     * @param sql    查询 SQL，支持 {@code ?} 占位符
     * @param mapper 行映射器，将每一行结果集转换为目标对象
     * @param params SQL 参数，按顺序对应各 {@code ?} 占位符
     * @param <T>    返回元素的类型
     * @return 映射结果列表，无结果时返回空列表（非 null）
     */
    protected <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return jdbc.query(sql, (rs, rowNum) -> mapper.map(rs), params);
    }

    /**
     * 执行查询并返回第一条结果；无结果时返回 {@code null}。
     *
     * <p>实现上先执行完整列表查询，再取第一个元素，适用于预期最多一条记录的场景
     *（如按主键或唯一键查询）。</p>
     *
     * @param sql    查询 SQL，支持 {@code ?} 占位符
     * @param mapper 行映射器
     * @param params SQL 参数
     * @param <T>    返回对象的类型
     * @return 第一条结果对象，或 {@code null}（无记录时）
     */
    protected <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> results = jdbc.query(sql, (rs, rowNum) -> mapper.map(rs), params);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 执行 INSERT / UPDATE / DELETE 语句。
     *
     * @param sql    DML 语句，支持 {@code ?} 占位符
     * @param params SQL 参数
     * @return 受影响的行数
     */
    protected int update(String sql, Object... params) {
        return jdbc.update(sql, params);
    }

    /**
     * 执行 INSERT 语句并返回数据库自动生成的主键（auto-increment）。
     *
     * <p>通过 {@link GeneratedKeyHolder} 获取插入后的主键值。
     * 如果数据库未返回主键（例如表没有自增列），则返回 {@code -1}。</p>
     *
     * @param sql    INSERT 语句，支持 {@code ?} 占位符
     * @param params SQL 参数，按顺序对应各 {@code ?} 占位符
     * @return 自动生成的主键值（long 类型），无主键时返回 {@code -1}
     */
    protected long insert(String sql, Object... params) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        // 使用 PreparedStatement 并开启 RETURN_GENERATED_KEYS 以获取自增主键
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            // 逐一绑定参数（JDBC 索引从 1 开始）
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        // 若 keyHolder 未持有主键值，返回哨兵值 -1
        return key != null ? key.longValue() : -1;
    }
}
