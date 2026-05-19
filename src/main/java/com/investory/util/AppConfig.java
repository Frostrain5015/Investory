package com.investory.util;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.WebApplicationTemplateResolver;
import org.thymeleaf.web.IWebApplication;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

@WebListener
public class AppConfig implements ServletContextListener {

    public static final String TEMPLATE_ENGINE = "templateEngine";
    public static final String THYMELEAF_APP   = "thymeleafApp";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();

        // 1. Initialize HikariCP
        DBUtil.init();

        // 2. Initialize Thymeleaf
        JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(ctx);
        ctx.setAttribute(THYMELEAF_APP, app);

        WebApplicationTemplateResolver resolver = new WebApplicationTemplateResolver(app);
        resolver.setPrefix("/WEB-INF/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false); // set true in production

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        ctx.setAttribute(TEMPLATE_ENGINE, engine);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        DBUtil.shutdown();
    }
}
