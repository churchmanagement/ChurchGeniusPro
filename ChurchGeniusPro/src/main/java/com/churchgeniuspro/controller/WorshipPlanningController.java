package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for the Worship Planning feature.
 */
@RestController
@RequestMapping("/api/worship")
public class WorshipPlanningController {

    private final WorshipGroupRepository             groupRepo;
    private final WorshipInstrumentRepository        instrumentRepo;
    private final WorshipGroupMemberRepository       groupMemberRepo;
    private final WorshipAssignmentRepository        assignmentRepo;
    private final WorshipAssignmentMemberRepository  assignmentMemberRepo;
    private final WorshipSongRepository              songRepo;

    public WorshipPlanningController(
            WorshipGroupRepository groupRepo,
            WorshipInstrumentRepository instrumentRepo,
            WorshipGroupMemberRepository groupMemberRepo,
            WorshipAssignmentRepository assignmentRepo,
            WorshipAssignmentMemberRepository assignmentMemberRepo,
            WorshipSongRepository songRepo) {
        this.groupRepo            = groupRepo;
        this.instrumentRepo       = instrumentRepo;
        this.groupMemberRepo      = groupMemberRepo;
        this.assignmentRepo       = assignmentRepo;
        this.assignmentMemberRepo = assignmentMemberRepo;
        this.songRepo             = songRepo;
    }

    // ======================================================================
    // GROUPS
    // ======================================================================

    @GetMapping("/groups")
    public ResponseEntity<?> getGroups(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        List<WorshipGroup> groups = groupRepo.findByClientIdAndDeleteFlagFalse(clientId);
        return ResponseEntity.ok(buildGroupTrees(groups));
    }

    @PostMapping("/groups")
    public ResponseEntity<?> createGroup(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        String name = str(body.get("groupName"));
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Group name is required."));
        WorshipGroup g = new WorshipGroup();
        g.setClientId(clientId);
        g.setGroupName(name);
        groupRepo.save(g);
        return ResponseEntity.ok(buildGroupTree(g));
    }

    @PutMapping("/groups/{id}")
    public ResponseEntity<?> updateGroup(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Optional<WorshipGroup> opt = groupRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getClientId().equals(clientId))
            return ResponseEntity.notFound().build();
        String name = str(body.get("groupName"));
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Group name is required."));
        WorshipGroup g = opt.get();
        g.setGroupName(name);
        groupRepo.save(g);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/groups/{id}")
    public ResponseEntity<?> deleteGroup(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Optional<WorshipGroup> opt = groupRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getClientId().equals(clientId))
            return ResponseEntity.notFound().build();
        WorshipGroup g = opt.get();
        g.setDeleteFlag(true);
        groupRepo.save(g);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ======================================================================
    // INSTRUMENTS
    // ======================================================================

    @PostMapping("/instruments")
    public ResponseEntity<?> createInstrument(@RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Long groupId = longVal(body.get("groupId"));
        String name  = str(body.get("instrumentName"));
        if (groupId == null) return ResponseEntity.badRequest().body(Map.of("error", "groupId required."));
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Instrument name required."));
        Optional<WorshipGroup> grp = groupRepo.findById(groupId);
        if (grp.isEmpty() || !grp.get().getClientId().equals(clientId))
            return ResponseEntity.status(403).build();
        WorshipInstrument inst = new WorshipInstrument();
        inst.setClientId(clientId);
        inst.setGroupId(groupId);
        inst.setInstrumentName(name);
        instrumentRepo.save(inst);
        return ResponseEntity.ok(Map.of("id", inst.getId(), "instrumentName", inst.getInstrumentName(), "groupId", groupId));
    }

    @PutMapping("/instruments/{id}")
    public ResponseEntity<?> updateInstrument(@PathVariable Long id,
                                               @RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Optional<WorshipInstrument> opt = instrumentForTenant(id, clientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        String name = str(body.get("instrumentName"));
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Instrument name required."));
        WorshipInstrument inst = opt.get();
        inst.setInstrumentName(name);
        instrumentRepo.save(inst);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/instruments/{id}")
    public ResponseEntity<?> deleteInstrument(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Optional<WorshipInstrument> opt = instrumentForTenant(id, clientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        WorshipInstrument inst = opt.get();
        inst.setDeleteFlag(true);
        instrumentRepo.save(inst);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ======================================================================
    // GROUP MEMBERS
    // ======================================================================

    @PostMapping("/members")
    public ResponseEntity<?> createMember(@RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Long   instrumentId   = longVal(body.get("instrumentId"));
        String memberName     = str(body.get("memberName"));
        Integer rotationOrder = intVal(body.get("rotationOrder"));
        if (instrumentId == null) return ResponseEntity.badRequest().body(Map.of("error", "instrumentId required."));
        if (memberName == null || memberName.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Member name required."));
        if (instrumentForTenant(instrumentId, clientId).isEmpty())
            return ResponseEntity.notFound().build();
        WorshipGroupMember m = new WorshipGroupMember();
        m.setClientId(clientId);
        m.setInstrumentId(instrumentId);
        m.setMemberName(memberName);
        m.setRotationOrder(rotationOrder);
        groupMemberRepo.save(m);
        return ResponseEntity.ok(memberMap(m));
    }

    @PutMapping("/members/{id}")
    public ResponseEntity<?> updateMember(@PathVariable Long id,
                                          @RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Optional<WorshipGroupMember> opt = groupMemberForTenant(id, clientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        WorshipGroupMember m = opt.get();
        if (body.containsKey("memberName")) m.setMemberName(str(body.get("memberName")));
        if (body.containsKey("rotationOrder")) m.setRotationOrder(intVal(body.get("rotationOrder")));
        groupMemberRepo.save(m);
        return ResponseEntity.ok(memberMap(m));
    }

    @DeleteMapping("/members/{id}")
    public ResponseEntity<?> deleteMember(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Optional<WorshipGroupMember> opt = groupMemberForTenant(id, clientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        WorshipGroupMember m = opt.get();
        m.setDeleteFlag(true);
        groupMemberRepo.save(m);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ======================================================================
    // ASSIGNMENTS
    // ======================================================================

    @GetMapping("/assignments")
    public ResponseEntity<?> getAssignments(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        List<WorshipAssignment> assignments;
        if (from != null && to != null) {
            assignments = assignmentRepo.findByClientIdAndAssignmentDateBetweenAndDeleteFlagFalse(
                    clientId, LocalDate.parse(from), LocalDate.parse(to));
        } else {
            // Apply a default floor of today - 7 days when no date range is supplied,
            // to avoid loading the entire historical dataset.
            LocalDate floor = LocalDate.now().minusDays(7);
            assignments = assignmentRepo.findByClientIdAndAssignmentDateBetweenAndDeleteFlagFalse(
                    clientId, floor, floor.plusYears(1));
        }
        return ResponseEntity.ok(buildAssignmentDetails(assignments));
    }

    @PostMapping("/assignments")
    @Transactional
    public ResponseEntity<?> saveAssignment(@RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Long      groupId  = longVal(body.get("groupId"));
        String    dateStr  = str(body.get("assignmentDate"));
        String    type     = str(body.get("assignmentType"));
        if (groupId == null || dateStr == null)
            return ResponseEntity.badRequest().body(Map.of("error", "groupId and assignmentDate required."));
        if (groupRepo.findByIdAndClientId(groupId, clientId).isEmpty())
            return ResponseEntity.notFound().build();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) body.get("members");
        // Validate every instrument before anything is written (the method is transactional
        // but returns, not throws, on a bad id).
        if (members != null) {
            for (Map<String, Object> mMap : members) {
                Long instId = longVal(mMap.get("instrumentId"));
                if (instId != null && instrumentForTenant(instId, clientId).isEmpty())
                    return ResponseEntity.notFound().build();
            }
        }
        LocalDate date = LocalDate.parse(dateStr);
        WorshipAssignment assignment = assignmentRepo
                .findFirstByClientIdAndGroupIdAndAssignmentDateAndDeleteFlagFalse(clientId, groupId, date)
                .orElse(new WorshipAssignment());
        assignment.setClientId(clientId);
        assignment.setGroupId(groupId);
        assignment.setAssignmentDate(date);
        assignment.setAssignmentType(type != null ? type : "manual");
        assignment.setDeleteFlag(false);
        assignmentRepo.save(assignment);
        assignmentMemberRepo.deleteByAssignmentId(assignment.getId());
        if (members != null) {
            for (Map<String, Object> mMap : members) {
                String mName = str(mMap.get("memberName"));
                if (mName == null || mName.isBlank()) continue; // skip empty/null entries
                WorshipAssignmentMember am = new WorshipAssignmentMember();
                am.setClientId(clientId);
                am.setAssignmentId(assignment.getId());
                am.setInstrumentId(longVal(mMap.get("instrumentId")));
                am.setMemberName(mName);
                am.setSortOrder(intVal(mMap.get("sortOrder")));
                assignmentMemberRepo.save(am);
            }
        }
        return ResponseEntity.ok(buildAssignmentDetail(assignment));
    }

    @DeleteMapping("/assignments/{id}")
    public ResponseEntity<?> deleteAssignment(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Optional<WorshipAssignment> opt = assignmentRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getClientId().equals(clientId))
            return ResponseEntity.notFound().build();
        WorshipAssignment a = opt.get();
        a.setDeleteFlag(true);
        assignmentRepo.save(a);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ======================================================================
    // AUTO-ASSIGN
    // ======================================================================

    @PostMapping("/auto-assign")
    public ResponseEntity<?> autoAssign(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Long    groupId  = longVal(body.get("groupId"));
        String  startStr = str(body.get("startDate"));
        Integer weeks    = intVal(body.get("weeks"));
        Integer dowRaw   = intVal(body.get("dayOfWeek"));
        // Optional 1-based starting rotation order (e.g. 2 → begin at Order 2, then 3, 1, 2 …).
        // Defaults to 1 (begin at the lowest order) to preserve the previous behavior.
        Integer startOrderRaw = intVal(body.get("startOrder"));
        int startOrder = (startOrderRaw == null || startOrderRaw < 1) ? 1 : startOrderRaw;
        if (groupId == null || startStr == null || weeks == null || dowRaw == null)
            return ResponseEntity.badRequest().body(Map.of("error", "groupId, startDate, weeks, dayOfWeek required."));
        Optional<WorshipGroup> grp = groupRepo.findById(groupId);
        if (grp.isEmpty() || !grp.get().getClientId().equals(clientId))
            return ResponseEntity.status(403).build();
        List<WorshipInstrument> instruments = instrumentRepo.findByGroupIdAndDeleteFlagFalse(groupId);
        // Only members WITH a rotationOrder participate in auto-assignment.
        // Members whose rotationOrder is null are excluded (available for manual assignment only).
        // Build per-instrument rotation groups: group members by rotationOrder value.
        // Each group (List<WorshipGroupMember>) represents one "rotation level" —
        // all members in a group are assigned together on the same date.
        // Groups are ordered by rotationOrder ascending (1, 2, 3 …).
        Map<Long, List<List<WorshipGroupMember>>> instGroupsMap = new LinkedHashMap<>();
        for (WorshipInstrument inst : instruments) {
            List<WorshipGroupMember> rotating =
                    groupMemberRepo.findByInstrumentIdAndDeleteFlagFalseOrderByRotationOrderAsc(inst.getId())
                            .stream()
                            .filter(m -> m.getRotationOrder() != null)
                            .sorted(Comparator.comparingInt(WorshipGroupMember::getRotationOrder))
                            .collect(java.util.stream.Collectors.toList());
            // Group by rotationOrder value using a TreeMap to preserve ascending order
            java.util.TreeMap<Integer, List<WorshipGroupMember>> byOrder = new java.util.TreeMap<>();
            for (WorshipGroupMember m : rotating) {
                byOrder.computeIfAbsent(m.getRotationOrder(), k -> new ArrayList<>()).add(m);
            }
            instGroupsMap.put(inst.getId(), new ArrayList<>(byOrder.values()));
        }
        // Pointer per instrument: index into the list of rotation groups (not individuals).
        // Start at (startOrder - 1) so generation begins at the selected order and then
        // continues circularly (the `ptr % groups.size()` in the loop wraps around).
        // Each instrument is offset relative to ITS own number of orders, so groups with
        // fewer orders still wrap correctly.
        Map<Long, Integer> pointers = new HashMap<>();
        for (Long instId : instGroupsMap.keySet()) {
            int size = instGroupsMap.get(instId).size();
            pointers.put(instId, size > 0 ? ((startOrder - 1) % size) : 0);
        }
        LocalDate start  = LocalDate.parse(startStr);
        DayOfWeek target = DayOfWeek.of(dowRaw);
        LocalDate cur    = start;
        while (cur.getDayOfWeek() != target) cur = cur.plusDays(1);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int w = 0; w < weeks; w++) {
            List<Map<String, Object>> slots = new ArrayList<>();
            for (WorshipInstrument inst : instruments) {
                List<List<WorshipGroupMember>> groups = instGroupsMap.get(inst.getId());
                if (groups == null || groups.isEmpty()) continue;
                int ptr = pointers.getOrDefault(inst.getId(), 0);
                // Pick the current rotation group (all members at this rotation level)
                List<WorshipGroupMember> currentGroup = groups.get(ptr % groups.size());
                List<Map<String, Object>> memberRows = new ArrayList<>();
                for (int i = 0; i < currentGroup.size(); i++) {
                    WorshipGroupMember m = currentGroup.get(i);
                    Map<String, Object> mRow = new LinkedHashMap<>();
                    mRow.put("memberName",    m.getMemberName());
                    mRow.put("rotationOrder", m.getRotationOrder());
                    mRow.put("sortOrder",     i);
                    memberRows.add(mRow);
                }
                // Advance by one group (not one member) for the next week
                pointers.put(inst.getId(), ptr + 1);
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("instrumentId",   inst.getId());
                slot.put("instrumentName", inst.getInstrumentName());
                slot.put("members",        memberRows);
                slots.add(slot);
            }
            Map<String, Object> asgn = new LinkedHashMap<>();
            asgn.put("groupId",        groupId);
            asgn.put("groupName",      grp.get().getGroupName());
            asgn.put("assignmentDate", cur.toString());
            asgn.put("assignmentType", "auto");
            asgn.put("slots",          slots);
            result.add(asgn);
            cur = cur.plusWeeks(1);
        }
        return ResponseEntity.ok(result);
    }

    // ======================================================================
    // SONGS
    // ======================================================================

    /**
     * Batch songs endpoint for memberHome worship schedule.
     * Returns: { "groupId|date": [ songTitle, ... ], ... }
     * One request per page load instead of N×M individual requests.
     */
    @GetMapping("/songs/batch")
    public ResponseEntity<?> getSongsBatch(@RequestParam List<Long> groupIds,
                                           @RequestParam String from,
                                           @RequestParam String to,
                                           HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate   = LocalDate.parse(to);
        // result map: "groupId|date" → list of song titles (non-headings, ordered)
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Long groupId : groupIds) {
            List<WorshipSong> songs = songRepo
                    .findByClientIdAndGroupIdAndServiceDateBetweenAndDeleteFlagFalseOrderByServiceDateAscSortOrderAsc(
                            clientId, groupId, fromDate, toDate);
            for (WorshipSong s : songs) {
                if (s.isHeading()) continue;
                String key = groupId + "|" + s.getServiceDate().toString();
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(s.getSongTitle());
            }
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/songs")
    public ResponseEntity<?> getSongs(@RequestParam Long groupId,
                                      @RequestParam(required = false) String date,
                                      HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (date == null || date.isBlank()) {
            // Return library songs (serviceDate IS NULL)
            List<WorshipSong> songs = songRepo
                    .findByClientIdAndGroupIdAndServiceDateIsNullAndDeleteFlagFalseOrderBySortOrderAsc(clientId, groupId);
            return ResponseEntity.ok(songs.stream().map(this::songMap).collect(Collectors.toList()));
        }
        List<WorshipSong> songs = songRepo
                .findByClientIdAndGroupIdAndServiceDateAndDeleteFlagFalseOrderBySortOrderAsc(
                        clientId, groupId, LocalDate.parse(date));
        return ResponseEntity.ok(songs.stream().map(this::songMap).collect(Collectors.toList()));
    }

    @PostMapping("/songs")
    public ResponseEntity<?> createSong(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Long   groupId = longVal(body.get("groupId"));
        String dateStr = str(body.get("serviceDate"));
        String title   = str(body.get("songTitle"));
        if (groupId == null || title == null || title.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "groupId, songTitle required."));
        if (groupRepo.findByIdAndClientId(groupId, clientId).isEmpty())
            return ResponseEntity.notFound().build();
        WorshipSong s = new WorshipSong();
        s.setClientId(clientId);
        s.setGroupId(groupId);
        s.setServiceDate(dateStr != null && !dateStr.isBlank() ? LocalDate.parse(dateStr) : null);
        s.setHeading(Boolean.TRUE.equals(body.get("isHeading")));
        s.setSongTitle(title);
        s.setSortOrder(intVal(body.get("sortOrder")));
        songRepo.save(s);
        return ResponseEntity.ok(songMap(s));
    }

    @PutMapping("/songs/{id}")
    public ResponseEntity<?> updateSong(@PathVariable Long id,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Optional<WorshipSong> opt = songRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getClientId().equals(clientId))
            return ResponseEntity.notFound().build();
        WorshipSong s = opt.get();
        if (body.containsKey("songTitle"))  s.setSongTitle(str(body.get("songTitle")));
        if (body.containsKey("isHeading"))  s.setHeading(Boolean.TRUE.equals(body.get("isHeading")));
        if (body.containsKey("sortOrder"))  s.setSortOrder(intVal(body.get("sortOrder")));
        songRepo.save(s);
        return ResponseEntity.ok(songMap(s));
    }

    @PutMapping("/songs/reorder")
    @Transactional
    public ResponseEntity<?> reorderSongs(@RequestBody List<Map<String, Object>> items,
                                          HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        for (Map<String, Object> item : items) {
            Long id = longVal(item.get("id"));
            Integer sortOrder = intVal(item.get("sortOrder"));
            if (id == null) continue;
            Optional<WorshipSong> opt = songRepo.findById(id);
            if (opt.isEmpty() || !opt.get().getClientId().equals(clientId)) continue;
            WorshipSong s = opt.get();
            if (sortOrder != null) s.setSortOrder(sortOrder);
            songRepo.save(s);
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/songs/{id}")
    public ResponseEntity<?> deleteSong(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Optional<WorshipSong> opt = songRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getClientId().equals(clientId))
            return ResponseEntity.notFound().build();
        WorshipSong s = opt.get();
        s.setDeleteFlag(true);
        songRepo.save(s);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/songs/bulk")
    @Transactional
    public ResponseEntity<?> bulkSaveSongs(@RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        Long   groupId = longVal(body.get("groupId"));
        String dateStr = str(body.get("serviceDate"));
        if (groupId == null || dateStr == null)
            return ResponseEntity.badRequest().body(Map.of("error", "groupId, serviceDate required."));
        if (groupRepo.findByIdAndClientId(groupId, clientId).isEmpty())
            return ResponseEntity.notFound().build();
        LocalDate date = LocalDate.parse(dateStr);
        songRepo.deleteByClientIdAndGroupIdAndServiceDate(clientId, groupId, date);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("songs");
        List<Map<String, Object>> saved = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> item = items.get(i);
                String title = str(item.get("songTitle"));
                if (title == null || title.isBlank()) continue;
                WorshipSong s = new WorshipSong();
                s.setClientId(clientId);
                s.setGroupId(groupId);
                s.setServiceDate(date);
                s.setHeading(Boolean.TRUE.equals(item.get("isHeading")));
                s.setSongTitle(title);
                s.setSortOrder(i);
                songRepo.save(s);
                saved.add(songMap(s));
            }
        }
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/songs/upload")
    public ResponseEntity<?> uploadSongDoc(@RequestParam Long groupId,
                                           @RequestParam(required = false) String date,
                                           @RequestParam("file") MultipartFile file,
                                           HttpServletRequest request) {
        ResponseEntity<?> denied = writeDeny(request);
        if (denied != null) return denied;
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (groupRepo.findByIdAndClientId(groupId, clientId).isEmpty())
            return ResponseEntity.notFound().build();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        List<String> lines = new ArrayList<>();
        try {
            if (filename.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
                    for (XWPFParagraph p : doc.getParagraphs()) {
                        String t = p.getText().trim();
                        if (!t.isBlank()) lines.add(t);
                    }
                }
            } else if (filename.endsWith(".pdf")) {
                try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    String text = stripper.getText(pdf);
                    for (String line : text.split("\\r?\\n")) {
                        String t = line.trim();
                        if (!t.isBlank()) lines.add(t);
                    }
                }
            } else {
                String text = new String(file.getBytes());
                for (String line : text.split("\\r?\\n")) {
                    String t = line.trim();
                    if (!t.isBlank()) lines.add(t);
                }
            }
        } catch (IOException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read file: " + ex.getMessage()));
        }
        // null date -> library upload (clears and replaces library for this group)
        boolean isLibrary = (date == null || date.isBlank());
        LocalDate serviceDate = isLibrary ? null : LocalDate.parse(date);
        if (isLibrary) {
            // Replace the group library entirely
            songRepo.deleteLibraryByClientIdAndGroupId(clientId, groupId);
        }
        List<WorshipSong> existing = isLibrary
                ? songRepo.findByClientIdAndGroupIdAndServiceDateIsNullAndDeleteFlagFalseOrderBySortOrderAsc(clientId, groupId)
                : songRepo.findByClientIdAndGroupIdAndServiceDateAndDeleteFlagFalseOrderBySortOrderAsc(clientId, groupId, serviceDate);
        int nextOrder = existing.stream()
                .mapToInt(s -> s.getSortOrder() == null ? 0 : s.getSortOrder())
                .max().orElse(-1) + 1;
        for (String line : lines) {
            WorshipSong s = new WorshipSong();
            s.setClientId(clientId);
            s.setGroupId(groupId);
            s.setServiceDate(serviceDate);
            s.setHeading(true);   // All uploaded lines become headings (section names)
            s.setSongTitle(line);
            s.setSortOrder(nextOrder++);
            songRepo.save(s);
        }
        List<WorshipSong> all = isLibrary
                ? songRepo.findByClientIdAndGroupIdAndServiceDateIsNullAndDeleteFlagFalseOrderBySortOrderAsc(clientId, groupId)
                : songRepo.findByClientIdAndGroupIdAndServiceDateAndDeleteFlagFalseOrderBySortOrderAsc(clientId, groupId, serviceDate);
        return ResponseEntity.ok(all.stream().map(this::songMap).collect(Collectors.toList()));
    }

    private Map<String, Object> songMap(WorshipSong s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          s.getId());
        m.put("groupId",     s.getGroupId());
        m.put("serviceDate", s.getServiceDate() != null ? s.getServiceDate().toString() : null);
        m.put("isHeading",   s.isHeading());
        m.put("songTitle",   s.getSongTitle());
        m.put("sortOrder",   s.getSortOrder());
        return m;
    }

    // ======================================================================
    // HELPERS
    // ======================================================================

    /**
     * Same guard chain as the {@code /worshipPlanning} page route, applied to
     * every write handler. Returns {@code null} when allowed.
     */
    private static ResponseEntity<?> writeDeny(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "general.worshipplanning");
        if (deny == null) deny = RoleGuard.requireMemberPermission(request, "member.worship");
        if (deny == null) return null;
        int status = RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403;
        return ResponseEntity.status(status).body(Map.of("error", "Access denied"));
    }

    /**
     * WorshipInstrument carries no clientId — its tenant is its group's.
     * Empty for an unknown id and for another church's instrument alike.
     */
    private Optional<WorshipInstrument> instrumentForTenant(Long instrumentId, String clientId) {
        if (instrumentId == null) return Optional.empty();
        return instrumentRepo.findById(instrumentId)
                .filter(i -> i.getGroupId() != null
                        && groupRepo.findByIdAndClientId(i.getGroupId(), clientId).isPresent());
    }

    /** WorshipGroupMember → instrument → group → clientId. */
    private Optional<WorshipGroupMember> groupMemberForTenant(Long memberId, String clientId) {
        if (memberId == null) return Optional.empty();
        return groupMemberRepo.findById(memberId)
                .filter(m -> instrumentForTenant(m.getInstrumentId(), clientId).isPresent());
    }

    /**
     * Build group trees for a list of groups using bulk queries — O(3) queries
     * regardless of how many groups/instruments exist, vs. O(1 + N + N×M) before.
     */
    private List<Map<String, Object>> buildGroupTrees(List<WorshipGroup> groups) {
        if (groups.isEmpty()) return new ArrayList<>();
        List<Long> groupIds = groups.stream().map(WorshipGroup::getId).collect(Collectors.toList());
        // 1 query: all instruments for all groups
        List<WorshipInstrument> allInstruments = instrumentRepo.findByGroupIdInAndDeleteFlagFalse(groupIds);
        List<Long> instrumentIds = allInstruments.stream().map(WorshipInstrument::getId).collect(Collectors.toList());
        // 1 query: all members for all instruments
        Map<Long, List<WorshipGroupMember>> membersByInstrument = new HashMap<>();
        if (!instrumentIds.isEmpty()) {
            groupMemberRepo.findByInstrumentIdInAndDeleteFlagFalse(instrumentIds)
                    .forEach(m -> membersByInstrument
                            .computeIfAbsent(m.getInstrumentId(), k -> new ArrayList<>()).add(m));
        }
        // Group instruments by groupId
        Map<Long, List<WorshipInstrument>> instsByGroup = new HashMap<>();
        allInstruments.forEach(i -> instsByGroup
                .computeIfAbsent(i.getGroupId(), k -> new ArrayList<>()).add(i));
        // Assemble result
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorshipGroup g : groups) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id",        g.getId());
            map.put("groupName", g.getGroupName());
            List<WorshipInstrument> instruments = instsByGroup.getOrDefault(g.getId(), List.of());
            List<Map<String, Object>> instList = new ArrayList<>();
            for (WorshipInstrument inst : instruments) {
                Map<String, Object> iMap = new LinkedHashMap<>();
                iMap.put("id",             inst.getId());
                iMap.put("instrumentName", inst.getInstrumentName());
                List<WorshipGroupMember> members = membersByInstrument.getOrDefault(inst.getId(), List.of());
                iMap.put("members", members.stream().map(this::memberMap).collect(Collectors.toList()));
                instList.add(iMap);
            }
            map.put("instruments", instList);
            result.add(map);
        }
        return result;
    }

    /** Convenience wrapper for a single group — delegates to bulk method. */
    private Map<String, Object> buildGroupTree(WorshipGroup g) {
        List<Map<String, Object>> list = buildGroupTrees(List.of(g));
        return list.isEmpty() ? new LinkedHashMap<>() : list.get(0);
    }

    /**
     * Build assignment detail maps for a list of assignments using bulk queries —
     * O(3) queries regardless of how many assignments/instruments exist,
     * vs. O(N×3) before (one group + one memberList + K instrument lookups per assignment).
     */
    private List<Map<String, Object>> buildAssignmentDetails(List<WorshipAssignment> assignments) {
        if (assignments.isEmpty()) return new ArrayList<>();
        // 1 query: all assignment members for all assignments
        List<Long> assignmentIds = assignments.stream().map(WorshipAssignment::getId).collect(Collectors.toList());
        List<WorshipAssignmentMember> allMembers =
                assignmentMemberRepo.findByAssignmentIdInOrderBySortOrderAsc(assignmentIds);
        // Group members by assignmentId
        Map<Long, List<WorshipAssignmentMember>> membersByAssignment = new HashMap<>();
        allMembers.forEach(m -> membersByAssignment
                .computeIfAbsent(m.getAssignmentId(), k -> new ArrayList<>()).add(m));
        // 1 query: all instruments referenced by those members
        Set<Long> instrumentIdSet = allMembers.stream()
                .map(WorshipAssignmentMember::getInstrumentId)
                .collect(Collectors.toSet());
        Map<Long, String> instNames = new HashMap<>();
        if (!instrumentIdSet.isEmpty()) {
            instrumentRepo.findByIdIn(instrumentIdSet)
                    .forEach(i -> instNames.put(i.getId(), i.getInstrumentName()));
        }
        // 1 query: all groups referenced by those assignments
        Set<Long> groupIdSet = assignments.stream()
                .map(WorshipAssignment::getGroupId)
                .collect(Collectors.toSet());
        Map<Long, String> groupNames = new HashMap<>();
        groupRepo.findAllById(groupIdSet)
                .forEach(g -> groupNames.put(g.getId(), g.getGroupName()));
        // Assemble result
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorshipAssignment a : assignments) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id",             a.getId());
            map.put("groupId",        a.getGroupId());
            map.put("assignmentDate", a.getAssignmentDate().toString());
            map.put("assignmentType", a.getAssignmentType());
            map.put("groupName",      groupNames.getOrDefault(a.getGroupId(), ""));
            List<WorshipAssignmentMember> amList =
                    membersByAssignment.getOrDefault(a.getId(), List.of());
            Map<Long, List<Map<String, Object>>> byInstrument = new LinkedHashMap<>();
            for (WorshipAssignmentMember am : amList) {
                byInstrument.computeIfAbsent(am.getInstrumentId(), k -> new ArrayList<>())
                        .add(Map.of("id", am.getId(), "memberName", am.getMemberName(),
                                    "sortOrder", am.getSortOrder() != null ? am.getSortOrder() : 0));
            }
            List<Map<String, Object>> slots = new ArrayList<>();
            for (Map.Entry<Long, List<Map<String, Object>>> e : byInstrument.entrySet()) {
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("instrumentId",   e.getKey());
                slot.put("instrumentName", instNames.getOrDefault(e.getKey(), ""));
                slot.put("members",        e.getValue());
                slots.add(slot);
            }
            map.put("slots", slots);
            result.add(map);
        }
        return result;
    }

    /** Convenience wrapper for a single assignment — delegates to bulk method. */
    private Map<String, Object> buildAssignmentDetail(WorshipAssignment a) {
        List<Map<String, Object>> list = buildAssignmentDetails(List.of(a));
        return list.isEmpty() ? new LinkedHashMap<>() : list.get(0);
    }

    private Map<String, Object> memberMap(WorshipGroupMember m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",            m.getId());
        map.put("memberName",    m.getMemberName());
        map.put("rotationOrder", m.getRotationOrder());
        map.put("instrumentId",  m.getInstrumentId());
        return map;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static Long longVal(Object o) {
        if (o == null) return null;
        try { return Long.parseLong(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Integer intVal(Object o) {
        if (o == null) return null;
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return null; }
    }
}
