package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.MeetingRepository;
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

    @GetMapping("/api/admin/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard(
            @RequestParam(required = false, defaultValue = "") String clientId,
            HttpServletRequest request) {

        String appClientId = SessionUtil.getAppClientId(request);
        Map<String, Object> res = new LinkedHashMap<>();

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
        long families = familyRepo.findAllActiveForLocationByAppUser(appClientId).size();

        res.put("totalMembers", totalMembers);
        res.put("newMembers",   newMembers);
        res.put("guests",       guests);
        res.put("families",     families);

        // ── Congregation breakdown by family role ─────────────────────────
        Set<String> menRoles   = Set.of("Head", "Father", "Father-in-Law");
        Set<String> womenRoles = Set.of("Wife", "Mother", "Mother-in-Law");
        Set<String> kidsRoles  = Set.of("Son", "Daughter");

        long men   = allMembers.stream().filter(m -> menRoles.contains(m.getRole())).count();
        long women = allMembers.stream().filter(m -> womenRoles.contains(m.getRole())).count();
        long kids  = allMembers.stream().filter(m -> kidsRoles.contains(m.getRole())).count();

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
}
