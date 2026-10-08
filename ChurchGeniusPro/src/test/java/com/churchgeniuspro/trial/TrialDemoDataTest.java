package com.churchgeniuspro.trial;

import com.churchgeniuspro.bankimport.BankImportController;
import com.churchgeniuspro.bankimport.BankImportDemoStatement;
import com.churchgeniuspro.bankimport.BankStatementParser;
import com.churchgeniuspro.bankimport.BankTxn;
import com.churchgeniuspro.bankimport.TransactionCategorizer;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AttendanceService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.service.TrialDemoDataSeeder;
import com.churchgeniuspro.util.MeetingRecurrence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Demo data for new trial accounts: which screens get it, that it belongs only to
 * the new tenant, that it is fictional, that dates are relative to the creation
 * date, and that running it twice never duplicates anything.
 */
class TrialDemoDataTest {

    static final String TRIAL = TestDataService.TRIAL_CLIENT_PREFIX + "1790000000001";
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);   // a Thursday

    // ── tiny in-memory persistence ────────────────────────────────────────

    static final AtomicLong SEQ = new AtomicLong(100);

    /** Gives a saved entity an id (Integer or Long, whichever its setter takes) and stores it. */
    static <T> T keep(List<Object> store, T e) {
        try {
            Method getId = e.getClass().getMethod("getId");
            if (getId.invoke(e) == null) {
                for (Method m : e.getClass().getMethods()) {
                    if (m.getName().equals("setId") && m.getParameterCount() == 1) {
                        Class<?> t = m.getParameterTypes()[0];
                        m.invoke(e, t == Long.class ? (Object) SEQ.incrementAndGet() : (Object) (int) SEQ.incrementAndGet());
                    }
                }
            }
        } catch (Exception ex) { throw new RuntimeException(ex); }
        if (!store.contains(e)) store.add(e);
        return e;
    }

    static List<FamilyMember> members(String clientId) {
        List<FamilyMember> out = new ArrayList<>();
        String[][] ppl = { {"John","Anderson"},{"Jane","Anderson"},{"Mary","Brooks"},{"David","Chen"},{"Susan","Chen"},
                           {"Sarah","Davis"},{"Michael","Edwards"},{"Liam","Edwards"} };
        int id = 1;
        for (String[] p : ppl) {
            FamilyMember m = new FamilyMember();
            m.setId(id++);
            m.setFirstName(p[0]); m.setLastName(p[1]); m.setAppClientId(clientId);
            m.setEmail((p[0] + "." + p[1]).toLowerCase() + "@" + clientId.toLowerCase() + ".test");
            m.setIncludeContributions(!"Liam".equals(p[0]));
            out.add(m);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Donations, Membership Requests, Attendance, Connect, Prayer, Unsubscribed")
    class Seeder {
        final List<Object> saved = new ArrayList<>();
        DonationRepository donations; MembershipFamilyRepository mfams; MembershipFamilyMemberRepository mmembers;
        AttendanceRecordRepository attendance; AttendanceVisitorRepository visitors; ConnectSubmissionRepository connect;
        PublicPrayerRequestRepository prayer; FollowUpRepository followUps; EmailUnsubscribeRepository unsub;
        FamilyRepository families; FamilyMemberRepository familyMembers;
        TrialDemoDataSeeder seeder;

        @SuppressWarnings("unchecked")
        <R> R repo(Class<R> type) {
            R r = mock(type);
            try { when(type.getMethod("save", Object.class).invoke(r, new Object[] { any() })).thenAnswer(i -> keep(saved, i.getArgument(0))); }
            catch (Exception e) { throw new RuntimeException(e); }
            return r;
        }
        <E> List<E> of(Class<E> t) { return saved.stream().filter(t::isInstance).map(t::cast).collect(Collectors.toList()); }

        @BeforeEach
        void setUp() {
            donations = repo(DonationRepository.class); mfams = repo(MembershipFamilyRepository.class);
            mmembers = repo(MembershipFamilyMemberRepository.class); attendance = repo(AttendanceRecordRepository.class);
            visitors = repo(AttendanceVisitorRepository.class); connect = repo(ConnectSubmissionRepository.class);
            prayer = repo(PublicPrayerRequestRepository.class); followUps = repo(FollowUpRepository.class);
            unsub = repo(EmailUnsubscribeRepository.class); families = repo(FamilyRepository.class);
            familyMembers = repo(FamilyMemberRepository.class);
            seeder = new TrialDemoDataSeeder(donations, mfams, mmembers, attendance, visitors, connect, prayer,
                                             followUps, unsub, families, familyMembers);
        }

        @Test
        @DisplayName("every area gets a small, realistic set of rows")
        void everyAreaSeeded() {
            Map<String, Integer> c = seeder.seed(TRIAL, members(TRIAL), TODAY);
            assertEquals(8, c.get("donations"));
            assertEquals(3, c.get("membershipRequests"));
            assertEquals(4, c.get("connectSubmissions"));
            assertEquals(5, c.get("prayerRequests"));
            assertEquals(3, c.get("unsubscribed"));
            assertTrue(c.get("attendance") > 20 && c.get("attendance") < 80, "attendance: enough to chart, not excessive " + c);
        }

        @Test
        @DisplayName("every row belongs to the new trial tenant only")
        void onlyThisTenant() {
            seeder.seed(TRIAL, members(TRIAL), TODAY);
            assertFalse(saved.isEmpty());
            for (Object e : saved) {
                String cid = (String) ReflectionTestUtils.invokeMethod(e,
                        hasMethod(e, "getClientId") ? "getClientId" : "getAppClientId");
                assertEquals(TRIAL, cid, e.getClass().getSimpleName() + " written for the wrong tenant");
            }
        }

        @Test
        @DisplayName("contact details are fictional: .test addresses and 555-01xx numbers")
        void fictional() {
            seeder.seed(TRIAL, members(TRIAL), TODAY);
            for (Object e : saved) {
                for (String getter : List.of("getEmail", "getPhone")) {
                    if (!hasMethod(e, getter)) continue;
                    String v = (String) ReflectionTestUtils.invokeMethod(e, getter);
                    if (v == null) continue;
                    if (getter.equals("getEmail")) assertTrue(v.endsWith(".test"), "real-looking email " + v);
                    else assertTrue(v.matches("(913-)?555-01\\d\\d"), "real-looking phone " + v);
                }
            }
            for (Donation d : of(Donation.class)) {
                assertNull(d.getStripePaymentIntentId(), "no processor ids on demo gifts");
                assertTrue(d.getPaymentMethod().equals("Bank Transfer") || d.getPaymentMethod().matches(".*\\u2022{4} (4242|4444|0005)"),
                        "card label must be a processor test-card ending: " + d.getPaymentMethod());
            }
        }

        @Test
        @DisplayName("donations: dated before the creation date, amount = gift + covered fee")
        void donations() {
            seeder.seed(TRIAL, members(TRIAL), TODAY);
            List<Donation> ds = of(Donation.class);
            assertTrue(ds.stream().anyMatch(d -> d.getFeeCovered().signum() > 0));
            for (Donation d : ds) {
                assertTrue(d.getDonatedAt().toLocalDate().isBefore(TODAY));
                assertTrue(d.getDonatedAt().toLocalDate().isAfter(TODAY.minusDays(60)));
                assertEquals(0, d.getAmount().compareTo(d.getIntendedAmount().add(d.getFeeCovered())));
                assertEquals("USD", d.getCurrency()); assertEquals("succeeded", d.getStatus());
            }
        }

        @Test
        @DisplayName("membership requests are pending applications with members and a head address")
        void membershipRequests() {
            seeder.seed(TRIAL, members(TRIAL), TODAY);
            List<MembershipFamily> fams = of(MembershipFamily.class);
            List<MembershipFamilyMember> ms = of(MembershipFamilyMember.class);
            assertEquals(3, fams.size());
            for (MembershipFamily f : fams) {
                assertFalse(f.isDeleteFlag(), "pending = not yet approved (approval soft-deletes)");
                assertNull(f.getExistingFamilyId(), "new families, not renewals");
                List<MembershipFamilyMember> mine = ms.stream().filter(m -> m.getMembershipFamily() == f).toList();
                assertFalse(mine.isEmpty());
                MembershipFamilyMember head = mine.stream().filter(m -> "Head".equals(m.getRole())).findFirst().orElseThrow();
                assertNotNull(head.getAddress1()); assertEquals("Olathe", head.getCity());
                assertEquals("16", head.getState(), "state stored as the form's numeric code (KS = 16)");
            }
        }

        @Test
        @DisplayName("attendance: past Sundays/Wednesdays only, valid statuses and service types")
        void attendance() {
            seeder.seed(TRIAL, members(TRIAL), TODAY);
            List<AttendanceRecord> rs = of(AttendanceRecord.class);
            assertFalse(rs.isEmpty());
            Set<String> services = new HashSet<>();
            for (AttendanceRecord r : rs) {
                assertTrue(r.getAttendanceDate().isBefore(TODAY), "attendance cannot be in the future");
                assertTrue(AttendanceService.STATUSES.contains(r.getStatus()));
                assertTrue(r.getAttendanceDate().getDayOfWeek() == DayOfWeek.SUNDAY || r.getAttendanceDate().getDayOfWeek() == DayOfWeek.WEDNESDAY);
                assertTrue(("MEMBER".equals(r.getPersonType()) && r.getFamilyMemberId() != null)
                        || ("VISITOR".equals(r.getPersonType()) && r.getVisitorId() != null));
                services.add(r.getServiceType());
            }
            assertEquals(Set.of("Sunday Worship", "Bible Study"), services, "names match the page's default service types");
            assertEquals(2, of(AttendanceVisitor.class).size());
        }

        @Test
        @DisplayName("Connect With Us: visitor record, valid status, linked follow-up — as the real form does")
        void connect() {
            seeder.seed(TRIAL, members(TRIAL), TODAY);
            List<ConnectSubmission> subs = of(ConnectSubmission.class);
            List<FollowUp> fus = of(FollowUp.class);
            for (ConnectSubmission s : subs) {
                assertTrue(List.of("New", "Assigned", "InProgress", "Contacted", "Closed").contains(s.getStatus()));
                assertNotNull(s.getMemberId());
                FamilyMember v = of(FamilyMember.class).stream().filter(m -> m.getId().equals(s.getMemberId())).findFirst().orElseThrow();
                assertEquals("Visitor", v.getMemberType());
                assertTrue(fus.stream().anyMatch(f -> "CONNECT".equals(f.getLinkedType()) && s.getId().equals(f.getLinkedId())));
                assertTrue(s.getConfirmationSent().matches("EMAIL|SMS|EMAIL\\+SMS|NONE"));
                if ("New".equals(s.getStatus())) assertNull(s.getAssignedTo());
            }
            assertTrue(subs.stream().anyMatch(s -> s.getAssignedTo() != null));
        }

        @Test
        @DisplayName("Prayer Requests: valid statuses and sources, linked follow-ups")
        void prayer() {
            seeder.seed(TRIAL, members(TRIAL), TODAY);
            for (PublicPrayerRequest p : of(PublicPrayerRequest.class)) {
                assertTrue(List.of("New", "Assigned", "InProgress", "PrayedFor", "FollowedUp", "Closed").contains(p.getStatus()));
                assertTrue(List.of("PUBLIC", "WEBSITE", "NTAG").contains(p.getSource()));
                assertTrue(of(FollowUp.class).stream().anyMatch(f -> "PRAYER".equals(f.getLinkedType()) && p.getId().equals(f.getLinkedId())));
            }
        }

        @Test
        @DisplayName("running it again for the same tenant adds nothing")
        void idempotent() {
            seeder.seed(TRIAL, members(TRIAL), TODAY);
            int before = saved.size();
            when(donations.findByClientIdOrderByDonatedAtDesc(TRIAL)).thenReturn(of(Donation.class));
            when(mfams.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(TRIAL)).thenReturn(of(MembershipFamily.class));
            when(attendance.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(eq(TRIAL), any(), any())).thenReturn(of(AttendanceRecord.class));
            when(connect.findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(TRIAL)).thenReturn(of(ConnectSubmission.class));
            when(prayer.findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(TRIAL)).thenReturn(of(PublicPrayerRequest.class));
            when(unsub.findByClientIdOrderByUnsubscribedAtDesc(TRIAL)).thenReturn(of(EmailUnsubscribe.class));
            Map<String, Integer> again = seeder.seed(TRIAL, members(TRIAL), TODAY);
            assertTrue(again.values().stream().allMatch(n -> n == 0), "second run: " + again);
            assertEquals(before, saved.size());
        }

        @Test
        @DisplayName("refuses a regular church — demo data never goes into a non-trial account")
        void refusesRealChurch() {
            assertThrows(IllegalArgumentException.class, () -> seeder.seed("CHR-1001", members("CHR-1001"), TODAY));
            assertTrue(saved.isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Meetings and pledge campaigns (TestDataService)")
    class CoreSeeding {
        final List<Object> saved = new ArrayList<>();
        TestDataService svc;
        MeetingRepository meetingRepo; PledgeCampaignRepository campaignRepo;

        @BeforeEach
        void setUp() throws Exception {
            Constructor<?> ctor = TestDataService.class.getDeclaredConstructors()[0];
            Object[] args = new Object[ctor.getParameterCount()];
            Class<?>[] types = ctor.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                Object r = mock(types[i]);
                if (org.springframework.data.repository.Repository.class.isAssignableFrom(types[i])) {
                    Method save = types[i].getMethod("save", Object.class);
                    when(save.invoke(r, new Object[] { any() })).thenAnswer(inv -> keep(saved, inv.getArgument(0)));
                }
                if (types[i] == MeetingRepository.class) meetingRepo = (MeetingRepository) r;
                if (types[i] == PledgeCampaignRepository.class) campaignRepo = (PledgeCampaignRepository) r;
                args[i] = r;
            }
            svc = (TestDataService) ctor.newInstance(args);
        }
        <E> List<E> of(Class<E> t) { return saved.stream().filter(t::isInstance).map(t::cast).collect(Collectors.toList()); }

        @Test
        @DisplayName("meetings start after the creation date, whatever weekday that is")
        void meetingsAreFuture() {
            for (int d = 0; d < 7; d++) {
                saved.clear();
                LocalDate today = TODAY.plusDays(d);
                int n = ReflectionTestUtils.invokeMethod(svc, "seedMeetings", TRIAL, today);
                List<Meeting> ms = of(Meeting.class);
                assertEquals(5, n); assertEquals(5, ms.size());
                for (Meeting m : ms) {
                    assertEquals(TRIAL, m.getAppClientId());
                    List<LocalDate> next = MeetingRecurrence.listOccurrences(m, today, 1, 400);
                    assertFalse(next.isEmpty(), m.getMeetingType().getTypeName() + " has an occurrence");
                    assertTrue(next.get(0).isAfter(today), m.getMeetingType().getTypeName() + " first occurs after " + today + " (" + today.getDayOfWeek() + ")");
                    assertTrue(next.get(0).isBefore(today.plusDays(40)), "and soon");
                }
                Meeting sunday = ms.stream().filter(m -> m.getMeetingType().getTypeName().equals("Sunday Service")).findFirst().orElseThrow();
                assertEquals("Weekly", sunday.getOccurrence()); assertEquals(DayOfWeek.SUNDAY, sunday.getMeetingDate().getDayOfWeek());
                assertEquals("10:00", sunday.getStartTime()); assertEquals("11:30", sunday.getEndTime());
                assertTrue(ms.stream().anyMatch(m -> "Monthly".equals(m.getOccurrence())));
                assertEquals(2, ms.stream().filter(m -> "One-time".equals(m.getOccurrence())).count());
            }
        }

        private Meeting existing(String occ, LocalDate date, LocalDate end) {
            Meeting m = new Meeting(); m.setOccurrence(occ); m.setMeetingDate(date); m.setEndDate(end); return m;
        }

        @Test
        @DisplayName("meetings are not added when any meeting is still upcoming (never twice)")
        void meetingsIdempotent() {
            for (Meeting m : List.of(existing("One-time", TODAY.plusDays(3), null),
                                     existing("One-time", TODAY, null),
                                     existing("Weekly", TODAY.minusMonths(6), null),
                                     existing("Monthly", TODAY.minusMonths(6), TODAY.plusDays(1)))) {
                when(meetingRepo.findAllActiveByAppUserOrderByDateAsc(TRIAL)).thenReturn(List.of(m));
                int n = ReflectionTestUtils.invokeMethod(svc, "seedMeetings", TRIAL, TODAY);
                assertEquals(0, n, m.getOccurrence() + " " + m.getMeetingDate()); assertTrue(saved.isEmpty());
            }
        }

        @Test
        @DisplayName("with only past meetings, the schedule is added and existing categories are reused")
        void pastOnlyMeetingsGetSchedule() throws Exception {
            when(meetingRepo.findAllActiveByAppUserOrderByDateAsc(TRIAL)).thenReturn(List.of(
                    existing("One-time", TODAY.minusDays(20), null), existing("Weekly", TODAY.minusMonths(3), TODAY.minusDays(1))));
            MeetingType own = new MeetingType(); own.setId(900); own.setTypeName("sunday service "); own.setAppClientId(TRIAL);
            MeetingTypeRepository typeRepo = (MeetingTypeRepository) ReflectionTestUtils.getField(svc, "meetingTypeRepo");
            when(typeRepo.findActiveByAppUser(TRIAL)).thenReturn(List.of(own));
            int n = ReflectionTestUtils.invokeMethod(svc, "seedMeetings", TRIAL, TODAY);
            assertEquals(5, n);
            assertEquals(4, of(MeetingType.class).size(), "Sunday Service already existed, so only 4 new categories");
            assertTrue(of(Meeting.class).stream().anyMatch(m -> m.getMeetingType() == own));
        }

        @Test
        @DisplayName("pledge campaigns show progress: payments fall inside each campaign's window")
        void pledgesShowProgress() {
            List<FamilyMember> ms = members(TRIAL);
            SubSource fund = new SubSource(); fund.setId(7); fund.setSourceName("General");
            SubSource fund2 = new SubSource(); fund2.setId(8); fund2.setSourceName("Missions");
            TransactionType tt = new TransactionType(); tt.setId(3);
            int[] r = ReflectionTestUtils.invokeMethod(svc, "seedPledges", TRIAL, ms, List.of(fund, fund2), List.of(tt),
                    2, 5, new Random(1), TODAY);
            assertEquals(2, r[0]); assertEquals(10, r[1]); assertTrue(r[2] > 0);
            List<PledgeCampaign> cs = of(PledgeCampaign.class);
            List<Income> pays = of(Income.class);
            for (PledgeCampaign c : cs) {
                LocalDate start = c.getCreatedDate().toLocalDate();
                assertTrue(start.isBefore(TODAY) && c.getEndDate().isAfter(TODAY));
                for (PledgeMember p : of(PledgeMember.class)) {
                    if (!p.getCampaignId().equals(c.getId())) continue;
                    BigDecimal paid = pays.stream()
                            .filter(i -> i.getMember().getId().equals(p.getFamilyMemberId()) && i.getSubSource().getId().equals(c.getSubSourceId()))
                            .filter(i -> !i.getIncomeDate().isBefore(start) && i.getIncomeDate().isBefore(TODAY))
                            .map(Income::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    assertTrue(paid.compareTo(p.getPledgeAmount()) <= 0);
                }
            }
            assertTrue(pays.stream().allMatch(i -> i.getIncomeDate().isBefore(TODAY) && TRIAL.equals(i.getAppClientId())));
        }

        @Test
        @DisplayName("pledges are not seeded twice")
        void pledgesIdempotent() {
            when(campaignRepo.findByClientId(TRIAL)).thenReturn(List.of(new PledgeCampaign()));
            int[] r = ReflectionTestUtils.invokeMethod(svc, "seedPledges", TRIAL, members(TRIAL), List.of(new SubSource()),
                    List.of(new TransactionType()), 2, 5, new Random(1), TODAY);
            assertArrayEquals(new int[] { 0, 0, 0 }, r); assertTrue(saved.isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Bank Import sample statement")
    class BankImportSample {

        @Test
        @DisplayName("parses with the real parser, dates are the last 30 days, every row categorized")
        void parses() {
            String csv = BankImportDemoStatement.csv(TODAY);
            List<BankTxn> txns = BankStatementParser.parse(BankImportDemoStatement.FILE_NAME, csv.getBytes(StandardCharsets.UTF_8));
            assertEquals(18, txns.size());
            for (BankTxn t : txns) {
                TransactionCategorizer.categorize(t);
                LocalDate d = LocalDate.parse(t.date);
                assertTrue(d.isBefore(TODAY) && !d.isBefore(TODAY.minusDays(30)), "date " + t.date);
                assertFalse(t.category.contains("ncategorized"), "uncategorized: " + t.description);
            }
            assertTrue(txns.stream().anyMatch(t -> t.amount.signum() > 0) && txns.stream().anyMatch(t -> t.amount.signum() < 0));
        }

        @Test
        @DisplayName("contains nothing that looks like an account or card number")
        void noAccountNumbers() {
            assertFalse(BankImportDemoStatement.csv(TODAY).matches("(?s).*\\d{7,}.*"));
        }

        private MockHttpServletRequest staff(String clientId) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("username", "admin"); s.setAttribute("role", "Admin"); s.setAttribute("appClientId", clientId);
            req.setSession(s);
            return req;
        }
        private BankImportController controller() throws Exception {
            Constructor<?> c = BankImportController.class.getDeclaredConstructors()[0];
            Object[] a = Arrays.stream(c.getParameterTypes()).map(t -> (Object) mock(t)).toArray();
            return (BankImportController) c.newInstance(a);
        }

        @Test
        @DisplayName("served to trial accounts; a regular church gets 404 (button stays hidden)")
        void onlyForTrial() throws Exception {
            BankImportController c = controller();
            ResponseEntity<?> trial = c.demoStatement(staff(TRIAL));
            assertEquals(200, trial.getStatusCode().value());
            assertTrue(String.valueOf(trial.getBody()).startsWith("Date,Description,Amount"));
            assertEquals(404, c.demoStatement(staff("CHR-1001")).getStatusCode().value());
            assertEquals(401, c.demoStatement(new MockHttpServletRequest()).getStatusCode().value());
        }
    }

    static boolean hasMethod(Object o, String name) {
        return Arrays.stream(o.getClass().getMethods()).anyMatch(m -> m.getName().equals(name) && m.getParameterCount() == 0);
    }
}
