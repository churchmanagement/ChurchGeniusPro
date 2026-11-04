package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.repository.FamilyMemberRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link KidsPortalAgeFilter} on every request (pages and API), after
 * {@link AuthFilter} (1) and {@link MemberApiScopeFilter} (2). Kept out of
 * {@link FilterConfig} so that class's constructor stays unchanged.
 */
@Configuration
public class KidsPortalAgeFilterConfig {

    @Bean
    public FilterRegistrationBean<KidsPortalAgeFilter> kidsPortalAgeFilter(FamilyMemberRepository members) {
        FilterRegistrationBean<KidsPortalAgeFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new KidsPortalAgeFilter(members));
        registration.addUrlPatterns("/*");
        registration.setName("kidsPortalAgeFilter");
        registration.setOrder(3);
        return registration;
    }
}
