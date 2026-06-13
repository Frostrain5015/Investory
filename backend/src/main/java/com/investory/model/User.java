package com.investory.model;

import java.time.LocalDateTime;

/**
 * 用户账户实体类。
 * <p>
 * 对应数据库 user 表，存储系统用户的认证信息与权限标志。
 * 密码以哈希形式存储（passwordHash），原始明文密码不持久化。
 * 用户是投资组合（Portfolio）的所有者，通过 userId 外键关联。
 * </p>
 */
public class User {

    /** 数据库自增主键 */
    private Long id;

    /** 登录用户名，系统内唯一，不可重复 */
    private String username;

    /**
     * 密码哈希值，使用安全哈希算法（如 BCrypt）对原始密码单向加密后存储。
     * 绝不存储明文密码；认证时通过哈希比对验证。
     */
    private String passwordHash;

    /** 用户邮箱地址，可为空，用于通知及账号找回 */
    private String email;

    /**
     * 是否为管理员标志。
     * true 表示该用户拥有管理员权限（可查看所有用户数据等高权限操作）；
     * false 表示普通用户，只能访问自己的投资组合数据。
     */
    private boolean isAdmin;

    /** 关联的 Frost ID 用户唯一标识（sub claim），用于 OAuth 登录绑定 */
    private String frostIdId;

    /** 账号创建时间，由数据库或业务层在注册时自动赋值 */
    private LocalDateTime createdAt;

    /** 无参构造器，供 JdbcTemplate RowMapper 及序列化框架使用 */
    public User() {}

    /**
     * 获取主键 ID。
     *
     * @return 数据库自增主键
     */
    public Long getId() { return id; }

    /**
     * 设置主键 ID。
     *
     * @param id 数据库自增主键
     */
    public void setId(Long id) { this.id = id; }

    /**
     * 获取登录用户名。
     *
     * @return 系统内唯一的用户名
     */
    public String getUsername() { return username; }

    /**
     * 设置登录用户名。
     *
     * @param username 系统内唯一的用户名
     */
    public void setUsername(String username) { this.username = username; }

    /**
     * 获取密码哈希值。
     *
     * @return 经哈希算法处理后的密码字符串
     */
    public String getPasswordHash() { return passwordHash; }

    /**
     * 设置密码哈希值（存入前应已完成哈希处理，禁止传入明文）。
     *
     * @param passwordHash 已哈希的密码字符串
     */
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    /**
     * 获取用户邮箱地址。
     *
     * @return 邮箱地址，可为 null
     */
    public String getEmail() { return email; }

    /**
     * 设置用户邮箱地址。
     *
     * @param email 邮箱地址
     */
    public void setEmail(String email) { this.email = email; }

    /**
     * 判断是否为管理员用户。
     *
     * @return true 表示管理员，false 表示普通用户
     */
    public boolean isAdmin() { return isAdmin; }

    /**
     * 设置管理员标志。
     *
     * @param admin true 表示授予管理员权限，false 表示撤销
     */
    public void setAdmin(boolean admin) { isAdmin = admin; }

    public String getFrostIdId() { return frostIdId; }
    public void setFrostIdId(String frostIdId) { this.frostIdId = frostIdId; }

    /**
     * 获取账号创建时间。
     *
     * @return 用户注册时的时间戳
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * 设置账号创建时间。
     *
     * @param createdAt 用户注册时的时间戳
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
