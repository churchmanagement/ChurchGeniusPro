package com.churchgeniuspro.membergive;

import com.churchgeniuspro.controller.MemberGivingLinkController;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App Store readiness D5: the iOS app opens the church's public donation page in Safari
 * instead of showing the Stripe card form. This pins the read-only giving-link endpoint and
 * the page wiring (iOS only; web and Android untouched).
 */
class MemberGivingLinkTest {

    private static final String CHURCH = "USRchurch-a";

    private static PublicScreenLink link(String token, boolean revoked, LocalDate expires) {
        PublicScreenLink l = new PublicScreenLink();
        l.setToken(token); l.setRevoked(revoked); l.setExpirationDate(expires);
        l.setPageUrl("/donate"); l.setAppClientId(CHURCH);
        return l;
    }

    private static MockHttpServletRequest memberRequest(String appClientId) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/member/giving-link");
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member");
        s.setAttribute("memberId", 5);
        s.setAttribute("memberRole", "Head");
        s.setAttribute("appClientId", appClientId);
        req.setSession(s);
        return req;
    }

    @Test
    @DisplayName("a member gets this church's active public donation path")
    void memberGetsPath() {
        PublicScreenLinkRepository repo = mock(PublicScreenLinkRepository.class);
        when(repo.findByAppClientIdAndPageUrlStartingWithAndRevokedFalse(CHURCH, "/donate"))
                .thenReturn(List.of(link("old", false, LocalDate.of(2000, 1, 1)), link("tok123", false, null)));
        ResponseEntity<Map<String, Object>> r = new MemberGivingLinkController(repo).givingLink(memberRequest(CHURCH));
        assertEquals(200, r.getStatusCode().value());
        assertEquals(true, r.getBody().get("available"));
        assertEquals("/donate/tok123", r.getBody().get("path"));
    }

    @Test
    @DisplayName("no active link → available:false with a message, never a path")
    void noLink() {
        PublicScreenLinkRepository repo = mock(PublicScreenLinkRepository.class);
        when(repo.findByAppClientIdAndPageUrlStartingWithAndRevokedFalse(eq(CHURCH), eq("/donate"))).thenReturn(List.of());
        ResponseEntity<Map<String, Object>> r = new MemberGivingLinkController(repo).givingLink(memberRequest(CHURCH));
        assertEquals(false, r.getBody().get("available"));
        assertFalse(r.getBody().containsKey("path"));
        assertTrue(String.valueOf(r.getBody().get("message")).contains("church office"));
    }

    @Test
    @DisplayName("staff, Kids Portal, temporary-access and session-less requests are refused without a lookup")
    void nonMembersRefused() {
        PublicScreenLinkRepository repo = mock(PublicScreenLinkRepository.class);
        MemberGivingLinkController c = new MemberGivingLinkController(repo);

        assertEquals(401, c.givingLink(new MockHttpServletRequest()).getStatusCode().value());

        MockHttpServletRequest staff = memberRequest(CHURCH);
        staff.getSession().setAttribute("role", "Admin");
        assertEquals(401, c.givingLink(staff).getStatusCode().value());

        MockHttpServletRequest child = memberRequest(CHURCH);
        child.getSession().setAttribute("memberRole", "Child");
        assertEquals(401, c.givingLink(child).getStatusCode().value());

        MockHttpServletRequest temp = memberRequest(CHURCH);
        temp.getSession().setAttribute("tempAccessId", 7);
        assertEquals(401, c.givingLink(temp).getStatusCode().value());

        verify(repo, never()).findByAppClientIdAndPageUrlStartingWithAndRevokedFalse(anyString(), anyString());
    }

    @Test
    @DisplayName("the lookup uses the session's own church only")
    void tenantScoped() {
        PublicScreenLinkRepository repo = mock(PublicScreenLinkRepository.class);
        when(repo.findByAppClientIdAndPageUrlStartingWithAndRevokedFalse(anyString(), anyString())).thenReturn(List.of());
        new MemberGivingLinkController(repo).givingLink(memberRequest("USRchurch-b"));
        verify(repo).findByAppClientIdAndPageUrlStartingWithAndRevokedFalse("USRchurch-b", "/donate");
    }

    @Test
    @DisplayName("pages: iOS-only Safari hand-off; the Stripe form stays for web and Android")
    void pageWiring() throws Exception {
        Path st = Path.of("src/main/resources/static");
        String member = Files.readString(st.resolve("memberHome.html"));
        assertTrue(member.contains("if (isIosApp()) { giveEnabled = false; return renderGiveInSafari(); }"));
        assertTrue(member.contains("window.CGP_NATIVE.platform === 'ios'"));
        assertTrue(member.contains("/api/member/giving-link"));
        assertTrue(member.contains("givePaymentElement.mount('#givePaymentElement')"), "web form kept");

        String bridge = Files.readString(st.resolve("native-bridge.js"));
        assertTrue(bridge.contains("window.CGP_NATIVE.openInSafari = openInSafari"));
        assertTrue(bridge.contains("IS_IOS && sameOrigin(abs) && isPaymentPath(abs)"));
        assertTrue(bridge.indexOf("if (!isNative) return;") < bridge.indexOf("var IS_IOS"),
                "browsers never reach the payment hand-off");

        String donation = Files.readString(st.resolve("donation.html"));
        assertTrue(donation.indexOf("if (cgpIosAppShell())") < donation.indexOf("Stripe(config.publishableKey)"),
                "iOS check runs before Stripe is initialised");
        String invoice = Files.readString(st.resolve("invoice.html"));
        assertTrue(invoice.indexOf("if (cgpIosAppShell())") < invoice.indexOf("window.Stripe(x.d.publishableKey)"));
    }
}
