package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.MemberAccountDeletionService;
import com.churchgeniuspro.service.MemberSafetyService;
import com.churchgeniuspro.service.RememberMeService;
import com.churchgeniuspro.service.SecurityAuditService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Member-portal endpoints for mobile Phase 2:
 * <ul>
 *   <li>M5 — {@code GET /api/member/blocks}, {@code POST|DELETE /api/member/blocks/{memberId}},
 *       {@code POST /api/member/messages/{id}/report}</li>
 *   <li>M4 — {@code POST /api/member/account/delete} {@code { password }}</li>
 * </ul>
 * All require a real Member session ({@code role=Member} with a {@code memberId}); the
 * church is always the session's, never a request parameter. Kids-portal (child),
 * temporary-access and NTAG sessions are refused.
 */
@RestController
public class MemberSafetyController {

    /** One daemon thread: confirmation mail is rare and its failure is only logged. */
    private static final java.util.concurrent.ExecutorService CONFIRMATION_MAIL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "member-deletion-mail");
                t.setDaemon(true);
                return t;
            });

    private final MemberSafetyService          safety;
    private final MemberAccountDeletionService deletion;
    private final RememberMeService            rememberMe;
    private final SecurityAuditService         audit;

    public MemberSafetyController(MemberSafetyService safety, MemberAccountDeletionService deletion,
                                  RememberMeService rememberMe, SecurityAuditService audit) {
        this.safety = safety; this.deletion = deletion; this.rememberMe = rememberMe; this.audit = audit;
    }

    // ── session helpers ───────────────────────────────────────────────────────

    /** The signed-in member, or null when this is not a (non-child) member session. */
    private static Member memberOf(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null) return null;
        if (!"Member".equals(s.getAttribute("role"))) return null;
        if (!(s.getAttribute("memberId") instanceof Number n)) return null;
        if (RoleGuard.isKidsPortalSession(s)) return null;
        if (s.getAttribute("tempAccessId") != null || s.getAttribute("ntagCredId") != null) return null;
        Object app = s.getAttribute("appClientId");
        String appClientId = app instanceof String str && !str.isBlank() ? str : null;
        if (appClientId == null) return null;
        return new Member(n.intValue(), appClientId, String.valueOf(s.getAttribute("clientId")),
                          String.valueOf(s.getAttribute("username")));
    }

    private record Member(int id, String appClientId, String memberRef, String username) {}

    private static ResponseEntity<Map<String, Object>> notMember() {
        return ResponseEntity.status(401).body(Map.of("error", "Not a member account"));
    }

    // ── M5: blocks ────────────────────────────────────────────────────────────

    @GetMapping("/api/member/blocks")
    public ResponseEntity<?> listBlocks(HttpServletRequest request) {
        Member me = memberOf(request);
        if (me == null) return notMember();
        return ResponseEntity.ok(safety.listBlocks(me.appClientId(), me.id()));
    }

    @PostMapping("/api/member/blocks/{memberId}")
    public ResponseEntity<?> block(@PathVariable Integer memberId, HttpServletRequest request) {
        Member me = memberOf(request);
        if (me == null) return notMember();
        return safety.block(me.appClientId(), me.id(), memberId)
                .<ResponseEntity<?>>map(b -> ResponseEntity.ok(Map.of("success", true, "blocked", b)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Member not found.")));
    }

    @DeleteMapping("/api/member/blocks/{memberId}")
    public ResponseEntity<?> unblock(@PathVariable Integer memberId, HttpServletRequest request) {
        Member me = memberOf(request);
        if (me == null) return notMember();
        boolean removed = safety.unblock(me.appClientId(), me.id(), memberId);
        return ResponseEntity.ok(Map.of("success", true, "removed", removed));
    }

    // ── M5: report ────────────────────────────────────────────────────────────

    @PostMapping("/api/member/messages/{id}/report")
    public ResponseEntity<?> report(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        Member me = memberOf(request);
        if (me == null) return notMember();
        String reason  = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        String details = body == null || body.get("details") == null ? null : String.valueOf(body.get("details"));
        MemberSafetyService.ReportOutcome out = safety.reportMessage(me.appClientId(), me.id(), id, reason, details);
        if (!out.ok()) return ResponseEntity.status(out.status()).body(Map.of("error", out.error()));
        return ResponseEntity.ok(Map.of("success", true, "reportId", out.reportId() == null ? 0 : out.reportId()));
    }

    // ── M4: delete my account ─────────────────────────────────────────────────

    @PostMapping("/api/member/account/delete")
    public ResponseEntity<?> deleteAccount(@RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request, HttpServletResponse response) {
        Member me = memberOf(request);
        if (me == null) return ResponseEntity.status(403).body(Map.of("error", "Only a member account can be deleted here."));
        String password = body == null || body.get("password") == null ? "" : String.valueOf(body.get("password"));
        boolean confirmed = body != null && Boolean.TRUE.equals(body.get("confirm"));
        if (!confirmed) return ResponseEntity.badRequest().body(Map.of("error", "Please confirm that you understand what will be deleted."));

        MemberAccountDeletionService.Outcome out =
                deletion.deleteOwnAccount(me.memberRef(), me.id(), me.appClientId(), me.username(), password);
        if (!out.ok()) return ResponseEntity.status(out.status()).body(Map.of("error", out.error()));

        // Sign the device out everywhere it can be: session, remember-me cookie.
        HttpSession session = request.getSession(false);
        if (session != null) {
            try { audit.recordLogout(request, session); } catch (Exception ignored) { /* audit never blocks */ }
            session.invalidate();
        }
        rememberMe.clearCookie(response);
        // The deletion is committed; the confirmation email must never hold the response
        // (an SMTP timeout would otherwise keep the member staring at "Deleting…").
        String toEmail = out.memberEmail(), name = out.memberName(), church = me.appClientId();
        CONFIRMATION_MAIL.execute(() -> deletion.sendConfirmation(toEmail, name, church));
        return ResponseEntity.ok(Map.of("success", true, "message", "Your account has been deleted."));
    }
}
