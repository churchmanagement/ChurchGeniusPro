package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.plaid.entity.PlaidTransactionStaging;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mandatory review workflow. Staged Plaid transactions are listed, edited,
 * and either approved (promoted to the {@code income}/{@code expense} ledger via
 * the existing services) or rejected. Bulk operations are supported.
 *
 * <p>Policy: approval requires a complete category mapping so financial data is
 * never silently misclassified (income → fund + method; expense → category +
 * fund + method; refund → nothing, since it is never posted to either ledger).
 * Rows already approved/rejected are immutable.
 */
@Service
public class PlaidReviewService {

    /**
     * Financial audit M8: the only values {@code direction} may hold. A free-text
     * value used to be stored as-is and fall through to the INCOME branch of
     * {@link #promote} at approval time, whatever it actually said. REFUND is for
     * money in that is not new revenue (most concretely, a vendor/card refund) —
     * approving it does not create an Income row, so it can never inflate a
     * fund's giving total, a pledge campaign's "collected" figure, or a
     * contribution statement the way every dollar in the income ledger otherwise
     * correctly does.
     */
    private static final Set<String> VALID_DIRECTIONS = Set.of("INCOME", "EXPENSE", "REFUND");

    private final PlaidTransactionStagingRepository stagingRepo;
    private final IncomeService incomeService;
    private final ExpenseService expenseService;
    private final TransactionTypeRepository transactionTypeRepo;
    private final PlaidAuditService audit;

    public PlaidReviewService(PlaidTransactionStagingRepository stagingRepo,
                              IncomeService incomeService,
                              ExpenseService expenseService,
                              TransactionTypeRepository transactionTypeRepo,
                              PlaidAuditService audit) {
        this.stagingRepo = stagingRepo;
        this.incomeService = incomeService;
        this.expenseService = expenseService;
        this.transactionTypeRepo = transactionTypeRepo;
        this.audit = audit;
    }

    // ── List ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String clientId, String status, String direction) {
        List<PlaidTransactionStaging> rows = (status != null && !status.isBlank())
                ? stagingRepo.findByClientIdAndStatusOrderByTxnDateDesc(clientId, status.toUpperCase())
                : stagingRepo.findByClientIdOrderByTxnDateDesc(clientId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlaidTransactionStaging r : rows) {
            if (r.isRemoved()) continue;
            if (direction != null && !direction.isBlank()
                    && !direction.equalsIgnoreCase(r.getDirection())) continue;
            out.add(toMap(r));
        }
        return out;
    }

    /** Dropdown data for the review UI. */
    @Transactional(readOnly = true)
    public Map<String, Object> lookups(String clientId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incomeFunds", incomeService.getAllSubSources(clientId));
        m.put("expensePurposes", expenseService.getAllPurposes(clientId));
        m.put("expenseFunds", expenseService.getAllFunds(clientId));
        List<Map<String, Object>> methods = new ArrayList<>();
        for (TransactionType tt : transactionTypeRepo.findActiveByAppUser(clientId)) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", tt.getId());
            mm.put("name", tt.getTypeName());
            methods.add(mm);
        }
        m.put("methods", methods);
        return m;
    }

    // ── Edit ─────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> edit(String clientId, Integer id, Map<String, Object> body, String actor) {
        PlaidTransactionStaging r = requireReviewable(clientId, id);
        if (body.containsKey("amount")) r.setAmount(bd(body.get("amount")));
        if (body.containsKey("direction")) {
            String d = str(body.get("direction"));
            if (d != null) {
                d = d.toUpperCase();
                // Financial audit M8: whitelist, not free text.
                if (!VALID_DIRECTIONS.contains(d)) {
                    throw new IllegalArgumentException(
                            "Unknown transaction type \"" + d + "\" — expected income, expense, or refund.");
                }
                r.setDirection(d);
            }
        }
        if (body.containsKey("description")) r.setDescription(str(body.get("description")));
        if (body.containsKey("txnDate")) r.setTxnDate(parseDate(str(body.get("txnDate"))));
        // Each mapped id must be one of THIS church's own lookup rows — approve() turns
        // them into ledger foreign keys, and a foreign id would link the ledger to
        // another church's fund/purpose/type.
        if (body.containsKey("mappedSubSourceId"))
            r.setMappedSubSourceId(ownedId(intOrNull(body.get("mappedSubSourceId")), incomeService.getAllSubSources(clientId), "sub-source"));
        if (body.containsKey("mappedPurposeId"))
            r.setMappedPurposeId(ownedId(intOrNull(body.get("mappedPurposeId")), expenseService.getAllPurposes(clientId), "purpose"));
        if (body.containsKey("mappedMainSourceId"))
            r.setMappedMainSourceId(ownedId(intOrNull(body.get("mappedMainSourceId")), expenseService.getAllFunds(clientId), "fund"));
        if (body.containsKey("mappedTransactionTypeId")) {
            Integer tt = intOrNull(body.get("mappedTransactionTypeId"));
            if (tt != null && transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(tt, clientId).isEmpty())
                throw new IllegalArgumentException("Unknown transaction type");
            r.setMappedTransactionTypeId(tt);
        }
        // Financial audit M8: ERROR is not a reviewer decision — once a valid
        // amount is supplied (here, or by a later sync repairing it), the row
        // is ordinary PENDING again.
        if ("ERROR".equals(r.getStatus()) && r.getAmount() != null && r.getAmount().signum() > 0) {
            r.setStatus("PENDING");
        }
        r.setUpdatedDate(new Date());
        stagingRepo.save(r);
        audit.record(clientId, actor, "TXN_EDITED", r.getPlaidTransactionId(), "Edited staged transaction " + id);
        return toMap(r);
    }

    // ── Approve / Reject ───────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> approve(String clientId, Integer id, String actor) {
        return approve(clientId, id, actor, false);
    }

    /**
     * @param force skip the soft (date + amount, source-agnostic) duplicate check —
     *              the reviewer has seen that warning and chosen to approve anyway.
     *              Financial audit H8. Never skips the hard check (this exact Plaid
     *              transaction already promoted), which cannot happen in practice
     *              since a staging row is unique per {@code plaid_transaction_id},
     *              but is still enforced defensively in {@code promote}.
     */
    @Transactional
    public Map<String, Object> approve(String clientId, Integer id, String actor, boolean force) {
        PlaidTransactionStaging r = requireReviewable(clientId, id);
        if (r.isPending()) {
            throw new IllegalArgumentException("This transaction is still pending at the bank and cannot be approved yet.");
        }
        // Financial audit M8: never let an unreadable/zero amount, or an
        // unrecognized direction, reach the ledger — checked here, before the
        // atomic claim, so a bad row is never marked APPROVED without actually
        // posting anything.
        if (r.getAmount() == null || r.getAmount().signum() <= 0) {
            throw new IllegalArgumentException(
                    "This transaction's amount could not be read from the bank — enter the correct amount before approving.");
        }
        if (!VALID_DIRECTIONS.contains(r.getDirection() == null ? "" : r.getDirection())) {
            throw new IllegalArgumentException("Please choose income, expense, or refund before approving.");
        }
        claim(clientId, id, "APPROVED", actor, r);
        promote(clientId, r, actor, force);
        audit.record(clientId, actor, "TXN_APPROVED", r.getPlaidTransactionId(),
                r.getDirection() + " " + r.getAmount()
                        + ("REFUND".equalsIgnoreCase(r.getDirection()) ? " → reviewed, excluded from ledger" : " → ledger"));
        return toMap(r);
    }

    @Transactional
    public Map<String, Object> reject(String clientId, Integer id, String actor) {
        PlaidTransactionStaging r = requireReviewable(clientId, id);
        claim(clientId, id, "REJECTED", actor, r);
        audit.record(clientId, actor, "TXN_REJECTED", r.getPlaidTransactionId(), "Rejected staged transaction " + id);
        return toMap(r);
    }

    /**
     * Atomically moves the row from a pre-review status to {@code newStatus} —
     * of two concurrent approve/reject requests for the same row (a
     * double-click, or two reviewers acting on it at once), at most one can
     * ever win this claim. The loser fails here, before {@link #promote} runs,
     * instead of racing it — so a lost race can never create a second ledger
     * row, and a reject can never silently overwrite an approval that already
     * posted (financial audit M6). {@code r} — already loaded by {@link
     * #requireReviewable} — is updated in memory to match what was just
     * written, so the caller's audit log and response reflect the true
     * final state without a second, redundant write; {@code promote} only
     * ever reads content fields off it (amount, mappings, …), never status.
     */
    private void claim(String clientId, Integer id, String newStatus, String actor, PlaidTransactionStaging r) {
        Date reviewedDate = new Date();
        int updated = stagingRepo.claimPending(id, clientId, newStatus, actor, reviewedDate);
        if (updated == 0) {
            String current = stagingRepo.findByIdAndClientId(id, clientId)
                    .map(PlaidTransactionStaging::getStatus).orElse("reviewed");
            throw new IllegalArgumentException(
                    "This transaction has already been " + current.toLowerCase() + " — probably by someone else just now.");
        }
        r.setStatus(newStatus);
        r.setReviewedBy(actor);
        r.setReviewedDate(reviewedDate);
    }

    /** Bulk approve/reject. Returns per-row outcome so partial success is visible. */
    @Transactional
    public Map<String, Object> bulk(String clientId, List<Integer> ids, String action, String actor) {
        int done = 0;
        List<Map<String, Object>> skipped = new ArrayList<>();
        boolean approving = "approve".equalsIgnoreCase(action);
        for (Integer id : ids) {
            try {
                if (approving) approve(clientId, id, actor);
                else reject(clientId, id, actor);
                done++;
            } catch (Exception e) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", id);
                s.put("reason", e.getMessage());
                skipped.add(s);
            }
        }
        audit.record(clientId, actor, approving ? "BULK_APPROVED" : "BULK_REJECTED",
                null, done + " processed, " + skipped.size() + " skipped");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processed", done);
        out.put("skipped", skipped);
        return out;
    }

    // ── Promotion to ledger ────────────────────────────────────────────────────

    private void promote(String clientId, PlaidTransactionStaging r, String actor, boolean force) {
        LocalDate date = r.getTxnDate() != null ? r.getTxnDate() : LocalDate.now();
        BigDecimal amount = r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO;
        // Financial audit H8: a Plaid transaction id is bank-guaranteed unique, so this
        // also lets Bank Import recognize (and refuse to re-post) a transaction that
        // reached the ledger through Plaid, and vice versa via the soft date+amount
        // check on the Bank Import side.
        String importRef = "plaid:" + r.getPlaidTransactionId();
        if ("EXPENSE".equalsIgnoreCase(r.getDirection())) {
            require(r.getMappedPurposeId(), "an expense category");
            require(r.getMappedMainSourceId(), "a fund");
            require(r.getMappedTransactionTypeId(), "a payment method");
            Expense e = expenseService.createExpense(
                    r.getMappedPurposeId(), r.getMappedMainSourceId(), date,
                    r.getMappedTransactionTypeId(), null, amount, r.getDescription(),
                    false, clientId, actor, importRef, force);
            r.setPromotedExpenseId(e.getId());
        } else if ("INCOME".equalsIgnoreCase(r.getDirection())) {
            require(r.getMappedSubSourceId(), "a fund");
            require(r.getMappedTransactionTypeId(), "a payment method");
            Income in = incomeService.createIncome(
                    null, r.getMappedSubSourceId(), date,
                    r.getMappedTransactionTypeId(), null, amount, r.getDescription(),
                    trim(guestLabel(r), 290), false, clientId, actor, importRef, force);
            r.setPromotedIncomeId(in.getId());
        } else if ("REFUND".equalsIgnoreCase(r.getDirection())) {
            // Financial audit M8: money in that is not new revenue (most concretely
            // a vendor/card refund). Reviewed and recorded — see the audit log entry
            // in approve() — without creating an Income row, so it can never inflate
            // a fund's giving total, a pledge's "collected" figure, or a
            // contribution statement. promotedIncomeId/promotedExpenseId stay unset.
        } else {
            // Unreachable in practice — approve() already rejects an unrecognized
            // direction before calling this — kept as a hard backstop so a bad
            // direction can never silently fall through to a ledger post (the
            // original form of this bug, financial audit M8).
            throw new IllegalStateException("Unrecognized transaction direction: " + r.getDirection());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * PENDING and ERROR are both pre-review states — ERROR just additionally
     * means Plaid's amount couldn't be read, so the row also needs a
     * correction before it can be approved (financial audit M8). Only
     * APPROVED/REJECTED are the real, human-made, immutable decisions this
     * guards against editing.
     */
    private PlaidTransactionStaging requireReviewable(String clientId, Integer id) {
        PlaidTransactionStaging r = stagingRepo.findByIdAndClientId(id, clientId).orElse(null);
        if (r == null) throw new IllegalArgumentException("Transaction not found.");
        if (!"PENDING".equals(r.getStatus()) && !"ERROR".equals(r.getStatus())) {
            throw new IllegalArgumentException("This transaction has already been " + r.getStatus().toLowerCase() + ".");
        }
        if (r.isRemoved()) throw new IllegalArgumentException("This transaction was removed by the bank.");
        return r;
    }

    private void require(Integer value, String label) {
        if (value == null) throw new IllegalArgumentException("Please select " + label + " before approving.");
    }

    private String guestLabel(PlaidTransactionStaging r) {
        return r.getMerchantName() != null ? r.getMerchantName() : r.getName();
    }

    private Map<String, Object> toMap(PlaidTransactionStaging r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("direction", r.getDirection());
        m.put("amount", r.getAmount());
        m.put("txnDate", r.getTxnDate() != null ? r.getTxnDate().toString() : null);
        m.put("name", r.getName());
        m.put("merchantName", r.getMerchantName());
        m.put("description", r.getDescription());
        m.put("plaidCategory", r.getPlaidCategory());
        m.put("status", r.getStatus());
        m.put("pending", r.isPending());
        m.put("plaidAccountId", r.getPlaidAccountId());
        m.put("mappedSubSourceId", r.getMappedSubSourceId());
        m.put("mappedPurposeId", r.getMappedPurposeId());
        m.put("mappedMainSourceId", r.getMappedMainSourceId());
        m.put("mappedTransactionTypeId", r.getMappedTransactionTypeId());
        m.put("promotedIncomeId", r.getPromotedIncomeId());
        m.put("promotedExpenseId", r.getPromotedExpenseId());
        return m;
    }

    private String str(Object o) { return o == null ? null : o.toString(); }

    private Integer intOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private BigDecimal bd(Object o) {
        if (o == null) return null;
        try { return new BigDecimal(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return null; }
    }

    private String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** {@code id} when it appears in the tenant's lookup list (or is null); otherwise refuses. */
    private static Integer ownedId(Integer id, List<Map<String, Object>> tenantRows, String what) {
        if (id == null) return null;
        boolean ok = tenantRows.stream().anyMatch(m -> id.equals(toInt(m.get("id"))));
        if (!ok) throw new IllegalArgumentException("Unknown " + what);
        return id;
    }
    private static Integer toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? null : Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return null; }
    }
}
