package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.service.ChurchStripeGateway;
import com.churchgeniuspro.service.DonationFeeService;
import com.churchgeniuspro.service.DonationPostingService;
import com.churchgeniuspro.service.DonationSettingsService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Donation Review — posting reviewed online donations to accounting.
 *
 * <ul>
 *   <li>{@code GET  /api/donations/{id}/preview}   — what posting would write (Review &amp; Save)</li>
 *   <li>{@code POST /api/donations/{id}/post}      — post one, with optional reviewer overrides</li>
 *   <li>{@code POST /api/donations/post-batch}     — post several with the configured defaults; per-id outcomes</li>
 *   <li>{@code POST /api/donations/{id}/refresh-fee} — ask Stripe for the actual fee (settled ACH)</li>
 *   <li>{@code GET  /api/donations/reconciliation} — read-only: which donations have ledger rows, and from which workflow</li>
 * </ul>
 *
 * <p>Access is the Donation Review page's: Accountant/Admin plus {@code accounting.donation}.
 * Every lookup carries the session's church; an id from another church is "not found".
 * The client never sends an amount or a fee — see {@link DonationPostingService}.
 */
@RestController
public class DonationPostingController {

    private static final Logger log = LoggerFactory.getLogger(DonationPostingController.class);

    /** Upper bound on ids per batch — same as the review-status endpoint. */
    static final int MAX_IDS = DonationReviewController.MAX_IDS;

    private final DonationPostingService posting;
    private final DonationFeeService     fees;
    private final DonationRepository     donationRepo;
    private final IncomeRepository       incomeRepo;
    private final ExpenseRepository      expenseRepo;

    public DonationPostingController(DonationPostingService posting,
                                     DonationFeeService fees,
                                     DonationRepository donationRepo,
                                     IncomeRepository incomeRepo,
                                     ExpenseRepository expenseRepo) {
        this.posting      = posting;
        this.fees         = fees;
        this.donationRepo = donationRepo;
        this.incomeRepo   = incomeRepo;
        this.expenseRepo  = expenseRepo;
    }

    @GetMapping("/api/donations/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable("id") Long id, HttpServletRequest request) {
        Gate g = gate(request); if (g.response != null) return g.response;
        try {
            return ResponseEntity.ok(posting.preview(id, g.clientId, DonationPostingService.Overrides.NONE).describe());
        } catch (RuntimeException ex) { return mapError(ex); }
    }

    @PostMapping("/api/donations/{id}/post")
    public ResponseEntity<?> post(@PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body,
                                  HttpServletRequest request) {
        Gate g = gate(request); if (g.response != null) return g.response;
        try {
            DonationPostingService.Overrides o = DonationPostingService.Overrides.fromBody(body);
            DonationPostingService.PostResult r = posting.post(id, g.clientId, o, username(request));
            return ResponseEntity.ok(r.describe());
        } catch (RuntimeException ex) { return mapError(ex); }
    }

    @PostMapping("/api/donations/post-batch")
    public ResponseEntity<?> postBatch(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Gate g = gate(request); if (g.response != null) return g.response;
        if (body == null || !(body.get("ids") instanceof List<?> raw) || raw.isEmpty()) {
            return bad("Select at least one donation.");
        }
        if (raw.size() > MAX_IDS) return bad("Too many donations selected at once.");
        Set<Long> ids = new LinkedHashSet<>();
        for (Object o : raw) {
            Long v = toId(o);
            if (v == null) return bad("Invalid donation id.");
            ids.add(v);
        }
        // All-or-nothing on ownership, like the review-status endpoint: an id from
        // another church refuses the whole request before anything is posted.
        long owned = donationRepo.countByClientIdAndIdIn(g.clientId, ids);
        if (owned != ids.size()) {
            return ResponseEntity.status(404).body(Map.of("error", "One or more donations were not found."));
        }
        List<DonationPostingService.PostResult> results = posting.postBatch(ids, g.clientId, username(request));
        int posted = 0, already = 0, failed = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DonationPostingService.PostResult r : results) {
            rows.add(r.describe());
            switch (r.status()) {
                case DonationPostingService.PostResult.POSTED -> posted++;
                case DonationPostingService.PostResult.ALREADY_POSTED -> already++;
                default -> failed++;
            }
        }
        log.info("Donation batch post: client={} requested={} posted={} already={} failed={}",
                g.clientId, ids.size(), posted, already, failed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", ids.size()); out.put("posted", posted);
        out.put("alreadyPosted", already); out.put("failed", failed);
        out.put("results", rows);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/donations/{id}/refresh-fee")
    public ResponseEntity<?> refreshFee(@PathVariable("id") Long id, HttpServletRequest request) {
        Gate g = gate(request); if (g.response != null) return g.response;
        Donation d = donationRepo.findByIdAndClientId(id, g.clientId).orElse(null);
        if (d == null) return ResponseEntity.status(404).body(Map.of("error", "Donation not found."));
        DonationFeeService.Refresh r = fees.refresh(d);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("donationId", d.getId());
        out.put("refreshed", r.found());
        out.put("status", r.status().name());
        out.put("stripeFee", d.getStripeFee());
        out.put("stripeNet", d.getStripeNet());
        out.put("feeSource", d.getFeeSource());
        out.put("message", r.message());
        return ResponseEntity.ok(out);
    }

    /**
     * Read-only (approval decision 7): classifies every donation of this church by
     * whether ledger rows exist for it and which workflow created them. Reads
     * {@code donation}, {@code income} and {@code expense}; writes nothing.
     */
    @GetMapping("/api/donations/reconciliation")
    public ResponseEntity<?> reconciliation(HttpServletRequest request) {
        Gate g = gate(request); if (g.response != null) return g.response;
        List<Donation> donations = donationRepo.findByClientIdOrderByReviewStatusAscDonatedAtDesc(g.clientId);

        List<String> refs = new ArrayList<>();
        for (Donation d : donations) refs.add(DonationPostingService.ledgerRef(d));
        Map<String, Income> incomeByRef = new HashMap<>();
        Map<String, Expense> expenseByRef = new HashMap<>();
        if (!refs.isEmpty()) {
            for (Income i : incomeRepo.findByAppClientIdAndImportRefInAndDeleteFlagFalse(g.clientId, refs)) incomeByRef.put(i.getImportRef(), i);
            List<String> feeRefs = refs.stream().map(r -> r + DonationPostingService.FEE_REF_SUFFIX).toList();
            for (Expense e : expenseRepo.findByAppClientIdAndImportRefInAndDeleteFlagFalse(g.clientId, feeRefs)) expenseByRef.put(e.getImportRef(), e);
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Donation d : donations) {
            Income inc = incomeByRef.get(DonationPostingService.ledgerRef(d));
            Expense exp = expenseByRef.get(DonationPostingService.feeImportRef(d));
            String bucket;
            if (d.isPosted())                          bucket = "POSTED_BY_REVIEW";
            else if (inc != null)                      bucket = "POSTED_HISTORICAL";     // the former automatic hook
            else if (d.isReviewCompleted())            bucket = "REVIEWED_NO_LEDGER";    // marked Completed by hand, no row
            else                                       bucket = "PENDING";

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("donationId", d.getId());
            m.put("donor", DonationPostingService.donorName(d));
            m.put("amount", d.getAmount());
            m.put("donatedAt", d.getDonatedAt() != null ? d.getDonatedAt().toString() : null);
            m.put("paymentMethod", d.getPaymentMethod());
            m.put("paymentIntentId", d.getStripePaymentIntentId());
            m.put("reviewStatus", d.isReviewCompleted() ? Donation.REVIEW_COMPLETED : "PENDING");
            m.put("bucket", bucket);
            m.put("incomeId", inc != null ? inc.getId() : d.getPostedIncomeId());
            m.put("incomeAmount", inc != null ? inc.getAmount() : null);
            m.put("incomeMethod", inc != null && inc.getTransactionType() != null ? inc.getTransactionType().getTypeName() : null);
            m.put("incomeCreatedBy", inc != null ? inc.getCreatedBy() : null);
            m.put("expenseId", exp != null ? exp.getId() : d.getPostedExpenseId());
            m.put("expenseAmount", exp != null ? exp.getAmount() : null);
            m.put("stripeFee", d.getStripeFee());
            m.put("feeSource", d.getFeeSource());
            // A card label used as the method on a historical row — the pollution the clean-up would address.
            m.put("cardLabelMethod", inc != null && inc.getTransactionType() != null
                    && inc.getTransactionType().getTypeName() != null
                    && inc.getTransactionType().getTypeName().contains("••••"));
            rows.add(m);
            counts.merge(bucket, 1, Integer::sum);
            amounts.merge(bucket, d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO, BigDecimal::add);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("readOnly", true);
        out.put("total", donations.size());
        out.put("counts", counts);
        out.put("amounts", amounts);
        out.put("rows", rows);
        return ResponseEntity.ok(out);
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private record Gate(String clientId, ResponseEntity<?> response) {}

    private static Gate gate(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.donation");
        if (deny != null) return new Gate(null, ResponseEntity.status(403).body(Map.of("error", "Access denied.")));
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return new Gate(null, ResponseEntity.status(401).body(Map.of("error", "Please sign in again.")));
        return new Gate(clientId, null);
    }

    /** One place that turns the service's exceptions into the statuses the page acts on. */
    static ResponseEntity<?> mapError(RuntimeException ex) {
        if (ex instanceof DonationSettingsService.IncompleteSettingsException inc) {
            return ResponseEntity.status(422).body(Map.of("error", inc.getMessage(), "code", "SETTINGS_INCOMPLETE", "missing", inc.getMissing()));
        }
        if (ex instanceof DonationFeeService.FeeUnavailableException) {
            return ResponseEntity.status(422).body(Map.of("error", ex.getMessage(), "code", "FEE_UNAVAILABLE"));
        }
        if (ex instanceof DonationPostingService.UnsupportedDonationException) {
            return ResponseEntity.status(422).body(Map.of("error", ex.getMessage(), "code", "UNSUPPORTED"));
        }
        if (ex instanceof DonationPostingService.NotFoundException) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
        if (ex instanceof DonationPostingService.InvalidReviewException
                || ex instanceof DonationSettingsService.InvalidSettingsException
                || ex instanceof IllegalArgumentException) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        log.error("Donation posting failed: {}", ex.toString());
        return ResponseEntity.status(500).body(Map.of("error", "The donation could not be posted. Nothing was saved."));
    }

    private static ResponseEntity<?> bad(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }

    private static Long toId(Object o) {
        if (o instanceof Number n) { long v = n.longValue(); return (v > 0 && v == n.doubleValue()) ? v : null; }
        if (o instanceof String s && s.matches("\\d{1,18}")) { long v = Long.parseLong(s); return v > 0 ? v : null; }
        return null;
    }

    private static String username(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object u = session != null ? session.getAttribute("username") : null;
        return u == null ? null : String.valueOf(u);
    }
}
