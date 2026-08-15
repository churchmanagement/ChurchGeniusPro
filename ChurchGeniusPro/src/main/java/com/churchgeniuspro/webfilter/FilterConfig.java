package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.service.PrivateAccessService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.TemporaryAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers servlet filters and maps them to URL patterns.
 *
 * <p>Filter execution order:
 * <ol>
 *   <li>{@link CspFilter} (order -1) — sets the Content-Security-Policy header
 *       on every response, covering all URL patterns ({@code /*}).</li>
 *   <li>{@link NoAutoLogoutFilter} (order 0) — adjusts the session timeout to
 *       {@code -1} (never expire) for Member sessions whose
 *       {@code member_preference.no_auto_logout} flag is {@code true}; restores
 *       the default 30-minute timeout otherwise.</li>
 *   <li>{@link AuthFilter}  (order 1) — gates every request; returns 401 when
 *       no valid session exists (public paths are whitelisted internally).
 *       Also re-validates {@code app_user.enabled} / {@code app_user.delete_flag}
 *       on every staff request so that disabling or deleting an account takes
 *       effect immediately without waiting for the session to expire.</li>
 *   <li>{@link LoginFilter} (order 2) — logs the active session's key attributes
 *       (clientId, churchName, subscription, role, username) at DEBUG level so
 *       they remain visible in the server log throughout the session lifecycle.</li>
 * </ol>
 */
@Configuration
public class FilterConfig {

    private final AppUserRepository appUserRepository;
    private final LoginRepository   loginRepository;
    private final TemporaryAccessService temporaryAccessService;
    private final PrivateAccessService privateAccessService;
    private final SubscriptionService subscriptionService;

    /** Optional break-glass token for the global login gate (recovery for off-network admins). */
    @Value("${private-access.bypass-token:}")
    private String privateAccessBypassToken;

    public FilterConfig(AppUserRepository appUserRepository,
                        LoginRepository loginRepository,
                        TemporaryAccessService temporaryAccessService,
                        PrivateAccessService privateAccessService,
                        SubscriptionService subscriptionService) {
        this.appUserRepository = appUserRepository;
        this.loginRepository   = loginRepository;
        this.temporaryAccessService = temporaryAccessService;
        this.privateAccessService = privateAccessService;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Catches MultipartException (truncated upload streams) at the filter level
     * so it never reaches GlobalExceptionHandler#handleAll as a noisy ERROR log.
     * Must run at order -2 — outside CspFilter — to wrap the full chain.
     */
    @Bean
    public FilterRegistrationBean<MultipartErrorFilter> multipartErrorFilter() {
        FilterRegistrationBean<MultipartErrorFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new MultipartErrorFilter());
        registration.addUrlPatterns("/*");
        registration.setName("multipartErrorFilter");
        registration.setOrder(-2);
        return registration;
    }

    /**
     * Sets Content-Security-Policy on every response.
     * Runs at order -1 so it fires before all other custom filters.
     */
    @Bean
    public FilterRegistrationBean<CspFilter> cspFilter() {
        FilterRegistrationBean<CspFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CspFilter());
        registration.addUrlPatterns("/*");
        registration.setName("cspFilter");
        registration.setOrder(-1);
        return registration;
    }

    /**
     * Extends the session lifetime to infinity for members who have enabled
     * "Don't log out automatically" in their preferences.
     *
     * <p>Order 0 ensures this runs first — before {@link AuthFilter} validates
     * the session — so the timeout is already set correctly for every request.
     */
    @Bean
    public FilterRegistrationBean<NoAutoLogoutFilter> noAutoLogoutFilter() {
        FilterRegistrationBean<NoAutoLogoutFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new NoAutoLogoutFilter());
        registration.addUrlPatterns("/api/*");
        registration.setName("noAutoLogoutFilter");
        registration.setOrder(0);
        return registration;
    }

    /**
     * Protects {@code /api/*} endpoints by requiring a valid HTTP session.
     * Also re-validates account status (enabled / deleted) on every request
     * so that toggling a user's status takes effect immediately.
     *
     * <p>Order 1 ensures this filter runs before any other custom filter.
     * Public paths are handled inside {@link AuthFilter} itself.
     */
    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter() {
        FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AuthFilter(appUserRepository, loginRepository));
        registration.addUrlPatterns("/api/*");
        registration.setName("authFilter");
        registration.setOrder(1);
        return registration;
    }

    /**
     * Logs active-session attributes on every {@code /api/*} request so the
     * values set at login are visible in the server log (after login, before
     * logout).  Runs at order 2 — after {@link AuthFilter} has already verified
     * the session is valid.
     */
    /**
     * Enforces the Temporary Access time window on every request ({@code /*}).
     * No-op for normal sessions; for temporary sessions it ends the session the
     * moment the pass expires or is revoked. Order 3 — after the auth filters.
     */
    @Bean
    public FilterRegistrationBean<TempAccessFilter> tempAccessFilter() {
        FilterRegistrationBean<TempAccessFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TempAccessFilter(temporaryAccessService));
        registration.addUrlPatterns("/*");
        registration.setName("tempAccessFilter");
        registration.setOrder(3);
        return registration;
    }

    /**
     * Enforces Private Page Access (network-based restriction) on the gated page
     * groups. Registered only on those path patterns so it adds no overhead elsewhere.
     * Order 4 — after the auth/temp-access filters so the session is available.
     *
     * <p>To gate a new area, add its key+prefixes to {@code PrivatePageCatalog} and
     * add the matching URL patterns here.
     */
    @Bean
    public FilterRegistrationBean<PrivatePageFilter> privatePageFilter() {
        FilterRegistrationBean<PrivatePageFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new PrivatePageFilter(privateAccessService, loginRepository, privateAccessBypassToken));
        // Mapped to /* so it also covers the public login routes ("/", "/login.html",
        // "/tempLogin"); the filter itself is a fast no-op for any non-gated path.
        registration.addUrlPatterns("/*");
        registration.setName("privatePageFilter");
        registration.setOrder(4);
        return registration;
    }

    /**
     * Strict "Allowed Access Pages" enforcement for NTAG-login sessions ({@code /*}).
     * No-op for normal sessions; for an NTAG session it forwards any page outside the
     * tag's permitted pages to {@code /access-denied.html}. Order 5 — after the auth
     * filters so the session (and its {@code ntagRoutes}) is available.
     */
    @Bean
    public FilterRegistrationBean<NtagAccessFilter> ntagAccessFilter() {
        FilterRegistrationBean<NtagAccessFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new NtagAccessFilter());
        registration.addUrlPatterns("/*");
        registration.setName("ntagAccessFilter");
        registration.setOrder(5);
        return registration;
    }

    /**
     * Enforces subscription-plan feature flags ({@code /*}) — pages and APIs
     * belonging to a feature the client's plan disables get a 403 (JSON for
     * {@code /api/*}, HTML notice otherwise). Fast no-op for non-gated paths,
     * anonymous requests, and Service Admin sessions. Order 6 — after auth so
     * the session's clientId is available. Path→feature mapping lives in
     * {@code SubscriptionFeatureCatalog}.
     */
    @Bean
    public FilterRegistrationBean<SubscriptionFeatureFilter> subscriptionFeatureFilter() {
        FilterRegistrationBean<SubscriptionFeatureFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SubscriptionFeatureFilter(subscriptionService));
        registration.addUrlPatterns("/*");
        registration.setName("subscriptionFeatureFilter");
        registration.setOrder(6);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<LoginFilter> loginFilter() {
        FilterRegistrationBean<LoginFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new LoginFilter());
        registration.addUrlPatterns("/api/*");
        registration.setName("loginFilter");
        registration.setOrder(2);
        return registration;
    }
}
