package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.KmCheckin;
import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.KmCheckinRepository;
import com.churchgeniuspro.repository.KmChildRepository;
import com.churchgeniuspro.util.EncryptionUtil;
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

    private final KmChildRepository      childRepo;
    private final KmCheckinRepository    checkinRepo;
    private final FamilyMemberRepository familyMemberRepo;

    public PublicKidsCheckinController(KmChildRepository childRepo,
                                       KmCheckinRepository checkinRepo,
                                       FamilyMemberRepository familyMemberRepo) {
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

    @ResponseBody
    @GetMapping("/api/public/kids-checkin/search")
    public ResponseEntity<?> search(@RequestParam("cid") String encryptedCid,
                                    @RequestParam(value = "phone", defaultValue = "") String phone) {
        String clientId = decryptCid(encryptedCid);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Invalid link"));
        String digits = onlyDigits(phone);
        if (digits.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter at least the last 4 digits of the phone number"));
        }
        List<KmChild> kids = childRepo.findByClientIdAndParentPhone(clientId, digits);

        // Build per-child guardian lists. The OLD behavior pulled in every
        // non-child family-member, which surfaced unrelated household members
        // (e.g. a different parent / extended family) the church-staff hadn't
        // designated as the guardian for THIS child.
        //
        // New rule: the guardian for a kid is the FamilyMember whose name
        // matches the kid's KmChild.parentName (the value the staff typed on
        // the Register Child form). Falls back to matching by phone if the
        // name isn't a clean match. If still no hit, no guardian row is
        // surfaced — the parent can fall back to manual entry.
        List<Map<String, Object>> children = new ArrayList<>();
        for (KmChild c : kids) {
            Integer fid = null;
            if (c.getFamilyMemberId() != null) {
                FamilyMember linked = familyMemberRepo.findById(c.getFamilyMemberId()).orElse(null);
                if (linked != null && linked.getFamily() != null) fid = linked.getFamily().getId();
            }

            List<Map<String, Object>> guardians = new ArrayList<>();
            if (fid != null) {
                List<FamilyMember> all = familyMemberRepo.findActiveMembersByFamilyId(fid);
                FamilyMember match = pickRegisteredGuardian(all, c.getParentName(), c.getParentPhone());
                if (match != null) {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("id",        match.getId());
                    g.put("firstName", match.getFirstName());
                    g.put("lastName",  match.getLastName());
                    g.put("role",      match.getRole());
                    g.put("phone",     match.getPhone());
                    g.put("email",     match.getEmail());
                    guardians.add(g);
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",          c.getId());
            row.put("firstName",   c.getFirstName());
            row.put("lastName",    c.getLastName());
            row.put("dob",         c.getDob() != null ? c.getDob().toString() : null);
            row.put("age",         c.getDob() != null
                    ? Period.between(c.getDob(), LocalDate.now()).getYears() : null);
            row.put("grade",       c.getGrade());
            row.put("parentName",  c.getParentName());
            row.put("parentPhone", c.getParentPhone());
            row.put("parentEmail", c.getParentEmail());
            row.put("allergies",   c.getAllergies());
            row.put("classroomId", c.getClassroomId());
            row.put("familyId",    fid);
            row.put("guardians",   guardians);
            children.add(row);
        }
        return ResponseEntity.ok(Map.of("children", children));
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

        // Validate child ownership: if a childId was sent, it must belong to
        // this clientId. (Prevents cross-org check-ins via a stale cid.)
        KmChild child = null;
        if (childId != null) {
            child = childRepo.findById(childId).orElse(null);
            if (child == null || !clientId.equals(child.getClientId())) {
                return ResponseEntity.status(404).body(Map.of("error", "Child not found"));
            }
            // Prevent duplicate active check-in for the same child.
            List<KmCheckin> active = checkinRepo.findActiveForChild(clientId, childId);
            if (!active.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Child is already checked in"));
            }
        }
        if (guardianId != null) {
            FamilyMember g = familyMemberRepo.findById(guardianId).orElse(null);
            if (g == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Guardian not found"));
            }
        }

        // Caller may pre-supply a familyCheckinCode so multiple submissions
        // (one per child/guardian) stamp the same shared code on every row.
        // Falls back to a freshly-generated code when omitted, preserving
        // the original single-row behaviour. The code is sanity-checked to
        // stay within the column width and avoid SQL/JSON injection vectors
        // — only the [A-Z0-9-] subset our generator already uses is allowed.
        String supplied = str(body.get("familyCheckinCode"));
        String code = (supplied != null && supplied.matches("[A-Z0-9\\-]{1,20}"))
                ? supplied : generateCode();
        LocalDateTime now = LocalDateTime.now();

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
            FamilyMember g = familyMemberRepo.findById(guardianId).orElse(null);
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

    // ── Helpers ──────────────────────────────────────────────────────────

    private String decryptCid(String encryptedCid) {
        if (encryptedCid == null || encryptedCid.isBlank()) return null;
        try {
            String plain = EncryptionUtil.decrypt(encryptedCid.trim());
            return (plain != null && !plain.isBlank()) ? plain : null;
        } catch (Exception e) {
            return null;
        }
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

    /** Same alphanumeric shape as KidsMinistryController#generateSecurityCode. */
    private static String generateCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random rnd = new Random();
        return String.valueOf(chars.charAt(rnd.nextInt(chars.length())))
                + "-" + String.format("%04d", rnd.nextInt(10000));
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
