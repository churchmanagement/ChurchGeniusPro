package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * REST API for the Kids Ministry module.
 *
 * All endpoints require Admin or SuperAdmin role.
 * All data is scoped to the logged-in user's appClientId.
 */
@RestController
@RequestMapping("/api/kids-ministry")
public class KidsMinistryController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KmChildRepository                  childRepo;
    private final KmClassroomRepository              classroomRepo;
    private final KmAuthorizedPickupRepository       pickupRepo;
    private final KmCheckinRepository                checkinRepo;
    private final KmVolunteerRepository              volunteerRepo;
    private final KmVolunteerRoleRepository          kmVolRoleRepo;
    private final KmVolunteerRoleAssignmentRepository kmVolRoleAssignRepo;
    private final FamilyMemberRepository             familyMemberRepo;
    private final KmChildSetupRepository             setupRepo;
    private final EmailService                       emailService;
    private final SmsService                         smsService;
    private final com.churchgeniuspro.service.SubscriptionService subscriptionService;

    private final com.churchgeniuspro.service.PublicLinkResolver links;

    /**
     * The demo/trial messaging gate, consulted BEFORE a volunteer broadcast so the
     * admin gets the refusal as the response rather than a "sent" count for mail
     * that EmailService quietly dropped. Optional (field-injected, null-checked)
     * so the constructor used by the tenant-isolation tests is unchanged; the
     * send services enforce the same rule regardless.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.MessagingPolicy messagingPolicy;

    /** Test seam — supply the policy without a Spring context. */
    public void setMessagingPolicy(com.churchgeniuspro.service.MessagingPolicy p) { this.messagingPolicy = p; }

    public KidsMinistryController(KmChildRepository childRepo,
                                  KmClassroomRepository classroomRepo,
                                  KmAuthorizedPickupRepository pickupRepo,
                                  KmCheckinRepository checkinRepo,
                                  KmVolunteerRepository volunteerRepo,
                                  KmVolunteerRoleRepository kmVolRoleRepo,
                                  KmVolunteerRoleAssignmentRepository kmVolRoleAssignRepo,
                                  FamilyMemberRepository familyMemberRepo,
                                  KmChildSetupRepository setupRepo,
                                  EmailService emailService,
                                  SmsService smsService,
                                  com.churchgeniuspro.service.SubscriptionService subscriptionService,
            com.churchgeniuspro.service.PublicLinkResolver links) {
        this.links = links;
        this.childRepo           = childRepo;
        this.classroomRepo       = classroomRepo;
        this.pickupRepo          = pickupRepo;
        this.checkinRepo         = checkinRepo;
        this.volunteerRepo       = volunteerRepo;
        this.kmVolRoleRepo       = kmVolRoleRepo;
        this.kmVolRoleAssignRepo = kmVolRoleAssignRepo;
        this.familyMemberRepo    = familyMemberRepo;
        this.setupRepo           = setupRepo;
        this.emailService        = emailService;
        this.smsService          = smsService;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Subscription plan: kids-portal limit check. The count is based on family
     * members with a child role (Child / Son / Daughter), per the plan spec.
     * Returns {@code null} when allowed; otherwise the user-facing message.
     */
    private String kidsPortalLimitMessage(String cid) {
        try {
            long children = familyMemberRepo.countChildRoleMembers(cid);
            return subscriptionService.checkKidsPortalLimit(cid, children);
        } catch (Exception e) {
            return null; // fail-open on limit-check errors
        }
    }

    // ════════════════════════════════════════════════════════
    // CHILDREN SETUP OPTIONS  (per-tenant section toggles)
    // ════════════════════════════════════════════════════════

    @GetMapping("/child-setup")
    public ResponseEntity<?> getChildSetup(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmChildSetup s = setupRepo.findByClientId(cid).orElseGet(() -> KmChildSetup.defaults(cid));
        return ResponseEntity.ok(setupMap(s));
    }

    @PutMapping("/child-setup")
    public ResponseEntity<?> saveChildSetup(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmChildSetup s = setupRepo.findByClientId(cid).orElseGet(() -> KmChildSetup.defaults(cid));
        s.setClassroomEnabled(boolOf(body, "classroomEnabled", true));
        s.setEmergencyContactEnabled(boolOf(body, "emergencyContactEnabled", true));
        s.setMedicalInfoEnabled(boolOf(body, "medicalInfoEnabled", true));
        s.setAllergiesEnabled(boolOf(body, "allergiesEnabled", true));
        s.setMedicalNotesEnabled(boolOf(body, "medicalNotesEnabled", true));
        s.setAuthorizedPickupEnabled(boolOf(body, "authorizedPickupEnabled", true));
        // ── pickup-expiration / alert config ──
        s.setDefaultPickupTime(cleanTime(str(body, "defaultPickupTime")));
        s.setPickupExpirationTime(cleanTime(str(body, "pickupExpirationTime")));
        s.setEmailAlertsEnabled(boolOf(body, "emailAlertsEnabled", false));
        s.setSmsAlertsEnabled(boolOf(body, "smsAlertsEnabled", false));
        Object recips = body.get("alertRecipients");
        if (recips != null) {
            try { s.setAlertRecipients(recips instanceof String rs ? rs : MAPPER.writeValueAsString(recips)); }
            catch (Exception e) { /* keep previous */ }
        }
        s.setDedupeName(boolOf(body, "dedupeName", true));
        s.setDedupePhone(boolOf(body, "dedupePhone", true));
        s.setDedupeEmail(boolOf(body, "dedupeEmail", true));
        s.setDedupeMemberId(boolOf(body, "dedupeMemberId", true));
        s.setUpdatedAt(LocalDateTime.now());
        setupRepo.save(s);
        return ResponseEntity.ok(setupMap(s));
    }

    private String cleanTime(String t) {
        if (t == null || t.isBlank()) return null;
        return t.trim().matches("\\d{1,2}:\\d{2}") ? t.trim() : null;
    }

    private Map<String, Object> setupMap(KmChildSetup s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("classroomEnabled", s.isClassroomEnabled());
        m.put("emergencyContactEnabled", s.isEmergencyContactEnabled());
        m.put("medicalInfoEnabled", s.isMedicalInfoEnabled());
        m.put("allergiesEnabled", s.isAllergiesEnabled());
        m.put("medicalNotesEnabled", s.isMedicalNotesEnabled());
        m.put("authorizedPickupEnabled", s.isAuthorizedPickupEnabled());
        m.put("defaultPickupTime", s.getDefaultPickupTime());
        m.put("pickupExpirationTime", s.getPickupExpirationTime());
        m.put("emailAlertsEnabled", s.isEmailAlertsEnabled());
        m.put("smsAlertsEnabled", s.isSmsAlertsEnabled());
        Object recips = new ArrayList<>();
        if (s.getAlertRecipients() != null && !s.getAlertRecipients().isBlank()) {
            try { recips = MAPPER.readValue(s.getAlertRecipients(), List.class); } catch (Exception e) { /* leave empty */ }
        }
        m.put("alertRecipients", recips);
        m.put("dedupeName", s.isDedupeName());
        m.put("dedupePhone", s.isDedupePhone());
        m.put("dedupeEmail", s.isDedupeEmail());
        m.put("dedupeMemberId", s.isDedupeMemberId());
        return m;
    }

    private boolean boolOf(Map<String, Object> body, String key, boolean dflt) {
        Object v = body.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String str) return Boolean.parseBoolean(str);
        return dflt;
    }

    /** Encrypted cid for the logged-in tenant, to embed the standalone /kidsCheckin page in-app. */
    @GetMapping("/checkin-cid")
    public ResponseEntity<?> checkinCid(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        if (cid == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        // The check-in QR carries the church's live Kids Check-In link token.
        return links.ensureLink(cid, com.churchgeniuspro.service.PublicPagePolicy.KIDS_CHECKIN_URL, "Kids Check-In")
                .<ResponseEntity<?>>map(l -> ResponseEntity.ok(Map.of("cid", l.getToken())))
                .orElseGet(() -> ResponseEntity.status(403).body(Map.of("error", "Kids Check-In is not available for this account.")));
    }

    /**
     * Directory of existing people (members + volunteers) with a name and an email
     * or phone — used by the Setup alert-recipient picker so staff can choose a
     * known person instead of re-typing contact details.
     */
    @GetMapping("/alert-directory")
    public ResponseEntity<?> alertDirectory(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        java.util.function.BiConsumer<String[], String> add = (nep, source) -> {
            String name = nep[0], email = nep[1], phone = nep[2];
            if ((name == null || name.isBlank()) && (email == null || email.isBlank())) return;
            if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) return;  // need a contact
            String key = (email != null ? email.trim().toLowerCase() : "") + "|" + (phone != null ? phone.replaceAll("[^0-9]", "") : "");
            byKey.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name == null ? "" : name.trim());
                m.put("email", email == null ? "" : email.trim());
                m.put("phone", phone == null ? "" : phone.trim());
                m.put("source", source);
                return m;
            });
        };

        for (FamilyMember m : familyMemberRepo.findActiveByTenantForEtl(cid)) {
            String name = ((m.getFirstName() == null ? "" : m.getFirstName()) + " "
                    + (m.getLastName() == null ? "" : m.getLastName())).trim();
            add.accept(new String[]{ name, m.getEmail(), m.getPhone() }, "Member");
        }
        for (KmVolunteer v : volunteerRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(cid)) {
            add.accept(new String[]{ v.getName(), v.getEmail(), v.getPhone() }, "Volunteer");
        }
        return ResponseEntity.ok(new ArrayList<>(byKey.values()));
    }

    // ════════════════════════════════════════════════════════
    // CLASSROOMS
    // ════════════════════════════════════════════════════════

    @GetMapping("/classrooms")
    public ResponseEntity<?> listClassrooms(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        List<KmClassroom> rooms = classroomRepo.findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(cid);
        return ResponseEntity.ok(rooms.stream().map(this::classroomMap).toList());
    }

    @PostMapping("/classrooms")
    public ResponseEntity<?> saveClassroom(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        Long id = body.get("id") != null ? toLong(body.get("id")) : null;
        KmClassroom room;
        if (id != null) {
            room = classroomRepo.findByIdAndClientId(id, cid).orElse(null);
            if (room == null) return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        } else {
            room = new KmClassroom();
        }
        room.setClientId(cid);
        room.setClassName(str(body, "className"));
        room.setMinAge(toInt(body.get("minAge")));
        room.setMaxAge(toInt(body.get("maxAge")));
        room.setCapacity(toInt(body.get("capacity")));
        room.setRoomNumber(str(body, "roomNumber"));
        room.setDescription(str(body, "description"));
        classroomRepo.save(room);
        return ResponseEntity.ok(Map.of("success", true, "id", room.getId()));
    }

    @DeleteMapping("/classrooms/{id}")
    public ResponseEntity<?> deleteClassroom(@PathVariable Long id, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmClassroom room = classroomRepo.findById(id).orElse(null);
        if (room == null || !cid.equals(room.getClientId())) return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        room.setDeleteFlag(true);
        classroomRepo.save(room);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ════════════════════════════════════════════════════════
    // CHILDREN
    // ════════════════════════════════════════════════════════

    @GetMapping("/children")
    public ResponseEntity<?> listChildren(@RequestParam(defaultValue = "false") boolean includeInactive,
                                          HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        List<KmChild> children = includeInactive
                ? childRepo.findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(cid)
                : childRepo.findByClientIdAndDeleteFlagFalseAndInactiveFalseOrderByLastNameAscFirstNameAsc(cid);
        List<KmClassroom> classrooms = classroomRepo.findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(cid);
        Map<Long, String> classNames = new HashMap<>();
        classrooms.forEach(r -> classNames.put(r.getId(), r.getClassName()));
        // One query for everyone currently checked in → toggle button state without N+1.
        Map<Long, KmCheckin> activeByChild = new HashMap<>();
        for (KmCheckin ci : checkinRepo.findByClientIdAndCheckoutTimeIsNullOrderByCheckinTimeDesc(cid)) {
            if (ci.getChildId() != null) activeByChild.putIfAbsent(ci.getChildId(), ci);
        }
        return ResponseEntity.ok(children.stream().map(c -> childMap(c, classNames, activeByChild.get(c.getId()))).toList());
    }

    @GetMapping("/children/{id}")
    public ResponseEntity<?> getChild(@PathVariable Long id, HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmChild child = childRepo.findById(id).orElse(null);
        if (child == null || !cid.equals(child.getClientId())) return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        List<KmAuthorizedPickup> pickups = pickupRepo.findByClientIdAndChildIdAndDeleteFlagFalseOrderByPersonNameAsc(cid, id);
        KmCheckin activeNow = checkinRepo.findActiveForChild(cid, id).stream().findFirst().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>(childMap(child, Map.of(), activeNow));
        result.put("authorizedPickups", pickups.stream().map(this::pickupMap).toList());
        result.put("formImageData", child.getFormImageData());

        // Most-recent check-in/out summary for the child detail panel.
        KmCheckin last = checkinRepo.findFirstByClientIdAndChildIdOrderByCheckinTimeDesc(cid, id).orElse(null);
        if (last != null) {
            Map<String, Object> lc = new LinkedHashMap<>();
            lc.put("checkinTime",   last.getCheckinTime()  != null ? last.getCheckinTime().toString()  : null);
            lc.put("checkoutTime",  last.getCheckoutTime() != null ? last.getCheckoutTime().toString() : null);
            lc.put("securityCode",  last.getSecurityCode());
            lc.put("barcode",       last.getFamilyCheckinCode() != null ? last.getFamilyCheckinCode() : last.getSecurityCode());
            lc.put("checkedOutBy",  last.getCheckedOutBy());
            lc.put("checkedOutUser",last.getCheckedOutUser());
            lc.put("status",        last.getCheckoutTime() != null ? "Checked Out" : "Checked In");
            result.put("lastCheckin", lc);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/children")
    @Transactional
    // Save flow does childRepo.save → pickupRepo.deleteByChildId → N×pickupRepo.save.
    // The delete-by-derived-query needs an open write transaction; without
    // @Transactional Spring Data hands back a "no EntityManager with actual
    // transaction" failure. Wrapping the whole method also gets us atomicity
    // — partial saves on a mid-loop crash are rolled back.
    public ResponseEntity<?> saveChild(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        Long id = body.get("id") != null ? toLong(body.get("id")) : null;

        // Subscription plan: kids-portal limit (new registrations only)
        if (id == null) {
            String limitMsg = kidsPortalLimitMessage(cid);
            if (limitMsg != null) return ResponseEntity.status(403).body(Map.of("error", limitMsg));
        }
        KmChild child;
        if (id != null) {
            child = childRepo.findByIdAndClientId(id, cid).orElse(null);
            if (child == null) return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        } else {
            child = new KmChild();
        }
        // Foreign keys from the body must belong to this church.
        Long classroomId = toLong(body.get("classroomId"));
        if (classroomId != null && classroomRepo.findByIdAndClientId(classroomId, cid).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Classroom not found"));
        Integer fmId = toInt(body.get("familyMemberId"));
        if (fmId != null && familyMemberRepo.findByIdAndTenant(fmId, cid).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Family member not found"));
        child.setClientId(cid);
        child.setFirstName(str(body, "firstName"));
        child.setLastName(str(body, "lastName"));
        child.setGender(str(body, "gender"));
        child.setGrade(str(body, "grade"));
        child.setAllergies(str(body, "allergies"));
        child.setMedicalNotes(str(body, "medicalNotes"));
        child.setParentName(str(body, "parentName"));
        child.setParentPhone(str(body, "parentPhone"));
        child.setParentEmail(str(body, "parentEmail"));
        child.setEmergencyContactName(str(body, "emergencyContactName"));
        child.setEmergencyContactPhone(str(body, "emergencyContactPhone"));
        if (body.get("classroomId") != null) child.setClassroomId(toLong(body.get("classroomId")));
        if (body.get("familyMemberId") != null) child.setFamilyMemberId(toInt(body.get("familyMemberId")));
        String dobStr = str(body, "dob");
        if (dobStr != null && !dobStr.isBlank()) child.setDob(LocalDate.parse(dobStr));
        child.setInactive(Boolean.TRUE.equals(body.get("inactive")));
        if (body.containsKey("alertsEnabled")) child.setPickupAlertsEnabled(boolOf(body, "alertsEnabled", true));
        else if (child.getPickupAlertsEnabled() == null) child.setPickupAlertsEnabled(Boolean.TRUE);
        if (body.containsKey("formImageData")) child.setFormImageData(str(body, "formImageData"));
        if (child.getCreatedDate() == null) child.setCreatedDate(LocalDate.now());
        // Auto-capture who registered the child and exactly when (first save only).
        if (child.getRegisteredAt() == null) {
            child.setRegisteredAt(LocalDateTime.now());
            child.setRegisteredBy(SessionUtil.getUsername(req));
        }
        childRepo.save(child);

        // Save authorized pickups if provided
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pickups = (List<Map<String, Object>>) body.get("authorizedPickups");
        if (pickups != null) {
            pickupRepo.deleteByClientIdAndChildId(cid, child.getId());
            for (Map<String, Object> p : pickups) {
                KmAuthorizedPickup ap = new KmAuthorizedPickup();
                ap.setClientId(cid);
                ap.setChildId(child.getId());
                ap.setPersonName(str(p, "personName"));
                ap.setRelationship(str(p, "relationship"));
                ap.setPhone(str(p, "phone"));
                pickupRepo.save(ap);
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "id", child.getId()));
    }

    /**
     * Register up to 3 children on one form that shares a single parent/guardian
     * section and a single Authorized Pickup list. Each child becomes its own
     * KmChild row carrying the shared parent + pickups + (optional) paper-form
     * photo, with registeredBy/registeredAt auto-captured. Returns the created
     * children so the caller can immediately check them in and print labels.
     *
     * Body: {
     *   parentName, parentPhone, parentEmail, familyMemberId?,
     *   emergencyContactName, emergencyContactPhone,
     *   formImageData?,                       // shared paper-form photo
     *   authorizedPickups: [{personName, relationship, phone}, ...],
     *   children: [{firstName, lastName, dob?, gender?, grade?, allergies?, medicalNotes?, classroomId?}, ...]
     * }
     */
    @PostMapping("/children/register-batch")
    @Transactional
    public ResponseEntity<?> registerBatch(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kids = (List<Map<String, Object>>) body.get("children");
        if (kids == null || kids.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "At least one child is required"));
        if (kids.size() > 3)
            return ResponseEntity.badRequest().body(Map.of("error", "A maximum of 3 children can be registered at once"));

        // Subscription plan: kids-portal limit
        String limitMsg = kidsPortalLimitMessage(cid);
        if (limitMsg != null) return ResponseEntity.status(403).body(Map.of("error", limitMsg));

        String parentName  = str(body, "parentName");
        String parentPhone = str(body, "parentPhone");
        String parentEmail = str(body, "parentEmail");
        Integer familyMemberId = body.get("familyMemberId") != null ? toInt(body.get("familyMemberId")) : null;
        if (familyMemberId != null && familyMemberRepo.findByIdAndTenant(familyMemberId, cid).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Family member not found"));
        // Validate classroom ids up front so nothing is persisted before a rejection.
        for (Map<String, Object> k : kids) {
            Long classroomId = toLong(k.get("classroomId"));
            if (classroomId != null && classroomRepo.findByIdAndClientId(classroomId, cid).isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "Classroom not found"));
        }
        String emName = str(body, "emergencyContactName");
        String emPhone = str(body, "emergencyContactPhone");
        String formImage = str(body, "formImageData");
        String registrar = SessionUtil.getUsername(req);
        LocalDateTime now = LocalDateTime.now();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pickups = (List<Map<String, Object>>) body.get("authorizedPickups");

        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> k : kids) {
            String fn = str(k, "firstName");
            String ln = str(k, "lastName");
            if ((fn == null || fn.isBlank()) && (ln == null || ln.isBlank())) continue;  // skip empty child slots

            KmChild child = new KmChild();
            child.setClientId(cid);
            child.setFirstName(fn);
            child.setLastName(ln);
            child.setGender(str(k, "gender"));
            child.setGrade(str(k, "grade"));
            child.setAllergies(str(k, "allergies"));
            child.setMedicalNotes(str(k, "medicalNotes"));
            String dobStr = str(k, "dob");
            if (dobStr != null && !dobStr.isBlank()) child.setDob(LocalDate.parse(dobStr));
            if (k.get("classroomId") != null) child.setClassroomId(toLong(k.get("classroomId")));
            // shared sections
            child.setParentName(parentName);
            child.setParentPhone(parentPhone);
            child.setParentEmail(parentEmail);
            child.setFamilyMemberId(familyMemberId);
            child.setEmergencyContactName(emName);
            child.setEmergencyContactPhone(emPhone);
            child.setFormImageData(formImage);
            child.setPickupAlertsEnabled(body.containsKey("alertsEnabled") ? boolOf(body, "alertsEnabled", true) : Boolean.TRUE);
            child.setCreatedDate(LocalDate.now());
            child.setRegisteredAt(now);
            child.setRegisteredBy(registrar);
            childRepo.save(child);

            if (pickups != null) {
                for (Map<String, Object> p : pickups) {
                    String pn = str(p, "personName");
                    if (pn == null || pn.isBlank()) continue;
                    KmAuthorizedPickup ap = new KmAuthorizedPickup();
                    ap.setClientId(cid);
                    ap.setChildId(child.getId());
                    ap.setPersonName(pn);
                    ap.setRelationship(str(p, "relationship"));
                    ap.setPhone(str(p, "phone"));
                    pickupRepo.save(ap);
                }
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", child.getId());
            c.put("firstName", child.getFirstName());
            c.put("lastName", child.getLastName());
            c.put("classroomId", child.getClassroomId());
            c.put("allergies", child.getAllergies());
            created.add(c);
        }
        if (created.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No valid children to register"));
        return ResponseEntity.ok(Map.of("success", true, "children", created,
                "parentPhone", parentPhone == null ? "" : parentPhone));
    }

    @DeleteMapping("/children/{id}")
    public ResponseEntity<?> deleteChild(@PathVariable Long id, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmChild child = childRepo.findById(id).orElse(null);
        if (child == null || !cid.equals(child.getClientId())) return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        child.setDeleteFlag(true);
        childRepo.save(child);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Lookup children by parent phone for check-in */
    @GetMapping("/children/lookup")
    public ResponseEntity<?> lookupByPhone(@RequestParam String phone, HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        String digits = phone.replaceAll("[^0-9]", "");
        List<KmChild> children = childRepo.findByClientIdAndParentPhone(cid, digits);
        List<KmClassroom> classrooms = classroomRepo.findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(cid);
        Map<Long, String> classNames = new HashMap<>();
        classrooms.forEach(r -> classNames.put(r.getId(), r.getClassName()));
        Map<Long, KmCheckin> activeByChild = new HashMap<>();
        for (KmCheckin ci : checkinRepo.findByClientIdAndCheckoutTimeIsNullOrderByCheckinTimeDesc(cid)) {
            if (ci.getChildId() != null) activeByChild.putIfAbsent(ci.getChildId(), ci);
        }
        return ResponseEntity.ok(children.stream().map(c -> childMap(c, classNames, activeByChild.get(c.getId()))).toList());
    }

    // ════════════════════════════════════════════════════════
    // CHECK-IN / CHECK-OUT
    // ════════════════════════════════════════════════════════

    /** Get today's active check-ins */
    @GetMapping("/checkins/active")
    public ResponseEntity<?> activeCheckins(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        List<KmCheckin> checkins = checkinRepo.findActiveCheckins(cid, startOfDay);
        return ResponseEntity.ok(enrichCheckins(checkins, cid));
    }

    /** Get all check-ins for a given date range (days back) */
    @GetMapping("/checkins/history")
    public ResponseEntity<?> checkinHistory(@RequestParam(defaultValue = "7") int days,
                                            @RequestParam(value = "from", required = false) String fromStr,
                                            @RequestParam(value = "to", required = false) String toStr,
                                            HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        List<KmCheckin> checkins;
        // Explicit date range (yyyy-MM-dd) takes precedence over the rolling-days window.
        if (fromStr != null && !fromStr.isBlank()) {
            LocalDate fromD;
            try { fromD = LocalDate.parse(fromStr.trim()); }
            catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "Invalid 'from' date")); }
            LocalDate toD;
            try { toD = (toStr != null && !toStr.isBlank()) ? LocalDate.parse(toStr.trim()) : LocalDate.now(); }
            catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "Invalid 'to' date")); }
            // inclusive end date → next-day exclusive bound
            checkins = checkinRepo.findByClientIdInRange(cid, fromD.atStartOfDay(), toD.plusDays(1).atStartOfDay());
        } else {
            LocalDateTime from = LocalDate.now().minusDays(days).atStartOfDay();
            checkins = checkinRepo.findByClientIdSince(cid, from);
        }
        return ResponseEntity.ok(enrichCheckins(checkins, cid));
    }

    /**
     * Per-child check-in detail used by the Children page expander.
     * Returns the active check-in for this child (if any) AND every other
     * row that shares the same familyCheckinCode (so kid + guardian rows
     * created together by the public /kidsCheckin page surface together).
     *
     * Response shape:
     * <pre>{@code
     * {
     *   "active": [ { id, kind:"child"|"guardian", name, role?, code, checkinTime, ... } ],
     *   "familyGroup": [ ... same shape, the full family group ... ],
     *   "history": [ ... last 30 days of this child's checkins ... ]
     * }
     * }</pre>
     */
    @GetMapping("/children/{childId}/checkin-detail")
    public ResponseEntity<?> childCheckinDetail(@PathVariable Long childId,
                                                HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        KmChild child = childRepo.findById(childId).orElse(null);
        if (child == null || !cid.equals(child.getClientId())) {
            return ResponseEntity.status(404).body(Map.of("error", "Child not found"));
        }

        List<KmCheckin> active  = checkinRepo.findActiveForChild(cid, childId);
        // Resolve the family-group rows from the most-recent active check-in's
        // shared code (if any). If the child was checked in via the legacy
        // per-child flow, there's no familyCheckinCode and the group is just
        // the kid themselves.
        List<KmCheckin> familyGroup = List.of();
        if (!active.isEmpty()) {
            String fcode = active.get(0).getFamilyCheckinCode();
            if (fcode != null && !fcode.isBlank()) {
                familyGroup = checkinRepo.findByClientIdAndFamilyCheckinCode(cid, fcode);
            } else {
                familyGroup = active;
            }
        }
        // Last 30 days of history (active + checked-out).
        LocalDateTime since = LocalDate.now().minusDays(30).atStartOfDay();
        List<KmCheckin> recent = checkinRepo.findByClientIdSince(cid, since).stream()
                .filter(c -> c.getChildId() != null && c.getChildId().equals(childId))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active",      mapCheckins(active));
        out.put("familyGroup", mapCheckins(familyGroup));
        out.put("history",     mapCheckins(recent));
        return ResponseEntity.ok(out);
    }

    /** Render KmCheckin rows for the Children-page expander. */
    private List<Map<String, Object>> mapCheckins(List<KmCheckin> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KmCheckin c : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",                c.getId());
            m.put("checkinTime",       c.getCheckinTime() != null ? c.getCheckinTime().toString() : null);
            m.put("checkoutTime",      c.getCheckoutTime() != null ? c.getCheckoutTime().toString() : null);
            m.put("securityCode",      c.getSecurityCode());
            m.put("familyCheckinCode", c.getFamilyCheckinCode());
            m.put("classroomId",       c.getClassroomId());
            // Identify who this row is for. Kid rows carry childId; guardian
            // rows carry guardianMemberId. Either field is enriched with a
            // display name so the frontend doesn't need a second lookup.
            if (c.getChildId() != null) {
                KmChild kid = childRepo.findById(c.getChildId()).orElse(null);
                m.put("kind",   "child");
                m.put("childId", c.getChildId());
                m.put("name",   kid != null
                        ? (safe(kid.getFirstName()) + " " + safe(kid.getLastName())).trim() : "Child");
            } else if (c.getGuardianMemberId() != null) {
                FamilyMember g = familyMemberRepo.findById(c.getGuardianMemberId()).orElse(null);
                m.put("kind",             "guardian");
                m.put("guardianMemberId", c.getGuardianMemberId());
                m.put("name",  g != null
                        ? (safe(g.getFirstName()) + " " + safe(g.getLastName())).trim() : "Guardian");
                m.put("role",  g != null ? g.getRole() : null);
                m.put("phone", g != null ? g.getPhone() : null);
            } else {
                m.put("kind", "unknown");
                m.put("name", "—");
            }
            out.add(m);
        }
        return out;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /** Check a child in */
    @PostMapping("/checkins")
    public ResponseEntity<?> checkIn(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        Long childId = toLong(body.get("childId"));
        if (childId == null) return ResponseEntity.badRequest().body(Map.of("error", "childId required"));

        KmChild child = childRepo.findById(childId).orElse(null);
        if (child == null || !cid.equals(child.getClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Child not found"));

        // Prevent double check-in
        List<KmCheckin> active = checkinRepo.findActiveForChild(cid, childId);
        if (!active.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Child is already checked in"));

        KmCheckin checkin = new KmCheckin();
        checkin.setClientId(cid);
        checkin.setChildId(childId);
        checkin.setClassroomId(child.getClassroomId());
        checkin.setCheckinTime(LocalDateTime.now());
        checkin.setSecurityCode(generateSecurityCode());
        checkin.setNotes(str(body, "notes"));
        checkinRepo.save(checkin);

        // checkinTime serialized as ISO string so the frontend can parse it
        // with new Date(...) and render in the user's locale.
        return ResponseEntity.ok(Map.of(
                "success", true,
                "id", checkin.getId(),
                "securityCode", checkin.getSecurityCode(),
                "childName", child.getFirstName() + " " + child.getLastName(),
                "checkinTime", checkin.getCheckinTime() != null
                        ? checkin.getCheckinTime().toString() : ""
        ));
    }

    /** Check a child out by security code */
    @PostMapping("/checkins/checkout")
    public ResponseEntity<?> checkOut(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        String code = str(body, "securityCode");
        if (code == null || code.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "securityCode required"));

        KmCheckin checkin = checkinRepo.findByClientIdAndSecurityCodeAndCheckoutTimeIsNull(cid, code.toUpperCase()).orElse(null);
        if (checkin == null)
            return ResponseEntity.status(404).body(Map.of("error", "No active check-in found for code: " + code));

        checkin.setCheckoutTime(LocalDateTime.now());
        checkin.setCheckedOutBy(str(body, "checkedOutBy"));
        checkin.setCheckedOutUser(SessionUtil.getUsername(req));
        checkinRepo.save(checkin);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Secure check-out: validates that the person picking up matches the child's
     * parent/guardian or an authorized-pickup record BEFORE closing the check-in,
     * then closes every active row under the scanned/typed code (the family group).
     * Records check-out time, the guardian name (checkedOutBy) and the staff user
     * (checkedOutUser). Body: {code, pickupName, pickupPhone}.
     */
    @PostMapping("/checkins/checkout-secure")
    public ResponseEntity<?> checkOutSecure(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        String code = str(body, "code");
        if (code == null || code.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "A check-in code is required"));
        code = code.trim().toUpperCase();
        if (!code.matches("[A-Z0-9\\-]{1,20}"))
            return ResponseEntity.badRequest().body(Map.of("error", "That doesn't look like a valid code"));

        List<KmCheckin> rows = checkinRepo.findByClientIdAndAnyCode(cid, code).stream()
                .filter(r -> r.getCheckoutTime() == null).toList();
        if (rows.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "No active check-in found for that code"));

        String pickupName  = str(body, "pickupName");
        String pickupPhone = str(body, "pickupPhone");

        // Gather the authorized people across the child rows under this code.
        List<String> authorizedNames = new ArrayList<>();
        List<String> authorizedPhones = new ArrayList<>();
        String childName = null;
        for (KmCheckin r : rows) {
            if (r.getChildId() == null) continue;
            KmChild kid = childRepo.findById(r.getChildId()).orElse(null);
            if (kid == null) continue;
            if (childName == null) childName = (safe(kid.getFirstName()) + " " + safe(kid.getLastName())).trim();
            if (kid.getParentName() != null)  authorizedNames.add(kid.getParentName());
            if (kid.getParentPhone() != null) authorizedPhones.add(kid.getParentPhone());
            for (KmAuthorizedPickup p : pickupRepo.findByClientIdAndChildIdAndDeleteFlagFalseOrderByPersonNameAsc(cid, r.getChildId())) {
                if (p.getPersonName() != null) authorizedNames.add(p.getPersonName());
                if (p.getPhone() != null)      authorizedPhones.add(p.getPhone());
            }
        }

        boolean validated = matchesAuthorized(pickupName, pickupPhone, authorizedNames, authorizedPhones);
        if (!validated) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Pickup name/phone does not match an authorized guardian for this child.");
            err.put("validated", false);
            err.put("authorized", authorizedNames.stream().distinct().toList());
            return ResponseEntity.status(422).body(err);
        }

        String staff = SessionUtil.getUsername(req);
        LocalDateTime now = LocalDateTime.now();
        int closed = 0;
        for (KmCheckin r : rows) {
            r.setCheckoutTime(now);
            if (pickupName != null && !pickupName.isBlank()) r.setCheckedOutBy(pickupName.trim());
            r.setCheckedOutUser(staff);
            checkinRepo.save(r);
            closed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("validated", true);
        out.put("closed", closed);
        out.put("childName", childName);
        out.put("checkoutTime", now.toString());
        out.put("checkedOutBy", pickupName);
        out.put("checkedOutUser", staff);
        return ResponseEntity.ok(out);
    }

    /** True if the pickup name OR phone matches one of the authorized entries. */
    private boolean matchesAuthorized(String name, String phone,
                                      List<String> names, List<String> phones) {
        String n = name == null ? "" : name.trim().toLowerCase();
        String ph = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        boolean nameOk = false, phoneOk = false;
        if (!n.isEmpty()) {
            for (String a : names) {
                if (a == null) continue;
                String an = a.trim().toLowerCase();
                if (an.isEmpty()) continue;
                if (an.equals(n) || an.contains(n) || n.contains(an)) { nameOk = true; break; }
            }
        }
        if (!ph.isEmpty()) {
            for (String a : phones) {
                if (a == null) continue;
                String ad = a.replaceAll("[^0-9]", "");
                if (ad.length() >= 7 && (ad.endsWith(ph) || ph.endsWith(ad))) { phoneOk = true; break; }
            }
        }
        return nameOk || phoneOk;
    }

    /** Check out by child ID directly (admin override) */
    @PostMapping("/checkins/checkout-by-child")
    public ResponseEntity<?> checkOutByChild(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        Long childId = toLong(body.get("childId"));
        List<KmCheckin> active = checkinRepo.findActiveForChild(cid, childId);
        if (active.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "No active check-in"));
        KmCheckin checkin = active.get(0);
        checkin.setCheckoutTime(LocalDateTime.now());
        checkin.setCheckedOutBy(str(body, "checkedOutBy"));
        checkin.setCheckedOutUser(SessionUtil.getUsername(req));
        String method = str(body, "method");
        checkin.setCheckoutMethod(method != null ? method : "List");
        String n = str(body, "notes");
        if (n != null && !n.isBlank())
            checkin.setNotes(checkin.getNotes() == null || checkin.getNotes().isBlank() ? n : (checkin.getNotes() + "\n" + n));
        checkinRepo.save(checkin);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Check out a single KmCheckin row by its id. Works for both kid rows
     * (with childId) and guardian rows (with guardianMemberId) — the
     * Currently Checked In board uses it for one-click row checkout.
     */
    @PostMapping("/checkins/{id}/checkout")
    public ResponseEntity<?> checkOutById(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmCheckin row = checkinRepo.findById(id).orElse(null);
        if (row == null || !cid.equals(row.getClientId())) {
            return ResponseEntity.status(404).body(Map.of("error", "Check-in not found"));
        }
        if (row.getCheckoutTime() != null) {
            return ResponseEntity.ok(Map.of("success", true, "alreadyCheckedOut", true));
        }
        row.setCheckoutTime(LocalDateTime.now());
        if (body != null) row.setCheckedOutBy(str(body, "checkedOutBy"));
        row.setCheckedOutUser(SessionUtil.getUsername(req));
        checkinRepo.save(row);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Family check-out: closes every active row that shares the given
     * familyCheckinCode. Lets the staff dismiss a kid + their parent in a
     * single click after a public /kidsCheckin submission.
     */
    @PostMapping("/checkins/checkout-family")
    public ResponseEntity<?> checkOutFamily(@RequestBody Map<String, Object> body,
                                            HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid  = SessionUtil.getAppClientId(req);
        String code = str(body, "familyCheckinCode");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "familyCheckinCode required"));
        }
        List<KmCheckin> rows = checkinRepo.findByClientIdAndFamilyCheckinCode(cid, code);
        int closed = 0;
        LocalDateTime now = LocalDateTime.now();
        String by = str(body, "checkedOutBy");
        String staff = SessionUtil.getUsername(req);
        for (KmCheckin row : rows) {
            if (row.getCheckoutTime() != null) continue;
            row.setCheckoutTime(now);
            if (by != null) row.setCheckedOutBy(by);
            row.setCheckedOutUser(staff);
            checkinRepo.save(row);
            closed++;
        }
        return ResponseEntity.ok(Map.of("success", true, "closed", closed));
    }

    /**
     * Manual check-out override — for exceptional situations where no code or
     * barcode is available. Requires Admin permission, a non-blank reason, and
     * records the staff user + timestamp + reason for auditing.
     * Body: {reason}. Path: the KmCheckin row id from the Active Check-Ins list.
     */
    @PostMapping("/checkins/{id}/manual-checkout")
    public ResponseEntity<?> manualCheckout(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);   // override requires elevated permission
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Manual check-out requires admin permission"));
        String cid = SessionUtil.getAppClientId(req);
        String reason = body != null ? str(body, "reason") : null;
        if (reason == null || reason.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "A reason is required for a manual check-out"));

        KmCheckin row = checkinRepo.findById(id).orElse(null);
        if (row == null || !cid.equals(row.getClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Check-in not found"));
        if (row.getCheckoutTime() != null)
            return ResponseEntity.ok(Map.of("success", true, "alreadyCheckedOut", true));

        String staff = SessionUtil.getUsername(req);
        LocalDateTime now = LocalDateTime.now();
        row.setCheckoutTime(now);
        row.setCheckedOutBy("Manual override");
        row.setCheckedOutUser(staff);
        String note = "Manual check-out by " + (staff != null ? staff : "staff") + " at " + now + " — reason: " + reason.trim();
        row.setNotes(row.getNotes() == null || row.getNotes().isBlank() ? note : (row.getNotes() + "\n" + note));
        checkinRepo.save(row);
        return ResponseEntity.ok(Map.of("success", true, "checkoutTime", now.toString(),
                "checkedOutUser", staff == null ? "" : staff, "reason", reason.trim()));
    }

    /**
     * Extend (override) the pickup deadline for one active check-in. Authorized
     * users only. Body: either {minutes:N} to push the current/effective deadline
     * out by N minutes, or {deadline:"HH:mm"} for an absolute time today; plus an
     * optional {notes}. The dashboard + overdue logic use this per-child deadline.
     */
    @PostMapping("/checkins/{id}/extend")
    public ResponseEntity<?> extendPickup(@PathVariable Long id,
                                          @RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmCheckin row = checkinRepo.findById(id).orElse(null);
        if (row == null || !cid.equals(row.getClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Check-in not found"));
        if (row.getCheckoutTime() != null)
            return ResponseEntity.badRequest().body(Map.of("error", "Child is already checked out"));

        KmChildSetup setup = setupRepo.findByClientId(cid).orElse(null);
        LocalDateTime current = effectiveDeadline(row, setup);
        LocalDateTime newDeadline;
        Object minObj = body.get("minutes");
        String deadlineStr = str(body, "deadline");
        if (minObj != null) {
            int mins;
            try { mins = Integer.parseInt(String.valueOf(minObj)); }
            catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "Invalid minutes")); }
            LocalDateTime base = current != null ? current : LocalDateTime.now();
            newDeadline = base.plusMinutes(mins);
        } else if (deadlineStr != null && deadlineStr.matches("\\d{1,2}:\\d{2}")) {
            LocalDate day = row.getCheckinTime() != null ? row.getCheckinTime().toLocalDate() : LocalDate.now();
            newDeadline = day.atTime(LocalTime.parse(padTime(deadlineStr)));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Provide 'minutes' or a 'deadline' time (HH:mm)"));
        }
        row.setPickupDeadline(newDeadline);
        String notes = str(body, "notes");
        if (notes != null && !notes.isBlank()) row.setPickupExtensionNotes(notes.trim());
        checkinRepo.save(row);
        return ResponseEntity.ok(Map.of("success", true, "pickupDeadline", newDeadline.toString()));
    }

    /** Snooze overdue alerts for one check-in (global per child). Body: {minutes}. */
    @PostMapping("/checkins/{id}/snooze")
    public ResponseEntity<?> snoozePickup(@PathVariable Long id,
                                          @RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmCheckin row = checkinRepo.findById(id).orElse(null);
        if (row == null || !cid.equals(row.getClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Check-in not found"));
        int mins;
        try { mins = Integer.parseInt(String.valueOf(body.get("minutes"))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "Invalid minutes")); }
        row.setSnoozeUntil(LocalDateTime.now().plusMinutes(mins));
        checkinRepo.save(row);
        return ResponseEntity.ok(Map.of("success", true, "snoozeUntil", row.getSnoozeUntil().toString()));
    }

    /**
     * Overdue-pickup monitoring dashboard data. Lists every currently checked-in
     * child with their pickup deadline, time remaining / overdue duration, parent
     * + emergency contacts, classroom, check-in time, and extension/alert status.
     */
    @GetMapping("/pickup-dashboard")
    public ResponseEntity<?> pickupDashboard(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmChildSetup setup = setupRepo.findByClientId(cid).orElse(null);

        Map<Long, String> classNames = new HashMap<>();
        classroomRepo.findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(cid)
                .forEach(r -> classNames.put(r.getId(), r.getClassName()));

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KmCheckin ci : checkinRepo.findByClientIdAndCheckoutTimeIsNullOrderByCheckinTimeDesc(cid)) {
            if (ci.getChildId() == null) continue;   // dashboard tracks children
            KmChild kid = childRepo.findById(ci.getChildId()).orElse(null);
            if (kid == null) continue;
            LocalDateTime deadline = effectiveDeadline(ci, setup);
            Long minsRemaining = null, overdueMins = null;
            boolean overdue = false;
            if (deadline != null) {
                long diff = ChronoUnit.MINUTES.between(now, deadline);
                if (diff >= 0) minsRemaining = diff;
                else { overdue = true; overdueMins = -diff; }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("checkinId", ci.getId());
            m.put("childId", kid.getId());
            m.put("childName", (safe(kid.getFirstName()) + " " + safe(kid.getLastName())).trim());
            m.put("classroom", ci.getClassroomId() != null ? classNames.getOrDefault(ci.getClassroomId(), "") : "");
            m.put("checkinTime", ci.getCheckinTime() != null ? ci.getCheckinTime().toString() : null);
            m.put("securityCode", ci.getSecurityCode());
            m.put("barcode", ci.getFamilyCheckinCode() != null ? ci.getFamilyCheckinCode() : ci.getSecurityCode());
            m.put("parentName", kid.getParentName());
            m.put("parentPhone", kid.getParentPhone());
            m.put("parentEmail", kid.getParentEmail());
            m.put("emergencyContactName", kid.getEmergencyContactName());
            m.put("emergencyContactPhone", kid.getEmergencyContactPhone());
            m.put("alertsEnabled", kid.getPickupAlertsEnabled() == null || kid.getPickupAlertsEnabled());
            m.put("pickupDeadline", deadline != null ? deadline.toString() : null);
            m.put("minutesRemaining", minsRemaining);
            m.put("overdue", overdue);
            m.put("overdueMinutes", overdueMins);
            m.put("extended", ci.getPickupDeadline() != null);
            m.put("extensionNotes", ci.getPickupExtensionNotes());
            boolean snoozed = ci.getSnoozeUntil() != null && ci.getSnoozeUntil().isAfter(now);
            m.put("alertStatus", snoozed ? "Snoozed" : (deadline == null ? "No deadline" : (overdue ? "Overdue" : "On time")));
            m.put("snoozeUntil", ci.getSnoozeUntil() != null ? ci.getSnoozeUntil().toString() : null);
            m.put("snoozed", snoozed);
            m.put("lastAlertSent", ci.getLastAlertSentAt() != null ? ci.getLastAlertSentAt().toString() : null);
            m.put("pickupConfirmed", false);
            rows.add(m);
        }
        // Overdue first, then soonest deadline.
        rows.sort((a, b) -> {
            boolean ao = Boolean.TRUE.equals(a.get("overdue")), bo = Boolean.TRUE.equals(b.get("overdue"));
            if (ao != bo) return ao ? -1 : 1;
            Long ar = (Long) a.get("minutesRemaining"), br = (Long) b.get("minutesRemaining");
            if (ar == null) return br == null ? 0 : 1;
            if (br == null) return -1;
            return Long.compare(ar, br);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("children", rows);
        out.put("defaultPickupTime", setup != null ? setup.getDefaultPickupTime() : null);
        out.put("pickupExpirationTime", setup != null ? setup.getPickupExpirationTime() : null);
        return ResponseEntity.ok(out);
    }

    /** Effective pickup deadline: per-child override, else today's expiration/default time. */
    private LocalDateTime effectiveDeadline(KmCheckin ci, KmChildSetup setup) {
        return com.churchgeniuspro.util.KmPickupUtil.effectiveDeadline(ci, setup);
    }

    private String padTime(String t) {
        return com.churchgeniuspro.util.KmPickupUtil.padTime(t);
    }

    /**
     * Looks up the people recorded under a scanned/typed check-in code — the
     * value encoded in the printed label's barcode. Matches either the shared
     * familyCheckinCode or the per-row securityCode so it resolves regardless of
     * which check-in path created the row. Staff-only; used by the barcode-scan
     * checkout on the Kids Ministry page.
     */
    @GetMapping("/checkins/lookup")
    public ResponseEntity<?> lookupByCode(@RequestParam(value = "code", defaultValue = "") String code,
                                          HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        String c = code == null ? "" : code.trim().toUpperCase();
        if (c.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Scan or enter a check-in code"));
        }
        if (!c.matches("[A-Z0-9\\-]{1,20}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "That doesn't look like a valid code"));
        }

        List<KmCheckin> rows = checkinRepo.findByClientIdAndAnyCode(cid, c);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No check-in found for that code"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", c);
        out.put("entries", mapCheckins(rows));
        return ResponseEntity.ok(out);
    }

    // ════════════════════════════════════════════════════════
    // VOLUNTEERS
    // ════════════════════════════════════════════════════════

    @GetMapping("/volunteers")
    public ResponseEntity<?> listVolunteers(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        List<KmVolunteer> vols = volunteerRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(cid);
        List<KmVolunteerRoleAssignment> allAssignments = kmVolRoleAssignRepo.findByClientIdAndDeleteFlagFalse(cid);
        Map<Long, List<String>> rolesPerVol = new HashMap<>();
        for (KmVolunteerRoleAssignment a : allAssignments) {
            rolesPerVol.computeIfAbsent(a.getVolunteerId(), k -> new ArrayList<>()).add(a.getRoleName());
        }
        return ResponseEntity.ok(vols.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("firstName", v.getFirstName() != null ? v.getFirstName() : (v.getName() != null ? v.getName().split(" ")[0] : ""));
            m.put("lastName", v.getLastName() != null ? v.getLastName() : (v.getName() != null && v.getName().contains(" ") ? v.getName().substring(v.getName().indexOf(" ") + 1) : ""));
            m.put("name", v.getName());
            m.put("email", v.getEmail());
            m.put("phone", v.getPhone());
            m.put("status", v.getStatus() != null ? v.getStatus() : "pending");
            m.put("isManual", v.isManual());
            m.put("notes", v.getNotes());
            m.put("familyMemberId", v.getFamilyMemberId());
            m.put("roles", rolesPerVol.getOrDefault(v.getId(), List.of()));
            return m;
        }).toList());
    }

    @PostMapping("/volunteers")
    public ResponseEntity<?> saveVolunteer(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        Long id = body.get("id") != null ? toLong(body.get("id")) : null;
        KmVolunteer v;
        if (id != null) {
            v = volunteerRepo.findByIdAndClientId(id, cid).orElse(null);
            if (v == null) return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        } else {
            v = new KmVolunteer();
        }
        Integer fmId = body.get("familyMemberId") != null ? ((Number) body.get("familyMemberId")).intValue() : null;
        if (fmId != null && familyMemberRepo.findByIdAndTenant(fmId, cid).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Family member not found"));
        v.setClientId(cid);
        String firstName = str(body, "firstName");
        String lastName = str(body, "lastName");
        v.setFirstName(firstName);
        v.setLastName(lastName);
        String fullName = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        v.setName(fullName.isBlank() ? "Unknown" : fullName);
        v.setPhone(str(body, "phone"));
        v.setEmail(str(body, "email"));
        v.setStatus(str(body, "status") != null ? str(body, "status") : "pending");
        v.setManual(Boolean.TRUE.equals(body.get("isManual")));
        v.setNotes(str(body, "notes"));
        if (fmId != null) v.setFamilyMemberId(fmId);
        volunteerRepo.save(v);
        // Sync roles
        Object rawRoles = body.get("roles");
        if (rawRoles instanceof List<?> roleList) {
            List<KmVolunteerRoleAssignment> existing = kmVolRoleAssignRepo.findByClientIdAndVolunteerIdAndDeleteFlagFalse(cid, v.getId());
            existing.forEach(a -> { a.setDeleteFlag(true); kmVolRoleAssignRepo.save(a); });
            for (Object r : roleList) {
                String roleName = r instanceof String ? (String) r : (r instanceof Map<?, ?> rm ? (String) rm.get("roleName") : null);
                if (roleName != null && !roleName.isBlank()) {
                    KmVolunteerRoleAssignment a = new KmVolunteerRoleAssignment();
                    a.setClientId(cid);
                    a.setVolunteerId(v.getId());
                    a.setRoleName(roleName.trim());
                    kmVolRoleAssignRepo.save(a);
                }
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "id", v.getId()));
    }

    @PutMapping("/volunteers/{id}")
    public ResponseEntity<?> updateVolunteer(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmVolunteer v = volunteerRepo.findById(id).orElse(null);
        if (v == null || !cid.equals(v.getClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Not found"));

        // Status-only patch (from table dropdown) — skip full save to avoid side-effects
        boolean statusOnly = body.containsKey("status") && body.size() == 1;
        if (statusOnly) {
            v.setStatus(str(body, "status"));
            volunteerRepo.save(v);
            return ResponseEntity.ok(Map.of("success", true));
        }

        // Full update (from edit modal)
        String firstName = str(body, "firstName");
        String lastName  = str(body, "lastName");
        if (firstName != null) v.setFirstName(firstName);
        if (lastName  != null) v.setLastName(lastName);
        if (firstName != null || lastName != null) {
            String fullName = ((v.getFirstName() != null ? v.getFirstName() : "") + " " + (v.getLastName() != null ? v.getLastName() : "")).trim();
            v.setName(fullName.isBlank() ? "Unknown" : fullName);
        }
        if (body.containsKey("phone"))  v.setPhone(str(body, "phone"));
        if (body.containsKey("email"))  v.setEmail(str(body, "email"));
        if (body.containsKey("status")) v.setStatus(str(body, "status"));
        if (body.containsKey("notes"))  v.setNotes(str(body, "notes"));
        if (body.containsKey("isManual")) v.setManual(Boolean.TRUE.equals(body.get("isManual")));
        volunteerRepo.save(v);

        // Sync roles if provided
        Object rawRoles = body.get("roles");
        if (rawRoles instanceof List<?> roleList) {
            List<KmVolunteerRoleAssignment> existing = kmVolRoleAssignRepo.findByClientIdAndVolunteerIdAndDeleteFlagFalse(cid, id);
            existing.forEach(a -> { a.setDeleteFlag(true); kmVolRoleAssignRepo.save(a); });
            for (Object r : roleList) {
                String roleName = r instanceof String ? (String) r
                        : (r instanceof Map<?, ?> rm ? (String) rm.get("roleName") : null);
                if (roleName != null && !roleName.isBlank()) {
                    KmVolunteerRoleAssignment a = new KmVolunteerRoleAssignment();
                    a.setClientId(cid);
                    a.setVolunteerId(id);
                    a.setRoleName(roleName.trim());
                    kmVolRoleAssignRepo.save(a);
                }
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "id", v.getId()));
    }

    @DeleteMapping("/volunteers/{id}")
    public ResponseEntity<?> deleteVolunteer(@PathVariable Long id, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmVolunteer v = volunteerRepo.findById(id).orElse(null);
        if (v == null || !cid.equals(v.getClientId())) return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        v.setDeleteFlag(true);
        volunteerRepo.save(v);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Volunteer Roles ──────────────────────────────────────

    @GetMapping("/volunteer-roles")
    public ResponseEntity<?> listVolunteerRoles(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        return ResponseEntity.ok(kmVolRoleRepo.findByClientIdAndDeleteFlagFalseOrderByRoleNameAsc(cid)
                .stream().map(r -> Map.of("id", r.getId(), "roleName", r.getRoleName())).toList());
    }

    @PostMapping("/volunteer-roles")
    public ResponseEntity<?> saveVolunteerRole(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmVolunteerRole r = new KmVolunteerRole();
        r.setClientId(cid);
        r.setRoleName(str(body, "roleName"));
        kmVolRoleRepo.save(r);
        return ResponseEntity.ok(Map.of("success", true, "id", r.getId()));
    }

    @DeleteMapping("/volunteer-roles/{id}")
    public ResponseEntity<?> deleteVolunteerRole(@PathVariable Long id, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmVolunteerRole r = kmVolRoleRepo.findById(id).orElse(null);
        if (r == null || !cid.equals(r.getClientId())) return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        r.setDeleteFlag(true);
        kmVolRoleRepo.save(r);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Volunteer messaging ──────────────────────────────────

    @PostMapping("/volunteers/send-email")
    public ResponseEntity<?> sendEmail(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        try (com.churchgeniuspro.util.EmailActionScope __scope = com.churchgeniuspro.util.EmailActionScope.begin("kids-ministry-email")) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        try {
            @SuppressWarnings("unchecked")
            List<String> to = (List<String>) body.get("to");
            String subject = str(body, "subject");
            String msgBody = str(body, "body");
            // Only this church's own volunteers may be addressed — never an arbitrary list.
            List<String> allowed = tenantVolunteerContacts(RoleGuard.clientId(req), true);
            List<String> targets = to == null ? List.of() : to.stream()
                    .filter(a -> a != null && allowed.contains(a.trim().toLowerCase())).distinct().toList();
            if (targets.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No recipients are volunteers of your church."));
            String safeBody = org.springframework.web.util.HtmlUtils.htmlEscape(msgBody == null ? "" : msgBody).replace("\n", "<br>");
            // Sent AS the church, not as the platform: sendOrgEmail carries the tenant,
            // so the Trial/Demo block, the recipient's unsubscribe choice and the
            // plan's monthly allowance all apply — exactly as they do to the SMS
            // sibling below. The tenant-less sendGenericEmail overload skipped all
            // three, which let a trial or demo account mail real volunteers.
            String emailTenant = com.churchgeniuspro.util.SessionUtil.getAppClientId(req);
            EmailService.Delivery d = emailService.delivery(emailTenant);
            if (d.blocked()) return ResponseEntity.status(403).body(Map.of("error", d.reason()));
            for (String addr : targets) emailService.sendOrgEmail(addr, subject, "<p>" + safeBody + "</p>", emailTenant);
            if (d.test()) {
                // Phase B: one test copy to the verified address; the volunteers were simulated.
                return ResponseEntity.ok(Map.of("success", true, "sent", 0,
                        "testEmailsSent", __scope.testEmailsSent(), "simulated", __scope.simulated(), "testEmail", d.testEmail(),
                        "message", "Trial/Demo test: " + __scope.testEmailsSent() + " test email sent to " + d.testEmail()
                                 + " — " + __scope.simulated() + " recipient(s) simulated, none emailed."));
            }
            return ResponseEntity.ok(Map.of("success", true, "sent", targets.size()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
            }
    }

    @PostMapping("/volunteers/send-sms")
    public ResponseEntity<?> sendSms(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        try {
            @SuppressWarnings("unchecked")
            List<String> to = (List<String>) body.get("to");
            String msgBody = str(body, "body");
            String smsTenant = com.churchgeniuspro.util.SessionUtil.getAppClientId(req);
            List<String> allowed = tenantVolunteerContacts(RoleGuard.clientId(req), false);
            List<String> targets = to == null ? List.of() : to.stream()
                    .map(com.churchgeniuspro.util.PhoneNumbers::toE164)
                    .filter(p -> p != null && allowed.contains(p)).distinct().toList();
            if (targets.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No recipients are volunteers of your church."));
            if (messagingPolicy != null) {
                String why = messagingPolicy.smsBlockReason(smsTenant);
                if (why != null) return ResponseEntity.status(403).body(Map.of("error", why));
            }
            for (String phone : targets) smsService.sendForClient(smsTenant, phone, msgBody);
            return ResponseEntity.ok(Map.of("success", true, "sent", targets.size()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════
    // DASHBOARD SUMMARY
    // ════════════════════════════════════════════════════════

    @GetMapping("/summary")
    public ResponseEntity<?> summary(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

        long totalChildren  = childRepo.findByClientIdAndDeleteFlagFalseAndInactiveFalseOrderByLastNameAscFirstNameAsc(cid).size();
        long totalClassrooms = classroomRepo.findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(cid).size();
        long checkedInToday = checkinRepo.findActiveCheckins(cid, startOfDay).size();
        long totalVolunteers = volunteerRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(cid).size();

        // Per-classroom counts
        List<KmClassroom> rooms = classroomRepo.findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(cid);
        List<Map<String, Object>> roomStats = rooms.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("className", r.getClassName());
            m.put("roomNumber", r.getRoomNumber());
            m.put("capacity", r.getCapacity());
            m.put("enrolled", childRepo.countByClientIdAndClassroomIdAndDeleteFlagFalseAndInactiveFalse(cid, r.getId()));
            m.put("presentToday", checkinRepo.countByClientIdAndClassroomIdAndCheckoutTimeIsNull(cid, r.getId()));
            return m;
        }).toList();

        return ResponseEntity.ok(Map.of(
                "totalChildren", totalChildren,
                "totalClassrooms", totalClassrooms,
                "checkedInToday", checkedInToday,
                "totalVolunteers", totalVolunteers,
                "classroomStats", roomStats
        ));
    }

    // ════════════════════════════════════════════════════════
    // AUTO-ASSIGN CLASSROOM
    // ════════════════════════════════════════════════════════

    @PostMapping("/children/{id}/auto-assign-classroom")
    public ResponseEntity<?> autoAssignClassroom(@PathVariable Long id, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        KmChild child = childRepo.findById(id).orElse(null);
        if (child == null || !cid.equals(child.getClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Child not found"));
        if (child.getDob() == null)
            return ResponseEntity.badRequest().body(Map.of("error", "DOB required for auto-assignment"));

        int ageYears = Period.between(child.getDob(), LocalDate.now()).getYears();
        List<KmClassroom> rooms = classroomRepo.findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(cid);
        KmClassroom best = rooms.stream()
                .filter(r -> (r.getMinAge() == null || ageYears >= r.getMinAge())
                          && (r.getMaxAge() == null || ageYears <= r.getMaxAge()))
                .findFirst().orElse(null);
        if (best == null)
            return ResponseEntity.badRequest().body(Map.of("error", "No matching classroom for age " + ageYears));

        child.setClassroomId(best.getId());
        childRepo.save(child);
        return ResponseEntity.ok(Map.of("success", true, "classroomId", best.getId(), "className", best.getClassName()));
    }

    // ════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════

    private Map<String, Object> classroomMap(KmClassroom r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId()); m.put("className", r.getClassName());
        m.put("minAge", r.getMinAge()); m.put("maxAge", r.getMaxAge());
        m.put("capacity", r.getCapacity()); m.put("roomNumber", r.getRoomNumber());
        m.put("description", r.getDescription());
        return m;
    }

    private Map<String, Object> childMap(KmChild c, Map<Long, String> classNames) {
        return childMap(c, classNames, null);
    }

    private Map<String, Object> childMap(KmChild c, Map<Long, String> classNames, KmCheckin active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("firstName", c.getFirstName()); m.put("lastName", c.getLastName());
        m.put("dob", c.getDob() != null ? c.getDob().toString() : null);
        m.put("gender", c.getGender()); m.put("grade", c.getGrade());
        m.put("allergies", c.getAllergies()); m.put("medicalNotes", c.getMedicalNotes());
        m.put("parentName", c.getParentName()); m.put("parentPhone", c.getParentPhone());
        m.put("parentEmail", c.getParentEmail());
        m.put("emergencyContactName", c.getEmergencyContactName());
        m.put("emergencyContactPhone", c.getEmergencyContactPhone());
        m.put("classroomId", c.getClassroomId());
        m.put("classroomName", c.getClassroomId() != null ? classNames.getOrDefault(c.getClassroomId(), "") : "");
        m.put("familyMemberId", c.getFamilyMemberId());
        m.put("inactive", c.isInactive());
        m.put("createdDate", c.getCreatedDate() != null ? c.getCreatedDate().toString() : null);
        m.put("registeredBy", c.getRegisteredBy());
        m.put("registeredAt", c.getRegisteredAt() != null ? c.getRegisteredAt().toString() : null);
        m.put("hasFormImage", c.getFormImageData() != null && !c.getFormImageData().isBlank());
        m.put("alertsEnabled", c.getPickupAlertsEnabled() == null || c.getPickupAlertsEnabled());
        // Current check-in status drives the Check In / Check Out toggle button.
        m.put("checkedIn", active != null);
        m.put("status", active != null ? "Checked In" : "Checked Out");
        if (active != null) {
            m.put("activeCheckinId", active.getId());
            m.put("activeCheckinTime", active.getCheckinTime() != null ? active.getCheckinTime().toString() : null);
            m.put("activeSecurityCode", active.getSecurityCode());
        }
        // Compute age
        if (c.getDob() != null) m.put("age", Period.between(c.getDob(), LocalDate.now()).getYears());
        return m;
    }

    private Map<String, Object> pickupMap(KmAuthorizedPickup p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId()); m.put("personName", p.getPersonName());
        m.put("relationship", p.getRelationship()); m.put("phone", p.getPhone());
        return m;
    }

    private List<Map<String, Object>> enrichCheckins(List<KmCheckin> checkins, String cid) {
        // Build child & classroom lookup maps
        Map<Long, KmChild> childCache = new HashMap<>();
        Map<Long, String> classNames = new HashMap<>();
        classroomRepo.findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(cid)
                .forEach(r -> classNames.put(r.getId(), r.getClassName()));

        return checkins.stream().map(ci -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ci.getId());
            m.put("childId", ci.getChildId());
            m.put("classroomId", ci.getClassroomId());
            m.put("classroomName", ci.getClassroomId() != null ? classNames.getOrDefault(ci.getClassroomId(), "") : "");
            m.put("checkinTime", ci.getCheckinTime() != null ? ci.getCheckinTime().toString() : null);
            m.put("checkoutTime", ci.getCheckoutTime() != null ? ci.getCheckoutTime().toString() : null);
            m.put("securityCode", ci.getSecurityCode());
            m.put("familyCheckinCode", ci.getFamilyCheckinCode());
            m.put("guardianMemberId", ci.getGuardianMemberId());
            m.put("checkedOutBy", ci.getCheckedOutBy());
            m.put("checkedOutUser", ci.getCheckedOutUser());
            m.put("status", ci.getCheckoutTime() != null ? "Checked Out" : "Checked In");
            m.put("notes", ci.getNotes());

            // Two row shapes share this table:
            //   – Per-child rows from the staff Check-In tab (childId set)
            //   – Guardian rows from the public /kidsCheckin page
            //       (childId null, guardianMemberId set)
            // Resolve display info defensively so a guardian-only row doesn't
            // NPE on the null-childId lookup that used to live here.
            if (ci.getChildId() != null) {
                KmChild child = childCache.computeIfAbsent(ci.getChildId(),
                        id -> childRepo.findById(id).orElse(null));
                if (child != null) {
                    m.put("kind",        "child");
                    m.put("childName",   child.getFirstName() + " " + child.getLastName());
                    m.put("allergies",   child.getAllergies());
                    m.put("parentName",  child.getParentName());
                    m.put("parentPhone", child.getParentPhone());
                } else {
                    m.put("kind",      "child");
                    m.put("childName", "(deleted child)");
                }
            } else if (ci.getGuardianMemberId() != null) {
                FamilyMember g = familyMemberRepo.findById(ci.getGuardianMemberId()).orElse(null);
                m.put("kind", "guardian");
                if (g != null) {
                    String name = (safe(g.getFirstName()) + " " + safe(g.getLastName())).trim();
                    m.put("guardianName", name.isEmpty() ? "Guardian" : name);
                    m.put("role",  g.getRole());
                    m.put("phone", g.getPhone());
                } else {
                    m.put("guardianName", "Guardian");
                }
            } else {
                m.put("kind", "unknown");
            }
            return m;
        }).toList();
    }

    /** A letter, a dash, four digits. I and O are left out — on a label they read as 1 and 0. */
    private String generateSecurityCode() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        Random rnd = new Random();
        return String.valueOf(letters.charAt(rnd.nextInt(letters.length()))) + "-" +
               String.format("%04d", rnd.nextInt(10000));
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isBlank() ? null : s;
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    /**
     * Contact addresses (lower-cased emails, or E.164 phones) of this church's active
     * volunteers. The messaging endpoints intersect the caller's list with this so the
     * church's mail and SMS identities cannot be used as a relay to strangers.
     */
    private List<String> tenantVolunteerContacts(String cid, boolean emails) {
        if (cid == null) return List.of();
        return volunteerRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(cid).stream()
                .map(v -> emails ? v.getEmail() : com.churchgeniuspro.util.PhoneNumbers.toE164(v.getPhone()))
                .filter(x -> x != null && !x.isBlank())
                .map(x -> emails ? x.trim().toLowerCase() : x)
                .toList();
    }
}
