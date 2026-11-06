package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.audit.AiAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AiGuardFilter} on {@code /api/*} at order 8 — after the auth (1), member
 * scope (2), account-status (6) and subscription (7) filters, so it only ever sees a signed-in,
 * plan-permitted request. Kept out of {@link FilterConfig} so that class's constructor stays as it is.
 */
@Configuration
public class AiGuardFilterConfig {

    @Bean
    public FilterRegistrationBean<AiGuardFilter> aiGuardFilter(AiAuditService audit, AiRateLimiter limiter,
                                                               @Value("${ai.gates.temp-access.enforce:true}") boolean enforceTempAccess,
                                                               com.churchgeniuspro.ai.consent.AiConsentGate consentGate) {
        FilterRegistrationBean<AiGuardFilter> registration = new FilterRegistrationBean<>();
        AiGuardFilter filter = new AiGuardFilter(audit, limiter, enforceTempAccess);
        filter.setConsentGate(consentGate);   // App Store readiness D6
        registration.setFilter(filter);
        registration.addUrlPatterns("/api/*");
        registration.setName("aiGuardFilter");
        registration.setOrder(8);
        return registration;
    }
}
