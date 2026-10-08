package com.churchgeniuspro.trialtips;

import com.churchgeniuspro.controller.TrialTipController;
import com.churchgeniuspro.hibernate.TrialTipState;
import com.churchgeniuspro.repository.TrialTipStateRepository;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Trial feature tips: who is eligible, and that state is per tenant + login and never an error. */
@DisplayName("Trial tips API")
class TrialTipControllerTest {

    final List<TrialTipState> rows = new ArrayList<>();
    TrialTipStateRepository repo;
    SubscriptionService subs;
    TrialTipController c;

    @BeforeEach
    void setUp() {
        repo = mock(TrialTipStateRepository.class);
        subs = mock(SubscriptionService.class);
        when(repo.findByClientIdAndUsername(anyString(), anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getClientId().equals(i.getArgument(0)) && r.getUsername().equals(i.getArgument(1))).toList());
        when(repo.findByClientIdAndUsernameAndTipKey(anyString(), anyString(), anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getClientId().equals(i.getArgument(0)) && r.getUsername().equals(i.getArgument(1)) && r.getTipKey().equals(i.getArgument(2))).findFirst());
        when(repo.countByClientIdAndUsername(anyString(), anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getClientId().equals(i.getArgument(0)) && r.getUsername().equals(i.getArgument(1))).count());
        when(repo.save(any(TrialTipState.class))).thenAnswer(i -> { TrialTipState t = i.getArgument(0); if (!rows.contains(t)) rows.add(t); return t; });
        // CHR-TRIAL is on the Trial plan; CHR-PRO is not. TRIAL-xxx / DEMO-xxx are managed tenants regardless.
        when(subs.trialInfo("CHR-TRIAL")).thenReturn(Map.of("endDate", "2026-11-01"));
        when(subs.trialInfo("CHR-PRO")).thenReturn(null);
        c = new TrialTipController(repo, subs);
    }

    static MockHttpServletRequest session(String role, String clientId, String user, Map<String, Object> extra) {
        MockHttpServletRequest req = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", user); s.setAttribute("role", role); s.setAttribute("appClientId", clientId); s.setAttribute("clientId", clientId);
        extra.forEach(s::setAttribute); req.setSession(s); return req;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }
    boolean eligible(MockHttpServletRequest r) { return Boolean.TRUE.equals(body(c.state(r)).get("eligible")); }

    @Test @DisplayName("eligible: staff logins of a sample-data trial, a demo tenant, or a church on the Trial plan")
    void eligible() {
        assertThat(eligible(session("SuperAdmin", "TRIAL-00042", "a@t.test", Map.of()))).isTrue();
        assertThat(eligible(session("Admin", "DEMO-0007", "b@t.test", Map.of()))).isTrue();
        assertThat(eligible(session("Accountant", "CHR-TRIAL", "c@t.test", Map.of()))).isTrue();
        assertThat(eligible(session("User", "CHR-TRIAL", "d@t.test", Map.of()))).isTrue();
        assertThat(body(c.state(session("Admin", "CHR-TRIAL", "b@t.test", Map.of()))).get("role")).isEqualTo("Admin");
    }

    @Test @DisplayName("not eligible: Free/Standard/Pro churches, Church-portal, member, temporary, NTAG and Service Admin sessions, no session")
    void notEligible() {
        assertThat(eligible(session("SuperAdmin", "CHR-PRO", "a@p.test", Map.of()))).isFalse();
        assertThat(eligible(session("Admin", "CHR-TRIAL", "x@t.test", Map.of("church", true)))).isFalse();
        assertThat(eligible(session("Member", "CHR-TRIAL", "m@t.test", Map.of("memberId", 5)))).isFalse();
        assertThat(eligible(session("User", "CHR-TRIAL", "t@t.test", Map.of("tempAccessId", 9)))).isFalse();
        assertThat(eligible(session("User", "CHR-TRIAL", "n@t.test", Map.of("ntagCredId", "abc")))).isFalse();
        assertThat(eligible(session("ServiceAdmin", "CHR-TRIAL", "sa", Map.of()))).isFalse();
        assertThat(eligible(new MockHttpServletRequest())).isFalse();
        // and recording for an ineligible login is a silent no-op, never a 4xx that could break a page
        assertThat(body(c.shown("favorites", session("Admin", "CHR-PRO", "a@p.test", Map.of()))).get("status")).isEqualTo("ignored");
        assertThat(rows).isEmpty();
    }

    @Test @DisplayName("shown / dismissed are recorded per tenant + login; state returns counts, last shown and today's total")
    void recordAndState() {
        MockHttpServletRequest a = session("Admin", "CHR-TRIAL", "a@t.test", Map.of());
        assertThat(body(c.shown("favorites", a))).containsEntry("shown", 1).containsEntry("dismissed", false);
        assertThat(body(c.shown("favorites", a))).containsEntry("shown", 2);
        assertThat(body(c.dismissed("bank-sync", a))).containsEntry("dismissed", true).containsEntry("shown", 1);
        Map<String, Object> st = body(c.state(a));
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> tips = (Map<String, Map<String, Object>>) st.get("tips");
        assertThat(tips).containsOnlyKeys("favorites", "bank-sync");
        assertThat(tips.get("favorites")).containsEntry("shown", 2).containsEntry("dismissed", false);
        assertThat(tips.get("bank-sync")).containsEntry("dismissed", true);
        assertThat(st.get("lastShownAt")).isNotNull();
        assertThat(st.get("shownToday")).isEqualTo(2);
        // another login of the same church, and the same login on another church, see nothing
        assertThat((Map<?, ?>) body(c.state(session("Admin", "CHR-TRIAL", "other@t.test", Map.of()))).get("tips")).isEmpty();
        assertThat((Map<?, ?>) body(c.state(session("Admin", "TRIAL-00042", "a@t.test", Map.of()))).get("tips")).isEmpty();
        // rows carry only keys and timestamps
        assertThat(rows).allSatisfy(r -> { assertThat(r.getTipKey()).matches("^[a-z0-9-]+$"); assertThat(r.getUsername()).isEqualTo("a@t.test"); });
    }

    @Test @DisplayName("tip keys are validated by shape and capped per login")
    void keys() {
        MockHttpServletRequest a = session("Admin", "CHR-TRIAL", "a@t.test", Map.of());
        assertThat(c.shown("Bad Key!", a).getStatusCode().value()).isEqualTo(400);
        assertThat(c.shown("<script>", a).getStatusCode().value()).isEqualTo(400);
        for (int i = 0; i < TrialTipController.MAX_KEYS_PER_LOGIN; i++) c.shown("k" + i, a);
        assertThat(c.shown("one-more", a).getStatusCode().value()).isEqualTo(400);
        assertThat(c.shown("k5", a).getStatusCode().value()).isEqualTo(200);   // existing keys still update
    }
}
