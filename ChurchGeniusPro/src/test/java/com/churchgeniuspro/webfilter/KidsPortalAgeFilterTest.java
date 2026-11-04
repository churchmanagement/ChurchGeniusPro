package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.KidsPortalEligibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Kids Portal 13+ rule (2026-11-04): a session already open when the family record
 * changes ends on the next request after the cache window — the API answers 401 with
 * the reason, a page goes to the login page. Staff and adults are untouched.
 */
@DisplayName("Kids Portal 13+ — open sessions are re-checked")
class KidsPortalAgeFilterTest {

    FamilyMemberRepository repo; FamilyMember kid; AtomicLong now; KidsPortalAgeFilter filter;

    @BeforeEach
    void setUp() {
        repo = mock(FamilyMemberRepository.class);
        kid = new FamilyMember(); kid.setId(5); kid.setRole("Child"); kid.setMemberRef("MBR-k");
        setAge(kid, 14);
        when(repo.findByMemberRef("MBR-k")).thenReturn(Optional.of(kid));
        now = new AtomicLong(1_000_000L);
        filter = new KidsPortalAgeFilter(repo, now::get);
    }

    static void setAge(FamilyMember m, Integer years) {
        if (years == null) { m.setBirthdayYear(null); m.setBirthdayMonth(null); m.setBirthdayDay(null); return; }
        LocalDate d = AppClock.today().minusYears(years).minusDays(1);
        m.setBirthdayYear(d.getYear()); m.setBirthdayMonth(d.getMonthValue()); m.setBirthdayDay(d.getDayOfMonth());
    }

    static MockHttpSession memberSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member"); s.setAttribute("memberId", 5); s.setAttribute("clientId", "MBR-k");
        return s;
    }

    static MockHttpServletRequest req(String uri, MockHttpSession s) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        r.setSession(s);
        return r;
    }

    @Test @DisplayName("an allowed child passes and the result is cached for the window (one lookup)")
    void allowedAndCached() throws Exception {
        MockHttpSession s = memberSession();
        for (int i = 0; i < 3; i++) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req("/api/member/home", s), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
        verify(repo, times(1)).findByMemberRef("MBR-k");
    }

    @Test @DisplayName("record changed (date cleared): after the window the API answers 401 KIDS_PORTAL_AGE and the session ends")
    void apiEndsSession() throws Exception {
        MockHttpSession s = memberSession();
        filter.doFilter(req("/api/x", s), new MockHttpServletResponse(), new MockFilterChain());
        setAge(kid, null);
        now.addAndGet(KidsPortalAgeFilter.RECHECK_MS - 1);
        MockFilterChain within = new MockFilterChain();
        filter.doFilter(req("/api/x", s), new MockHttpServletResponse(), within);
        assertThat(within.getRequest()).as("still inside the cache window").isNotNull();

        now.addAndGet(2);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req("/api/x", s), res, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains(KidsPortalEligibility.CODE).contains("\"reason\":\"dob\"");
        assertThat(s.isInvalid()).isTrue();
    }

    @Test @DisplayName("a page request for an under-13 session goes to the login page with the reason")
    void pageRedirects() throws Exception {
        setAge(kid, 11);
        MockHttpSession s = memberSession();
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req("/memberHome", s), res, new MockFilterChain());
        assertThat(res.getRedirectedUrl()).isEqualTo("/?reason=age");
        assertThat(s.isInvalid()).isTrue();
    }

    @Test @DisplayName("staff sessions, no session and static files are never looked up")
    void notApplicable() throws Exception {
        MockHttpSession staff = new MockHttpSession();
        staff.setAttribute("role", "Admin"); staff.setAttribute("memberId", 5); staff.setAttribute("clientId", "MBR-k");
        filter.doFilter(req("/api/x", staff), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(req("/shell.js", memberSession()), new MockHttpServletResponse(), new MockFilterChain());
        verify(repo, never()).findByMemberRef(anyString());
    }

    @Test @DisplayName("a lookup error lets the request through (the session was allowed at sign-in)")
    void failsOpen() throws Exception {
        when(repo.findByMemberRef("MBR-k")).thenThrow(new RuntimeException("db"));
        MockFilterChain chain = new MockFilterChain();
        MockHttpSession s = memberSession();
        filter.doFilter(req("/api/x", s), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(s.isInvalid()).isFalse();
    }
}
