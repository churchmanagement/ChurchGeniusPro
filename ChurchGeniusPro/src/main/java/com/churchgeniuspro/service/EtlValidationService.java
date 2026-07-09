package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * ETL Phase 4b — Validate. Grades every staged row VALID / WARN / ERROR and
 * records why, merging with any transform errors from the stage step. Checks:
 * required fields, email sanity, tenant-scoped family dedupe (email, then
 * name+phone) against LIVE members, and family-link resolution for income/expense
 * (against staged families + existing members). All reads are tenant-scoped; no
 * live data is modified — dedupe only records a {@code dedupe_match_id} + default
 * {@code SKIP} action for the operator to review.
 */
@Service
public class EtlValidationService {

    private static final Logger log = LoggerFactory.getLogger(EtlValidationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final StagingFamilyRepository  familyRepo;
    private final StagingIncomeRepository  incomeRepo;
    private final StagingExpenseRepository expenseRepo;
    private final FamilyMemberRepository   memberRepo;
    private final ImportRunService         runService;

    public EtlValidationService(StagingFamilyRepository familyRepo,
                                StagingIncomeRepository incomeRepo,
                                StagingExpenseRepository expenseRepo,
                                FamilyMemberRepository memberRepo,
                                ImportRunService runService) {
        this.familyRepo  = familyRepo;
        this.incomeRepo  = incomeRepo;
        this.expenseRepo = expenseRepo;
        this.memberRepo  = memberRepo;
        this.runService  = runService;
    }

    public record ValidationSummary(String targetTable, int total, int valid, int warn, int error) {}

    @Transactional
    public ValidationSummary validate(Long runId, String clientId, String actor, String targetTable) {
        ValidationSummary summary = switch (targetTable) {
            case "family"  -> validateFamily(runId, clientId);
            case "income"  -> validateMoney(runId, clientId, "income");
            case "expense" -> validateMoney(runId, clientId, "expense");
            default -> throw new IllegalArgumentException("Unknown target table: " + targetTable);
        };
        runService.recordAudit(runId, clientId, actor, "VALIDATE", Map.of(
            "targetTable", summary.targetTable(),
            "total", summary.total(),
            "valid", summary.valid(),
            "warn", summary.warn(),
            "error", summary.error()));
        log.info("ETL run {} validated {} {} rows: {} valid, {} warn, {} error",
            runId, summary.total(), targetTable, summary.valid(), summary.warn(), summary.error());
        return summary;
    }

    // ───────────────────────── family ─────────────────────────

    private ValidationSummary validateFamily(Long runId, String clientId) {
        List<StagingFamily> rows = familyRepo.findByRunId(runId);

        // Tenant dedupe index from LIVE members.
        Map<String, Integer> byEmail = new HashMap<>();
        Map<String, Integer> byNamePhone = new HashMap<>();
        for (FamilyMember m : memberRepo.findActiveByTenantForEtl(clientId)) {
            if (m.getEmail() != null && !m.getEmail().isBlank())
                byEmail.putIfAbsent(m.getEmail().trim().toLowerCase(), m.getId());
            String np = namePhoneKey(m.getFirstName(), m.getLastName(), m.getPhone());
            if (np != null) byNamePhone.putIfAbsent(np, m.getId());
        }

        int valid = 0, warn = 0, error = 0;
        for (StagingFamily r : rows) {
            List<Map<String, String>> issues = existing(r.getValidationMsgs());

            boolean hasName = notBlank(r.getFirstName()) || notBlank(r.getLastName());
            if (!hasName) issues.add(issue("firstName", "ERROR", "A first or last name is required"));

            if (notBlank(r.getEmail()) && !EMAIL.matcher(r.getEmail().trim()).matches())
                issues.add(issue("email", "WARN", "Email does not look valid: '" + r.getEmail() + "'"));

            // Dedupe (email first, then name+phone).
            Integer match = null; String how = null;
            if (notBlank(r.getEmail())) {
                match = byEmail.get(r.getEmail().trim().toLowerCase());
                if (match != null) how = "email";
            }
            if (match == null) {
                String np = namePhoneKey(r.getFirstName(), r.getLastName(), r.getPhone());
                if (np != null) { match = byNamePhone.get(np); if (match != null) how = "name + phone"; }
            }
            if (match != null) {
                r.setDedupeMatchId(match.longValue());
                if (r.getDedupeAction() == null) r.setDedupeAction("SKIP");
                issues.add(issue("_row", "WARN",
                    "Possible duplicate of existing member #" + match + " (matched by " + how + "); default action SKIP"));
            } else {
                r.setDedupeAction(r.getDedupeAction() == null ? "INSERT" : r.getDedupeAction());
            }

            String status = grade(issues);
            r.setRowStatus(status);
            r.setValidationMsgs(issues.isEmpty() ? null : toJson(issues));
            familyRepo.save(r);
            if ("ERROR".equals(status)) error++; else if ("WARN".equals(status)) warn++; else valid++;
        }
        return new ValidationSummary("family", rows.size(), valid, warn, error);
    }

    // ───────────────────────── income / expense ─────────────────────────

    private ValidationSummary validateMoney(Long runId, String clientId, String table) {
        // Resolvable family keys = staged families for this run + existing member keys.
        Set<String> resolvable = new HashSet<>();
        for (StagingFamily f : familyRepo.findByRunId(runId))
            if (notBlank(f.getFamilyKey())) resolvable.add(f.getFamilyKey().trim());
        for (FamilyMember m : memberRepo.findActiveByTenantForEtl(clientId)) {
            if (m.getId() != null) resolvable.add(String.valueOf(m.getId()));
            if (notBlank(m.getMemberRef())) resolvable.add(m.getMemberRef().trim());
        }

        int valid = 0, warn = 0, error = 0, total;
        if ("income".equals(table)) {
            List<StagingIncome> rows = incomeRepo.findByRunId(runId);
            total = rows.size();
            for (StagingIncome r : rows) {
                List<Map<String, String>> issues = existing(r.getValidationMsgs());
                checkAmount(issues, r.getAmount());
                if (r.getIncomeDate() == null) issues.add(issue("incomeDate", "WARN", "Missing date"));
                resolveLink(issues, r.getFamilyLink(), resolvable);
                String status = grade(issues);
                r.setRowStatus(status);
                r.setValidationMsgs(issues.isEmpty() ? null : toJson(issues));
                incomeRepo.save(r);
                if ("ERROR".equals(status)) error++; else if ("WARN".equals(status)) warn++; else valid++;
            }
        } else {
            List<StagingExpense> rows = expenseRepo.findByRunId(runId);
            total = rows.size();
            for (StagingExpense r : rows) {
                List<Map<String, String>> issues = existing(r.getValidationMsgs());
                checkAmount(issues, r.getAmount());
                if (r.getExpenseDate() == null) issues.add(issue("expenseDate", "WARN", "Missing date"));
                resolveLink(issues, r.getFamilyLink(), resolvable);
                String status = grade(issues);
                r.setRowStatus(status);
                r.setValidationMsgs(issues.isEmpty() ? null : toJson(issues));
                expenseRepo.save(r);
                if ("ERROR".equals(status)) error++; else if ("WARN".equals(status)) warn++; else valid++;
            }
        }
        return new ValidationSummary(table, total, valid, warn, error);
    }

    private void checkAmount(List<Map<String, String>> issues, BigDecimal amount) {
        if (amount == null) issues.add(issue("amount", "ERROR", "Amount is required"));
        else if (amount.signum() <= 0) issues.add(issue("amount", "ERROR", "Amount must be greater than zero"));
    }

    private void resolveLink(List<Map<String, String>> issues, String link, Set<String> resolvable) {
        if (!notBlank(link)) return;   // unlinked money is allowed
        if (!resolvable.contains(link.trim()))
            issues.add(issue("familyLink", "WARN",
                "Could not resolve family link '" + link + "' to a member or staged family; will load unlinked"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }

    private String namePhoneKey(String first, String last, String phone) {
        String f = first == null ? "" : first.trim().toLowerCase();
        String l = last  == null ? "" : last.trim().toLowerCase();
        String p = phone == null ? "" : phone.replaceAll("\\D", "");
        if ((f + l).isBlank() || p.isEmpty()) return null;
        return f + "|" + l + "|" + p;
    }

    private String grade(List<Map<String, String>> issues) {
        boolean err = issues.stream().anyMatch(i -> "ERROR".equals(i.get("level")));
        if (err) return "ERROR";
        boolean w = issues.stream().anyMatch(i -> "WARN".equals(i.get("level")));
        return w ? "WARN" : "VALID";
    }

    private List<Map<String, String>> existing(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, String> issue(String field, String level, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("level", level);
        m.put("message", message);
        return m;
    }

    private String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }
}
