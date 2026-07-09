package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.util.EncryptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Public-visitor engagement from the NTAG landing page / public website:
 * <ul>
 *   <li><b>Connect With Us</b> → creates a Visitor ({@link FamilyMember}) and an auto
 *       {@link FollowUp} linked to it.</li>
 *   <li><b>Prayer Request</b> → creates a {@link PublicPrayerRequest} and an auto
 *       {@link FollowUp}, manageable separately from internal prayer requests with
 *       volunteer assignment, status, and an activity timeline.</li>
 * </ul>
 */
@Service
public class PublicEngagementService {

    private static final Logger LOG = LoggerFactory.getLogger(PublicEngagementService.class);

    private final FamilyMemberRepository memberRepo;
    private final FamilyRepository familyRepo;
    private final FollowUpRepository followUpRepo;
    private final PublicPrayerRequestRepository prayerRepo;
    private final PublicPrayerNoteRepository noteRepo;
    private final PrayerVolunteerRepository volunteerRepo;
    private final ChurchRegistrationRepository churchRepo;
    private final ChurchLogoRepository logoRepo;

    public PublicEngagementService(FamilyMemberRepository memberRepo,
                                   FamilyRepository familyRepo,
                                   FollowUpRepository followUpRepo,
                                   PublicPrayerRequestRepository prayerRepo,
                                   PublicPrayerNoteRepository noteRepo,
                                   PrayerVolunteerRepository volunteerRepo,
                                   ChurchRegistrationRepository churchRepo,
                                   ChurchLogoRepository logoRepo) {
        this.memberRepo = memberRepo;
        this.familyRepo = familyRepo;
        this.followUpRepo = followUpRepo;
        this.prayerRepo = prayerRepo;
        this.noteRepo = noteRepo;
        this.volunteerRepo = volunteerRepo;
        this.churchRepo = churchRepo;
        this.logoRepo = logoRepo;
    }

    // ── Branding for the public pages ───────────────────────────────────────────

    public Map<String, Object> churchInfo(String cid) {
        String clientId = decrypt(cid);
        Map<String, Object> m = new LinkedHashMap<>();
        if (clientId == null) return m;
        ChurchRegistration cr = churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);
        m.put("churchName", cr != null ? cr.getChurchName() : "Church");
        m.put("websiteUrl",   cr != null ? cr.getWebsiteUrl()   : null);
        m.put("facebookUrl",  cr != null ? cr.getFacebookUrl()  : null);
        m.put("instagramUrl", cr != null ? cr.getInstagramUrl() : null);
        m.put("youtubeUrl",   cr != null ? cr.getYoutubeUrl()   : null);
        m.put("logo", logoDataUrl(clientId));
        return m;
    }

    // ── Connect With Us → Visitor + Follow-Up ───────────────────────────────────

    @Transactional
    public void connect(String cid, Map<String, Object> b) {
        String clientId = decrypt(cid);
        if (clientId == null) throw new IllegalArgumentException("Invalid link.");
        String first = str(b, "firstName"), last = str(b, "lastName");
        if (blank(first) && blank(last)) throw new IllegalArgumentException("Please enter your name.");

        // A FamilyMember requires a parent Family (family_id is NOT NULL), so create a
        // single-member family to hold the visitor.
        Family family = new Family();
        family.setAppClientId(clientId);
        family.setInactive(false);
        family.setDeleteFlag(false);
        family = familyRepo.save(family);

        FamilyMember v = new FamilyMember();
        v.setFamily(family);
        v.setAppClientId(clientId);
        v.setRole("Head");
        v.setMemberType("Visitor");
        v.setFirstName(first);
        v.setLastName(last);
        v.setEmail(str(b, "email"));
        v.setPhone(str(b, "phone"));
        v.setGender(str(b, "gender"));
        v.setAddress1(str(b, "address"));
        applyBirthDate(v, str(b, "birthDate"));
        FamilyMember saved = memberRepo.save(v);

        String name = (nz(first) + " " + nz(last)).trim();
        StringBuilder d = new StringBuilder("New visitor via Connect With Us.\n");
        if (!blank(v.getEmail())) d.append("Email: ").append(v.getEmail()).append('\n');
        if (!blank(v.getPhone())) d.append("Phone: ").append(v.getPhone()).append('\n');
        String prefs = contactPrefs(b);
        if (!prefs.isEmpty())            d.append("Preferred contact: ").append(prefs).append('\n');
        if (!blank(str(b, "maritalStatus"))) d.append("Marital status: ").append(str(b, "maritalStatus")).append('\n');
        if (!blank(str(b, "address")))   d.append("Address: ").append(str(b, "address")).append('\n');
        if (!blank(str(b, "howHeard")))  d.append("How they heard about us: ").append(str(b, "howHeard")).append('\n');

        createFollowUp(clientId, "Welcome new visitor: " + (name.isEmpty() ? "(no name)" : name),
                d.toString(), "VISITOR", (long) saved.getId(), "Visitor: " + name, "public");
    }

    // ── Prayer Request → PublicPrayerRequest + Follow-Up ────────────────────────

    @Transactional
    public Long prayer(String cid, Map<String, Object> b, String source) {
        String clientId = decrypt(cid);
        if (clientId == null) throw new IllegalArgumentException("Invalid link.");
        String first = str(b, "firstName"), last = str(b, "lastName"), email = str(b, "email");
        if (blank(first) || blank(last)) throw new IllegalArgumentException("First and last name are required.");
        if (blank(email)) throw new IllegalArgumentException("Email is required.");
        if (blank(str(b, "requestText"))) throw new IllegalArgumentException("Please enter your prayer request.");

        PublicPrayerRequest p = new PublicPrayerRequest();
        p.setClientId(clientId);
        p.setFirstName(first);
        p.setLastName(last);
        p.setEmail(email);
        p.setPhone(str(b, "phone"));
        p.setRequestText(str(b, "requestText"));
        p.setShareWithTeam(boolVal(b.get("shareWithTeam")));
        p.setSource(source == null ? "PUBLIC" : source);
        p.setStatus("New");
        PublicPrayerRequest saved = prayerRepo.save(p);

        String name = (nz(first) + " " + nz(last)).trim();
        String desc = "Public prayer request from " + name + ".\n"
                + (blank(p.getPhone()) ? "" : ("Phone: " + p.getPhone() + "\n"))
                + "Email: " + email + "\n"
                + "Share with prayer team: " + (p.isShareWithTeam() ? "Yes" : "No") + "\n\n"
                + nz(p.getRequestText());
        Long fuId = createFollowUp(clientId, "Prayer follow-up: " + name, desc,
                "PRAYER", saved.getId(), "Prayer: " + name, "public");
        saved.setFollowUpId(fuId);
        prayerRepo.save(saved);
        return saved.getId();
    }

    // ── Admin: manage public prayer requests ────────────────────────────────────

    public List<Map<String, Object>> listPrayer(String clientId, String source) {
        List<PublicPrayerRequest> rows = (source == null || source.isBlank() || "ALL".equalsIgnoreCase(source))
                ? prayerRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(clientId)
                : prayerRepo.findByClientIdAndSourceAndDeleteFlagFalseOrderByCreatedAtDesc(clientId, source);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PublicPrayerRequest p : rows) out.add(prayerMap(p));
        return out;
    }

    public Map<String, Object> prayerMap(PublicPrayerRequest p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("firstName", p.getFirstName());
        m.put("lastName", p.getLastName());
        m.put("email", p.getEmail());
        m.put("phone", p.getPhone());
        m.put("requestText", p.getRequestText());
        m.put("shareWithTeam", p.isShareWithTeam());
        m.put("source", p.getSource());
        m.put("status", p.getStatus());
        m.put("assignedVolunteerId", p.getAssignedVolunteerId());
        m.put("assignedTo", p.getAssignedTo());
        m.put("followUpId", p.getFollowUpId());
        m.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        return m;
    }

    @Transactional
    public void assign(String clientId, Long id, Integer volunteerId, String actor) {
        PublicPrayerRequest p = mustFind(clientId, id);
        if (volunteerId == null) { p.setAssignedVolunteerId(null); p.setAssignedTo(null); }
        else {
            PrayerVolunteer v = volunteerRepo.findByIdAndClientId(volunteerId, clientId).orElse(null);
            p.setAssignedVolunteerId(volunteerId);
            p.setAssignedTo(v == null ? null : (nz(v.getFirstName()) + " " + nz(v.getLastName())).trim());
            if ("New".equals(p.getStatus())) p.setStatus("Assigned");
        }
        prayerRepo.save(p);
        addNoteInternal(clientId, id, "NOTE",
                volunteerId == null ? "Unassigned." : ("Assigned to " + nz(p.getAssignedTo()) + "."), actor);
    }

    @Transactional
    public void setStatus(String clientId, Long id, String status, String actor) {
        PublicPrayerRequest p = mustFind(clientId, id);
        p.setStatus(status);
        prayerRepo.save(p);
        addNoteInternal(clientId, id, "NOTE", "Status changed to " + status + ".", actor);
    }

    @Transactional
    public void addNote(String clientId, Long id, String type, String text, String author) {
        mustFind(clientId, id);
        addNoteInternal(clientId, id, type, text, author);
    }

    private void addNoteInternal(String clientId, Long id, String type, String text, String author) {
        PublicPrayerNote n = new PublicPrayerNote();
        n.setClientId(clientId);
        n.setPublicPrayerId(id);
        n.setNoteType(type == null || type.isBlank() ? "NOTE" : type);
        n.setNoteText(text);
        n.setAuthor(author);
        noteRepo.save(n);
    }

    public List<Map<String, Object>> notes(String clientId, Long id) {
        mustFind(clientId, id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PublicPrayerNote n : noteRepo.findByPublicPrayerIdOrderByCreatedAtDesc(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", n.getNoteType());
            m.put("text", n.getNoteText());
            m.put("author", n.getAuthor());
            m.put("time", n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
            out.add(m);
        }
        return out;
    }

    @Transactional
    public void deletePrayer(String clientId, Long id) {
        PublicPrayerRequest p = mustFind(clientId, id);
        p.setDeleteFlag(true);
        prayerRepo.save(p);
    }

    public List<Map<String, Object>> volunteers(String clientId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrayerVolunteer v : volunteerRepo.findByClient(clientId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("name", (nz(v.getFirstName()) + " " + nz(v.getLastName())).trim());
            m.put("role", v.getRole());
            out.add(m);
        }
        return out;
    }

    // ── internals ──

    private PublicPrayerRequest mustFind(String clientId, Long id) {
        return prayerRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Prayer request not found."));
    }

    private Long createFollowUp(String clientId, String title, String desc, String linkedType, Long linkedId,
                                String linkedLabel, String createdBy) {
        try {
            FollowUp f = new FollowUp();
            f.setClientId(clientId);
            f.setTitle(title.length() > 255 ? title.substring(0, 255) : title);
            f.setDescription(desc);
            f.setStatus("PENDING");
            f.setPriority("MEDIUM");
            f.setLinkedType(linkedType);
            f.setLinkedId(linkedId);
            f.setLinkedLabel(linkedLabel != null && linkedLabel.length() > 255 ? linkedLabel.substring(0, 255) : linkedLabel);
            f.setCreatedBy(createdBy);
            f.setDeleteFlag(false);
            f.setCreatedAt(new Date());
            f.setUpdatedAt(new Date());
            return followUpRepo.save(f).getId();
        } catch (Exception e) {
            LOG.warn("[PublicEngagement] follow-up create failed: {}", e.toString());
            return null;
        }
    }

    private void applyBirthDate(FamilyMember v, String iso) {
        if (blank(iso)) return;
        try {
            String[] p = iso.split("-");
            if (p.length == 3) {
                v.setBirthdayYear(Integer.parseInt(p[0]));
                v.setBirthdayMonth(Integer.parseInt(p[1]));
                v.setBirthdayDay(Integer.parseInt(p[2]));
            }
        } catch (Exception ignored) {}
    }

    private static String contactPrefs(Map<String, Object> b) {
        Object raw = b.get("contactPreferences");
        List<String> prefs = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object o : list) { if (o != null && !o.toString().isBlank()) prefs.add(o.toString()); }
        return String.join(", ", prefs);
    }

    private String logoDataUrl(String clientId) {
        ChurchLogo logo = logoRepo.findByClientId(clientId).orElse(null);
        if (logo == null || logo.getLogoData() == null || logo.getLogoData().length == 0) return null;
        String ct = logo.getContentType() != null ? logo.getContentType() : "image/png";
        return "data:" + ct + ";base64," + Base64.getEncoder().encodeToString(logo.getLogoData());
    }

    private static String decrypt(String cid) { if (cid == null || cid.isBlank()) return null; try { return EncryptionUtil.decrypt(cid.trim()); } catch (Exception e) { return null; } }
    private static String str(Map<String, Object> b, String k) { Object v = b.get(k); return v == null ? null : v.toString().trim(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static boolean boolVal(Object o) { if (o == null) return false; return (o instanceof Boolean b) ? b : Boolean.parseBoolean(o.toString()); }
}
