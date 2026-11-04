package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AttendanceRecord;
import com.churchgeniuspro.hibernate.AttendanceServiceType;
import com.churchgeniuspro.hibernate.AttendanceVisitor;
import com.churchgeniuspro.hibernate.FollowUp;
import com.churchgeniuspro.hibernate.VisitorParty;
import com.churchgeniuspro.repository.AttendanceRecordRepository;
import com.churchgeniuspro.repository.AttendanceServiceTypeRepository;
import com.churchgeniuspro.repository.AttendanceVisitorRepository;
import com.churchgeniuspro.repository.FollowUpRepository;
import com.churchgeniuspro.repository.VisitorPartyRepository;
import com.churchgeniuspro.util.AppClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Visitor check-in and attendance enhancements (2026-11-04, approved plan D1–D8).
 *
 * <ul>
 *   <li>Services / events = ONLY the church's {@code attendance_service_type} rows (D2). Services are
 *       unique by name ignoring case and spaces; events by name + date (D3).</li>
 *   <li>Visitors register as an individual, a family or a group; families and groups are a
 *       {@link VisitorParty}, each person stays an {@link AttendanceVisitor} (D1).</li>
 *   <li>Matching: same e-mail and first name, else same phone digits and first name. Never two people of
 *       one registration onto the same visitor; never a duplicate record for person + date + service.</li>
 *   <li>Interest / follow-up are stored on each visit (attendance record) and the latest copied to the
 *       visitor (D6). Ticking follow-up creates ONE unassigned PENDING task per visitor party, linked
 *       {@code VISITOR → primary visitor}; a repeat visit is appended to the open task (D5).</li>
 *   <li>The attendance date may be in the past or the future (D8); any day other than today (church
 *       time) is saved already checked out.</li>
 * </ul>
 * Every lookup is scoped by the church id.
 */
@Service
public class VisitorCheckInService {

    public static final String LINK_VISITOR = "VISITOR";
    public static final List<String> INTEREST = List.of("INTERESTED", "NOT_SURE", "NOT_INTERESTED");
    public static final List<String> RELATIONSHIPS = List.of("PRIMARY", "SPOUSE", "ADULT", "CHILD", "OTHER");
    public static final List<String> FILTERS = List.of("all", "services", "today", "future", "past");
    private static final int MAX_PEOPLE = 30;

    private final AttendanceVisitorRepository visitorRepo;
    private final AttendanceRecordRepository recordRepo;
    private final AttendanceServiceTypeRepository typeRepo;
    private final VisitorPartyRepository partyRepo;
    private final FollowUpRepository followUpRepo;

    public VisitorCheckInService(AttendanceVisitorRepository visitorRepo, AttendanceRecordRepository recordRepo,
                                 AttendanceServiceTypeRepository typeRepo, VisitorPartyRepository partyRepo,
                                 FollowUpRepository followUpRepo) {
        this.visitorRepo = visitorRepo;
        this.recordRepo = recordRepo;
        this.typeRepo = typeRepo;
        this.partyRepo = partyRepo;
        this.followUpRepo = followUpRepo;
    }

    /** A request the caller can fix (400 / 404 / 409 in the controller). */
    public static class CheckInException extends RuntimeException {
        public final int status;
        public final String code;
        public final Map<String, Object> extra;
        public CheckInException(int status, String code, String message) { this(status, code, message, Map.of()); }
        public CheckInException(int status, String code, String message, Map<String, Object> extra) {
            super(message); this.status = status; this.code = code; this.extra = extra;
        }
    }

    // ══ Services and events (the "target" a check-in belongs to) ═════════════════

    public static String normName(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public static boolean isEvent(AttendanceServiceType t) {
        return "event".equalsIgnoreCase(t.getCategory()) && t.getEventDate() != null;
    }

    /** Services, Today's / Future / Past events from the church's own list, optionally filtered by name. */
    public List<Map<String, Object>> targets(String clientId, String filter, String q, List<AttendanceServiceType> all) {
        String f = filter == null || filter.isBlank() ? "all" : filter.toLowerCase(Locale.ROOT);
        if (!FILTERS.contains(f)) throw new CheckInException(400, "BAD_FILTER", "Unknown filter.");
        LocalDate today = AppClock.today();
        String ql = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AttendanceServiceType t : all) {
            if (!clientId.equals(t.getClientId()) || t.isDeleteFlag()) continue;
            boolean ev = isEvent(t);
            String when = !ev ? "service" : t.getEventDate().isEqual(today) ? "today"
                    : t.getEventDate().isAfter(today) ? "future" : "past";
            if (!"all".equals(f) && !f.equals(when.equals("service") ? "services" : when)) continue;
            if (!ql.isEmpty() && (t.getName() == null || !t.getName().toLowerCase(Locale.ROOT).contains(ql))) continue;
            out.add(targetMap(t, when));
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> "service".equals(m.get("when")) ? 0 : 1)
                .thenComparing((Map<String, Object> m) -> m.get("eventDate") == null ? "" : String.valueOf(m.get("eventDate")),
                        "past".equals(f) ? Comparator.<String>reverseOrder() : Comparator.<String>naturalOrder())
                .thenComparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
        return out;
    }

    public Map<String, Object> targetMap(AttendanceServiceType t, String when) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("kind", isEvent(t) ? "event" : "service");
        m.put("eventDate", t.getEventDate() == null ? null : t.getEventDate().toString());
        m.put("when", when);
        return m;
    }

    /**
     * New service (name) or event (name + date). Service names are unique ignoring case and spaces.
     * An event with the same name on the same date is refused; the same name on another date is
     * returned as a suggestion (409 EVENT_NAME_EXISTS) unless {@code confirmNew}.
     */
    @Transactional
    public AttendanceServiceType createTarget(String clientId, String kind, String name, LocalDate eventDate,
                                              boolean confirmNew) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new CheckInException(400, "NAME_REQUIRED", "Enter a name.");
        if (n.length() > 100) throw new CheckInException(400, "NAME_TOO_LONG", "Names can be at most 100 characters.");
        boolean event = "event".equalsIgnoreCase(kind);
        if (!event && !"service".equalsIgnoreCase(kind)) throw new CheckInException(400, "BAD_KIND", "Choose Service or Event.");
        if (event && eventDate == null) throw new CheckInException(400, "DATE_REQUIRED", "Enter the event date.");
        checkDateRange(eventDate);
        String key = normName(n);
        List<AttendanceServiceType> live = typeRepo.findByClientIdAndDeleteFlagFalseOrderBySortOrderAscNameAsc(clientId);
        if (!event) {
            for (AttendanceServiceType t : live) {
                if (!isEvent(t) && key.equals(normName(t.getName())))
                    throw new CheckInException(409, "SERVICE_EXISTS",
                            "A service named \"" + t.getName() + "\" already exists.", Map.of("existing", targetMap(t, "service")));
            }
        } else {
            List<Map<String, Object>> sameName = new ArrayList<>();
            LocalDate today = AppClock.today();
            for (AttendanceServiceType t : live) {
                if (!isEvent(t) || !key.equals(normName(t.getName()))) continue;
                if (t.getEventDate().isEqual(eventDate))
                    throw new CheckInException(409, "EVENT_EXISTS",
                            "\"" + t.getName() + "\" is already on " + eventDate + ".", Map.of("existing", targetMap(t, whenOf(t, today))));
                sameName.add(targetMap(t, whenOf(t, today)));
            }
            if (!sameName.isEmpty() && !confirmNew)
                throw new CheckInException(409, "EVENT_NAME_EXISTS",
                        "An event with this name already exists on another date. Pick it, or create a new one for "
                                + eventDate + ".", Map.of("suggestions", sameName));
        }
        AttendanceServiceType t = new AttendanceServiceType();
        t.setClientId(clientId);
        t.setName(n);
        t.setCategory(event ? "event" : "service");
        t.setEventDate(event ? eventDate : null);
        t.setSortOrder(999);
        return typeRepo.save(t);
    }

    private static String whenOf(AttendanceServiceType t, LocalDate today) {
        if (!isEvent(t)) return "service";
        return t.getEventDate().isEqual(today) ? "today" : t.getEventDate().isAfter(today) ? "future" : "past";
    }

    /** The church's own service/event by id (404 otherwise). */
    public AttendanceServiceType target(String clientId, Long id) {
        if (id == null) throw new CheckInException(400, "TARGET_REQUIRED", "Choose a service or event.");
        return typeRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId)
                .orElseThrow(() -> new CheckInException(404, "TARGET_NOT_FOUND", "That service or event was not found."));
    }

    /** Attendance dates: past and future allowed (D8), within ten years back / two years ahead. */
    public static void checkDateRange(LocalDate d) {
        if (d == null) return;
        LocalDate today = AppClock.today();
        if (d.isBefore(today.minusYears(10)) || d.isAfter(today.plusYears(2)))
            throw new CheckInException(400, "DATE_OUT_OF_RANGE", "Choose a date within the last ten years or the next two.");
    }

    /** Check-in / check-out times for a record dated {@code date}: today = now, open; any other day = closed. */
    public static LocalDateTime[] timesFor(LocalDate date) {
        LocalDateTime now = LocalDateTime.now(AppClock.ZONE);
        if (date.isEqual(now.toLocalDate())) return new LocalDateTime[]{now, null};
        LocalDateTime at = LocalDateTime.of(date, LocalTime.of(now.getHour(), now.getMinute()));
        return new LocalDateTime[]{at, at};
    }

    // ══ Registration ═════════════════════════════════════════════════════════════

    public record Person(String firstName, String lastName, String gender, String relationship,
                         String phone, String email, String address1, String address2,
                         String city, String state, String zipCode) {}

    public record Registration(String kind, String groupName, List<Person> people, Long targetId, LocalDate date,
                               String interestLevel, boolean followUpRequired, String followUpNote,
                               String invitedBy, String status, String ministry, String campus) {}

    @Transactional
    public Map<String, Object> register(String clientId, String createdBy, Registration r) {
        String kind = r.kind() == null ? "INDIVIDUAL" : r.kind().toUpperCase(Locale.ROOT);
        if (!List.of("INDIVIDUAL", "FAMILY", "GROUP").contains(kind))
            throw new CheckInException(400, "BAD_KIND", "Choose Individual, Family or Group.");
        List<Person> people = r.people() == null ? List.of() : r.people();
        if (people.isEmpty()) throw new CheckInException(400, "PEOPLE_REQUIRED", "Add at least one person.");
        if (people.size() > MAX_PEOPLE) throw new CheckInException(400, "TOO_MANY", "At most " + MAX_PEOPLE + " people per registration.");
        if ("INDIVIDUAL".equals(kind) && people.size() > 1)
            throw new CheckInException(400, "INDIVIDUAL_ONE", "An individual registration has one person.");
        for (int i = 0; i < people.size(); i++) validatePerson(people.get(i), i);
        String interest = normInterest(r.interestLevel());
        LocalDate date = r.date() == null ? AppClock.today() : r.date();
        checkDateRange(date);
        AttendanceServiceType target = target(clientId, r.targetId());

        // Match or create every person first (no two people on one visitor).
        Set<Long> used = new HashSet<>();
        List<AttendanceVisitor> all = new ArrayList<>(visitorRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(clientId));
        List<AttendanceVisitor> visitors = new ArrayList<>();
        List<Boolean> matched = new ArrayList<>();
        for (Person p : people) {
            AttendanceVisitor v = match(all, p, used);
            matched.add(v != null);
            if (v == null) {
                v = new AttendanceVisitor();
                v.setClientId(clientId);
                v.setVisitCount(0);
            }
            applyPerson(v, p);
            if (r.invitedBy() != null && !r.invitedBy().isBlank()) v.setInvitedBy(trim(r.invitedBy(), 200));
            v.setUpdatedAt(LocalDateTime.now(AppClock.ZONE));
            v = visitorRepo.save(v);
            if (v.getId() != null) used.add(v.getId());
            visitors.add(v);
        }

        // Family / group party (kept when everybody already shares one).
        VisitorParty party = null;
        if (!"INDIVIDUAL".equals(kind)) {
            Long shared = visitors.get(0).getPartyId();
            boolean same = shared != null && visitors.stream().allMatch(v -> shared.equals(v.getPartyId()));
            if (same) party = partyRepo.findByIdAndClientIdAndDeleteFlagFalse(shared, clientId).orElse(null);
            if (party == null) {
                party = new VisitorParty();
                party.setClientId(clientId);
                party.setCreatedBy(createdBy);
            }
            party.setKind("FAMILY".equals(kind) ? VisitorParty.FAMILY : VisitorParty.GROUP);
            party.setGroupName(blank(r.groupName()) ? null : trim(r.groupName(), 150));
            party.setPrimaryVisitorId(visitors.get(0).getId());
            party = partyRepo.save(party);
            for (int i = 0; i < visitors.size(); i++) {
                AttendanceVisitor v = visitors.get(i);
                v.setPartyId(party.getId());
                if ("FAMILY".equals(kind)) {
                    String rel = normRelationship(people.get(i).relationship());
                    v.setRelationship(i == 0 && rel == null ? "PRIMARY" : rel);
                }
                visitorRepo.save(v);
            }
        }

        // One record per person for this date + service (never a duplicate).
        LocalDateTime[] times = timesFor(date);
        List<Map<String, Object>> results = new ArrayList<>();
        List<AttendanceRecord> records = new ArrayList<>();
        for (int i = 0; i < visitors.size(); i++) {
            AttendanceVisitor v = visitors.get(i);
            Optional<AttendanceRecord> existing = recordRepo
                    .findFirstByClientIdAndAttendanceDateAndServiceTypeAndVisitorIdAndDeleteFlagFalse(clientId, date, target.getName(), v.getId());
            boolean already = existing.isPresent();
            AttendanceRecord a = existing.orElseGet(AttendanceRecord::new);
            if (!already) {
                a.setClientId(clientId);
                a.setAttendanceDate(date);
                a.setCheckInTime(times[0]);
                a.setCheckOutTime(times[1]);
                a.setPersonType("VISITOR");
                a.setVisitorId(v.getId());
                a.setServiceType(target.getName());
                a.setCheckInMethod("manual");
                a.setCreatedBy(createdBy);
                v.setVisitCount(v.getVisitCount() + 1);
                if (v.getFirstVisitDate() == null || date.isBefore(v.getFirstVisitDate())) v.setFirstVisitDate(date);
                if (v.getLastVisitDate() == null || date.isAfter(v.getLastVisitDate())) v.setLastVisitDate(date);
            }
            a.setPersonName(v.getName());
            a.setServiceTypeId(target.getId());
            a.setPartyId(v.getPartyId());
            a.setMinistry(blank(r.ministry()) ? a.getMinistry() : trim(r.ministry(), 100));
            a.setCampus(blank(r.campus()) ? a.getCampus() : trim(r.campus(), 100));
            a.setStatus(normStatus(r.status()));
            a.setInterestLevel(interest);
            a.setFollowUpRequired(r.followUpRequired());
            a.setVisitNote(blank(r.followUpNote()) ? null : r.followUpNote().trim());
            a = recordRepo.save(a);
            records.add(a);
            // the latest visit's values go on the visitor (D6)
            if (isLatestVisit(clientId, v, a)) {
                v.setInterestLevel(interest);
                v.setFollowUpRequired(r.followUpRequired());
                v.setNote(a.getVisitNote());
            }
            visitorRepo.save(v);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("visitorId", v.getId());
            m.put("name", v.getName());
            m.put("matchedExisting", matched.get(i));
            m.put("alreadyCheckedIn", already);
            m.put("recordId", a.getId());
            m.put("visitCount", v.getVisitCount());
            m.put("classification", classify(v.getVisitCount()));
            results.add(m);
        }

        Long followUpId = null;
        if (r.followUpRequired()) {
            FollowUp f = upsertFollowUp(clientId, createdBy, visitors.get(0), party, target, date, r.followUpNote(), visitors);
            followUpId = f.getId();
            for (AttendanceRecord a : records) { a.setFollowUpId(followUpId); recordRepo.save(a); }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("kind", kind);
        out.put("partyId", party == null ? null : party.getId());
        out.put("target", targetMap(target, whenOf(target, AppClock.today())));
        out.put("date", date.toString());
        out.put("checkedOut", times[1] != null);
        out.put("visitors", results);
        out.put("followUpId", followUpId);
        return out;
    }

    private boolean isLatestVisit(String clientId, AttendanceVisitor v, AttendanceRecord a) {
        if (v.getId() == null) return true;
        List<AttendanceRecord> visits = recordRepo.findByClientIdAndVisitorIdAndDeleteFlagFalseOrderByAttendanceDateDesc(clientId, v.getId());
        return visits.isEmpty() || !visits.get(0).getAttendanceDate().isAfter(a.getAttendanceDate());
    }

    private void validatePerson(Person p, int i) {
        String who = "Person " + (i + 1);
        if (p == null || blank(p.firstName())) throw new CheckInException(400, "FIRST_NAME_REQUIRED", who + ": first name is required.");
        if (blank(p.lastName())) throw new CheckInException(400, "LAST_NAME_REQUIRED", who + ": last name is required.");
        if (!blank(p.gender()) && !List.of("M", "F").contains(p.gender().trim().toUpperCase(Locale.ROOT)))
            throw new CheckInException(400, "BAD_GENDER", who + ": gender must be Male or Female.");
        if (!blank(p.relationship()) && normRelationship(p.relationship()) == null)
            throw new CheckInException(400, "BAD_RELATIONSHIP", who + ": unknown relationship.");
        if (!blank(p.email()) && !p.email().trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new CheckInException(400, "BAD_EMAIL", who + ": the e-mail address doesn't look right.");
        if (!blank(p.phone()) && digits(p.phone()).length() < 7)
            throw new CheckInException(400, "BAD_PHONE", who + ": the phone number is too short.");
        if (!blank(p.zipCode()) && p.zipCode().trim().length() > 12)
            throw new CheckInException(400, "BAD_ZIP", who + ": ZIP code is too long.");
    }

    /** Same e-mail + first name, else same phone digits + first name; never one already used. */
    AttendanceVisitor match(List<AttendanceVisitor> all, Person p, Set<Long> used) {
        String first = p.firstName().trim().toLowerCase(Locale.ROOT);
        String email = blank(p.email()) ? null : p.email().trim().toLowerCase(Locale.ROOT);
        String phone = blank(p.phone()) ? null : digits(p.phone());
        if (email != null) {
            for (AttendanceVisitor v : all) {
                if (used.contains(v.getId()) || v.getEmail() == null) continue;
                if (email.equals(v.getEmail().trim().toLowerCase(Locale.ROOT)) && first.equals(firstOf(v))) return v;
            }
        }
        if (phone != null && phone.length() >= 7) {
            String tail = last10(phone);
            for (AttendanceVisitor v : all) {
                if (used.contains(v.getId()) || v.getPhone() == null) continue;
                if (tail.equals(last10(digits(v.getPhone()))) && first.equals(firstOf(v))) return v;
            }
        }
        return null;
    }

    private static String firstOf(AttendanceVisitor v) {
        String f = v.getFirstName();
        if (blank(f) && v.getName() != null) f = v.getName().trim().split("\\s+")[0];
        return f == null ? "" : f.trim().toLowerCase(Locale.ROOT);
    }

    private static void applyPerson(AttendanceVisitor v, Person p) {
        v.setFirstName(trim(p.firstName(), 100));
        v.setLastName(trim(p.lastName(), 100));
        v.setName(trim((p.firstName().trim() + " " + p.lastName().trim()), 150));
        if (!blank(p.gender())) v.setGender(p.gender().trim().toUpperCase(Locale.ROOT));
        if (!blank(p.phone())) v.setPhone(trim(p.phone(), 40));
        if (!blank(p.email())) v.setEmail(trim(p.email(), 150));
        if (!blank(p.address1()) || !blank(p.city()) || !blank(p.zipCode())) {
            v.setAddress1(blank(p.address1()) ? null : trim(p.address1(), 200));
            v.setAddress2(blank(p.address2()) ? null : trim(p.address2(), 200));
            v.setCity(blank(p.city()) ? null : trim(p.city(), 100));
            v.setState(blank(p.state()) ? null : trim(p.state(), 40));
            v.setZipCode(blank(p.zipCode()) ? null : trim(p.zipCode(), 12));
            v.setAddress(joinAddress(v));
        }
        if (!blank(p.relationship())) v.setRelationship(normRelationship(p.relationship()));
    }

    static String joinAddress(AttendanceVisitor v) {
        String cityLine = String.join(" ", Arrays.asList(
                v.getCity() == null ? "" : v.getCity() + (v.getState() != null || v.getZipCode() != null ? "," : ""),
                v.getState() == null ? "" : v.getState(), v.getZipCode() == null ? "" : v.getZipCode())).trim().replaceAll("\\s+", " ");
        String s = String.join(", ", Arrays.stream(new String[]{v.getAddress1(), v.getAddress2(), cityLine})
                .filter(x -> x != null && !x.isBlank()).toList());
        return s.isEmpty() ? null : trim(s, 255);
    }

    // ══ Follow-up task (D5) ══════════════════════════════════════════════════════

    FollowUp upsertFollowUp(String clientId, String createdBy, AttendanceVisitor primary, VisitorParty party,
                            AttendanceServiceType target, LocalDate date, String note, List<AttendanceVisitor> people) {
        String names = people.stream().map(AttendanceVisitor::getName).collect(Collectors.joining(", "));
        String visit = "Visited " + target.getName() + " on " + date
                + (people.size() > 1 ? " (" + names + ")" : "")
                + (blank(note) ? "" : " — " + note.trim());
        FollowUp open = null;
        for (AttendanceVisitor v : people) {
            open = followUpRepo.findByClientIdAndLinkedTypeAndLinkedIdAndDeleteFlagFalseOrderByCreatedAtDesc(clientId, LINK_VISITOR, v.getId())
                    .stream().filter(f -> "PENDING".equals(f.getStatus()) || "MISSED".equals(f.getStatus())).findFirst().orElse(null);
            if (open != null) break;
        }
        if (open != null) {
            String d = open.getDescription() == null ? "" : open.getDescription();
            if (!d.contains(visit)) open.setDescription((d.isBlank() ? "" : d + "\n") + visit);
            return followUpRepo.save(open);
        }
        FollowUp f = new FollowUp();
        f.setClientId(clientId);
        String label = party == null ? primary.getName()
                : (party.getGroupName() != null ? party.getGroupName()
                : (VisitorParty.FAMILY.equals(party.getKind()) ? primary.getLastName() + " family" : primary.getName() + "'s group"));
        f.setTitle(trim("Follow up with visitor: " + label, 255));
        f.setDescription(visit);
        f.setStatus("PENDING");
        f.setPriority("MEDIUM");
        f.setLinkedType(LINK_VISITOR);
        f.setLinkedId(primary.getId());
        f.setLinkedLabel(trim(label, 255));
        f.setCreatedBy(createdBy);
        return followUpRepo.save(f);
    }

    // ══ Visitor list / edit / delete ═════════════════════════════════════════════

    public List<Map<String, Object>> listVisitors(String clientId, String q) {
        String ql = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<AttendanceVisitor> all = visitorRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(clientId);
        Map<Long, VisitorParty> parties = partiesFor(clientId, all);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AttendanceVisitor v : all) {
            if (!ql.isEmpty() && !(has(v.getName(), ql) || has(v.getEmail(), ql) || has(v.getPhone(), ql)
                    || (v.getPhone() != null && !digits(ql).isEmpty() && digits(v.getPhone()).contains(digits(ql))))) continue;
            out.add(visitorMap(v, parties.get(v.getPartyId())));
            if (out.size() >= 200) break;
        }
        return out;
    }

    public Map<String, Object> getVisitor(String clientId, Long id) {
        AttendanceVisitor v = visitor(clientId, id);
        Map<String, Object> m = visitorMap(v, v.getPartyId() == null ? null
                : partyRepo.findByIdAndClientIdAndDeleteFlagFalse(v.getPartyId(), clientId).orElse(null));
        if (v.getPartyId() != null) {
            m.put("partyMembers", visitorRepo.findByClientIdAndPartyIdAndDeleteFlagFalse(clientId, v.getPartyId()).stream()
                    .map(o -> Map.<String, Object>of("id", o.getId(), "name", nv(o.getName()), "relationship", nv(o.getRelationship())))
                    .toList());
        }
        List<Map<String, Object>> visits = new ArrayList<>();
        for (AttendanceRecord a : recordRepo.findByClientIdAndVisitorIdAndDeleteFlagFalseOrderByAttendanceDateDesc(clientId, id)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("recordId", a.getId());
            r.put("date", a.getAttendanceDate().toString());
            r.put("serviceType", a.getServiceType());
            r.put("interestLevel", a.getInterestLevel());
            r.put("followUpRequired", Boolean.TRUE.equals(a.getFollowUpRequired()));
            r.put("note", a.getVisitNote());
            visits.add(r);
            if (visits.size() >= 50) break;
        }
        m.put("visits", visits);
        return m;
    }

    @Transactional
    public Map<String, Object> updateVisitor(String clientId, Long id, Person p, String interestLevel, Boolean followUp, String note) {
        AttendanceVisitor v = visitor(clientId, id);
        validatePerson(p, 0);
        if (!blank(p.email()) || !blank(p.phone())) {
            // never turn this visitor into a copy of another live visitor
            List<AttendanceVisitor> others = new ArrayList<>(visitorRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(clientId));
            others.removeIf(o -> o.getId().equals(id));
            AttendanceVisitor dup = match(others, p, Set.of());
            if (dup != null) throw new CheckInException(409, "VISITOR_EXISTS",
                    "Another visitor (" + dup.getName() + ") already has these details.", Map.of("existingId", dup.getId()));
        }
        applyPerson(v, p);
        if (blank(p.gender())) v.setGender(null);
        if (blank(p.relationship())) v.setRelationship(null);
        if (blank(p.phone())) v.setPhone(null);
        if (blank(p.email())) v.setEmail(null);
        if (blank(p.address1()) && blank(p.city()) && blank(p.zipCode())) {
            v.setAddress1(null); v.setAddress2(null); v.setCity(null); v.setState(null); v.setZipCode(null);
            v.setAddress(null);
        }
        if (interestLevel != null) v.setInterestLevel(blank(interestLevel) ? null : normInterest(interestLevel));
        if (followUp != null) v.setFollowUpRequired(followUp);
        if (note != null) v.setNote(blank(note) ? null : note.trim());
        v.setUpdatedAt(LocalDateTime.now(AppClock.ZONE));
        visitorRepo.save(v);
        return getVisitor(clientId, id);
    }

    /** Soft delete: the visitor disappears from lists; attendance history and follow-ups are kept. */
    @Transactional
    public void deleteVisitor(String clientId, Long id) {
        AttendanceVisitor v = visitor(clientId, id);
        v.setDeleteFlag(true);
        v.setUpdatedAt(LocalDateTime.now(AppClock.ZONE));
        visitorRepo.save(v);
        if (v.getPartyId() != null) {
            partyRepo.findByIdAndClientIdAndDeleteFlagFalse(v.getPartyId(), clientId).ifPresent(party -> {
                List<AttendanceVisitor> left = visitorRepo.findByClientIdAndPartyIdAndDeleteFlagFalse(clientId, party.getId());
                if (left.isEmpty()) party.setDeleteFlag(true);
                else if (id.equals(party.getPrimaryVisitorId())) party.setPrimaryVisitorId(left.get(0).getId());
                partyRepo.save(party);
            });
        }
    }

    private AttendanceVisitor visitor(String clientId, Long id) {
        return visitorRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId)
                .orElseThrow(() -> new CheckInException(404, "VISITOR_NOT_FOUND", "Visitor not found."));
    }

    private Map<Long, VisitorParty> partiesFor(String clientId, Collection<AttendanceVisitor> vs) {
        Set<Long> ids = vs.stream().map(AttendanceVisitor::getPartyId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return partyRepo.findByClientIdAndIdInAndDeleteFlagFalse(clientId, ids).stream()
                .collect(Collectors.toMap(VisitorParty::getId, p -> p));
    }

    public Map<String, Object> visitorMap(AttendanceVisitor v, VisitorParty party) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("name", v.getName());
        m.put("firstName", v.getFirstName() != null ? v.getFirstName() : firstWord(v.getName()));
        m.put("lastName", v.getLastName() != null ? v.getLastName() : restWords(v.getName()));
        m.put("gender", v.getGender());
        m.put("phone", v.getPhone());
        m.put("email", v.getEmail());
        m.put("address1", v.getAddress1() != null ? v.getAddress1() : (v.getCity() == null ? v.getAddress() : null));
        m.put("address2", v.getAddress2());
        m.put("city", v.getCity());
        m.put("state", v.getState());
        m.put("zipCode", v.getZipCode());
        m.put("address", v.getAddress());
        m.put("invitedBy", v.getInvitedBy());
        m.put("relationship", v.getRelationship());
        m.put("interestLevel", v.getInterestLevel());
        m.put("followUpRequired", Boolean.TRUE.equals(v.getFollowUpRequired()));
        m.put("note", v.getNote());
        m.put("firstVisit", v.getFirstVisitDate() == null ? null : v.getFirstVisitDate().toString());
        m.put("lastVisit", v.getLastVisitDate() == null ? null : v.getLastVisitDate().toString());
        m.put("visits", v.getVisitCount());
        m.put("classification", classify(v.getVisitCount()));
        m.put("partyId", v.getPartyId());
        m.put("partyKind", party == null ? null : party.getKind());
        m.put("partyName", party == null ? null : partyLabel(party, v));
        return m;
    }

    private String partyLabel(VisitorParty p, AttendanceVisitor anyone) {
        if (p.getGroupName() != null) return p.getGroupName();
        if (VisitorParty.FAMILY.equals(p.getKind()))
            return (anyone.getLastName() != null ? anyone.getLastName() : restWords(anyone.getName())) + " family";
        return "Group";
    }

    // ══ Reports ══════════════════════════════════════════════════════════════════

    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : AppClock.today();
        LocalDate f = from != null ? from : t.minusMonths(3);
        if (f.isAfter(t)) throw new CheckInException(400, "BAD_RANGE", "The start date is after the end date.");
        if (f.isBefore(t.minusYears(5))) throw new CheckInException(400, "RANGE_TOO_LONG", "Choose at most five years.");
        return new LocalDate[]{f, t};
    }

    private static boolean onTarget(AttendanceRecord a, AttendanceServiceType target) {
        if (target == null) return true;
        if (a.getServiceTypeId() != null) return target.getId().equals(a.getServiceTypeId());
        return !isEvent(target) && target.getName().equals(a.getServiceType());
    }

    /** 1. Everybody who attended (members and visitors) in the range, optionally one service/event. */
    public Map<String, Object> attendeeReport(String clientId, LocalDate from, LocalDate to, Long targetId) {
        LocalDate[] r = range(from, to);
        AttendanceServiceType target = targetId == null ? null : target(clientId, targetId);
        List<Map<String, Object>> rows = new ArrayList<>();
        int members = 0, visitors = 0;
        for (AttendanceRecord a : recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, r[0], r[1])) {
            if (!onTarget(a, target)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", a.getAttendanceDate().toString());
            m.put("name", a.getPersonName());
            m.put("personType", a.getPersonType());
            m.put("serviceType", a.getServiceType());
            m.put("status", a.getStatus());
            rows.add(m);
            if ("VISITOR".equals(a.getPersonType())) visitors++; else members++;
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("date"))).reversed()
                .thenComparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
        Map<String, Object> out = header("Attendee list", r, target);
        out.put("totals", Map.of("records", rows.size(), "members", members, "visitors", visitors));
        out.put("rows", rows);
        return out;
    }

    private record VisitStats(int visits, LocalDate first, LocalDate last, AttendanceRecord latest) {}

    private Map<Long, VisitStats> visitorStats(String clientId, LocalDate[] r, AttendanceServiceType target) {
        Map<Long, VisitStats> out = new HashMap<>();
        for (AttendanceRecord a : recordRepo.findByClientIdAndPersonTypeAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, "VISITOR", r[0], r[1])) {
            if (a.getVisitorId() == null || !onTarget(a, target)) continue;
            VisitStats s = out.get(a.getVisitorId());
            LocalDate d = a.getAttendanceDate();
            if (s == null) out.put(a.getVisitorId(), new VisitStats(1, d, d, a));
            else out.put(a.getVisitorId(), new VisitStats(s.visits() + 1, d.isBefore(s.first()) ? d : s.first(),
                    d.isAfter(s.last()) ? d : s.last(), latestOf(s.latest(), a)));
        }
        return out;
    }

    private static AttendanceRecord latestOf(AttendanceRecord x, AttendanceRecord y) {
        int c = y.getAttendanceDate().compareTo(x.getAttendanceDate());
        if (c != 0) return c > 0 ? y : x;
        return (y.getId() != null && x.getId() != null && y.getId() > x.getId()) ? y : x;
    }

    private List<AttendanceVisitor> visitorsById(String clientId, Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return visitorRepo.findByClientIdAndIdInAndDeleteFlagFalse(clientId, ids);
    }

    /** 2. Visitors seen in the range: contact, visits in range, first/last visit, classification. */
    public Map<String, Object> visitorListReport(String clientId, LocalDate from, LocalDate to, Long targetId) {
        LocalDate[] r = range(from, to);
        AttendanceServiceType target = targetId == null ? null : target(clientId, targetId);
        Map<Long, VisitStats> stats = visitorStats(clientId, r, target);
        List<AttendanceVisitor> vs = visitorsById(clientId, stats.keySet());
        Map<Long, VisitorParty> parties = partiesFor(clientId, vs);
        List<Map<String, Object>> rows = new ArrayList<>();
        int first = 0;
        for (AttendanceVisitor v : vs) {
            VisitStats s = stats.get(v.getId());
            Map<String, Object> m = visitorMap(v, parties.get(v.getPartyId()));
            m.put("visitsInRange", s.visits());
            m.put("firstInRange", s.first().toString());
            m.put("lastInRange", s.last().toString());
            if (v.getFirstVisitDate() != null && !v.getFirstVisitDate().isBefore(r[0])) first++;
            rows.add(m);
        }
        rows.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
        Map<String, Object> out = header("Visitor report", r, target);
        out.put("totals", Map.of("visitors", rows.size(), "firstTimeInRange", first,
                "visits", stats.values().stream().mapToInt(VisitStats::visits).sum()));
        out.put("rows", rows);
        return out;
    }

    /** 3. Interest: each visitor's latest answer in the range, counted per level. */
    public Map<String, Object> interestReport(String clientId, LocalDate from, LocalDate to, Long targetId) {
        LocalDate[] r = range(from, to);
        AttendanceServiceType target = targetId == null ? null : target(clientId, targetId);
        Map<Long, VisitStats> stats = visitorStats(clientId, r, target);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : INTEREST) counts.put(k, 0);
        counts.put("NOT_ASKED", 0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AttendanceVisitor v : visitorsById(clientId, stats.keySet())) {
            VisitStats s = stats.get(v.getId());
            String lvl = s.latest().getInterestLevel() == null ? "NOT_ASKED" : s.latest().getInterestLevel();
            counts.merge(lvl, 1, Integer::sum);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId()); m.put("name", v.getName()); m.put("phone", v.getPhone()); m.put("email", v.getEmail());
            m.put("interestLevel", lvl); m.put("lastVisit", s.last().toString()); m.put("serviceType", s.latest().getServiceType());
            rows.add(m);
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("interestLevel")))
                .thenComparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
        Map<String, Object> out = header("Interest report", r, target);
        out.put("counts", counts);
        out.put("rows", rows);
        return out;
    }

    /** 4. Follow-up: visits in the range marked "follow-up required", with the task's status. */
    public Map<String, Object> followUpReport(String clientId, LocalDate from, LocalDate to, Long targetId) {
        LocalDate[] r = range(from, to);
        AttendanceServiceType target = targetId == null ? null : target(clientId, targetId);
        Map<Long, AttendanceRecord> latest = new HashMap<>();
        for (AttendanceRecord a : recordRepo.findByClientIdAndPersonTypeAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, "VISITOR", r[0], r[1])) {
            if (a.getVisitorId() == null || !Boolean.TRUE.equals(a.getFollowUpRequired()) || !onTarget(a, target)) continue;
            latest.merge(a.getVisitorId(), a, VisitorCheckInService::latestOf);
        }
        Map<Long, FollowUp> tasks = new HashMap<>();
        for (AttendanceRecord a : latest.values()) {
            if (a.getFollowUpId() != null && !tasks.containsKey(a.getFollowUpId()))
                followUpRepo.findByIdAndClientIdAndDeleteFlagFalse(a.getFollowUpId(), clientId).ifPresent(f -> tasks.put(f.getId(), f));
        }
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AttendanceVisitor v : visitorsById(clientId, latest.keySet())) {
            AttendanceRecord a = latest.get(v.getId());
            FollowUp f = a.getFollowUpId() == null ? null : tasks.get(a.getFollowUpId());
            String st = f == null ? "NO_TASK" : f.getStatus();
            byStatus.merge(st, 1, Integer::sum);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId()); m.put("name", v.getName()); m.put("phone", v.getPhone()); m.put("email", v.getEmail());
            m.put("visitDate", a.getAttendanceDate().toString()); m.put("serviceType", a.getServiceType());
            m.put("note", a.getVisitNote()); m.put("followUpId", f == null ? null : f.getId());
            m.put("taskStatus", st); m.put("assignedTo", f == null ? null : f.getAssignedTo());
            rows.add(m);
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("visitDate"))).reversed());
        Map<String, Object> out = header("Follow-up report", r, target);
        out.put("byStatus", byStatus);
        out.put("rows", rows);
        return out;
    }

    /** 5. Families and groups that visited in the range, with their people. */
    public Map<String, Object> partyReport(String clientId, LocalDate from, LocalDate to, Long targetId) {
        LocalDate[] r = range(from, to);
        AttendanceServiceType target = targetId == null ? null : target(clientId, targetId);
        Map<Long, VisitStats> stats = visitorStats(clientId, r, target);
        Map<Long, List<AttendanceVisitor>> byParty = new LinkedHashMap<>();
        for (AttendanceVisitor v : visitorsById(clientId, stats.keySet()))
            if (v.getPartyId() != null) byParty.computeIfAbsent(v.getPartyId(), k -> new ArrayList<>()).add(v);
        Map<Long, VisitorParty> parties = byParty.isEmpty() ? Map.of()
                : partyRepo.findByClientIdAndIdInAndDeleteFlagFalse(clientId, byParty.keySet()).stream()
                .collect(Collectors.toMap(VisitorParty::getId, p -> p));
        List<Map<String, Object>> rows = new ArrayList<>();
        int families = 0, groups = 0;
        for (var e : byParty.entrySet()) {
            VisitorParty p = parties.get(e.getKey());
            if (p == null) continue;
            List<AttendanceVisitor> everyone = visitorRepo.findByClientIdAndPartyIdAndDeleteFlagFalse(clientId, p.getId());
            everyone.sort(Comparator.comparing((AttendanceVisitor v) -> !v.getId().equals(p.getPrimaryVisitorId()))
                    .thenComparing(v -> nv(v.getName()).toLowerCase(Locale.ROOT)));
            AttendanceVisitor primary = everyone.isEmpty() ? e.getValue().get(0) : everyone.get(0);
            LocalDate last = e.getValue().stream().map(v -> stats.get(v.getId()).last()).max(LocalDate::compareTo).orElse(null);
            int visits = e.getValue().stream().mapToInt(v -> stats.get(v.getId()).visits()).sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partyId", p.getId());
            m.put("kind", p.getKind());
            m.put("name", partyLabel(p, primary));
            m.put("primary", primary.getName());
            m.put("phone", primary.getPhone());
            m.put("email", primary.getEmail());
            m.put("people", everyone.stream().map(v -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", v.getId()); pm.put("name", v.getName()); pm.put("relationship", v.getRelationship());
                pm.put("visitedInRange", stats.containsKey(v.getId()));
                return pm;
            }).toList());
            m.put("visitsInRange", visits);
            m.put("lastVisit", last == null ? null : last.toString());
            rows.add(m);
            if (VisitorParty.FAMILY.equals(p.getKind())) families++; else groups++;
        }
        rows.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
        Map<String, Object> out = header("Family & group report", r, target);
        out.put("totals", Map.of("families", families, "groups", groups));
        out.put("rows", rows);
        return out;
    }

    private Map<String, Object> header(String title, LocalDate[] r, AttendanceServiceType target) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("from", r[0].toString());
        out.put("to", r[1].toString());
        out.put("target", target == null ? null : targetMap(target, whenOf(target, AppClock.today())));
        out.put("generatedAt", LocalDateTime.now(AppClock.ZONE).withNano(0).toString());
        return out;
    }

    // ══ helpers ══════════════════════════════════════════════════════════════════

    public static String classify(int visits) {
        return visits <= 1 ? "First Visit" : (visits == 2 ? "Second Visit" : "Returning Visitor");
    }

    static String normInterest(String s) {
        if (blank(s)) return null;
        String k = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (!INTEREST.contains(k)) throw new CheckInException(400, "BAD_INTEREST", "Choose Interested, Not Sure or Not Interested.");
        return k;
    }

    static String normRelationship(String s) {
        if (blank(s)) return null;
        String k = s.trim().toUpperCase(Locale.ROOT);
        return RELATIONSHIPS.contains(k) ? k : null;
    }

    static String normStatus(String s) {
        if (blank(s)) return "PRESENT";
        String k = s.trim().toUpperCase(Locale.ROOT);
        return AttendanceService.STATUSES.contains(k) ? k : "PRESENT";
    }

    static String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }
    private static String last10(String d) { return d.length() > 10 ? d.substring(d.length() - 10) : d; }
    static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String trim(String s, int max) { String t = s.trim(); return t.length() > max ? t.substring(0, max) : t; }
    private static String nv(String s) { return s == null ? "" : s; }
    private static boolean has(String s, String ql) { return s != null && s.toLowerCase(Locale.ROOT).contains(ql); }
    private static String firstWord(String n) { return n == null ? null : n.trim().split("\\s+")[0]; }
    private static String restWords(String n) {
        if (n == null) return null;
        String[] p = n.trim().split("\\s+", 2);
        return p.length > 1 ? p[1] : "";
    }
}
