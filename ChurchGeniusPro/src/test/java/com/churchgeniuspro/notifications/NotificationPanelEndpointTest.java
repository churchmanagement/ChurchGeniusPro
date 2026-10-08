package com.churchgeniuspro.notifications;

import com.churchgeniuspro.controller.PushController;
import com.churchgeniuspro.repository.PushNotificationLogRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.service.PublicSubmissionNotificationService;
import com.churchgeniuspro.service.WebPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The existing notification panel endpoints carry the submission notifications. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Notification panel endpoints")
class NotificationPanelEndpointTest {

    @Mock WebPushService push;
    @Mock PushSubscriptionRepository subs;
    @Mock PushNotificationLogRepository log;
    @Mock PublicSubmissionNotificationService submissions;
    PushController controller;
    MockHttpServletRequest req;

    @BeforeEach
    void setUp() {
        controller = new PushController(push, subs, log);
        controller.setSubmissions(submissions);
        when(log.findTodayAndFutureByUserKey(anyString(), any(), any())).thenReturn(List.of());
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", "USR-1");
        req = new MockHttpServletRequest(); req.setSession(s);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("submissions appear in the same list and count as unread")
    void merged() {
        when(submissions.listFor(req)).thenReturn(List.of(Map.of(
                "id", "ps-7", "title", "New prayer request", "url", "/followups?tab=prayer",
                "sentAt", "2026-09-28T12:00:00Z", "read", false, "dismissible", true)));
        Map<String, Object> body = controller.notifications(req).getBody();
        assertThat((List<Map<String, Object>>) body.get("notifications")).hasSize(1);
        assertThat(body.get("unreadCount")).isEqualTo(1L);
    }

    @Test
    @DisplayName("nothing to show keeps the existing empty panel ('You're all caught up!')")
    void empty() {
        when(submissions.listFor(req)).thenReturn(List.of());
        assertThat((List<?>) controller.notifications(req).getBody().get("notifications")).isEmpty();
    }

    @Test
    @DisplayName("dismiss: own → 200; unknown / other church / malformed id → 404")
    void dismiss() {
        when(submissions.dismiss(req, 7L)).thenReturn(true);
        when(submissions.dismiss(req, 8L)).thenReturn(false);
        assertThat(controller.dismiss("ps-7", req).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.dismiss("ps-8", req).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.dismiss("123", req).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.dismiss("ps-x", req).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("not signed in → 401, and nothing is looked up")
    void anonymous() {
        MockHttpServletRequest anon = new MockHttpServletRequest();
        assertThat(controller.dismiss("ps-7", anon).getStatusCode().value()).isEqualTo(401);
        verify(submissions, never()).dismiss(any(), eq(7L));
    }

    @Test
    @DisplayName("opening the panel also marks submission notifications read")
    void markAllRead() {
        controller.markAllRead(req);
        verify(submissions).markAllRead(req);
    }
}
