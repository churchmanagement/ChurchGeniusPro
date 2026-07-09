package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Attendance module business logic (Phase 1): configurable service types, per-member
 * scan codes, manual/QR/barcode/family check-in, dashboard stats + trends, records
 * query, and Excel/CSV/PDF export.
 */
@Service
public class AttendanceService {

    private final AttendanceRecordRepository      recordRepo;
    private final AttendanceVisitorRepository     visitorRepo;
    private final AttendanceServiceTypeRepository typeRepo;
    private final MemberAttendanceCodeRepository  codeRepo;
    private final FamilyMemberRepository          memberRepo;
    private final VolunteerProfileRepository      volunteerRepo;

    public AttendanceService(AttendanceRecordRepository recordRepo,
                             AttendanceVisitorRepository visitorRepo,
                             AttendanceServiceTypeRepository typeRepo,
                             MemberAttendanceCodeRepository codeRepo,
                             FamilyMemberRepository memberRepo,
                             VolunteerProfileRepository volunteerRepo) {
        this.recordRepo    = recordRepo;
        this.visitorRepo   = visitorRepo;
        this.typeRepo      = typeRepo;
        this.codeRepo      = codeRepo;
        this.memberRepo    = memberRepo;
        this.volunteerRepo = volunteerRepo;
    }

    private static final DateTimeFormatter D  = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static final List<String> STATUSES = List.of("PRESENT", "LATE", "ABSENT", "EXCUSED");

    // ── Service types (seeded defaults, configurable) ───────────────────────────
    private static final String[][] DEFAULT_TYPES = {
            {"Sunday Worship","service"}, {"Bible Study","service"}, {"Prayer Meeting","service"},
            {"Youth Ministry","service"}, {"Children's Ministry","service"}, {"Choir","service"},
            {"Volunteer Activities","event"}, {"Custom Event","event"}
    };

    public List<AttendanceServiceType> serviceTypes(String clientId) {
        if (typeRepo.countByClientIdAndDeleteFlagFalse(clientId) == 0) {
            int i = 0;
            for (String[] d : DEFAULT_TYPES) {
                AttendanceServiceType t = new AttendanceServiceType();
                t.setClientId(clientId); t.setName(d[0]); t.setCategory(d[1]); t.setSortOrder(i++);
                typeRepo.save(t);
            }
        }
        return typeRepo.findByClientIdAndDeleteFlagFalseOrderBySortOrderAscNameAsc(clientId);
    }

    public AttendanceServiceType addServiceType(String clientId, String name, String category) {
        AttendanceServiceType t = new AttendanceServiceType();
        t.setClientId(clientId); t.setName(name);
        t.setCategory("event".equalsIgnoreCase(category) ? "event" : "service");
        t.setSortOrder(999);
        return typeRepo.save(t);
    }

    // ── Member scan code (QR / barcode value) ───────────────────────────────────
    public String memberCode(String clientId, Integer memberId) {
        return codeRepo.findByClientIdAndFamilyMemberId(clientId, memberId)
                .map(MemberAttendanceCode::getCode)
                .orElseGet(() -> {
                    MemberAttendanceCode c = new MemberAttendanceCode();
                    c.setClientId(clientId); c.setFamilyMemberId(memberId);
                    return codeRepo.save(c).getCode();
                });
    }

    /** Resolve a scanned code to a member (name + id), or empty. */
    public Optional<Map<String, Object>> resolveScan(String clientId, String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return codeRepo.findByCode(code.trim())
                .filter(c -> clientId.equals(c.getClientId()))
                .flatMap(c -> memberRepo.findById(c.getFamilyMemberId()))
                .filter(m -> !m.isDeleteFlag())
                .map(m -> personMap(m));
    }

    // ── People search (members + visitors) ──────────────────────────────────────
    public List<Map<String, Object>> searchPeople(String appClientId, String q) {
        String ql = q == null ? "" : q.toLowerCase().trim();
        List<Map<String, Object>> out = new ArrayList<>();
        for (FamilyMember m : memberRepo.findAllWithFamilyByAppUser(appClientId)) {
            if (ql.isEmpty() || matches(m, ql)) out.add(personMap(m));
            if (out.size() > 60) break;
        }
        if (!ql.isEmpty()) {
            for (AttendanceVisitor v : visitorRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(appClientId)) {
                if (contains(v.getName(), ql) || contains(v.getPhone(), ql) || contains(v.getEmail(), ql)) {
                    Map<String, Object> mp = new LinkedHashMap<>();
                    mp.put("personType", "VISITOR"); mp.put("visitorId", v.getId());
                    mp.put("name", v.getName()); mp.put("email", v.getEmail()); mp.put("phone", v.getPhone());
                    out.add(mp);
                }
            }
        }
        return out;
    }

    private boolean matches(FamilyMember m, String ql) {
        return contains(fullName(m), ql) || contains(m.getPhone(), ql) || contains(m.getEmail(), ql)
            || String.valueOf(m.getId()).equals(ql);
    }
    private static boolean contains(String s, String ql) { return s != null && s.toLowerCase().contains(ql); }
    private static String fullName(FamilyMember m) {
        return com.churchgeniuspro.util.MemberNameUtil.display(
                m.getFirstName(), m.getLastName(), m.getOtherName());
    }
    private Map<String, Object> personMap(FamilyMember m) {
        Map<String, Object> mp = new LinkedHashMap<>();
        mp.put("personType", "MEMBER");
        mp.put("memberId", m.getId());
        mp.put("name", fullName(m));
        mp.put("email", m.getEmail());
        mp.put("phone", m.getPhone());
        mp.put("photo", m.getPhotoThumbnail());
        mp.put("familyId", m.getFamily() != null ? m.getFamily().getId() : null);
        return mp;
    }

    public List<Map<String, Object>> familyMembers(Integer familyId) {
        return memberRepo.findActiveMembersByFamilyId(familyId).stream()
                .map(this::personMap).collect(Collectors.toList());
    }

    // ── Check-in ────────────────────────────────────────────────────────────────
    /** Member check-in (idempotent per member+date+service). */
    public AttendanceRecord checkInMember(String clientId, String createdBy, Integer memberId, String name,
                                          String service, String ministry, String campus, String status, String method) {
        AttendanceRecord a = recordRepo.findDuplicate(clientId, LocalDate.now(), service, "MEMBER", memberId, null)
                .orElseGet(AttendanceRecord::new);
        a.setClientId(clientId);
        a.setAttendanceDate(LocalDate.now());
        if (a.getCheckInTime() == null) a.setCheckInTime(LocalDateTime.now());
        a.setPersonType("MEMBER");
        a.setFamilyMemberId(memberId);
        a.setPersonName(name);
        a.setServiceType(service);
        a.setMinistry(blankNull(ministry));
        a.setCampus(blankNull(campus));
        a.setStatus(normStatus(status));
        a.setCheckInMethod(method != null ? method : "manual");
        a.setCreatedBy(createdBy);
        a.setDeleteFlag(false);
        return recordRepo.save(a);
    }

    public List<AttendanceRecord> familyCheckIn(String clientId, String createdBy, Integer familyId,
                                                String service, String ministry, String campus, String status, List<Integer> memberIds) {
        List<AttendanceRecord> out = new ArrayList<>();
        for (FamilyMember m : memberRepo.findActiveMembersByFamilyId(familyId)) {
            if (memberIds != null && !memberIds.isEmpty() && !memberIds.contains(m.getId())) continue;
            out.add(checkInMember(clientId, createdBy, m.getId(), fullName(m), service, ministry, campus, status, "family"));
        }
        return out;
    }

    /** Visitor check-in: find-or-create visitor (by phone/email), bump visit counters. */
    public Map<String, Object> checkInVisitor(String clientId, String createdBy, String name, String phone,
                                              String email, String address, String invitedBy,
                                              String service, String ministry, String campus, String status) {
        AttendanceVisitor v = null;
        if (phone != null && !phone.isBlank())
            v = visitorRepo.findFirstByClientIdAndPhoneAndDeleteFlagFalse(clientId, phone.trim()).orElse(null);
        if (v == null && email != null && !email.isBlank())
            v = visitorRepo.findFirstByClientIdAndEmailAndDeleteFlagFalse(clientId, email.trim()).orElse(null);
        boolean isNew = (v == null);
        if (isNew) {
            v = new AttendanceVisitor();
            v.setClientId(clientId);
            v.setFirstVisitDate(LocalDate.now());
            v.setVisitCount(0);
        }
        if (name != null && !name.isBlank()) v.setName(name.trim());
        if (phone != null && !phone.isBlank()) v.setPhone(phone.trim());
        if (email != null && !email.isBlank()) v.setEmail(email.trim());
        if (address != null && !address.isBlank()) v.setAddress(address.trim());
        if (invitedBy != null && !invitedBy.isBlank()) v.setInvitedBy(invitedBy.trim());
        v.setLastVisitDate(LocalDate.now());
        v.setVisitCount(v.getVisitCount() + 1);
        visitorRepo.save(v);

        AttendanceRecord a = new AttendanceRecord();
        a.setClientId(clientId); a.setAttendanceDate(LocalDate.now()); a.setCheckInTime(LocalDateTime.now());
        a.setPersonType("VISITOR"); a.setVisitorId(v.getId()); a.setPersonName(v.getName());
        a.setServiceType(service); a.setMinistry(blankNull(ministry)); a.setCampus(blankNull(campus));
        a.setStatus(normStatus(status)); a.setCheckInMethod("manual"); a.setCreatedBy(createdBy);
        recordRepo.save(a);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("visitorId", v.getId()); r.put("name", v.getName());
        r.put("visitCount", v.getVisitCount());
        r.put("classification", v.getVisitCount() <= 1 ? "First Visit" : (v.getVisitCount() == 2 ? "Second Visit" : "Returning Visitor"));
        return r;
    }

    public boolean checkOut(Long recordId, String clientId) {
        return recordRepo.findByIdAndClientIdAndDeleteFlagFalse(recordId, clientId).map(a -> {
            a.setCheckOutTime(LocalDateTime.now()); recordRepo.save(a); return true;
        }).orElse(false);
    }

    // ── Dashboard ────────────────────────────────────────────────────────────────
    public Map<String, Object> dashboard(String clientId) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY), weekEnd = weekStart.plusDays(6);
        LocalDate monthStart = today.withDayOfMonth(1), monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        List<AttendanceRecord> week  = recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, weekStart, weekEnd);
        List<AttendanceRecord> month = recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, monthStart, monthEnd);

        Set<Integer> presentMembersWeek = week.stream()
                .filter(a -> "MEMBER".equals(a.getPersonType()) && a.getFamilyMemberId() != null && isPresent(a))
                .map(AttendanceRecord::getFamilyMemberId).collect(Collectors.toSet());
        Set<Integer> volunteerIds = volunteerRepo.findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(clientId)
                .stream().map(VolunteerProfile::getFamilyMemberId).filter(Objects::nonNull).collect(Collectors.toSet());
        long volunteersPresent = presentMembersWeek.stream().filter(volunteerIds::contains).count();

        long firstTimeVisitors = visitorRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(clientId).stream()
                .filter(v -> v.getFirstVisitDate() != null && !v.getFirstVisitDate().isBefore(monthStart) && !v.getFirstVisitDate().isAfter(monthEnd))
                .count();
        Set<Long> returningVisitorIds = month.stream()
                .filter(a -> "VISITOR".equals(a.getPersonType()) && a.getVisitorId() != null)
                .map(AttendanceRecord::getVisitorId).collect(Collectors.toSet());
        long returningVisitors = visitorRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(clientId).stream()
                .filter(v -> returningVisitorIds.contains(v.getId()) && v.getVisitCount() > 1).count();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weekCount",  week.stream().filter(this::isPresent).count());
        m.put("monthCount", month.stream().filter(this::isPresent).count());
        m.put("firstTimeVisitors", firstTimeVisitors);
        m.put("returningVisitors", returningVisitors);
        m.put("membersPresent", presentMembersWeek.size());
        m.put("volunteersPresent", volunteersPresent);
        return m;
    }

    private boolean isPresent(AttendanceRecord a) { return "PRESENT".equals(a.getStatus()) || "LATE".equals(a.getStatus()); }

    // ── Trends ───────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> weeklyTrend(String clientId) {
        LocalDate today = LocalDate.now();
        LocalDate end = today.with(DayOfWeek.SUNDAY);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            LocalDate wEnd = end.minusWeeks(i), wStart = wEnd.minusDays(6);
            long c = recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, wStart, wEnd)
                    .stream().filter(this::isPresent).count();
            out.add(point("Wk of " + wStart.format(DateTimeFormatter.ofPattern("MMM d")), c));
        }
        return out;
    }

    public List<Map<String, Object>> monthlyTrend(String clientId) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 11; i >= 0; i--) {
            LocalDate mStart = today.minusMonths(i).withDayOfMonth(1);
            LocalDate mEnd = mStart.withDayOfMonth(mStart.lengthOfMonth());
            long c = recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, mStart, mEnd)
                    .stream().filter(this::isPresent).count();
            out.add(point(mStart.format(DateTimeFormatter.ofPattern("MMM yy")), c));
        }
        return out;
    }

    public List<Map<String, Object>> byField(String clientId, boolean ministry) {
        return byField(clientId, ministry, LocalDate.now().minusMonths(3), LocalDate.now());
    }

    public List<Map<String, Object>> byField(String clientId, boolean ministry, LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.now().minusMonths(3);
        LocalDate t = to   != null ? to   : LocalDate.now();
        Map<String, Long> counts = new TreeMap<>();
        for (AttendanceRecord a : recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, f, t)) {
            if (!isPresent(a)) continue;
            String key = ministry ? a.getMinistry() : a.getServiceType();
            if (key == null || key.isBlank()) key = ministry ? "(none)" : "(unspecified)";
            counts.merge(key, 1L, Long::sum);
        }
        return counts.entrySet().stream().map(e -> point(e.getKey(), e.getValue())).collect(Collectors.toList());
    }

    public List<Map<String, Object>> yearlyTrend(String clientId) {
        int year = LocalDate.now().getYear();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int y = year - 4; y <= year; y++) {
            long c = recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(
                    clientId, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))
                    .stream().filter(this::isPresent).count();
            out.add(point(String.valueOf(y), c));
        }
        return out;
    }

    private Map<String, Object> point(String label, long value) {
        Map<String, Object> p = new LinkedHashMap<>(); p.put("label", label); p.put("value", value); return p;
    }

    // ── Reports ──────────────────────────────────────────────────────────────────
    /** Per-member: attendance %, last attendance, consecutive absences (over a range). */
    public List<Map<String, Object>> memberReport(String clientId, LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.now().minusMonths(3);
        LocalDate t = to   != null ? to   : LocalDate.now();
        List<AttendanceRecord> recs = recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, f, t);
        List<LocalDate> serviceDays = recs.stream().filter(this::isPresent)
                .map(AttendanceRecord::getAttendanceDate).distinct()
                .sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        int totalDays = serviceDays.size();

        Map<Integer, TreeSet<LocalDate>> byMember = new LinkedHashMap<>();
        Map<Integer, String> names = new HashMap<>();
        for (AttendanceRecord a : recs) {
            if (!"MEMBER".equals(a.getPersonType()) || a.getFamilyMemberId() == null) continue;
            names.putIfAbsent(a.getFamilyMemberId(), a.getPersonName());
            if (isPresent(a)) byMember.computeIfAbsent(a.getFamilyMemberId(), k -> new TreeSet<>()).add(a.getAttendanceDate());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Integer, TreeSet<LocalDate>> e : byMember.entrySet()) {
            TreeSet<LocalDate> dates = e.getValue();
            int attended = dates.size();
            int consec = 0;
            for (LocalDate d : serviceDays) { if (dates.contains(d)) break; consec++; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("memberId", e.getKey());
            m.put("name", names.get(e.getKey()));
            m.put("attended", attended);
            m.put("totalDays", totalDays);
            m.put("percentage", totalDays == 0 ? 0 : (int) Math.round(100.0 * attended / totalDays));
            m.put("lastAttendance", dates.isEmpty() ? null : dates.last().toString());
            m.put("consecutiveAbsences", consec);
            out.add(m);
        }
        out.sort((a, b) -> Integer.compare((Integer) b.get("percentage"), (Integer) a.get("percentage")));
        return out;
    }

    /** Per-family summary: check-ins, distinct members attended, last attendance. */
    public List<Map<String, Object>> familyReport(String clientId, LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.now().minusMonths(3);
        LocalDate t = to   != null ? to   : LocalDate.now();
        Map<Integer, Integer> memberFamily = new HashMap<>();
        Map<Integer, String> familyName = new HashMap<>();
        for (FamilyMember m : memberRepo.findAllWithFamilyByAppUser(clientId)) {
            Integer fid = m.getFamily() != null ? m.getFamily().getId() : null;
            if (fid == null) continue;
            memberFamily.put(m.getId(), fid);
            familyName.putIfAbsent(fid, ((m.getLastName() != null && !m.getLastName().isBlank()) ? m.getLastName() : "Family") + " Family");
        }
        Map<Integer, Integer> checkins = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> distinct = new HashMap<>();
        Map<Integer, LocalDate> last = new HashMap<>();
        for (AttendanceRecord a : recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, f, t)) {
            if (!isPresent(a) || a.getFamilyMemberId() == null) continue;
            Integer fid = memberFamily.get(a.getFamilyMemberId());
            if (fid == null) continue;
            checkins.merge(fid, 1, Integer::sum);
            distinct.computeIfAbsent(fid, k -> new HashSet<>()).add(a.getFamilyMemberId());
            last.merge(fid, a.getAttendanceDate(), (x, y) -> x.isAfter(y) ? x : y);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Integer fid : checkins.keySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("familyId", fid);
            m.put("family", familyName.getOrDefault(fid, "Family"));
            m.put("checkIns", checkins.get(fid));
            m.put("membersAttended", distinct.getOrDefault(fid, Set.of()).size());
            m.put("lastAttendance", last.get(fid) == null ? null : last.get(fid).toString());
            out.add(m);
        }
        out.sort((a, b) -> Integer.compare((Integer) b.get("checkIns"), (Integer) a.get("checkIns")));
        return out;
    }

    /** Visitor report: first-time, returning, and follow-up lists. */
    public Map<String, Object> visitorReport(String clientId) {
        LocalDate cutoff = LocalDate.now().minusDays(60);
        List<Map<String, Object>> firstTime = new ArrayList<>(), returning = new ArrayList<>(), followUp = new ArrayList<>();
        for (AttendanceVisitor v : visitorRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(clientId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId()); m.put("name", v.getName()); m.put("phone", v.getPhone());
            m.put("email", v.getEmail());
            m.put("firstVisit", v.getFirstVisitDate() == null ? null : v.getFirstVisitDate().toString());
            m.put("lastVisit",  v.getLastVisitDate()  == null ? null : v.getLastVisitDate().toString());
            m.put("visits", v.getVisitCount());
            if (v.getVisitCount() <= 1) firstTime.add(m); else returning.add(m);
            // Needs follow-up: still new (<=2 visits) and seen within the last 60 days.
            if (v.getVisitCount() <= 2 && v.getLastVisitDate() != null && !v.getLastVisitDate().isBefore(cutoff))
                followUp.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("firstTime", firstTime);
        out.put("returning", returning);
        out.put("followUp", followUp);
        return out;
    }

    // ── Records query + edit ─────────────────────────────────────────────────────
    public List<AttendanceRecord> queryRecords(String clientId, LocalDate from, LocalDate to,
                                               Integer memberId, Integer familyId, String service,
                                               String status, String ministry) {
        LocalDate f = from != null ? from : LocalDate.now().minusMonths(3);
        LocalDate t = to != null ? to : LocalDate.now();
        Set<Integer> familyMemberIds = familyId == null ? null :
                memberRepo.findActiveMembersByFamilyId(familyId).stream().map(FamilyMember::getId).collect(Collectors.toSet());
        return recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, f, t).stream()
                .filter(a -> memberId == null || memberId.equals(a.getFamilyMemberId()))
                .filter(a -> familyMemberIds == null || (a.getFamilyMemberId() != null && familyMemberIds.contains(a.getFamilyMemberId())))
                .filter(a -> service == null || service.isBlank() || service.equals(a.getServiceType()))
                .filter(a -> status == null || status.isBlank() || status.equalsIgnoreCase(a.getStatus()))
                .filter(a -> ministry == null || ministry.isBlank() || ministry.equals(a.getMinistry()))
                .sorted(Comparator.comparing(AttendanceRecord::getAttendanceDate).reversed()
                        .thenComparing(a -> a.getPersonName() == null ? "" : a.getPersonName()))
                .collect(Collectors.toList());
    }

    public Optional<AttendanceRecord> updateRecord(Long id, String clientId, Map<String, Object> body) {
        return recordRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId).map(a -> {
            Object st = body.get("status");      if (st != null) a.setStatus(normStatus(String.valueOf(st)));
            Object sv = body.get("serviceType"); if (sv != null) a.setServiceType(String.valueOf(sv));
            Object mn = body.get("ministry");    if (mn != null) a.setMinistry(blankNull(String.valueOf(mn)));
            Object cp = body.get("campus");      if (cp != null) a.setCampus(blankNull(String.valueOf(cp)));
            Object dt = body.get("attendanceDate"); if (dt != null && !String.valueOf(dt).isBlank()) a.setAttendanceDate(LocalDate.parse(String.valueOf(dt)));
            recordRepo.save(a);
            return a;
        });
    }

    public boolean deleteRecord(Long id, String clientId) {
        return recordRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId)
                .map(a -> { a.setDeleteFlag(true); recordRepo.save(a); return true; }).orElse(false);
    }

    public Map<String, Object> recordMap(AttendanceRecord a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("date", a.getAttendanceDate() == null ? null : a.getAttendanceDate().format(D));
        m.put("checkIn",  a.getCheckInTime()  == null ? null : a.getCheckInTime().format(DT));
        m.put("checkOut", a.getCheckOutTime() == null ? null : a.getCheckOutTime().format(DT));
        m.put("name", a.getPersonName());
        m.put("personType", a.getPersonType());
        m.put("memberId", a.getFamilyMemberId());
        m.put("serviceType", a.getServiceType());
        m.put("ministry", a.getMinistry());
        m.put("campus", a.getCampus());
        m.put("status", a.getStatus());
        m.put("method", a.getCheckInMethod());
        return m;
    }

    // ── Exports ───────────────────────────────────────────────────────────────────
    private static final String[] COLS = {"Date","Check-In","Check-Out","Name","Type","Service","Ministry","Campus","Status","Method"};

    private List<String[]> rows(List<AttendanceRecord> recs) {
        List<String[]> rows = new ArrayList<>();
        for (AttendanceRecord a : recs) {
            rows.add(new String[]{
                a.getAttendanceDate() == null ? "" : a.getAttendanceDate().format(D),
                a.getCheckInTime()  == null ? "" : a.getCheckInTime().format(DT),
                a.getCheckOutTime() == null ? "" : a.getCheckOutTime().format(DT),
                nv(a.getPersonName()), nv(a.getPersonType()), nv(a.getServiceType()),
                nv(a.getMinistry()), nv(a.getCampus()), nv(a.getStatus()), nv(a.getCheckInMethod())
            });
        }
        return rows;
    }

    public byte[] exportCsv(List<AttendanceRecord> recs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", COLS)).append("\n");
        for (String[] r : rows(recs)) {
            sb.append(Arrays.stream(r).map(this::csv).collect(Collectors.joining(","))).append("\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private String csv(String s) { if (s == null) return ""; return "\"" + s.replace("\"", "\"\"") + "\""; }

    public byte[] exportXlsx(List<AttendanceRecord> recs) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Attendance");
            Row h = sh.createRow(0);
            for (int i = 0; i < COLS.length; i++) h.createCell(i).setCellValue(COLS[i]);
            int r = 1;
            for (String[] row : rows(recs)) {
                Row xr = sh.createRow(r++);
                for (int i = 0; i < row.length; i++) xr.createCell(i).setCellValue(row[i]);
            }
            for (int i = 0; i < COLS.length; i++) sh.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) { throw new RuntimeException("Failed to build spreadsheet", e); }
    }

    public byte[] exportPdf(List<AttendanceRecord> recs, String title) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold   = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            List<String[]> rows = rows(recs);
            // landscape, ~38 rows/page
            PDRectangle land = new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth());
            float[] x = {30, 95, 165, 235, 360, 405, 490, 575, 650, 705};
            int per = 36, idx = 0;
            while (idx < rows.size() || idx == 0) {
                PDPage page = new PDPage(land); doc.addPage(page);
                PDPageContentStream cs = new PDPageContentStream(doc, page);
                float y = land.getHeight() - 36;
                cs.beginText(); cs.setFont(bold, 13); cs.newLineAtOffset(30, y);
                cs.showText(title != null ? title : "Attendance Report"); cs.endText();
                y -= 20;
                cs.beginText(); cs.setFont(bold, 8); cs.newLineAtOffset(30, y);
                for (int i = 0; i < COLS.length; i++) { cs.showText(COLS[i]); cs.newLineAtOffset(x[Math.min(i+1, x.length-1)] - x[i], 0); }
                cs.endText();
                y -= 14;
                int end = Math.min(rows.size(), idx + per);
                for (; idx < end; idx++) {
                    String[] row = rows.get(idx);
                    cs.beginText(); cs.setFont(normal, 7.5f); cs.newLineAtOffset(30, y);
                    for (int i = 0; i < row.length; i++) {
                        cs.showText(clip(row[i], 22));
                        cs.newLineAtOffset(x[Math.min(i+1, x.length-1)] - x[i], 0);
                    }
                    cs.endText();
                    y -= 12;
                }
                cs.close();
                if (idx >= rows.size()) break;
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) { throw new RuntimeException("Failed to build PDF", e); }
    }

    private static String clip(String s, int n) { if (s == null) return ""; return s.length() > n ? s.substring(0, n) : s; }
    private static String nv(String s) { return s == null ? "" : s; }
    private static String blankNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
    private static String normStatus(String s) {
        if (s == null) return "PRESENT";
        String u = s.trim().toUpperCase();
        return STATUSES.contains(u) ? u : "PRESENT";
    }
}
