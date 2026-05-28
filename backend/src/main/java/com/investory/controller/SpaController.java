package com.investory.controller;

import com.investory.dao.PortfolioDao;
import com.investory.model.Portfolio;
import com.investory.model.User;
import com.investory.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 单页应用（SPA）入口控制器。
 *
 * <p>本控制器承担两类职责：
 * <ol>
 *   <li><b>SPA 路由托管</b> — 对所有前端路由路径（如 {@code /dashboard}、{@code /holdings} 等）
 *       返回同一份 {@code index.html}，让 React Router 在客户端接管路由解析。
 *       这是前后端分离项目支持浏览器刷新/直接访问深层路径的标准做法。</li>
 *   <li><b>认证接口</b> — 提供登录（{@code POST /login}）、注册（{@code POST /register}）
 *       和登出（{@code GET /logout}）端点，直接操作 HTTP Session，
 *       无需经过 Thymeleaf 视图层，响应体为简单字符串（{@code "ok"} 或错误信息）。</li>
 * </ol>
 *
 * <p>所有方法均标注 {@link ResponseBody}（或通过 {@code @Controller} + 方法级注解），
 * 直接将返回值写入响应体，不走模板引擎。
 */
@Controller
public class SpaController {

    /** 认证服务，处理登录验证与用户注册逻辑。 */
    @Autowired private AuthService authService;

    /** 投资组合数据访问对象，用于登录时查询用户的默认组合。 */
    @Autowired private PortfolioDao portfolioDao;

    /**
     * 为所有前端路由路径提供 React SPA 入口页面。
     *
     * <p>映射了应用中所有由 React Router 管理的客户端路由，包括：
     * 根路径、登录/注册、行情、自选股、仪表盘、投资组合、持仓、交易记录、
     * 股息、股票详情、盈亏日历、管理后台等页面路径。
     *
     * <p>读取 classpath 下 {@code static/index.html}（即 Vite 构建产物）并原样返回，
     * 响应的 Content-Type 默认为 {@code text/html}。
     * 浏览器收到 HTML 后加载 JS bundle，React Router 再根据当前 URL 渲染对应组件。
     *
     * @return {@code index.html} 的完整文本内容
     * @throws IOException 当 classpath 中找不到 {@code static/index.html} 时抛出
     */
    // 为所有非 API 的 GET 路由提供 React SPA 的 index.html
    @GetMapping(value = {"/", "/login", "/register", "/market", "/watchlist", "/dashboard", "/portfolio",
        "/holdings", "/transactions", "/transactions/**", "/dividends", "/dividends/**",
        "/stock", "/pnl-calendar", "/admin"})
    @ResponseBody
    public String serveSpa() throws IOException {
        Resource resource = new ClassPathResource("static/index.html");
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 处理用户登录请求（表单提交或前端 AJAX 调用）。
     *
     * <p>流程说明：
     * <ol>
     *   <li>调用 {@link AuthService#login} 验证用户名和密码。</li>
     *   <li>验证失败（返回 null）时直接响应字符串 {@code "error"}，前端据此提示登录失败。</li>
     *   <li>验证成功时创建新 Session，并写入：
     *     <ul>
     *       <li>{@code userId} — 用户数据库 ID，作为后续权限校验的凭据。</li>
     *       <li>{@code username} — 用户名，供页面展示使用。</li>
     *       <li>{@code isAdmin} — 管理员标志，控制后台入口的显示与访问。</li>
     *       <li>{@code portfolioId} — 用户第一个投资组合的 ID（若存在），
     *           用于持仓、交易等页面快速定位数据。</li>
     *     </ul>
     *   </li>
     *   <li>登录成功返回字符串 {@code "ok"}，前端收到后跳转到主页。</li>
     * </ol>
     *
     * @param username 表单参数：用户名
     * @param password 表单参数：密码（明文，Service 层负责哈希比对）
     * @param req      HTTP 请求对象，用于创建/获取 Session
     * @return {@code "ok"} 表示登录成功；{@code "error"} 表示用户名或密码错误
     */
    @PostMapping("/login")
    @ResponseBody
    public String loginPost(@RequestParam String username, @RequestParam String password,
                            HttpServletRequest req) {
        User user = authService.login(username, password);
        if (user == null) return "error";
        // 登录成功，创建新 Session 并写入用户身份信息
        HttpSession session = req.getSession(true);
        session.setAttribute("userId",   user.getId());
        session.setAttribute("username", user.getUsername());
        session.setAttribute("isAdmin",  user.isAdmin());
        // 查询用户第一个投资组合，写入 Session 以便后续页面直接使用
        List<Portfolio> portfolios = portfolioDao.findByUser(user.getId());
        if (!portfolios.isEmpty()) session.setAttribute("portfolioId", portfolios.get(0).getId());
        return "ok";
    }

    /**
     * 处理用户注册请求。
     *
     * <p>将参数委托给 {@link AuthService#register} 进行校验和持久化。
     * Service 层返回 {@code null} 表示注册成功；返回非空字符串表示失败原因
     * （如"用户名已存在"），直接透传给前端展示。
     *
     * @param username 表单参数：期望注册的用户名
     * @param password 表单参数：密码（明文）
     * @param email    表单参数：邮箱（可选，允许为空）
     * @return {@code "ok"} 表示注册成功；否则返回 Service 层给出的错误描述字符串
     */
    @PostMapping("/register")
    @ResponseBody
    public String registerPost(@RequestParam String username, @RequestParam String password,
                               @RequestParam(required = false) String email) {
        String error = authService.register(username, password, email);
        return error != null ? error : "ok";
    }

    /**
     * 处理用户登出请求。
     *
     * <p>使当前 Session 失效（若 Session 不存在则跳过），
     * 然后重定向到应用根路径，React SPA 将渲染登录页面。
     *
     * @param req HTTP 请求对象，用于获取并销毁当前 Session
     * @return Spring MVC 重定向指令 {@code "redirect:/"}, 即跳转到 SPA 根路径
     */
    @GetMapping("/logout")
    public String logoutGet(HttpServletRequest req) {
        // 销毁当前 Session，清除所有登录状态（userId、username、portfolioId 等）
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        return "redirect:/";
    }
}
