package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.churchgeniuspro.util.SourcePreset;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 *
 * <p>Migration Phase 2: matching moved to {@link EtlMatchService} with tiers.
 * EXACT / STRONG / IN_FILE → WARN + default SKIP (the admin may pick UPDATE, MERGE
 * for people, or import as new); POSSIBLE → row status {@code REVIEW}, which blocks
 * approval until the admin decides; NONE → INSERT. Money rows also resolve their
 * person by the source system's id (members migrated from the same product), by
 * member id / member ref, or by staged household key.
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
    private final EtlMatchService          matcher;
    private final TransformStageService    stageService;
    /** Phase 3: links learned by earlier runs (optional; absent = no cross-run links). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtlLinkService linkService;
    public void setLinkService(EtlLinkService s) { this.linkService = s; }

    @org.springframework.beans.factory.annotation.Autowired
    public EtlValidationService(StagingFamilyRepository familyRepo,
                                StagingIncomeRepository incomeRepo,
                                StagingExpenseRepository expenseRepo,
                                FamilyMemberRepository memberRepo,
                                ImportRunService runService,
                                EtlMatchService matcher,
                                TransformStageService stageService) {
        this.familyRepo  = familyRepo;
        this.incomeRepo  = incomeRepo;
        this.expenseRepo = expenseRepo;
        this.memberRepo  = memberRepo;
        this.runService  = runService;
        this.matcher     = matcher;
        this.stageService = stageService;
    }

    /** Compatibility constructor (tests predating Phase 2): generic matcher, no file-id lookup. */
    public EtlValidationService(StagingFamilyRepository familyRepo,
                                StagingIncomeRepository incomeRepo,
                                StagingExpenseRepository expenseRepo,
                                FamilyMemberRepository memberRepo,
                                ImportRunService runService) {
        this(familyRepo, incomeRepo, expenseRepo, memberRepo, runService,
             new EtlMatchService(memberRepo, null, null), null);
    }

    public record ValidationSummary(String targetTable, int total, int valid, int warn, int error, int review,
                                    int newRows, int exact, int strong, int possible, int inFile) {
        public ValidationSummary(String targetTable, int total, int valid, int warn, int error) {
            this(targetTable, total, valid, warn, error, 0, 0, 0, 0, 0, 0);
        }
    }

    /** Phase 4: validation of the record data types (pledges, events, groups, kids …); optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtlRecordService recordService;
    public void setRecordService(EtlRecordService s) { this.recordService = s; }

    @Transactional
    public ValidationSummary validate(Long runId, String clientId, String actor, String targetTable) {
        if (com.churchgeniuspro.util.EtlTargetSchema.isRecordTable(targetTable) && recordService != null)
            return recordService.validate(runId, clientId, actor, targetTable);   // records its own VALIDATE audit
        ValidationSummary summary = switch (targetTable) {
            case "family"  -> validateFamily(runId, clientId);
            case "income"  -> validateMoney(runId, clientId, "income");
            case "expense" -> validateMoney(runId, clientId, "expense");
            default -> throw new IllegalArgumentException("Unknown target table: " + targetTable);
        };
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("targetTable", summary.targetTable());
        detail.put("total", summary.total()); detail.put("valid", summary.valid());
        detail.put("warn", summary.warn()); detail.put("error", summary.error()); detail.put("review", summary.review());
        detail.put("new", summary.newRows()); detail.put("exact", summary.exact()); detail.put("strong", summary.strong());
        detail.put("possible", summary.possible()); detail.put("inFile", summary.inFile());
        runService.recordAudit(runId, clientId, actor, "VALIDATE", detail);
        log.info("ETL run {} validated {} {} rows: {} valid, {} warn, {} error",
            runId, summary.total(), targetTable, summary.valid(), summary.warn(), summary.error());
        return summary;
    }

    // ───────────────────────── family ─────────────────────────

    private ValidationSummary validateFamily(Long runId, String clientId) {
        List<StagingFamily> rows = familyRepo.findByRunId(runId);
        ImportRun run = runService.getRun(runId, clientId);
        String system = SourcePreset.of(run.getSourcePreset()).name();
        boolean hasIds = stageService != null && stageService.hasSourceIdsForTarget(runId, "family");
        EtlMatchService.FamilyIndex index = matcher.familyIndex(clientId, hasIds ? system : null);

        // Phase 3: propose households for rows the file gave no household of their own
        // (computed before grading so each row is saved once, below).
        proposeHouseholds(rows);

        Tally t = new Tally();
        for (StagingFamily r : rows) {
            List<Map<String, String>> issues = existing(r.getValidationMsgs());

            boolean hasName = notBlank(r.getFirstName()) || notBlank(r.getLastName());
            if (!hasName) issues.add(issue("firstName", "ERROR", "A first or last name is required"));

            if (notBlank(r.getEmail()) && !EMAIL.matcher(r.getEmail().trim()).matches())
                issues.add(issue("email", "WARN", "Email does not look valid: '" + r.getEmail() + "'"));

            if (r.getHouseholdProposal() != null)
                issues.add(issue("_household", "INFO", "Same last name and address as other rows in this file — proposed household"
                    + ("ACCEPTED".equals(r.getHouseholdDecision()) ? " (accepted)" : "REJECTED".equals(r.getHouseholdDecision()) ? " (rejected)" : " (undecided: stays separate)")));

            EtlMatchService.Match m = index.match(r);
            applyMatch(r.getDedupeAction(), m, issues, "member",
                (tier) -> r.setMatchTier(tier), (how) -> r.setMatchDetail(how),
                (id) -> r.setDedupeMatchId(id), (a) -> r.setDedupeAction(a));

            String status = grade(issues, m, r.getDedupeAction());
            r.setRowStatus(status);
            r.setValidationMsgs(issues.isEmpty() ? null : toJson(issues));
            familyRepo.save(r);
            t.count(status, m);
        }
        return t.summary("family", rows.size());
    }

    // ───────────────────────── Phase 3: household proposals ─────────────────────────

    /**
     * Approved decision 2 — households are PROPOSED, never assumed. A row is eligible when
     * the file gave it no household of its own (its key is just its own row id) and it has
     * a street address. Rows sharing normalised last name + street + (zip or city) form one
     * proposal; a proposal needs at least two rows. Existing decisions are kept when the
     * proposal key is unchanged (re-staging recomputes keys, so a changed group is undecided
     * again). Nothing is merged until the admin ACCEPTs.
     *
     * @return number of rows that received a proposal
     */
    static int proposeHouseholds(List<StagingFamily> rows) {
        Map<String, List<StagingFamily>> groups = new LinkedHashMap<>();
        for (StagingFamily r : rows) {
            String prior = r.getHouseholdProposal(), priorDecision = r.getHouseholdDecision();
            r.setHouseholdProposal(null);
            boolean ownKey = r.getFamilyKey() == null || r.getSourceRowId() == null || r.getFamilyKey().trim().equals(r.getSourceRowId().trim());
            if (!ownKey || !notBlank(r.getLastName()) || !notBlank(r.getAddress1())) { r.setHouseholdDecision(null); continue; }
            String locality = notBlank(r.getPinCode()) ? norm(r.getPinCode()) : norm(r.getCity());
            if (locality.isEmpty()) { r.setHouseholdDecision(null); continue; }
            String key = norm(r.getLastName()) + "|" + normAddress(r.getAddress1()) + "|" + locality;
            if (key.length() > 160) key = key.substring(0, 160);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            // remember the prior state on the row until we know whether the group survives
            r.setHouseholdProposal(key);
            if (!key.equals(prior)) r.setHouseholdDecision(null); else r.setHouseholdDecision(priorDecision);
        }
        int n = 0;
        for (Map.Entry<String, List<StagingFamily>> e : groups.entrySet()) {
            if (e.getValue().size() < 2) { for (StagingFamily r : e.getValue()) { r.setHouseholdProposal(null); r.setHouseholdDecision(null); } continue; }
            n += e.getValue().size();
        }
        return n;
    }

    /** Lower-case alphanumerics only, so "123 Main St." and "123 main st" agree. */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static final Map<String, String> STREET_WORDS = Map.ofEntries(
        Map.entry("street", "st"), Map.entry("avenue", "ave"), Map.entry("av", "ave"), Map.entry("road", "rd"), Map.entry("drive", "dr"),
        Map.entry("lane", "ln"), Map.entry("boulevard", "blvd"), Map.entry("court", "ct"), Map.entry("place", "pl"), Map.entry("circle", "cir"),
        Map.entry("terrace", "ter"), Map.entry("parkway", "pkwy"), Map.entry("highway", "hwy"), Map.entry("trail", "trl"), Map.entry("square", "sq"),
        Map.entry("north", "n"), Map.entry("south", "s"), Map.entry("east", "e"), Map.entry("west", "w"),
        Map.entry("apartment", "apt"), Map.entry("unit", "apt"), Map.entry("suite", "ste"), Map.entry("number", ""), Map.entry("no", ""));

    /** Street line normalised word by word ("9 Oak Street" / "9 Oak St." / "9 oak st" → "9oakst"), then alphanumerics only. */
    static String normAddress(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).replace("#", " ").split("[^a-z0-9]+")) {
            if (w.isEmpty()) continue;
            sb.append(STREET_WORDS.getOrDefault(w, w));
        }
        return sb.toString();
    }

    /** Shared tier → message / default-action logic. */
    private void applyMatch(String currentAction, EtlMatchService.Match m, List<Map<String, String>> issues, String noun,
                            java.util.function.Consumer<String> tier, java.util.function.Consumer<String> how,
                            java.util.function.Consumer<Long> matchId, java.util.function.Consumer<String> action) {
        tier.accept(m.tier());
        how.accept(m.how());
        switch (m.tier()) {
            case EtlMatchService.EXACT -> {
                matchId.accept(m.liveId());
                if (currentAction == null) action.accept("SKIP");
                issues.add(issue("_row", "WARN", "Already imported: existing " + noun + " #" + m.liveId() + " (" + m.how() + "); default action SKIP"));
            }
            case EtlMatchService.STRONG -> {
                matchId.accept(m.liveId());
                if (currentAction == null) action.accept("SKIP");
                issues.add(issue("_row", "WARN", "Likely duplicate of existing " + noun + " #" + m.liveId() + " (matched by " + m.how() + "); default action SKIP"));
            }
            case EtlMatchService.POSSIBLE -> {
                matchId.accept(m.liveId());
                issues.add(issue("_row", "REVIEW", "Possible duplicate of existing " + noun + " #" + m.liveId() + " (" + m.how() + ") — decide: skip, update or import as new"));
            }
            case EtlMatchService.IN_FILE -> {
                matchId.accept(null);
                if (currentAction == null) action.accept("SKIP");
                issues.add(issue("_row", "WARN", "Duplicate within this file (" + m.how() + "); default action SKIP"));
            }
            default -> {
                matchId.accept(null);
                if (currentAction == null) action.accept("INSERT");
            }
        }
    }

    /** ERROR beats everything; an undecided POSSIBLE match is REVIEW; else WARN/VALID. */
    private String grade(List<Map<String, String>> issues, EtlMatchService.Match m, String decidedAction) {
        if (issues.stream().anyMatch(i -> "ERROR".equals(i.get("level")))) return "ERROR";
        if (EtlMatchService.POSSIBLE.equals(m.tier()) && decidedAction == null) return "REVIEW";
        boolean w = issues.stream().anyMatch(i -> "WARN".equals(i.get("level")) || "REVIEW".equals(i.get("level")));
        return w ? "WARN" : "VALID";
    }

    private static final class Tally {
        int valid, warn, error, review, newRows, exact, strong, possible, inFile;
        void count(String status, EtlMatchService.Match m) {
            switch (status) { case "ERROR" -> error++; case "REVIEW" -> review++; case "WARN" -> warn++; default -> valid++; }
            switch (m.tier()) {
                case EtlMatchService.EXACT -> exact++; case EtlMatchService.STRONG -> strong++;
                case EtlMatchService.POSSIBLE -> possible++; case EtlMatchService.IN_FILE -> inFile++;
                default -> newRows++;
            }
        }
        ValidationSummary summary(String table, int total) {
            return new ValidationSummary(table, total, valid, warn, error, review, newRows, exact, strong, possible, inFile);
        }
    }

    // ───────────────────────── income / expense ─────────────────────────

    private ValidationSummary validateMoney(Long runId, String clientId, String table) {
        ImportRun run = runService.getRun(runId, clientId);
        String system = SourcePreset.of(run.getSourcePreset()).name();

        // Resolvable person keys → "m<id>" (live member) or "s<family key>" (staged, not yet loaded).
        Map<String, String> people = new HashMap<>();
        for (StagingFamily f : familyRepo.findByRunId(runId)) {
            // Phase 3: a people row in the same run resolves by its own id (a gift's "Person Id"),
            // whether it is still staged, already loaded, or skipped as a duplicate of a live member.
            Long live = f.getTargetId() != null ? f.getTargetId() : ("SKIPPED".equals(f.getRowStatus()) ? f.getDedupeMatchId() : null);
            if (notBlank(f.getSourceRowId())) people.put(f.getSourceRowId().trim(), live != null ? "m" + live : "s" + f.getSourceRowId().trim());
            if (notBlank(f.getFamilyKey())) people.putIfAbsent(f.getFamilyKey().trim(), live != null ? "m" + live : "s" + f.getFamilyKey().trim());
        }
        Set<Integer> active = new HashSet<>();
        for (FamilyMember m : memberRepo.findActiveByTenantForEtl(clientId)) {
            if (m.getId() != null) { people.put(String.valueOf(m.getId()), "m" + m.getId()); active.add(m.getId()); }
            if (notBlank(m.getMemberRef())) people.put(m.getMemberRef().trim(), "m" + m.getId());
            if (system.equals(m.getSourceSystem()) && notBlank(m.getSourceId())) people.put(m.getSourceId().trim(), "m" + m.getId());
        }
        // Phase 3: links learned by earlier runs of the same product (members still active only).
        if (linkService != null)
            for (Map.Entry<String, Long> e : linkService.index(clientId, system, com.churchgeniuspro.hibernate.ImportLink.PERSON).entrySet())
                if (active.contains(e.getValue().intValue())) people.putIfAbsent(e.getKey(), "m" + e.getValue());

        Tally t = new Tally();
        if ("income".equals(table)) {
            List<StagingIncome> rows = incomeRepo.findByRunId(runId);
            LocalDate[] win = window(rows.stream().map(StagingIncome::getIncomeDate).toList());
            EtlMatchService.MoneyIndex index = matcher.moneyIndex(clientId, "income", win[0], win[1]);
            for (StagingIncome r : rows) {
                List<Map<String, String>> issues = existing(r.getValidationMsgs());
                checkAmount(issues, r.getAmount());
                // Migration Phase 1: the live column is NOT NULL, so a missing date is an ERROR
                // (it used to be a WARN that then failed at load).
                if (r.getIncomeDate() == null) issues.add(issue("incomeDate", "ERROR", "Date is required"));
                String person = resolvePerson(issues, r.getFamilyLink(), people);
                if (person == null && notBlank(r.getGuestName())) person = "g" + r.getGuestName().trim().toLowerCase(Locale.ROOT);
                EtlMatchService.Match m = index.matchIncome(r, person);
                applyMatch(r.getDedupeAction(), m, issues, "income row",
                    r::setMatchTier, r::setMatchDetail, r::setDedupeMatchId, r::setDedupeAction);
                String status = grade(issues, m, r.getDedupeAction());
                r.setRowStatus(status);
                r.setValidationMsgs(issues.isEmpty() ? null : toJson(issues));
                incomeRepo.save(r);
                t.count(status, m);
            }
            return t.summary(table, rows.size());
        } else {
            List<StagingExpense> rows = expenseRepo.findByRunId(runId);
            LocalDate[] win = window(rows.stream().map(StagingExpense::getExpenseDate).toList());
            EtlMatchService.MoneyIndex index = matcher.moneyIndex(clientId, "expense", win[0], win[1]);
            for (StagingExpense r : rows) {
                List<Map<String, String>> issues = existing(r.getValidationMsgs());
                checkAmount(issues, r.getAmount());
                if (r.getExpenseDate() == null) issues.add(issue("expenseDate", "ERROR", "Date is required"));
                EtlMatchService.Match m = index.matchExpense(r);
                applyMatch(r.getDedupeAction(), m, issues, "expense row",
                    r::setMatchTier, r::setMatchDetail, r::setDedupeMatchId, r::setDedupeAction);
                String status = grade(issues, m, r.getDedupeAction());
                r.setRowStatus(status);
                r.setValidationMsgs(issues.isEmpty() ? null : toJson(issues));
                expenseRepo.save(r);
                t.count(status, m);
            }
            return t.summary(table, rows.size());
        }
    }

    private static LocalDate[] window(List<LocalDate> dates) {
        LocalDate min = null, max = null;
        for (LocalDate d : dates) {
            if (d == null) continue;
            if (min == null || d.isBefore(min)) min = d;
            if (max == null || d.isAfter(max)) max = d;
        }
        return new LocalDate[]{min, max};
    }

    /** @return "m<id>" / "s<key>" when the link resolves, else null (WARN recorded; row loads unlinked / as guest). */
    private String resolvePerson(List<Map<String, String>> issues, String link, Map<String, String> people) {
        if (!notBlank(link)) return null;
        String hit = people.get(link.trim());
        if (hit == null)
            issues.add(issue("familyLink", "WARN",
                "Could not resolve family link '" + link + "' to a member or staged family; will load unlinked (guest name when present)"));
        return hit;
    }

    private void checkAmount(List<Map<String, String>> issues, BigDecimal amount) {
        if (amount == null) issues.add(issue("amount", "ERROR", "Amount is required"));
        else if (amount.signum() <= 0) issues.add(issue("amount", "ERROR", "Amount must be greater than zero"));
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
