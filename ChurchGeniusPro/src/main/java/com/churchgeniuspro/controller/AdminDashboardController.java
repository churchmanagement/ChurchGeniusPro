package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.service.DashboardGlanceService;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides aggregated statistics for the Admin home dashboard.
 * <p>GET /api/admin/dashboard?clientId={clientId}
 *
 * <p>ACCESS (2026-11-10, user rule). The endpoint used to answer any signed-in session. Now:
 * <ul>
 *   <li>the dashboard itself — SuperAdmin, Admin, or the church owner login ({@link RoleGuard#requireAdminOrChurch},
 *       the roles the Home page shows these sections to); everyone else gets 403;</li>
 *   <li>the STATISTICS block (Members, New Members, Guests, Families, Groups, Attendance %, Events, Visitors,
 *       Birthdays, Anniversaries, Meetings) — additionally the existing Admin → Family permission
 *       ({@code admin.family}, {@link RoleGuard#requirePagePermission}: the saved map, the role defaults when
 *       nothing was saved, the Admin main-menu rule). Without it the figures are NOT in the response at all
 *       ({@code statsAllowed:false}) and the page hides the block.</li>
 * </ul>
 * No new permission key; the church is always the session's.
 */
@RestController
public class AdminDashboardController {

    private final ChurchRegistrationRepository churchRepo;
    private final FamilyMemberRepository       memberRepo;
    private final FamilyRepository             familyRepo;
    private final MeetingRepository            meetingRepo;

    public AdminDashboardController(ChurchRegistrationRepository churchRepo,
                                    FamilyMemberRepository       memberRepo,
                                    FamilyRepository             familyRepo,
                                    MeetingRepository            meetingRepo) {
        this.churchRepo  = churchRepo;
        this.memberRepo  = memberRepo;
        this.familyRepo  = familyRepo;
        this.meetingRepo = meetingRepo;
    }

    /** Optional so hand-built tests keep the four-argument constructor; absent = no "at a glance" figures. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DashboardGlanceService glanceService;

    /** The permission behind the statistics block: Permissions screen → Admin → Family. */
    public static final String STATS_PERMISSION = "admin.family";

    /** True when this session may see the dashboard statistics: SuperAdmin / Admin / church owner AND Admin → Family. */
    public static boolean statsAllowed(HttpServletRequest request) {
        return RoleGuard.requireAdminOrChurch(request) == null
            && RoleGuard.requirePagePermission(request, STATS_PERMISSION) == null;
    }

    @GetMapping("/api/admin/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard(
            @RequestParam(required = false, defaultValue = "") String clientId,
            HttpServletRequest request) {

        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null || appClientId.isBlank())
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (RoleGuard.requireAdminOrChurch(request) != null)
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied", "code", "PERMISSION_DENIED"));
        boolean statsAllowed = statsAllowed(request);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("statsAllowed", statsAllowed);

        // ── Church name ───────────────────────────────────────────────────
        String churchName = "";
        if (!clientId.isBlank()) {
            churchName = churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                    .map(ChurchRegistration::getChurchName)
                    .orElse("");
        }
        res.put("churchName", churchName);

        // ── All active (non-deleted) family members ───────────────────────
        List<FamilyMember> allMembers = memberRepo.findAllWithFamilyByAppUser(appClientId);

        Date oneMonthAgo = Date.from(
                LocalDate.now().minusMonths(1)
                         .atStartOfDay(ZoneId.systemDefault()).toInstant());

        // Members (memberType = 'Member', case-insensitive)
        long totalMembers = allMembers.stream()
                .filter(m -> "member".equalsIgnoreCase(m.getMemberType()))
                .count();

        // New members added in the last 30 days
        long newMembers = allMembers.stream()
                .filter(m -> "member".equalsIgnoreCase(m.getMemberType()))
                .filter(m -> m.getCreatedDate() != null && m.getCreatedDate().after(oneMonthAgo))
                .count();

        // Guests (memberType = 'Guest')
        long guests = allMembers.stream()
                .filter(m -> "guest".equalsIgnoreCase(m.getMemberType()))
                .count();

        // Families (all active)
        List<com.churchgeniuspro.hibernate.Family> activeFamilies = familyRepo.findAllActiveForLocationByAppUser(appClientId);
        long families = activeFamilies.size();

        if (statsAllowed) {
            res.put("totalMembers", totalMembers);
            res.put("newMembers",   newMembers);
            res.put("guests",       guests);
            res.put("families",     families);
            // Groups, Attendance %, Events, Visitors, Birthdays / Anniversaries / Meetings (month · week · today)
            if (glanceService != null) res.put("glance", glanceService.glance(appClientId, allMembers, AppClock.today()));
            // 2026-11-16 — the figures beside the Church Growth chart (same permission as the statistics).
            res.put("growth", growthFigures(allMembers, activeFamilies, LocalDate.now().getYear()));
        }

        // ── Congregation breakdown by family role ─────────────────────────
        Set<String> menRoles   = Set.of("Head", "Father", "Father-in-Law");
        Set<String> womenRoles = Set.of("Wife", "Mother", "Mother-in-Law");
        Set<String> kidsRoles  = Set.of("Son", "Daughter");

        long men   = allMembers.stream().filter(m -> m.getRole() != null && menRoles.contains(m.getRole())).count();
        long women = allMembers.stream().filter(m -> m.getRole() != null && womenRoles.contains(m.getRole())).count();
        long kids  = allMembers.stream().filter(m -> m.getRole() != null && kidsRoles.contains(m.getRole())).count();

        res.put("men",   men);
        res.put("women", women);
        res.put("kids",  kids);

        // ── Member Distribution: Male / Female / Adults / Children ────────
        long maleCount   = allMembers.stream()
                .filter(m -> "male".equalsIgnoreCase(m.getGender()))
                .count();
        long femaleCount = allMembers.stream()
                .filter(m -> "female".equalsIgnoreCase(m.getGender()))
                .count();
        long childCount  = allMembers.stream()
                .filter(m -> "Child".equalsIgnoreCase(m.getRole()))
                .count();
        long adultCount  = allMembers.stream()
                .filter(m -> !"Child".equalsIgnoreCase(m.getRole()))
                .count();

        res.put("maleCount",   maleCount);
        res.put("femaleCount", femaleCount);
        res.put("adultCount",  adultCount);
        res.put("childCount",  childCount);

        // ── Members added per month (current calendar year) ───────────────
        int    currentYear = LocalDate.now().getYear();
        long[] byMonth     = new long[12];
        allMembers.forEach(m -> {
            if (m.getCreatedDate() != null) {
                LocalDate d = m.getCreatedDate().toInstant()
                               .atZone(ZoneId.systemDefault()).toLocalDate();
                if (d.getYear() == currentYear) {
                    byMonth[d.getMonthValue() - 1]++;
                }
            }
        });
        res.put("membersByMonth", byMonth);

        // ── Upcoming meetings (today onwards, max 10) ─────────────────────
        List<Map<String, Object>> meetingList =
                meetingRepo.findUpcomingFromDateByAppUser(LocalDate.now(), appClientId)
                        .stream()
                        .limit(10)
                        .map(m -> {
                            Map<String, Object> ev = new LinkedHashMap<>();
                            ev.put("date",    m.getMeetingDate().toString());
                            ev.put("title",   m.getMeetingType() != null
                                              ? m.getMeetingType().getTypeName() : "Meeting");
                            ev.put("time",    m.getStartTime() != null ? m.getStartTime() : "");
                            ev.put("endTime", m.getEndTime()   != null ? m.getEndTime()   : "");
                            ev.put("city",    m.getCity()      != null ? m.getCity()      : "");
                            ev.put("note",    m.getNote()      != null ? m.getNote()      : "");
                            return ev;
                        })
                        .collect(Collectors.toList());
        res.put("upcomingMeetings", meetingList);

        return ResponseEntity.ok(res);
    }

    /**
     * Members and Families figures shown beside the Church Growth chart.
     * Members = people whose type is Member (active, not deleted): total, added in {@code year}, male, female,
     * adults and children (role Child). Families = active families: total, added in {@code year}, and those whose
     * primary member is a Guest / a Visitor (the Families page rule for a family's type).
     */
    public static Map<String, Object> growthFigures(List<FamilyMember> people, List<com.churchgeniuspro.hibernate.Family> families, int year) {
        long total = 0, added = 0, male = 0, female = 0, adults = 0, children = 0;
        for (FamilyMember m : people) {
            if (!"member".equalsIgnoreCase(m.getMemberType())) continue;
            total++;
            if (inYear(m.getCreatedDate(), year)) added++;
            if ("male".equalsIgnoreCase(m.getGender())) male++;
            else if ("female".equalsIgnoreCase(m.getGender())) female++;
            if ("Child".equalsIgnoreCase(com.churchgeniuspro.util.FamilyRoles.canonical(m.getRole()))) children++;
            else adults++;
        }
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("total", total);
        members.put("added", added);
        members.put("male", male);
        members.put("female", female);
        members.put("adults", adults);
        members.put("children", children);

        long famAdded = 0, guests = 0, visitors = 0;
        for (com.churchgeniuspro.hibernate.Family f : families) {
            if (inYear(f.getCreatedDate(), year)) famAdded++;
            FamilyMember primary = null;
            if (f.getMembers() != null) {
                for (FamilyMember m : f.getMembers()) {
                    if (m == null || m.isDeleteFlag()) continue;
                    String role = com.churchgeniuspro.util.FamilyRoles.canonical(m.getRole());
                    boolean head = com.churchgeniuspro.util.FamilyRoles.HEAD.equals(role);
                    if (head) { primary = m; break; }
                    if (primary == null || (m.getId() != null && primary.getId() != null && m.getId() < primary.getId())) primary = m;
                }
            }
            String type = primary == null || primary.getMemberType() == null ? "" : primary.getMemberType().trim();
            if ("guest".equalsIgnoreCase(type)) guests++;
            else if ("visitor".equalsIgnoreCase(type)) visitors++;
        }
        Map<String, Object> fam = new LinkedHashMap<>();
        fam.put("total", (long) families.size());
        fam.put("added", famAdded);
        fam.put("guests", guests);
        fam.put("visitors", visitors);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("members", members);
        out.put("families", fam);
        return out;
    }

    private static boolean inYear(Date d, int year) {
        if (d == null) return false;
        return new java.util.Date(d.getTime()).toInstant().atZone(ZoneId.systemDefault()).getYear() == year;
    }
}
