package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Donation Review — marks listed donations Completed, or back to Pending (undo).
 *
 * <p>This is a bookkeeping flag for the Donation Review page only. It changes the
 * donation's {@code review_status} and nothing else: Stripe's payment status, the
 * donation record itself and the Income row it was posted to (and therefore tax
 * statements, giving statements and financial reports) are never touched. Completed
 * donations stay in the list and are excluded only from the page's Pending totals.
 *
 * <p>Access is the same as the page: Accountant or Admin role plus the
 * {@code accounting.donation} permission. Every update is scoped by the session's
 * church in its WHERE clause, and a request naming any donation outside that church
 * is refused as a whole with nothing changed.
 */
@RestController
public class DonationReviewController {

    private static final Logger log = LoggerFactory.getLogger(DonationReviewController.class);

    /** Upper bound on ids per request — far above any real page selection. */
    static final int MAX_IDS = 1000;

    private final DonationRepository donationRepo;

    public DonationReviewController(DonationRepository donationRepo) {
        this.donationRepo = donationRepo;
    }

    @PostMapping("/api/donations/review-status")
    public ResponseEntity<?> setReviewStatus(@RequestBody(required = false) Map<String, Object> body,
                                             HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.donation");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));

        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in again."));

        if (body == null) return bad("Nothing to update.");
        String status = body.get("status") instanceof String s ? s.trim().toUpperCase() : "";
        if (!Donation.REVIEW_COMPLETED.equals(status) && !"PENDING".equals(status)) {
            return bad("Status must be COMPLETED or PENDING.");
        }
        Set<Long> ids = new LinkedHashSet<>();
        if (!(body.get("ids") instanceof List<?> raw) || raw.isEmpty()) return bad("Select at least one donation.");
        if (raw.size() > MAX_IDS) return bad("Too many donations selected at once.");
        for (Object o : raw) {
            Long id = toId(o);
            if (id == null) return bad("Invalid donation id.");
            ids.add(id);
        }

        // All-or-nothing: if any id is not this church's donation, change nothing.
        long owned = donationRepo.countByClientIdAndIdIn(clientId, ids);
        if (owned != ids.size()) {
            return ResponseEntity.status(404).body(Map.of("error", "One or more donations were not found."));
        }

        int changed;
        if (Donation.REVIEW_COMPLETED.equals(status)) {
            LocalDateTime now = LocalDateTime.now(AppClock.ZONE);
            changed = donationRepo.markReviewCompleted(clientId, ids, now, username(request));
        } else {
            changed = donationRepo.markReviewPending(clientId, ids);
        }
        log.info("Donation review: client={} set {} donation(s) to {} ({} requested)",
                clientId, changed, status, ids.size());
        return ResponseEntity.ok(Map.of("status", status, "requested", ids.size(), "updated", changed));
    }

    private static ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static Long toId(Object o) {
        if (o instanceof Number n) {
            long v = n.longValue();
            return (v > 0 && v == n.doubleValue()) ? v : null;
        }
        if (o instanceof String s && s.matches("\\d{1,18}")) {
            long v = Long.parseLong(s);
            return v > 0 ? v : null;
        }
        return null;
    }

    private static String username(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object u = session != null ? session.getAttribute("username") : null;
        if (u == null) return null;
        String s = String.valueOf(u);
        return s.length() > 320 ? s.substring(0, 320) : s;
    }
}
