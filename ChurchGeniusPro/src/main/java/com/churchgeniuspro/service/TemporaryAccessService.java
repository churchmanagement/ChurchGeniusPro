package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AccessAudit;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.TemporaryAccess;
import com.churchgeniuspro.hibernate.VolunteerProfile;
import com.churchgeniuspro.repository.AccessAuditRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.TemporaryAccessRepository;
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import com.churchgeniuspro.util.PasswordUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business logic for Temporary Access passes: catalog of grantable pages, secure
 * barcode/code generation (code stored hashed), create/list/extend/revoke,
 * validation of the barcode + code within the time window, and the audit trail.
 */
@Service
public class TemporaryAccessService {

    private static final Logger log = LoggerFactory.getLogger(TemporaryAccessService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemporaryAccessRepository accessRepo;
    private final AccessAuditRepository     auditRepo;
    private final FamilyMemberRepository    memberRepo;
    private final VolunteerProfileRepository volunteerRepo;

    public TemporaryAccessService(TemporaryAccessRepository accessRepo,
                                  AccessAuditRepository auditRepo,
                                  FamilyMemberRepository memberRepo,
                                  VolunteerProfileRepository volunteerRepo) {
        this.accessRepo = accessRepo;
        this.auditRepo  = auditRepo;
        this.memberRepo = memberRepo;
        this.volunteerRepo = volunteerRepo;
    }

    // ── People directory: members + general volunteers (deduplicated) ──────────
    // A volunteer is a family member with a VolunteerProfile, so the family-member
    // list already covers both; we only add an isVolunteer flag. Child-role members
    // are flagged so the UI can skip the email auto-fill for them.
    private static final java.util.regex.Pattern CHILD_ROLE =
            java.util.regex.Pattern.compile("child|son|daughter|infant|toddler|kid", java.util.regex.Pattern.CASE_INSENSITIVE);

    public List<Map<String, Object>> peopleDirectory(String appClientId) {
        Set<Integer> volunteerIds = volunteerRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(appClientId)
                .stream().map(VolunteerProfile::getFamilyMemberId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        List<Map<String, Object>> out = new ArrayList<>();
        for (FamilyMember m : memberRepo.findAllWithFamilyByAppUser(appClientId)) {
            String name = ((m.getFirstName() != null ? m.getFirstName() : "") + " "
                         + (m.getLastName()  != null ? m.getLastName()  : "")).trim();
            if (name.isEmpty()) continue;
            boolean isVolunteer = volunteerIds.contains(m.getId());
            boolean isChild = m.getRole() != null && CHILD_ROLE.matcher(m.getRole()).find();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", m.getId());
            p.put("name", name);
            p.put("email", m.getEmail());
            p.put("phone", m.getPhone());
            // Prefer the small thumbnail for the badge; fall back to the full photo.
            p.put("photo", m.getPhotoThumbnail() != null && !m.getPhotoThumbnail().isBlank()
                    ? m.getPhotoThumbnail() : m.getPhotoData());
            p.put("isVolunteer", isVolunteer);
            p.put("isChild", isChild);
            p.put("source", isVolunteer ? "Volunteer" : "Member");
            out.add(p);
        }
        // Sort by name for a tidy dropdown.
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    // ── Grantable page catalog (General-section pages only) ────────────────────
    // Temporary passes are intentionally scoped to operational / front-desk pages,
    // never Accounting or Admin/user-management. Each entry's permKey is gated by
    // the existing RoleGuard.requirePermission checks on that page + its APIs.

    public record PageDef(String key, String label, String route) {}

    public static final List<PageDef> PAGE_CATALOG = List.of(
            new PageDef("general.meetings",        "Meetings",         "/meetings"),
            new PageDef("general.events",          "Events",           "/events"),
            new PageDef("general.eventcheckin",    "Event Check-in",   "/event-checkin-admin"),
            new PageDef("general.kidsministry",    "Kids Ministry",    "/kidsMinistry"),
            new PageDef("general.worshipplanning", "Worship Planning", "/memberWorship"),
            new PageDef("general.sundayschool",    "Sunday School",    "/sundaySchool"),
            new PageDef("general.volunteers",      "Volunteers",       "/volunteers")
    );

    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PageDef p : PAGE_CATALOG) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("label", p.label());
            m.put("route", p.route());
            out.add(m);
        }
        return out;
    }

    private boolean isCatalogKey(String key) {
        return PAGE_CATALOG.stream().anyMatch(p -> p.key().equals(key));
    }

    /** Route of the first permitted page (used to land the user after login). */
    public String firstRoute(String permissionsCsv) {
        List<String> keys = splitPerms(permissionsCsv);
        for (PageDef p : PAGE_CATALOG) if (keys.contains(p.key())) return p.route();
        return "/access-denied.html";
    }

    /**
     * Builds the session privileges JSON from the pass's permitted keys: every
     * catalog key is written explicitly (true if granted, false otherwise) so the
     * opt-in-denial {@code requirePermission} blocks non-granted pages.
     */
    public String buildPrivilegesJson(String permissionsCsv) {
        List<String> granted = splitPerms(permissionsCsv);
        Map<String, Object> map = new LinkedHashMap<>();
        for (PageDef p : PAGE_CATALOG) map.put(p.key(), granted.contains(p.key()));
        try { return MAPPER.writeValueAsString(map); }
        catch (Exception e) { return "{}"; }
    }

    private List<String> splitPerms(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    // ── Token + code generation ────────────────────────────────────────────────

    /** Unique, non-guessable badge token, e.g. {@code TAC-7F3A...} (24 hex chars). */
    private String generateBarcode() {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] buf = new byte[12];
            RNG.nextBytes(buf);
            StringBuilder sb = new StringBuilder("TAC-");
            for (byte b : buf) sb.append(String.format("%02X", b));
            String candidate = sb.toString();
            if (!accessRepo.existsByBarcodeValue(candidate)) return candidate;
        }
        // Extremely unlikely fallback
        return "TAC-" + System.nanoTime() + RNG.nextInt(1_000_000);
    }

    /** Random 6-digit numeric code (000000–999999, zero-padded). */
    private String generateCode() {
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    // ── CRUD ────────────────────────────────────────────────────────────────────

    /** Result of a create — carries the ONE-TIME plaintext code for printing/email. */
    public record CreateResult(TemporaryAccess access, String plainCode) {}

    public CreateResult create(String clientId, String createdBy, String holderName, String email,
                               String phone, String role, String holderPhoto, List<String> badgeFields,
                               LocalDateTime start, LocalDateTime end, List<String> pageKeys) {
        List<String> clean = (pageKeys == null ? List.<String>of() : pageKeys).stream()
                .filter(this::isCatalogKey).distinct().collect(Collectors.toList());
        String badgeCsv = (badgeFields == null || badgeFields.isEmpty())
                ? "" : badgeFields.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(","));

        String plainCode = generateCode();
        TemporaryAccess a = new TemporaryAccess();
        a.setClientId(clientId);
        a.setCreatedBy(createdBy);
        a.setHolderName(holderName);
        a.setEmail(email);
        a.setPhone(phone);
        a.setRole(role);
        a.setHolderPhoto(holderPhoto);
        a.setBadgeFields(badgeCsv);
        a.setStartDateTime(start);
        a.setEndDateTime(end);
        a.setBarcodeValue(generateBarcode());
        a.setAccessCodeHash(PasswordUtil.encode(plainCode));   // hashed, never plaintext
        a.setPermissions(String.join(",", clean));
        a.setStatus("ACTIVE");
        a.setDeleteFlag(false);
        accessRepo.save(a);
        return new CreateResult(a, plainCode);
    }

    public List<TemporaryAccess> list(String clientId) {
        return accessRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(clientId);
    }

    public Optional<TemporaryAccess> find(Long id, String clientId) {
        return accessRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId);
    }

    /** Extend (and, if it had lapsed, reactivate) without touching live sessions. */
    public Optional<TemporaryAccess> extend(Long id, String clientId, LocalDateTime newEnd) {
        return find(id, clientId).map(a -> {
            if (newEnd != null) a.setEndDateTime(newEnd);
            if ("REVOKED".equals(a.getStatus())) a.setStatus("ACTIVE");
            accessRepo.save(a);
            return a;
        });
    }

    public Optional<TemporaryAccess> revoke(Long id, String clientId) {
        return find(id, clientId).map(a -> { a.setStatus("REVOKED"); accessRepo.save(a); return a; });
    }

    /** Soft-delete a pass (removes it from the Active &amp; Recent list). */
    public boolean softDelete(Long id, String clientId) {
        return find(id, clientId).map(a -> { a.setDeleteFlag(true); a.setStatus("REVOKED"); accessRepo.save(a); return true; })
                .orElse(false);
    }

    /** Routes the pass is permitted to reach (used to filter the temp user's nav). */
    public List<String> permittedRoutes(String permissionsCsv) {
        List<String> keys = splitPerms(permissionsCsv);
        List<String> routes = new ArrayList<>();
        for (PageDef p : PAGE_CATALOG) if (keys.contains(p.key())) routes.add(p.route());
        return routes;
    }

    /**
     * Granted pages as {key,label,route} in catalog order — used to BUILD the
     * temp user's sidebar nav directly (the standard menu uses different hrefs/
     * aliases and omits some of these pages, so filtering it can't surface them).
     */
    public List<Map<String, Object>> permittedPages(String permissionsCsv) {
        List<String> keys = splitPerms(permissionsCsv);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PageDef p : PAGE_CATALOG) {
            if (!keys.contains(p.key())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("label", p.label());
            m.put("route", p.route());
            out.add(m);
        }
        return out;
    }

    // ── Validation + status ──────────────────────────────────────────────────────

    public enum CheckResult { OK, NOT_FOUND, REVOKED, NOT_STARTED, EXPIRED, BAD_CODE }

    /** Effective status for display: REVOKED → EXPIRED → SCHEDULED → ACTIVE. */
    public String effectiveStatus(TemporaryAccess a) {
        if (a == null) return "UNKNOWN";
        if ("REVOKED".equals(a.getStatus())) return "REVOKED";
        LocalDateTime now = LocalDateTime.now();
        if (a.getEndDateTime() != null && now.isAfter(a.getEndDateTime())) return "EXPIRED";
        if (a.getStartDateTime() != null && now.isBefore(a.getStartDateTime())) return "SCHEDULED";
        return "ACTIVE";
    }

    /** True while the pass is live right now (active + inside the time window). */
    public boolean isLiveNow(TemporaryAccess a) {
        return "ACTIVE".equals(effectiveStatus(a));
    }

    /**
     * Validates a barcode + 6-digit code against the time window and the hash.
     * Returns the matched pass alongside the result (null when NOT_FOUND).
     */
    public record Validation(CheckResult result, TemporaryAccess access) {}

    public Validation validate(String barcodeValue, String code) {
        Optional<TemporaryAccess> opt = (barcodeValue == null) ? Optional.empty()
                : accessRepo.findByBarcodeValueAndDeleteFlagFalse(barcodeValue.trim());
        if (opt.isEmpty()) return new Validation(CheckResult.NOT_FOUND, null);
        TemporaryAccess a = opt.get();
        if ("REVOKED".equals(a.getStatus())) return new Validation(CheckResult.REVOKED, a);
        LocalDateTime now = LocalDateTime.now();
        if (a.getStartDateTime() != null && now.isBefore(a.getStartDateTime()))
            return new Validation(CheckResult.NOT_STARTED, a);
        if (a.getEndDateTime() != null && now.isAfter(a.getEndDateTime()))
            return new Validation(CheckResult.EXPIRED, a);
        if (code == null || !PasswordUtil.matches(code.trim(), a.getAccessCodeHash()))
            return new Validation(CheckResult.BAD_CODE, a);
        return new Validation(CheckResult.OK, a);
    }

    // ── Audit ────────────────────────────────────────────────────────────────────

    public AccessAudit recordLogin(TemporaryAccess a, String deviceInfo, String ip) {
        AccessAudit au = new AccessAudit();
        au.setTemporaryAccessId(a.getId());
        au.setClientId(a.getClientId());
        au.setLoginTime(LocalDateTime.now());
        au.setDeviceInfo(deviceInfo);
        au.setIpAddress(ip);
        return auditRepo.save(au);
    }

    public void recordLogout(Long auditId) {
        if (auditId == null) return;
        auditRepo.findById(auditId).ifPresent(au -> {
            if (au.getLogoutTime() == null) { au.setLogoutTime(LocalDateTime.now()); auditRepo.save(au); }
        });
    }

    public List<AccessAudit> auditFor(Long temporaryAccessId) {
        return auditRepo.findByTemporaryAccessIdOrderByLoginTimeDesc(temporaryAccessId);
    }
}
