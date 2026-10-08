package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import com.churchgeniuspro.service.LoginProtectionService;
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
 *   <li>{@link LoginThrottleFilter} (order 0) — refuses secondary login endpoints
 *       ({@code /api/serviceadmin/login}, {@code /api/temp-access/login},
 *       {@code /api/ntag-login/*}) while an IP-scope brute-force block is active, and
 *       records their 401/403 responses as failed attempts.</li>
 *   <li>{@link NoAutoLogoutFilter} (order 0) — adjusts the session timeout to
 *       {@code -1} (never expire) for Member sessions whose
 *       {@code member_preference.no_auto_logout} flag is {@code true}; restores
 *       the default 30-minute timeout otherwise.</li>
 *   <li>{@link ServiceAdminAuthFilter} (order 1) — requires {@code serviceAdminId}
 *       in the session for every {@code /api/serviceadmin/*} request except login.</li>
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
    private final LoginProtectionService loginProtectionService;
    private final DemoAccessService demoAccessService;
    private final TrialRegistrationLinkService trialLinkService;
    private final com.churchgeniuspro.service.PublicLinkResolver publicLinkResolver;
    private final com.churchgeniuspro.service.AccountStatusService accountStatusService;

    /** Optional break-glass token for the global login gate (recovery for off-network admins). */
    @Value("${private-access.bypass-token:}")
    private String privateAccessBypassToken;

    /** Public site URL — its host is what a browser's Origin/Referer must name on writes. */
    @Value("${app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    public FilterConfig(AppUserRepository appUserRepository,
                        LoginRepository loginRepository,
                        TemporaryAccessService temporaryAccessService,
                        PrivateAccessService privateAccessService,
                        SubscriptionService subscriptionService,
                        LoginProtectionService loginProtectionService,
                        DemoAccessService demoAccessService,
                        TrialRegistrationLinkService trialLinkService,
                        com.churchgeniuspro.service.PublicLinkResolver publicLinkResolver,
                        com.churchgeniuspro.service.AccountStatusService accountStatusService) {
        this.appUserRepository = appUserRepository;
        this.loginRepository   = loginRepository;
        this.temporaryAccessService = temporaryAccessService;
        this.privateAccessService = privateAccessService;
        this.subscriptionService = subscriptionService;
        this.loginProtectionService = loginProtectionService;
        this.demoAccessService = demoAccessService;
        this.trialLinkService = trialLinkService;
        this.accountStatusService = accountStatusService;
        this.publicLinkResolver = publicLinkResolver;
    }

    /**
     * Brute-force protection for the secondary authentication endpoints — service-admin
     * login, temporary badge/code login and the NFC (NTAG) login steps.
     *
     * <p>{@code POST /login} is not listed here: {@code LoginController} calls
     * {@link LoginProtectionService} directly so it can key on the submitted username as
     * well as the IP. These endpoints have no username to key on, so they are limited by
     * IP scope, which is the meaningful signal for badge codes, PINs and OTPs.
     *
     * <p>Order 0 — alongside {@link NoAutoLogoutFilter} and ahead of {@link AuthFilter},
     * so a throttled request is refused before any credential is examined.
     */
    @Bean
    public FilterRegistrationBean<LoginThrottleFilter> loginThrottleFilter() {
        FilterRegistrationBean<LoginThrottleFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new LoginThrottleFilter(loginProtectionService));
        registration.addUrlPatterns("/api/serviceadmin/login",
                                    "/api/temp-access/login",
                                    "/api/ntag-login/*");
        registration.setName("loginThrottleFilter");
        registration.setOrder(0);
        return registration;
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
     * Refuses state-changing requests whose Origin/Referer is another site. Order 0,
     * ahead of authentication: a forged request should never reach a handler at all.
     * Mapped to the API and the login endpoint; page GETs are unaffected.
     */
    @Bean
    public FilterRegistrationBean<CsrfOriginFilter> csrfOriginFilter() {
        FilterRegistrationBean<CsrfOriginFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CsrfOriginFilter(appBaseUrl));
        registration.addUrlPatterns("/api/*", "/login", "/webhook/*");
        registration.setName("csrfOriginFilter");
        registration.setOrder(0);
        return registration;
    }

    /**
     * Requires a service-admin session on {@code /api/serviceadmin/*}. AuthFilter
     * whitelists that prefix (service admins have no tenant session), so this is
     * the one place the check lives — it no longer depends on each controller
     * remembering to do it. Order 1, alongside AuthFilter; the two never overlap.
     */
    @Bean
    public FilterRegistrationBean<ServiceAdminAuthFilter> serviceAdminAuthFilter() {
        FilterRegistrationBean<ServiceAdminAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ServiceAdminAuthFilter());
        registration.addUrlPatterns("/api/serviceadmin/*");
        registration.setName("serviceAdminAuthFilter");
        registration.setOrder(1);
        return registration;
    }

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
        registration.setFilter(new PrivatePageFilter(privateAccessService, loginRepository, privateAccessBypassToken, publicLinkResolver));
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
     * Re-checks, on every {@code /api/*} call, that the account is still allowed to
     * be here: the tenant's subscription has not lapsed, been suspended or been
     * removed, and this demo/trial login's window has not ended or been blocked.
     *
     * <p>Those questions were previously asked only at sign-in, so a session
     * outlived their answers — indefinitely for a member with "Don't log out
     * automatically" on, and through every remember-me rebuild. Order 6, ahead of
     * the feature filter: "may this account be here at all" precedes "does its plan
     * include this page". Answers are cached for a minute and every path fails open.
     */
    @Bean
    public FilterRegistrationBean<AccountStatusFilter> accountStatusFilter() {
        FilterRegistrationBean<AccountStatusFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AccountStatusFilter(accountStatusService));
        registration.addUrlPatterns("/api/*");
        registration.setName("accountStatusFilter");
        registration.setOrder(6);
        return registration;
    }

    /**
     * Enforces subscription-plan feature flags ({@code /*}) — pages and APIs
     * belonging to a feature the client's plan disables get a 403 (JSON for
     * {@code /api/*}, HTML notice otherwise). Fast no-op for non-gated paths,
     * anonymous requests, and Service Admin sessions. Order 7 — after auth so
     * the session's clientId is available. Path→feature mapping lives in
     * {@code SubscriptionFeatureCatalog}.
     */
    @Bean
    public FilterRegistrationBean<SubscriptionFeatureFilter> subscriptionFeatureFilter() {
        FilterRegistrationBean<SubscriptionFeatureFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SubscriptionFeatureFilter(subscriptionService));
        registration.addUrlPatterns("/*");
        registration.setName("subscriptionFeatureFilter");
        registration.setOrder(7);
        return registration;
    }

    /**
     * Holds a demo/trial login to the acknowledgement popup: until the trial
     * agreement is accepted, {@code /api/*} calls outside a small whitelist are
     * refused with 403 and {@code DEMO_AGREEMENT_REQUIRED}.
     *
     * <p>Mapped to {@code /api/*} only — page requests must render so the popup has
     * somewhere to appear; withholding the data is what keeps the application out of
     * reach. Order 8, after the auth and subscription filters, so the session is
     * populated. A no-op for every non-demo session.
     */
    @Bean
    public FilterRegistrationBean<DemoTrialAgreementFilter> demoTrialAgreementFilter() {
        FilterRegistrationBean<DemoTrialAgreementFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new DemoTrialAgreementFilter(demoAccessService));
        registration.addUrlPatterns("/api/*");
        registration.setName("demoTrialAgreementFilter");
        registration.setOrder(8);
        return registration;
    }

    /**
     * Gates the Trial Registration page behind a valid invitation token.
     *
     * <p>Mapped to the static file AND the route, because the page provisions a
     * tenant and {@code /trialRegistration.html} is served by the resource handler
     * without any controller running. Order 9 — after the auth filters, though it
     * depends on none of them: the visitor is anonymous by design.
     */
    @Bean
    public FilterRegistrationBean<TrialRegistrationLinkFilter> trialRegistrationLinkFilter() {
        FilterRegistrationBean<TrialRegistrationLinkFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TrialRegistrationLinkFilter(trialLinkService));
        registration.addUrlPatterns("/trialRegistration.html", "/trialRegistration");
        registration.setName("trialRegistrationLinkFilter");
        registration.setOrder(9);
        return registration;
    }

    /**
     * Gates the public Trial Request page behind the Service Admin's current
     * request-link token (Service Admin → Trial Requests → Generate link). Mapped to
     * the static file AND the route, for the same reason as the registration gate.
     */
    @Bean
    public FilterRegistrationBean<TrialRequestLinkFilter> trialRequestLinkFilter(
            com.churchgeniuspro.service.TrialRequestService trialRequestService) {
        FilterRegistrationBean<TrialRequestLinkFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TrialRequestLinkFilter(trialRequestService));
        registration.addUrlPatterns("/trialRequest.html", "/trialRequest");
        registration.setName("trialRequestLinkFilter");
        registration.setOrder(9);
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
