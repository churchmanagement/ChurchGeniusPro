package com.churchgeniuspro.webfilter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PermissionApiFilter} on {@code /api/*} at order 8 — after auth (1), member scope (2),
 * account status (6) and subscription (7). Kept out of {@link FilterConfig} so its constructor stays as it is.
 */
@Configuration
public class PermissionApiFilterConfig {

    @Bean
    public FilterRegistrationBean<PermissionApiFilter> permissionApiFilter() {
        FilterRegistrationBean<PermissionApiFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new PermissionApiFilter());
        registration.addUrlPatterns("/api/*");
        registration.setName("permissionApiFilter");
        registration.setOrder(8);
        return registration;
    }
}
