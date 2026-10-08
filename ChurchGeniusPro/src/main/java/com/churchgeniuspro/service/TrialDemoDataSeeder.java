package com.churchgeniuspro.service;

import com.churchgeniuspro.common.States;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Sample data for the screens a new trial (or demo) tenant would otherwise show
 * empty: Donations, Membership Requests, Attendance, Follow-Ups → Connect With Us
 * and Prayer Requests, and the Unsubscribed List.
 *
 * <p>Called from {@link TestDataService#provisionTenant} — the single provisioning
 * path — so it only ever runs for a brand-new {@code TRIAL-}/{@code DEMO-} tenant,
 * inside that tenant's provisioning transaction. Every row carries the new
 * tenant's id; nothing here reads or writes another church.
 *
 * <p><b>Idempotent per area:</b> each block first checks whether the tenant already
 * has rows of that kind and skips if so, so running it twice for one tenant can
 * never duplicate data.
 *
 * <p><b>Fictional only:</b> names are invented, e-mail addresses use the reserved
 * {@code .test} domain, phone numbers use the 555-01xx fictional range, and card
 * labels use payment-processor test-card endings. No real person, account or
 * card is represented.
 *
 * <p>Dates are relative to {@code today} (the creation date) — submissions in the
 * days before it, attendance on the Sundays/Wednesdays before it.
 */
@Component
public class TrialDemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(TrialDemoDataSeeder.class);

    /** Domain for every invented address — reserved by RFC 2606, never deliverable. */
    static String domain(String clientId) { return clientId.toLowerCase() + ".test"; }

    private final DonationRepository                donationRepo;
    private final MembershipFamilyRepository        membershipFamilyRepo;
    private final MembershipFamilyMemberRepository  membershipMemberRepo;
    private final AttendanceRecordRepository        attendanceRepo;
    private final AttendanceVisitorRepository       visitorRepo;
    private final ConnectSubmissionRepository       connectRepo;
    private final PublicPrayerRequestRepository     prayerRepo;
    private final FollowUpRepository                followUpRepo;
    private final EmailUnsubscribeRepository        unsubscribeRepo;
    private final FamilyRepository                  familyRepo;
    private final FamilyMemberRepository            familyMemberRepo;

    /** Used only to back-date creation timestamps the entities force to "now". Absent in unit tests. */
    @PersistenceContext
    private EntityManager em;

    public TrialDemoDataSeeder(DonationRepository donationRepo,
                               MembershipFamilyRepository membershipFamilyRepo,
                               MembershipFamilyMemberRepository membershipMemberRepo,
                               AttendanceRecordRepository attendanceRepo,
                               AttendanceVisitorRepository visitorRepo,
                               ConnectSubmissionRepository connectRepo,
                               PublicPrayerRequestRepository prayerRepo,
                               FollowUpRepository followUpRepo,
                               EmailUnsubscribeRepository unsubscribeRepo,
                               FamilyRepository familyRepo,
                               FamilyMemberRepository familyMemberRepo) {
        this.donationRepo         = donationRepo;
        this.membershipFamilyRepo = membershipFamilyRepo;
        this.membershipMemberRepo = membershipMemberRepo;
        this.attendanceRepo       = attendanceRepo;
        this.visitorRepo          = visitorRepo;
        this.connectRepo          = connectRepo;
        this.prayerRepo           = prayerRepo;
        this.followUpRepo         = followUpRepo;
        this.unsubscribeRepo      = unsubscribeRepo;
        this.familyRepo           = familyRepo;
        this.familyMemberRepo     = familyMemberRepo;
    }

    /**
     * Seeds every area for {@code clientId}.
     *
     * @param members the tenant's demo members (families seeded just before)
     * @param today   the tenant's creation date; all dates are relative to it
     * @return per-area row counts (0 where an area was skipped as already present)
     */
    public Map<String, Integer> seed(String clientId, List<FamilyMember> members, LocalDate today) {
        if (!TestDataService.isManagedTenant(clientId)) {
            // Belt and braces: provisioning only ever passes a TRIAL-/DEMO- id.
            throw new IllegalArgumentException("Demo data is only seeded into trial/demo tenants: " + clientId);
        }
        Random rng = new Random(clientId.hashCode() * 31L + 7);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("donations",          seedDonations(clientId, members, today));
        counts.put("membershipRequests", seedMembershipRequests(clientId, today));
        counts.put("attendance",         seedAttendance(clientId, members, today, rng));
        counts.put("connectSubmissions", seedConnect(clientId, members, today));
        counts.put("prayerRequests",     seedPrayer(clientId, today));
        counts.put("unsubscribed",       seedUnsubscribed(clientId, members, today));
        log.info("TrialDemoDataSeeder: {} → {}", clientId, counts);
        return counts;
    }

    /**
     * Which areas {@link #seed} WOULD fill for this tenant right now — true where the
     * tenant has no rows of that kind. Read-only; uses the very same checks as the
     * seeding, so a preview can never disagree with what a run then does.
     */
    public Map<String, Boolean> wouldFill(String clientId, LocalDate today) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("donations",          !hasDonations(clientId));
        m.put("membershipRequests", !hasMembershipRequests(clientId));
        m.put("attendance",         !hasAttendance(clientId, today));
        m.put("connectSubmissions", !hasConnect(clientId));
        m.put("prayerRequests",     !hasPrayer(clientId));
        m.put("unsubscribed",       !hasUnsubscribed(clientId));
        return m;
    }

    // Any existing row of the kind — the church's own or earlier demo data — means
    // "leave this area alone". Deleted rows count too where the repository exposes no
    // filter, which errs on the side of not adding.
    boolean hasDonations(String c)          { return !donationRepo.findByClientIdOrderByDonatedAtDesc(c).isEmpty(); }
    boolean hasMembershipRequests(String c) { return !membershipFamilyRepo.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(c).isEmpty(); }
    boolean hasAttendance(String c, LocalDate today) {
        return !attendanceRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(c, today.minusYears(5), today.plusYears(1)).isEmpty();
    }
    boolean hasConnect(String c)            { return !connectRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(c).isEmpty(); }
    boolean hasPrayer(String c)             { return !prayerRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(c).isEmpty(); }
    boolean hasUnsubscribed(String c)       { return !unsubscribeRepo.findByClientIdOrderByUnsubscribedAtDesc(c).isEmpty(); }

    // ── Donations (/donation-review) ──────────────────────────────────────

    private record Gift(String first, String last, String method, int amount, boolean coverFee, String note) {}

    int seedDonations(String clientId, List<FamilyMember> members, LocalDate today) {
        if (hasDonations(clientId)) return 0;
        List<FamilyMember> adults = adults(members);
        List<Gift> gifts = new ArrayList<>(List.of(
                new Gift(null, null, "Visa •••• 4242",       150, false, "Tithe"),
                new Gift(null, null, "Mastercard •••• 4444",  75, true,  null),
                new Gift("Grace", "Whitfield", "Visa •••• 4242", 250, true, "For the building fund"),
                new Gift(null, null, "Bank Transfer",                            500, false, "Monthly tithe"),
                new Gift("Daniel", "Okafor", "Amex •••• 0005",  40, false, "Youth retreat"),
                new Gift(null, null, "Visa •••• 4242",        100, true,  null),
                new Gift("Helen", "Marsh", "Mastercard •••• 4444", 60, false, "Missions"),
                new Gift(null, null, "Bank Transfer",                            320, false, null)));
        int i = 0, created = 0;
        for (Gift g : gifts) {
            Donation d = new Donation();
            d.setClientId(clientId);
            String first = g.first(), last = g.last(), email, phone;
            if (first == null && !adults.isEmpty()) {           // a member giving online
                FamilyMember m = adults.get(i % adults.size());
                first = m.getFirstName(); last = m.getLastName(); email = m.getEmail(); phone = m.getPhone();
            } else {                                            // a guest donor
                if (first == null) { first = "Guest"; last = "Donor " + (i + 1); }
                email = (first + "." + last).toLowerCase().replace(' ', '.') + "@" + domain(clientId);
                phone = "913-555-01" + String.format("%02d", 40 + i);
            }
            d.setFirstName(first); d.setLastName(last); d.setEmail(email); d.setPhone(phone);
            BigDecimal gift = BigDecimal.valueOf(g.amount()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal fee  = g.coverFee()
                    ? gift.multiply(new BigDecimal("0.029")).add(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2);
            d.setIntendedAmount(gift);
            d.setFeeCovered(fee);
            d.setAmount(gift.add(fee));
            d.setCurrency("USD");
            d.setStatus("succeeded");
            d.setPaymentMethod(g.method());
            d.setNote(g.note());
            d.setDonatedAt(today.minusDays(2L + i * 6L).atTime(LocalTime.of(9 + (i % 9), (i * 17) % 60)));
            donationRepo.save(d);
            created++; i++;
        }
        return created;
    }

    // ── Membership Requests — pending applications (/membershipRequests) ───

    private record Applicant(String role, String first, String last, String gender, int age) {}

    int seedMembershipRequests(String clientId, LocalDate today) {
        if (hasMembershipRequests(clientId)) return 0;
        String ks = String.valueOf(States.getCodeByAbbreviation("KS"));
        List<List<Applicant>> families = List.of(
                List.of(new Applicant("Head", "Marcus", "Bennett", "Male", 41),
                        new Applicant("Wife", "Alicia", "Bennett", "Female", 38),
                        new Applicant("Son", "Caleb", "Bennett", "Male", 10)),
                List.of(new Applicant("Head", "Naomi", "Castillo", "Female", 29)),
                List.of(new Applicant("Head", "Peter", "Lindqvist", "Male", 57),
                        new Applicant("Wife", "Ruth", "Lindqvist", "Female", 55)));
        String[] streets = { "418 Maple Ridge Dr", "77 Cedar Court, Apt 3B", "1290 Willow Bend Ln" };
        int created = 0;
        for (int f = 0; f < families.size(); f++) {
            MembershipFamily mf = new MembershipFamily();
            mf.setAppClientId(clientId);
            mf.setDeclarationAccepted(true);
            mf = membershipFamilyRepo.save(mf);
            int n = 0;
            for (Applicant a : families.get(f)) {
                MembershipFamilyMember m = new MembershipFamilyMember();
                m.setMembershipFamily(mf);
                m.setAppClientId(clientId);
                m.setRole(a.role()); m.setFirstName(a.first()); m.setLastName(a.last()); m.setGender(a.gender());
                LocalDate bday = today.minusYears(a.age()).minusDays(37L * (n + 1) + f * 11L);
                m.setBirthdayMonth(bday.getMonthValue()); m.setBirthdayDay(bday.getDayOfMonth()); m.setBirthdayYear(bday.getYear());
                if (!"Son".equals(a.role())) {
                    m.setEmail((a.first() + "." + a.last()).toLowerCase() + "@" + domain(clientId));
                    m.setPhone("913-555-01" + String.format("%02d", 60 + f * 3 + n));
                }
                if ("Head".equals(a.role())) {
                    m.setAddress1(streets[f]); m.setCity("Olathe"); m.setState(ks); m.setCountry("USA"); m.setPinCode("66061");
                    m.setSameAsFamilyAddress(false);
                    m.setComments(f == 0 ? "We moved here this summer and have been visiting for a few weeks."
                                         : f == 2 ? "Transferring our membership from our previous church." : null);
                }
                membershipMemberRepo.save(m);
                n++;
            }
            backdate("membership_family", "created_date", "app_client_id", mf.getId(), clientId,
                     today.minusDays(1L + f * 4L).atTime(19, 5 + f * 9));
            created++;
        }
        return created;
    }

    // ── Attendance (/attendance) ──────────────────────────────────────────

    int seedAttendance(String clientId, List<FamilyMember> members, LocalDate today, Random rng) {
        if (hasAttendance(clientId, today)) return 0;
        int created = 0;
        // Sunday Worship — the six Sundays before the creation date.
        LocalDate sunday = today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        for (int w = 0; w < 6; w++) {
            LocalDate date = sunday.minusWeeks(w);
            for (FamilyMember m : members) {
                int roll = rng.nextInt(100);
                if (roll >= 72) continue;                                 // ~72% attend
                String status = roll < 6 ? "LATE" : "PRESENT";
                LocalTime t = "LATE".equals(status) ? LocalTime.of(10, 10 + rng.nextInt(15)) : LocalTime.of(9, 35 + rng.nextInt(24));
                attendanceRepo.save(record(clientId, date, t, m.getId(), null, name(m), "Sunday Worship",
                        rng.nextInt(4) == 0 ? "qr" : "manual", status));
                created++;
            }
        }
        // Bible Study — the four Wednesdays before, a smaller adult group.
        List<FamilyMember> adults = adults(members);
        LocalDate wed = today.with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY));
        for (int w = 0; w < 4; w++) {
            for (int a = 0; a < Math.min(5, adults.size()); a++) {
                if (rng.nextInt(10) < 2) continue;
                FamilyMember m = adults.get(a);
                attendanceRepo.save(record(clientId, wed.minusWeeks(w), LocalTime.of(18, 50 + rng.nextInt(9)),
                        m.getId(), null, name(m), "Bible Study", "manual", "PRESENT"));
                created++;
            }
        }
        // Visitors — one returning (two visits), one first-timer last Sunday.
        String[][] visitors = { { "Samuel Ortiz", "913-555-0181", "Invited by a friend" },
                                { "Leah Nakamura", "913-555-0182", "Found us online" } };
        for (int v = 0; v < visitors.length; v++) {
            List<LocalDate> visits = v == 0 ? List.of(sunday.minusWeeks(2), sunday) : List.of(sunday);
            AttendanceVisitor av = new AttendanceVisitor();
            av.setClientId(clientId);
            av.setName(visitors[v][0]);
            av.setPhone(visitors[v][1]);
            av.setEmail(visitors[v][0].toLowerCase().replace(' ', '.') + "@" + domain(clientId));
            av.setInvitedBy(visitors[v][2]);
            av.setFirstVisitDate(visits.get(0));
            av.setLastVisitDate(visits.get(visits.size() - 1));
            av.setVisitCount(visits.size());
            av = visitorRepo.save(av);
            for (LocalDate d : visits) {
                attendanceRepo.save(record(clientId, d, LocalTime.of(9, 50), null, av.getId(), av.getName(),
                        "Sunday Worship", "manual", "PRESENT"));
                created++;
            }
        }
        return created;
    }

    private static AttendanceRecord record(String clientId, LocalDate date, LocalTime time, Integer memberId,
                                           Long visitorId, String name, String service, String method, String status) {
        AttendanceRecord r = new AttendanceRecord();
        r.setClientId(clientId);
        r.setAttendanceDate(date);
        r.setCheckInTime(date.atTime(time));
        r.setPersonType(memberId != null ? "MEMBER" : "VISITOR");
        r.setFamilyMemberId(memberId);
        r.setVisitorId(visitorId);
        r.setPersonName(name);
        r.setServiceType(service);
        r.setCheckInMethod(method);
        r.setStatus(status);
        r.setCreatedBy("demo");
        r.setCreatedDate(date.atTime(time));
        r.setDeleteFlag(false);
        return r;
    }

    // ── Follow-Ups → Connect With Us ──────────────────────────────────────

    private record Visitor(String first, String last, String gender, String marital, String prefs,
                           String howHeard, String status, int daysAgo, boolean assign) {}

    int seedConnect(String clientId, List<FamilyMember> members, LocalDate today) {
        if (hasConnect(clientId)) return 0;
        List<FamilyMember> adults = adults(members);
        List<Visitor> visitors = List.of(
                new Visitor("Jordan", "Hale",     "Male",   "Single",  "Email, Text", "A coworker invited me",           "New",        1, false),
                new Visitor("Priscilla", "Moreau", "Female", "Married", "Phone",       "Drove past the church",           "Assigned",   4, true),
                new Visitor("Tobias", "Grant",    "Male",   "Married", "Email",       "Found your website",              "Contacted",  9, true),
                new Visitor("Imani", "Brooks-Reed", "Female", "Single", "Text",        "Came to the community picnic",    "InProgress", 6, true));
        int created = 0, i = 0;
        for (Visitor v : visitors) {
            String email = (v.first() + "." + v.last()).toLowerCase().replace(' ', '.') + "@" + domain(clientId);
            String phone = "913-555-01" + String.format("%02d", 20 + i);

            // The real Connect form also files the person as a Visitor; do the same.
            Family fam = new Family();
            fam.setAppClientId(clientId); fam.setInactive(false); fam.setDeleteFlag(false);
            fam = familyRepo.save(fam);
            FamilyMember fm = new FamilyMember();
            fm.setFamily(fam); fm.setAppClientId(clientId); fm.setRole("Head"); fm.setMemberType("Visitor");
            fm.setFirstName(v.first()); fm.setLastName(v.last()); fm.setEmail(email); fm.setPhone(phone);
            fm.setGender(v.gender()); fm.setInactive(false); fm.setIncludeContributions(false); fm.setDeleteFlag(false);
            fm = familyMemberRepo.save(fm);

            ConnectSubmission s = new ConnectSubmission();
            s.setClientId(clientId);
            s.setMemberId(fm.getId());
            s.setFirstName(v.first()); s.setLastName(v.last()); s.setEmail(email); s.setPhone(phone);
            s.setAddress((210 + i * 37) + " Prairie View Rd, Olathe, KS 66062");
            s.setGender(v.gender()); s.setMaritalStatus(v.marital());
            s.setContactPreferences(v.prefs()); s.setHowHeard(v.howHeard());
            s.setStatus(v.status());
            if (v.assign() && !adults.isEmpty()) {
                FamilyMember who = adults.get(i % adults.size());
                s.setAssignedMemberId(who.getId());
                s.setAssignedTo(name(who));
            }
            // Same encoding as PublicEngagementService.sendConfirmation ("EMAIL", "EMAIL+SMS", "NONE").
            List<String> sent = new ArrayList<>();
            if (v.prefs().contains("Email")) sent.add("EMAIL");
            if (v.prefs().contains("Text"))  sent.add("SMS");
            s.setConfirmationSent(sent.isEmpty() ? "NONE" : String.join("+", sent));
            s = connectRepo.save(s);

            String full = v.first() + " " + v.last();
            FollowUp f = followUp(clientId, "Welcome new visitor: " + full,
                    "New visitor via Connect With Us.\nEmail: " + email + "\nPhone: " + phone
                            + "\nPreferred contact: " + v.prefs() + "\nHow they heard about us: " + v.howHeard(),
                    "Contacted".equals(v.status()) ? "COMPLETED" : "PENDING", s.getAssignedTo(),
                    "CONNECT", s.getId(), "Connect: " + full, today.plusDays(2));
            s.setFollowUpId(f.getId());
            connectRepo.save(s);
            backdate("connect_submission", "created_at", "client_id", s.getId(), clientId,
                     today.minusDays(v.daysAgo()).atTime(20, 15 + i * 7));
            created++; i++;
        }
        return created;
    }

    // ── Follow-Ups → Prayer Requests ──────────────────────────────────────

    int seedPrayer(String clientId, LocalDate today) {
        if (hasPrayer(clientId)) return 0;
        Object[][] rows = {
                // first, last, request, share, source, status, daysAgo
                { "Margaret", "Ellison", "Please pray for my mother's recovery after her hip surgery this week.", true,  "PUBLIC",  "New",        1 },
                { "Anonymous", "",        "Praying for direction as I look for a new job.",                         false, "WEBSITE", "New",        2 },
                { "Victor", "Alvarez",   "Our family is moving across the country next month — pray for a smooth transition.", true, "NTAG", "PrayedFor", 5 },
                { "Hannah", "Sorensen",  "Thankful for a healthy baby girl! Please pray for rest for our family.", true,  "PUBLIC",  "FollowedUp", 8 },
                { "Elijah", "Turner",    "Pray for peace and wisdom for my teenage son.",                          false, "PUBLIC",  "InProgress", 3 },
        };
        int created = 0;
        for (Object[] r : rows) {
            PublicPrayerRequest p = new PublicPrayerRequest();
            p.setClientId(clientId);
            p.setFirstName((String) r[0]); p.setLastName((String) r[1]);
            if (!"Anonymous".equals(r[0])) {
                p.setEmail(((String) r[0] + "." + r[1]).toLowerCase() + "@" + domain(clientId));
            }
            p.setRequestText((String) r[2]);
            p.setShareWithTeam((Boolean) r[3]);
            p.setSource((String) r[4]);
            p.setStatus((String) r[5]);
            p.setDeleteFlag(false);
            p = prayerRepo.save(p);
            String who = ((String) r[0] + " " + r[1]).trim();
            FollowUp f = followUp(clientId, "Prayer follow-up: " + who, (String) r[2],
                    "FollowedUp".equals(r[5]) ? "COMPLETED" : "PENDING", null,
                    "PRAYER", p.getId(), "Prayer: " + who, today.plusDays(1));
            p.setFollowUpId(f.getId());
            prayerRepo.save(p);
            backdate("public_prayer_request", "created_at", "client_id", p.getId(), clientId,
                     today.minusDays((Integer) r[6]).atTime(7 + created * 2, 40));
            created++;
        }
        return created;
    }

    // ── Unsubscribed List (/unsubscribed-list) ────────────────────────────

    int seedUnsubscribed(String clientId, List<FamilyMember> members, LocalDate today) {
        if (hasUnsubscribed(clientId)) return 0;
        List<String[]> people = new ArrayList<>();
        List<FamilyMember> adults = adults(members);
        // Two members who opted out of mailings, plus a past visitor.
        for (int i = adults.size() - 1; i >= 0 && people.size() < 2; i--) {
            FamilyMember m = adults.get(i);
            if (m.getEmail() != null) people.add(new String[] { m.getEmail(), m.getFirstName(), m.getLastName() });
        }
        people.add(new String[] { "chris.delaney@" + domain(clientId), "Chris", "Delaney" });
        int created = 0;
        for (String[] p : people) {
            String email = p[0].toLowerCase().trim();
            if (unsubscribeRepo.existsByEmailAndClientId(email, clientId)) continue;
            EmailUnsubscribe u = new EmailUnsubscribe();
            u.setClientId(clientId); u.setEmail(email); u.setFirstName(p[1]); u.setLastName(p[2]);
            u.setUnsubscribedAt(today.minusDays(12L + created * 19L).atTime(16, 20));
            unsubscribeRepo.save(u);
            created++;
        }
        return created;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private FollowUp followUp(String clientId, String title, String desc, String status, String assignedTo,
                              String linkedType, Long linkedId, String label, LocalDate due) {
        FollowUp f = new FollowUp();
        f.setClientId(clientId);
        f.setTitle(title.length() > 255 ? title.substring(0, 255) : title);
        f.setDescription(desc);
        f.setStatus(status);
        f.setPriority("MEDIUM");
        f.setAssignedTo(assignedTo);
        f.setLinkedType(linkedType);
        f.setLinkedId(linkedId);
        f.setLinkedLabel(label);
        f.setDueDate(java.sql.Date.valueOf(due));
        f.setCreatedBy("public");
        f.setDeleteFlag(false);
        return followUpRepo.save(f);
    }

    /** Adults with contributions on, in a stable order — heads and spouses from the family seeding. */
    private static List<FamilyMember> adults(List<FamilyMember> members) {
        List<FamilyMember> out = new ArrayList<>();
        for (FamilyMember m : members) if (m.isIncludeContributions()) out.add(m);
        return out;
    }

    private static String name(FamilyMember m) {
        return ((m.getFirstName() == null ? "" : m.getFirstName()) + " "
              + (m.getLastName()  == null ? "" : m.getLastName())).trim();
    }

    /** Tables/columns {@link #backdate} may touch — never built from input. */
    private static final Set<String> BACKDATABLE = Set.of(
            "membership_family.created_date.app_client_id",
            "connect_submission.created_at.client_id",
            "public_prayer_request.created_at.client_id");

    /**
     * These entities force their creation timestamp to "now" in {@code @PrePersist}
     * and mark the column non-updatable, so a demo submission "from last week" can
     * only be dated by a direct UPDATE. Scoped to the row's id AND the tenant.
     */
    private void backdate(String table, String column, String tenantColumn, Number id, String clientId,
                          LocalDateTime when) {
        if (em == null || id == null) return;
        if (!BACKDATABLE.contains(table + "." + column + "." + tenantColumn)) {
            throw new IllegalArgumentException("not back-datable: " + table + "." + column);
        }
        em.flush();
        em.createNativeQuery("UPDATE " + table + " SET " + column + " = ?1 WHERE id = ?2 AND " + tenantColumn + " = ?3")
          .setParameter(1, Timestamp.valueOf(when))
          .setParameter(2, id)
          .setParameter(3, clientId)
          .executeUpdate();
    }
}
