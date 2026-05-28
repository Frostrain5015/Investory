package com.investory.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 全局 Web 配置类。
 *
 * <p>实现 {@link WebMvcConfigurer} 接口，通过回调方法对框架默认行为进行定制，
 * 主要负责两件事：
 * <ol>
 *   <li><b>CORS 跨域策略</b> — 允许所有来源、所有方法、所有请求头携带凭证，
 *       满足前端开发服务器（Vite dev server）与后端分离部署时的跨域需求。</li>
 *   <li><b>登录拦截器注册</b> — 将 {@link LoginInterceptor} 挂载到几乎所有路径，
 *       并精确排除公开资源（静态文件、登录/注册页、部分公开 API），确保未登录请求
 *       无法访问受保护端点。</li>
 * </ol>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 注入登录拦截器，由 Spring 容器统一管理其生命周期。 */
    @Autowired private LoginInterceptor loginInterceptor;

    /**
     * 配置全局 CORS（跨域资源共享）策略。
     *
     * <p>映射规则说明：
     * <ul>
     *   <li>{@code /**} — 对所有路径生效。</li>
     *   <li>{@code allowedOriginPatterns("*")} — 允许任意来源（使用 Pattern 而非
     *       {@code allowedOrigins} 是因为后者与 {@code allowCredentials(true)} 不兼容）。</li>
     *   <li>{@code allowedMethods("*")} — 允许 GET/POST/PUT/DELETE/OPTIONS 等所有 HTTP 方法。</li>
     *   <li>{@code allowedHeaders("*")} — 允许所有请求头（包括自定义头）。</li>
     *   <li>{@code allowCredentials(true)} — 允许请求携带 Cookie/Session，
     *       这是基于 Session 认证的必要条件。</li>
     * </ul>
     *
     * @param registry Spring 提供的 CORS 注册表，用于链式配置跨域规则
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * 注册登录拦截器，定义其拦截路径与放行路径。
     *
     * <p>拦截策略：
     * <ul>
     *   <li>{@code addPathPatterns("/**")} — 默认拦截所有路径。</li>
     *   <li>{@code excludePathPatterns(...)} — 以下路径无需登录即可访问：
     *     <ul>
     *       <li>{@code /}、{@code /login}、{@code /register}、{@code /logout} —
     *           认证相关的公开页面/操作。</li>
     *       <li>{@code /error} — Spring Boot 默认错误处理端点，必须放行。</li>
     *       <li>{@code /api/session} — 前端用于查询当前登录态的接口，未登录时也需可访问。</li>
     *       <li>{@code /api/stock/search} — 股票搜索接口，允许匿名访问（如注册前的搜索体验）。</li>
     *       <li>{@code /assets/**}、{@code /favicon.svg}、{@code /icons.svg} —
     *           Vite 构建产物中的静态资源目录及图标文件。</li>
     *       <li>{@code /*.js}、{@code /*.css}、{@code /*.json} —
     *           根路径下的 JS/CSS/JSON 文件（Vite 生成的入口 chunk 及 manifest）。</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param registry Spring 提供的拦截器注册表，用于添加和配置拦截器链
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/", "/login", "/register", "/logout", "/error",
                        "/api/session", "/api/stock/search",
                        "/assets/**", "/favicon.svg", "/icons.svg", "/*.js", "/*.css", "/*.json");
    }
}
