package com.investory.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 全局 Thymeleaf 模型增强器。
 *
 * <p>使用 {@link ControllerAdvice} 注解，在所有返回视图（Thymeleaf 模板）的 Controller
 * 方法执行前，自动向 {@link Model} 中注入公共属性，避免每个 Controller 重复填充相同数据。
 *
 * <p>注入的属性可在任意 Thymeleaf 模板中通过 {@code ${attributeName}} 直接引用：
 * <ul>
 *   <li>{@code sessionUserId} — 当前登录用户的数据库 ID，可用于权限判断或数据查询。</li>
 *   <li>{@code sessionUsername} — 当前登录用户名，可用于页面欢迎语展示。</li>
 *   <li>{@code sessionPortfolioId} — 当前会话关联的默认投资组合 ID，供需要显示持仓数据的页面使用。</li>
 *   <li>{@code contextPath} — 应用的 Servlet 上下文路径（即 {@code /investory}），
 *       用于在模板中构建绝对 URL，确保部署在非根路径时链接依然正确。</li>
 * </ul>
 *
 * <p>注意：本类仅对 {@code @Controller}（Thymeleaf 视图）生效，
 * 不影响 {@code @RestController} 的 JSON 响应。
 */
@ControllerAdvice
public class GlobalModelAdvice {

    /**
     * 在每次视图渲染前向 Model 注入全局公共属性。
     *
     * <p>触发时机：任何 {@code @Controller} 方法执行后、视图渲染前，
     * Spring MVC 会自动调用所有被 {@link ModelAttribute} 注解标记的方法。
     *
     * <p>会话属性仅在 Session 存在时注入；若用户未登录（Session 为 null），
     * 则 {@code sessionUserId}、{@code sessionUsername}、{@code sessionPortfolioId}
     * 三个属性不会被添加到 Model，模板中引用时值为 null，需做非空判断。
     * {@code contextPath} 属性始终注入，不依赖登录状态。
     *
     * @param req   当前 HTTP 请求对象，用于获取 Session 和上下文路径
     * @param model Spring MVC 的 Model 对象，属性将传递给 Thymeleaf 模板引擎
     */
    @ModelAttribute
    public void addGlobalAttributes(HttpServletRequest req, Model model) {
        // 获取已有 Session（false 表示不主动创建），避免为未登录请求无谓创建 Session
        HttpSession session = req.getSession(false);
        if (session != null) {
            // 当前用户 ID，通常为数据库 users 表的主键
            model.addAttribute("sessionUserId",       session.getAttribute("userId"));
            // 当前用户名，用于页面顶部导航栏展示
            model.addAttribute("sessionUsername",     session.getAttribute("username"));
            // 当前会话默认投资组合 ID，登录时取用户第一个组合写入 Session
            model.addAttribute("sessionPortfolioId",  session.getAttribute("portfolioId"));
        }
        // 应用上下文路径（如 /investory），模板中构造绝对链接时需要拼接此前缀
        model.addAttribute("contextPath", req.getContextPath());
    }
}
