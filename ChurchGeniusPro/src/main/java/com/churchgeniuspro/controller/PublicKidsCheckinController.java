package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.KmCheckin;
import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.KmCheckinRepository;
import com.churchgeniuspro.repository.KmChildRepository;
import com.churchgeniuspro.util.PublicSendLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.*;

/**
 * Public-facing Kids Check-In endpoints.
 *
 * <p>The public can hit these endpoints without a session as long as they
 * supply a valid AES-encrypted client ID (the {@code cid} query parameter
 * minted by {@link PublicScreensController}). All work is scoped to the
 * decrypted client ID; the user never sees the raw value.
 *
 * <h3>Routes</h3>
 * <ul>
 *   <li>{@code GET  /kidsCheckin}                       — serves the public page</li>
 *   <li>{@code GET  /api/public/kids-checkin/search}    — find children by parent phone</li>
 *   <li>{@code POST /api/public/kids-checkin/submit}    — record a kid+guardian check-in</li>
 * </ul>
 *
 * <p>Barcode lookup &amp; checkout are intentionally NOT here — those are
 * staff-only and live on the authenticated {@code KidsMinistryController}.
 */
@Controller
public class PublicKidsCheckinController {

    /** A full North-American number; anything shorter is not a lookup key. */
    static final int MIN_PHONE_DIGITS = 10;

    /** A shared family code issued earlier than this is not reused for a new row. */
    static final long CODE_REUSE_WINDOW_MINUTES = 15;

    private final KmChildRepository      childRepo;
    private final KmCheckinRepository    checkinRepo;
    private final FamilyMemberRepository familyMemberRepo;

    private final com.churchgeniuspro.service.PublicLinkResolver links;
    private final PublicSendLimiter sendLimiter;
    private final java.security.SecureRandom codeRandom = new java.security.SecureRandom();

    public PublicKidsCheckinController(KmChildRepository childRepo,
                                       KmCheckinRepository checkinRepo,
                                       FamilyMemberRepository familyMemberRepo,
            com.churchgeniuspro.service.PublicLinkResolver links,
            PublicSendLimiter sendLimiter) {
        this.links = links;
        this.sendLimiter = sendLimiter;
        this.childRepo        = childRepo;
        this.checkinRepo      = checkinRepo;
        this.familyMemberRepo = familyMemberRepo;
    }

    // ── Page route (no auth) ─────────────────────────────────────────────

    @GetMapping("/kidsCheckin")
    public String page() {
        return "forward:/kidsCheckin.html";
    }

    // ── Search children by parent phone ──────────────────────────────────

    /**
     * The kiosk's lookup: the household's children and designated guardians for
     * the full phone number a parent types.
     *
     * <p>What comes back is only what the kiosk shows to pick people: names, age
     * and grade, an allergy flag, and guardian names/roles. Dates of birth, the
     * allergy text, classroom, family ids and every phone number / e-mail stay
     * server-side — anyone holding the kiosk link and a parent's number could
     * otherwise pull a child's full profile (security audit P3). Bounded per
     * network origin and per kiosk link per day.
     */
    @ResponseBody
    @GetMapping("/api/public/kids-checkin/search")
    public ResponseEntity<?> search(@RequestParam("cid") String encryptedCid,
                                    @RequestParam(value = "phone", defaultValue = "") String phone,
                                    HttpServletRequest request) {
        String clientId = decryptCid(encryptedCid);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Invalid link"));

        String limited = sendLimiter.check(PublicSendLimiter.KIDS_CHECKIN_SEARCH, request, null, encryptedCid);
        if (limited != null) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many searches. Please wait a moment and try again."));
        }
        String digits = onlyDigits(phone);
        if (digits.length() < MIN_PHONE_DIGITS) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter the full phone number"));
        }

        List<Map<String, Object>> children = new ArrayList<>();
        for (Household h : households(clientId, digits)) {
            KmChild c = h.child();
            List<Map<String, Object>> guardians = new ArrayList<>();
            if (h.guardian() != null) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("id",        h.guardian().getId());
                g.put("firstName", h.guardian().getFirstName());
                g.put("lastName",  h.guardian().getLastName());
                g.put("role",      h.guardian().getRole());
                guardians.add(g);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",         c.getId());
            row.put("firstName",  c.getFirstName());
            row.put("lastName",   c.getLastName());
            row.put("age",        c.getDob() != null ? Period.between(c.getDob(), LocalDate.now()).getYears() : null);
            row.put("grade",      c.getGrade());
            row.put("hasAllergy", c.getAllergies() != null && !c.getAllergies().isBlank());
            row.put("guardians",  guardians);
            children.add(row);
        }
        return ResponseEntity.ok(Map.of("children", children));
    }

    /** One child the phone number resolves to, with the guardian the search would surface for it. */
    private record Household(KmChild child, Integer familyId, FamilyMember guardian) {}

    /**
     * The households a parent's number unlocks — the same resolution for search and
     * for submit, so a check-in can only ever name a child (or guardian) that the
     * number it was made with would have shown.
     */
    private List<Household> households(String clientId, String phoneDigits) {
        List<Household> out = new ArrayList<>();
        for (KmChild c : childRepo.findByClientIdAndParentPhone(clientId, phoneDigits)) {
            Integer fid = null;
            FamilyMember guardian = null;
            if (c.getFamilyMemberId() != null) {
                FamilyMember linked = familyMemberRepo.findByIdAndTenant(c.getFamilyMemberId(), clientId).orElse(null);
                if (linked != null && linked.getFamily() != null) fid = linked.getFamily().getId();
            }
            if (fid != null) {
                // Only the FamilyMember whose name matches the child's registered
                // parent (or, failing that, the phone) — never the whole household.
                guardian = pickRegisteredGuardian(familyMemberRepo.findActiveMembersByFamilyId(fid),
                                                  c.getParentName(), c.getParentPhone());
            }
            out.add(new Household(c, fid, guardian));
        }
        return out;
    }

    /**
     * Picks the registered guardian for a child by matching on the
     * KmChild.parentName / parentPhone fields against the family roster.
     *
     * Priority:
     *   1. Exact case-insensitive match on "First Last" → ParentName
     *   2. Exact case-insensitive match on first or last name alone
     *   3. Phone-number digit match against parentPhone
     *   4. null — no guardian surfaced to the public UI
     */
    private FamilyMember pickRegisteredGuardian(List<FamilyMember> members,
                                                String parentName,
                                                String parentPhone) {
        if (members == null || members.isEmpty()) return null;
        String wantName  = parentName  == null ? "" : parentName.trim().toLowerCase();
        String wantPhone = onlyDigits(parentPhone);

        // Only consider adult/guardian-shaped roles (drop kids).
        List<FamilyMember> adults = new ArrayList<>();
        for (FamilyMember m : members) {
            String role = m.getRole() == null ? "" : m.getRole().toLowerCase();
            if (role.equals("child") || role.equals("son") || role.equals("daughter")) continue;
            adults.add(m);
        }
        if (adults.isEmpty()) return null;

        // 1) Full-name match
        if (!wantName.isEmpty()) {
            for (FamilyMember m : adults) {
                String full = (safeLower(m.getFirstName()) + " " + safeLower(m.getLastName())).trim();
                if (full.equals(wantName)) return m;
            }
            // 2) First-or-last alone
            for (FamilyMember m : adults) {
                if (safeLower(m.getFirstName()).equals(wantName)) return m;
                if (safeLower(m.getLastName()).equals(wantName))  return m;
            }
        }

        // 3) Phone digits
        if (!wantPhone.isEmpty()) {
            for (FamilyMember m : adults) {
                String d = onlyDigits(m.getPhone());
                if (!d.isEmpty() && (d.equals(wantPhone) || d.endsWith(wantPhone) || wantPhone.endsWith(d))) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String safeLower(String s) { return s == null ? "" : s.trim().toLowerCase(); }

    // ── Submit check-in (kid only / parent only / both) ──────────────────

    /**
     * Records a check-in for a child and/or a guardian.
     *
     * <p>The body carries the phone number the kiosk searched with, and a child or
     * guardian is accepted only if that number would have surfaced them — the same
     * household rule as {@link #search}. Ids alone are small integers; the number is
     * the thing the parent actually knew. The shared family code on the labels is
     * issued here: a caller may pass back a code this endpoint issued moments ago
     * for the same household (so one submission per person still prints one code),
     * but never a code of its own choosing (security audit P3).
     */
    @ResponseBody
    @PostMapping("/api/public/kids-checkin/submit")
    public ResponseEntity<?> submit(@RequestBody Map<String, Object> body) {
        String clientId = decryptCid(str(body.get("cid")));
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Invalid link"));

        Long    childId      = toLong(body.get("childId"));      // null when parent-only
        Integer guardianId   = toInt(body.get("guardianMemberId")); // null when kid-only
        if (childId == null && guardianId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Pick at least one person to check in"));
        }
        String digits = onlyDigits(str(body.get("phone")));
        if (digits.length() < MIN_PHONE_DIGITS) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter the full phone number"));
        }
        List<Household> households = households(clientId, digits);

        // The child must be one the phone number resolves to.
        KmChild child = null;
        if (childId != null) {
            child = households.stream().map(Household::child)
                    .filter(c -> childId.equals(c.getId())).findFirst().orElse(null);
            if (child == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Child not found"));
            }
            // Prevent duplicate active check-in for the same child.
            List<KmCheckin> active = checkinRepo.findActiveForChild(clientId, childId);
            if (!active.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Child is already checked in"));
            }
        }
        // The guardian must be the designated guardian of one of those children —
        // and, when a child is named, of that child's household.
        FamilyMember guardian = null;
        if (guardianId != null) {
            final KmChild forChild = child;
            guardian = households.stream()
                    .filter(h -> h.guardian() != null && guardianId.equals(h.guardian().getId()))
                    .filter(h -> forChild == null || forChild.getId().equals(h.child().getId()))
                    .map(Household::guardian).findFirst().orElse(null);
            if (guardian == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Guardian not found"));
            }
        }

        // One shared code per family submission: reuse a code this endpoint issued in
        // the last few minutes for the same household (the kiosk posts one row per
        // person), otherwise mint a fresh one. Caller-chosen codes are never accepted.
        LocalDateTime now = LocalDateTime.now();
        String code = reusableCode(clientId, str(body.get("familyCheckinCode")), households, now);
        if (code == null) code = generateCode();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("familyCheckinCode", code);
        result.put("checkinTime",       now.toString());

        if (child != null) {
            KmCheckin row = new KmCheckin();
            row.setClientId(clientId);
            row.setChildId(child.getId());
            row.setClassroomId(child.getClassroomId());
            row.setCheckinTime(now);
            row.setSecurityCode(code);            // share the same code on the label
            row.setFamilyCheckinCode(code);
            checkinRepo.save(row);
            Map<String, Object> kidOut = new LinkedHashMap<>();
            kidOut.put("id",       row.getId());
            kidOut.put("name",     trim(child.getFirstName()) + " " + trim(child.getLastName()));
            kidOut.put("kind",     "child");
            result.put("child", kidOut);
        }
        if (guardianId != null) {
            FamilyMember g = guardian;
            KmCheckin row = new KmCheckin();
            row.setClientId(clientId);
            row.setGuardianMemberId(guardianId);
            row.setCheckinTime(now);
            row.setSecurityCode(code);
            row.setFamilyCheckinCode(code);
            checkinRepo.save(row);
            Map<String, Object> gOut = new LinkedHashMap<>();
            gOut.put("id",   row.getId());
            gOut.put("name", g != null
                    ? (trim(g.getFirstName()) + " " + trim(g.getLastName())).trim() : "Guardian");
            gOut.put("kind", "guardian");
            gOut.put("role", g != null ? g.getRole() : null);
            result.put("guardian", gOut);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * {@code supplied}, when it is a code this endpoint issued within
     * {@link #CODE_REUSE_WINDOW_MINUTES} for a child or guardian of one of these
     * households; otherwise {@code null}.
     */
    private String reusableCode(String clientId, String supplied, List<Household> households, LocalDateTime now) {
        if (supplied == null || !supplied.matches("[A-Z0-9\\-]{1,20}")) return null;
        Set<Long> childIds = new HashSet<>();
        Set<Integer> guardianIds = new HashSet<>();
        for (Household h : households) {
            childIds.add(h.child().getId());
            if (h.guardian() != null) guardianIds.add(h.guardian().getId());
        }
        for (KmCheckin row : checkinRepo.findByClientIdAndFamilyCheckinCode(clientId, supplied)) {
            boolean recent = row.getCheckinTime() != null
                    && !row.getCheckinTime().isBefore(now.minusMinutes(CODE_REUSE_WINDOW_MINUTES));
            boolean sameHousehold = (row.getChildId() != null && childIds.contains(row.getChildId()))
                    || (row.getGuardianMemberId() != null && guardianIds.contains(row.getGuardianMemberId()));
            if (recent && sameHousehold) return supplied;
        }
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String decryptCid(String cid) {
        // A live Kids Check-In link token only. A church that never published the
        // page, or revoked its link, has no check-in kiosk — by design.
        return links.resolveClientId(cid, com.churchgeniuspro.service.PublicPagePolicy.KIDS_CHECKIN_URL);
    }

    private static String onlyDigits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * A letter, a dash, four digits — the shape a family reads off the label at pickup, and
     * the shape {@code KidsMinistryController#generateSecurityCode} issues at the desk. Drawn
     * from {@link java.security.SecureRandom} so one code seen in the lobby does not predict
     * the next. I and O are left out of the letters: printed small, they are read back as 1 and 0.
     */
    private String generateCode() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        return String.valueOf(letters.charAt(codeRandom.nextInt(letters.length())))
                + "-" + String.format("%04d", codeRandom.nextInt(10000));
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static Long toLong(Object v) {
        if (v == null) return null;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }
    private static Integer toInt(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

}
