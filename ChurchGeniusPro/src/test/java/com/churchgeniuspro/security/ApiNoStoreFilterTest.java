package com.churchgeniuspro.security;

import com.churchgeniuspro.webfilter.ApiNoStoreFilter;
import com.churchgeniuspro.webfilter.FilterConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Mobile Phase 1, M7: no API response may be kept by a browser or WebView cache. */
class ApiNoStoreFilterTest {

    @Test
    @DisplayName("every /api response carries Cache-Control: no-store")
    void setsNoStore() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new ApiNoStoreFilter().doFilter(new MockHttpServletRequest("GET", "/api/session"), res, new MockFilterChain());
        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("a controller that sets its own Cache-Control wins (the filter sets it BEFORE the chain)")
    void controllerMayOverride() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((HttpServletResponse) rs).setHeader("Cache-Control", "public, max-age=3600");
        new ApiNoStoreFilter().doFilter(new MockHttpServletRequest("GET", "/api/public/songbook/ad-image"), res, chain);
        assertThat(res.getHeader("Cache-Control")).isEqualTo("public, max-age=3600");
    }

    @Test
    @DisplayName("registered on /api/* only, ahead of the auth filters")
    void registration() throws Exception {
        // FilterConfig needs a dozen collaborators; the registration method touches none
        // of them, so an uninitialised instance is enough to read the registration.
        FilterConfig cfg = (FilterConfig) sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(FilterConfig.class, Object.class.getDeclaredConstructor())
                .newInstance();
        FilterRegistrationBean<ApiNoStoreFilter> reg = cfg.apiNoStoreFilter();
        assertThat(reg.getUrlPatterns()).containsExactly("/api/*");
        assertThat(reg.getOrder()).isLessThan(0);
    }
}
