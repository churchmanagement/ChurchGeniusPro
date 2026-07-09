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

/**
 * The mandatory review workflow. Staged Plaid transactions are listed, edited,
 * and either approved (promoted to the {@code income}/{@code expense} ledger via
 * the existing services) or rejected. Bulk operations are supported.
 *
 * <p>Policy: approval requires a complete category mapping so financial data is
 * never silently misclassified (income → fund + method; expense → category +
 * fund + method). Rows already approved/rejected are immutable.
 */
@Service
public class PlaidReviewService {

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
        PlaidTransactionStaging r = requirePending(clientId, id);
        if (body.containsKey("amount")) r.setAmount(bd(body.get("amount")));
        if (body.containsKey("direction")) {
            String d = str(body.get("direction"));
            if (d != null) r.setDirection(d.toUpperCase());
        }
        if (body.containsKey("description")) r.setDescription(str(body.get("description")));
        if (body.containsKey("txnDate")) r.setTxnDate(parseDate(str(body.get("txnDate"))));
        if (body.containsKey("mappedSubSourceId")) r.setMappedSubSourceId(intOrNull(body.get("mappedSubSourceId")));
        if (body.containsKey("mappedPurposeId")) r.setMappedPurposeId(intOrNull(body.get("mappedPurposeId")));
        if (body.containsKey("mappedMainSourceId")) r.setMappedMainSourceId(intOrNull(body.get("mappedMainSourceId")));
        if (body.containsKey("mappedTransactionTypeId")) r.setMappedTransactionTypeId(intOrNull(body.get("mappedTransactionTypeId")));
        r.setUpdatedDate(new Date());
        stagingRepo.save(r);
        audit.record(clientId, actor, "TXN_EDITED", r.getPlaidTransactionId(), "Edited staged transaction " + id);
        return toMap(r);
    }

    // ── Approve / Reject ───────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> approve(String clientId, Integer id, String actor) {
        PlaidTransactionStaging r = requirePending(clientId, id);
        if (r.isPending()) {
            throw new IllegalArgumentException("This transaction is still pending at the bank and cannot be approved yet.");
        }
        promote(clientId, r, actor);
        r.setStatus("APPROVED");
        r.setReviewedBy(actor);
        r.setReviewedDate(new Date());
        stagingRepo.save(r);
        audit.record(clientId, actor, "TXN_APPROVED", r.getPlaidTransactionId(),
                r.getDirection() + " " + r.getAmount() + " → ledger");
        return toMap(r);
    }

    @Transactional
    public Map<String, Object> reject(String clientId, Integer id, String actor) {
        PlaidTransactionStaging r = requirePending(clientId, id);
        r.setStatus("REJECTED");
        r.setReviewedBy(actor);
        r.setReviewedDate(new Date());
        stagingRepo.save(r);
        audit.record(clientId, actor, "TXN_REJECTED", r.getPlaidTransactionId(), "Rejected staged transaction " + id);
        return toMap(r);
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

    private void promote(String clientId, PlaidTransactionStaging r, String actor) {
        LocalDate date = r.getTxnDate() != null ? r.getTxnDate() : LocalDate.now();
        BigDecimal amount = r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO;
        if ("EXPENSE".equalsIgnoreCase(r.getDirection())) {
            require(r.getMappedPurposeId(), "an expense category");
            require(r.getMappedMainSourceId(), "a fund");
            require(r.getMappedTransactionTypeId(), "a payment method");
            Expense e = expenseService.createExpense(
                    r.getMappedPurposeId(), r.getMappedMainSourceId(), date,
                    r.getMappedTransactionTypeId(), null, amount, r.getDescription(),
                    false, clientId, actor);
            r.setPromotedExpenseId(e.getId());
        } else {
            require(r.getMappedSubSourceId(), "a fund");
            require(r.getMappedTransactionTypeId(), "a payment method");
            Income in = incomeService.createIncome(
                    null, r.getMappedSubSourceId(), date,
                    r.getMappedTransactionTypeId(), null, amount, r.getDescription(),
                    trim(guestLabel(r), 290), false, clientId, actor);
            r.setPromotedIncomeId(in.getId());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private PlaidTransactionStaging requirePending(String clientId, Integer id) {
        PlaidTransactionStaging r = stagingRepo.findByIdAndClientId(id, clientId).orElse(null);
        if (r == null) throw new IllegalArgumentException("Transaction not found.");
        if (!"PENDING".equals(r.getStatus())) {
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
}
