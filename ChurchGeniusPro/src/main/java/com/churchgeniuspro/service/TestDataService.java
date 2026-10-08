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
import com.churchgeniuspro.hibernate.SubscriptionPlan;
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
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
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

    /** Optional: drops cached send permissions when a tenant's plan changes. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MessagingPolicy messagingPolicy;

    /** Client price, billing frequency and subscription history (optional for hand-built tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SubscriptionLifecycleService lifecycle;

    /** M7: builds and runs the catalogue-ordered, tenant-scoped purge. */
    @org.springframework.beans.factory.annotation.Autowired
    private TenantPurgePlanner tenantPurgePlanner;

    /** Optional: carries a subscription extension through to the per-login windows. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DemoAccessService demoAccess;

    /** Test seam — supply the demo access service without a Spring context. */
    public void setDemoAccess(DemoAccessService d) { this.demoAccess = d; }

    /**
     * Sample data for the screens the core seeding leaves empty (Donations,
     * Membership Requests, Attendance, Connect, Prayer, Unsubscribed). Optional so
     * a context without it still provisions; present in the running application.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TrialDemoDataSeeder demoExtras;

    /** Test seam — supply the extra-areas seeder without a Spring context. */
    public void setDemoExtras(TrialDemoDataSeeder s) { this.demoExtras = s; }

    /**
     * The business time zone, as used by the schedulers. Demo dates ("next Sunday",
     * "the six Sundays before") are counted from the creation date in this zone, so
     * a tenant created on a Saturday evening in Kansas is not given Monday's dates
     * by a UTC server.
     */
    static final java.time.ZoneId BUSINESS_ZONE = java.time.ZoneId.of("America/Chicago");

    private static final Logger log = LoggerFactory.getLogger(TestDataService.class);

    /** Marker prefix for every demo clientId. Used by {@link #listDemoClients()}. */
    public static final String DEMO_CLIENT_PREFIX = "DEMO-";

    /**
     * Client-id prefix for self-service trial tenants (the TrialRegistration page).
     *
     * <p>Separate from {@link #DEMO_CLIENT_PREFIX} on purpose. A trial tenant is a
     * real prospective customer who filled in a form; a demo tenant is internal
     * test data. They share the provisioning and the Demo Role Access screen, but
     * they must never be confused in the admin UI, and {@code clearDemoClient}
     * — which deletes a tenant outright — stays DEMO-only so no real prospect can
     * be wiped by demo housekeeping.
     */
    public static final String TRIAL_CLIENT_PREFIX = "TRIAL-";

    /** True for a tenant this service provisioned: demo or trial. */
    public static boolean isManagedTenant(String clientId) {
        return clientId != null
                && (clientId.startsWith(DEMO_CLIENT_PREFIX) || clientId.startsWith(TRIAL_CLIENT_PREFIX));
    }

    /** True for a demo tenant loaded from the Service Admin screen. */
    public static boolean isDemoTenant(String clientId) {
        return clientId != null && clientId.startsWith(DEMO_CLIENT_PREFIX);
    }

    /** True for a self-service trial tenant. */
    public static boolean isTrialTenant(String clientId) {
        return clientId != null && clientId.startsWith(TRIAL_CLIENT_PREFIX);
    }

    /**
     * The short, unique tail of a managed client id, used to keep generated
     * usernames short while still unique. Prefix-agnostic so DEMO- and TRIAL-
     * tenants produce the same shape of username.
     */
    public static String prettySuffix(String clientId) {
        String id = clientId == null ? "" : clientId;
        for (String prefix : new String[] { DEMO_CLIENT_PREFIX, TRIAL_CLIENT_PREFIX }) {
            if (id.startsWith(prefix)) { id = id.substring(prefix.length()); break; }
        }
        return id.length() > 6 ? id.substring(id.length() - 6) : id;
    }

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

    /** Source of truth for which plans exist — never a hardcoded list. */
    private final SubscriptionPlanRepository   planRepo;
    /** Needed to drop the 5-minute plan cache when a demo tenant's plan changes. */
    private final SubscriptionService          subscriptionService;

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
                           JdbcTemplate                 jdbc,
                           SubscriptionPlanRepository   planRepo,
                           SubscriptionService          subscriptionService) {
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
        this.planRepo            = planRepo;
        this.subscriptionService = subscriptionService;
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
        return loadSmallDemo(null, null);
    }

    /**
     * Loads a demo tenant on a chosen subscription plan with a chosen expiry.
     *
     * <p>Both are recorded on this tenant's own {@code service_client} row, so every demo
     * load carries its own plan and expiry and loading a second one cannot disturb the
     * first — they are different rows.
     *
     * @param planCode  a code from Subscription Plans; blank uses the first active plan
     * @param expiresOn last day these accounts may sign in; {@code null} means one year
     */
    @Transactional
    public Map<String, Object> loadSmallDemo(String planCode, LocalDate expiresOn) {
        String clientId = DEMO_CLIENT_PREFIX + System.currentTimeMillis();
        return provisionTenant(TenantSpec.demo(clientId), planCode, expiresOn);
    }

    /**
     * What a provisioned tenant should look like: who it belongs to, and which
     * logins it gets.
     *
     * <p>Exists so the self-service trial flow can reuse the demo seeding wholesale
     * instead of copying it. A demo tenant and a trial tenant differ only in these
     * fields; everything downstream — the families, contributions, events, groups,
     * kids ministry, Sunday school and pledges — is identical, which is exactly what
     * "the same dummy data as a Demo tenant" means.
     *
     * @param staffRoles which staff logins to create, from {@link #DEMO_ROLES}
     * @param portalLogins how many member-portal and child-portal logins to create
     *                     (one of each per unit; 0 for none). A trial takes one of
     *                     each so its credentials panel and welcome email name a
     *                     single Member Portal and a single Kids Portal; the demo
     *                     tenant keeps the pair it has always had.
     */
    public record TenantSpec(String clientId,
                             String churchName,
                             List<String> staffRoles,
                             int portalLogins,
                             Contact contact) {

        /** True when this tenant gets portal logins at all. */
        public boolean hasPortalLogins() { return portalLogins > 0; }

        /**
         * Who the tenant belongs to.
         *
         * <p>The address is plain text because that is what {@code service_client}
         * stores and what a registration form collects. {@code ChurchRegistration}
         * keeps its address on a separate {@code Address} entity whose state and
         * country are lookup ids, so only its scalar fields are populated here —
         * the same subset the demo seeding has always set.
         */
        public record Contact(String firstName, String lastName, String email, String phone,
                              String addressLine1, String addressLine2, String city,
                              String state, String country, String pinCode, String note) {

            public static Contact of(String firstName, String lastName, String email, String phone) {
                return new Contact(firstName, lastName, email, phone,
                                   null, null, null, null, null, null, null);
            }

            public String fullName() {
                return ((firstName == null ? "" : firstName) + " "
                      + (lastName  == null ? "" : lastName)).trim();
            }
        }

        /** The classic internal demo tenant: every role, portals included. */
        public static TenantSpec demo(String clientId) {
            return new TenantSpec(
                    clientId,
                    "Demo Church " + clientId.substring(DEMO_CLIENT_PREFIX.length()),
                    List.of("SuperAdmin", "Admin", "Accountant", "User"),
                    2,
                    Contact.of("Demo", "Admin",
                               "admin@" + clientId.toLowerCase() + ".test", "555-0100"));
        }
    }

    /**
     * Creates a tenant and its sample data.
     *
     * <p>The single provisioning path. {@link #loadSmallDemo} and the self-service
     * trial registration both go through here, so a change to what a new tenant
     * contains cannot apply to one and miss the other.
     *
     * @param spec      who the tenant is and which logins it gets
     * @param planCode  a code from Subscription Plans; blank uses the first active plan
     * @param expiresOn last day these accounts may sign in; {@code null} means one year
     */
    @Transactional
    public Map<String, Object> provisionTenant(TenantSpec spec, String planCode, LocalDate expiresOn) {
        String resolvedPlan = resolvePlanCode(planCode);
        LocalDate expiry    = expiresOn != null ? expiresOn : com.churchgeniuspro.util.AppClock.today().plusYears(1);
        if (SubscriptionService.isExpired(expiry, com.churchgeniuspro.util.AppClock.today())) {
            throw new IllegalArgumentException(
                    "Expiration date must be in the future — " + expiry + " would create a "
                  + "tenant that nobody can log into.");
        }
        String clientId = spec.clientId();
        String churchName = spec.churchName();
        Random rng = new Random(clientId.hashCode()); // deterministic-per-tenant

        // Idempotent housekeeping: convert any legacy null signup.church values
        // into explicit booleans before we add more rows. Required by spec #3.
        backfillNullChurchFlags();

        Map<String, Integer> counts = new LinkedHashMap<>();

        // 1. ChurchRegistration (the org header). Captured because its id
        //    becomes signup.church_id for the Church-level login row.
        ChurchRegistration church = seedChurchRegistration(clientId, spec);
        counts.put("church", 1);

        // 1b. service_client — REQUIRED for login. countValidChurchLogin /
        //     countValidNonChurchLogin both INNER JOIN this table, so without
        //     a matching active row every demo account would get a 403 at
        //     /login. status=Active, delete_flag=false, end_date > today.
        ServiceClient demoSubscription = seedServiceClient(clientId, spec, resolvedPlan, expiry);

        // 2. Staff accounts — every role gets its own member profile linked via
        //    link_group so role-switching and Member Portal work out of the box.
        //    The returned list is mutable; portal/child logins appended below.
        List<Map<String, Object>> credentials = new ArrayList<>();
        seedAllStaffLinked(clientId, church.getId(), credentials, rng, spec.staffRoles());

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
        int portalLogins = spec.hasPortalLogins()
                ? seedMemberPortalLogins(clientId, members, credentials, spec.portalLogins()) : 0;
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

        // 10. Meetings — a weekly/monthly schedule plus one-time meetings, all in the
        //     future relative to the creation date.
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        int meetingCount = seedMeetings(clientId, today);
        counts.put("meetings", meetingCount);

        // 11. Kids Ministry — 4 children. Returns the created KmChild ids +
        //     their FamilyMember rows so the school + child logins can link.
        List<FamilyMember> childMembers = seedKidsMinistry(clientId, members, 4, rng);
        counts.put("kidsMinistry", childMembers.size());

        // 11b. Child-portal sign-ups (church=false, client_id=MBR<token>) for
        //      two children so they can log into the Kids Portal.
        int childLogins = spec.hasPortalLogins()
                ? seedChildPortalLogins(clientId, childMembers, credentials, spec.portalLogins()) : 0;
        counts.put("childPortalLogins", childLogins);

        // 12. Sunday school — class + teacher + students (children) + exam +
        //     questions + graded submissions so the Kids Portal shows grades.
        int[] schoolStats = seedSundaySchool(clientId, childMembers, rng);
        counts.put("ssClasses",     schoolStats[0]);
        counts.put("ssStudents",    schoolStats[1]);
        counts.put("ssExams",       schoolStats[2]);
        counts.put("ssSubmissions", schoolStats[3]);

        // 13. Pledge campaigns (2) + member pledges (5 per campaign), with a few
        //     payments so progress shows.
        int[] pledges = seedPledges(clientId, members, funds, txnTypes, 2, 5, rng, today);
        counts.put("pledgeCampaigns", pledges[0]);
        counts.put("pledges",         pledges[1]);
        counts.put("pledgePayments",  pledges[2]);

        // 14. Donations, Membership Requests, Attendance, Connect With Us, Prayer
        //     Requests, Unsubscribed List — see TrialDemoDataSeeder.
        if (demoExtras != null) counts.putAll(demoExtras.seed(clientId, members, today));

        // users count reflects EVERY login row created (staff + portal + child).
        counts.put("users", credentials.size());

        log.info("TestDataService: created tenant {} with counts {}", clientId, counts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientId",     clientId);
        out.put("churchName",   churchName);
        out.put("counts",       counts);
        out.put("credentials",  credentials);
        out.put("subscription", describeSubscription(demoSubscription));
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
    // ── One-off: demo data for trial accounts created before it existed ────

    /**
     * Existing trial tenants ({@code TRIAL-}, not deleted), newest first, with the
     * church name. Read-only.
     */
    public List<Map<String, Object>> listTrialTenants() {
        return jdbc.queryForList(
                "SELECT client_id AS \"clientId\", church_name AS \"churchName\", end_date AS \"endDate\", status " +
                "FROM service_client WHERE client_id LIKE ? AND COALESCE(delete_flag, false) = false " +
                "ORDER BY client_id DESC", TRIAL_CLIENT_PREFIX + "%");
    }

    /**
     * Dry run: for each existing trial tenant, which areas a backfill WOULD fill.
     * An area is filled only when the tenant has no data of that kind; everything
     * else is left exactly as it is. Writes nothing.
     */
    public List<Map<String, Object>> previewTrialBackfill() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : listTrialTenants()) {
            String clientId = String.valueOf(t.get("clientId"));
            Map<String, Object> row = new LinkedHashMap<>(t);
            Map<String, Boolean> areas = new LinkedHashMap<>();
            areas.put("meetings", !hasUpcomingMeeting(clientId, today));
            boolean canPledge = !demoAdults(clientId).isEmpty()
                    && !subSourceRepo.findAllActiveByAppUser(clientId).isEmpty()
                    && !transactionTypeRepo.findActiveByAppUser(clientId).isEmpty();
            areas.put("pledges", canPledge && pledgeCampaignRepo.findByClientId(clientId).isEmpty());
            if (demoExtras != null) areas.putAll(demoExtras.wouldFill(clientId, today));
            row.put("wouldFill", areas);
            row.put("demoMembers", demoMembers(clientId).size());
            out.add(row);
        }
        return out;
    }

    /**
     * Fills the empty demo areas of ONE existing trial tenant, in its own transaction
     * (a failure rolls back only this tenant). Never updates or deletes existing rows:
     * every area is skipped when the tenant already has data of that kind, and demo
     * attendance/donations/assignments reference only the demo members this service
     * seeded when the trial was created (identified by their {@code @<tenant>.test}
     * address), never people the church added itself.
     */
    @Transactional
    public Map<String, Integer> backfillTrialDemoData(String clientId) {
        if (!isTrialTenant(clientId)) {
            throw new IllegalArgumentException("Only trial accounts (" + TRIAL_CLIENT_PREFIX + "…) can be filled: " + clientId);
        }
        if (serviceClientRepo.findByClientId(clientId).isEmpty()) {
            throw new IllegalArgumentException("Trial account not found: " + clientId);
        }
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("meetings", seedMeetings(clientId, today));

        List<FamilyMember> demo = demoMembers(clientId);
        List<SubSource> funds = subSourceRepo.findAllActiveByAppUser(clientId);
        List<TransactionType> txnTypes = transactionTypeRepo.findActiveByAppUser(clientId);
        if (!demoAdults(clientId).isEmpty() && !funds.isEmpty() && !txnTypes.isEmpty()) {
            int[] p = seedPledges(clientId, demo, funds, txnTypes, 2, 5, new Random(clientId.hashCode()), today);
            counts.put("pledgeCampaigns", p[0]);
            counts.put("pledges",         p[1]);
            counts.put("pledgePayments",  p[2]);
        } else {
            counts.put("pledgeCampaigns", 0);
        }
        if (demoExtras != null) counts.putAll(demoExtras.seed(clientId, demo, today));
        log.info("TestDataService: backfilled trial demo data for {} → {}", clientId, counts);
        return counts;
    }

    /** The demo people seeded into this tenant at creation (their address is on its .test domain). */
    List<FamilyMember> demoMembers(String clientId) {
        String suffix = "@" + clientId.toLowerCase() + ".test";
        List<FamilyMember> out = new ArrayList<>();
        for (FamilyMember m : familyMemberRepo.findAllWithFamilyByAppUser(clientId)) {
            if (m.getEmail() != null && m.getEmail().toLowerCase().endsWith(suffix)
                    && !"Visitor".equalsIgnoreCase(m.getMemberType())) out.add(m);
        }
        return out;
    }

    private List<FamilyMember> demoAdults(String clientId) {
        List<FamilyMember> out = new ArrayList<>();
        for (FamilyMember m : demoMembers(clientId)) if (m.isIncludeContributions()) out.add(m);
        return out;
    }

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
        return clearManagedTenant(clientId, performedBy);
    }

    /**
     * The same cascade for a tenant this deployment provisioned — demo OR trial.
     *
     * <p>{@link #clearDemoClient} keeps its {@code DEMO-} guard, because demo
     * housekeeping must never be able to wipe a real prospect by accident. Trial
     * deletion is a separate, deliberate act with its own confirmation, and it
     * calls this directly.
     *
     * <p>Every statement is scoped by this one {@code clientId} — either directly,
     * or through a sub-select that is itself scoped by it. There is no unqualified
     * DELETE anywhere in the sequence, which is what keeps one tenant's deletion
     * from touching another's rows.
     */
    @Transactional
    public Map<String, Object> clearManagedTenant(String clientId, String performedBy) {
        if (!isManagedTenant(clientId)) {
            throw new IllegalArgumentException(
                "Refusing to clear a tenant that is neither demo nor trial: " + clientId);
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
        // M7 — delete every tenant-owned table for this client, children before
        // parents, each statement strictly scoped to this clientId. The ordered,
        // fully-scoped plan is derived from the live catalog (see TenantPurgePlanner)
        // rather than a hand-maintained list, so it covers all tenant tables (164 at
        // the September audit) and stays correct as the schema evolves. The old
        // 31-table sequence lived here; every table it deleted is still deleted, in
        // an order that also satisfies the W3 RESTRICT foreign keys.
        Map<String, Integer> deleted = tenantPurgePlanner.purge(jdbc, clientId);

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

        // Each demo tenant's own plan and expiry, read from its own service_client row.
        // Done as one lookup per tenant after the credential query rather than joined into
        // it, because that query is a three-way UNION and adding the join to each branch
        // would obscure what it is actually for.
        for (Map.Entry<String, Map<String, Object>> entry : byTenant.entrySet()) {
            ServiceClient sc = serviceClientRepo.findByClientId(entry.getKey()).orElse(null);
            entry.getValue().put("subscription",
                    sc != null ? describeSubscription(sc) : Map.of("status", "Unknown"));
        }
        return new ArrayList<>(byTenant.values());
    }

    /* ── Adding a role to an existing demo tenant ────────────────────────── */

    private static final String[][] EXTRA_NAMES = {
        { "Grace",  "Bennett" }, { "Paul",   "Rivera"  }, { "Hannah", "Osei"    },
        { "Daniel", "Kim"     }, { "Miriam", "Lopez"   }, { "Caleb",  "Owens"   },
        { "Naomi",  "Fischer" }, { "Isaac",  "Mensah"  }, { "Rachel", "Dunn"    },
    };

    /* ── Demo role names ────────────────────────────────────────────────────
       The roles a Service Admin can add to a demo tenant. Church and the two
       portal roles are NOT app_user roles — they are separate login shapes — so
       they are named as constants and dispatched on, rather than being written
       into app_user.role where they would be meaningless. */
    public static final String ROLE_CHURCH         = "Church";
    public static final String ROLE_MEMBER_PORTAL  = "Member Portal";
    public static final String ROLE_CHILD_PORTAL   = "Child Portal";

    /** Roles offered on the Service Admin screen, in display order. */
    public static final List<String> DEMO_ROLES = List.of(
            ROLE_CHURCH, "SuperAdmin", "Admin", "Accountant", "User",
            ROLE_MEMBER_PORTAL, ROLE_CHILD_PORTAL);

    /**
     * Maps a caller-supplied role to its canonical spelling.
     *
     * <p>Case- and spacing-insensitive so a direct API call with {@code superadmin}
     * or {@code member portal} lands on the same branch as the dropdown does.
     * Anything unrecognised is passed through unchanged, which keeps older
     * free-text roles such as {@code Staff} working exactly as before.
     */
    public static String canonicalDemoRole(String roleName) {
        if (roleName == null || roleName.isBlank()) return "User";
        String want = roleName.trim().replaceAll("\\s+", " ");
        for (String known : DEMO_ROLES) {
            if (known.equalsIgnoreCase(want)) return known;
            // tolerate "superadmin"/"memberportal" written without the separator
            if (known.replace(" ", "").equalsIgnoreCase(want.replace(" ", ""))) return known;
        }
        return want;
    }

    /**
     * Adds a second Church-level login to a demo tenant.
     *
     * <p>A Church login has no {@code app_user} and no member profile: it is keyed
     * directly by the tenant's Client ID with {@code church = true}, exactly as
     * {@code seedAllStaffLinked} creates it at load time.
     *
     * <p>Only one is allowed per tenant. {@code LoginRepository.findByClientId}
     * returns an {@code Optional}, so a second row sharing the tenant's Client ID
     * would make the church-registration endpoints throw instead of answering —
     * refusing here is what keeps that contract true.
     */
    private Map<String, Object> addChurchLogin(ChurchRegistration reg, String clientId, String pretty) {
        for (SignUp existing : loginRepo.findAllByClientId(clientId)) {
            if (Boolean.TRUE.equals(existing.getChurch())
                    && !Boolean.TRUE.equals(existing.getDeleted())) {
                throw new IllegalArgumentException(
                        "This demo tenant already has a Church login (" + existing.getUsername()
                      + "). A tenant can only have one. Use Reset on its row to reissue the "
                      + "username and password.");
            }
        }

        String username = null;
        for (int attempt = 0; attempt < 40 && username == null; attempt++) {
            String candidate = attempt == 0 ? "church_" + pretty
                                            : "church" + (attempt + 1) + "_" + pretty;
            if (loginRepo.findActiveByUsername(candidate).isEmpty()) username = candidate;
        }
        if (username == null) throw new IllegalStateException("Could not generate a free username");

        String password = randomPassword();
        SignUp su = new SignUp();
        su.setClientId(clientId);            // church logins key on the tenant itself
        su.setUsername(username);
        su.setPassword(PasswordUtil.encode(password));
        su.setDemoPassword(password);
        su.setActive(true);
        su.setDeleted(false);
        su.setLocked(false);
        su.setChurch(true);
        su.setChurchId(reg.getId());
        su.setCreated(new Date());
        SignUp saved = loginRepo.save(su);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username",   username);
        out.put("password",   password);
        out.put("role",       ROLE_CHURCH);
        out.put("memberName", "Church Administrator");
        out.put("clientId",   clientId);
        out.put("churchName", reg.getChurchName());
        out.put("signupId",   saved.getId());
        return out;
    }

    /**
     * Adds a Member-portal or Child-portal login to a demo tenant.
     *
     * <p>Portal logins have no {@code app_user} either: the signup is keyed by
     * {@code family_member.member_ref}, so the member profile must exist first and
     * be saved before the ref is generated. The Demo Role Access list derives the
     * label from {@code family_member.role}, which is why a child gets role
     * {@code "Child"} and an adult {@code "Head"} — set them wrong and the row
     * shows up under the other portal type.
     *
     * <p>A child is given a parent in the same family so the household is not a
     * lone minor, matching what the loader produces.
     */
    private Map<String, Object> addPortalLogin(ChurchRegistration reg, String clientId, boolean child) {
        Random rng = new Random();
        String[] n = EXTRA_NAMES[rng.nextInt(EXTRA_NAMES.length)];

        Family fam = new Family();
        fam.setAppClientId(clientId);
        fam.setInactive(false);
        fam.setDeleteFlag(false);
        familyRepo.save(fam);

        FamilyMember draft = newMember(clientId, fam, "Head", n[0], n[1], "Member",
                rng.nextBoolean() ? "Male" : "Female", 32 + rng.nextInt(20));
        draft.setIncludeContributions(true);
        // The member_ref this login is keyed by is assigned during persist, so read
        // it off what save() returns rather than off the instance handed to it.
        FamilyMember login = familyMemberRepo.save(draft);

        if (child) {
            String[] kids = { "Lily", "Owen", "Ava", "Ethan", "Mia", "Liam" };
            FamilyMember kidDraft = newMember(clientId, fam, "Child",
                    kids[rng.nextInt(kids.length)], n[1], "Member",
                    rng.nextBoolean() ? "Male" : "Female", 6 + rng.nextInt(8));
            login = familyMemberRepo.save(kidDraft);   // the child holds the login
        }
        if (login.getMemberRef() == null) {
            throw new IllegalStateException("Member profile has no member_ref — cannot key a portal login");
        }

        String base = (child ? "child_" : "member_") + safeLower(login.getFirstName());
        String username = null;
        for (int attempt = 0; attempt < 40 && username == null; attempt++) {
            String candidate = (base + "_" + Math.abs(login.getId())
                             + (attempt == 0 ? "" : String.valueOf(attempt)))
                             .replaceAll("[^a-z0-9_]", "");
            if (loginRepo.findActiveByUsername(candidate).isEmpty()) username = candidate;
        }
        if (username == null) throw new IllegalStateException("Could not generate a free username");

        String password = randomPassword();
        SignUp su = new SignUp();
        su.setClientId(login.getMemberRef());   // MBR<token> = the portal login key
        su.setUsername(username);
        su.setPassword(PasswordUtil.encode(password));
        su.setDemoPassword(password);
        su.setActive(true);
        su.setDeleted(false);
        su.setLocked(false);
        su.setChurch(false);
        su.setCreated(new Date());
        SignUp saved = loginRepo.save(su);

        String memberName = ((login.getFirstName() == null ? "" : login.getFirstName()) + " "
                          +  (login.getLastName()  == null ? "" : login.getLastName())).trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username",   username);
        out.put("password",   password);
        out.put("role",       child ? ROLE_CHILD_PORTAL : ROLE_MEMBER_PORTAL);
        out.put("memberName", memberName);
        out.put("memberId",   login.getId());
        out.put("clientId",   clientId);
        out.put("churchName", reg.getChurchName());
        out.put("signupId",   saved.getId());
        return out;
    }

    /**
     * Adds ONE staff-style login to an existing demo tenant, without touching any
     * of that tenant's data.
     *
     * <p>Mirrors the creation path {@code seedAllStaffLinked} uses for its staff
     * accounts — family, member profile, {@code app_user}, then a {@code signup}
     * keyed by {@code app_user.user_id} — so an added role is indistinguishable
     * from one created at load time. The tenant's own Client ID is reused: the
     * new role joins the existing tenant and shares its demo data.
     *
     * @param clientId the demo tenant to add to
     * @param roleName app_user role, e.g. Admin / Accountant / User
     * @return username, password, role, memberName, clientId, signupId
     */
    @Transactional
    public Map<String, Object> addDemoRole(String clientId, String roleName) {
        // Trial tenants are managed on the same screen, so they accept roles too.
        if (!isManagedTenant(clientId)) {
            throw new IllegalArgumentException("Not a demo or trial tenant: " + clientId);
        }
        ChurchRegistration reg = churchRegRepo.findByClientIdAndDeleteFlagFalse(clientId).orElseThrow(
                () -> new IllegalArgumentException("No such tenant: " + clientId));

        String pretty = prettySuffix(clientId);
        String role   = canonicalDemoRole(roleName);

        // Three login shapes exist per tenant and they are not interchangeable —
        // signup.client_id means something different in each. Dispatch first so the
        // staff path below stays exactly as it was.
        if (ROLE_CHURCH.equals(role))        return addChurchLogin(reg, clientId, pretty);
        if (ROLE_MEMBER_PORTAL.equals(role)) return addPortalLogin(reg, clientId, false);
        if (ROLE_CHILD_PORTAL.equals(role))  return addPortalLogin(reg, clientId, true);

        // A name and username that do not collide with what the tenant already has
        Random rng = new Random();
        String first = null, last = null, username = null;
        for (int attempt = 0; attempt < 40 && username == null; attempt++) {
            String[] n = EXTRA_NAMES[rng.nextInt(EXTRA_NAMES.length)];
            String candidate = (n[0] + "." + n[1]).toLowerCase() + "_" + pretty;
            if (attempt > 8) candidate = candidate + (1 + rng.nextInt(99));   // widen the net
            if (loginRepo.findActiveByUsername(candidate).isEmpty()) {
                first = n[0]; last = n[1]; username = candidate;
            }
        }
        if (username == null) throw new IllegalStateException("Could not generate a free username");

        String password  = randomPassword();
        String linkGroup = java.util.UUID.randomUUID().toString();

        Family fam = new Family();
        fam.setAppClientId(clientId);
        fam.setInactive(false);
        fam.setDeleteFlag(false);
        familyRepo.save(fam);

        FamilyMember fm = newMember(clientId, fam, "Head", first, last, "Member",
                rng.nextBoolean() ? "Male" : "Female", 30 + rng.nextInt(25));
        fm.setIncludeContributions(true);
        familyMemberRepo.save(fm);

        // Email must match the member's, or the staff→member lookup cannot find the household
        String email = (first + "." + last).toLowerCase() + "@" + clientId.toLowerCase() + ".test";
        AppUser u = new AppUser();
        u.setClientId(clientId);
        u.setFirstName(first);
        u.setLastName(last);
        u.setEmail(email);
        u.setRole(role);
        u.setEnabled(true);
        u.setDeleteFlag(false);
        u.setLinkGroup(linkGroup);
        AppUser savedUser = appUserRepo.save(u);

        SignUp su = new SignUp();
        su.setClientId(savedUser.getUserId());      // non-church logins key on app_user.user_id
        su.setUsername(username);
        su.setPassword(PasswordUtil.encode(password));
        su.setDemoPassword(password);
        su.setActive(true);
        su.setDeleted(false);
        su.setLocked(false);
        su.setChurch(false);
        su.setLinkGroup(linkGroup);
        su.setCreated(new Date());
        SignUp savedSignup = loginRepo.save(su);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username",   username);
        out.put("password",   password);
        out.put("role",       role);
        out.put("memberName", first + " " + last);
        out.put("clientId",   clientId);
        out.put("churchName", reg.getChurchName());
        out.put("signupId",   savedSignup.getId());
        return out;
    }

    /**
     * Reissues one demo login: new username, new password, everything else kept.
     *
     * <p>Role, member name and Client ID are deliberately untouched, and no demo
     * DATA is altered — demo data is tenant-scoped and shared between roles, so
     * wiping it here would silently take the tenant's other roles with it.
     *
     * @return the new username and password, plus the signup id
     */
    @Transactional
    public Map<String, Object> reissueDemoLogin(String username) {
        SignUp su = loginRepo.findActiveByUsername(username).orElseThrow(
                () -> new IllegalArgumentException("No active demo login named " + username));

        String base = username.contains("_")
                ? username.substring(0, username.lastIndexOf('_'))
                : username;
        String pretty = username.contains("_")
                ? username.substring(username.lastIndexOf('_') + 1)
                : "";
        Random rng = new Random();
        String newUsername = null;
        for (int i = 0; i < 40 && newUsername == null; i++) {
            String candidate = base + (pretty.isEmpty() ? "" : "_" + pretty) + (1 + rng.nextInt(999));
            if (loginRepo.findActiveByUsername(candidate).isEmpty()) newUsername = candidate;
        }
        if (newUsername == null) throw new IllegalStateException("Could not generate a free username");

        String newPwd = randomPassword();
        su.setUsername(newUsername);
        su.setPassword(PasswordUtil.encode(newPwd));
        su.setDemoPassword(newPwd);
        su.setLocked(false);
        su.setActive(true);
        loginRepo.save(su);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", newUsername);
        out.put("password", newPwd);
        out.put("signupId", su.getId());
        return out;
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
    @Transactional
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
            // Never return the credential itself — only whether it is a BCrypt hash or a legacy plaintext row.
            String pw = String.valueOf(row.get("password"));
            out.put("passwordStorage", pw.startsWith("$2") ? "bcrypt" : "legacy-plaintext");
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

    private ChurchRegistration seedChurchRegistration(String clientId, TenantSpec spec) {
        ChurchRegistration cr = new ChurchRegistration();
        cr.setClientId(clientId);
        cr.setFirstName(spec.contact().firstName());
        cr.setLastName(spec.contact().lastName());
        cr.setChurchName(spec.churchName());
        cr.setEmail(spec.contact().email());
        cr.setPhone(spec.contact().phone());
        cr.setNote(spec.contact().note());
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
    /**
     * Creates the {@code service_client} row for a demo tenant.
     *
     * <p>This row <em>is</em> the demo tenant's subscription — there is deliberately no
     * separate mechanism for demo data. It carries the plan
     * ({@code subscription_type} → {@code subscription_plan.plan_code}) and the expiry
     * ({@code end_date}), which is exactly what {@code SubscriptionService} reads for
     * feature limits and what {@code countValidChurchLogin} / {@code countValidNonChurchLogin}
     * read to allow or refuse a login. A demo tenant therefore behaves under the same plan
     * limits and the same expiry rules as a paying customer, because it is the same code path.
     *
     * <p>Plan and expiry were previously hardcoded to {@code FULL} and one year.
     *
     * @param planCode  a {@code subscription_plan.plan_code}, already validated
     * @param expiresOn last day the tenant may sign in
     */
    private ServiceClient seedServiceClient(String clientId, TenantSpec spec,
                                            String planCode, LocalDate expiresOn) {
        LocalDate startDate = com.churchgeniuspro.util.AppClock.today();   // trial/demo dates are Chicago dates
        ServiceClient sc = new ServiceClient();
        sc.setClientId(clientId);
        sc.setChurchName(spec.churchName());
        sc.setName(spec.contact().fullName());
        sc.setEmail(spec.contact().email());
        sc.setPhone(spec.contact().phone());
        sc.setAddressLine1(spec.contact().addressLine1());
        sc.setAddressLine2(spec.contact().addressLine2());
        sc.setCity(spec.contact().city());
        sc.setState(spec.contact().state());
        if (spec.contact().country() != null && !spec.contact().country().isBlank()) {
            sc.setCountry(spec.contact().country());
        }
        sc.setPinCode(spec.contact().pinCode());
        sc.setNote(spec.contact().note());
        sc.setStartDate(startDate);
        sc.setEndDate(expiresOn);
        // Kept consistent with the dates so the record reads correctly in the admin UI.
        sc.setActivePeriod((int) Math.max(1, ChronoUnit.DAYS.between(startDate, expiresOn)));
        sc.setActivePeriodUnit("DAYS");
        sc.setStatus("Active");        // login validator checks for this exact value
        sc.setPaymentStatus("PAID");
        sc.setSubscriptionType(planCode);
        sc.setApproved(true);
        sc.setDeleteFlag(false);
        if (lifecycle != null) lifecycle.applyPricing(sc, null, SubscriptionLifecycleService.MONTHLY, null, false, false);
        ServiceClient savedSc = serviceClientRepo.save(sc);
        if (lifecycle != null) lifecycle.recordAndRefresh(null, savedSc, null, "CREATED");
        if (messagingPolicy != null) messagingPolicy.invalidate(savedSc.getClientId());
        return savedSc;
    }

    // ── Demo subscription: plan + expiry ─────────────────────────────────────

    /**
     * Validates a requested plan code against the plans actually configured under
     * Subscription Plans.
     *
     * <p>Nothing here knows the name of a single plan. The list comes from
     * {@code subscription_plan}, so adding, renaming or deactivating a plan in the admin UI
     * changes what demo data can be created with, immediately and without a code change.
     *
     * @param requested a plan code; blank selects the first active plan by sort order
     * @return the canonical {@code plan_code} as stored in the database
     * @throws IllegalArgumentException if the code matches no active plan — the message
     *         lists what is available, so a bad request is self-diagnosing
     */
    public String resolvePlanCode(String requested) {
        List<SubscriptionPlan> active = planRepo.findAllByOrderBySortOrderAscIdAsc()
                .stream().filter(SubscriptionPlan::isActive).toList();

        if (active.isEmpty()) {
            throw new IllegalArgumentException(
                    "No active subscription plans are configured. Add one under Subscription Plans first.");
        }
        if (requested == null || requested.isBlank()) {
            String fallback = active.get(0).getPlanCode();
            log.info("TestDataService: no plan requested, defaulting to first active plan '{}'", fallback);
            return fallback;
        }
        String wanted = requested.trim();
        for (SubscriptionPlan plan : active) {
            if (plan.getPlanCode().equalsIgnoreCase(wanted)) {
                return plan.getPlanCode();   // canonical casing from the database
            }
        }
        throw new IllegalArgumentException("Unknown subscription plan '" + requested + "'. Available: "
                + active.stream().map(SubscriptionPlan::getPlanCode).collect(Collectors.joining(", ")));
    }

    /**
     * Views, edits or extends one demo tenant's subscription.
     *
     * <p>Updates the existing {@code service_client} row in place. There is one such row per
     * demo tenant, keyed by {@code client_id}, so extending an expiry cannot create a
     * duplicate subscription record — there is nowhere for a duplicate to go.
     *
     * <p>Extending past today also flips {@code status} back to {@code Active}, so a tenant
     * that had expired can sign in again straight away rather than needing a second edit.
     *
     * <p>Guarded to demo tenants only: the {@code DEMO-} prefix check means this can never
     * alter a paying customer's subscription, which matters because it is reachable from an
     * admin screen whose other buttons are all demo-scoped.
     *
     * @param planCode  new plan, or {@code null}/blank to leave the plan unchanged
     * @param expiresOn new expiry, or {@code null} to leave the expiry unchanged
     */
    @Transactional
    public Map<String, Object> updateDemoSubscription(String clientId, String planCode, LocalDate expiresOn) {
        // Widened to trial tenants: upgrading a trial off the Trial plan is the
        // point of the trial. clearDemoClient is deliberately NOT widened — it
        // deletes a tenant, and a trial tenant is a real prospect.
        if (!isManagedTenant(clientId)) {
            throw new IllegalArgumentException(
                    "Refusing to change the subscription of '" + clientId
                  + "' — not a demo or trial tenant.");
        }
        ServiceClient sc = serviceClientRepo.findByClientId(clientId)
                .orElseThrow(() -> new IllegalArgumentException("No subscription found for " + clientId));

        String previousPlan   = sc.getSubscriptionType();
        LocalDate previousEnd = sc.getEndDate();
        SubscriptionLifecycleService.Snapshot before = SubscriptionLifecycleService.snapshot(sc);

        if (planCode != null && !planCode.isBlank()) {
            String resolved = resolvePlanCode(planCode);
            // Sample-data trials stay on TRIAL (extensions only); DEMO- tenants are unaffected.
            SubscriptionLifecycleService.checkConversionAllowed(clientId, resolved);
            sc.setSubscriptionType(resolved);
            // The send-permission answer is cached per client; a plan change must drop it.
            if (messagingPolicy != null) messagingPolicy.invalidate(clientId);
        }
        if (expiresOn != null) {
            sc.setEndDate(expiresOn);
            if (expiresOn.isAfter(com.churchgeniuspro.util.AppClock.today())) {
                // Reactivate: an expired tenant should be usable again immediately.
                sc.setStatus("Active");
            }
            // ...and carry the new date to the per-login windows, which are checked
            // at sign-in independently of the subscription. Without this the tenant
            // was extended and every one of its logins still refused.
            if (demoAccess != null) {
                try { demoAccess.extendWindows(clientId, expiresOn); }
                catch (Exception e) {
                    log.warn("Could not extend demo access windows for {} — {}", clientId, e.getMessage());
                }
            }
            if (sc.getStartDate() != null) {
                sc.setActivePeriod((int) Math.max(1, ChronoUnit.DAYS.between(sc.getStartDate(), expiresOn)));
                sc.setActivePeriodUnit("DAYS");
            }
        }
        // A new plan re-copies its list price unless this client has a negotiated price.
        if (lifecycle != null) lifecycle.applyPricing(sc, before, null, null, null, false);
        serviceClientRepo.save(sc);
        if (lifecycle != null) lifecycle.recordAndRefresh(before, sc, null, "DEMO_SUBSCRIPTION");

        // SubscriptionService caches the resolved plan for five minutes. Without this the
        // admin would change the plan and see the old limits still applied.
        subscriptionService.clearCache();

        log.info("TestDataService: demo subscription updated for {} — plan {} -> {}, expiry {} -> {}",
                clientId, previousPlan, sc.getSubscriptionType(), previousEnd, sc.getEndDate());

        return describeSubscription(sc);
    }

    /**
     * The subscription facts the admin screen shows for a demo tenant.
     *
     * <p>{@code status} is derived from the date rather than read from
     * {@code service_client.status}, and matches the login queries exactly: those use
     * {@code end_date > current_date}, so a tenant whose expiry is today can no longer sign
     * in and is reported as Expired. Deriving it keeps the badge and the actual login
     * behaviour from disagreeing.
     */
    Map<String, Object> describeSubscription(ServiceClient sc) {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate today = com.churchgeniuspro.util.AppClock.today();
        LocalDate end   = sc.getEndDate();

        boolean statusActive = "Active".equalsIgnoreCase(sc.getStatus());
        boolean active       = statusActive && !SubscriptionService.isExpired(end, today);

        String planCode = SubscriptionService.toPlanCode(sc.getSubscriptionType());
        String planName = planCode == null ? null : planRepo.findByPlanCodeIgnoreCase(planCode)
                .map(SubscriptionPlan::getPlanName).orElse(planCode);

        m.put("planCode",      planCode);
        m.put("planName",      planName);
        m.put("startDate",     sc.getStartDate() != null ? sc.getStartDate().toString() : null);
        m.put("expiresOn",     end != null ? end.toString() : null);
        m.put("status",        active ? "Active" : "Expired");
        m.put("daysRemaining", end != null ? ChronoUnit.DAYS.between(today, end) : null);
        return m;
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
    /**
     * @param staffRoles which of the four staff logins to create. The Church login
     *        is always created — a tenant without one has no org account — but the
     *        staff roles are selectable, so a trial tenant can be provisioned with
     *        SuperAdmin alone.
     */
    private void seedAllStaffLinked(String clientId, Integer churchRegId,
                                    List<Map<String, Object>> creds, Random rng,
                                    List<String> staffRoles) {
        // Prefix-agnostic: DEMO- and TRIAL- tenants produce the same username shape.
        String pretty = prettySuffix(clientId);

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
            if (staffRoles != null && !staffRoles.contains(s[2])) continue;   // not wanted for this tenant
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

    /**
     * Future meetings counted from the creation date {@code today}: two weekly
     * series (Sunday Service, Wednesday Bible Study) and a monthly Prayer Meeting
     * that start after today, plus two one-time meetings in the coming weeks.
     * Skipped when the tenant already has any upcoming meeting, so it never
     * duplicates or crowds a schedule someone has set up. Categories that already
     * exist (same name, any case) are reused rather than created a second time.
     */
    private int seedMeetings(String clientId, LocalDate today) {
        if (hasUpcomingMeeting(clientId, today)) return 0;
        java.util.Map<String, MeetingType> existing = new java.util.HashMap<>();
        for (MeetingType t : meetingTypeRepo.findActiveByAppUser(clientId)) {
            if (t.getTypeName() != null) existing.putIfAbsent(t.getTypeName().trim().toLowerCase(), t);
        }
        java.util.Map<String, MeetingType> types = new java.util.LinkedHashMap<>();
        for (String name : List.of("Sunday Service", "Bible Study", "Prayer Meeting", "Leadership Team", "Youth Night")) {
            MeetingType mt = existing.get(name.toLowerCase());
            if (mt == null) {
                mt = new MeetingType();
                mt.setTypeName(name);
                mt.setAppClientId(clientId);
                mt.setDeleteFlag(false);
                mt = meetingTypeRepo.save(mt);
            }
            types.put(name, mt);
        }
        LocalDate nextSunday = today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SUNDAY));
        LocalDate nextWed    = today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.WEDNESDAY));
        LocalDate youthFri   = today.plusDays(3).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.FRIDAY));

        List<Meeting> list = new ArrayList<>();
        list.add(meeting(clientId, types.get("Sunday Service"), nextSunday, "10:00", "11:30", "Weekly", "0",
                "Weekly worship service with communion on the first Sunday of the month."));
        list.add(meeting(clientId, types.get("Bible Study"), nextWed, "19:00", "20:30", "Weekly", "3",
                "Midweek Bible study in the fellowship hall — currently reading the Gospel of John."));
        Meeting prayer = meeting(clientId, types.get("Prayer Meeting"), today.plusDays(1), "08:00", "09:00", "Monthly", null,
                "Monthly prayer breakfast on the first Saturday.");
        prayer.setMonthWeekOrdinal(1);   // first …
        prayer.setMonthWeekDay(6);       // … Saturday (0=Sun…6=Sat)
        list.add(prayer);
        list.add(meeting(clientId, types.get("Leadership Team"), today.plusDays(10), "18:30", "20:00", "One-time", null,
                "Quarterly planning: budget review and fall outreach calendar."));
        list.add(meeting(clientId, types.get("Youth Night"), youthFri, "18:00", "20:00", "One-time", null,
                "Games, worship and pizza for grades 6–12."));
        list.forEach(meetingRepo::save);
        return list.size();
    }

    /**
     * True when the tenant has a meeting still to come: a one-time meeting dated today
     * or later, or a recurring series with no end date or one not yet passed.
     */
    boolean hasUpcomingMeeting(String clientId, LocalDate today) {
        for (Meeting m : meetingRepo.findAllActiveByAppUserOrderByDateAsc(clientId)) {
            String occ = m.getOccurrence() == null ? "" : m.getOccurrence().trim();
            boolean recurring = occ.equalsIgnoreCase("Daily") || occ.equalsIgnoreCase("Weekly")
                             || occ.equalsIgnoreCase("Monthly");
            if (recurring ? (m.getEndDate() == null || !m.getEndDate().isBefore(today))
                          : (m.getMeetingDate() != null && !m.getMeetingDate().isBefore(today))) {
                return true;
            }
        }
        return false;
    }

    private static Meeting meeting(String clientId, MeetingType type, LocalDate date, String start, String end,
                                   String occurrence, String weekDays, String note) {
        Meeting m = new Meeting();
        m.setMeetingType(type);
        m.setMeetingDate(date);          // the date for a one-time meeting; the series start otherwise
        m.setStartTime(start);
        m.setEndTime(end);
        m.setOccurrence(occurrence);
        m.setWeekDays(weekDays);
        m.setCountry("USA");
        m.setNote(note);
        m.setAppClientId(clientId);
        m.setDeleteFlag(false);
        return m;
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

    /**
     * Pledge campaigns with member pledges and a couple of payments each.
     *
     * <p>A campaign's progress is computed from income given to its fund between the
     * campaign's creation and its end date (PledgeController.collectedForPledge), so a
     * campaign created "now" with no gifts would show $0 collected. Each demo campaign
     * is therefore dated as having started a few weeks before {@code today}, and each
     * pledger has made one or two monthly payments inside that window.
     *
     * @return {campaigns, pledges, payments}
     */
    private int[] seedPledges(String clientId, List<FamilyMember> members,
                              List<SubSource> funds, List<TransactionType> txnTypes,
                              int campaignCount, int pledgesPerCampaign,
                              Random rng, LocalDate today) {
        if (!pledgeCampaignRepo.findByClientId(clientId).isEmpty()) {
            return new int[] { 0, 0, 0 };
        }
        String[][] campaigns = {
                { "Building Fund " + today.getYear(), "Roof replacement and sanctuary renovation.", "25000", "75", "6" },
                { "Missions Trip " + (today.getYear() + 1), "Sending a team to support a partner church's community clinic.", "8000", "40", "4" },
        };
        List<FamilyMember> pledgers = new ArrayList<>();
        for (FamilyMember m : members) if (m.isIncludeContributions()) pledgers.add(m);

        int created = 0, pledges = 0, payments = 0;
        for (int i = 0; i < Math.min(campaignCount, campaigns.length); i++) {
            String[] def = campaigns[i];
            SubSource fund = funds.get(i % funds.size());
            LocalDate started = today.minusDays(Long.parseLong(def[3]));
            PledgeCampaign c = new PledgeCampaign();
            c.setClientId(clientId);
            c.setName(def[0]);
            c.setSubSourceId(fund.getId());
            c.setDescription(def[1] + " Auto-allocates contributions made to " + fund.getSourceName() + ".");
            c.setTargetAmount(new BigDecimal(def[2]));
            c.setEndDate(today.plusMonths(Long.parseLong(def[4])));
            c.setStatus("Active");
            c.setCreatedDate(started.atTime(9, 0));       // see method note: the progress window starts here
            c.setDeleteFlag(false);
            pledgeCampaignRepo.save(c);
            created++;

            // Pledgers: a different slice of the adults for each campaign.
            for (int k = 0; k < Math.min(pledgesPerCampaign, pledgers.size()); k++) {
                FamilyMember m = pledgers.get((k + i * 2) % pledgers.size());
                PledgeMember p = new PledgeMember();
                p.setClientId(clientId);
                p.setCampaignId(c.getId());
                p.setFamilyMemberId(m.getId());
                if (m.getFamily() != null) p.setFamilyId(m.getFamily().getId());
                int base = i == 0 ? 500 + rng.nextInt(2001) : 150 + rng.nextInt(451);   // $500–$2,500 / $150–$600
                BigDecimal pledged = BigDecimal.valueOf(base);
                BigDecimal monthly = pledged.divide(BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP);
                p.setPledgeAmount(pledged);
                p.setMonthlyAmount(monthly);
                p.setAmountCollected(BigDecimal.ZERO);           // computed from income for member pledges
                p.setDeleteFlag(false);
                pledgeMemberRepo.save(p);
                pledges++;

                // One or two payments since the campaign started (never on/after today).
                int paid = k % 3 == 2 ? 0 : 1 + (k % 2);
                for (int n = 0; n < paid; n++) {
                    LocalDate when = started.plusDays(7L + n * 30L + k);
                    if (!when.isBefore(today)) break;
                    Income inc = new Income();
                    inc.setMember(m);
                    inc.setSubSource(fund);
                    inc.setTransactionType(txnTypes.get((k + n) % txnTypes.size()));
                    inc.setIncomeDate(when);
                    inc.setAmount(monthly);
                    inc.setNote("Pledge payment — " + def[0]);
                    inc.setAppClientId(clientId);
                    inc.setDeleteFlag(false);
                    inc.setQuickAdd(false);
                    inc.setCreatedBy("system");
                    incomeRepo.save(inc);
                    payments++;
                }
            }
        }
        return new int[] { created, pledges, payments };
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

            Map<String, Object> row = credRow(ROLE_MEMBER_PORTAL, username, password, false,
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

            Map<String, Object> row = credRow(ROLE_CHILD_PORTAL, username, password, false,
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
