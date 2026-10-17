package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EtlValidationService.ValidationSummary;
import com.churchgeniuspro.util.EtlTargetSchema;
import com.churchgeniuspro.util.SourcePreset;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * ETL Migration Phase 4 — the record data types: pledge campaigns, pledges, events, event
 * registrations, attendance, groups, group memberships, Kids Ministry children and
 * check-ins. Staged in {@code staging_record}; this service validates them (required
 * values, references, match tiers per the approved plan), loads them and reverses them.
 *
 * <p>Same rules as people and money (Phase 2/3):
 * <ul>
 *   <li>Match tiers — EXACT (the source id was imported before: {@code import_link}),
 *       STRONG (natural key), POSSIBLE (looser key → REVIEW, blocks approval), IN_FILE,
 *       NONE. EXACT/STRONG/IN_FILE default to SKIP.</li>
 *   <li>Pledges are money: never MERGE, UPDATE only from an EXACT/STRONG match.</li>
 *   <li>References resolve to a parent staged in this run, to what an earlier run of the
 *       same product created, or to a live record by name; an unresolved REQUIRED parent
 *       (campaign, event, child) is an ERROR, an unresolved person is a WARN — never a
 *       guess. A group or classroom that does not exist is created at load and recorded so
 *       rollback removes it when nothing uses it.</li>
 *   <li>Rollback soft-deletes what the import created (registrations and check-ins, which
 *       have no soft delete, are removed — only rows this import created), restores
 *       before-images of updated rows, and refuses to remove a campaign / event / group /
 *       child the church has since added records under.</li>
 * </ul>
 */
@Service
public class EtlRecordService {

    private static final Logger log = LoggerFactory.getLogger(EtlRecordService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Parent table each record type refers to (null = none). */
    public static final Map<String, String> PARENT = Map.of(
        "pledge", "pledge_campaign", "event_registration", "event", "group_member", "group", "km_checkin", "km_child");

    /** Live fields an UPDATE / MERGE may change (and a before-image therefore captures), per type. */
    static final Map<String, List<String>> FIELDS = Map.of(
        "pledge_campaign",    List.of("name", "subSourceId", "targetAmount", "endDate", "description", "status", "otherGifts"),
        "pledge",             List.of("pledgeAmount", "monthlyAmount", "gifts", "notes", "guestName", "amountCollected"),
        "event",              List.of("eventName", "eventDate", "startTime", "endTime", "eventType", "address1", "city", "state", "pinCode", "fee", "maxCapacity"),
        "event_registration", List.of("firstName", "lastName", "email", "phone", "adults", "kids", "attending", "note", "checkedIn"),
        "attendance",         List.of("serviceType", "status", "checkInTime", "checkOutTime", "ministry", "campus", "personName"),
        "group",              List.of("groupName"),
        "group_member",       List.of("firstName", "lastName", "email", "familyMemberId"),
        "km_child",           List.of("firstName", "lastName", "dob", "gender", "grade", "allergies", "medicalNotes", "parentName", "parentPhone",
                                      "parentEmail", "emergencyContactName", "emergencyContactPhone", "classroomId", "familyMemberId"),
        "km_checkin",         List.of("checkinTime", "checkoutTime", "classroomId", "checkedOutBy", "notes"));

    private final StagingRecordRepository recordRepo;
    private final StagingFamilyRepository familyRepo;
    private final FamilyMemberRepository  memberRepo;
    private final ImportRunService        runService;
    private final EtlLiveLookup           live;
    private final EtlReferenceResolver    refResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtlLinkService linkService;
    public void setLinkService(EtlLinkService s) { this.linkService = s; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TransformStageService stageService;
    public void setStageService(TransformStageService s) { this.stageService = s; }

    public EtlRecordService(StagingRecordRepository recordRepo, StagingFamilyRepository familyRepo, FamilyMemberRepository memberRepo,
                            ImportRunService runService, EtlLiveLookup live, EtlReferenceResolver refResolver) {
        this.recordRepo = recordRepo; this.familyRepo = familyRepo; this.memberRepo = memberRepo;
        this.runService = runService; this.live = live; this.refResolver = refResolver;
    }

    public static boolean financial(String table) { return "pledge".equals(table); }

    // ═════════════════════════════ values ═════════════════════════════

    static Map<String, Object> values(StagingRecord r) { return readMap(r.getValuesJson()); }
    static Map<String, Object> links(StagingRecord r)  { return readMap(r.getLinksJson()); }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    static String str(Map<String, Object> v, String k) {
        Object o = v.get(k);
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
    static BigDecimal dec(Map<String, Object> v, String k) { String s = str(v, k); return s == null ? null : new BigDecimal(s); }
    static LocalDate date(Map<String, Object> v, String k) { String s = str(v, k); return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); }
    static LocalDateTime dt(Map<String, Object> v, String k) {
        String s = str(v, k);
        if (s == null) return null;
        return s.length() <= 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
    }
    static Integer integer(Map<String, Object> v, String k) { Object o = v.get(k); return o == null ? null : o instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(o).trim()); }
    static Boolean bool(Map<String, Object> v, String k) { Object o = v.get(k); return o == null ? null : o instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(o)); }

    /** Lower-case alphanumerics only. */
    static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    /** Lower-case, trimmed, inner whitespace collapsed ("Youth  Group " → "youth group"). */
    static String nm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    static String last10(String phone) { if (phone == null) return ""; String d = phone.replaceAll("\\D", ""); return d.length() > 10 ? d.substring(d.length() - 10) : d; }
    static String amountKey(BigDecimal b) { return b == null ? "" : b.stripTrailingZeros().toPlainString(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String json(Object o) { try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return null; } }
    private static Map<String, String> issue(String field, String level, String message) {
        Map<String, String> m = new LinkedHashMap<>(); m.put("field", field); m.put("level", level); m.put("message", message); return m;
    }
    private static List<Map<String, String>> msgs(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try { return MAPPER.readValue(json, new TypeReference<List<Map<String, String>>>() {}); } catch (Exception e) { return new ArrayList<>(); }
    }

    // ═════════════════════════════ people (same rules as validateMoney) ═════════════════════════════

    /** Resolvable person keys → "m<live id>" or "s<staged key>"; plus live names for display/fallback. */
    final class People {
        final Map<String, String> token = new HashMap<>();
        final Map<String, StagingFamily> stagedByKey = new HashMap<>();
        final Map<Integer, FamilyMember> liveById = new HashMap<>();
        final Map<String, List<Integer>> liveByName = new HashMap<>();

        People(Long runId, String tenant, String system) {
            for (StagingFamily f : familyRepo.findByRunId(runId)) {
                Long l = f.getTargetId() != null ? f.getTargetId() : ("SKIPPED".equals(f.getRowStatus()) ? f.getDedupeMatchId() : null);
                if (!blank(f.getSourceRowId())) { token.put(f.getSourceRowId().trim(), l != null ? "m" + l : "s" + f.getSourceRowId().trim()); stagedByKey.put(f.getSourceRowId().trim(), f); }
                if (!blank(f.getFamilyKey())) { token.putIfAbsent(f.getFamilyKey().trim(), l != null ? "m" + l : "s" + f.getFamilyKey().trim()); stagedByKey.putIfAbsent(f.getFamilyKey().trim(), f); }
            }
            Set<Integer> active = new HashSet<>();
            for (FamilyMember m : memberRepo.findActiveByTenantForEtl(tenant)) {
                if (m.getId() == null) continue;
                active.add(m.getId()); liveById.put(m.getId(), m);
                token.put(String.valueOf(m.getId()), "m" + m.getId());
                if (!blank(m.getMemberRef())) token.put(m.getMemberRef().trim(), "m" + m.getId());
                if (system.equals(m.getSourceSystem()) && !blank(m.getSourceId())) token.put(m.getSourceId().trim(), "m" + m.getId());
                liveByName.computeIfAbsent(norm(m.getFirstName()) + "|" + norm(m.getLastName()), k -> new ArrayList<>()).add(m.getId());
            }
            if (linkService != null)
                for (Map.Entry<String, Long> e : linkService.index(tenant, system, ImportLink.PERSON).entrySet())
                    if (active.contains(e.getValue().intValue())) token.putIfAbsent(e.getKey(), "m" + e.getValue());
        }

        String resolve(String link) { return blank(link) ? null : token.get(link.trim()); }

        /** First / last / email of a resolved person token (live member or staged row), for name fallbacks. */
        String[] names(String tok) {
            if (tok == null) return null;
            if (tok.startsWith("m")) { FamilyMember m = liveById.get(Integer.valueOf(tok.substring(1))); return m == null ? null : new String[]{m.getFirstName(), m.getLastName(), m.getEmail(), m.getPhone()}; }
            StagingFamily f = stagedByKey.get(tok.substring(1));
            return f == null ? null : new String[]{f.getFirstName(), f.getLastName(), f.getEmail(), f.getPhone()};
        }
    }

    // ═════════════════════════════ validation ═════════════════════════════

    /** Natural keys of one row (or one live record): strong keys and possible keys, with how they matched. */
    static final class Keys {
        final List<String> strong = new ArrayList<>(), possible = new ArrayList<>();
        String strongHow, possibleHow;
        Keys strong(String k, String how) { if (k != null) { strong.add(k); if (strongHow == null) strongHow = how; } return this; }
        Keys possible(String k, String how) { if (k != null) { possible.add(k); if (possibleHow == null) possibleHow = how; } return this; }
    }

    /** Staged rows of a parent table in this run, indexed by source id and by natural name key. */
    static final class Staged {
        final Map<String, StagingRecord> bySource = new HashMap<>();
        final Map<String, List<StagingRecord>> byKey = new HashMap<>();
        void add(StagingRecord r, String... keys) {
            if (!blank(r.getSourceRowId())) bySource.putIfAbsent(r.getSourceRowId().trim(), r);
            for (String k : keys) if (k != null) byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
        }
    }

    /** Per-validation context. */
    final class Ctx {
        final Long runId; final String tenant, system, table; final boolean hasIds;
        final Map<String, List<Long>> liveStrong = new HashMap<>(), livePossible = new HashMap<>();
        final Map<String, Long> exact = new HashMap<>();
        People people;
        Ctx(Long runId, String tenant, String system, String table, boolean hasIds) { this.runId = runId; this.tenant = tenant; this.system = system; this.table = table; this.hasIds = hasIds; }
        People people() { if (people == null) people = new People(runId, tenant, system); return people; }
        void live(Keys k, Long id) {
            for (String s : k.strong) liveStrong.computeIfAbsent(s, x -> new ArrayList<>()).add(id);
            for (String p : k.possible) livePossible.computeIfAbsent(p, x -> new ArrayList<>()).add(id);
        }
        /** Earlier-run link of a parent entity → live id, if that row is still active. */
        Long linked(String entity, String sourceKey, String liveTable) {
            if (linkService == null || blank(sourceKey)) return null;
            Long id = linkService.resolve(tenant, system, entity, sourceKey.trim()).orElse(null);
            return id != null && live.active(liveTable, id, tenant) ? id : null;
        }
    }

    @Transactional
    public ValidationSummary validate(Long runId, String tenant, String actor, String table) {
        if (!EtlTargetSchema.isRecordTable(table)) throw new IllegalArgumentException("Not a record table: " + table);
        ImportRun run = runService.getRun(runId, tenant);
        String system = SourcePreset.of(run.getSourcePreset()).name();
        boolean hasIds = stageService != null && stageService.hasSourceIdsForTarget(runId, table);
        Ctx c = new Ctx(runId, tenant, system, table, hasIds);
        List<StagingRecord> rows = recordRepo.findByRunIdAndTargetTableOrderByIdAsc(runId, table);

        if (hasIds && linkService != null) {
            Map<String, Long> prior = linkService.index(tenant, system, table);
            Set<Long> active = prior.isEmpty() ? Set.of() : live.activeIds(table, tenant);
            for (Map.Entry<String, Long> e : prior.entrySet()) if (active.contains(e.getValue())) c.exact.put(e.getKey(), e.getValue());
        }

        // Rows of this table: resolve references and keys first (live indexes may need the date window).
        List<Map<String, Object>> vals = new ArrayList<>();
        List<Map<String, Object>> lks = new ArrayList<>();
        List<List<Map<String, String>>> iss = new ArrayList<>();
        List<Keys> keys = new ArrayList<>();
        Resolver res = new Resolver(c);
        for (StagingRecord r : rows) {
            Map<String, Object> v = values(r);
            Map<String, Object> l = new LinkedHashMap<>();
            List<Map<String, String>> issues = msgs(r.getValidationMsgs()).stream()
                .filter(i -> !"_ref".equals(i.get("field")) && !"_row".equals(i.get("field"))).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            for (EtlTargetSchema.TargetColumn col : EtlTargetSchema.required(table))
                if (v.get(col.name()) == null && issues.stream().noneMatch(i -> col.name().equals(i.get("field"))))
                    issues.add(issue(col.name(), "ERROR", label(col.name()) + " is required"));
            Keys k = res.row(r, v, l, issues);
            vals.add(v); lks.add(l); iss.add(issues); keys.add(k);
        }
        res.liveIndex(vals);

        Tally t = new Tally();
        Map<String, Long> inFileStrong = new HashMap<>();
        Map<String, Long> inFileIds = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            StagingRecord r = rows.get(i);
            List<Map<String, String>> issues = iss.get(i);
            Keys k = keys.get(i);
            String tier = EtlMatchService.NONE, how = null; Long liveId = null, inFileRow = null;
            String sid = blank(r.getSourceRowId()) ? null : r.getSourceRowId().trim();
            if (hasIds && sid != null && c.exact.containsKey(sid)) { tier = EtlMatchService.EXACT; liveId = c.exact.get(sid); how = "imported before (source id " + sid + ")"; }
            else if (hasIds && sid != null && inFileIds.containsKey(sid)) { tier = EtlMatchService.IN_FILE; inFileRow = inFileIds.get(sid); how = "same source id as row " + inFileRow; }
            else {
                for (String s : k.strong) if (inFileStrong.containsKey(s)) { tier = EtlMatchService.IN_FILE; inFileRow = inFileStrong.get(s); how = "same " + k.strongHow + " as row " + inFileRow; break; }
                if (EtlMatchService.NONE.equals(tier))
                    for (String s : k.strong) { List<Long> hit = c.liveStrong.get(s); if (hit != null && !hit.isEmpty()) { tier = EtlMatchService.STRONG; liveId = hit.get(0); how = k.strongHow; break; } }
                if (EtlMatchService.NONE.equals(tier))
                    for (String p : k.possible) {
                        List<Long> hit = c.livePossible.get(p);
                        if (hit != null && !hit.isEmpty()) { tier = EtlMatchService.POSSIBLE; liveId = hit.get(0); how = k.possibleHow + (hit.size() > 1 ? " (" + hit.size() + " records)" : ""); break; }
                    }
            }
            if (sid != null) inFileIds.putIfAbsent(sid, r.getId());
            for (String s : k.strong) inFileStrong.putIfAbsent(s, r.getId());

            r.setMatchTier(tier); r.setMatchDetail(how == null ? null : (how.length() > 255 ? how.substring(0, 255) : how));
            String noun = EtlTargetSchema.LABELS.getOrDefault(table, table).toLowerCase(Locale.ROOT);
            switch (tier) {
                case EtlMatchService.EXACT -> { r.setDedupeMatchId(liveId); if (r.getDedupeAction() == null) r.setDedupeAction("SKIP");
                    issues.add(issue("_row", "WARN", "Already imported: existing record #" + liveId + " (" + how + "); default action SKIP")); }
                case EtlMatchService.STRONG -> { r.setDedupeMatchId(liveId); if (r.getDedupeAction() == null) r.setDedupeAction("SKIP");
                    issues.add(issue("_row", "WARN", "Likely duplicate of existing record #" + liveId + " in " + noun + " (matched by " + how + "); default action SKIP")); }
                case EtlMatchService.POSSIBLE -> { r.setDedupeMatchId(liveId);
                    issues.add(issue("_row", "REVIEW", "Possible duplicate of existing record #" + liveId + " (" + how + ") — decide: skip, " + (financial(table) ? "" : "merge, ") + "update or import as new")); }
                case EtlMatchService.IN_FILE -> { r.setDedupeMatchId(null); if (r.getDedupeAction() == null) r.setDedupeAction("SKIP");
                    issues.add(issue("_row", "WARN", "Duplicate within this file (" + how + "); default action SKIP")); }
                default -> { r.setDedupeMatchId(null); if (r.getDedupeAction() == null) r.setDedupeAction("INSERT"); }
            }
            String status;
            if (issues.stream().anyMatch(x -> "ERROR".equals(x.get("level")))) status = "ERROR";
            else if (EtlMatchService.POSSIBLE.equals(tier) && r.getDedupeAction() == null) status = "REVIEW";
            else status = issues.stream().anyMatch(x -> "WARN".equals(x.get("level")) || "REVIEW".equals(x.get("level"))) ? "WARN" : "VALID";
            r.setRowStatus(status);
            r.setValidationMsgs(issues.isEmpty() ? null : json(issues));
            r.setLinksJson(lks.get(i).isEmpty() ? null : json(lks.get(i)));
            recordRepo.save(r);
            t.count(status, tier);
        }
        ValidationSummary sum = t.summary(table, rows.size());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("targetTable", table); detail.put("total", sum.total()); detail.put("valid", sum.valid()); detail.put("warn", sum.warn());
        detail.put("error", sum.error()); detail.put("review", sum.review()); detail.put("new", sum.newRows()); detail.put("exact", sum.exact());
        detail.put("strong", sum.strong()); detail.put("possible", sum.possible()); detail.put("inFile", sum.inFile());
        runService.recordAudit(runId, tenant, actor, "VALIDATE", detail);
        log.info("ETL run {} validated {} {} records: {} valid, {} warn, {} error, {} review", runId, rows.size(), table, sum.valid(), sum.warn(), sum.error(), sum.review());
        return sum;
    }

    private static String label(String col) {
        return switch (col) {
            case "campaignName" -> "Campaign name"; case "pledgeAmount" -> "Pledge amount"; case "eventName" -> "Event name";
            case "eventDate" -> "Event date"; case "attendanceDate" -> "Attendance date"; case "groupName" -> "Group name";
            case "firstName" -> "First name"; case "lastName" -> "Last name"; case "checkinTime" -> "Check-in time";
            default -> col;
        };
    }

    private static final class Tally {
        int valid, warn, error, review, newRows, exact, strong, possible, inFile;
        void count(String status, String tier) {
            switch (status) { case "ERROR" -> error++; case "REVIEW" -> review++; case "WARN" -> warn++; default -> valid++; }
            switch (tier) { case EtlMatchService.EXACT -> exact++; case EtlMatchService.STRONG -> strong++;
                case EtlMatchService.POSSIBLE -> possible++; case EtlMatchService.IN_FILE -> inFile++; default -> newRows++; }
        }
        ValidationSummary summary(String table, int total) { return new ValidationSummary(table, total, valid, warn, error, review, newRows, exact, strong, possible, inFile); }
    }

    /**
     * Per-type reference resolution and natural keys. Tokens: parents "l<live id>" (live),
     * "s<staging id>" (staged in this run), "new:<name>" (created at load); people "m<id>" /
     * "s<key>". Keys embed the tokens, so a child of a NEW parent can only ever match within
     * the file — never a live record.
     */
    final class Resolver {
        final Ctx c;
        Staged staged;
        Resolver(Ctx c) { this.c = c; }

        private Staged staged(String parentTable) {
            if (staged != null) return staged;
            staged = new Staged();
            for (StagingRecord p : recordRepo.findByRunIdAndTargetTableOrderByIdAsc(c.runId, parentTable)) {
                Map<String, Object> v = values(p);
                switch (parentTable) {
                    case "pledge_campaign" -> staged.add(p, "n:" + norm(str(v, "campaignName")));
                    case "event" -> staged.add(p, "n:" + norm(str(v, "eventName")), "nd:" + norm(str(v, "eventName")) + "|" + str(v, "eventDate"));
                    case "group" -> staged.add(p, "n:" + nm(str(v, "groupName")));
                    case "km_child" -> staged.add(p, "n:" + norm(str(v, "firstName")) + "|" + norm(str(v, "lastName")),
                        "nd:" + norm(str(v, "firstName")) + "|" + norm(str(v, "lastName")) + "|" + str(v, "dob"));
                    default -> { }
                }
            }
            return staged;
        }

        /** "s<id>" for a staged parent with this source id, else "l<id>" for an earlier run's link, else null. */
        private String byLink(String parentTable, String link) {
            if (blank(link)) return null;
            StagingRecord s = staged(parentTable).bySource.get(link.trim());
            if (s != null) return "s" + s.getId();
            Long l = c.linked(parentTable, link, parentTable);
            return l == null ? null : "l" + l;
        }

        /** Staged parent by name key (exactly one), else null; >1 → ambiguity message in {@code amb}. */
        private String stagedByKey(String parentTable, String key, List<String> amb) {
            List<StagingRecord> hit = staged(parentTable).byKey.get(key);
            if (hit == null || hit.isEmpty()) return null;
            if (hit.size() > 1) { amb.add(hit.size() + " rows in this run's " + EtlTargetSchema.LABELS.get(parentTable).toLowerCase(Locale.ROOT) + " file"); return null; }
            return "s" + hit.get(0).getId();
        }

        private String personTok(Map<String, Object> v, String linkCol, List<Map<String, String>> issues, String what) {
            String link = str(v, linkCol);
            if (link == null) return null;
            String tok = c.people().resolve(link);
            if (tok == null) issues.add(issue(linkCol, "WARN", "Could not resolve " + what + " '" + link + "' to a member or a person staged in this run" ));
            return tok;
        }

        // live indexes (built once, after all rows are seen so the date windows are known)
        void liveIndex(List<Map<String, Object>> rows) {
            switch (c.table) {
                case "pledge_campaign" -> {
                    Map<Integer, String> fundName = new HashMap<>();
                    for (SubSource s : live.funds(c.tenant)) fundName.put(s.getId(), s.getSourceName());
                    for (PledgeCampaign x : live.campaigns(c.tenant))
                        c.live(campaignKeys(x.getName(), fundName.get(x.getSubSourceId())), x.getId().longValue());
                }
                case "pledge" -> {
                    for (PledgeMember p : live.pledges(c.tenant)) {
                        Keys k = new Keys();
                        String parent = "l" + p.getCampaignId();
                        if (p.getFamilyMemberId() != null) k.strong(parent + "|m" + p.getFamilyMemberId(), "campaign + member");
                        k.possible(parent + "|amt:" + amountKey(p.getPledgeAmount()), "campaign + amount");
                        c.live(k, p.getId().longValue());
                    }
                }
                case "event" -> { for (ChurchEvent e : live.events(c.tenant)) c.live(eventKeys(e.getEventName(), e.getEventDate(), e.getStartTime()), e.getId().longValue()); }
                case "event_registration" -> {
                    for (EventRegistration r : live.registrations(c.tenant))
                        c.live(registrationKeys("l" + r.getEventId(), r.getFirstName(), r.getLastName(), r.getEmail()), r.getId().longValue());
                }
                case "attendance" -> {
                    LocalDate[] w = window(rows, "attendanceDate");
                    for (AttendanceRecord a : live.attendance(c.tenant, w[0], w[1])) {
                        String person = a.getFamilyMemberId() != null ? "m" + a.getFamilyMemberId() : "n:" + norm(a.getPersonName());
                        c.live(attendanceKeys(person, a.getAttendanceDate(), a.getServiceType()), a.getId());
                        if (a.getFamilyMemberId() != null && !blank(a.getPersonName()))   // a name-only import row still finds a member's record
                            c.live(attendanceKeys("n:" + norm(a.getPersonName()), a.getAttendanceDate(), a.getServiceType()), a.getId());
                    }
                }
                case "group" -> { for (Group g : live.groups(c.tenant)) c.live(groupKeys(g.getGroupName()), g.getId().longValue()); }
                case "group_member" -> {
                    for (Object[] m : live.groupMembers(c.tenant)) {
                        String parent = "l" + m[0];
                        Keys k = new Keys();
                        if (m[1] != null) k.strong(parent + "|m" + m[1], "group + member");
                        k.possible(parent + "|n:" + norm((String) m[2]) + norm((String) m[3]), "group + first and last name");
                        c.live(k, ((Number) m[5]).longValue());
                    }
                }
                case "km_child" -> {
                    for (KmChild k : live.children(c.tenant))
                        c.live(childKeys(k.getFirstName(), k.getLastName(), k.getDob(), k.getParentName(), k.getParentPhone(), true), k.getId());
                }
                case "km_checkin" -> {
                    LocalDate[] w = window(rows, "checkinTime");
                    if (w[0] != null)
                        for (KmCheckin k : live.checkins(c.tenant, w[0].minusDays(1).atStartOfDay(), w[1].plusDays(2).atStartOfDay()))
                            c.live(checkinKeys("l" + k.getChildId(), k.getCheckinTime()), k.getId());
                }
                default -> { }
            }
        }

        private LocalDate[] window(List<Map<String, Object>> rows, String col) {
            LocalDate min = null, max = null;
            for (Map<String, Object> v : rows) {
                LocalDate d;
                try { d = "checkinTime".equals(col) ? (dt(v, col) == null ? null : dt(v, col).toLocalDate()) : date(v, col); } catch (Exception e) { d = null; }
                if (d == null) continue;
                if (min == null || d.isBefore(min)) min = d;
                if (max == null || d.isAfter(max)) max = d;
            }
            return new LocalDate[]{min, max};
        }

        // ── keys ──
        Keys campaignKeys(String name, String fund) {
            return new Keys().strong("c:" + norm(name) + "|" + norm(fund), "name + fund").possible("c:" + norm(name), "same name");
        }
        Keys eventKeys(String name, LocalDate d, String start) {
            String base = "e:" + norm(name) + "|" + d;
            return new Keys().strong(base + "|" + (start == null ? "" : start), "name + date + start time").possible(base, "name + date");
        }
        Keys registrationKeys(String parent, String first, String last, String email) {
            Keys k = new Keys();
            if (!blank(email)) k.strong(parent + "|e:" + email.trim().toLowerCase(Locale.ROOT), "event + e-mail");
            if (!blank(first) && !blank(last)) k.strong(parent + "|n:" + norm(first) + "|" + norm(last), "event + first and last name");
            if (!blank(last)) k.possible(parent + "|l:" + norm(last), "event + last name");
            return k;
        }
        Keys attendanceKeys(String person, LocalDate d, String service) {
            return new Keys().strong(person + "|" + d + "|" + nm(service), "person + date + service").possible(person + "|" + d, "person + date");
        }
        Keys groupKeys(String name) { return new Keys().strong("g:" + nm(name), "group name").possible("g~" + norm(name), "similar group name"); }
        Keys childKeys(String first, String last, LocalDate dob, String parentName, String parentPhone, boolean liveSide) {
            String fl = "k:" + norm(first) + "|" + norm(last);
            Keys k = new Keys();
            if (dob != null) k.strong(fl + "|" + dob, "first + last name + birth date");
            boolean guardian = false;
            if (!blank(parentName)) { k.possible(fl + "|g:" + norm(parentName), "first + last name + guardian"); guardian = true; }
            if (!blank(last10(parentPhone))) { k.possible(fl + "|p:" + last10(parentPhone), "first + last name + guardian phone"); guardian = true; }
            if (liveSide || !guardian) k.possible(fl, "same first and last name");
            return k;
        }
        Keys checkinKeys(String child, LocalDateTime t) {
            if (t == null) return new Keys();
            return new Keys().strong(child + "|" + t.truncatedTo(ChronoUnit.MINUTES), "child + check-in time").possible(child + "|" + t.toLocalDate(), "child + date");
        }

        // ── per-row resolution ──
        Keys row(StagingRecord r, Map<String, Object> v, Map<String, Object> l, List<Map<String, String>> issues) {
            try {
                return switch (c.table) {
                    case "pledge_campaign" -> campaign(v, l, issues);
                    case "pledge" -> pledge(v, l, issues);
                    case "event" -> event(v, issues);
                    case "event_registration" -> registration(v, l, issues);
                    case "attendance" -> attendance(v, l, issues);
                    case "group" -> groupKeys(str(v, "groupName"));
                    case "group_member" -> groupMember(v, l, issues);
                    case "km_child" -> child(v, l, issues);
                    case "km_checkin" -> checkin(v, l, issues);
                    default -> new Keys();
                };
            } catch (Exception e) {
                issues.add(issue("_row", "ERROR", "Could not read the row: " + e.getMessage()));
                return new Keys();
            }
        }

        private Keys campaign(Map<String, Object> v, Map<String, Object> l, List<Map<String, String>> issues) {
            String fund = str(v, "fund");
            if (fund != null) {
                SubSource hit = live.funds(c.tenant).stream().filter(s -> nm(s.getSourceName()).equals(nm(fund))).findFirst().orElse(null);
                if (hit != null) l.put("fund", "l" + hit.getId());
                else { l.put("fund", "new:" + fund); issues.add(issue("fund", "INFO", "Fund '" + fund + "' does not exist yet and will be created")); }
            }
            BigDecimal target = dec(v, "targetAmount");
            if (target != null && target.signum() < 0) issues.add(issue("targetAmount", "ERROR", "Goal cannot be negative"));
            LocalDate s = date(v, "startDate"), e = date(v, "endDate");
            if (s != null && e != null && e.isBefore(s)) issues.add(issue("endDate", "ERROR", "End date is before the start date"));
            if (s == null) issues.add(issue("startDate", "INFO", "No start date: gifts count toward this campaign from the import date only"));
            return campaignKeys(str(v, "campaignName"), fund);
        }

        private Keys pledge(Map<String, Object> v, Map<String, Object> l, List<Map<String, String>> issues) {
            String parent = parent("pledge_campaign", str(v, "campaignLink"), "n:" + norm(str(v, "campaignName")), str(v, "campaignName"),
                () -> live.campaigns(c.tenant).stream().filter(x -> norm(x.getName()).equals(norm(str(v, "campaignName")))).map(x -> x.getId().longValue()).toList(),
                "campaign", issues, null);
            if (parent != null) l.put("parent", parent);
            BigDecimal amt = dec(v, "pledgeAmount");
            if (amt != null && amt.signum() <= 0) issues.add(issue("pledgeAmount", "ERROR", "Pledge amount must be greater than zero"));
            BigDecimal monthly = dec(v, "monthlyAmount");
            if (monthly != null && monthly.signum() < 0) issues.add(issue("monthlyAmount", "ERROR", "Monthly amount cannot be negative"));
            String person = personTok(v, "personLink", issues, "person");
            if (person != null) l.put("person", person);
            if (person == null && str(v, "guestName") == null)
                issues.add(issue("guestName", "ERROR", "A pledge needs a person (Person Id) or a name"));
            else if (person == null && str(v, "personLink") != null)
                issues.get(issues.size() - 1).put("message", issues.get(issues.size() - 1).get("message") + "; will load as guest '" + str(v, "guestName") + "'");
            Keys k = new Keys();
            if (parent == null) return k;
            if (person != null) k.strong(parent + "|" + person, "campaign + member");
            else k.possible(parent + "|amt:" + amountKey(amt), "campaign + amount, person unresolved");
            return k;
        }

        private Keys event(Map<String, Object> v, List<Map<String, String>> issues) {
            String st = str(v, "startTime"), en = str(v, "endTime");
            if (st != null && en != null && en.compareTo(st) < 0) issues.add(issue("endTime", "WARN", "End time is before the start time"));
            Integer cap = integer(v, "maxCapacity");
            if (cap != null && cap < 0) issues.add(issue("maxCapacity", "ERROR", "Capacity cannot be negative"));
            return eventKeys(str(v, "eventName"), date(v, "eventDate"), st);
        }

        private Keys registration(Map<String, Object> v, Map<String, Object> l, List<Map<String, String>> issues) {
            String name = str(v, "eventName"); LocalDate d = date(v, "eventDate");
            String key = d == null ? "n:" + norm(name) : "nd:" + norm(name) + "|" + d;
            String parent = parent("event", str(v, "eventLink"), key, name,
                () -> live.events(c.tenant).stream().filter(e -> norm(e.getEventName()).equals(norm(name)) && (d == null || d.equals(e.getEventDate())))
                    .map(e -> e.getId().longValue()).toList(),
                "event", issues, d == null ? "add an event date column to pick one" : null);
            if (parent != null) l.put("parent", parent);
            String person = personTok(v, "personLink", issues, "person");
            if (person != null) l.put("person", person);
            String[] pn = c.people().names(person);
            String first = str(v, "firstName") != null ? str(v, "firstName") : pn == null ? null : pn[0];
            String last  = str(v, "lastName")  != null ? str(v, "lastName")  : pn == null ? null : pn[1];
            String email = str(v, "email")     != null ? str(v, "email")     : pn == null ? null : pn[2];
            if (blank(first) && blank(last) && blank(email)) issues.add(issue("firstName", "ERROR", "A registration needs a name or an e-mail"));
            if (email != null && !EMAIL.matcher(email).matches()) issues.add(issue("email", "WARN", "E-mail does not look valid: '" + email + "'"));
            for (String n : List.of("adults", "kids")) { Integer x = integer(v, n); if (x != null && x < 0) issues.add(issue(n, "ERROR", n + " cannot be negative")); }
            return parent == null ? new Keys() : registrationKeys(parent, first, last, email);
        }

        private Keys attendance(Map<String, Object> v, Map<String, Object> l, List<Map<String, String>> issues) {
            String person = personTok(v, "personLink", issues, "person");
            String first = str(v, "firstName"), last = str(v, "lastName"), full = str(v, "fullName");
            if (person == null && str(v, "personLink") == null && (!blank(first) || !blank(last))) {
                List<Integer> byName = c.people().liveByName.get(norm(first) + "|" + norm(last));
                if (byName != null && byName.size() == 1) { person = "m" + byName.get(0); issues.add(issue("_ref", "INFO", "Linked by name to member #" + byName.get(0))); }
            }
            if (person != null) l.put("person", person);
            String[] pn = c.people().names(person);
            String display = pn != null ? ((pn[0] == null ? "" : pn[0]) + " " + (pn[1] == null ? "" : pn[1])).trim()
                : full != null ? full : ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            if (blank(display) && person == null) issues.add(issue("fullName", "ERROR", "Attendance needs a person (Person Id) or a name"));
            else if (person == null) issues.add(issue("_ref", "WARN", "Not linked to a member; recorded by name ('" + display + "') as a visitor"));
            l.put("personName", display);
            String status = str(v, "status");
            String ns = normStatus(status);
            if (status != null && ns == null) { issues.add(issue("status", "WARN", "Unknown attendance status '" + status + "'; recorded as PRESENT (map it in the attendanceStatus values)")); ns = "PRESENT"; }
            l.put("status", ns == null ? "PRESENT" : ns);
            LocalDateTime in = dt(v, "checkInTime"), out = dt(v, "checkOutTime");
            if (in != null && out != null && out.isBefore(in)) issues.add(issue("checkOutTime", "ERROR", "Check-out is before check-in"));
            String pk = person != null ? person : "n:" + norm(display);
            return attendanceKeys(pk, date(v, "attendanceDate"), str(v, "serviceType"));
        }

        private Keys groupMember(Map<String, Object> v, Map<String, Object> l, List<Map<String, String>> issues) {
            String name = str(v, "groupName"), link = str(v, "groupLink");
            String parent = byLink("group", link);
            if (parent == null && name != null) {
                List<String> amb = new ArrayList<>();
                parent = stagedByKey("group", "n:" + nm(name), amb);
                if (parent == null && amb.isEmpty()) {
                    List<Long> hits = live.groups(c.tenant).stream().filter(g -> nm(g.getGroupName()).equals(nm(name))).map(g -> g.getId().longValue()).toList();
                    if (hits.size() == 1) parent = "l" + hits.get(0);
                    else if (hits.size() > 1) amb.add(hits.size() + " existing groups");
                }
                if (!amb.isEmpty()) issues.add(issue("groupName", "ERROR", "Group '" + name + "' is ambiguous: " + amb.get(0)));
                else if (parent == null) { parent = "new:" + name.trim(); issues.add(issue("groupName", "INFO", "Group '" + name.trim() + "' does not exist yet and will be created")); }
            }
            if (parent == null) issues.add(issue("groupName", "ERROR", link != null ? "Group id '" + link + "' was not found in this run or an earlier import" : "Group is required (a group name or group id)"));
            else l.put("parent", parent);
            String person = personTok(v, "personLink", issues, "person");
            if (person != null) l.put("person", person);
            String[] pn = c.people().names(person);
            String first = str(v, "firstName") != null ? str(v, "firstName") : pn == null ? null : pn[0];
            String last  = str(v, "lastName")  != null ? str(v, "lastName")  : pn == null ? null : pn[1];
            if (blank(first) || blank(last)) issues.add(issue("firstName", "ERROR", "A group member needs a first and last name (or a Person Id that resolves)"));
            Keys k = new Keys();
            if (parent == null) return k;
            if (person != null) k.strong(parent + "|" + person, "group + member");
            k.possible(parent + "|n:" + norm(first) + norm(last), "group + first and last name");
            return k;
        }

        private Keys child(Map<String, Object> v, Map<String, Object> l, List<Map<String, String>> issues) {
            String parentTok = personTok(v, "parentLink", issues, "parent");
            if (parentTok != null) l.put("person", parentTok);
            classroom(v, l, issues);
            LocalDate dob = date(v, "dob");
            if (dob != null && dob.isAfter(LocalDate.now())) issues.add(issue("dob", "ERROR", "Birth date is in the future"));
            String[] pn = c.people().names(parentTok);
            String parentName = str(v, "parentName") != null ? str(v, "parentName") : pn == null ? null : (((pn[0] == null ? "" : pn[0]) + " " + (pn[1] == null ? "" : pn[1])).trim());
            String parentPhone = str(v, "parentPhone") != null ? str(v, "parentPhone") : pn == null ? null : pn[3];
            return childKeys(str(v, "firstName"), str(v, "lastName"), dob, parentName, parentPhone, false);
        }

        private void classroom(Map<String, Object> v, Map<String, Object> l, List<Map<String, String>> issues) {
            String room = str(v, "classroom");
            if (room == null) return;
            KmClassroom hit = live.classrooms(c.tenant).stream().filter(x -> nm(x.getClassName()).equals(nm(room))).findFirst().orElse(null);
            if (hit != null) l.put("classroom", "l" + hit.getId());
            else { l.put("classroom", "new:" + room); issues.add(issue("classroom", "INFO", "Classroom '" + room + "' does not exist yet and will be created")); }
        }

        private Keys checkin(Map<String, Object> v, Map<String, Object> l, List<Map<String, String>> issues) {
            String first = str(v, "childFirstName"), last = str(v, "childLastName"); LocalDate dob = date(v, "childDob");
            String key = dob == null ? "n:" + norm(first) + "|" + norm(last) : "nd:" + norm(first) + "|" + norm(last) + "|" + dob;
            String parent = parent("km_child", str(v, "childLink"), (blank(first) && blank(last)) ? null : key, (first == null ? "" : first) + " " + (last == null ? "" : last),
                () -> live.children(c.tenant).stream().filter(k -> norm(k.getFirstName()).equals(norm(first)) && norm(k.getLastName()).equals(norm(last)) && (dob == null || dob.equals(k.getDob())))
                    .map(KmChild::getId).toList(),
                "child", issues, dob == null ? "add a birth date column to pick one" : null);
            if (parent != null) l.put("parent", parent);
            classroom(v, l, issues);
            LocalDateTime in = dt(v, "checkinTime"), out = dt(v, "checkoutTime");
            if (in != null && out != null && out.isBefore(in)) issues.add(issue("checkoutTime", "ERROR", "Check-out is before check-in"));
            if (in != null && out == null) issues.add(issue("checkoutTime", "INFO", "No check-out time: recorded as checked out at the check-in time, so the child never shows as still checked in"));
            return parent == null ? new Keys() : checkinKeys(parent, in);
        }

        /**
         * Required parent: by id link (staged row / earlier run), else by name key (staged
         * row in this run, then live). Unresolved or ambiguous → ERROR; returns the token.
         */
        private String parent(String parentTable, String link, String nameKey, String name,
                              java.util.function.Supplier<List<Long>> liveByName, String noun,
                              List<Map<String, String>> issues, String ambiguityHint) {
            String tok = byLink(parentTable, link);
            if (tok != null) return tok;
            if (link != null && blank(name)) {
                issues.add(issue("_ref", "ERROR", "The " + noun + " id '" + link + "' was not found in this run or an earlier import"));
                return null;
            }
            if (blank(name) || nameKey == null) { issues.add(issue("_ref", "ERROR", "The " + noun + " is required (a " + noun + " id or name)")); return null; }
            List<String> amb = new ArrayList<>();
            tok = stagedByKey(parentTable, nameKey, amb);
            if (tok == null && amb.isEmpty()) {
                List<Long> hits = liveByName.get();
                if (hits.size() == 1) tok = "l" + hits.get(0);
                else if (hits.size() > 1) amb.add(hits.size() + " existing records");
            }
            if (!amb.isEmpty()) { issues.add(issue("_ref", "ERROR", "The " + noun + " '" + name.trim() + "' is ambiguous: " + amb.get(0) + (ambiguityHint == null ? "" : " — " + ambiguityHint))); return null; }
            if (tok == null) issues.add(issue("_ref", "ERROR", "The " + noun + " '" + name.trim() + "' was not found — stage it in this run or create it first"));
            return tok;
        }
    }

    /** Attendance status → the app's vocabulary; null when unrecognised. */
    static String normStatus(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "present", "yes", "y", "true", "1", "x", "attended", "here", "checked in" -> "PRESENT";
            case "absent", "no", "n", "false", "0", "missed" -> "ABSENT";
            case "late", "tardy" -> "LATE";
            case "excused", "excused absence" -> "EXCUSED";
            default -> null;
        };
    }

    // ═════════════════════════════ batch helpers ═════════════════════════════

    public long countReview(Long runId, String table) { return recordRepo.findByRunIdAndTargetTableAndRowStatus(runId, table, "REVIEW").size(); }

    public int tagLoadable(Long runId, String table) {
        int n = 0;
        for (StagingRecord r : recordRepo.findByRunIdAndTargetTableOrderByIdAsc(runId, table))
            if ("VALID".equals(r.getRowStatus()) || "WARN".equals(r.getRowStatus()) || "APPROVED".equals(r.getRowStatus())) { r.setRowStatus("APPROVED"); recordRepo.save(r); n++; }
        return n;
    }

    public void assignBatch(Long runId, String table, Long batchId) {
        for (StagingRecord r : recordRepo.findByRunIdAndTargetTableAndRowStatus(runId, table, "APPROVED")) { r.setBatchId(batchId); recordRepo.save(r); }
    }

    public StagingRecord row(Long rowId, String tenant, String table) {
        return recordRepo.findByIdAndClientId(rowId, tenant).filter(r -> table.equals(r.getTargetTable())).orElse(null);
    }
    public void save(StagingRecord r) { recordRepo.save(r); }
    public List<StagingRecord> rows(Long runId, String table) { return recordRepo.findByRunIdAndTargetTableOrderByIdAsc(runId, table); }
    public List<StagingRecord> batchRows(Long batchId) { return recordRepo.findByBatchIdOrderByIdAsc(batchId); }
    public List<StagingRecord> runRows(Long runId) { return recordRepo.findByRunId(runId); }

    /**
     * Parents this batch still waits for: rows whose required parent, or whose person, is
     * staged in this run but not loaded (or skipped onto a live match) yet. The loader
     * refuses the batch with this message rather than loading half-linked rows.
     */
    public String waitingFor(Long batchId, String table, String tenant) {
        int parents = 0, people = 0;
        Map<Long, StagingRecord> parentRows = new HashMap<>();
        String parentTable = PARENT.get(table);
        if (parentTable != null) {
            List<StagingRecord> rows = batchRows(batchId);
            if (rows.isEmpty()) return null;
            for (StagingRecord p : recordRepo.findByRunIdAndTargetTableOrderByIdAsc(rows.get(0).getRunId(), parentTable)) parentRows.put(p.getId(), p);
        }
        Map<String, StagingFamily> fam = null;
        for (StagingRecord r : batchRows(batchId)) {
            if (!"APPROVED".equals(r.getRowStatus())) continue;
            Map<String, Object> l = links(r);
            String p = (String) l.get("parent");
            if (p != null && p.startsWith("s")) {
                StagingRecord pr = parentRows.get(Long.valueOf(p.substring(1)));
                if (pr == null || !("LOADED".equals(pr.getRowStatus()) || ("SKIPPED".equals(pr.getRowStatus()) && pr.getDedupeMatchId() != null))) parents++;
            }
            String person = (String) l.get("person");
            if (person != null && person.startsWith("s")) {
                if (fam == null) { fam = new HashMap<>(); for (StagingFamily f : familyRepo.findByRunId(r.getRunId())) { if (f.getSourceRowId() != null) fam.put(f.getSourceRowId().trim(), f); if (f.getFamilyKey() != null) fam.putIfAbsent(f.getFamilyKey().trim(), f); } }
                StagingFamily f = fam.get(person.substring(1));
                if (f != null && Set.of("PENDING", "VALID", "WARN", "REVIEW", "APPROVED").contains(f.getRowStatus())) people++;
            }
        }
        if (parents > 0) return parents + " row(s) refer to " + EtlTargetSchema.LABELS.get(parentTable).toLowerCase(Locale.ROOT)
            + " staged in this run that are not loaded yet — approve and load that table first";
        if (people > 0) return people + " row(s) refer to people staged in this run that are not loaded yet — approve and load the people first";
        return null;
    }

    // ═════════════════════════════ load ═════════════════════════════

    /** Per-load caches: the reference resolver session and the groups / classrooms created by this load. */
    public final class LoadCache {
        final EtlReferenceResolver.Session ref;
        final Map<String, Long> createdGroups = new HashMap<>(), createdRooms = new HashMap<>();
        final Map<Long, StagingRecord> parents = new HashMap<>();
        Map<String, StagingFamily> family;
        public LoadCache(String tenant) { this.ref = refResolver.session(tenant); }
    }

    public LoadCache cache(String tenant) { return new LoadCache(tenant); }

    /** Load one chunk of APPROVED rows (the caller supplies the transaction). Returns {inserted, updated, skipped, failed}. */
    public int[] loadChunk(String table, List<Long> ids, String tenant, String actor, Long runId, Long batchId, String system, boolean hasIds, LoadCache cache) {
        int ins = 0, upd = 0, skip = 0, fail = 0;
        for (StagingRecord r : recordRepo.findByIdInAndClientId(ids, tenant)) {
            if (!"APPROVED".equals(r.getRowStatus())) continue;
            String action = effective(r.getDedupeAction(), r.getDedupeMatchId());
            try {
                if ("SKIP".equals(action)) { r.setRowStatus("SKIPPED"); skip++; }
                else if ("UPDATE".equals(action) || "MERGE".equals(action)) {
                    Object existing = live.one(table, r.getDedupeMatchId(), tenant).orElse(null);
                    if (existing == null) throw new IllegalStateException("matched record #" + r.getDedupeMatchId() + " no longer exists");
                    Object candidate = build(table, r, tenant, actor, runId, batchId, system, cache);
                    r.setBeforeImage(snapshot(table, existing));
                    copy(table, candidate, existing, "MERGE".equals(action));
                    live.save(existing);
                    r.setTargetId(r.getDedupeMatchId()); r.setRowStatus("LOADED"); upd++;
                    if (hasIds) link(tenant, system, table, r.getSourceRowId(), r.getDedupeMatchId(), runId, batchId);
                } else {
                    Object entity = build(table, r, tenant, actor, runId, batchId, system, cache);
                    live.save(entity);
                    afterInsert(table, entity, values(r), tenant);
                    Long id = idOf(entity);
                    r.setTargetId(id); r.setRowStatus("LOADED"); ins++;
                    if (hasIds) link(tenant, system, table, r.getSourceRowId(), id, runId, batchId);
                }
            } catch (Exception e) {
                r.setRowStatus("FAILED"); fail++;
                List<Map<String, String>> m = msgs(r.getValidationMsgs());
                m.add(issue("_load", "ERROR", "Load failed: " + e.getMessage()));
                r.setValidationMsgs(json(m));
                log.warn("ETL {} row {} load failed: {}", table, r.getId(), e.getMessage());
            }
            recordRepo.save(r);
        }
        if (linkService != null) linkService.recordReferenceRows(tenant, system, runId, batchId, cache.ref);
        return new int[]{ins, upd, skip, fail};
    }

    /**
     * Fix-ups the entity's own lifecycle would undo on insert: {@code EventRegistration}'s
     * {@code @PrePersist} resets "checked in" (right for a live sign-up), so a migrated
     * attendee is marked checked in afterwards, at the event's date and start time.
     */
    private void afterInsert(String table, Object entity, Map<String, Object> v, String tenant) {
        if (entity instanceof EventRegistration x && Boolean.TRUE.equals(bool(v, "checkedIn"))) {
            ChurchEvent e = live.<ChurchEvent>one("event", x.getEventId().longValue(), tenant).orElse(null);
            LocalDateTime at = null;
            if (e != null && e.getEventDate() != null) {
                at = e.getEventDate().atStartOfDay();
                if (e.getStartTime() != null && e.getStartTime().matches("\\d{2}:\\d{2}"))
                    at = e.getEventDate().atTime(Integer.parseInt(e.getStartTime().substring(0, 2)), Integer.parseInt(e.getStartTime().substring(3)));
            }
            x.setCheckedIn(true);
            x.setCheckedInAt(at == null ? null : java.util.Date.from(at.atZone(java.time.ZoneId.systemDefault()).toInstant()));
            live.save(x);
        }
    }

    private static String effective(String a, Long matchId) {
        if ("SKIP".equals(a)) return "SKIP";
        if ("UPDATE".equals(a) && matchId != null) return "UPDATE";
        if ("MERGE".equals(a) && matchId != null) return "MERGE";
        if ("INSERT".equals(a)) return "INSERT";
        return matchId != null ? "SKIP" : "INSERT";
    }

    private void link(String tenant, String system, String entity, String key, Long id, Long runId, Long batchId) {
        if (linkService == null || blank(key) || id == null) return;
        try { linkService.record(tenant, system, entity, key, id, runId, batchId); } catch (Exception e) { log.warn("ETL link {}:{} not recorded: {}", entity, key, e.getMessage()); }
    }

    private static Long idOf(Object e) { Object id = new BeanWrapperImpl(e).getPropertyValue("id"); return id == null ? null : ((Number) id).longValue(); }

    /** Live id of a parent token: "l<id>", a staged parent's loaded/skipped-onto id, or a created group/classroom. */
    private Long parentId(String tok, String parentTable, String tenant, Long runId, Long batchId, String system, LoadCache cache) {
        if (tok == null) return null;
        if (tok.startsWith("l")) return Long.valueOf(tok.substring(1));
        if (tok.startsWith("s")) {
            Long sid = Long.valueOf(tok.substring(1));
            StagingRecord p = cache.parents.computeIfAbsent(sid, k -> recordRepo.findByIdAndClientId(k, tenant).orElse(null));
            if (p == null) throw new IllegalStateException("the parent row is missing");
            if ("LOADED".equals(p.getRowStatus()) && p.getTargetId() != null) return p.getTargetId();
            if ("SKIPPED".equals(p.getRowStatus()) && p.getDedupeMatchId() != null) return p.getDedupeMatchId();
            throw new IllegalStateException("the " + EtlTargetSchema.LABELS.get(parentTable).toLowerCase(Locale.ROOT) + " row it refers to was not loaded");
        }
        if (tok.startsWith("new:")) return createRef(parentTable, tok.substring(4), tenant, runId, batchId, system, cache);
        return null;
    }

    private Long createRef(String kind, String name, String tenant, Long runId, Long batchId, String system, LoadCache cache) {
        String key = nm(name);
        if ("group".equals(kind)) {
            Long hit = cache.createdGroups.get(key);
            if (hit != null) return hit;
            Group g = live.groups(tenant).stream().filter(x -> nm(x.getGroupName()).equals(key)).findFirst().orElse(null);   // created by an earlier chunk
            if (g == null) {
                g = new Group(); g.setGroupName(name.trim()); g.setAppClientId(tenant); g.setDeleteFlag(false); g.setCreatedDate(new Date());
                live.save(g);
                link(tenant, system, ImportLink.REF_GROUP, name.trim(), g.getId().longValue(), runId, batchId);
            }
            cache.createdGroups.put(key, g.getId().longValue());
            return g.getId().longValue();
        }
        Long hit = cache.createdRooms.get(key);
        if (hit != null) return hit;
        KmClassroom room = live.classrooms(tenant).stream().filter(x -> nm(x.getClassName()).equals(key)).findFirst().orElse(null);
        if (room == null) {
            room = new KmClassroom(); room.setClientId(tenant); room.setClassName(name.trim()); room.setDeleteFlag(false);
            live.save(room);
            link(tenant, system, ImportLink.REF_KM_CLASSROOM, name.trim(), room.getId(), runId, batchId);
        }
        cache.createdRooms.put(key, room.getId());
        return room.getId();
    }

    /** Live member id of a person token ("m<id>", or a staged person now loaded / skipped onto a member). */
    private Integer personId(String tok, Long runId, String tenant, LoadCache cache) {
        if (tok == null) return null;
        if (tok.startsWith("m")) return Integer.valueOf(tok.substring(1));
        if (cache.family == null) {
            cache.family = new HashMap<>();
            for (StagingFamily f : familyRepo.findByRunId(runId)) {
                if (f.getSourceRowId() != null) cache.family.put(f.getSourceRowId().trim(), f);
                if (f.getFamilyKey() != null) cache.family.putIfAbsent(f.getFamilyKey().trim(), f);
            }
        }
        StagingFamily f = cache.family.get(tok.substring(1));
        if (f == null) return null;
        if (f.getTargetId() != null && "LOADED".equals(f.getRowStatus())) return f.getTargetId().intValue();
        if ("SKIPPED".equals(f.getRowStatus()) && f.getDedupeMatchId() != null) return f.getDedupeMatchId().intValue();
        return null;
    }

    private FamilyMember member(Integer id, String tenant) {
        return id == null ? null : live.<FamilyMember>one("family_member", id.longValue(), tenant).orElse(null);
    }

    /** Build the (unsaved) live entity a staged row describes; parents / people / references resolved. */
    Object build(String table, StagingRecord r, String tenant, String actor, Long runId, Long batchId, String system, LoadCache cache) {
        Map<String, Object> v = values(r), l = links(r);
        switch (table) {
            case "pledge_campaign" -> {
                PledgeCampaign x = new PledgeCampaign();
                x.setClientId(tenant); x.setName(str(v, "campaignName")); x.setTargetAmount(dec(v, "targetAmount")); x.setEndDate(date(v, "endDate"));
                x.setDescription(str(v, "description")); x.setStatus(str(v, "status")); x.setOtherGifts(str(v, "otherGifts")); x.setDeleteFlag(false);
                // Collection window starts at created_date (audit M4) — a migrated campaign keeps its real start.
                LocalDate start = date(v, "startDate");
                if (start != null) x.setCreatedDate(start.atStartOfDay());
                String fund = (String) l.get("fund");
                if (fund != null && fund.startsWith("l")) x.setSubSourceId(Integer.valueOf(fund.substring(1)));
                else if (fund != null) x.setSubSourceId(cache.ref.subSource(fund.substring(4), null).getId());
                return x;
            }
            case "pledge" -> {
                PledgeMember x = new PledgeMember();
                x.setClientId(tenant);
                Long cid = parentId((String) l.get("parent"), "pledge_campaign", tenant, runId, batchId, system, cache);
                if (cid == null) throw new IllegalStateException("no campaign");
                x.setCampaignId(cid.intValue());
                Integer mid = personId((String) l.get("person"), runId, tenant, cache);
                FamilyMember m = member(mid, tenant);
                if (m != null) { x.setFamilyMemberId(m.getId()); x.setFamilyId(m.getFamily() == null ? null : m.getFamily().getId()); }
                else {
                    if (str(v, "guestName") == null) throw new IllegalStateException("the person did not resolve and the row has no name");
                    x.setGuestName(str(v, "guestName"));
                }
                x.setPledgeAmount(dec(v, "pledgeAmount")); x.setMonthlyAmount(dec(v, "monthlyAmount"));
                x.setGifts(str(v, "gifts")); x.setNotes(str(v, "notes"));
                // Member pledges compute "collected" from income; the stored figure matters for guest pledges.
                x.setAmountCollected(dec(v, "amountCollected") == null ? BigDecimal.ZERO : dec(v, "amountCollected"));
                x.setDeleteFlag(false);
                return x;
            }
            case "event" -> {
                ChurchEvent x = new ChurchEvent();
                x.setAppClientId(tenant); x.setEventName(str(v, "eventName")); x.setEventDate(date(v, "eventDate"));
                x.setStartTime(str(v, "startTime")); x.setEndTime(str(v, "endTime"));
                x.setEventType(str(v, "eventType") == null ? "One Day" : str(v, "eventType"));
                x.setAddress1(str(v, "address1")); x.setCity(str(v, "city")); x.setState(str(v, "state")); x.setPinCode(str(v, "pinCode"));
                x.setFee(str(v, "fee")); x.setMaxCapacity(integer(v, "maxCapacity")); x.setCreatedBy(actor);
                return x;   // event_code stays null: it is unique across all churches
            }
            case "event_registration" -> {
                EventRegistration x = new EventRegistration();
                Long eid = parentId((String) l.get("parent"), "event", tenant, runId, batchId, system, cache);
                if (eid == null) throw new IllegalStateException("no event");
                x.setEventId(eid.intValue()); x.setClientId(tenant);
                FamilyMember m = member(personId((String) l.get("person"), runId, tenant, cache), tenant);
                x.setFirstName(or(str(v, "firstName"), m == null ? null : m.getFirstName()));
                x.setLastName(or(str(v, "lastName"), m == null ? null : m.getLastName()));
                x.setEmail(or(str(v, "email"), m == null ? null : m.getEmail()));
                x.setPhone(or(str(v, "phone"), m == null ? null : m.getPhone()));
                x.setAdults(integer(v, "adults")); x.setKids(integer(v, "kids"));
                x.setAttending(bool(v, "attending") == null ? Boolean.TRUE : bool(v, "attending"));
                x.setNote(str(v, "note"));
                boolean in = Boolean.TRUE.equals(bool(v, "checkedIn"));
                x.setCheckedIn(in); x.setWalkIn(false);
                return x;
            }
            case "attendance" -> {
                AttendanceRecord x = new AttendanceRecord();
                x.setClientId(tenant); x.setAttendanceDate(date(v, "attendanceDate"));
                Integer mid = personId((String) l.get("person"), runId, tenant, cache);
                if (mid != null) { x.setPersonType("MEMBER"); x.setFamilyMemberId(mid); }
                else x.setPersonType("VISITOR");      // name only; no visitor record exists for it
                x.setPersonName((String) l.get("personName"));
                x.setServiceType(str(v, "serviceType")); x.setMinistry(str(v, "ministry")); x.setCampus(str(v, "campus"));
                x.setStatus(l.get("status") == null ? "PRESENT" : (String) l.get("status"));
                LocalDateTime in = dt(v, "checkInTime"), out = dt(v, "checkOutTime");
                x.setCheckInTime(in); x.setCheckOutTime(out != null ? out : in);   // never "still checked in"
                x.setCheckInMethod("import"); x.setCreatedBy(actor); x.setCreatedDate(LocalDateTime.now()); x.setDeleteFlag(false);
                return x;
            }
            case "group" -> {
                Group x = new Group();
                x.setGroupName(str(v, "groupName")); x.setAppClientId(tenant); x.setDeleteFlag(false); x.setCreatedDate(new Date());
                return x;
            }
            case "group_member" -> {
                Long gid = parentId((String) l.get("parent"), "group", tenant, runId, batchId, system, cache);
                Group g = gid == null ? null : live.<Group>one("group", gid, tenant).orElse(null);
                if (g == null) throw new IllegalStateException("no group");
                GroupMember x = new GroupMember();
                x.setGroup(g); x.setAppClientId(tenant); x.setDeleteFlag(false); x.setCreatedDate(new Date());
                FamilyMember m = member(personId((String) l.get("person"), runId, tenant, cache), tenant);
                if (m != null) x.setFamilyMemberId(m.getId());
                x.setFirstName(or(str(v, "firstName"), m == null ? null : m.getFirstName()));
                x.setLastName(or(str(v, "lastName"), m == null ? null : m.getLastName()));
                x.setEmail(or(str(v, "email"), m == null ? null : m.getEmail()));
                if (blank(x.getFirstName()) || blank(x.getLastName())) throw new IllegalStateException("first and last name are required");
                return x;
            }
            case "km_child" -> {
                KmChild x = new KmChild();
                x.setClientId(tenant); x.setFirstName(str(v, "firstName")); x.setLastName(str(v, "lastName")); x.setDob(date(v, "dob"));
                x.setGender(str(v, "gender")); x.setGrade(str(v, "grade")); x.setAllergies(str(v, "allergies")); x.setMedicalNotes(str(v, "medicalNotes"));
                FamilyMember p = member(personId((String) l.get("person"), runId, tenant, cache), tenant);
                if (p != null) x.setFamilyMemberId(p.getId());
                String pn = p == null ? null : (((p.getFirstName() == null ? "" : p.getFirstName()) + " " + (p.getLastName() == null ? "" : p.getLastName())).trim());
                x.setParentName(or(str(v, "parentName"), pn));
                x.setParentPhone(or(str(v, "parentPhone"), p == null ? null : p.getPhone()));
                x.setParentEmail(or(str(v, "parentEmail"), p == null ? null : p.getEmail()));
                x.setEmergencyContactName(str(v, "emergencyContactName")); x.setEmergencyContactPhone(str(v, "emergencyContactPhone"));
                String room = (String) l.get("classroom");
                if (room != null) x.setClassroomId(room.startsWith("l") ? Long.valueOf(room.substring(1)) : createRef("classroom", room.substring(4), tenant, runId, batchId, system, cache));
                x.setInactive(false); x.setDeleteFlag(false); x.setCreatedDate(LocalDate.now());
                return x;
            }
            case "km_checkin" -> {
                Long childId = parentId((String) l.get("parent"), "km_child", tenant, runId, batchId, system, cache);
                KmChild child = childId == null ? null : live.<KmChild>one("km_child", childId, tenant).orElse(null);
                if (child == null) throw new IllegalStateException("no child");
                KmCheckin x = new KmCheckin();
                x.setClientId(tenant); x.setChildId(child.getId());
                String room = (String) l.get("classroom");
                x.setClassroomId(room == null ? child.getClassroomId()
                    : room.startsWith("l") ? Long.valueOf(room.substring(1)) : createRef("classroom", room.substring(4), tenant, runId, batchId, system, cache));
                LocalDateTime in = dt(v, "checkinTime"), out = dt(v, "checkoutTime");
                x.setCheckinTime(in);
                // An imported check-in is history: always checked out, so pickup alerts and the
                // "currently checked in" lists never pick it up.
                x.setCheckoutTime(out != null ? out : in);
                x.setCheckoutMethod("import");
                x.setCheckedOutBy(str(v, "checkedOutBy")); x.setNotes(str(v, "notes"));
                return x;
            }
            default -> throw new IllegalArgumentException("Unknown record table " + table);
        }
    }

    private static String or(String a, String b) { return blank(a) ? b : a; }

    /** UPDATE copies every non-null field of the candidate; MERGE only fills fields the live row has empty. */
    static void copy(String table, Object from, Object to, boolean merge) {
        BeanWrapper f = new BeanWrapperImpl(from), t = new BeanWrapperImpl(to);
        for (String p : FIELDS.get(table)) {
            Object nv = f.getPropertyValue(p);
            if (nv == null) continue;
            Object cur = t.getPropertyValue(p);
            boolean empty = cur == null || (cur instanceof String s && s.isBlank());
            if (!merge || empty) t.setPropertyValue(p, nv);
        }
    }

    /** Before-image of the updatable fields; money as plain decimal strings, dates ISO. */
    static String snapshot(String table, Object e) {
        BeanWrapper w = new BeanWrapperImpl(e);
        Map<String, Object> snap = new LinkedHashMap<>();
        for (String p : FIELDS.get(table)) snap.put(p, TransformStageService.jsonValue(w.getPropertyValue(p)));
        return json(snap);
    }

    static void restore(String table, Object e, String beforeImage) {
        Map<String, Object> snap = readMap(beforeImage);
        BeanWrapper w = new BeanWrapperImpl(e);
        for (String p : FIELDS.get(table)) {
            if (!snap.containsKey(p)) continue;
            Object raw = snap.get(p);
            Class<?> type = w.getPropertyType(p);
            Object val;
            if (raw == null) val = null;
            else if (type == BigDecimal.class) val = new BigDecimal(String.valueOf(raw));
            else if (type == LocalDate.class) val = LocalDate.parse(String.valueOf(raw));
            else if (type == LocalDateTime.class) val = LocalDateTime.parse(String.valueOf(raw));
            else if (type == Integer.class || type == int.class) val = ((Number) raw).intValue();
            else if (type == Long.class || type == long.class) val = ((Number) raw).longValue();
            else if (type == Boolean.class || type == boolean.class) val = raw;
            else val = String.valueOf(raw);
            if (val == null && type != null && type.isPrimitive()) continue;
            w.setPropertyValue(p, val);
        }
    }

    // ═════════════════════════════ rollback ═════════════════════════════

    private static final Set<String> HARD_DELETE = Set.of("event_registration", "km_checkin");

    /**
     * Reverse a LOADED / FAILED batch of a record table. Refuses — before changing anything —
     * when a campaign / event / group / child this batch created now has records under it
     * that the import did not create (the church has built on it).
     */
    public int rollback(ImportBatch batch, String tenant) {
        String table = batch.getTargetTable();
        List<StagingRecord> rows = batchRows(batch.getId()).stream()
            .filter(r -> "LOADED".equals(r.getRowStatus()) && r.getTargetId() != null).toList();
        if (Set.of("pledge_campaign", "event", "group", "km_child").contains(table)) {
            List<String> blocked = new ArrayList<>();
            for (StagingRecord r : rows) {
                if (r.getBeforeImage() != null) continue;              // an update is restored, never removed
                long n = live.dependents(table, r.getTargetId(), tenant);
                if (n > 0) blocked.add("#" + r.getTargetId() + " (" + n + ")");
            }
            if (!blocked.isEmpty())
                throw new EtlLoadService.LoadException(blocked.size() + " " + EtlTargetSchema.LABELS.get(table).toLowerCase(Locale.ROOT)
                    + " created by this import now have records added outside it: " + String.join(", ", blocked.subList(0, Math.min(5, blocked.size())))
                    + " — remove those in the app first, or keep this import");
        }
        int n = 0;
        for (StagingRecord r : rows) {
            Object x = live.one(table, r.getTargetId(), tenant).orElse(null);
            if (x != null) {
                if (r.getBeforeImage() != null) { restore(table, x, r.getBeforeImage()); live.save(x); }
                else if (HARD_DELETE.contains(table)) live.remove(table, r.getTargetId(), tenant);
                else { new BeanWrapperImpl(x).setPropertyValue("deleteFlag", true); live.save(x); }
            }
            r.setRowStatus("ROLLED_BACK"); recordRepo.save(r); n++;
        }
        return n;
    }
}
