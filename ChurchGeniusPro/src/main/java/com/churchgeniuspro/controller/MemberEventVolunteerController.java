package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventVolunteer;
import com.churchgeniuspro.hibernate.EventVolunteerRole;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EventVolunteerRepository;
import com.churchgeniuspro.repository.EventVolunteerRoleRepository;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Member-facing REST endpoints for viewing event volunteer assignments.
 *
 * <p>These endpoints are called from memberHome.html (Volunteer tab) and
 * require an active member session (memberId stored in HttpSession).
 *
 * <ul>
 *   <li>{@code GET /api/members/my/event-volunteers}
 *       — list all event volunteer assignments for the logged-in member</li>
 * </ul>
 */
@RestController
public class MemberEventVolunteerController {

    private final EventVolunteerRepository     evRepo;
    private final EventVolunteerRoleRepository evRoleRepo;
    private final ChurchEventRepository        churchEventRepo;

    public MemberEventVolunteerController(EventVolunteerRepository evRepo,
                                          EventVolunteerRoleRepository evRoleRepo,
                                          ChurchEventRepository churchEventRepo) {
        this.evRepo         = evRepo;
        this.evRoleRepo     = evRoleRepo;
        this.churchEventRepo = churchEventRepo;
    }

    /**
     * Returns all event volunteer assignments for the logged-in member,
     * enriched with event name, date, and roles.
     */
    @GetMapping("/api/members/my/event-volunteers")
    public ResponseEntity<?> myEventVolunteers(HttpServletRequest req) {
        Integer memberId = getMemberId(req);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String cid = SessionUtil.getAppClientId(req);

        List<EventVolunteer> myVols = evRepo
                .findByAppClientIdAndFamilyMemberIdAndDeleteFlagFalseOrderByCreatedAtDesc(cid, memberId);

        if (myVols.isEmpty()) return ResponseEntity.ok(List.of());

        List<Long> volIds = myVols.stream().map(EventVolunteer::getId).toList();
        Map<Long, List<EventVolunteerRole>> rolesMap =
                evRoleRepo.findByAppClientIdAndEventVolunteerIdInAndDeleteFlagFalseOrderByIdAsc(cid, volIds)
                          .stream().collect(Collectors.groupingBy(EventVolunteerRole::getEventVolunteerId));

        // Batch-load distinct events
        Set<Integer> eventIds = myVols.stream().map(EventVolunteer::getEventId).collect(Collectors.toSet());
        Map<Integer, ChurchEvent> eventMap = new HashMap<>();
        for (Integer eid : eventIds) {
            churchEventRepo.findByIdAndDeleteFlagFalse(eid).ifPresent(e -> eventMap.put(eid, e));
        }

        List<Map<String, Object>> result = myVols.stream().map(ev -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",             ev.getId());
            m.put("eventId",        ev.getEventId());
            m.put("familyMemberId", ev.getFamilyMemberId());
            m.put("firstName",      ev.getFirstName() != null ? ev.getFirstName() : "");
            m.put("lastName",       ev.getLastName()  != null ? ev.getLastName()  : "");
            m.put("status",         ev.getStatus()    != null ? ev.getStatus()    : "pending");
            m.put("notes",          ev.getNotes()     != null ? ev.getNotes()     : "");
            m.put("roles", rolesMap.getOrDefault(ev.getId(), List.of()).stream().map(r -> {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id",       r.getId());
                rm.put("roleName", r.getRoleName());
                return rm;
            }).toList());

            // Enrich with event details
            ChurchEvent ce = eventMap.get(ev.getEventId());
            if (ce != null) {
                m.put("eventName", ce.getEventName() != null ? ce.getEventName() : "");
                m.put("eventDate", ce.getEventDate() != null ? ce.getEventDate().toString() : null);
                m.put("startTime", ce.getStartTime());
                m.put("endTime",   ce.getEndTime());
                m.put("eventType", ce.getEventType());
                m.put("address1",  ce.getAddress1());
                m.put("city",      ce.getCity());
                m.put("state",     ce.getState());
            } else {
                m.put("eventName", "");
                m.put("eventDate", null);
            }
            return m;
        }).toList();

        return ResponseEntity.ok(result);
    }

    private Integer getMemberId(HttpServletRequest req) {
        Object val = req.getSession(false) != null
                ? req.getSession(false).getAttribute("memberId") : null;
        if (val == null) return null;
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return null; }
    }
}
