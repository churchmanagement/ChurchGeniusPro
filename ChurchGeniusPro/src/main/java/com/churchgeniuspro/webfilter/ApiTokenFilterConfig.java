package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.mcp.PersonalAccessTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ApiTokenFilter} on {@code /mcp} and {@code /mcp/*} at order 0 (AI Phase 5).
 * {@code /mcp} is outside {@code /api/*}, so none of the cookie-session filters (AuthFilter,
 * MemberApiScopeFilter, CsrfOriginFilter, AccountStatusFilter, SubscriptionFeatureFilter,
 * AiGuardFilter) run for it — this filter is the whole gate, which is why it also applies the
 * plan check, the rate limits and the audit refusals itself. Kept out of {@link FilterConfig}
 * so that class's constructor stays as it is.
 */
@Configuration
public class ApiTokenFilterConfig {

    @Bean
    public FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(PersonalAccessTokenService tokens, AiRateLimiter limiter, AiAuditService audit,
                                                                 @Value("${mcp.rate.per-token-per-minute:60}") int perTokenPerMinute) {
        FilterRegistrationBean<ApiTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiTokenFilter(tokens, limiter, audit, perTokenPerMinute));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setName("apiTokenFilter");
        registration.setOrder(0);
        return registration;
    }
}
