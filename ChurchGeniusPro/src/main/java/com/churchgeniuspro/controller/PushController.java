package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.PushNotificationLog;
import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.repository.PushNotificationLogRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.service.WebPushService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST endpoints that support Web Push (VAPID) notification subscriptions,
 * badge counts, and notification history.
 *
 * <ul>
 *   <li>{@code GET  /api/push/vapid-public-key}  — returns the server's VAPID public key.</li>
 *   <li>{@code POST /api/push/subscribe}           — stores/updates a browser push subscription.</li>
 *   <li>{@code POST /api/push/unsubscribe}          — deactivates a subscription by endpoint.</li>
 *   <li>{@code GET  /api/push/badge}               — returns unread notification count for badge.</li>
 *   <li>{@code GET  /api/push/notifications}       — returns recent notification history (last 20).</li>
 *   <li>{@code POST /api/push/mark-read}           — marks all notifications as read.</li>
 *   <li>{@code POST /api/push/mark-read/{id}}      — marks a single notification as read.</li>
 * </ul>
 */
@RestController
public class PushController {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));

    private final WebPushService                pushService;
    private final PushSubscriptionRepository    pushSubRepo;
    private final PushNotificationLogRepository logRepo;

    /** Public-page submission notifications (Prayer, Connect, Membership, Donation). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.PublicSubmissionNotificationService submissions;

    /** Test seam. */
    public void setSubmissions(com.churchgeniuspro.service.PublicSubmissionNotificationService s) { this.submissions = s; }

    public PushController(WebPushService pushService,
                          PushSubscriptionRepository pushSubRepo,
                          PushNotificationLogRepository logRepo) {
        this.pushService  = pushService;
        this.pushSubRepo  = pushSubRepo;
        this.logRepo      = logRepo;
    }

    // ── GET /api/push/vapid-public-key ────────────────────────────────────────

    // No `produces` constraint: set the content type on the response so the key is
    // returned regardless of the caller's Accept header (avoids a 406 when the fetch
    // sends e.g. Accept: application/json).
    @GetMapping(value = "/api/push/vapid-public-key")
    public ResponseEntity<String> vapidPublicKey() {
        if (!pushService.isEnabled()) {
            // Push is optional: when VAPID keys aren't configured, return 204 No Content
            // so the client can silently skip registration without logging an error.
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .body(pushService.getVapidPublicKeyRaw());
    }

    // ── POST /api/push/subscribe ──────────────────────────────────────────────

    @PostMapping("/api/push/subscribe")
    public ResponseEntity<?> subscribe(@RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("clientId") == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String endpoint = (String) body.get("endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing endpoint"));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> keys = (Map<String, String>) body.get("keys");
        if (keys == null || keys.get("p256dh") == null || keys.get("auth") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing keys"));
        }

        String clientId    = (String) session.getAttribute("clientId");
        String role        = (String) session.getAttribute("role");
        String appClientId = resolveAppClientId(session);
        String userType    = "Member".equals(role) ? "member" : "staff";

        Optional<PushSubscription> existing = pushSubRepo.findByEndpoint(endpoint);
        PushSubscription sub = existing.orElseGet(PushSubscription::new);
        sub.setEndpoint(endpoint);
        sub.setP256dh(keys.get("p256dh"));
        sub.setAuth(keys.get("auth"));
        sub.setUserKey(clientId);
        sub.setAppClientId(appClientId);
        sub.setUserType(userType);
        sub.setActive(true);
        pushSubRepo.save(sub);

        // Return the current unread count so the browser can initialise the badge
        long unread = logRepo.countUnread(clientId);
        return ResponseEntity.ok(Map.of("status", "subscribed", "unreadCount", unread));
    }

    // ── POST /api/push/unsubscribe ────────────────────────────────────────────

    @PostMapping("/api/push/unsubscribe")
    public ResponseEntity<?> unsubscribe(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("clientId") == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        String endpoint = (String) body.get("endpoint");
        if (endpoint != null) {
            pushSubRepo.deactivateByEndpoint(endpoint);
        }
        return ResponseEntity.ok(Map.of("status", "unsubscribed"));
    }

    // ── GET /api/push/badge ───────────────────────────────────────────────────

    /**
     * Returns the number of unread push notifications for the logged-in user.
     * Called by the frontend on page load to update the app-icon badge and
     * topbar bell indicator.
     *
     * <p>Response: {@code { "count": N }}
     */
    @GetMapping("/api/push/badge")
    public ResponseEntity<Map<String, Object>> badge(HttpServletRequest request) {
        String userKey = resolveUserKey(request);
        if (userKey == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        long count = logRepo.countUnread(userKey);
        return ResponseEntity.ok(Map.of("count", count));
    }

    // ── GET /api/push/notifications ───────────────────────────────────────────

    /**
     * Returns the 20 most recent notifications for the logged-in user
     * (both read and unread), newest first.  Used to populate the topbar
     * notification dropdown.
     *
     * <p>Response:
     * {@code { "notifications": [{ id, title, body, url, tag, sentAt, read }], "unreadCount": N }}
     */
    @GetMapping("/api/push/notifications")
    public ResponseEntity<Map<String, Object>> notifications(HttpServletRequest request) {
        String userKey = resolveUserKey(request);
        if (userKey == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        // Only show notifications sent today or later (start of today in UTC)
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<PushNotificationLog> rows = logRepo.findTodayAndFutureByUserKey(
                userKey, startOfToday, PageRequest.of(0, 50));

        // Deduplicate: rows are already sorted newest-first, so the first row seen
        // for a given (title, body, calendar-day) combination is the most recent one.
        // Older duplicates (same message fired by two schedulers or a re-run) are dropped.
        java.util.Set<String> seenKeys = new java.util.LinkedHashSet<>();
        List<PushNotificationLog> deduped = rows.stream().filter(n -> {
            String day = n.getSentAt() != null
                    ? n.getSentAt().atZone(ZoneOffset.UTC).toLocalDate().toString() : "?";
            String key = day + "|" + (n.getTitle() != null ? n.getTitle() : "")
                             + "|" + (n.getBody()  != null ? n.getBody()  : "");
            return seenKeys.add(key); // false (and filtered out) if already seen
        }).collect(Collectors.toList());

        List<Map<String, Object>> items = deduped.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",     n.getId());
            m.put("title",  n.getTitle());
            m.put("body",   n.getBody());
            m.put("url",    n.getUrl());
            m.put("tag",    n.getTag());
            m.put("sentAt", n.getSentAt() != null ? ISO_FMT.format(n.getSentAt()) : null);
            m.put("read",   n.getReadAt() != null);
            return m;
        }).collect(Collectors.toList());

        long unread = deduped.stream().filter(n -> n.getReadAt() == null).count();

        // Public-page submissions the user is allowed to see (permissions, destination
        // page guard, own church, active account — all decided in the service).
        if (submissions != null) {
            try {
                List<Map<String, Object>> subs = submissions.listFor(request);
                if (!subs.isEmpty()) {
                    unread += subs.stream().filter(m -> !Boolean.TRUE.equals(m.get("read"))).count();
                    List<Map<String, Object>> merged = new java.util.ArrayList<>(subs);
                    merged.addAll(items);
                    merged.sort((a, b) -> String.valueOf(b.get("sentAt")).compareTo(String.valueOf(a.get("sentAt"))));
                    items = merged;
                }
            } catch (Exception e) {
                // Never let this break the existing panel.
                org.slf4j.LoggerFactory.getLogger(PushController.class)
                        .warn("Submission notifications unavailable — {}", e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of(
            "notifications", items,
            "unreadCount",   unread
        ));
    }

    // ── POST /api/push/mark-read ──────────────────────────────────────────────

    /**
     * Marks ALL unread notifications as read for the logged-in user and
     * returns the new (zero) unread count.
     *
     * <p>Response: {@code { "count": 0 }}
     */
    @PostMapping("/api/push/mark-read")
    public ResponseEntity<Map<String, Object>> markAllRead(HttpServletRequest request) {
        String userKey = resolveUserKey(request);
        if (userKey == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        logRepo.markAllRead(userKey, Instant.now());
        if (submissions != null) {
            try { submissions.markAllRead(request); } catch (Exception ignored) { /* non-critical */ }
        }
        return ResponseEntity.ok(Map.of("count", 0));
    }

    // ── POST /api/push/dismiss/{id}  ·  /api/push/dismiss-all ────────────────

    /**
     * Clears one public-submission notification ({@code ps-<id>}) from the caller's
     * panel. 404 unless it belongs to the caller's own church and the caller may
     * view its type — ids from other churches are indistinguishable from missing ones.
     */
    @PostMapping("/api/push/dismiss/{id}")
    public ResponseEntity<Map<String, Object>> dismiss(@PathVariable String id, HttpServletRequest request) {
        if (resolveUserKey(request) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        Long sid = com.churchgeniuspro.service.PublicSubmissionNotificationService.parseId(id);
        if (submissions == null || sid == null || !submissions.dismiss(request, sid)) {
            return ResponseEntity.status(404).body(Map.of("error", "Notification not found"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Clears every public-submission notification currently in the caller's panel. */
    @PostMapping("/api/push/dismiss-all")
    public ResponseEntity<Map<String, Object>> dismissAll(HttpServletRequest request) {
        if (resolveUserKey(request) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        int n = submissions == null ? 0 : submissions.dismissAll(request);
        return ResponseEntity.ok(Map.of("success", true, "cleared", n));
    }

    // ── POST /api/push/mark-read/{id} ────────────────────────────────────────

    /**
     * Marks a single notification as read and returns the updated unread count.
     */
    @PostMapping("/api/push/mark-read/{id}")
    public ResponseEntity<Map<String, Object>> markOneRead(@PathVariable Long id,
                                                            HttpServletRequest request) {
        String userKey = resolveUserKey(request);
        if (userKey == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        logRepo.markReadById(id, userKey, Instant.now());   // only the caller's own notification
        long remaining = logRepo.countUnread(userKey);
        return ResponseEntity.ok(Map.of("count", remaining));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveUserKey(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object clientId = session.getAttribute("clientId");
        return clientId != null ? clientId.toString() : null;
    }

    private String resolveAppClientId(HttpSession session) {
        Object v = session.getAttribute("appClientId");
        if (v != null) return String.valueOf(v);
        Object c = session.getAttribute("clientId");
        return c != null ? String.valueOf(c) : null;
    }
}
