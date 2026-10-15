package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MembershipFamily;
import com.churchgeniuspro.hibernate.MembershipFamilyMember;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.MembershipFamilyMemberRepository;
import com.churchgeniuspro.repository.MembershipFamilyRepository;
import com.churchgeniuspro.util.FamilyRoles;
import com.churchgeniuspro.util.PhotoImages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Membership Requests — the workflow behind the admin page (V15).
 *
 * <ul>
 *   <li>{@link #approve}: status only. The request is accepted; nothing in
 *       {@code family}/{@code family_member} changes; staff update the record by hand.</li>
 *   <li>{@link #apply} ("Approve and Update"): applies the submitted changes to the
 *       family record <b>atomically</b> — one transaction; any failure rolls everything
 *       back and the request keeps its previous status. Stamps
 *       {@code family.membership_renewed_at} and sets the request to UPDATED.</li>
 *   <li>{@link #preview}: what {@code apply} would change, computed with the very same
 *       matching and field rules, so the review screen is exact.</li>
 *   <li>{@link #setStatus} / {@link #delete}: audited transitions and a tenant-scoped
 *       soft delete that never touches the family.</li>
 * </ul>
 *
 * Matching an existing household: by first + last name first; a role fallback only for
 * the unique roles (Head, Spouse); never Child/Adult by role alone (that overwrote the
 * wrong child). Blank submitted values never overwrite existing data; an existing
 * member's member type, alert, contribution and privacy settings are left alone.
 */
public class MembershipRequestService {

    private static final Logger log = LoggerFactory.getLogger(MembershipRequestService.class);

    public static final String ERR_NOT_FOUND = "Membership request not found.";
    public static final String ERR_FAMILY_MISSING = "Existing family not found.";
    public static final String ERR_CROSS_TENANT = "This request refers to a family that does not belong to your church.";

    /** A business-rule refusal (4xx); anything else is a failure that rolls back (5xx). */
    public static class RefusedException extends RuntimeException {
        public final int status;
        public RefusedException(int status, String message) { super(message); this.status = status; }
    }

    // ── field table: one definition drives the diff AND the write ───────────────

    record FieldDef(String key, String label, Function<MembershipFamilyMember, Object> submitted,
                    Function<FamilyMember, Object> existing, BiConsumer<FamilyMember, Object> write) {}

    static final List<FieldDef> FIELDS = List.of(
        // Role: compared in canonical form, so a legacy "Head" vs submitted "Head of Household" is NOT a
        // change (historical roles are never rewritten); a real change (Adult → Spouse) is written canonically.
        new FieldDef("role", "Role", m -> FamilyRoles.canonical(m.getRole()), f -> FamilyRoles.canonical(f.getRole()), (f, v) -> f.setRole((String) v)),
        new FieldDef("firstName", "First name", MembershipFamilyMember::getFirstName, FamilyMember::getFirstName, (f, v) -> f.setFirstName((String) v)),
        new FieldDef("middleName", "Middle name", MembershipFamilyMember::getMiddleName, FamilyMember::getMiddleName, (f, v) -> f.setMiddleName((String) v)),
        new FieldDef("lastName", "Last name", MembershipFamilyMember::getLastName, FamilyMember::getLastName, (f, v) -> f.setLastName((String) v)),
        new FieldDef("otherName", "Nickname", MembershipFamilyMember::getOtherName, FamilyMember::getOtherName, (f, v) -> f.setOtherName((String) v)),
        new FieldDef("gender", "Gender", MembershipFamilyMember::getGender, FamilyMember::getGender, (f, v) -> f.setGender((String) v)),
        new FieldDef("phone", "Phone", MembershipFamilyMember::getPhone, FamilyMember::getPhone, (f, v) -> f.setPhone((String) v)),
        new FieldDef("email", "Email", MembershipFamilyMember::getEmail, FamilyMember::getEmail, (f, v) -> f.setEmail((String) v)),
        new FieldDef("birthdayMonth", "Birthday month", MembershipFamilyMember::getBirthdayMonth, FamilyMember::getBirthdayMonth, (f, v) -> f.setBirthdayMonth((Integer) v)),
        new FieldDef("birthdayDay", "Birthday day", MembershipFamilyMember::getBirthdayDay, FamilyMember::getBirthdayDay, (f, v) -> f.setBirthdayDay((Integer) v)),
        new FieldDef("birthdayYear", "Birthday year", MembershipFamilyMember::getBirthdayYear, FamilyMember::getBirthdayYear, (f, v) -> f.setBirthdayYear((Integer) v)),
        new FieldDef("anniversaryMonth", "Anniversary month", MembershipFamilyMember::getAnniversaryMonth, FamilyMember::getAnniversaryMonth, (f, v) -> f.setAnniversaryMonth((Integer) v)),
        new FieldDef("anniversaryDay", "Anniversary day", MembershipFamilyMember::getAnniversaryDay, FamilyMember::getAnniversaryDay, (f, v) -> f.setAnniversaryDay((Integer) v)),
        new FieldDef("anniversaryYear", "Anniversary year", MembershipFamilyMember::getAnniversaryYear, FamilyMember::getAnniversaryYear, (f, v) -> f.setAnniversaryYear((Integer) v)),
        new FieldDef("address1", "Address line 1", MembershipFamilyMember::getAddress1, FamilyMember::getAddress1, (f, v) -> f.setAddress1((String) v)),
        new FieldDef("address2", "Address line 2", MembershipFamilyMember::getAddress2, FamilyMember::getAddress2, (f, v) -> f.setAddress2((String) v)),
        new FieldDef("city", "City", MembershipFamilyMember::getCity, FamilyMember::getCity, (f, v) -> f.setCity((String) v)),
        new FieldDef("state", "State", MembershipFamilyMember::getState, FamilyMember::getState, (f, v) -> f.setState((String) v)),
        new FieldDef("country", "Country", MembershipFamilyMember::getCountry, FamilyMember::getCountry, (f, v) -> f.setCountry((String) v)),
        new FieldDef("pinCode", "ZIP / PIN", MembershipFamilyMember::getPinCode, FamilyMember::getPinCode, (f, v) -> f.setPinCode((String) v)),
        new FieldDef("comments", "Comments", MembershipFamilyMember::getComments, FamilyMember::getComments, (f, v) -> f.setComments((String) v))
    );

    private final MembershipFamilyRepository mfRepo;
    private final MembershipFamilyMemberRepository mfmRepo;
    private final FamilyRepository familyRepo;
    private final FamilyMemberRepository familyMemberRepo;
    private final LoginRepository loginRepo;
    private final TransactionTemplate tx;   // null in unit tests → runs inline

    public MembershipRequestService(MembershipFamilyRepository mfRepo, MembershipFamilyMemberRepository mfmRepo,
                                    FamilyRepository familyRepo, FamilyMemberRepository familyMemberRepo,
                                    LoginRepository loginRepo, PlatformTransactionManager txm) {
        this.mfRepo = mfRepo; this.mfmRepo = mfmRepo; this.familyRepo = familyRepo;
        this.familyMemberRepo = familyMemberRepo; this.loginRepo = loginRepo;
        this.tx = txm != null ? new TransactionTemplate(txm) : null;
    }

    // ── matching (shared by preview and apply) ──────────────────────────────────

    /** How a submitted member maps onto the existing household. */
    public record Match(MembershipFamilyMember submitted, FamilyMember existing, String how) {}   // how: NAME / ROLE / NEW

    static boolean namesMatch(String a, String b) {
        return a != null && b != null && !a.isBlank() && !b.isBlank() && a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * Name first (first + last, case-insensitive). Role fallback only for the unique
     * roles — Head or Spouse — and only when exactly one unmatched existing member has
     * that role. Children and adults are never matched by role alone.
     */
    public static List<Match> match(List<MembershipFamilyMember> submitted, List<FamilyMember> existing) {
        Set<Integer> taken = new HashSet<>();
        List<Match> out = new ArrayList<>();
        for (MembershipFamilyMember s : submitted) {
            FamilyMember hit = existing.stream()
                    .filter(e -> !taken.contains(e.getId()))
                    .filter(e -> namesMatch(e.getFirstName(), s.getFirstName()) && namesMatch(e.getLastName(), s.getLastName()))
                    .findFirst().orElse(null);
            String how = hit != null ? "NAME" : null;
            if (hit == null) {
                String role = FamilyRoles.canonical(s.getRole());
                if (FamilyRoles.HEAD.equals(role) || FamilyRoles.SPOUSE.equals(role)) {
                    List<FamilyMember> sameRole = existing.stream()
                            .filter(e -> !taken.contains(e.getId()))
                            .filter(e -> role.equals(FamilyRoles.canonical(e.getRole())))
                            .toList();
                    if (sameRole.size() == 1) { hit = sameRole.get(0); how = "ROLE"; }
                }
            }
            if (hit != null) taken.add(hit.getId()); else how = "NEW";
            out.add(new Match(s, hit, how));
        }
        return out;
    }

    static boolean blank(Object v) { return v == null || (v instanceof String s && s.isBlank()); }

    /** The field changes {@code apply} would make for one matched member (none for a NEW member). */
    public static List<Map<String, Object>> changes(Match m) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (m.existing() == null) return out;
        for (FieldDef f : FIELDS) {
            Object nv = f.submitted().apply(m.submitted());
            if (blank(nv)) continue;                      // blank never overwrites
            Object ov = f.existing().apply(m.existing());
            if (nv instanceof String ns && ov instanceof String os ? ns.trim().equals(os.trim()) : Objects.equals(nv, ov)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("field", f.key()); c.put("label", f.label()); c.put("oldValue", ov); c.put("newValue", nv);
            out.add(c);
        }
        // The head's address IS the family address, so the flag only means something for the others.
        if (!FamilyRoles.isHead(m.submitted().getRole()) && m.submitted().isSameAsFamilyAddress() != m.existing().isSameAsFamilyAddress()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("field", "sameAsFamilyAddress"); c.put("label", "Same as family address");
            c.put("oldValue", m.existing().isSameAsFamilyAddress()); c.put("newValue", m.submitted().isSameAsFamilyAddress());
            out.add(c);
        }
        return out;
    }

    /**
     * A photo change is reported only when the submission carries a usable image (one the
     * server can decode and a browser can show) that differs from the member's current photo.
     * No photo, a blank, an unchanged photo, or an unsupported format (HEIC from an iPhone,
     * WebP) is not a change — and is never written by {@link #apply}.
     */
    public static boolean photoChanged(Match m) {
        String np = m.submitted().getPhotoData();
        if (!PhotoImages.isUsable(np)) return false;
        if (m.existing() == null) return true;
        String op = m.existing().getPhotoData();
        return op == null || op.isBlank() || !op.equals(np);
    }

    // ── preview ─────────────────────────────────────────────────────────────────

    /**
     * For an existing-family request: each submitted member with its match, the field
     * changes, and whether the photo changes. Read-only. {@code hasChanges} is false
     * for a renewal that resubmits the record as it stands.
     */
    public Map<String, Object> preview(MembershipFamily mf, List<MembershipFamilyMember> submitted, String tenant) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (mf.getExistingFamilyId() == null) { out.put("isExisting", false); out.put("hasChanges", true); return out; }
        Family fam = familyRepo.findById(mf.getExistingFamilyId()).orElse(null);
        if (fam == null || fam.getAppClientId() == null || !fam.getAppClientId().equals(tenant)) {
            out.put("isExisting", true); out.put("familyMissing", true); out.put("hasChanges", false); return out;
        }
        List<FamilyMember> existing = familyMemberRepo.findActiveMembersByFamilyId(fam.getId());
        List<Map<String, Object>> members = new ArrayList<>();
        boolean any = false;
        for (Match m : match(submitted, existing)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestMemberId", m.submitted().getId());
            row.put("name", (nz(m.submitted().getFirstName()) + " " + nz(m.submitted().getLastName())).trim());
            row.put("matchType", m.how());
            row.put("existingMemberId", m.existing() != null ? m.existing().getId() : null);
            row.put("existingName", m.existing() != null ? (nz(m.existing().getFirstName()) + " " + nz(m.existing().getLastName())).trim() : null);
            List<Map<String, Object>> ch = changes(m);
            boolean photo = photoChanged(m);
            row.put("changes", ch);
            row.put("photoChanged", photo);
            row.put("existingPhotoData", photo && m.existing() != null ? m.existing().getPhotoData() : null);
            any |= m.existing() == null || !ch.isEmpty() || photo;
            members.add(row);
        }
        out.put("isExisting", true);
        out.put("familyId", fam.getId());
        out.put("members", members);
        out.put("hasChanges", any);
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ── status-only approve / transitions / delete ──────────────────────────────

    public MembershipFamily approve(Integer id, String tenant, String actor) {
        return setStatus(id, tenant, actor, MembershipFamily.STATUS_APPROVED);
    }

    /** Allowed moves — recorded only; moving back never un-applies data (the page says so). */
    static boolean allowed(String from, String to) {
        if (from == null) from = MembershipFamily.STATUS_PENDING_REVIEW;
        return switch (to) {
            case MembershipFamily.STATUS_PENDING_REVIEW -> !from.equals(MembershipFamily.STATUS_PENDING_REVIEW);
            case MembershipFamily.STATUS_APPROVED -> !from.equals(MembershipFamily.STATUS_APPROVED);
            case MembershipFamily.STATUS_UPDATED -> false;   // only apply() may set UPDATED
            default -> false;
        };
    }

    public MembershipFamily setStatus(Integer id, String tenant, String actor, String to) {
        if (to == null || !(to.equals(MembershipFamily.STATUS_PENDING_REVIEW) || to.equals(MembershipFamily.STATUS_APPROVED) || to.equals(MembershipFamily.STATUS_UPDATED)))
            throw new RefusedException(400, "Unknown status.");
        MembershipFamily mf = mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, tenant).orElseThrow(() -> new RefusedException(404, ERR_NOT_FOUND));
        if (!allowed(mf.getStatus(), to))
            throw new RefusedException(409, "Cannot change status from " + label(mf.getStatus()) + " to " + label(to) + ".");
        mf.setStatus(to);
        mf.setStatusChangedAt(new Date());
        mf.setStatusChangedBy(actor);
        return mfRepo.save(mf);
    }

    public MembershipFamily delete(Integer id, String tenant, String actor) {
        MembershipFamily mf = mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, tenant).orElseThrow(() -> new RefusedException(404, ERR_NOT_FOUND));
        mf.setDeleteFlag(true);
        mf.setDeletedAt(new Date());
        mf.setDeletedBy(actor);
        return mfRepo.save(mf);   // family / family_member untouched by design
    }

    public static String label(String status) {
        if (status == null) return "Pending Review";
        return switch (status) {
            case MembershipFamily.STATUS_APPROVED -> "Approved";
            case MembershipFamily.STATUS_UPDATED -> "Updated";
            default -> "Pending Review";
        };
    }

    // ── apply ("Approve and Update") ────────────────────────────────────────────

    public record Applied(Integer familyId, boolean isExisting, int created, int updated, boolean hadChanges) {}

    /**
     * Applies the request atomically. Throws {@link RefusedException} for business
     * refusals (nothing written); any other exception rolls the transaction back and
     * propagates — the request keeps its previous status either way.
     */
    public Applied apply(Integer id, String tenant, String actor) {
        if (tx == null) return doApply(id, tenant, actor);
        return tx.execute(status -> doApply(id, tenant, actor));
    }

    private Applied doApply(Integer id, String tenant, String actor) {
        MembershipFamily mf = mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, tenant).orElseThrow(() -> new RefusedException(404, ERR_NOT_FOUND));
        if (mf.isUpdated()) throw new RefusedException(409, "This request has already been applied.");
        List<MembershipFamilyMember> submitted = mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(id);
        Date now = new Date();
        Family family;
        int created = 0, updated = 0;
        boolean hadChanges;
        boolean isExisting = mf.getExistingFamilyId() != null;

        if (isExisting) {
            family = familyRepo.findById(mf.getExistingFamilyId()).orElseThrow(() -> new RefusedException(400, ERR_FAMILY_MISSING));
            if (mf.getAppClientId() == null || family.getAppClientId() == null || !mf.getAppClientId().equals(family.getAppClientId())) {
                log.warn("Membership apply: refusing cross-tenant merge of request {} (request client {}, family {} client {})",
                        id, mf.getAppClientId(), family.getId(), family.getAppClientId());
                throw new RefusedException(400, ERR_CROSS_TENANT);
            }
            List<FamilyMember> existing = familyMemberRepo.findActiveMembersByFamilyId(family.getId());
            List<Match> matches = match(submitted, existing);
            hadChanges = false;
            for (Match m : matches) {
                if (m.existing() == null) {
                    FamilyMember fm = newMember(family, m.submitted());
                    familyMemberRepo.save(fm);
                    created++; hadChanges = true;
                } else {
                    List<Map<String, Object>> ch = changes(m);
                    boolean photo = photoChanged(m);
                    if (ch.isEmpty() && !photo) continue;
                    FamilyMember fm = m.existing();
                    for (Map<String, Object> c : ch) {
                        String key = (String) c.get("field");
                        if ("sameAsFamilyAddress".equals(key)) { fm.setSameAsFamilyAddress(m.submitted().isSameAsFamilyAddress()); continue; }
                        FIELDS.stream().filter(f -> f.key().equals(key)).findFirst().ifPresent(f -> f.write().accept(fm, c.get("newValue")));
                    }
                    if (photo) setPhoto(fm, m.submitted().getPhotoData());
                    raisePrivacy(fm, m.submitted());
                    familyMemberRepo.save(fm);
                    updated++; hadChanges = true;
                }
            }
        } else {
            family = new Family();
            family.setInactive(false);
            family.setAppClientId(mf.getAppClientId());
            family = familyRepo.save(family);
            for (MembershipFamilyMember s : submitted) {
                FamilyMember fm = familyMemberRepo.save(newMember(family, s));
                created++;
                if (mf.getSignupId() != null && FamilyRoles.isHead(s.getRole()) && loginRepo != null) {
                    SignUp su = loginRepo.findById(mf.getSignupId()).orElse(null);
                    if (su != null) {
                        su.setActive(true);
                        loginRepo.save(su);
                        if (su.getClientId() != null && su.getClientId().startsWith("MBR")) { fm.setMemberRef(su.getClientId()); familyMemberRepo.save(fm); }
                    }
                }
            }
            hadChanges = true;
        }

        family.setMembershipRenewedAt(now);          // the one place this is ever set
        familyRepo.save(family);

        mf.setStatus(MembershipFamily.STATUS_UPDATED);
        mf.setStatusChangedAt(now);
        mf.setStatusChangedBy(actor);
        mf.setAppliedAt(now);
        mf.setAppliedBy(actor);
        mf.setAppliedFamilyId(family.getId());
        mfRepo.save(mf);
        return new Applied(family.getId(), isExisting, created, updated, hadChanges);
    }

    /** A brand-new member: every submitted field, defaults for the settings the form does not collect. */
    static FamilyMember newMember(Family family, MembershipFamilyMember s) {
        FamilyMember fm = new FamilyMember();
        fm.setFamily(family);
        fm.setAppClientId(s.getAppClientId() != null ? s.getAppClientId() : family.getAppClientId());
        for (FieldDef f : FIELDS) { Object v = f.submitted().apply(s); if (!blank(v)) f.write().accept(fm, v); }
        if (fm.getRole() == null) fm.setRole(FamilyRoles.canonical(s.getRole()));
        fm.setMemberType("Member");
        fm.setSameAsFamilyAddress(s.isSameAsFamilyAddress());
        fm.setPhonePrivate(Boolean.TRUE.equals(s.getPhonePrivate()));
        fm.setEmailPrivate(Boolean.TRUE.equals(s.getEmailPrivate()));
        fm.setAddressPrivate(Boolean.TRUE.equals(s.getAddressPrivate()));
        fm.setInactive(false);
        fm.setDisableAlerts(false);
        fm.setIncludeContributions(FamilyRoles.isHead(s.getRole()));
        setPhoto(fm, s.getPhotoData());
        return fm;
    }

    /** The public form can raise a privacy flag on an existing member, never lower one. */
    static void raisePrivacy(FamilyMember fm, MembershipFamilyMember s) {
        if (Boolean.TRUE.equals(s.getPhonePrivate())) fm.setPhonePrivate(true);
        if (Boolean.TRUE.equals(s.getEmailPrivate())) fm.setEmailPrivate(true);
        if (Boolean.TRUE.equals(s.getAddressPrivate())) fm.setAddressPrivate(true);
    }

    /** Photo and thumbnail always move together (View Family / avatar read the thumbnail first). */
    static void setPhoto(FamilyMember fm, String dataUrl) {
        if (!PhotoImages.isUsable(dataUrl)) return;   // same rule as photoChanged: unsupported formats are never stored
        String photo = PhotoImages.normalise(dataUrl);
        fm.setPhotoData(photo);
        fm.setPhotoThumbnail(PhotoImages.thumbnail(photo));
    }
}
