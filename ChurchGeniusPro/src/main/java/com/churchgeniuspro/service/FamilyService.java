package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.model.FamilyBO;
import com.churchgeniuspro.model.FamilyMemberBO;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

/**
 * Service layer for family persistence and retrieval.
 *
 * <h3>Design after refactor</h3>
 * <ul>
 *   <li>The {@code family_name} column has been removed from the {@code family}
 *       table.  The display name is now derived at runtime from the primary
 *       member's last name (e.g. "Mathew Family").</li>
 *   <li>Address fields ({@code family_address1 … family_pin_code}) have been
 *       removed from the {@code family} table.  The primary member's own address
 *       columns in {@code family_member} are the single source of truth.</li>
 *   <li>All API responses that previously returned {@code familyName} still
 *       return a {@code familyName} key — its value is simply computed rather
 *       than fetched from a stored column.</li>
 * </ul>
 *
 * <h3>Filter semantics for {@link #getFamilies}</h3>
 * <ul>
 *   <li>Neither flag set → active families only (deleteFlag=false, inactive=false)</li>
 *   <li>showDeleted only → ONLY soft-deleted families (deleteFlag=true)</li>
 *   <li>showInactive only → ONLY inactive non-deleted families</li>
 *   <li>Both set → families that are deleted OR inactive</li>
 * </ul>
 */
@Service
public class FamilyService {

    private final FamilyRepository       familyRepository;
    private final FamilyMemberRepository familyMemberRepository;

    public FamilyService(FamilyRepository familyRepository,
                         FamilyMemberRepository familyMemberRepository) {
        this.familyRepository       = familyRepository;
        this.familyMemberRepository = familyMemberRepository;
    }

    // ── Save (create) ─────────────────────────────────────────────────────

    @Transactional
    public Family save(FamilyBO bo, String appClientId) {
        Family family = new Family();
        family.setInactive(bo.isInactive());
        family.setAppClientId(appClientId);

        List<FamilyMember> members = new ArrayList<>();
        if (bo.getMembers() != null) {
            bo.getMembers().forEach(mbo -> members.add(buildMember(mbo, family, appClientId)));
        }
        family.setMembers(members);
        return familyRepository.save(family);
    }

    // ── Update ────────────────────────────────────────────────────────────

    /**
     * Updates an existing family's details and merges its member list:
     * <ul>
     *   <li>Members present in the BO with a known {@code id} → fields updated in place.</li>
     *   <li>Members present in the BO without an {@code id} AND unmatched existing active
     *       members are available → updated in place (role-match preferred, then first-in-order).
     *       This prevents phantom duplicate rows when the client omits member ids, e.g. due to a
     *       page-load race condition where the form is saved before the family data finishes
     *       loading from the server.</li>
     *   <li>Members present in the BO without an {@code id} AND no unmatched existing member
     *       → a new row is inserted.</li>
     *   <li>Existing active members whose {@code id} is absent from the BO (only evaluated when
     *       at least one incoming member carries a DB id) → soft-deleted rather than physically
     *       removed.</li>
     * </ul>
     */
    @Transactional
    public Family update(Integer id, FamilyBO bo, String appClientId) {
        Family family = findOrThrow(id, appClientId);
        family.setInactive(bo.isInactive());

        if (bo.getMembers() != null) {
            // Build a lookup of existing members by DB id
            Map<Integer, FamilyMember> existingById = family.getMembers().stream()
                    .filter(m -> m.getId() != null)
                    .collect(Collectors.toMap(FamilyMember::getId, m -> m));

            // IDs the caller wants to keep (non-null ids in the BO list)
            Set<Integer> incomingIds = bo.getMembers().stream()
                    .map(FamilyMemberBO::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // Soft-delete active members NOT present in the incoming list.
            // Guard: skip when ALL incoming ids are null — that signals a client-side
            // race condition (form saved before page data finished loading).  In that
            // case we fall through to the in-place update path below instead of
            // wiping the existing rows.
            if (!incomingIds.isEmpty()) {
                family.getMembers().forEach(m -> {
                    if (!m.isDeleteFlag() && m.getId() != null && !incomingIds.contains(m.getId())) {
                        m.setDeleteFlag(true);
                    }
                });
            }

            // Active members not already claimed by an incoming id.
            // Available as fallback targets for incoming entries that carry no id.
            List<FamilyMember> unmatchedActive = family.getMembers().stream()
                    .filter(m -> !m.isDeleteFlag()
                              && m.getId() != null
                              && !incomingIds.contains(m.getId()))
                    .collect(Collectors.toList());

            // Update existing members or add new ones
            for (FamilyMemberBO mbo : bo.getMembers()) {
                if (mbo.getId() != null && existingById.containsKey(mbo.getId())) {
                    // Known id → update in place
                    FamilyMember existing = existingById.get(mbo.getId());
                    if (!existing.isDeleteFlag()) {
                        updateMemberFields(mbo, existing);
                    }
                } else if (mbo.getId() == null && !unmatchedActive.isEmpty()) {
                    // No id provided but an unmatched existing active member is available.
                    // Prefer a role match; fall back to the first available member.
                    String incomingRole = mbo.getRole();
                    FamilyMember toUpdate = (incomingRole != null)
                            ? unmatchedActive.stream()
                                    .filter(m -> incomingRole.equals(m.getRole()))
                                    .findFirst()
                                    .orElse(unmatchedActive.get(0))
                            : unmatchedActive.get(0);
                    unmatchedActive.remove(toUpdate);
                    updateMemberFields(mbo, toUpdate);
                } else {
                    family.getMembers().add(buildMember(mbo, family, family.getAppClientId()));
                }
            }
        }

        return familyRepository.save(family);
    }

    // ── List ──────────────────────────────────────────────────────────────

    /**
     * Returns a paginated, lightweight summary list for the view-families table.
     *
     * <p>Uses two narrow SQL projections instead of loading full Family entities:
     * <ol>
     *   <li>A scalar projection with all family/member columns <em>except</em>
     *       {@code photo_data}, to avoid pulling megabytes of image blobs.</li>
     *   <li>A separate query fetching only {@code (family_id, photo_thumbnail)}
     *       (falling back to {@code photo_data} for old rows) for the primary
     *       member of each family.</li>
     * </ol>
     * Status and name filtering are applied in Java; pagination is applied after
     * filtering so the caller always gets accurate {@code total} counts.
     *
     * @param page      0-based page number (pass -1 to return all results)
     * @param pageSize  number of items per page (ignored when page == -1)
     * @return map with keys {@code items} (the page slice) and {@code total}
     *         (total matching families after all filters)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getFamilies(String search,
                                           boolean showDeleted,
                                           boolean showInactive,
                                           String appClientId,
                                           int page,
                                           int pageSize) {
        final String s = (search == null || search.isBlank()) ? "" : search.trim().toLowerCase();

        // ── 1. Load scalar projection (no photo_data) ─────────────────────────
        List<Map<String, Object>> rows = familyRepository.findFamilyListProjection(appClientId);

        // Group member rows by familyId
        // Each map key: familyId → list of member projection rows
        Map<Integer, List<Map<String, Object>>> membersByFamily = new LinkedHashMap<>();
        Map<Integer, Boolean> familyInactive  = new HashMap<>();
        Map<Integer, Boolean> familyDeleted   = new HashMap<>();
        Map<Integer, String>  familyClientId  = new HashMap<>();

        for (Map<String, Object> row : rows) {
            Integer fid = toInt(row.get("familyId"));
            if (fid == null) continue;
            familyInactive.putIfAbsent(fid,  toBool(row.get("inactive")));
            familyDeleted.putIfAbsent(fid,   toBool(row.get("deleteFlag")));
            familyClientId.putIfAbsent(fid,  (String) row.get("appClientId"));
            membersByFamily.computeIfAbsent(fid, k -> new ArrayList<>()).add(row);
        }

        // ── 2. Load primary-member photos (one per family) ────────────────────
        List<Map<String, Object>> photoRows = familyRepository.findPrimaryMemberPhotosByAppUser(appClientId);
        Map<Integer, String> photoByFamily = new HashMap<>();
        for (Map<String, Object> pr : photoRows) {
            Integer fid = toInt(pr.get("familyId"));
            if (fid != null) photoByFamily.put(fid, (String) pr.get("photoData"));
        }

        // ── 3. Build response list ─────────────────────────────────────────────
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map.Entry<Integer, List<Map<String, Object>>> entry : membersByFamily.entrySet()) {
            Integer fid      = entry.getKey();
            boolean deleted  = Boolean.TRUE.equals(familyDeleted.get(fid));
            boolean inactive = Boolean.TRUE.equals(familyInactive.get(fid));

            // Status filter
            if (showDeleted && showInactive) { if (!deleted && !inactive) continue; }
            else if (showDeleted)            { if (!deleted)              continue; }
            else if (showInactive)           { if (deleted || !inactive)  continue; }
            else                             { if (deleted || inactive)   continue; }

            List<Map<String, Object>> members = entry.getValue();

            // Find primary member (Head first, then any non-deleted)
            Map<String, Object> primary = members.stream()
                    .filter(m -> !toBool(m.get("memberDeleteFlag")) && !toBool(m.get("memberInactive")))
                    .filter(m -> isHead(str(m.get("role"))))
                    .findFirst()
                    .orElseGet(() -> members.stream()
                            .filter(m -> !toBool(m.get("memberDeleteFlag")))
                            .findFirst().orElse(null));

            String firstName   = primary != null ? str(primary.get("firstName"))  : "";
            String lastName    = primary != null ? str(primary.get("lastName"))    : "";
            String derivedName = (lastName == null || lastName.isBlank()) ? firstName : lastName + " Family";

            // Name-based search filter
            if (!s.isEmpty()) {
                boolean match = (firstName != null && firstName.toLowerCase().contains(s))
                             || (lastName  != null && lastName.toLowerCase().contains(s));
                if (!match) continue;
            }

            long memberCount = members.stream()
                    .filter(m -> !toBool(m.get("memberDeleteFlag")))
                    .count();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id",               fid);
            out.put("familyName",       derivedName);
            out.put("inactive",         inactive);
            out.put("deleted",          deleted);
            out.put("memberCount",      memberCount);
            out.put("primaryFirstName", firstName);
            out.put("primaryLastName",  lastName);
            // Thumbnail pre-computed at save time (photo_thumbnail column).
            // Falls back to full photo_data via COALESCE in the SQL query for old rows.
            out.put("primaryPhotoData", photoByFamily.get(fid));
            result.add(out);
        }

        // Sort by first name (primary), then last name (secondary)
        result.sort((a, b) -> {
            String fa = (String) a.get("primaryFirstName");
            String fb = (String) b.get("primaryFirstName");
            int cmp = (fa == null ? "" : fa).compareToIgnoreCase(fb == null ? "" : fb);
            if (cmp != 0) return cmp;
            String la = (String) a.get("primaryLastName");
            String lb = (String) b.get("primaryLastName");
            return (la == null ? "" : la).compareToIgnoreCase(lb == null ? "" : lb);
        });

        int total = result.size();

        // Pagination slice (page == -1 means return all)
        List<Map<String, Object>> pageItems;
        if (page < 0 || pageSize <= 0) {
            pageItems = result;
        } else {
            int fromIndex = page * pageSize;
            int toIndex   = Math.min(fromIndex + pageSize, total);
            pageItems = (fromIndex >= total) ? List.of() : result.subList(fromIndex, toIndex);
        }

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("items", pageItems);
        wrapper.put("total", total);
        return wrapper;
    }

    // ── Private scalar helpers ────────────────────────────────────────────────

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(v.toString());
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static boolean isHead(String role) {
        return "Head".equalsIgnoreCase(role) || "Head of Household".equalsIgnoreCase(role);
    }

    // ── Single-family detail ──────────────────────────────────────────────

    /**
     * Returns the full details of one family including all its members.
     * Used by the view-mode and edit-mode pages.
     * The {@code familyName} key in the result is derived from the primary member.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getById(Integer id, String appClientId) {
        Family f = familyRepository.findByIdAndAppClientIdWithMembers(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Family not found: " + id));

        // Derive display name from primary member
        FamilyMember primary = f.getMembers().stream()
                .filter(m -> !m.isDeleteFlag())
                .filter(m -> "Head".equalsIgnoreCase(m.getRole())
                          || "Head of Household".equalsIgnoreCase(m.getRole()))
                .findFirst()
                .orElse(f.getMembers().stream().filter(m -> !m.isDeleteFlag()).findFirst().orElse(null));

        String lastName  = primary != null && primary.getLastName()  != null ? primary.getLastName()  : "";
        String firstName = primary != null && primary.getFirstName() != null ? primary.getFirstName() : "";
        String derivedName = lastName.isBlank() ? firstName : lastName + " Family";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id",         f.getId());
        result.put("familyName", derivedName);
        result.put("inactive",   f.isInactive());
        result.put("deleted",    f.isDeleteFlag());
        result.put("members", f.getMembers().stream()
                // Return all members (active, inactive, deleted) so the frontend
                // can filter/display with its own checkboxes.
                .sorted((a, b) -> {
                    boolean aHead = "Head".equalsIgnoreCase(a.getRole())
                                 || "Head of Household".equalsIgnoreCase(a.getRole());
                    boolean bHead = "Head".equalsIgnoreCase(b.getRole())
                                 || "Head of Household".equalsIgnoreCase(b.getRole());
                    if (aHead != bHead) return aHead ? -1 : 1;
                    // Within the same tier, keep the lowest id first (oldest row first)
                    Integer aId = a.getId() != null ? a.getId() : Integer.MAX_VALUE;
                    Integer bId = b.getId() != null ? b.getId() : Integer.MAX_VALUE;
                    return Integer.compare(aId, bId);
                })
                .map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",                  m.getId());
            row.put("role",                m.getRole());
            row.put("firstName",           m.getFirstName());
            row.put("middleName",          m.getMiddleName());
            row.put("lastName",            m.getLastName());
            row.put("phone",               m.getPhone());
            row.put("email",               m.getEmail());
            row.put("memberType",          m.getMemberType());
            row.put("gender",              m.getGender());
            row.put("birthdayMonth",       m.getBirthdayMonth());
            row.put("birthdayDay",         m.getBirthdayDay());
            row.put("birthdayYear",        m.getBirthdayYear());
            row.put("anniversaryMonth",    m.getAnniversaryMonth());
            row.put("anniversaryDay",      m.getAnniversaryDay());
            row.put("anniversaryYear",     m.getAnniversaryYear());
            row.put("address1",            m.getAddress1());
            row.put("address2",            m.getAddress2());
            row.put("city",                m.getCity());
            row.put("state",               m.getState());
            row.put("country",             m.getCountry());
            row.put("pinCode",             m.getPinCode());
            row.put("sameAsFamilyAddress", m.isSameAsFamilyAddress());
            row.put("phonePrivate",        Boolean.TRUE.equals(m.getPhonePrivate()));
            row.put("emailPrivate",        Boolean.TRUE.equals(m.getEmailPrivate()));
            row.put("addressPrivate",      Boolean.TRUE.equals(m.getAddressPrivate()));
            row.put("otherName",           m.getOtherName());
            row.put("nickname",            m.getOtherName());
            row.put("displayName",         com.churchgeniuspro.util.MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName()));
            row.put("comments",            m.getComments());
            row.put("inactive",            m.isInactive());
            row.put("deleted",             m.isDeleteFlag());
            row.put("disableAlerts",       m.isDisableAlerts());
            row.put("includeContributions",m.isIncludeContributions());
            row.put("photoData",           m.getPhotoData());
            return row;
        }).collect(Collectors.toList()));

        return result;
    }

    // ── All Members (flat list) ───────────────────────────────────────────

    /**
     * Returns a flat list of {@link FamilyMember} records including the parent
     * family id and a derived family display name.  Used by the Members list page,
     * the family-form member-search autocomplete, and the View Family member-search panel.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllMembers(String search, String role,
                                                   String memberType, String phone,
                                                   String email,
                                                   boolean showDeleted,
                                                   boolean showInactive,
                                                   boolean includeInContributions,
                                                   String appClientId) {
        String q  = (search     == null) ? "" : search.trim();
        String r  = (role       == null) ? "" : role.trim();
        String mt = (memberType == null) ? "" : memberType.trim();
        String ph = (phone      == null) ? "" : phone.trim();
        String em = (email      == null) ? "" : email.trim();

        boolean anyFilter    = !q.isEmpty() || !r.isEmpty() || !mt.isEmpty() || !ph.isEmpty() || !em.isEmpty();
        boolean useAllVariant = showDeleted || showInactive;

        List<FamilyMember> rawList = anyFilter
                ? (useAllVariant
                        ? familyMemberRepository.searchByFiltersAll(q, r, mt, ph, em, appClientId)
                        : familyMemberRepository.searchByFilters(q, r, mt, ph, em, appClientId))
                : (useAllVariant
                        ? familyMemberRepository.findAllWithFamilyByAppUserAll(appClientId)
                        : familyMemberRepository.findAllWithFamilyByAppUser(appClientId));

        // Build a familyId → derived display name map from the loaded list.
        // Head-of-Household takes priority; fall back to the first member found.
        Map<Integer, String> familyDisplayName = new HashMap<>();
        rawList.forEach(m -> {
            if (m.getFamily() == null) return;
            Integer fid = m.getFamily().getId();
            String  ln  = m.getLastName()  != null ? m.getLastName()  : "";
            String  fn  = m.getFirstName() != null ? m.getFirstName() : "";
            String  name = ln.isBlank() ? fn : ln + " Family";
            boolean isHead = "Head".equalsIgnoreCase(m.getRole())
                          || "Head of Household".equalsIgnoreCase(m.getRole());
            if (isHead) {
                familyDisplayName.put(fid, name);
            } else {
                familyDisplayName.putIfAbsent(fid, name);
            }
        });

        return rawList.stream()
                .filter(m -> showDeleted || !m.isDeleteFlag())
                .filter(m -> showInactive || !m.isInactive())
                .filter(m -> !includeInContributions || m.isIncludeContributions())
                .map(m -> {
                    Integer fid = m.getFamily() != null ? m.getFamily().getId() : null;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",                  m.getId());
                    row.put("firstName",           m.getFirstName());
                    row.put("middleName",          m.getMiddleName());
                    row.put("lastName",            m.getLastName());
                    row.put("nickname",            m.getOtherName());
                    row.put("displayName",         com.churchgeniuspro.util.MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName()));
                    row.put("role",                m.getRole());
                    row.put("email",               m.getEmail());
                    row.put("phone",               m.getPhone());
                    row.put("memberType",          m.getMemberType());
                    row.put("gender",              m.getGender());
                    // Expose all three birthday components so callers can
                    // compute a full DOB (kidsMinistry's Register Child flow
                    // uses this to auto-fill DOB and derive Grade from age).
                    row.put("birthdayMonth",       m.getBirthdayMonth());
                    row.put("birthdayDay",         m.getBirthdayDay());
                    row.put("birthdayYear",        m.getBirthdayYear());
                    row.put("anniversaryMonth",    m.getAnniversaryMonth());
                    row.put("inactive",            m.isInactive());
                    row.put("disableAlerts",       m.isDisableAlerts());
                    row.put("includeContributions",m.isIncludeContributions());
                    row.put("deleted",             m.isDeleteFlag());
                    row.put("familyId",            fid);
                    row.put("familyName",          fid != null ? familyDisplayName.getOrDefault(fid, "") : null);
                    row.put("memberRef",           m.getMemberRef());
                    return row;
                })
                .collect(Collectors.toList());
    }

    // ── Soft-Delete / Restore ─────────────────────────────────────────────

    @Transactional
    public void softDelete(Integer id, String appClientId) {
        Family family = findOrThrow(id, appClientId);
        family.setDeleteFlag(true);
        familyRepository.save(family);
    }

    @Transactional
    public void restore(Integer id, String appClientId) {
        Family family = findOrThrow(id, appClientId);
        family.setDeleteFlag(false);
        familyRepository.save(family);
    }

    // ── Inactive ──────────────────────────────────────────────────────────

    @Transactional
    public void setInactive(Integer id, boolean inactive, String appClientId) {
        Family family = findOrThrow(id, appClientId);
        family.setInactive(inactive);
        familyRepository.save(family);
    }

    // ── Bulk Operations ───────────────────────────────────────────────────

    @Transactional
    public int bulkAction(List<Integer> ids, String action, String appClientId) {
        for (Integer id : ids) {
            if ("delete".equalsIgnoreCase(action))        softDelete(id, appClientId);
            else if ("inactive".equalsIgnoreCase(action)) setInactive(id, true, appClientId);
        }
        return ids.size();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /** Tenant-scoped; an id owned by another tenant reads as "not found" (no existence oracle). */
    private Family findOrThrow(Integer id, String appClientId) {
        if (appClientId == null) throw new IllegalArgumentException("Family not found: " + id);
        return familyRepository.findByIdAndAppClientId(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Family not found: " + id));
    }

    /**
     * Soft-deletes a single {@link FamilyMember} by setting its {@code deleteFlag}.
     * If the deleted member is the primary member (role = "Head" / "Head of Household"),
     * the entire family is also soft-deleted automatically.
     */
    @Transactional
    public void softDeleteMember(Integer familyId, Integer memberId, String appClientId) {
        Family family = findOrThrow(familyId, appClientId);
        family.getMembers().stream()
                .filter(m -> m.getId().equals(memberId))
                .findFirst()
                .ifPresent(m -> {
                    m.setDeleteFlag(true);
                    familyMemberRepository.save(m);

                    // If the deleted member is the primary, cascade to the family.
                    boolean isPrimary = "Head".equalsIgnoreCase(m.getRole())
                                    || "Head of Household".equalsIgnoreCase(m.getRole());
                    if (isPrimary) {
                        family.setDeleteFlag(true);
                        familyRepository.save(family);
                    }
                });
    }

    /** Maps a {@link FamilyMemberBO} to a new {@link FamilyMember} entity. */
    private FamilyMember buildMember(FamilyMemberBO mbo, Family family, String appClientId) {
        FamilyMember m = new FamilyMember();
        m.setFamily(family);
        m.setAppClientId(appClientId);
        m.setRole(mbo.getRole());
        m.setFirstName(mbo.getFirstName());
        m.setMiddleName(mbo.getMiddleName());
        m.setLastName(mbo.getLastName());
        m.setPhone(mbo.getPhone());
        m.setEmail(mbo.getEmail());
        m.setMemberType(mbo.getMemberType());
        m.setGender(mbo.getGender());
        m.setBirthdayMonth(parseIntOrNull(mbo.getBirthdayMonth()));
        m.setBirthdayDay(parseIntOrNull(mbo.getBirthdayDay()));
        m.setBirthdayYear(parseIntOrNull(mbo.getBirthdayYear()));
        m.setAnniversaryMonth(parseIntOrNull(mbo.getAnniversaryMonth()));
        m.setAnniversaryDay(parseIntOrNull(mbo.getAnniversaryDay()));
        m.setAnniversaryYear(parseIntOrNull(mbo.getAnniversaryYear()));
        m.setAddress1(mbo.getAddress1());
        m.setAddress2(mbo.getAddress2());
        m.setCity(mbo.getCity());
        m.setState(mbo.getState());
        m.setCountry(mbo.getCountry());
        m.setPinCode(mbo.getPinCode());
        m.setSameAsFamilyAddress(mbo.isSameAsFamilyAddress());
        m.setPhonePrivate(mbo.isPhonePrivate());
        m.setEmailPrivate(mbo.isEmailPrivate());
        m.setAddressPrivate(mbo.isAddressPrivate());
        m.setOtherName(mbo.getOtherName());
        m.setComments(mbo.getComments());
        m.setPhotoData(mbo.getPhotoData());
        m.setPhotoThumbnail(thumbnailDataUrl(mbo.getPhotoData(), 60));
        m.setInactive(mbo.isInactive());
        m.setDisableAlerts(mbo.isDisableAlerts());
        m.setIncludeContributions(mbo.isIncludeContributions());
        return m;
    }

    /** Updates the mutable fields of an existing {@link FamilyMember} from a BO. */
    private void updateMemberFields(FamilyMemberBO mbo, FamilyMember m) {
        m.setRole(mbo.getRole());
        m.setFirstName(mbo.getFirstName());
        m.setMiddleName(mbo.getMiddleName());
        m.setLastName(mbo.getLastName());
        m.setPhone(mbo.getPhone());
        m.setEmail(mbo.getEmail());
        m.setMemberType(mbo.getMemberType());
        m.setGender(mbo.getGender());
        m.setBirthdayMonth(parseIntOrNull(mbo.getBirthdayMonth()));
        m.setBirthdayDay(parseIntOrNull(mbo.getBirthdayDay()));
        m.setBirthdayYear(parseIntOrNull(mbo.getBirthdayYear()));
        m.setAnniversaryMonth(parseIntOrNull(mbo.getAnniversaryMonth()));
        m.setAnniversaryDay(parseIntOrNull(mbo.getAnniversaryDay()));
        m.setAnniversaryYear(parseIntOrNull(mbo.getAnniversaryYear()));
        m.setAddress1(mbo.getAddress1());
        m.setAddress2(mbo.getAddress2());
        m.setCity(mbo.getCity());
        m.setState(mbo.getState());
        m.setCountry(mbo.getCountry());
        m.setPinCode(mbo.getPinCode());
        m.setSameAsFamilyAddress(mbo.isSameAsFamilyAddress());
        m.setPhonePrivate(mbo.isPhonePrivate());
        m.setEmailPrivate(mbo.isEmailPrivate());
        m.setAddressPrivate(mbo.isAddressPrivate());
        m.setOtherName(mbo.getOtherName());
        m.setComments(mbo.getComments());
        // null = no change; "" (empty string sentinel) = user explicitly removed; data URL = new photo
        if (mbo.getPhotoData() != null) {
            String newPhoto = mbo.getPhotoData().isEmpty() ? null : mbo.getPhotoData();
            m.setPhotoData(newPhoto);
            m.setPhotoThumbnail(thumbnailDataUrl(newPhoto, 60));
        }
        m.setInactive(mbo.isInactive());
        m.setDisableAlerts(mbo.isDisableAlerts());
        m.setIncludeContributions(mbo.isIncludeContributions());
    }

    private Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * Scales a base64 data-URL photo down to a square thumbnail of {@code size} pixels.
     * Returns the original data-URL when already small enough, null when input is null/blank.
     *
     * <p>This keeps the list-view payload small: a 60px JPEG thumbnail is typically
     * 1–3 KB vs. 100–500 KB for a full-resolution member photo.
     */
    private String thumbnailDataUrl(String dataUrl, int size) {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        try {
            int commaIdx = dataUrl.indexOf(',');
            if (commaIdx < 0) return dataUrl;
            byte[] imageBytes = Base64.getDecoder().decode(dataUrl.substring(commaIdx + 1));
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (original == null) return dataUrl;
            int origW = original.getWidth();
            int origH = original.getHeight();
            if (origW <= size && origH <= size) return dataUrl;
            double scale = (double) size / Math.max(origW, origH);
            int tw = Math.max(1, (int) (origW * scale));
            int th = Math.max(1, (int) (origH * scale));
            BufferedImage thumb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = thumb.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(original, 0, 0, tw, th, null);
            g2.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumb, "jpeg", baos);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return dataUrl;
        }
    }
}
