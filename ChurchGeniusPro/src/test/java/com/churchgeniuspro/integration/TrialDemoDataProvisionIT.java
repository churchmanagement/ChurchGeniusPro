package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.service.TrialDemoDataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.churchgeniuspro.hibernate.ConnectSubmission;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.repository.ConnectSubmissionRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingTypeRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Provisions a real trial tenant against PostgreSQL through the production path
 * ({@link TestDataService#provisionTenant}, its transaction, ddl-auto schema) and
 * checks what lands in each table: every demo area populated, nothing written to
 * another church, timestamps spread before the creation date, meetings in the
 * future, and a second seeding run adding nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "app.base-url=http://localhost")
class TrialDemoDataProvisionIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestDataService testData;
    @Autowired TrialDemoDataSeeder seeder;
    @Autowired DonationRepository donationRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConnectSubmissionRepository connectRepo;
    @Autowired FamilyRepository familyRepo;
    @Autowired FamilyMemberRepository familyMemberRepo;
    @Autowired MeetingRepository meetingRepo;
    @Autowired MeetingTypeRepository meetingTypeRepo;

    private long count(String table, String col, String clientId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + col + " = ?", Long.class, clientId);
    }

    @Test
    void trialTenantGetsDemoDataInEveryArea() {
        // A regular church's donation that must stay untouched.
        Donation other = new Donation();
        other.setClientId("CHR-REAL-1"); other.setAmount(new BigDecimal("42.00")); other.setFirstName("Real");
        donationRepo.save(other);

        String clientId = TestDataService.TRIAL_CLIENT_PREFIX + System.currentTimeMillis();
        TestDataService.TenantSpec spec = new TestDataService.TenantSpec(clientId, "Grace Trial Church",
                List.of("SuperAdmin"), 1, TestDataService.TenantSpec.Contact.of("Pat", "Trial", "pat@trial.test", "913-555-0100"));
        LocalDate today = LocalDate.now(ZoneId.of("America/Chicago"));
        Map<String, Object> out = testData.provisionTenant(spec, null, today.plusDays(30));
        @SuppressWarnings("unchecked") Map<String, Integer> counts = (Map<String, Integer>) out.get("counts");

        assertEquals(8, count("donation", "client_id", clientId));
        assertEquals(3, count("membership_family", "app_client_id", clientId));
        assertTrue(count("membership_family_member", "app_client_id", clientId) >= 5);
        assertTrue(count("attendance_record", "client_id", clientId) > 20);
        assertEquals(2, count("attendance_visitor", "client_id", clientId));
        assertEquals(4, count("connect_submission", "client_id", clientId));
        assertEquals(5, count("public_prayer_request", "client_id", clientId));
        assertEquals(3, count("email_unsubscribe", "client_id", clientId));
        assertEquals(5, count("meeting", "app_client_id", clientId));
        assertEquals(2, count("pledge_campaign", "client_id", clientId));
        assertEquals(counts.get("pledgePayments").longValue(),
                jdbc.queryForObject("SELECT count(*) FROM income WHERE app_client_id = ? AND note LIKE 'Pledge payment%'", Long.class, clientId));

        // Submissions are spread over the days before creation (back-dated), not all "now".
        assertEquals(4L, jdbc.queryForObject("SELECT count(DISTINCT created_at::date) FROM connect_submission WHERE client_id = ?", Long.class, clientId));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM connect_submission WHERE client_id = ? AND created_at::date >= ?", Long.class, clientId, today));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM public_prayer_request WHERE client_id = ? AND created_at::date >= ?", Long.class, clientId, today));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM membership_family WHERE app_client_id = ? AND created_date::date >= ?", Long.class, clientId, today));
        // Meetings: every one dated after the creation date.
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM meeting WHERE app_client_id = ? AND meeting_date <= ?", Long.class, clientId, today));
        // Pending applications are not approved/deleted.
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM membership_family WHERE app_client_id = ? AND delete_flag", Long.class, clientId));

        // The other church is untouched.
        assertEquals(1L, count("donation", "client_id", "CHR-REAL-1"));
        assertEquals(0L, count("connect_submission", "client_id", "CHR-REAL-1"));

        // Running the extra seeding again for the same tenant adds nothing.
        Map<String, Integer> again = seeder.seed(clientId, List.of(), today);
        assertTrue(again.values().stream().allMatch(n -> n == 0), "second run: " + again);
        assertEquals(8, count("donation", "client_id", clientId));
    }

    /** Tables whose existing rows the backfill must leave byte-for-byte unchanged. */
    private static final String[][] TABLES = {
            {"connect_submission","client_id"}, {"meeting","app_client_id"}, {"meeting_type","app_client_id"},
            {"pledge_campaign","client_id"}, {"pledge_member","client_id"}, {"income","app_client_id"},
            {"family_member","app_client_id"}, {"family","app_client_id"}, {"follow_up","client_id"},
            {"donation","client_id"}, {"attendance_record","client_id"}, {"email_unsubscribe","client_id"},
            {"public_prayer_request","client_id"}, {"membership_family","app_client_id"} };

    private Map<String, String> snapshot(String clientId) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String[] t : TABLES) {
            Long max = jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM " + t[0] + " WHERE " + t[1] + " = ?", Long.class, clientId);
            String hash = jdbc.queryForObject("SELECT COALESCE(md5(string_agg(x::text, '|' ORDER BY x.id)),'') FROM " + t[0]
                    + " x WHERE " + t[1] + " = ?", String.class, clientId);
            m.put(t[0], max + ":" + hash);
        }
        return m;
    }
    private String hashUpTo(String table, String col, String clientId, long maxId) {
        return jdbc.queryForObject("SELECT COALESCE(md5(string_agg(x::text, '|' ORDER BY x.id)),'') FROM " + table
                + " x WHERE " + col + " = ? AND x.id <= ?", String.class, clientId, maxId);
    }

    @Test
    void backfillFillsOnlyEmptyAreasOfAnOldTrialAndChangesNothingExisting() {
        LocalDate today = LocalDate.now(ZoneId.of("America/Chicago"));
        // An "old" trial: provisioned before the extra areas existed …
        testData.setDemoExtras(null);
        String clientId = TestDataService.TRIAL_CLIENT_PREFIX + (System.currentTimeMillis() + 7);
        try {
            testData.provisionTenant(new TestDataService.TenantSpec(clientId, "Old Trial Church", List.of("SuperAdmin"), 1,
                    TestDataService.TenantSpec.Contact.of("Old", "Trial", "old@trial.test", "913-555-0101")), null, today.plusDays(30));
        } finally {
            testData.setDemoExtras(seeder);
        }
        // … whose church has since added real data of its own.
        Family fam = new Family(); fam.setAppClientId(clientId); fam.setInactive(false); fam.setDeleteFlag(false);
        fam = familyRepo.save(fam);
        FamilyMember real = new FamilyMember(); real.setFamily(fam); real.setAppClientId(clientId); real.setRole("Head");
        real.setFirstName("Real"); real.setLastName("Person"); real.setEmail("real.person@gmail.example");
        real.setMemberType("Member"); real.setIncludeContributions(true); real.setDeleteFlag(false); real.setInactive(false);
        real = familyMemberRepo.save(real);
        ConnectSubmission own = new ConnectSubmission(); own.setClientId(clientId); own.setFirstName("Walk"); own.setLastName("In");
        connectRepo.save(own);
        // Old-style seeded meetings were removed by the 90-day purge, so none are upcoming any more.
        jdbc.update("UPDATE meeting SET delete_flag = true WHERE app_client_id = ?", clientId);
        int typesBefore = meetingTypeRepo.findActiveByAppUser(clientId).size();

        // Preview: read-only, and it predicts the run.
        Map<String, String> before = snapshot(clientId);
        Map<String, Object> row = testData.previewTrialBackfill().stream()
                .filter(t -> clientId.equals(t.get("clientId"))).findFirst().orElseThrow();
        assertEquals(before, snapshot(clientId), "preview must not write anything");
        @SuppressWarnings("unchecked") Map<String, Boolean> would = (Map<String, Boolean>) row.get("wouldFill");
        assertEquals(Boolean.FALSE, would.get("connectSubmissions"), "the church already has a Connect submission");
        assertEquals(Boolean.FALSE, would.get("pledges"), "the old trial already has a campaign");
        assertEquals(Boolean.TRUE, would.get("donations"));
        assertEquals(Boolean.TRUE, would.get("meetings"), "no upcoming meetings any more");

        Map<String, Integer> added = testData.backfillTrialDemoData(clientId);

        // Every row that existed before is byte-for-byte unchanged.
        for (String[] t : TABLES) {
            String[] b = before.get(t[0]).split(":", 2);
            assertEquals(b.length > 1 ? b[1] : "", hashUpTo(t[0], t[1], clientId, Long.parseLong(b[0])),
                    t[0] + ": an existing row was modified");
        }
        // Filled exactly what the preview said.
        assertEquals(0, added.get("connectSubmissions")); assertEquals(1L, count("connect_submission", "client_id", clientId));
        assertEquals(0, added.get("pledgeCampaigns"));
        assertEquals(8, added.get("donations")); assertEquals(5, added.get("meetings"));
        assertTrue(added.get("attendance") > 0);
        // The church's own member is never used for invented attendance or gifts.
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM attendance_record WHERE family_member_id = ?", Long.class, real.getId()));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM donation WHERE email = ?", Long.class, "real.person@gmail.example"));
        // Existing meeting categories are reused, not duplicated.
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM meeting_type WHERE app_client_id = ? AND delete_flag = false AND lower(type_name) = 'sunday service'", Long.class, clientId));
        assertEquals(typesBefore, meetingTypeRepo.findActiveByAppUser(clientId).size(), "all five categories already existed");

        // A second run adds nothing.
        Map<String, Integer> again = testData.backfillTrialDemoData(clientId);
        assertTrue(again.values().stream().allMatch(n -> n == 0), "second run: " + again);

        // Only trial accounts.
        assertThrows(IllegalArgumentException.class, () -> testData.backfillTrialDemoData("CHR-REAL-1"));
        assertThrows(IllegalArgumentException.class, () -> testData.backfillTrialDemoData(TestDataService.DEMO_CLIENT_PREFIX + "1"));
        assertThrows(IllegalArgumentException.class, () -> testData.backfillTrialDemoData(TestDataService.TRIAL_CLIENT_PREFIX + "does-not-exist"));
    }
}
