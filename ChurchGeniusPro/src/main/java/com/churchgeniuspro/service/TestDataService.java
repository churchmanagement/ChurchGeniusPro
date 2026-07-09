package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.hibernate.GroupMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.hibernate.MemberMessage;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.hibernate.PledgeMember;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.hibernate.SsClass;
import com.churchgeniuspro.hibernate.SsExam;
import com.churchgeniuspro.hibernate.SsQuestion;
import com.churchgeniuspro.hibernate.SsStudent;
import com.churchgeniuspro.hibernate.SsSubmission;
import com.churchgeniuspro.hibernate.SsTeacher;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.GroupMemberRepository;
import com.churchgeniuspro.repository.GroupRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.KmChildRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingTypeRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.MemberMessageRepository;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.repository.PledgeMemberRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SsClassRepository;
import com.churchgeniuspro.repository.SsExamRepository;
import com.churchgeniuspro.repository.SsQuestionRepository;
import com.churchgeniuspro.repository.SsStudentRepository;
import com.churchgeniuspro.repository.SsSubmissionRepository;
import com.churchgeniuspro.repository.SsTeacherRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.churchgeniuspro.util.PasswordUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One-shot test-data loader for the Service Admin home page.
 *
 * <p>Each call to {@link #loadSmallDemo()} creates a brand-new demo tenant
 * (clientId of the form {@code DEMO-<timestamp>}) and inserts a small but
 * representative slice of data across every major module — members,
 * families, contributions, events, ministries, attendance, kids ministry,
 * pledge campaigns, transactions, and users/roles. The fresh-clientId
 * approach keeps demo data isolated from real tenants and makes clear-down
 * trivial: {@link #clearDemoClient(String)} just deletes every row tagged
 * with that clientId.
 *
 * <p>Why a service (not the controller): putting the inserts here lets us
 * wrap the whole load in a single {@code @Transactional} boundary, so a
 * failure midway through rolls back the partial tenant rather than leaving
 * orphan rows.
 */
@Service
public class TestDataService {

    private static final Logger log = LoggerFactory.getLogger(TestDataService.class);

    /** Marker prefix for every demo clientId. Used by {@link #listDemoClients()}. */
    public static final String DEMO_CLIENT_PREFIX = "DEMO-";

    /** Generates the per-account demo password. The raw value is persisted in
     *  {@code signup.demo_password} (for display in the Service Admin credentials
     *  table) and its BCrypt hash in {@code signup.password} (for login). */
    private static final java.security.SecureRandom PWD_RNG = new java.security.SecureRandom();
    // Ambiguous characters (0/O, 1/l/I) omitted so the displayed password is
    // easy to read and re-type when signing into a demo account.
    private static final String PWD_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    private final ChurchRegistrationRepository churchRegRepo;
    private final ServiceClientRepository      serviceClientRepo;
    private final AppUserRepository            appUserRepo;
    private final LoginRepository              loginRepo;        // SignUp rows
    private final FamilyRepository             familyRepo;
    private final FamilyMemberRepository       familyMemberRepo;
    private final MainSourceRepository         mainSourceRepo;
    private final SubSourceRepository          subSourceRepo;
    private final TransactionTypeRepository    transactionTypeRepo;
    private final IncomeRepository             incomeRepo;
    private final ChurchEventRepository        churchEventRepo;
    private final MeetingTypeRepository        meetingTypeRepo;
    private final MeetingRepository            meetingRepo;
    private final GroupRepository              groupRepo;
    private final GroupMemberRepository        groupMemberRepo;
    private final KmChildRepository            kmChildRepo;
    private final PledgeCampaignRepository     pledgeCampaignRepo;
    private final PledgeMemberRepository       pledgeMemberRepo;
    private final PurposeRepository            purposeRepo;
    private final ExpenseRepository            expenseRepo;
    private final MemberMessageRepository      memberMessageRepo;
    private final SsClassRepository            ssClassRepo;
    private final SsTeacherRepository          ssTeacherRepo;
    private final SsStudentRepository          ssStudentRepo;
    private final SsExamRepository             ssExamRepo;
    private final SsQuestionRepository         ssQuestionRepo;
    private final SsSubmissionRepository       ssSubmissionRepo;
    private final JdbcTemplate                 jdbc;

    public TestDataService(ChurchRegistrationRepository churchRegRepo,
                           ServiceClientRepository      serviceClientRepo,
                           AppUserRepository            appUserRepo,
                           LoginRepository              loginRepo,
                           FamilyRepository             familyRepo,
                           FamilyMemberRepository       familyMemberRepo,
                           MainSourceRepository         mainSourceRepo,
                           SubSourceRepository          subSourceRepo,
                           TransactionTypeRepository    transactionTypeRepo,
                           IncomeRepository             incomeRepo,
                           ChurchEventRepository        churchEventRepo,
                           MeetingTypeRepository        meetingTypeRepo,
                           MeetingRepository            meetingRepo,
                           GroupRepository              groupRepo,
                           GroupMemberRepository        groupMemberRepo,
                           KmChildRepository            kmChildRepo,
                           PledgeCampaignRepository     pledgeCampaignRepo,
                           PledgeMemberRepository       pledgeMemberRepo,
                           PurposeRepository            purposeRepo,
                           ExpenseRepository            expenseRepo,
                           MemberMessageRepository      memberMessageRepo,
                           SsClassRepository            ssClassRepo,
                           SsTeacherRepository          ssTeacherRepo,
                           SsStudentRepository          ssStudentRepo,
                           SsExamRepository             ssExamRepo,
                           SsQuestionRepository         ssQuestionRepo,
                           SsSubmissionRepository       ssSubmissionRepo,
                           JdbcTemplate                 jdbc) {
        this.churchRegRepo       = churchRegRepo;
        this.serviceClientRepo   = serviceClientRepo;
        this.appUserRepo         = appUserRepo;
        this.loginRepo           = loginRepo;
        this.familyRepo          = familyRepo;
        this.familyMemberRepo    = familyMemberRepo;
        this.mainSourceRepo      = mainSourceRepo;
        this.subSourceRepo       = subSourceRepo;
        this.transactionTypeRepo = transactionTypeRepo;
        this.incomeRepo          = incomeRepo;
        this.churchEventRepo     = churchEventRepo;
        this.meetingTypeRepo     = meetingTypeRepo;
        this.meetingRepo         = meetingRepo;
        this.groupRepo           = groupRepo;
        this.groupMemberRepo     = groupMemberRepo;
        this.kmChildRepo         = kmChildRepo;
        this.pledgeCampaignRepo  = pledgeCampaignRepo;
        this.pledgeMemberRepo    = pledgeMemberRepo;
        this.purposeRepo         = purposeRepo;
        this.expenseRepo         = expenseRepo;
        this.memberMessageRepo   = memberMessageRepo;
        this.ssClassRepo         = ssClassRepo;
        this.ssTeacherRepo       = ssTeacherRepo;
        this.ssStudentRepo       = ssStudentRepo;
        this.ssExamRepo          = ssExamRepo;
        this.ssQuestionRepo      = ssQuestionRepo;
        this.ssSubmissionRepo    = ssSubmissionRepo;
        this.jdbc                = jdbc;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Build a fresh demo tenant and populate it with a "small" volume of
     * sample data across every supported module.
     *
     * @return a summary map with {@code clientId}, per-entity counts, and
     *         the credentials table the UI renders.
     */
    @Transactional
    public Map<String, Object> loadSmallDemo() {
        String clientId = DEMO_CLIENT_PREFIX + System.currentTimeMillis();
        String churchName = "Demo Church " + clientId.substring(DEMO_CLIENT_PREFIX.length());
        Random rng = new Random(clientId.hashCode()); // deterministic-per-tenant

        // Idempotent housekeeping: convert any legacy null signup.church values
        // into explicit booleans before we add more rows. Required by spec #3.
        backfillNullChurchFlags();

        Map<String, Integer> counts = new LinkedHashMap<>();

        // 1. ChurchRegistration (the org header). Captured because its id
        //    becomes signup.church_id for the Church-level login row.
        ChurchRegistration church = seedChurchRegistration(clientId, churchName);
        counts.put("church", 1);

        // 1b. service_client — REQUIRED for login. countValidChurchLogin /
        //     countValidNonChurchLogin both INNER JOIN this table, so without
        //     a matching active row every demo account would get a 403 at
        //     /login. status=Active, delete_flag=false, end_date > today.
        seedServiceClient(clientId, churchName);

        // 2. Staff accounts — every role gets its own member profile linked via
        //    link_group so role-switching and Member Portal work out of the box.
        //    The returned list is mutable; portal/child logins appended below.
        List<Map<String, Object>> credentials = new ArrayList<>();
        seedAllStaffLinked(clientId, church.getId(), credentials, rng);

        // 3. Accounting taxonomy: MainSource → SubSource → TransactionType
        List<SubSource> funds = seedAccountingTaxonomy(clientId);
        counts.put("funds", funds.size());
        List<TransactionType> txnTypes = seedTransactionTypes(clientId);
        counts.put("transactionTypes", txnTypes.size());

        // 4. Families + Members
        List<FamilyMember> members = seedFamiliesAndMembers(clientId, 10, rng);
        counts.put("families", 10);
        counts.put("members",  members.size());

        // 4b. Member-portal sign-ups (church=false, client_id=MBR<token>) for
        //     two existing adult members so they can log into /memberHome.
        int portalLogins = seedMemberPortalLogins(clientId, members, credentials, 2);
        counts.put("memberPortalLogins", portalLogins);

        // 5. Income / Contributions (~30 spread over current calendar year)
        int incomeCount = seedContributions(clientId, members, funds, txnTypes, 30, rng);
        counts.put("contributions", incomeCount);

        // 6. Events (2)
        int eventCount = seedEvents(clientId, 2, rng);
        counts.put("events", eventCount);

        // 7. Purposes (expense categories) — Building Fund, Missions, etc.
        List<Purpose> purposes = seedPurposes(clientId);
        counts.put("purposes", purposes.size());

        // 8. Groups (one per type) with members + a sample message.
        int[] groupStats = seedGroups(clientId, members, rng);
        counts.put("groups",         groupStats[0]);
        counts.put("groupMembers",   groupStats[1]);
        counts.put("groupMessages",  groupStats[2]);

        // 9. Expenses across purposes/funds (incl. one recurring).
        int expenseCount = seedExpenses(clientId, purposes, funds, txnTypes, rng);
        counts.put("expenses", expenseCount);

        // 10. Meetings (2)
        int meetingCount = seedMeetings(clientId, 2, rng);
        counts.put("meetings", meetingCount);

        // 11. Kids Ministry — 4 children. Returns the created KmChild ids +
        //     their FamilyMember rows so the school + child logins can link.
        List<FamilyMember> childMembers = seedKidsMinistry(clientId, members, 4, rng);
        counts.put("kidsMinistry", childMembers.size());

        // 11b. Child-portal sign-ups (church=false, client_id=MBR<token>) for
        //      two children so they can log into the Kids Portal.
        int childLogins = seedChildPortalLogins(clientId, childMembers, credentials, 2);
        counts.put("childPortalLogins", childLogins);

        // 12. Sunday school — class + teacher + students (children) + exam +
        //     questions + graded submissions so the Kids Portal shows grades.
        int[] schoolStats = seedSundaySchool(clientId, childMembers, rng);
        counts.put("ssClasses",     schoolStats[0]);
        counts.put("ssStudents",    schoolStats[1]);
        counts.put("ssExams",       schoolStats[2]);
        counts.put("ssSubmissions", schoolStats[3]);

        // 13. Pledge campaigns (1) + member pledges (5 across that campaign)
        int[] pledges = seedPledges(clientId, members, funds, 1, 5, rng);
        counts.put("pledgeCampaigns", pledges[0]);
        counts.put("pledges",         pledges[1]);

        // users count reflects EVERY login row created (staff + portal + child).
        counts.put("users", credentials.size());

        log.info("TestDataService: created demo tenant {} with counts {}", clientId, counts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientId",    clientId);
        out.put("churchName",  churchName);
        out.put("counts",      counts);
        out.put("credentials", credentials);
        return out;
    }

    /**
     * Generates a unique, readable demo password (10 chars, mixed-case +
     * digits, ambiguous characters excluded). Each demo account gets its own
     * value; the raw string is stored in {@code signup.demo_password} for
     * display and its BCrypt hash in {@code signup.password} for login, so the
     * password shown in the credentials table is the real one that signs in.
     */
    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(PWD_ALPHABET.charAt(PWD_RNG.nextInt(PWD_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * One-time housekeeping pass: every existing {@code signup} row whose
     * {@code church} flag is {@code NULL} gets backfilled. Church-level rows
     * are detected via {@code service_client.client_id} match; everything
     * else is treated as a non-church login. Logged for auditability.
     *
     * <p>This is idempotent — re-runs touch zero rows — so it's safe to call
     * from {@link #loadSmallDemo()} on every load. It also ensures that after
     * the first demo load the signup table no longer violates the
     * "every row has a non-null church flag" invariant.
     */
    @Transactional
    public int backfillNullChurchFlags() {
        // Mark as church=true any signup whose client_id matches a service_client.
        int asChurch = jdbc.update(
            "UPDATE signup SET church = true " +
            "WHERE church IS NULL " +
            "AND client_id IN (SELECT client_id FROM service_client WHERE client_id IS NOT NULL)");
        // Everything else with a NULL church flag becomes a non-church row.
        int asNonChurch = jdbc.update(
            "UPDATE signup SET church = false WHERE church IS NULL");
        int total = asChurch + asNonChurch;
        if (total > 0) {
            log.info("TestDataService: backfilled {} signup rows with non-null church flag " +
                     "({} church, {} non-church)", total, asChurch, asNonChurch);
        }
        return total;
    }

    /**
     * Delete every row belonging to {@code clientId} (must start with
     * {@link #DEMO_CLIENT_PREFIX}). Uses raw SQL through JdbcTemplate so we
     * can wipe foreign-key children before parents in one pass without
     * Hibernate cascading surprises.
     */
    @Transactional
    public Map<String, Object> clearDemoClient(String clientId) {
        return clearDemoClient(clientId, null);
    }

    /**
     * Same as {@link #clearDemoClient(String)} but records {@code performedBy}
     * (the acting service admin) in the deletion audit log entry.
     */
    @Transactional
    public Map<String, Object> clearDemoClient(String clientId, String performedBy) {
        if (clientId == null || !clientId.startsWith(DEMO_CLIENT_PREFIX)) {
            throw new IllegalArgumentException(
                "Refusing to clear non-demo clientId: " + clientId);
        }
        // Resolve the tenant name BEFORE deletion so it can be recorded in the audit log.
        String churchName = null;
        try {
            churchName = jdbc.queryForObject(
                "SELECT church_name FROM church_registration WHERE client_id=?",
                String.class, clientId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ignore) {
            // No church_registration row (already partially cleared) — leave name null.
        }
        Map<String, Integer> deleted = new LinkedHashMap<>();

        // Children that reference parents in this tenant — delete first.
        // event_registration has no client column, so scope by event_id.
        deleted.put("event_registration",
            jdbc.update("DELETE FROM event_registration WHERE event_id IN " +
                        "(SELECT id FROM church_event WHERE app_client_id=?)", clientId));
        deleted.put("church_event_day",
            jdbc.update("DELETE FROM church_event_day WHERE event_id IN " +
                        "(SELECT id FROM church_event WHERE app_client_id=?)", clientId));
        deleted.put("group_member",
            jdbc.update("DELETE FROM group_member WHERE app_client_id=?", clientId));
        deleted.put("km_checkin",
            jdbc.update("DELETE FROM km_checkin WHERE client_id=?", clientId));
        deleted.put("km_child",
            jdbc.update("DELETE FROM km_child WHERE client_id=?", clientId));
        deleted.put("pledge_member",
            jdbc.update("DELETE FROM pledge_member WHERE client_id=?", clientId));
        deleted.put("pledge_campaign",
            jdbc.update("DELETE FROM pledge_campaign WHERE client_id=?", clientId));
        // Sunday school (child + exam data). Delete grandchildren first.
        deleted.put("ss_submission",
            jdbc.update("DELETE FROM ss_submission WHERE client_id=?", clientId));
        deleted.put("ss_question",
            jdbc.update("DELETE FROM ss_question WHERE client_id=?", clientId));
        deleted.put("ss_exam",
            jdbc.update("DELETE FROM ss_exam WHERE client_id=?", clientId));
        deleted.put("ss_student",
            jdbc.update("DELETE FROM ss_student WHERE client_id=?", clientId));
        deleted.put("ss_teacher",
            jdbc.update("DELETE FROM ss_teacher WHERE client_id=?", clientId));
        deleted.put("ss_class",
            jdbc.update("DELETE FROM ss_class WHERE client_id=?", clientId));
        deleted.put("member_message",
            jdbc.update("DELETE FROM member_message WHERE app_client_id=?", clientId));
        deleted.put("expense",
            jdbc.update("DELETE FROM expense WHERE app_client_id=?", clientId));
        deleted.put("purpose",
            jdbc.update("DELETE FROM purpose WHERE app_client_id=?", clientId));
        deleted.put("income",
            jdbc.update("DELETE FROM income WHERE app_client_id=?", clientId));
        deleted.put("meeting",
            jdbc.update("DELETE FROM meeting WHERE app_client_id=?", clientId));
        deleted.put("meeting_type",
            jdbc.update("DELETE FROM meeting_type WHERE app_client_id=?", clientId));
        deleted.put("church_event",
            jdbc.update("DELETE FROM church_event WHERE app_client_id=?", clientId));
        deleted.put("app_group",
            jdbc.update("DELETE FROM app_group WHERE app_client_id=?", clientId));
        deleted.put("transaction_type",
            jdbc.update("DELETE FROM transaction_type WHERE app_client_id=?", clientId));
        deleted.put("sub_source",
            jdbc.update("DELETE FROM sub_source WHERE app_client_id=?", clientId));
        deleted.put("main_source",
            jdbc.update("DELETE FROM main_source WHERE app_client_id=?", clientId));
        // signup rows for this tenant are keyed three different ways:
        //   • church login        → client_id = DEMO clientId
        //   • staff logins        → client_id = app_user.user_id
        //   • member/child portal → client_id = family_member.member_ref
        // Delete all three BEFORE removing app_user / family_member so the
        // sub-selects can still resolve the token columns.
        int sigByClient = jdbc.update("DELETE FROM signup WHERE client_id=?", clientId);
        int sigByUser = jdbc.update(
            "DELETE FROM signup WHERE client_id IN " +
            "(SELECT user_id FROM app_user WHERE client_id=?)", clientId);
        int sigByMember = jdbc.update(
            "DELETE FROM signup WHERE client_id IN " +
            "(SELECT member_ref FROM family_member WHERE app_client_id=? AND member_ref IS NOT NULL)",
            clientId);
        deleted.put("signup", sigByClient + sigByUser + sigByMember);

        deleted.put("family_member",
            jdbc.update("DELETE FROM family_member WHERE app_client_id=?", clientId));
        deleted.put("family",
            jdbc.update("DELETE FROM family WHERE app_client_id=?", clientId));
        deleted.put("app_user",
            jdbc.update("DELETE FROM app_user WHERE client_id=?", clientId));
        deleted.put("church_registration",
            jdbc.update("DELETE FROM church_registration WHERE client_id=?", clientId));
        deleted.put("service_client",
            jdbc.update("DELETE FROM service_client WHERE client_id=?", clientId));

        int total = deleted.values().stream().mapToInt(Integer::intValue).sum();

        // Deletion audit trail. No general admin-action audit table exists, so this is
        // written as a clearly-labelled AUDIT line to the application log, capturing every
        // field required by the spec: tenant name, Client ID, App Client ID (identical for
        // a tenant), the acting service admin, the timestamp, and the per-module counts.
        log.warn("AUDIT demo-tenant-deletion | tenant='{}' clientId='{}' appClientId='{}' "
                + "performedBy='{}' at='{}' totalDeleted={} counts={}",
                churchName, clientId, clientId,
                (performedBy == null || performedBy.isBlank() ? "(unknown service admin)" : performedBy),
                java.time.LocalDateTime.now(), total, deleted);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientId",   clientId);
        out.put("churchName", churchName);
        out.put("deleted",    deleted);
        out.put("total",      total);
        return out;
    }

    /**
     * Returns every existing demo tenant along with its full credentials,
     * reconstructed from the signup table on every call. The UI shows this
     * after page reload so the credentials panel persists. Spec #1.
     *
     * <p>Three signup shapes exist per tenant:
     * <ol>
     *   <li>Church login:        signup.client_id = tenant clientId, church=true</li>
     *   <li>Staff logins:        signup.client_id = app_user.user_id (USR token), church=false</li>
     *   <li>Member/child portal: signup.client_id = family_member.member_ref (MBR token), church=false</li>
     * </ol>
     * All three are pulled in a UNION so the UI shows every generated account.
     */
    public List<Map<String, Object>> listDemoClients() {
        // UNION of three signup shapes. Each branch emits the same columns:
        //   tenant_id, church_name, username, password, demo_password, church_flag, role, member_name, signup_id
        // The ORDER BY at the end sorts by tenant creation date then signup id so
        // Church row comes first, then staff rows, then portal rows.
        String sql =
            // (1) Church-level login
            "SELECT cr.client_id AS tenant_id, cr.church_name, " +
            "       s.username, s.password, s.demo_password, s.church AS church_flag, " +
            "       'Church' AS role, '' AS member_name, s.id AS signup_id " +
            "FROM church_registration cr " +
            "JOIN signup s ON s.client_id = cr.client_id " +
            "WHERE cr.client_id LIKE ? AND cr.delete_flag = false " +
            "  AND s.church = true " +

            "UNION ALL " +

            // (2) Staff logins (signup.client_id = app_user.user_id)
            "SELECT cr.client_id AS tenant_id, cr.church_name, " +
            "       s.username, s.password, s.demo_password, s.church AS church_flag, " +
            "       COALESCE(u.role, 'Staff') AS role, " +
            "       COALESCE(u.first_name || ' ' || u.last_name, '') AS member_name, " +
            "       s.id AS signup_id " +
            "FROM church_registration cr " +
            "JOIN app_user u ON u.client_id = cr.client_id " +
            "JOIN signup s ON s.client_id = u.user_id " +
            "WHERE cr.client_id LIKE ? AND cr.delete_flag = false " +
            "  AND (s.church IS NULL OR s.church = false) " +

            "UNION ALL " +

            // (3) Member-portal and child-portal logins
            //     (signup.client_id = family_member.member_ref)
            "SELECT cr.client_id AS tenant_id, cr.church_name, " +
            "       s.username, s.password, s.demo_password, s.church AS church_flag, " +
            "       CASE WHEN LOWER(fm.role) = 'child' THEN 'Child Portal' " +
            "            ELSE 'Member Portal' END AS role, " +
            "       COALESCE(fm.first_name || ' ' || fm.last_name, '') AS member_name, " +
            "       s.id AS signup_id " +
            "FROM church_registration cr " +
            "JOIN family_member fm ON fm.app_client_id = cr.client_id " +
            "                      AND fm.member_ref IS NOT NULL " +
            "JOIN signup s ON s.client_id = fm.member_ref " +
            "WHERE cr.client_id LIKE ? AND cr.delete_flag = false " +
            "  AND (s.church IS NULL OR s.church = false) " +

            "ORDER BY tenant_id DESC, signup_id ASC";

        java.util.LinkedHashMap<String, Map<String, Object>> byTenant = new java.util.LinkedHashMap<>();
        jdbc.query(sql, ps -> {
            String pattern = DEMO_CLIENT_PREFIX + "%";
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
        }, rs -> {
            String tenantId = rs.getString("tenant_id");
            Map<String, Object> tenant = byTenant.computeIfAbsent(tenantId, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("clientId",    k);
                m.put("churchName",  rs2safeString(rs, "church_name"));
                m.put("credentials", new ArrayList<Map<String, Object>>());
                return m;
            });
            String username = rs2safeString(rs, "username");
            if (username == null || username.isEmpty()) return; // no signup rows yet
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> creds = (List<Map<String, Object>>) tenant.get("credentials");
            Map<String, Object> row = new LinkedHashMap<>();
            String role = rs2safeString(rs, "role");
            row.put("role",       role);
            row.put("username",   username);
            // signup.password holds only the one-way BCrypt hash, which can't be
            // used to sign in. The real generated password is persisted in
            // signup.demo_password at creation, so surface that. Tenants created
            // before this column existed have no stored value — show a clear
            // marker rather than a misleading or blank password.
            String demoPwd = rs2safeString(rs, "demo_password");
            row.put("password",   (demoPwd == null || demoPwd.isEmpty())
                    ? "(unavailable — reload an older tenant; re-create to view)"
                    : demoPwd);
            row.put("memberName", rs2safeString(rs, "member_name"));
            row.put("clientId",   tenantId);
            // signup.church is nullable in DB, but we backfill on every load
            // so it should always come back true/false from here on.
            row.put("church",   rs.getObject("church_flag") != null && rs.getBoolean("church_flag"));
            creds.add(row);
        });
        return new ArrayList<>(byTenant.values());
    }

    /**
     * Resets the password for a single demo-tenant login and returns the new
     * plaintext so it can be displayed.
     *
     * <p>Legacy demo tenants were created before {@code signup.demo_password}
     * existed, so their original plaintext was never stored — only the one-way
     * BCrypt hash remains, which cannot be reversed. For those, the only way to
     * surface a usable password is to set a fresh one. This generates a new
     * password, stores both the BCrypt hash ({@code signup.password}, used for
     * login) and the plaintext ({@code signup.demo_password}, used for display),
     * unlocks/activates the row, and returns the new value.
     *
     * <p>Strictly guarded: refuses to touch any login that does not belong to a
     * {@code DEMO-} tenant, so a real customer password can never be reset here.
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> resetDemoPassword(String username) {
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("Username is required.");
        username = username.trim();

        // Guard: the username must resolve to a DEMO- tenant via any of the three
        // login models (church login, staff login, or member/child portal login).
        String guardSql =
            "SELECT COUNT(*) FROM signup s " +
            "LEFT JOIN church_registration crc ON crc.client_id = s.client_id " +
            "LEFT JOIN app_user u             ON u.user_id      = s.client_id " +
            "LEFT JOIN church_registration cru ON cru.client_id = u.client_id " +
            "LEFT JOIN family_member fm        ON fm.member_ref  = s.client_id " +
            "LEFT JOIN church_registration crm ON crm.client_id = fm.app_client_id " +
            "WHERE UPPER(s.username) = UPPER(?) " +
            "  AND (crc.client_id LIKE ? OR cru.client_id LIKE ? OR crm.client_id LIKE ?)";
        String like = DEMO_CLIENT_PREFIX + "%";
        Integer count = jdbc.queryForObject(guardSql, Integer.class, username, like, like, like);
        if (count == null || count == 0)
            throw new IllegalArgumentException("This login is not part of a demo tenant — refusing to reset.");

        SignUp su = loginRepo.findActiveByUsername(username).orElse(null);
        if (su == null)
            throw new IllegalArgumentException("No active login found for username: " + username);

        String newPwd = randomPassword();
        su.setPassword(PasswordUtil.encode(newPwd));
        su.setDemoPassword(newPwd);
        su.setLocked(false);
        su.setActive(true);
        su.setUpdated(new Date());
        loginRepo.save(su);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", su.getUsername());
        out.put("password", newPwd);
        return out;
    }

    /**
     * Inspects the signup + service_client tables for a given username and
     * returns everything that affects login eligibility. Backs the
     * {@code /api/serviceadmin/test-data/verify-login} diagnostic endpoint
     * so the operator can confirm exactly what the DB sees vs what they typed.
     */
    public Map<String, Object> verifySignupLogin(String username) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", username);
        // Look up the signup row. Use a raw SQL query so we see the exact
        // column values regardless of any JPA mapping quirks.
        try {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT s.id, s.username, s.password, s.active, s.deleted, s.locked, " +
                "       s.church, s.client_id, s.church_id " +
                "FROM signup s WHERE s.username = ?", username);
            out.put("signupFound", true);
            out.put("signupId",     row.get("id"));
            out.put("storedUsername", row.get("username"));
            out.put("storedPassword", row.get("password"));
            out.put("active",       row.get("active"));
            out.put("deleted",      row.get("deleted"));
            out.put("locked",       row.get("locked"));
            out.put("church",       row.get("church"));
            out.put("clientId",     row.get("client_id"));
            out.put("churchId",     row.get("church_id"));

            // For non-church rows, signup.client_id is app_user.user_id. Resolve
            // to the tenant clientId so we can check the matching service_client.
            String tenantClientId;
            if (Boolean.TRUE.equals(row.get("church"))) {
                tenantClientId = (String) row.get("client_id");
            } else {
                tenantClientId = jdbc.query(
                    "SELECT client_id FROM app_user WHERE user_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    row.get("client_id"));
            }
            out.put("tenantClientId", tenantClientId);

            // Did we create a service_client row that satisfies the login validator?
            if (tenantClientId != null) {
                java.util.List<Map<String, Object>> scRows = jdbc.queryForList(
                    "SELECT status, delete_flag, end_date FROM service_client WHERE client_id = ?",
                    tenantClientId);
                out.put("serviceClientCount", scRows.size());
                if (!scRows.isEmpty()) out.put("serviceClient", scRows.get(0));
            }
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            out.put("signupFound", false);
        }
        return out;
    }

    private static String rs2safeString(java.sql.ResultSet rs, String col) {
        try {
            String v = rs.getString(col);
            return v == null ? "" : v;
        } catch (java.sql.SQLException e) {
            return "";
        }
    }

    // ── Seeders ───────────────────────────────────────────────────────────

    private ChurchRegistration seedChurchRegistration(String clientId, String churchName) {
        ChurchRegistration cr = new ChurchRegistration();
        cr.setClientId(clientId);
        cr.setFirstName("Demo");
        cr.setLastName("Admin");
        cr.setChurchName(churchName);
        cr.setEmail("admin@" + clientId.toLowerCase() + ".test");
        cr.setPhone("555-0100");
        cr.setNonProfit(true);
        return churchRegRepo.save(cr);
    }

    /**
     * Creates the service_client row that the login validator joins on.
     * Without this row, every login attempt for the demo tenant returns 403
     * from {@code countValidChurchLogin} / {@code countValidNonChurchLogin}.
     *
     * <p>Active for one year so demo credentials remain usable long enough
     * to be useful for testing.
     */
    private ServiceClient seedServiceClient(String clientId, String churchName) {
        ServiceClient sc = new ServiceClient();
        sc.setClientId(clientId);
        sc.setChurchName(churchName);
        sc.setName("Demo Admin");
        sc.setEmail("admin@" + clientId.toLowerCase() + ".test");
        sc.setStartDate(LocalDate.now());
        sc.setEndDate(LocalDate.now().plusYears(1));
        sc.setActivePeriod(12);
        sc.setActivePeriodUnit("MONTHS");
        sc.setStatus("Active");        // login validator checks for this exact value
        sc.setPaymentStatus("PAID");
        sc.setSubscriptionType("FULL");
        sc.setApproved(true);
        sc.setDeleteFlag(false);
        return serviceClientRepo.save(sc);
    }

    /**
     * Creates every staff account for the demo tenant — Church login plus one
     * fully linked account for each non-Church role (SuperAdmin, Admin,
     * Accountant, User).
     *
     * <p>Every non-Church account gets:
     * <ol>
     *   <li>A {@link FamilyMember} profile in its own one-person family so the
     *       member record is immediately available in the directory and
     *       contribution history.</li>
     *   <li>An {@link AppUser} carrying the staff role, tagged with a
     *       {@code link_group} UUID.</li>
     *   <li>A {@link SignUp} row keyed by {@code app_user.user_id}, also tagged
     *       with the same {@code link_group} so account-switching works.</li>
     * </ol>
     *
     * <p>The Church-level login has no AppUser — it is keyed directly by
     * {@code tenant clientId} with {@code church = true}.
     *
     * <p>Usernames use readable first.last format with a short unique suffix so
     * re-running Load on the same DB never hits the unique constraint on
     * {@code signup.username}.
     */
    private void seedAllStaffLinked(String clientId, Integer churchRegId,
                                    List<Map<String, Object>> creds, Random rng) {
        String suffix = clientId.substring(DEMO_CLIENT_PREFIX.length());
        // Last 6 chars keep usernames short while guaranteeing uniqueness
        String pretty = suffix.length() > 6 ? suffix.substring(suffix.length() - 6) : suffix;

        // ── (A) Church-level login ──────────────────────────────────────────
        // signup.client_id = tenant clientId, church = true.
        // No AppUser or FamilyMember — the Church login manages the org account.
        String churchUsername = "church_" + pretty;
        String churchPassword = randomPassword();
        SignUp churchRow = new SignUp();
        churchRow.setClientId(clientId);
        churchRow.setUsername(churchUsername);
        churchRow.setPassword(PasswordUtil.encode(churchPassword));
        churchRow.setDemoPassword(churchPassword);
        churchRow.setActive(true);
        churchRow.setDeleted(false);
        churchRow.setLocked(false);
        churchRow.setChurch(true);
        churchRow.setChurchId(churchRegId);
        churchRow.setCreated(new Date());
        loginRepo.save(churchRow);
        Map<String, Object> churchCred = credRow("Church", churchUsername, churchPassword, true, clientId);
        churchCred.put("memberName", "Church Administrator");
        creds.add(churchCred);

        // ── (B) Non-Church staff — one per role, each linked to a member ───
        // { firstName, lastName, role, usernameBase }
        String[][] specs = {
            { "Grace",  "Pastor",    "SuperAdmin", "grace.pastor"    },
            { "Daniel", "Manager",   "Admin",      "daniel.manager"  },
            { "Ruth",   "Treasurer", "Accountant", "ruth.treasurer"  },
            { "Sam",    "Wilson",    "User",       "sam.wilson"      },
        };

        for (String[] s : specs) {
            String linkGroup = java.util.UUID.randomUUID().toString();
            String username  = s[3] + "_" + pretty;
            String password  = randomPassword();

            // 1. One-person family + member profile
            Family fam = new Family();
            fam.setAppClientId(clientId);
            fam.setInactive(false);
            fam.setDeleteFlag(false);
            familyRepo.save(fam);

            FamilyMember fm = newMember(clientId, fam, "Head", s[0], s[1], "Member",
                    rng.nextBoolean() ? "Male" : "Female", 35 + rng.nextInt(20));
            fm.setIncludeContributions(true);
            familyMemberRepo.save(fm);

            // 2. AppUser with role + link_group.
            // Email MUST match the FamilyMember email (first.last@...) so that
            // getMemberFamily()'s email-based staff→member lookup succeeds and
            // the Family tab shows the member's household data.
            // newMember() generates: (first + "." + last).toLowerCase() + "@" + clientId.toLowerCase() + ".test"
            String memberEmail = (s[0] + "." + s[1]).toLowerCase() + "@" + clientId.toLowerCase() + ".test";
            AppUser u = new AppUser();
            u.setClientId(clientId);
            u.setFirstName(s[0]);
            u.setLastName(s[1]);
            u.setEmail(memberEmail);
            u.setRole(s[2]);
            u.setEnabled(true);
            u.setDeleteFlag(false);
            u.setLinkGroup(linkGroup);
            AppUser savedUser = appUserRepo.save(u);

            // 3. SignUp keyed by app_user.user_id (non-church login model)
            SignUp su = new SignUp();
            su.setClientId(savedUser.getUserId());
            su.setUsername(username);
            su.setPassword(PasswordUtil.encode(password));
            su.setDemoPassword(password);
            su.setActive(true);
            su.setDeleted(false);
            su.setLocked(false);
            su.setChurch(false);
            su.setLinkGroup(linkGroup);
            su.setCreated(new Date());
            loginRepo.save(su);

            Map<String, Object> row = credRow(s[2], username, password, false, savedUser.getUserId());
            row.put("memberId",   fm.getId());
            row.put("memberName", s[0] + " " + s[1]);
            creds.add(row);
        }
    }

    private static Map<String, Object> credRow(String role, String username, String password,
                                               boolean church, String clientId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role",     role);
        row.put("username", username);
        row.put("password", password);
        row.put("church",   church);
        row.put("clientId", clientId);
        return row;
    }

    private List<SubSource> seedAccountingTaxonomy(String clientId) {
        // Two main sources, each with a couple of sub-sources (funds).
        MainSource tithes = newMainSource(clientId, "Tithes & Offerings");
        MainSource giving = newMainSource(clientId, "Designated Giving");
        mainSourceRepo.save(tithes);
        mainSourceRepo.save(giving);

        List<SubSource> funds = new ArrayList<>();
        funds.add(newSubSource(clientId, tithes, "General Fund"));
        funds.add(newSubSource(clientId, tithes, "Building Fund"));
        funds.add(newSubSource(clientId, giving, "Missions"));
        funds.add(newSubSource(clientId, giving, "Youth Ministry"));
        subSourceRepo.saveAll(funds);
        return funds;
    }

    private List<TransactionType> seedTransactionTypes(String clientId) {
        List<TransactionType> types = new ArrayList<>();
        for (String name : new String[] { "Cash", "Check", "Credit Card", "ACH / Bank Transfer" }) {
            TransactionType t = new TransactionType();
            t.setTypeName(name);
            t.setAppClientId(clientId);
            t.setDeleteFlag(false);
            types.add(t);
        }
        transactionTypeRepo.saveAll(types);
        return types;
    }

    private List<FamilyMember> seedFamiliesAndMembers(String clientId, int familyCount, Random rng) {
        String[] surnames = { "Anderson","Brooks","Chen","Davis","Edwards",
                              "Foster","Gomez","Hughes","Iyer","Johnson" };
        String[] heads    = { "John","Mary","David","Sarah","Michael",
                              "Linda","James","Priya","Robert","Emily" };
        String[] spouses  = { "Jane","Mark","Susan","Daniel","Rebecca",
                              "Carlos","Amy","Raj","Karen","Tom" };
        String[] kids     = { "Liam","Olivia","Noah","Ava","Ethan",
                              "Sophia","Mason","Isabella","Lucas","Mia" };

        List<FamilyMember> all = new ArrayList<>();
        for (int i = 0; i < familyCount; i++) {
            String surname = surnames[i % surnames.length];
            Family fam = new Family();
            fam.setAppClientId(clientId);
            fam.setInactive(false);
            fam.setDeleteFlag(false);
            familyRepo.save(fam);

            FamilyMember head = newMember(clientId, fam, "Head",
                    heads[i % heads.length], surname, "Member",
                    rng.nextBoolean() ? "Male" : "Female",
                    35 + rng.nextInt(25));
            head.setIncludeContributions(true);
            familyMemberRepo.save(head);
            all.add(head);

            // 60% of families have a spouse
            if (rng.nextInt(10) < 6) {
                FamilyMember spouse = newMember(clientId, fam, "Wife",
                        spouses[i % spouses.length], surname, "Member",
                        "Female", 33 + rng.nextInt(22));
                spouse.setIncludeContributions(true);
                familyMemberRepo.save(spouse);
                all.add(spouse);
            }
            // 40% of families have one kid
            if (rng.nextInt(10) < 4) {
                FamilyMember kid = newMember(clientId, fam, "Son",
                        kids[i % kids.length], surname, "Member",
                        rng.nextBoolean() ? "Male" : "Female",
                        6 + rng.nextInt(11));
                familyMemberRepo.save(kid);
                all.add(kid);
            }
        }
        return all;
    }

    private int seedContributions(String clientId, List<FamilyMember> members,
                                  List<SubSource> funds, List<TransactionType> txnTypes,
                                  int count, Random rng) {
        // Spread roughly across the current calendar year.
        int year = LocalDate.now().getYear();
        // Only adults contribute (skip kids).
        List<FamilyMember> contributors = new ArrayList<>();
        for (FamilyMember m : members) {
            if (m.isIncludeContributions()) contributors.add(m);
        }
        if (contributors.isEmpty()) return 0;

        int created = 0;
        for (int i = 0; i < count; i++) {
            FamilyMember donor = contributors.get(rng.nextInt(contributors.size()));
            SubSource     fund = funds.get(rng.nextInt(funds.size()));
            TransactionType tt = txnTypes.get(rng.nextInt(txnTypes.size()));
            int month = 1 + rng.nextInt(12);
            int day   = 1 + rng.nextInt(28);
            BigDecimal amount = BigDecimal.valueOf(25 + rng.nextInt(476)); // $25–$500

            Income inc = new Income();
            inc.setMember(donor);
            inc.setSubSource(fund);
            inc.setTransactionType(tt);
            inc.setIncomeDate(LocalDate.of(year, month, day));
            inc.setAmount(amount);
            inc.setNote("Demo contribution");
            inc.setAppClientId(clientId);
            inc.setDeleteFlag(false);
            inc.setQuickAdd(false);
            inc.setCreatedBy("system");
            incomeRepo.save(inc);
            created++;
        }
        return created;
    }

    private int seedEvents(String clientId, int count, Random rng) {
        String[] names = { "Spring Picnic", "Christmas Service", "Easter Brunch",
                           "Summer Retreat", "Worship Night" };
        LocalDate base = LocalDate.now();
        int created = 0;
        for (int i = 0; i < count; i++) {
            ChurchEvent e = new ChurchEvent();
            e.setEventName(names[i % names.length]);
            e.setEventCode(clientId + "-EVT-" + (i + 1) + "-" + System.nanoTime());
            e.setEventType("In-Person");
            e.setEventDate(base.plusDays(14L + (long) rng.nextInt(60)));
            e.setStartTime("18:00");
            e.setEndTime("20:00");
            e.setShowRegistrants(true);
            e.setAllowMaybeRsvp(false);
            e.setGenerateQrCode(false);
            e.setSelfCheckinEnabled(false);
            e.setFoodAvailable(false);
            e.setAccommodationAvailable(false);
            e.setAppClientId(clientId);
            e.setDeleteFlag(false);
            churchEventRepo.save(e);
            created++;
        }
        return created;
    }

    // (seedMinistries was replaced by the richer seedGroups, which creates one
    //  group per type with members + a sample message.)

    private int seedMeetings(String clientId, int count, Random rng) {
        // Create one MeetingType then hang Meeting rows off it.
        MeetingType mt = new MeetingType();
        mt.setTypeName("Sunday Service");
        mt.setAppClientId(clientId);
        mt.setDeleteFlag(false);
        MeetingType savedType = meetingTypeRepo.save(mt);

        int created = 0;
        LocalDate base = LocalDate.now();
        for (int i = 0; i < count; i++) {
            Meeting m = new Meeting();
            m.setMeetingType(savedType);
            m.setMeetingDate(base.plusDays(7L + (long) rng.nextInt(30)));
            m.setStartTime("10:00");
            m.setEndTime("11:30");
            m.setOccurrence("One-time");
            m.setNote("Demo meeting #" + (i + 1));
            m.setAppClientId(clientId);
            m.setDeleteFlag(false);
            meetingRepo.save(m);
            created++;
        }
        return created;
    }

    /**
     * Creates {@code count} children. For each, we create:
     *  - a FamilyMember with role "Child" (so the child has a real member
     *    profile that can be linked to a portal login and a SsStudent), and
     *  - a KmChild row for the kids-ministry check-in flow.
     *
     * @return the list of child FamilyMember records (used by the child
     *         portal-login and Sunday-school seeders).
     */
    private List<FamilyMember> seedKidsMinistry(String clientId, List<FamilyMember> members,
                                                int count, Random rng) {
        List<FamilyMember> childMembers = new ArrayList<>();
        if (members.isEmpty()) return childMembers;
        String[] firstNames = { "Lily", "Owen", "Ava", "Ethan", "Mia", "Liam" };

        // Only attach children to adult parents (skip any existing child rows).
        List<FamilyMember> parents = new ArrayList<>();
        for (FamilyMember m : members) {
            String r = m.getRole() == null ? "" : m.getRole();
            if (!r.equalsIgnoreCase("Son") && !r.equalsIgnoreCase("Daughter")
                    && !r.equalsIgnoreCase("Child")) parents.add(m);
        }
        if (parents.isEmpty()) parents = members;

        for (int i = 0; i < count; i++) {
            FamilyMember parent = parents.get(i % parents.size());
            String first = firstNames[i % firstNames.length];
            String last  = parent.getLastName() != null ? parent.getLastName() : "Demo";

            // Real child member profile in the parent's family.
            FamilyMember childFm = newMember(clientId, parent.getFamily(), "Child",
                    first, last, "Member",
                    rng.nextBoolean() ? "Male" : "Female", 6 + rng.nextInt(8));
            familyMemberRepo.save(childFm);
            childMembers.add(childFm);

            // KmChild check-in record tied to the parent.
            KmChild c = new KmChild();
            c.setClientId(clientId);
            c.setFirstName(first);
            c.setLastName(last);
            c.setDob(LocalDate.now().minusYears(5 + rng.nextInt(8)));
            c.setGender(childFm.getGender());
            c.setGrade("K" + (1 + rng.nextInt(5)));
            c.setFamilyMemberId(parent.getId());
            c.setParentName(((parent.getFirstName() != null ? parent.getFirstName() : "") + " "
                          + (parent.getLastName()  != null ? parent.getLastName()  : "")).trim());
            c.setParentPhone("555-01" + String.format("%02d", i + 1));
            c.setParentEmail(parent.getEmail());
            c.setInactive(false);
            c.setDeleteFlag(false);
            kmChildRepo.save(c);
        }
        return childMembers;
    }

    private int[] seedPledges(String clientId, List<FamilyMember> members,
                              List<SubSource> funds, int campaignCount, int pledgesPerCampaign,
                              Random rng) {
        int campaigns = 0, pledges = 0;
        for (int i = 0; i < campaignCount; i++) {
            SubSource fund = funds.get(i % funds.size());
            PledgeCampaign c = new PledgeCampaign();
            c.setClientId(clientId);
            c.setName("Building Fund " + LocalDate.now().getYear());
            c.setSubSourceId(fund.getId());
            c.setDescription("Auto-allocates contributions made to " + fund.getSourceName());
            c.setTargetAmount(BigDecimal.valueOf(25000));
            c.setEndDate(LocalDate.now().plusMonths(6));
            c.setStatus("Active");
            c.setDeleteFlag(false);
            pledgeCampaignRepo.save(c);
            campaigns++;

            // Take up to N adult members as pledgers.
            int issued = 0;
            for (FamilyMember m : members) {
                if (issued >= pledgesPerCampaign) break;
                if (!m.isIncludeContributions()) continue;
                PledgeMember p = new PledgeMember();
                p.setClientId(clientId);
                p.setCampaignId(c.getId());
                p.setFamilyMemberId(m.getId());
                if (m.getFamily() != null) p.setFamilyId(m.getFamily().getId());
                BigDecimal pledged = BigDecimal.valueOf(500 + rng.nextInt(2001)); // $500–$2,500
                p.setPledgeAmount(pledged);
                p.setMonthlyAmount(pledged.divide(BigDecimal.valueOf(12), 2,
                        java.math.RoundingMode.HALF_UP));
                p.setAmountCollected(BigDecimal.ZERO);
                p.setDeleteFlag(false);
                pledgeMemberRepo.save(p);
                pledges++;
                issued++;
            }
        }
        return new int[] { campaigns, pledges };
    }

    // ── Small entity builders ─────────────────────────────────────────────

    private MainSource newMainSource(String clientId, String name) {
        MainSource ms = new MainSource();
        ms.setSourceName(name);
        ms.setAppClientId(clientId);
        ms.setDeleteFlag(false);
        return ms;
    }

    private SubSource newSubSource(String clientId, MainSource parent, String name) {
        SubSource ss = new SubSource();
        ss.setSourceName(name);
        ss.setMainSource(parent);
        ss.setAppClientId(clientId);
        ss.setDeleteFlag(false);
        return ss;
    }

    private FamilyMember newMember(String clientId, Family fam, String role,
                                   String first, String last, String memberType,
                                   String gender, int age) {
        FamilyMember m = new FamilyMember();
        m.setFamily(fam);
        m.setRole(role);
        m.setFirstName(first);
        m.setLastName(last);
        m.setMemberType(memberType);
        m.setGender(gender);
        LocalDate today = LocalDate.now();
        LocalDate bday  = today.minusYears(age);
        m.setBirthdayMonth(bday.getMonthValue());
        m.setBirthdayDay(bday.getDayOfMonth());
        m.setBirthdayYear(bday.getYear());
        m.setEmail((first + "." + last).toLowerCase() + "@" + clientId.toLowerCase() + ".test");
        m.setPhone("555-02" + String.format("%02d", Math.abs((first + last).hashCode()) % 100));
        m.setInactive(false);
        m.setIncludeContributions(false);  // overridden for adults by caller
        m.setDeleteFlag(false);
        m.setAppClientId(clientId);
        return m;
    }

    /** Null-safe lowercase, used to build usernames from member names. */
    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    // ── seedStaffLinkedMembers removed — replaced by seedAllStaffLinked ─────
    // All staff accounts (Church + SuperAdmin + Admin + Accountant + User) are
    // now created with linked member profiles in a single pass by
    // seedAllStaffLinked(), called from loadSmallDemo() before families are
    // seeded. The old split approach (seedUsers for orphan staff +
    // seedStaffLinkedMembers for linked staff) is no longer used.

    // ── Member portal logins ──────────────────────────────────────────────

    /**
     * Creates member-portal sign-ups for the first {@code count} contributing
     * adults. A member-portal login has {@code church=false} and
     * {@code client_id = family_member.member_ref} (the MBR&lt;token&gt;), which
     * is exactly what {@code LoginController} looks up via
     * {@code findByMemberRef}.
     */
    private int seedMemberPortalLogins(String clientId, List<FamilyMember> members,
                                       List<Map<String, Object>> creds, int count) {
        int created = 0;
        for (FamilyMember m : members) {
            if (created >= count) break;
            if (!m.isIncludeContributions()) continue;       // adults only
            if (m.getMemberRef() == null) continue;          // need the MBR token
            String username = ("member_" + safeLower(m.getFirstName()) + "_"
                    + Math.abs(m.getId())).replaceAll("[^a-z0-9_]", "");
            String password = randomPassword();

            SignUp su = new SignUp();
            su.setClientId(m.getMemberRef());   // MBR<token> = login key for members
            su.setUsername(username);
            su.setPassword(PasswordUtil.encode(password));
            su.setDemoPassword(password);
            su.setActive(true);
            su.setDeleted(false);
            su.setLocked(false);
            su.setChurch(false);
            su.setCreated(new Date());
            loginRepo.save(su);

            Map<String, Object> row = credRow("Member (portal)", username, password, false,
                    m.getMemberRef());
            row.put("memberId", m.getId());
            row.put("memberName", ((m.getFirstName() != null ? m.getFirstName() : "") + " "
                    + (m.getLastName() != null ? m.getLastName() : "")).trim());
            creds.add(row);
            created++;
        }
        return created;
    }

    /**
     * Creates child-portal sign-ups for the first {@code count} children.
     * Same shape as member-portal logins (church=false, client_id=member_ref);
     * the role on the linked FamilyMember is "Child", which the front-end uses
     * to show the simplified Kids Portal.
     */
    private int seedChildPortalLogins(String clientId, List<FamilyMember> childMembers,
                                      List<Map<String, Object>> creds, int count) {
        int created = 0;
        for (FamilyMember c : childMembers) {
            if (created >= count) break;
            if (c.getMemberRef() == null) continue;
            String username = ("child_" + safeLower(c.getFirstName()) + "_"
                    + Math.abs(c.getId())).replaceAll("[^a-z0-9_]", "");
            String password = randomPassword();

            SignUp su = new SignUp();
            su.setClientId(c.getMemberRef());
            su.setUsername(username);
            su.setPassword(PasswordUtil.encode(password));
            su.setDemoPassword(password);
            su.setActive(true);
            su.setDeleted(false);
            su.setLocked(false);
            su.setChurch(false);
            su.setCreated(new Date());
            loginRepo.save(su);

            Map<String, Object> row = credRow("Child (portal)", username, password, false,
                    c.getMemberRef());
            row.put("memberId", c.getId());
            row.put("memberName", ((c.getFirstName() != null ? c.getFirstName() : "") + " "
                    + (c.getLastName() != null ? c.getLastName() : "")).trim());
            creds.add(row);
            created++;
        }
        return created;
    }

    // ── Purposes (expense categories) ─────────────────────────────────────

    private List<Purpose> seedPurposes(String clientId) {
        String[] names = {
            "Building Fund", "Missions", "Charity", "Worship Support",
            "Utilities", "Rent", "Salaries", "Ministry Supplies"
        };
        List<Purpose> out = new ArrayList<>();
        for (String n : names) {
            Purpose p = new Purpose();
            p.setPurposeName(n);
            p.setAppClientId(clientId);
            p.setDeleteFlag(false);
            out.add(purposeRepo.save(p));
        }
        return out;
    }

    // ── Groups with members + a sample message ────────────────────────────

    /** @return [groupCount, groupMemberCount, messageCount] */
    private int[] seedGroups(String clientId, List<FamilyMember> members, Random rng) {
        String[] groupNames = {
            "Worship Team", "Men's Fellowship", "Women's Fellowship",
            "Youth Group", "Prayer Warriors", "Bible Study"
        };
        int groups = 0, gMembers = 0, messages = 0;

        // Pick member-portal-capable adults to act as senders/recipients for the
        // sample message (member_message FKs reference family_member ids).
        List<FamilyMember> adults = new ArrayList<>();
        for (FamilyMember m : members) if (m.isIncludeContributions()) adults.add(m);

        for (int i = 0; i < groupNames.length; i++) {
            Group g = new Group();
            g.setGroupName(groupNames[i]);
            g.setAppClientId(clientId);
            g.setDeleteFlag(false);
            groupRepo.save(g);
            groups++;

            // 3–4 members each (first one is the implicit leader).
            int memberCount = 3 + rng.nextInt(2);
            for (int j = 0; j < memberCount && j < members.size(); j++) {
                FamilyMember m = members.get((i + j) % members.size());
                GroupMember gm = new GroupMember();
                gm.setGroup(g);
                gm.setFirstName(m.getFirstName());
                gm.setLastName(m.getLastName());
                gm.setEmail(m.getEmail());
                gm.setAppClientId(clientId);
                gm.setDeleteFlag(false);
                groupMemberRepo.save(gm);
                gMembers++;
            }
        }

        // One sample member-to-member message so the Chat/Groups demo isn't empty.
        if (adults.size() >= 2) {
            FamilyMember from = adults.get(0);
            FamilyMember to   = adults.get(1);
            MemberMessage msg = new MemberMessage();
            msg.setSenderMemberId(from.getId());
            msg.setSenderName(((from.getFirstName() != null ? from.getFirstName() : "") + " "
                    + (from.getLastName() != null ? from.getLastName() : "")).trim());
            msg.setRecipientMemberId(to.getId());
            msg.setBody("Welcome to the group! Looking forward to serving together. 🙏");
            // sentAt is an Instant and is auto-set by the entity's @PrePersist.
            msg.setAppClientId(clientId);
            msg.setDeletedBySender(false);
            msg.setDeletedByRecipient(false);
            memberMessageRepo.save(msg);
            messages++;
        }
        return new int[] { groups, gMembers, messages };
    }

    // ── Expenses ──────────────────────────────────────────────────────────

    private int seedExpenses(String clientId, List<Purpose> purposes,
                             List<SubSource> funds, List<TransactionType> txnTypes, Random rng) {
        if (purposes.isEmpty() || funds.isEmpty()) return 0;
        // The expense main_source FK points at a MainSource. Derive it from
        // the first fund's parent so the FK is always valid.
        MainSource mainSource = funds.get(0).getMainSource();

        // (purposeName, vendor, amount, recurring)
        Object[][] specs = {
            { "Utilities",        "City Power & Water",  220.00, false },
            { "Rent",             "Grace Property Mgmt", 1800.00, true  },
            { "Ministry Supplies","Faith Bookstore",     145.50, false },
            { "Worship Support",  "SoundWave Audio",     310.00, false },
            { "Salaries",         "Payroll",             2500.00, true  },
            { "Charity",          "Local Food Bank",     500.00, false },
        };
        int created = 0;
        int year = LocalDate.now().getYear();
        for (Object[] s : specs) {
            Purpose purpose = purposes.stream()
                    .filter(p -> p.getPurposeName().equals(s[0]))
                    .findFirst().orElse(purposes.get(0));
            TransactionType tt = txnTypes.isEmpty() ? null
                    : txnTypes.get(rng.nextInt(txnTypes.size()));

            Expense e = new Expense();
            e.setPurpose(purpose);
            e.setMainSource(mainSource);
            e.setTransactionType(tt);
            e.setExpenseDate(LocalDate.of(year, 1 + rng.nextInt(12), 1 + rng.nextInt(28)));
            e.setAmount(BigDecimal.valueOf((Double) s[2]));
            e.setNote("Demo expense — " + s[1]);
            e.setQuickAdd((Boolean) s[3]);   // recurring entries shown in Quick Add
            e.setAppClientId(clientId);
            e.setDeleteFlag(false);
            e.setCreatedBy("system");
            expenseRepo.save(e);
            created++;
        }
        return created;
    }

    // ── Sunday school: class + teacher + students + exam + grades ─────────

    /** @return [classes, students, exams, submissions] */
    private int[] seedSundaySchool(String clientId, List<FamilyMember> childMembers, Random rng) {
        if (childMembers.isEmpty()) return new int[] { 0, 0, 0, 0 };

        // 1. One class.
        SsClass cls = new SsClass();
        cls.setClientId(clientId);
        cls.setClassName("Sunday School — Beginners");
        cls.setDescription("Foundational Bible lessons for children.");
        cls.setDeleteFlag(false);
        SsClass savedClass = ssClassRepo.save(cls);

        // 2. A teacher for the class.
        SsTeacher teacher = new SsTeacher();
        teacher.setClientId(clientId);
        teacher.setClassId(savedClass.getId());
        teacher.setTeacherName("Miss Hannah");
        teacher.setEmail("hannah@" + clientId.toLowerCase() + ".test");
        teacher.setDeleteFlag(false);
        SsTeacher savedTeacher = ssTeacherRepo.save(teacher);

        // 3. Enrol the children as students (linked to their member profiles).
        List<SsStudent> students = new ArrayList<>();
        for (FamilyMember c : childMembers) {
            SsStudent st = new SsStudent();
            st.setClientId(clientId);
            st.setClassId(savedClass.getId());
            st.setTeacherId(savedTeacher.getId());
            st.setFamilyMemberId(c.getId());
            st.setMemberRef(c.getMemberRef());
            st.setStudentName(((c.getFirstName() != null ? c.getFirstName() : "") + " "
                    + (c.getLastName() != null ? c.getLastName() : "")).trim());
            st.setContactEmail(c.getEmail());
            st.setDeleteFlag(false);
            students.add(ssStudentRepo.save(st));
        }

        // 4. One published exam with a few questions.
        SsExam exam = new SsExam();
        exam.setClientId(clientId);
        exam.setClassId(savedClass.getId());
        exam.setExamTitle("Lesson 1 Quiz — Creation");
        exam.setTotalQuestions(3);
        exam.setRequiredQuestions(3);
        exam.setDurationMinutes(15);
        exam.setExamDate(LocalDate.now().minusDays(7));
        exam.setStatus("Published");
        exam.setCopyToHoh(false);
        exam.setDeleteFlag(false);
        SsExam savedExam = ssExamRepo.save(exam);

        // (questionText, optionsJson, correctAnswer, marks)
        Object[][] q = {
            { "On which day did God create light?",
              "[\"Day 1\",\"Day 2\",\"Day 3\",\"Day 4\"]", "Day 1", 1 },
            { "How many days did creation take?",
              "[\"5\",\"6\",\"7\",\"8\"]", "6", 1 },
            { "Who were the first man and woman?",
              "[\"Noah & Sarah\",\"Adam & Eve\",\"Cain & Abel\",\"Abraham & Hagar\"]",
              "Adam & Eve", 1 },
        };
        int order = 0;
        for (Object[] qq : q) {
            SsQuestion question = new SsQuestion();
            question.setClientId(clientId);
            question.setExamId(savedExam.getId());
            question.setQuestionType("MCQ");
            question.setQuestionText((String) qq[0]);
            question.setOptionsJson((String) qq[1]);
            question.setCorrectAnswer((String) qq[2]);
            question.setMarks((Integer) qq[3]);
            question.setSortOrder(order++);
            question.setDeleteFlag(false);
            ssQuestionRepo.save(question);
        }

        // 5. Graded submissions for each student so the Kids Portal shows grades.
        int submissions = 0;
        for (SsStudent st : students) {
            int score = 1 + rng.nextInt(3);   // 1–3 out of 3
            SsSubmission sub = new SsSubmission();
            sub.setClientId(clientId);
            sub.setExamId(savedExam.getId());
            sub.setStudentId(st.getId());
            sub.setStartedAt(java.time.LocalDateTime.now().minusDays(6));
            sub.setSubmittedAt(java.time.LocalDateTime.now().minusDays(6).plusMinutes(10));
            sub.setStatus("Graded");
            sub.setAutoMarks(score);
            sub.setManualMarks(0);
            sub.setTotalMarks(score);
            sub.setReviewed(true);
            sub.setDeleteFlag(false);
            ssSubmissionRepo.save(sub);
            submissions++;
        }

        return new int[] { 1, students.size(), 1, submissions };
    }
}
