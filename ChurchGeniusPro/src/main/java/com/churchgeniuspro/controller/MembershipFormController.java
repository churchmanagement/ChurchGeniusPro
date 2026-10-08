package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.hibernate.GroupMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MemberMessage;
import com.churchgeniuspro.hibernate.MemberPreference;
import com.churchgeniuspro.hibernate.MembershipFamily;
import com.churchgeniuspro.hibernate.MembershipFamilyMember;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.hibernate.PledgeMember;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.WorshipAssignment;
import com.churchgeniuspro.hibernate.WorshipAssignmentMember;
import com.churchgeniuspro.hibernate.WorshipGroup;
import com.churchgeniuspro.hibernate.WorshipGroupMember;
import com.churchgeniuspro.hibernate.WorshipInstrument;
import com.churchgeniuspro.hibernate.WorshipSong;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchEventDayRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.GroupMemberRepository;
import com.churchgeniuspro.repository.GroupRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MemberMessageRepository;
import com.churchgeniuspro.repository.MemberPreferenceRepository;
import com.churchgeniuspro.repository.MembershipFamilyMemberRepository;
import com.churchgeniuspro.repository.MembershipFamilyRepository;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.repository.PledgeMemberRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.WorshipAssignmentMemberRepository;
import com.churchgeniuspro.repository.WorshipAssignmentRepository;
import com.churchgeniuspro.repository.WorshipGroupMemberRepository;
import com.churchgeniuspro.repository.WorshipGroupRepository;
import com.churchgeniuspro.repository.WorshipInstrumentRepository;
import com.churchgeniuspro.repository.WorshipSongRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.service.WhatsAppSenderService;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Handles the public membership application form and the admin Membership Requests page.
 *
 * <h3>Public routes (no login required)</h3>
 * <ul>
 *   <li>{@code GET  /membershipForm}                      → serves membershipForm.html</li>
 *   <li>{@code GET  /api/membership-form/config}           → declaration config for a CID token</li>
 *   <li>{@code POST /api/membership-form/submit}           → saves family + members from public form</li>
 *   <li>{@code GET  /api/membership-form/lookup}           → looks up existing member by phone/email</li>
 *   <li>{@code POST /api/membership-form/verify-otp}       → checks the emailed code, returns the family
 *                                                            and a one-time {@code updateToken}</li>
 * </ul>
 *
 * <p><strong>Updating an existing family</strong> is honoured only when {@code submit} carries the
 * {@code updateToken} that {@code verify-otp} issued for that family under the same link. The
 * family id alone is a small integer that anyone holding the public link could send; the token is
 * the proof that the code we emailed to the family was actually entered. The form never creates
 * logins: member portal accounts come from the Member Signup flow, whose code goes to the member's
 * own address.
 *
 * <h3>Admin routes (Admin / SuperAdmin only)</h3>
 * <ul>
 *   <li>{@code GET  /membershipRequests}                   → serves membershipRequests.html</li>
 *   <li>{@code GET  /api/membership-requests}              → list pending requests for org</li>
 *   <li>{@code GET  /api/membership-requests/{id}}         → single request with members</li>
 *   <li>{@code POST /api/membership-requests/{id}/approve} → transfer to family tables, soft-delete</li>
 * </ul>
 *
 * <p><strong>Schema note:</strong> {@link MembershipFamily} mirrors {@link Family} — it no longer
 * stores a family name or address.  Those details live on the primary ({@code Head})
 * {@link MembershipFamilyMember} row, exactly as they do in {@link FamilyMember}.
 */
@Controller
public class MembershipFormController {

    private static final Logger log = LoggerFactory.getLogger(MembershipFormController.class);

    /** In-app notification for staff with the right permissions. Optional: absent in unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.PublicSubmissionNotificationService submissionNotifications;

    /** Test seam. */
    public void setSubmissionNotifications(com.churchgeniuspro.service.PublicSubmissionNotificationService s) {
        this.submissionNotifications = s;
    }

    @Value("${app.base-url}")
    private String baseUrl;

    // ── Verified-family proof (verify-otp → submit) ────────────────────────
    //
    // verify-otp used to just return the family; submit then trusted whatever
    // existingFamilyId the anonymous body carried, so an "update" of any family in the
    // church could be queued for approval by anyone with the public link. The proof
    // issued here is what ties the two steps together: opaque, random, bound to one
    // family of one tenant, time-boxed, and spent by the submission that uses it.
    // In-memory on the same terms as MemberSignupController's lookup handles (single
    // instance); a restart just sends the applicant back to the code step.

    /** How long a proof stays valid — long enough to fill in a large family. */
    public static final long UPDATE_PROOF_TTL_MS = 60 * 60_000L;

    public static final String UPDATE_PROOF_REQUIRED_MSG =
            "Please look up your family and enter the code we emailed before updating an existing record.";

    private record UpdateProof(Integer familyId, String appClientId, long issuedAt) {}

    private final java.util.concurrent.ConcurrentHashMap<String, UpdateProof> updateProofs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.security.SecureRandom proofRandom = new java.security.SecureRandom();

    private java.util.function.LongSupplier proofClock = System::currentTimeMillis;

    /** Test seam — the clock proofs are aged against, without a Spring context. */
    public void setProofClock(java.util.function.LongSupplier clock) {
        this.proofClock = clock != null ? clock : System::currentTimeMillis;
    }

    /** Issues a fresh one-time proof that {@code familyId} of {@code appClientId} was verified. */
    private String issueUpdateProof(Integer familyId, String appClientId) {
        byte[] raw = new byte[32];
        proofRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        if (updateProofs.size() > 10_000) updateProofs.clear();   // safety valve
        updateProofs.put(token, new UpdateProof(familyId, appClientId, proofClock.getAsLong()));
        return token;
    }

    /** True when {@code token} is a live proof for exactly this family and tenant. Never consumes. */
    private boolean isValidUpdateProof(String token, Integer familyId, String appClientId) {
        if (token == null || token.isBlank() || familyId == null || appClientId == null) return false;
        UpdateProof p = updateProofs.get(token.trim());
        if (p == null) return false;
        if (proofClock.getAsLong() - p.issuedAt() > UPDATE_PROOF_TTL_MS) {
            updateProofs.remove(token.trim());
            return false;
        }
        return familyId.equals(p.familyId()) && appClientId.equals(p.appClientId());
    }

    /** A proof is good for one submission. */
    private void spendUpdateProof(String token) {
        if (token != null) updateProofs.remove(token.trim());
    }

    private final PublicScreenLinkRepository        linkRepo;
    private final MembershipFamilyRepository        mfRepo;
    private final MembershipFamilyMemberRepository  mfmRepo;

    /**
     * The subscription plan, for the maximum-people limit applied when a public
     * membership request is approved. Field-injected and null-checked so this
     * controller's existing construction sites (and their tests) are unchanged.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.SubscriptionService subscriptionService;

    /** Test seam — supply the plan without a Spring context. */
    public void setSubscriptionService(com.churchgeniuspro.service.SubscriptionService s) {
        this.subscriptionService = s;
    }

    /**
     * The church's own wording for the Financial Report letter. Field-injected on
     * the same terms as the plan above; absent, the report falls back to the
     * built-in wording rather than losing the letter around the table.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.FinancialReportLetterService letterService;

    /** Test seam — supply the letter text without a Spring context. */
    public void setLetterService(com.churchgeniuspro.service.FinancialReportLetterService s) {
        this.letterService = s;
    }
    private final FamilyRepository                  familyRepo;
    private final FamilyMemberRepository            familyMemberRepo;
    private final ChurchRegistrationRepository      churchRegRepo;
    private final ChurchLogoRepository              logoRepo;
    private final VerificationStore                 verificationStore;
    private final EmailService                      emailService;
    private final LoginRepository                   loginRepository;
    private final AppUserRepository                 appUserRepository;
    private final SubSourceRepository               subSourceRepo;
    private final IncomeRepository                  incomeRepo;
    private final MeetingRepository                 meetingRepo;
    private final ChurchEventRepository             churchEventRepo;
    private final ChurchEventDayRepository          churchEventDayRepo;
    private final MemberMessageRepository           memberMessageRepo;
    private final MemberPreferenceRepository         memberPrefRepo;
    private final WhatsAppSenderService              whatsAppSenderService;
    private final GroupRepository                    groupRepo;
    private final GroupMemberRepository              groupMemberRepo;
    private final WorshipGroupRepository             worshipGroupRepo;
    private final WorshipGroupMemberRepository       worshipGroupMemberRepo;
    private final WorshipInstrumentRepository        worshipInstrumentRepo;
    private final WorshipAssignmentRepository        worshipAssignmentRepo;
    private final WorshipAssignmentMemberRepository  worshipAssignmentMemberRepo;
    private final WorshipSongRepository              worshipSongRepo;
    private final EventCalendarController            eventCalendarController;
    private final PledgeCampaignRepository           pledgeCampaignRepo;
    private final PledgeMemberRepository             pledgeMemberRepo;
    private final PledgeController                    pledgeController;
    private final PublicSendLimiter                   sendLimiter;

    public MembershipFormController(PublicScreenLinkRepository linkRepo,
                                    MembershipFamilyRepository mfRepo,
                                    MembershipFamilyMemberRepository mfmRepo,
                                    FamilyRepository familyRepo,
                                    FamilyMemberRepository familyMemberRepo,
                                    ChurchRegistrationRepository churchRegRepo,
                                    ChurchLogoRepository logoRepo,
                                    VerificationStore verificationStore,
                                    EmailService emailService,
                                    LoginRepository loginRepository,
                                    AppUserRepository appUserRepository,
                                    SubSourceRepository subSourceRepo,
                                    IncomeRepository incomeRepo,
                                    MeetingRepository meetingRepo,
                                    ChurchEventRepository churchEventRepo,
                                    ChurchEventDayRepository churchEventDayRepo,
                                    MemberMessageRepository memberMessageRepo,
                                    MemberPreferenceRepository memberPrefRepo,
                                    WhatsAppSenderService whatsAppSenderService,
                                    GroupRepository groupRepo,
                                    GroupMemberRepository groupMemberRepo,
                                    WorshipGroupRepository worshipGroupRepo,
                                    WorshipGroupMemberRepository worshipGroupMemberRepo,
                                    WorshipInstrumentRepository worshipInstrumentRepo,
                                    WorshipAssignmentRepository worshipAssignmentRepo,
                                    WorshipAssignmentMemberRepository worshipAssignmentMemberRepo,
                                    WorshipSongRepository worshipSongRepo,
                                    EventCalendarController eventCalendarController,
                                    PledgeCampaignRepository pledgeCampaignRepo,
                                    PledgeMemberRepository   pledgeMemberRepo,
                                    PledgeController         pledgeController,
                                    PublicSendLimiter        sendLimiter) {
        this.linkRepo          = linkRepo;
        this.mfRepo            = mfRepo;
        this.mfmRepo           = mfmRepo;
        this.familyRepo        = familyRepo;
        this.familyMemberRepo  = familyMemberRepo;
        this.churchRegRepo     = churchRegRepo;
        this.logoRepo          = logoRepo;
        this.verificationStore = verificationStore;
        this.emailService      = emailService;
        this.loginRepository   = loginRepository;
        this.appUserRepository = appUserRepository;
        this.subSourceRepo     = subSourceRepo;
        this.incomeRepo        = incomeRepo;
        this.meetingRepo       = meetingRepo;
        this.churchEventRepo    = churchEventRepo;
        this.churchEventDayRepo = churchEventDayRepo;
        this.memberMessageRepo     = memberMessageRepo;
        this.memberPrefRepo        = memberPrefRepo;
        this.whatsAppSenderService = whatsAppSenderService;
        this.groupRepo             = groupRepo;
        this.groupMemberRepo       = groupMemberRepo;
        this.worshipGroupRepo            = worshipGroupRepo;
        this.worshipGroupMemberRepo      = worshipGroupMemberRepo;
        this.worshipInstrumentRepo       = worshipInstrumentRepo;
        this.worshipAssignmentRepo       = worshipAssignmentRepo;
        this.worshipAssignmentMemberRepo = worshipAssignmentMemberRepo;
        this.worshipSongRepo             = worshipSongRepo;
        this.eventCalendarController     = eventCalendarController;
        this.pledgeCampaignRepo          = pledgeCampaignRepo;
        this.pledgeMemberRepo            = pledgeMemberRepo;
        this.pledgeController            = pledgeController;
        this.sendLimiter                 = sendLimiter;
    }

    // -- Public page -------------------------------------------------------

    @GetMapping("/membershipForm")
    public String membershipFormPage() {
        return "forward:/membershipForm.html";
    }

    // -- Public API: fetch declaration config ------------------------------

    @ResponseBody
    @GetMapping("/api/membership-form/config")
    public ResponseEntity<Map<String, Object>> getConfig(@RequestParam String cid) {
        PublicScreenLink link = resolveLink(cid);
        if (link == null) return bad("Invalid or expired link.");

        String appClientId = link.getAppClientId();

        // Church name from ChurchRegistration
        String churchName = churchRegRepo.findByClientIdAndDeleteFlagFalse(appClientId)
                .map(ChurchRegistration::getChurchName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);

        // Logo existence check
        boolean hasLogo = logoRepo.findByClientId(appClientId)
                .map(l -> l.getLogoData() != null && l.getLogoData().length > 0)
                .orElse(false);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("showDeclaration", Boolean.TRUE.equals(link.getShowDeclaration()));
        m.put("declarationText", link.getDeclarationText());
        if (churchName != null) m.put("churchName", churchName);
        if (hasLogo) m.put("logoUrl", "/api/membership-form/logo?cid=" + cid);
        return ResponseEntity.ok(m);
    }

    // -- Public API: serve church logo -------------------------------------

    @ResponseBody
    @GetMapping("/api/membership-form/logo")
    public ResponseEntity<byte[]> getLogo(@RequestParam String cid) {
        PublicScreenLink link = resolveLink(cid);
        if (link == null) return ResponseEntity.notFound().build();

        ChurchLogo logo = logoRepo.findByClientId(link.getAppClientId()).orElse(null);
        if (logo == null || logo.getLogoData() == null || logo.getLogoData().length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        logo.getContentType() != null ? logo.getContentType() : "image/png"))
                .body(logo.getLogoData());
    }

    // -- Public API: lookup existing member by phone or email --------------

    @ResponseBody
    @GetMapping("/api/membership-form/lookup")
    public ResponseEntity<Map<String, Object>> lookup(@RequestParam String cid,
                                                      @RequestParam String query,
                                                      HttpServletRequest request) {
        PublicScreenLink link = resolveLink(cid);
        if (link == null) return bad("Invalid or expired link.");
        if (query == null || query.isBlank()) return bad("Phone or email is required.");
        // Unauthenticated phone/email probe that also triggers an OTP email: bounded
        // per network origin here, and per family once the family is known (below), so
        // it can neither enumerate members nor keep re-issuing one family's code.
        String rateErr = sendLimiter.check(PublicSendLimiter.MEMBERSHIP_OTP, request, null, null);
        if (rateErr != null) return ResponseEntity.status(429).body(Map.of("error", rateErr));

        String appClientId = link.getAppClientId();

        List<FamilyMember> hits = familyMemberRepo.findByPhoneOrEmailAndClient(query.trim(), appClientId);
        if (hits.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("found", false);
            return ResponseEntity.ok(r);
        }

        // Find the email to send OTP to — prefer the matched member's own email, else Head
        FamilyMember matched = hits.get(0);
        Family family = matched.getFamily();

        // Get the email — use the matched member's email, or fall back to the Head
        String targetEmail = matched.getEmail();
        if (targetEmail == null || targetEmail.isBlank()) {
            // Re-assign via stream
            targetEmail = familyMemberRepo.findActiveMembersByFamilyId(family.getId()).stream()
                    .filter(m -> "Head".equalsIgnoreCase(m.getRole()))
                    .map(FamilyMember::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .findFirst()
                    .orElse(null);
        }

        if (targetEmail == null || targetEmail.isBlank()) {
            // No email on record — cannot send OTP
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("found",    true);
            r.put("noEmail",  true);
            return ResponseEntity.ok(r);
        }

        // Per-family ceiling on codes (the network dimension was applied above).
        String familyLimited = sendLimiter.check(PublicSendLimiter.MEMBERSHIP_OTP, (String) null,
                                                 family.getId().toString(), null);
        if (familyLimited != null) return ResponseEntity.status(429).body(Map.of("error", familyLimited));

        // Generate OTP and send email
        String otp = verificationStore.generateAndStore(family.getId().toString(), "member-lookup", targetEmail);
        String maskedEmail = maskEmail(targetEmail);

        String html = "<p>Hi,</p>"
                + "<p>Your verification code for the membership form is:</p>"
                + "<h2 style='letter-spacing:4px;color:#673147;'>" + otp + "</h2>"
                + "<p>This code expires in <strong>10 minutes</strong>.</p>"
                + "<p>If you did not request this, please ignore this email.</p>";
        emailService.sendGenericEmail(targetEmail, "Your Membership Verification Code", html);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("found",       true);
        r.put("otpSent",     true);
        r.put("familyId",    family.getId());
        r.put("maskedEmail", maskedEmail);
        return ResponseEntity.ok(r);
    }

    // -- Public API: resend OTP --------------------------------------------

    @ResponseBody
    @PostMapping("/api/membership-form/resend-otp")
    public ResponseEntity<Map<String, Object>> resendOtp(@RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        String cid      = (String) body.get("cid");
        Object fidObj   = body.get("familyId");
        if (cid == null || fidObj == null) return bad("Missing parameters.");

        PublicScreenLink link = resolveLink(cid);
        if (link == null) return bad("Invalid or expired link.");

        Integer familyId = fidObj instanceof Number n ? n.intValue() : null;
        if (familyId == null) return bad("Invalid family ID.");

        // Re-check family still belongs to this org
        String appClientId = link.getAppClientId();

        Family family = familyRepo.findById(familyId).orElse(null);
        if (family == null || !appClientId.equals(family.getAppClientId()))
            return bad("Family not found.");

        String storedEmail = verificationStore.getEmail(familyId.toString(), "member-lookup");
        if (storedEmail == null) return bad("Session expired. Please search again.");

        // Each resend replaces the live code and e-mails the family again: bounded per
        // network origin and per family, or a caller could both flood the inbox and keep
        // the real applicant's code from ever staying valid (security audit P2).
        String limited = sendLimiter.check(PublicSendLimiter.MEMBERSHIP_OTP, request, familyId.toString(), null);
        if (limited != null) return ResponseEntity.status(429).body(Map.of("error", limited));

        String otp = verificationStore.generateAndStore(familyId.toString(), "member-lookup", storedEmail);
        String html = "<p>Hi,</p>"
                + "<p>Your new verification code for the membership form is:</p>"
                + "<h2 style='letter-spacing:4px;color:#673147;'>" + otp + "</h2>"
                + "<p>This code expires in <strong>10 minutes</strong>.</p>";
        emailService.sendGenericEmail(storedEmail, "Your Membership Verification Code (Resent)", html);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("maskedEmail", maskEmail(storedEmail));
        return ResponseEntity.ok(r);
    }

    // -- Public API: verify OTP and return family data ----------------------

    @ResponseBody
    @PostMapping("/api/membership-form/verify-otp")
    public ResponseEntity<Map<String, Object>> verifyOtp(@RequestBody Map<String, Object> body) {
        try {
            String cid    = (String) body.get("cid");
            Object fidObj = body.get("familyId");
            String code   = (String) body.get("code");
            if (cid == null || fidObj == null || code == null) return bad("Missing parameters.");

            PublicScreenLink link = resolveLink(cid);
            if (link == null) return bad("Invalid or expired link.");

            Integer familyId = fidObj instanceof Number n ? n.intValue() : null;
            if (familyId == null) return bad("Invalid family ID.");

            String appClientId = link.getAppClientId();

            // The family must belong to the link's church BEFORE any code is checked
            // or member data returned; same message as an unknown id (no oracle).
            Family family = familyRepo.findById(familyId)
                    .filter(f -> appClientId.equals(f.getAppClientId()))
                    .orElse(null);
            if (family == null) return bad("Family not found.");

            boolean valid = verificationStore.validate(familyId.toString(), "member-lookup", code.trim());
            if (!valid) return bad("Invalid or expired verification code.");

            verificationStore.remove(familyId.toString(), "member-lookup");

            List<Map<String, Object>> memberList = new ArrayList<>();
            for (FamilyMember fm : familyMemberRepo.findActiveMembersByFamilyId(familyId)) {
                memberList.add(memberToMap(fm));
            }

            Map<String, Object> familyMap = new LinkedHashMap<>();
            familyMap.put("id",      family.getId());
            familyMap.put("members", memberList);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("family",  familyMap);
            // The page must hand this back on submit to update THIS family (see class note).
            r.put("updateToken", issueUpdateProof(familyId, appClientId));
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            log.error("Error in verify-otp: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Something went wrong. Please try again."));
        }
    }

    // The public form no longer creates logins. The former check-username /
    // send-signup-otp / verify-signup-otp endpoints and the signup* fields of submit
    // were removed (security audit P1 / N5 / N8): no shipped page called them, the
    // code they emailed went to an address the caller chose, and the account they
    // created was stamped onto an existing member before any approval. Member portal
    // accounts are created through the Member Signup flow.

    // -- Public API: submit membership form --------------------------------

    @ResponseBody
    @PostMapping("/api/membership-form/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String cid = (String) body.get("cid");
        if (cid == null || cid.isBlank()) return bad("Missing link token.");

        PublicScreenLink link = resolveLink(cid);
        if (link == null) return bad("Invalid or expired link.");

        // Every submission e-mails every admin of the church and stages a row (with an
        // optional photo): bounded per network origin and per church before anything is
        // written (security audit P2). The photo is capped to what the page itself allows.
        String limited = sendLimiter.check(PublicSendLimiter.MEMBERSHIP_SUBMIT, request, null, link.getAppClientId());
        if (limited != null) return ResponseEntity.status(429).body(Map.of("error", limited));
        String photoError = checkPhotos(body.get("members"));
        if (photoError != null) return bad(photoError);

        // Validate declaration when required
        Boolean declAccepted = body.get("declarationAccepted") instanceof Boolean b ? b : false;
        if (Boolean.TRUE.equals(link.getShowDeclaration()) && !Boolean.TRUE.equals(declAccepted)) {
            return bad("You must accept the declaration to continue.");
        }

        String appClientId = link.getAppClientId();

        // Build and persist MembershipFamily (no address / name fields — stored on Head member)
        MembershipFamily mf = new MembershipFamily();
        mf.setDeclarationAccepted(declAccepted);
        mf.setAppClientId(appClientId);

        // existingFamilyId arrives from an anonymous request body. It is honoured only
        // with the proof verify-otp issued for THAT family under THIS link — the id
        // alone is a small integer anyone holding the public link could send, and on
        // approval the request overwrites the real members' contact details. A missing,
        // spent, expired or mismatched proof is refused outright (never downgraded to a
        // "new family": the applicant is told to enter their code again), and the family
        // is still re-checked against the link's tenant as defence in depth.
        Object existingFamilyIdObj = body.get("existingFamilyId");
        String updateToken = str(body, "updateToken");
        if (existingFamilyIdObj instanceof Number n) {
            Integer famId = n.intValue();
            if (!isValidUpdateProof(updateToken, famId, appClientId)) {
                log.warn("Membership submit: refusing existingFamilyId={} for client {} — no valid verification proof",
                         famId, appClientId);
                return ResponseEntity.status(403).body(Map.of("error", UPDATE_PROOF_REQUIRED_MSG));
            }
            Family claimed = familyRepo.findById(famId).orElse(null);
            if (claimed != null
                    && !claimed.isDeleteFlag()
                    && appClientId != null
                    && appClientId.equals(claimed.getAppClientId())) {
                mf.setExistingFamilyId(famId);
            } else {
                // Verified earlier but gone (deleted) since: treat the submission as a
                // brand-new family rather than rejecting the applicant outright.
                log.warn("Membership submit: ignoring existingFamilyId={} for client {} (not owned by this link)",
                         famId, appClientId);
            }
        }
        MembershipFamily saved = mfRepo.save(mf);
        if (saved.getExistingFamilyId() != null) spendUpdateProof(updateToken);   // one submission per proof

        // Build and persist each member
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) body.get("members");
        if (members != null) {
            for (Map<String, Object> m2 : members) {
                MembershipFamilyMember mfm = new MembershipFamilyMember();
                mfm.setMembershipFamily(saved);
                mfm.setRole(str(m2, "role"));
                mfm.setGender(str(m2, "gender"));
                mfm.setFirstName(str(m2, "firstName"));
                mfm.setMiddleName(str(m2, "middleName"));
                mfm.setLastName(str(m2, "lastName"));
                mfm.setPhone(str(m2, "phone"));
                mfm.setEmail(str(m2, "email"));
                mfm.setMemberType("Member");
                mfm.setBirthdayMonth(intVal(m2, "birthdayMonth"));
                mfm.setBirthdayDay(intVal(m2, "birthdayDay"));
                mfm.setBirthdayYear(intVal(m2, "birthdayYear"));
                mfm.setAnniversaryMonth(intVal(m2, "anniversaryMonth"));
                mfm.setAnniversaryDay(intVal(m2, "anniversaryDay"));
                mfm.setAnniversaryYear(intVal(m2, "anniversaryYear"));
                mfm.setAddress1(str(m2, "address1"));
                mfm.setAddress2(str(m2, "address2"));
                mfm.setCity(str(m2, "city"));
                mfm.setState(str(m2, "state"));
                mfm.setCountry(str(m2, "country"));
                mfm.setPinCode(str(m2, "pinCode"));
                mfm.setSameAsFamilyAddress(Boolean.TRUE.equals(m2.get("sameAsFamilyAddress")));
                mfm.setPhonePrivate(Boolean.TRUE.equals(m2.get("phonePrivate")));
                mfm.setEmailPrivate(Boolean.TRUE.equals(m2.get("emailPrivate")));
                mfm.setAddressPrivate(Boolean.TRUE.equals(m2.get("addressPrivate")));
                mfm.setOtherName(str(m2, "otherName"));
                mfm.setComments(str(m2, "comments"));
                // Photo — only the primary/head member carries a photo from the form
                String photoVal = str(m2, "photo");
                if (photoVal != null && !photoVal.isBlank()) mfm.setPhotoData(photoVal);
                mfm.setInactive(false);
                mfm.setDisableAlerts(false);
                // Primary (Head) member gets includeContributions = true; others = false
                mfm.setIncludeContributions("Head".equalsIgnoreCase(str(m2, "role")));
                mfm.setAppClientId(appClientId);
                mfmRepo.save(mfm);
            }
        }

        // No login is created here and nothing in family_member is touched by an
        // anonymous submission (see class note): the request is staged for review only.

        // -- Notify Admin & SuperAdmin users -------------------------------
        try {
            List<AppUser> admins = appUserRepository.findAdminsByClientId(appClientId);
            ChurchRegistration church = churchRegRepo.findByClientIdAndDeleteFlagFalse(appClientId).orElse(null);
            String churchName = church != null && church.getChurchName() != null ? church.getChurchName() : "your church";

            // Find head member name from submitted members for email subject
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> submittedMembers2 = (List<Map<String, Object>>) body.get("members");
            String headName = "A member";
            if (submittedMembers2 != null) {
                headName = submittedMembers2.stream()
                        .filter(m -> "Head".equalsIgnoreCase(str(m, "role")))
                        .map(m -> ((str(m, "firstName") != null ? str(m, "firstName") : "") + " " +
                                  (str(m, "lastName")  != null ? str(m, "lastName")  : "")).trim())
                        .filter(n -> !n.isBlank())
                        .findFirst()
                        .orElse("A member");
            }
            final String finalHeadName = headName;

            // Phase B: notifying every admin of one new request is ONE email action.
            try (com.churchgeniuspro.util.EmailActionScope __scope = com.churchgeniuspro.util.EmailActionScope.begin("membership-request-admin-notify")) {
            for (AppUser admin : admins) {
                if (admin.getEmail() == null || admin.getEmail().isBlank()) continue;
                String html = "<p>Hi " + admin.getFirstName() + ",</p>"
                        + "<p><strong>" + finalHeadName + "</strong> has submitted a membership form for "
                        + churchName + ".</p>"
                        + "<p>Please log in to the <strong>Membership Requests</strong> page to review and approve it.</p>"
                        + "<p>— " + churchName + "</p>";
                emailService.sendOrgEmail(admin.getEmail(),
                        "New Membership Form Submitted – " + finalHeadName,
                        html, appClientId);
            }
            }
        } catch (Exception e) {
            // Never fail the submission due to notification errors
        }

        if (submissionNotifications != null) {
            String who = "A family";
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> ms = (List<Map<String, Object>>) body.get("members");
                if (ms != null) {
                    who = ms.stream()
                            .filter(m -> "Head".equalsIgnoreCase(str(m, "role")))
                            .map(m -> ((str(m, "firstName") != null ? str(m, "firstName") : "") + " "
                                     + (str(m, "lastName")  != null ? str(m, "lastName")  : "")).trim())
                            .filter(n -> !n.isBlank()).findFirst().orElse(who);
                }
            } catch (Exception ignored) { }
            submissionNotifications.record(appClientId,
                    com.churchgeniuspro.service.PublicSubmissionNotificationService.Type.MEMBERSHIP,
                    "New membership form",
                    who + " submitted a membership form for review.",
                    saved.getId() == null ? null : saved.getId().longValue());
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("id", saved.getId());
        return ResponseEntity.ok(r);
    }

    // (The former /api/member/debug session dump was removed — security audit P16.)

    // -- Admin: manually link a signup to a family member -----------------
    @ResponseBody
    @PostMapping("/api/member/link-signup")
    public ResponseEntity<?> linkSignup(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        Integer signupId      = body.get("signupId")      instanceof Number n ? n.intValue() : null;
        Integer familyMemberId = body.get("familyMemberId") instanceof Number n ? n.intValue() : null;
        if (signupId == null || familyMemberId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "signupId and familyMemberId are required"));
        String adminClientId = RoleGuard.clientId(request);
        FamilyMember fm = familyMemberRepo.findById(familyMemberId)
                .filter(m -> adminClientId != null && adminClientId.equals(
                        m.getFamily() != null ? m.getFamily().getAppClientId() : m.getAppClientId()))
                .orElse(null);
        if (fm == null) return ResponseEntity.badRequest().body(Map.of("error", "Family member not found"));
        com.churchgeniuspro.hibernate.SignUp su = loginRepository.findById(signupId).orElse(null);
        if (su == null) return ResponseEntity.badRequest().body(Map.of("error", "Signup not found"));
        // The signup must have been created by an application to THIS church, or be an
        // unbound member signup. Never re-bind a signup that is already someone's login.
        boolean fromThisChurch = mfRepo.findBySignupIdAndDeleteFlagFalse(signupId)
                .map(mf -> adminClientId.equals(mf.getAppClientId())).orElse(false);
        boolean alreadyBound = su.getClientId() != null && su.getClientId().startsWith("MBR")
                && familyMemberRepo.findByMemberRef(su.getClientId()).map(m -> !m.getId().equals(fm.getId())).orElse(false);
        if (!fromThisChurch || alreadyBound) {
            return ResponseEntity.badRequest().body(Map.of("error", "Signup not found"));
        }
        // Link by setting the family member's memberRef to match the signup's clientId
        if (su.getClientId() != null && su.getClientId().startsWith("MBR")) {
            fm.setMemberRef(su.getClientId());
        }
        familyMemberRepo.save(fm);
        su.setActive(true);
        loginRepository.save(su);
        return ResponseEntity.ok(Map.of("success", true,
            "familyMemberId", fm.getId(), "signupId", signupId));
    }

    // -- Member API: get own family ----------------------------------------

    @ResponseBody
    @GetMapping("/api/member/family")
    public ResponseEntity<?> getMemberFamily(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        Object memberIdObj = session.getAttribute("memberId");
        Integer memberId = memberIdObj instanceof Number n ? n.intValue() : null;

        FamilyMember self = null;

        if (memberId != null) {
            self = familyMemberRepo.findById(memberId).orElse(null);
        }

        // memberId not in session — resolve via clientId (= memberRef = MBR<uuid>).
        // The MBR token is the only trusted member<->row binding (unique member_ref; the
        // same rule LoginController.buildResponse applies). There is deliberately no
        // email/username fallback: a lookup must never change the session's tenant.
        if (self == null) {
            String clientId = session.getAttribute("clientId") instanceof String s ? s : null;
            if (clientId != null && clientId.startsWith("MBR")) {
                self = familyMemberRepo.findByMemberRef(clientId).orElse(null);
                if (self != null) {
                    session.setAttribute("memberId", self.getId());
                    if (self.getMemberRef() != null)
                        session.setAttribute("memberRef", self.getMemberRef());
                    String appCid = self.getFamily() != null
                            ? self.getFamily().getAppClientId() : self.getAppClientId();
                    session.setAttribute("appClientId", appCid);
                    memberId = self.getId();
                }
            }
        }

        // Still null — try resolving via staff user's linked member account.
        // A staff AppUser may be linked to a Member signup via link_group.
        // We look up the AppUser by appUserId stored in session, then match their
        // email to a FamilyMember in the same org so the member portal shows real data.
        if (self == null) {
            Object appUserIdAttr = session.getAttribute("appUserId");
            if (appUserIdAttr instanceof Integer appUserId) {
                AppUser appUser = appUserRepository.findById(appUserId).orElse(null);
                if (appUser != null && appUser.getEmail() != null && !appUser.getEmail().isBlank()) {
                    String staffEmail    = appUser.getEmail().trim().toLowerCase();
                    String staffClientId = appUser.getClientId(); // org's appClientId
                    // Find a FamilyMember with matching email in the same org. The query is
                    // tenant-scoped (never a cross-tenant scan) — same predicate the previous
                    // in-memory filter applied, so results are unchanged.
                    if (staffClientId != null) {
                        java.util.List<FamilyMember> candidates =
                                familyMemberRepo.findActiveByEmailAndTenant(staffEmail, staffClientId);
                        if (!candidates.isEmpty()) self = candidates.get(0);
                    }
                    if (self != null) {
                        // Cache in session so subsequent API calls resolve instantly
                        session.setAttribute("memberId", self.getId());
                        if (self.getMemberRef() != null)
                            session.setAttribute("memberRef", self.getMemberRef());
                        String appCid = self.getFamily() != null
                                ? self.getFamily().getAppClientId() : self.getAppClientId();
                        if (appCid != null) session.setAttribute("appClientId", appCid);
                        memberId = self.getId();
                    }
                }
            }
        }

        // Still null — no linked member record found for this staff user.
        // Return empty data (not 401) so the member portal renders cleanly.
        if (self == null) {
            boolean isStaff = session.getAttribute("username") != null;
            if (isStaff) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("member", null);
                empty.put("family", null);
                empty.put("familyMembers", java.util.Collections.emptyList());
                return ResponseEntity.ok(empty);
            }
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        // Use the family ID from the proxy — getId() is safe without a session because
        // Hibernate stores the identifier directly on the proxy object itself.
        // Never call other getters on the proxy (e.g. getAppClientId()) outside a session.
        Integer familyId = self.getFamily() != null ? self.getFamily().getId() : null;
        if (familyId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        // appClientId is a plain column on FamilyMember — safe to read without a session.
        // Fall back to loading it from the Family row only if missing on the member record.
        String appClientId = self.getAppClientId();
        if (appClientId == null || appClientId.isBlank()) {
            appClientId = familyMemberRepo.findFamilyAppClientId(familyId);
        }

        List<Map<String, Object>> memberList = familyMemberRepo
                .findActiveMembersByFamilyId(familyId)
                .stream().map(this::memberToMap).collect(java.util.stream.Collectors.toList());

        // Church name
        String churchName = "";
        if (appClientId != null && !appClientId.isBlank()) {
            churchName = churchRegRepo.findByClientIdAndDeleteFlagFalse(appClientId)
                    .map(ChurchRegistration::getChurchName).orElse("");
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("selfId",     memberId);
        r.put("familyId",   familyId);
        r.put("members",    memberList);
        r.put("churchName", churchName);
        return ResponseEntity.ok(r);
    }

    // -- Member API: update a family member's details ---------------------

    @ResponseBody
    @PatchMapping("/api/member/update/{memberId}")
    public ResponseEntity<?> updateMember(@PathVariable Integer memberId,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (!"Member".equals(session.getAttribute("role")))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        // Verify the member belongs to the logged-in member's family
        Integer sessionMemberId = session.getAttribute("memberId") instanceof Number n ? n.intValue() : null;
        FamilyMember self = sessionMemberId != null ? familyMemberRepo.findById(sessionMemberId).orElse(null) : null;
        if (self == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        FamilyMember fm = familyMemberRepo.findById(memberId).orElse(null);
        if (fm == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found"));

        // Ensure the target member is in the same family
        if (fm.getFamily() == null || !fm.getFamily().getId().equals(self.getFamily().getId()))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        // Apply editable fields (no role/family changes allowed via member portal)
        if (body.containsKey("firstName"))  fm.setFirstName(str(body, "firstName"));
        if (body.containsKey("lastName"))   fm.setLastName(str(body, "lastName"));
        if (body.containsKey("phone"))      fm.setPhone(str(body, "phone"));
        if (body.containsKey("email"))      fm.setEmail(str(body, "email"));
        if (body.containsKey("otherName"))  fm.setOtherName(str(body, "otherName"));
        if (body.containsKey("gender"))     fm.setGender(str(body, "gender"));
        if (body.containsKey("address1"))   fm.setAddress1(str(body, "address1"));
        if (body.containsKey("address2"))   fm.setAddress2(str(body, "address2"));
        if (body.containsKey("city"))       fm.setCity(str(body, "city"));
        if (body.containsKey("state"))      fm.setState(str(body, "state"));
        if (body.containsKey("country"))    fm.setCountry(str(body, "country"));
        if (body.containsKey("pinCode"))    fm.setPinCode(str(body, "pinCode"));
        if (body.containsKey("birthdayMonth")) fm.setBirthdayMonth(intVal(body, "birthdayMonth"));
        if (body.containsKey("birthdayDay"))   fm.setBirthdayDay(intVal(body, "birthdayDay"));
        if (body.containsKey("birthdayYear"))  fm.setBirthdayYear(intVal(body, "birthdayYear"));

        familyMemberRepo.save(fm);
        return ResponseEntity.ok(Map.of("success", true, "member", memberToMap(fm)));
    }

    // -- Member API: all org members grouped by family, role-ordered ---------

    /** Role sort order: Head of Household → Spouse → Adult → Child → others. */
    private static int roleOrder(String role) {
        if (role == null) return 99;
        return switch (role.toLowerCase()) {
            case "head of household", "head" -> 0;
            case "spouse", "wife"            -> 1;
            case "adult"                     -> 2;
            case "child", "son", "daughter"  -> 3;
            default                          -> 4;
        };
    }

    @ResponseBody
    @GetMapping("/api/member/directory")
    public ResponseEntity<?> getMemberDirectory(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        // The Directory is visible to member-portal users AND to staff
        // (Admin/SuperAdmin/Accountant/User/Limited). guardMemberApi returned an
        // empty list for staff without a linked member account, which made the
        // directory show "No members found" for staff — allow both here instead.
        boolean isMemberSession = session != null
                && "Member".equals(session.getAttribute("role"))
                && session.getAttribute("memberId") != null;
        boolean isStaffSess = session != null && session.getAttribute("username") != null;
        if (session == null || (!isMemberSession && !isStaffSess))
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        String appClientId = resolveAppClientId(session);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "No org"));

        // Field-level privacy: staff see everything; a member sees their OWN private
        // fields but not other members'. (Directory shows the whole church.)
        // NOTE: member-portal sessions ALSO have a "username" attribute (set in the
        // common login block), so staff must be detected as "not a member session"
        // — using username alone wrongly treated every member as staff and skipped
        // redaction entirely.
        boolean viewerIsStaff = !isMemberSession;
        Integer viewerMemberId = session.getAttribute("memberId") instanceof Number vn ? vn.intValue() : null;

        // Collect set of memberRefs that have an active signup account
        java.util.Set<String> accountRefs = loginRepository.findAll().stream()
                .filter(s -> Boolean.FALSE.equals(s.getDeleted())
                          && Boolean.TRUE.equals(s.getActive())
                          && s.getClientId() != null
                          && s.getClientId().startsWith("MBR"))
                .map(SignUp::getClientId)
                .collect(java.util.stream.Collectors.toSet());

        List<FamilyMember> members = familyMemberRepo.findAllWithFamilyByAppUser(appClientId);

        // Group by familyId
        Map<Integer, List<FamilyMember>> byFamily = new LinkedHashMap<>();
        for (FamilyMember m : members) {
            Integer fid = m.getFamily() != null ? m.getFamily().getId() : 0;
            byFamily.computeIfAbsent(fid, k -> new ArrayList<>()).add(m);
        }

        // Build response: list of family groups, each with role-ordered members
        List<Map<String, Object>> families = new ArrayList<>();
        for (Map.Entry<Integer, List<FamilyMember>> entry : byFamily.entrySet()) {
            List<FamilyMember> fms = entry.getValue();
            fms.sort(java.util.Comparator.comparingInt(fm -> roleOrder(fm.getRole())));

            // Family display name = head's last name or first member's name
            String familyName = fms.stream()
                    .filter(fm -> roleOrder(fm.getRole()) == 0)
                    .findFirst()
                    .map(fm -> (fm.getLastName() != null ? fm.getLastName() : "") + " Family")
                    .orElse(fms.get(0).getFirstName() + " Family");

            List<Map<String, Object>> memberList = fms.stream().map(m -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id",         m.getId());
                mm.put("memberRef",  m.getMemberRef());
                mm.put("firstName",  m.getFirstName());
                mm.put("lastName",   m.getLastName());
                mm.put("nickname",   m.getOtherName());
                mm.put("displayName", com.churchgeniuspro.util.MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName()));
                mm.put("role",       m.getRole());
                // Field-level privacy: hide Private fields from members viewing OTHER
                // members. Staff see all; a member always sees their own record.
                boolean redact = !viewerIsStaff
                        && !(viewerMemberId != null && viewerMemberId.equals(m.getId()));
                boolean phPriv = redact && Boolean.TRUE.equals(m.getPhonePrivate());
                boolean emPriv = redact && Boolean.TRUE.equals(m.getEmailPrivate());
                boolean adPriv = redact && Boolean.TRUE.equals(m.getAddressPrivate());
                mm.put("phone",      phPriv ? null : m.getPhone());
                mm.put("email",      emPriv ? null : m.getEmail());
                mm.put("address1",   adPriv ? null : m.getAddress1());
                mm.put("address2",   adPriv ? null : m.getAddress2());
                mm.put("city",       adPriv ? null : m.getCity());
                mm.put("state",      adPriv ? null : m.getState());
                mm.put("country",    adPriv ? null : m.getCountry());
                mm.put("pinCode",    adPriv ? null : m.getPinCode());
                mm.put("hasEmail",   !emPriv && m.getEmail() != null && !m.getEmail().isBlank());
                mm.put("hasPhone",   !phPriv && m.getPhone() != null && !m.getPhone().isBlank());
                mm.put("hasAccount", m.getMemberRef() != null
                                     && accountRefs.contains(m.getMemberRef()));
                return mm;
            }).collect(java.util.stream.Collectors.toList());

            Map<String, Object> fg = new LinkedHashMap<>();
            fg.put("familyId",   entry.getKey());
            fg.put("familyName", familyName);
            fg.put("members",    memberList);
            families.add(fg);
        }

        // Sort family groups by head's last name
        families.sort((a, b) -> {
            String na = (String) a.get("familyName");
            String nb = (String) b.get("familyName");
            return na.compareToIgnoreCase(nb);
        });

        return ResponseEntity.ok(families);
    }

    // -- Member API: calendar events for a given year/month ---------------

    /**
     * Returns all calendar items (meetings, birthdays, anniversaries, church events)
     * for the given year + month, using the same recurrence-expansion engine as
     * {@link EventCalendarController#buildEvents}.
     *
     * <p>The response wraps everything in an {@code events} list and an empty
     * {@code meetings} list so the existing frontend {@code renderCalendar(events, meetings)}
     * call continues to work without changes.
     */
    @ResponseBody
    @GetMapping("/api/member/events")
    public ResponseEntity<?> getMemberEvents(
            @RequestParam int year,
            @RequestParam int month,
            HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        Map<String, Object> emptyEvents = new LinkedHashMap<>();
        emptyEvents.put("events", java.util.Collections.emptyList());
        emptyEvents.put("meetings", java.util.Collections.emptyList());
        ResponseEntity<?> guard = guardMemberApi(session, emptyEvents);
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "No org"));

        // Delegate entirely to the same recurrence engine that powers eventCalendar / viewEventCalendar.
        // This correctly expands weekly, monthly, one-time meetings AND includes birthdays,
        // anniversaries, and church events (one-day and multi-day).
        List<Map<String, Object>> all = eventCalendarController.buildEvents(appClientId, year, month);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events",   all);
        result.put("meetings", Collections.emptyList());
        return ResponseEntity.ok(result);
    }

    // -- Member API: upcoming events + meetings + birthdays ----------------

    @ResponseBody
    @GetMapping("/api/member/upcoming")
    public ResponseEntity<?> getMemberUpcoming(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, java.util.Collections.emptyList());
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "No org"));

        LocalDate today = LocalDate.now();
        LocalDate in60  = today.plusDays(60);

        // Events
        List<Map<String, Object>> events = churchEventRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(appClientId)
                .stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type",        "event");
                    m.put("id",          e.getId());
                    m.put("title",       e.getEventName());
                    m.put("date",        e.getEventDate() != null ? e.getEventDate().toString() : null);
                    m.put("primaryName", e.getEventName()); // event name serves as location label
                    m.put("address1",    e.getAddress1());
                    m.put("address2",    e.getAddress2());
                    m.put("city",        e.getCity());
                    m.put("state",       e.getState());
                    m.put("country",     e.getCountry());
                    m.put("pinCode",     e.getPinCode());
                    return m;
                }).filter(m -> {
                    String d = (String) m.get("date");
                    if (d == null) return false;
                    try { return !LocalDate.parse(d).isBefore(today); } catch (Exception ex) { return false; }
                  })
                  .sorted(java.util.Comparator
                      .<Map<String, Object>, String>comparing(m -> (String) m.getOrDefault("date", "")))
                  .collect(java.util.stream.Collectors.toList());

        // Meetings — sorted by computed occurrence date ASC, then startTime ASC
        List<Map<String, Object>> meetings = meetingRepo
                .findByAppClientIdAndDeleteFlagFalse(appClientId)
                .stream().map(m2 -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type",     "meeting");
                    m.put("id",       m2.getId());
                    String title = m2.getMeetingType() != null && m2.getMeetingType().getTypeName() != null
                            ? m2.getMeetingType().getTypeName() : "Meeting";
                    m.put("title",    title);
                    // Compute next occurrence date for recurring meetings
                    m.put("date", nextOccurrenceDate(m2.getMeetingDate(), m2.getOccurrence(), m2.getEndDate(), today));
                    // Resolve Head-of-Household name from location family
                    String hohName = null;
                    if (m2.getLocationFamilyId() != null) {
                        hohName = familyRepo.findByIdWithMembers(m2.getLocationFamilyId())
                                .map(f -> f.getMembers().stream()
                                        .filter(mem -> "Head".equalsIgnoreCase(mem.getMemberType())
                                                && !mem.isDeleteFlag())
                                        .findFirst()
                                        .map(mem -> (mem.getFirstName() != null ? mem.getFirstName().trim() : "") + " " + (mem.getLastName() != null ? mem.getLastName().trim() : ""))
                                        .orElse(null))
                                .orElse(null);
                    }
                    m.put("primaryName", hohName);
                    m.put("address1",    m2.getAddress1());
                    m.put("address2",    m2.getAddress2());
                    m.put("city",        m2.getCity());
                    m.put("state",       m2.getState());
                    m.put("country",     m2.getCountry());
                    m.put("pinCode",     m2.getPinCode());
                    m.put("startTime",   m2.getStartTime() != null ? m2.getStartTime() : "");
                    return m;
                }).filter(m -> {
                    String d = (String) m.get("date");
                    if (d == null) return false;
                    try { return !LocalDate.parse(d).isBefore(today); } catch (Exception ex) { return false; }
                  })
                  .sorted(java.util.Comparator
                      .<Map<String, Object>, String>comparing(m -> (String) m.getOrDefault("date", ""))
                      .thenComparing(m -> (String) m.getOrDefault("startTime", "")))
                  .collect(java.util.stream.Collectors.toList());

        // Birthdays (upcoming only: today or later this month, or all of next month)
        List<FamilyMember> allMembers = familyMemberRepo.findAllWithFamilyByAppUser(appClientId);
        int thisMonth   = today.getMonthValue();
        int nextMonth   = today.plusMonths(1).getMonthValue();
        int todayDay    = today.getDayOfMonth();
        List<Map<String, Object>> birthdays = allMembers.stream()
                .filter(m -> m.getBirthdayMonth() != null
                        && ((m.getBirthdayMonth() == thisMonth
                                && m.getBirthdayDay() != null
                                && m.getBirthdayDay() >= todayDay)
                            || m.getBirthdayMonth() == nextMonth))
                .map(m -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("type",      "birthday");
                    mm.put("name",      com.churchgeniuspro.util.MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName()));
                    mm.put("month",     m.getBirthdayMonth());
                    mm.put("day",       m.getBirthdayDay());
                    return mm;
                }).collect(java.util.stream.Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events",    events);
        result.put("meetings",  meetings);
        result.put("birthdays", birthdays);
        return ResponseEntity.ok(result);
    }

    // -- Member API: family contributions ---------------------------------

    @ResponseBody
    @GetMapping("/api/member/contributions")
    public ResponseEntity<?> getMemberContributions(
            @RequestParam(required = false) Integer year,
            HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        int filterYearFallback = (year != null) ? year : LocalDate.now().getYear();
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("year", filterYearFallback, "total", java.math.BigDecimal.ZERO, "records", java.util.Collections.emptyList()));
        if (guard != null) return guard;
        resolveAppClientId(session); // ensure memberId is populated in session
        Object memberIdObj = session.getAttribute("memberId");
        Integer memberId = memberIdObj instanceof Number n ? n.intValue() : null;
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        FamilyMember self = familyMemberRepo.findById(memberId).orElse(null);
        if (self == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found"));

        int filterYear = (year != null) ? year : LocalDate.now().getYear();

        List<Map<String, Object>> all = new ArrayList<>();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;

        // Only return contributions for the logged-in member, not the whole family
        List<Income> incomes = incomeRepo.findByMemberActive(self.getId());
        for (Income inc : incomes) {
            if (inc.getIncomeDate() != null && inc.getIncomeDate().getYear() == filterYear) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date",    inc.getIncomeDate().toString());
                row.put("name",    (self.getFirstName() != null ? self.getFirstName() : "") + " " +
                                   (self.getLastName()  != null ? self.getLastName()  : ""));
                row.put("purpose", inc.getSubSource() != null ? inc.getSubSource().getSourceName() : "—");
                row.put("amount",  inc.getAmount() != null ? inc.getAmount() : java.math.BigDecimal.ZERO);
                all.add(row);
                total = total.add(inc.getAmount() != null ? inc.getAmount() : java.math.BigDecimal.ZERO);
            }
        }
        all.sort((a, b) -> String.valueOf(b.get("date")).compareTo(String.valueOf(a.get("date"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year",    filterYear);
        result.put("total",   total);
        result.put("records", all);
        return ResponseEntity.ok(result);
    }

    // -- Member API: my pledges --------------------------------------------

    /**
     * Returns the logged-in member's pledges across every campaign, along with
     * pledged / collected / pending totals. Used by the Contributions tab on
     * /memberHome. Walk-up (guest) pledges and other members' pledges are
     * never included.
     */
    @ResponseBody
    @GetMapping("/api/member/pledges")
    public ResponseEntity<?> getMemberPledges(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        Map<String, Object> emptyShell = Map.of(
                "pledges", java.util.Collections.emptyList(),
                "totalPledged",   java.math.BigDecimal.ZERO,
                "totalCollected", java.math.BigDecimal.ZERO,
                "totalPending",   java.math.BigDecimal.ZERO);
        ResponseEntity<?> guard = guardMemberApi(session, emptyShell);
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        Object memberIdObj = session.getAttribute("memberId");
        Integer memberId = memberIdObj instanceof Number n ? n.intValue() : null;
        if (memberId == null || appClientId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        List<PledgeMember> rows = pledgeMemberRepo.findByMember(appClientId, memberId);

        // Build a small (id → name) cache for the campaigns referenced.
        Map<Integer, PledgeCampaign> campaignCache = new java.util.HashMap<>();
        for (PledgeMember p : rows) {
            if (p.getCampaignId() != null && !campaignCache.containsKey(p.getCampaignId())) {
                pledgeCampaignRepo.findByIdAndClientId(p.getCampaignId(), appClientId)
                        .ifPresent(c -> campaignCache.put(c.getId(), c));
            }
        }

        java.math.BigDecimal totalPledged   = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalCollected = java.math.BigDecimal.ZERO;

        List<Map<String, Object>> out = new ArrayList<>();
        for (PledgeMember p : rows) {
            PledgeCampaign c = p.getCampaignId() != null ? campaignCache.get(p.getCampaignId()) : null;
            // Skip if the parent campaign was deleted (defensive — repo
            // already filters by deleteFlag, but the campaign itself could
            // be soft-deleted independently).
            if (c == null || c.isDeleteFlag()) continue;

            java.math.BigDecimal pledged   = p.getPledgeAmount() != null ? p.getPledgeAmount() : java.math.BigDecimal.ZERO;
            // Compute collected from actual income (member + campaign fund) so it
            // reflects fund edits and contributions recorded before the pledge.
            java.math.BigDecimal collected = pledgeController.collectedForPledge(p, c);
            java.math.BigDecimal pending   = pledged.subtract(collected);
            if (pending.signum() < 0) pending = java.math.BigDecimal.ZERO;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pledgeId",       p.getId());
            row.put("campaignId",     c.getId());
            row.put("campaignName",   c.getName());
            row.put("campaignStatus", c.getStatus());
            row.put("endDate",        c.getEndDate() != null ? c.getEndDate().toString() : null);
            row.put("pledged",        pledged);
            row.put("collected",      collected);
            row.put("pending",        pending);
            row.put("monthlyAmount",  p.getMonthlyAmount());
            row.put("gifts",          p.getGifts());
            row.put("notes",          p.getNotes());
            out.add(row);

            totalPledged   = totalPledged.add(pledged);
            totalCollected = totalCollected.add(collected);
        }
        // Most-recently created first.
        out.sort((a, b) -> Integer.compare(
                ((Number) b.getOrDefault("pledgeId", 0)).intValue(),
                ((Number) a.getOrDefault("pledgeId", 0)).intValue()));

        java.math.BigDecimal totalPending = totalPledged.subtract(totalCollected);
        if (totalPending.signum() < 0) totalPending = java.math.BigDecimal.ZERO;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pledges",        out);
        result.put("totalPledged",   totalPledged);
        result.put("totalCollected", totalCollected);
        result.put("totalPending",   totalPending);
        return ResponseEntity.ok(result);
    }

    // -- Member API: contact admin -----------------------------------------

    @ResponseBody
    @PostMapping("/api/member/contact-admin")
    public ResponseEntity<?> contactAdmin(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try (com.churchgeniuspro.util.EmailActionScope __scope = com.churchgeniuspro.util.EmailActionScope.begin("member-contact-admin")) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("success", false, "error", "Not a member account"));
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        Object memberIdObj = session.getAttribute("memberId");
        Integer memberId = memberIdObj instanceof Number n ? n.intValue() : null;
        if (memberId == null || appClientId == null)
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        String subject = str(body, "subject");
        String message = str(body, "message");
        if (message == null || message.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required."));
        FamilyMember self = familyMemberRepo.findById(memberId).orElse(null);
        String memberName = self != null ? ((self.getFirstName()!=null?self.getFirstName():"")+' '+(self.getLastName()!=null?self.getLastName():"")).trim() : "A member";
        String memberEmail = self != null ? self.getEmail() : null;
        String emailSubject = "Member Message: " + (subject != null && !subject.isBlank() ? subject : "General Enquiry") + " – " + memberName;
        String html = "<p><strong>From:</strong> " + memberName + "</p>"
                + (memberEmail != null ? "<p><strong>Email:</strong> " + memberEmail + "</p>" : "")
                + "<p><strong>Message:</strong></p><p>" + message.replace("<","&lt;").replace(">","&gt;") + "</p><p>— " + emailService.getChurchName(appClientId) + "</p>";
        try {
            List<AppUser> admins = appUserRepository.findAdminsByClientId(appClientId);
            if (admins.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No admin email found."));
            for (AppUser admin : admins) { if (admin.getEmail()==null||admin.getEmail().isBlank()) continue; emailService.sendOrgEmail(admin.getEmail(), emailSubject, html, appClientId); }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return ResponseEntity.status(500).body(Map.of("error", "Failed to send email.")); }
            }
    }

    // -- Member API: get purposes for Give ---------------------------------

    @ResponseBody
    @GetMapping("/api/member/purposes")
    public ResponseEntity<?> getMemberPurposes(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, java.util.Collections.emptyList());
        if (guard != null) return guard;
        Object appCid = request.getSession(false).getAttribute("appClientId");
        String appClientId = appCid != null ? String.valueOf(appCid) : null;
        List<SubSource> sources = subSourceRepo.findAllActiveByAppUser(appClientId);
        List<Map<String,Object>> result = sources.stream().map(s->{ Map<String,Object> m=new LinkedHashMap<>(); m.put("id",s.getId()); m.put("name",s.getSourceName()); return m; }).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // -- Member API: submit giving -----------------------------------------

    @ResponseBody
    @PostMapping("/api/member/give")
    public ResponseEntity<?> memberGive(@RequestBody Map<String,Object> body, HttpServletRequest request) {
        // Phase D: a contribution is a real Stripe payment made through
        // MemberContributionController (/api/member/give/intent + /save). This
        // endpoint used to record an income row with no payment behind it; it no
        // longer records anything.
        Object memberIdObj = request.getSession(false)!=null?request.getSession(false).getAttribute("memberId"):null;
        if (memberIdObj == null) return ResponseEntity.status(401).body(Map.of("error","Not authenticated"));
        return ResponseEntity.status(410).body(Map.of("error",
                "Contributions are now made as online payments. Please use the Give / Contribute form to pay by card."));
    }

    // -- Member API: personal tax / giving report -------------------------

    @ResponseBody
    @GetMapping("/api/member/tax-report")
    public ResponseEntity<?> getMemberTaxReport(
            @RequestParam(required = false) Integer year,
            HttpServletRequest request) {

        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, java.util.Collections.emptyList());
        if (guard != null) return guard;

        resolveAppClientId(session);
        Object memberIdObj = session.getAttribute("memberId");
        Integer memberId = memberIdObj instanceof Number n ? n.intValue() : null;
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        FamilyMember self = familyMemberRepo.findById(memberId).orElse(null);
        if (self == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found"));

        String appClientId = resolveAppClientId(session);
        int filterYear = (year != null) ? year : LocalDate.now().getYear();

        // Church name
        String churchName = (appClientId != null && !appClientId.isBlank())
                ? churchRegRepo.findByClientIdAndDeleteFlagFalse(appClientId)
                               .map(cr -> cr.getChurchName() != null ? cr.getChurchName() : "")
                               .orElse("")
                : "";

        // Individual entries for the logged-in member only, scoped to this org
        // guestName=null: member portal always looks up a specific registered member
        List<Object[]> entryRows = incomeRepo.reportIncomeEntriesForTaxYear(filterYear, appClientId, memberId, null);

        // Column layout (after guest_name was added at r[3]):
        // [0]member_id [1]first_name [2]last_name [3]guest_name
        // [4]address1  [5]address2   [6]city       [7]state  [8]pin_code
        // [9]income_date [10]main_name [11]sub_name [12]amount [13]method
        List<Map<String, Object>> entries = new ArrayList<>();
        BigDecimal memberTotal = BigDecimal.ZERO;
        for (Object[] r : entryRows) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("date",         taxDateStr(r[9]));
            e.put("mainCategory", r[10] != null ? r[10].toString() : "");
            e.put("subCategory",  r[11] != null ? r[11].toString() : "");
            BigDecimal amt = r[12] instanceof BigDecimal bd ? bd
                           : r[12] != null ? new BigDecimal(r[12].toString()) : BigDecimal.ZERO;
            e.put("amount", amt);
            e.put("method", r[13] != null ? r[13].toString() : "");
            entries.add(e);
            memberTotal = memberTotal.add(amt);
        }

        // Address (from first entry or member record)
        String addr1   = self.getAddress1()  != null ? self.getAddress1()  : "";
        String addr2   = self.getAddress2()  != null ? self.getAddress2()  : "";
        String city    = self.getCity()      != null ? self.getCity()      : "";
        String state   = self.getState()     != null ? self.getState()     : "";
        String pinCode = self.getPinCode()   != null ? self.getPinCode()   : "";

        Map<String, Object> memberData = new LinkedHashMap<>();
        memberData.put("firstName",   self.getFirstName()  != null ? self.getFirstName()  : "");
        memberData.put("lastName",    self.getLastName()   != null ? self.getLastName()   : "");
        memberData.put("address1",    addr1);
        memberData.put("address2",    addr2);
        memberData.put("city",        city);
        memberData.put("state",       state);
        memberData.put("pinCode",     pinCode);
        memberData.put("memberTotal", memberTotal);
        memberData.put("entries",     entries);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year",        filterYear);
        result.put("churchName",  churchName);
        result.put("member",      memberData);
        result.put("grandTotal",  memberTotal);
        // The wording above and below the contribution table, this church's own
        // where it has edited it. Same source as the staff Year-End Tax Report, so
        // a member's statement and the printed one cannot drift apart.
        result.putAll(letterService != null
                ? letterService.rendered(appClientId, churchName, filterYear)
                : com.churchgeniuspro.service.FinancialReportLetterService
                        .defaults(churchName, filterYear));
        return ResponseEntity.ok(result);
    }

    // -- Member home page --------------------------------------------------

    @GetMapping("/memberHome")
    public String memberHomePage(HttpServletRequest request) {
        // Allow any authenticated session (Member portal users OR staff with member
        // portal permissions granted) to access this page.
        // Unauthenticated requests → login.
        // Church-type logins → redirect to their own home (/viewusers).
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session == null) return "redirect:/login";

        Object roleAttr = session.getAttribute("role");
        String role = roleAttr instanceof String s ? s : null;

        // Unauthenticated (no username and no memberId)
        boolean isStaff  = session.getAttribute("username") != null;
        boolean isMember = "Member".equals(role) && session.getAttribute("memberId") != null;
        if (!isStaff && !isMember) return "redirect:/login";

        // Church-type logins have no business on the member home page
        Object churchAttr = session.getAttribute("church");
        boolean isChurch  = Boolean.TRUE.equals(churchAttr) || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (isChurch) return "redirect:/viewusers";

        // Staff users: enforce the 'member' section permission key.
        // The permissions modal saves 'member' = false when the entire MY PROFILE
        // section is unchecked. requirePermission() returns FORWARD_ACCESS_DENIED
        // when the key is explicitly false; null means allowed.
        // Member portal users (isMember) bypass this — sub-tab visibility is
        // governed separately by memberPrivileges, not the section-level gate.
        if (isStaff && !isMember) {
            String deny = com.churchgeniuspro.util.RoleGuard.requirePagePermission(request, "member");
            if (deny != null) return deny;
        }

        return "forward:/memberHome.html";
    }

    // -- Admin API: approve-signup -----------------------------------------

    @ResponseBody
    @PostMapping("/api/membership-requests/{id}/approve-signup")
    public ResponseEntity<?> approveSignup(@PathVariable Integer id, HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrChurch(request);
        if (deny!=null) return ResponseEntity.status(403).body(Map.of("error","Access denied"));
        MembershipFamily mf = mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, SessionUtil.getAppClientId(request)).orElse(null);
        if (mf==null) return ResponseEntity.notFound().build();
        if (mf.getSignupId()==null) return ResponseEntity.badRequest().body(Map.of("error","No signup account."));
        SignUp signup = loginRepository.findById(mf.getSignupId()).orElse(null);
        if (signup==null) return ResponseEntity.badRequest().body(Map.of("error","Signup not found."));
        signup.setActive(true); loginRepository.save(signup);
        return ResponseEntity.ok(Map.of("success",true));
    }

    // -- Admin page --------------------------------------------------------

    @GetMapping("/membershipRequests")
    public String requestsPage(HttpServletRequest request) {
        // Members with admin.membership permission are allowed through
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        boolean isMember = session != null
                && "Member".equals(session.getAttribute("role"))
                && session.getAttribute("memberId") != null;
        if (isMember) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.membership");
            return deny != null ? deny : "forward:/membershipRequests.html";
        }
        String deny = RoleGuard.requireAdminOrChurch(request); if (deny!=null) return deny;
        deny = RoleGuard.requirePagePermission(request, "admin.membership"); if (deny!=null) return deny;
        return "forward:/membershipRequests.html";
    }

    @ResponseBody @GetMapping("/api/membership-requests")
    public ResponseEntity<?> list(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession _s = request.getSession(false);
        boolean _isMbr = _s != null && "Member".equals(_s.getAttribute("role")) && _s.getAttribute("memberId") != null;
        if (_isMbr) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.membership");
            if (deny != null) return ResponseEntity.status(403).body(Map.of("error","Access denied"));
        } else {
            String deny = RoleGuard.requireAdminOrChurch(request); if (deny!=null) return ResponseEntity.status(403).body(Map.of("error","Access denied"));
        }
        String appClientId = _isMbr ? resolveAppClientId(_s) : SessionUtil.getAppClientId(request);
        List<MembershipFamily> families = mfRepo.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(appClientId);
        List<Map<String,Object>> result = new ArrayList<>();
        for (MembershipFamily f : families) result.add(toFamilyMap(f,false));
        return ResponseEntity.ok(result);
    }

    @ResponseBody @GetMapping("/api/membership-requests/{id}")
    public ResponseEntity<?> getOne(@PathVariable Integer id, HttpServletRequest request) {
        jakarta.servlet.http.HttpSession _s = request.getSession(false);
        boolean _isMbr = _s != null && "Member".equals(_s.getAttribute("role")) && _s.getAttribute("memberId") != null;
        if (_isMbr) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.membership");
            if (deny != null) return ResponseEntity.status(403).body(Map.of("error","Access denied"));
        } else {
            String deny = RoleGuard.requireAdminOrChurch(request); if (deny!=null) return ResponseEntity.status(403).body(Map.of("error","Access denied"));
        }
        String appClientId = _isMbr ? resolveAppClientId(_s) : SessionUtil.getAppClientId(request);
        MembershipFamily mf = mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId).orElse(null);
        if (mf==null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toFamilyMap(mf,true));
    }

    @ResponseBody @PostMapping("/api/membership-requests/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Integer id, HttpServletRequest request) {
        jakarta.servlet.http.HttpSession _s = request.getSession(false);
        boolean _isMbr = _s != null && "Member".equals(_s.getAttribute("role")) && _s.getAttribute("memberId") != null;
        if (_isMbr) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.membership");
            if (deny != null) return ResponseEntity.status(403).body(Map.of("error","Access denied"));
        } else {
            String deny = RoleGuard.requireAdminOrChurch(request); if (deny!=null) return ResponseEntity.status(403).body(Map.of("error","Access denied"));
        }
        String appClientId = _isMbr ? resolveAppClientId(_s) : SessionUtil.getAppClientId(request);
        MembershipFamily mf = mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId).orElse(null);
        if (mf==null) return ResponseEntity.notFound().build();
        List<MembershipFamilyMember> mfMembers = mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(id);

        // Subscription plan: approving turns a public request into real people, so
        // it answers to the same maximum-people limit the admin Add Family screen
        // does. Checked at approval rather than at submission: a visitor filling in
        // the public form must not be told about the church's plan, and a request
        // that arrives while the church is at its limit is still worth keeping.
        try {
            if (subscriptionService != null) {
                long current = familyMemberRepo.countActiveMembers(appClientId);
                String limitMsg = subscriptionService.checkPeopleLimit(appClientId, current, mfMembers.size());
                if (limitMsg != null) return ResponseEntity.status(403).body(Map.of("error", limitMsg));
            }
        } catch (Exception ignored) { /* fail-open: never block an approval on a limit-check error */ }

        Family targetFamily; boolean isExisting = mf.getExistingFamilyId()!=null;
        if (isExisting) {
            targetFamily = familyRepo.findById(mf.getExistingFamilyId()).orElse(null);
            if (targetFamily==null) return bad("Existing family not found.");
            // Defence in depth: never merge a request into a family belonging to a
            // different tenant, even if a stale row predates the check in submit().
            if (mf.getAppClientId() == null
                    || !mf.getAppClientId().equals(targetFamily.getAppClientId())) {
                log.warn("Membership approve: refusing cross-tenant merge of request {} (request client {}, family {} client {})",
                         id, mf.getAppClientId(), targetFamily.getId(), targetFamily.getAppClientId());
                return bad("This request refers to a family that does not belong to your church.");
            }
            familyRepo.save(targetFamily);
            List<FamilyMember> existing = familyMemberRepo.findActiveMembersByFamilyId(targetFamily.getId());
            // Track which existing members have already been matched to avoid double-matching
            java.util.Set<Integer> matched = new java.util.HashSet<>();
            for (MembershipFamilyMember mfm : mfMembers) {
                // 1) Match by first+last name (case-insensitive) — most reliable
                FamilyMember fm = existing.stream()
                    .filter(e -> !matched.contains(e.getId()))
                    .filter(e -> namesMatch(e.getFirstName(), mfm.getFirstName())
                              && namesMatch(e.getLastName(),  mfm.getLastName()))
                    .findFirst().orElse(null);
                // 2) Fall back: match by normalised role (handles "Head of Household" == "Head")
                if (fm == null) {
                    fm = existing.stream()
                        .filter(e -> !matched.contains(e.getId()))
                        .filter(e -> rolesMatch(e.getRole(), mfm.getRole()))
                        .findFirst().orElse(null);
                }
                if (fm == null) { fm = new FamilyMember(); fm.setFamily(targetFamily); fm.setAppClientId(mfm.getAppClientId()); }
                else            { matched.add(fm.getId()); }
                applyMemberFields(fm, mfm); familyMemberRepo.save(fm); mfm.setDeleteFlag(true); mfmRepo.save(mfm);
            }
        } else {
            targetFamily=new Family(); targetFamily.setInactive(false); targetFamily.setAppClientId(mf.getAppClientId()); targetFamily=familyRepo.save(targetFamily);
            for (MembershipFamilyMember mfm : mfMembers) {
                FamilyMember fm=new FamilyMember(); fm.setFamily(targetFamily); fm.setAppClientId(mfm.getAppClientId());
                applyMemberFields(fm,mfm); FamilyMember savedFm=familyMemberRepo.save(fm);
                if (mf.getSignupId()!=null&&("Head".equalsIgnoreCase(mfm.getRole())||"Head of Household".equalsIgnoreCase(mfm.getRole()))) {
                    SignUp su=loginRepository.findById(mf.getSignupId()).orElse(null);
                    if (su!=null){su.setActive(true);loginRepository.save(su);if(su.getClientId()!=null&&su.getClientId().startsWith("MBR")){savedFm.setMemberRef(su.getClientId());familyMemberRepo.save(savedFm);}}
                }
                mfm.setDeleteFlag(true); mfmRepo.save(mfm);
            }
        }
        mf.setDeleteFlag(true); mfRepo.save(mf);
        Map<String,Object> r=new LinkedHashMap<>(); r.put("success",true); r.put("familyId",targetFamily.getId()); r.put("isExisting",isExisting);
        return ResponseEntity.ok(r);
    }

    // -- Private helpers ---------------------------------------------------

    private void applyMemberFields(FamilyMember fm, MembershipFamilyMember mfm) {
        fm.setRole(mfm.getRole()); fm.setGender(mfm.getGender()); fm.setFirstName(mfm.getFirstName()); fm.setMiddleName(mfm.getMiddleName());
        fm.setLastName(mfm.getLastName()); fm.setPhone(mfm.getPhone()); fm.setEmail(mfm.getEmail()); fm.setMemberType("Member");
        fm.setBirthdayMonth(mfm.getBirthdayMonth()); fm.setBirthdayDay(mfm.getBirthdayDay()); fm.setBirthdayYear(mfm.getBirthdayYear());
        fm.setAnniversaryMonth(mfm.getAnniversaryMonth()); fm.setAnniversaryDay(mfm.getAnniversaryDay()); fm.setAnniversaryYear(mfm.getAnniversaryYear());
        fm.setAddress1(mfm.getAddress1()); fm.setAddress2(mfm.getAddress2()); fm.setCity(mfm.getCity()); fm.setState(mfm.getState()); fm.setCountry(mfm.getCountry()); fm.setPinCode(mfm.getPinCode());
        fm.setSameAsFamilyAddress(mfm.isSameAsFamilyAddress()); fm.setOtherName(mfm.getOtherName()); fm.setComments(mfm.getComments());
        fm.setPhonePrivate(Boolean.TRUE.equals(mfm.getPhonePrivate()));
        fm.setEmailPrivate(Boolean.TRUE.equals(mfm.getEmailPrivate()));
        fm.setAddressPrivate(Boolean.TRUE.equals(mfm.getAddressPrivate()));
        fm.setInactive(false); fm.setDisableAlerts(false); fm.setIncludeContributions("Head".equalsIgnoreCase(mfm.getRole()));
        // Copy photo only when the form submission included one
        if (mfm.getPhotoData() != null && !mfm.getPhotoData().isBlank()) {
            fm.setPhotoData(mfm.getPhotoData());
        }
    }

    /** Case-insensitive null-safe name comparison. */
    private boolean namesMatch(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * Treats "Head" and "Head of Household" as the same role.
     * All other roles must match exactly (case-insensitive).
     */
    private boolean rolesMatch(String a, String b) {
        if (a == null || b == null) return false;
        String na = normaliseRole(a);
        String nb = normaliseRole(b);
        return na.equalsIgnoreCase(nb);
    }

    private String normaliseRole(String role) {
        if (role == null) return "";
        String r = role.trim().toLowerCase();
        return (r.equals("head of household") || r.equals("head")) ? "head" : r;
    }

    private PublicScreenLink resolveLink(String cid) {
        // Only a token minted for the Membership Form page may unlock these endpoints;
        // a Member Signup / SMS Opt-In / Donation token for the same church must not.
        return linkRepo.findByToken(cid)
                .filter(l->PublicScreensController.MEMBERSHIP_FORM_URL.equals(l.getPageUrl()))
                .filter(l->!l.isRevoked())
                .filter(l->l.getExpirationDate()==null||!l.getExpirationDate().isBefore(LocalDate.now()))
                .orElse(null);
    }

    private Map<String,Object> toFamilyMap(MembershipFamily f, boolean includeMembers) {
        List<MembershipFamilyMember> allM = mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(f.getId());
        MembershipFamilyMember head = allM.stream().filter(m->"Head".equalsIgnoreCase(m.getRole())).findFirst().orElse(allM.isEmpty()?null:allM.get(0));
        String familyName = head!=null&&head.getLastName()!=null&&!head.getLastName().isBlank() ? head.getLastName()+" Family" : (head!=null&&head.getFirstName()!=null?head.getFirstName()+"'s Family":"—");
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",f.getId()); m.put("familyName",familyName); m.put("familyCity",head!=null&&head.getCity()!=null?head.getCity():"");
        // The family's contact details are the Head's (the public form has one address/phone/email for the family).
        if (head != null) {
            m.put("familyAddress1", head.getAddress1()); m.put("familyAddress2", head.getAddress2());
            m.put("familyState", head.getState()); m.put("familyCountry", head.getCountry()); m.put("familyPinCode", head.getPinCode());
            m.put("familyPhone", head.getPhone()); m.put("familyEmail", head.getEmail());
        }
        m.put("declarationAccepted",f.getDeclarationAccepted()); m.put("existingFamilyId",f.getExistingFamilyId()); m.put("hasExistingFamily",f.getExistingFamilyId()!=null);
        m.put("signupId",f.getSignupId()); m.put("createdDate",f.getCreatedDate()!=null?f.getCreatedDate().toString():null);
        if (f.getSignupId()!=null){SignUp su=loginRepository.findById(f.getSignupId()).orElse(null);if(su!=null){m.put("signupUsername",su.getUsername());m.put("signupActive",Boolean.TRUE.equals(su.getActive()));}}
        if (includeMembers) {
            List<Map<String,Object>> mm=new ArrayList<>();
            for (MembershipFamilyMember mfm : allM) {
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("id",mfm.getId()); row.put("role",mfm.getRole()); row.put("gender",mfm.getGender()); row.put("firstName",mfm.getFirstName()); row.put("middleName",mfm.getMiddleName()); row.put("lastName",mfm.getLastName());
                row.put("phone",mfm.getPhone()); row.put("email",mfm.getEmail()); row.put("memberType",mfm.getMemberType());
                row.put("birthdayMonth",mfm.getBirthdayMonth()); row.put("birthdayDay",mfm.getBirthdayDay()); row.put("birthdayYear",mfm.getBirthdayYear());
                row.put("anniversaryMonth",mfm.getAnniversaryMonth()); row.put("anniversaryDay",mfm.getAnniversaryDay()); row.put("anniversaryYear",mfm.getAnniversaryYear());
                row.put("address1",mfm.getAddress1()); row.put("address2",mfm.getAddress2()); row.put("city",mfm.getCity()); row.put("state",mfm.getState()); row.put("country",mfm.getCountry()); row.put("pinCode",mfm.getPinCode());
                row.put("sameAsFamilyAddress",mfm.isSameAsFamilyAddress()); row.put("otherName",mfm.getOtherName()); row.put("comments",mfm.getComments());
                row.put("phonePrivate",Boolean.TRUE.equals(mfm.getPhonePrivate())); row.put("emailPrivate",Boolean.TRUE.equals(mfm.getEmailPrivate())); row.put("addressPrivate",Boolean.TRUE.equals(mfm.getAddressPrivate()));
                boolean hasPhoto = mfm.getPhotoData()!=null && !mfm.getPhotoData().isBlank();
                row.put("hasPhoto",hasPhoto); if (hasPhoto) row.put("photoData",mfm.getPhotoData());   // review screen only (admin, own tenant)
                mm.add(row);
            }
            m.put("members",mm);
        }
        return m;
    }

    private Map<String,Object> memberToMap(FamilyMember fm) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id",            fm.getId());
        m.put("role",          fm.getRole());
        m.put("gender",        fm.getGender());
        m.put("firstName",     fm.getFirstName());
        m.put("middleName",    fm.getMiddleName());
        m.put("lastName",      fm.getLastName());
        m.put("phone",         fm.getPhone());
        m.put("email",         fm.getEmail());
        m.put("memberType",    fm.getMemberType());
        m.put("birthdayMonth", fm.getBirthdayMonth());
        m.put("birthdayDay",   fm.getBirthdayDay());
        m.put("birthdayYear",  fm.getBirthdayYear());
        m.put("anniversaryMonth", fm.getAnniversaryMonth());
        m.put("anniversaryDay",   fm.getAnniversaryDay());
        m.put("anniversaryYear",  fm.getAnniversaryYear());
        m.put("address1",      fm.getAddress1());
        m.put("address2",      fm.getAddress2());
        m.put("city",          fm.getCity());
        m.put("state",         fm.getState());
        m.put("country",       fm.getCountry());
        m.put("pinCode",       fm.getPinCode());
        m.put("sameAsFamilyAddress", fm.isSameAsFamilyAddress());
        m.put("otherName",     fm.getOtherName());
        m.put("nickname",      fm.getOtherName());
        m.put("displayName",   com.churchgeniuspro.util.MemberNameUtil.display(fm.getFirstName(), fm.getLastName(), fm.getOtherName()));
        m.put("comments",      fm.getComments());
        m.put("inactive",      fm.isInactive());
        m.put("disableAlerts", fm.isDisableAlerts());
        m.put("includeContributions", fm.isIncludeContributions());
        m.put("photoData",     fm.getPhotoData());
        return m;
    }

    // -- Helper: 400 bad-request response -------------------------------------

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", msg);
        return ResponseEntity.badRequest().body(r);
    }

    // -- Helper: mask email for display (e.g. j***@example.com) ---------------

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 1) return local + "***" + domain;
        return local.charAt(0) + "***" + domain;
    }

    // -- Helper: safe string extraction from a Map<String,Object> -------------

    /** Safely converts a native-query date column (LocalDate, java.sql.Date, java.util.Date) to ISO string. */
    private static String taxDateStr(Object obj) {
        if (obj == null) return "";
        if (obj instanceof java.time.LocalDate ld) return ld.toString();
        if (obj instanceof java.sql.Date sd)       return sd.toLocalDate().toString();
        if (obj instanceof java.util.Date ud)      return new java.sql.Date(ud.getTime()).toLocalDate().toString();
        return obj.toString();
    }

    /** The page refuses files over 2 MB; a data-URL of one is under this. */
    public static final int MAX_PHOTO_CHARS = 3_000_000;

    /**
     * A member photo must be an image data-URL of at most {@link #MAX_PHOTO_CHARS}
     * characters. Returns the message to show, or {@code null} when every photo is fine.
     */
    static String checkPhotos(Object members) {
        if (!(members instanceof List<?> list)) return null;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object photo = m.get("photo");
            if (photo == null) continue;
            String p = photo.toString();
            if (p.isBlank()) continue;
            if (!p.startsWith("data:image/") || p.length() > MAX_PHOTO_CHARS) {
                return "Photo must be an image under 2 MB.";
            }
        }
        return null;
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // -- Helper: safe Integer extraction from a Map<String,Object> ----------

    private Integer intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return null; }
    }
    // -- Member API: list members who have an account (for messaging) ----------

    /**
     * Returns the list of members in this org who have an active login account.
     * The logged-in member themselves is excluded so you can't message yourself.
     */
    @ResponseBody
    @GetMapping("/api/member/accounts")
    public ResponseEntity<?> getMemberAccounts(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, java.util.Collections.emptyList());
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "No org"));

        Object selfIdObj = session.getAttribute("memberId");
        Integer selfId = selfIdObj instanceof Number n ? n.intValue() : null;

        // All active MBR* signups
        java.util.Set<String> accountRefs = loginRepository.findAll().stream()
                .filter(s -> Boolean.FALSE.equals(s.getDeleted())
                          && Boolean.TRUE.equals(s.getActive())
                          && s.getClientId() != null
                          && s.getClientId().startsWith("MBR"))
                .map(SignUp::getClientId)
                .collect(java.util.stream.Collectors.toSet());

        List<FamilyMember> all = familyMemberRepo.findAllWithFamilyByAppUser(appClientId);
        List<Map<String, Object>> result = all.stream()
                .filter(m -> m.getMemberRef() != null && accountRefs.contains(m.getMemberRef()))
                .filter(m -> !m.getId().equals(selfId))
                .map(m -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("id",        m.getId());
                    mm.put("firstName", m.getFirstName());
                    mm.put("lastName",  m.getLastName());
                    mm.put("nickname",  m.getOtherName());
                    mm.put("displayName", com.churchgeniuspro.util.MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName()));
                    mm.put("role",      m.getRole());
                    String familyName = m.getFamily() != null
                        ? (m.getLastName() != null ? m.getLastName() : m.getFirstName()) + " Family"
                        : "";
                    mm.put("familyName", familyName);
                    return mm;
                })
                .sorted(java.util.Comparator.comparing(
                    mm -> ((String) mm.get("lastName")) != null ? (String) mm.get("lastName") : ""))
                .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // -- Member API: groups this member belongs to ----------------------------

    /**
     * Returns the groups that the logged-in member belongs to, along with all
     * other members in each group, annotating which ones have active member
     * accounts (for in-app messaging) and which have email addresses (for email).
     *
     * <p>Matching is done by email: if a GroupMember's email matches a FamilyMember
     * who has an active MBR* login, that group member has an account.
     *
     * <p>Response shape:
     * <pre>
     * [
     *   {
     *     "id": 3,
     *     "groupName": "Youth Group",
     *     "members": [
     *       { "id": 12, "firstName": "Jane", "lastName": "Doe",
     *         "email": "jane@example.com", "hasAccount": true,
     *         "memberId": 7,   // FamilyMember.id — present when hasAccount=true
     *         "hasEmail": true }
     *     ]
     *   }
     * ]
     * </pre>
     */
    @ResponseBody
    @GetMapping("/api/member/groups")
    public ResponseEntity<?> getMemberGroups(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, java.util.Collections.emptyList());
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "No org"));

        Integer selfId = memberIdFromSession(session);
        FamilyMember self = selfId != null ? familyMemberRepo.findById(selfId).orElse(null) : null;
        String selfEmail = self != null && self.getEmail() != null ? self.getEmail().trim().toLowerCase() : null;

        // Build lookup: email (lowercase) → FamilyMember (only those with active accounts)
        java.util.Set<String> accountRefs = loginRepository.findAll().stream()
                .filter(s -> Boolean.FALSE.equals(s.getDeleted())
                          && Boolean.TRUE.equals(s.getActive())
                          && s.getClientId() != null
                          && s.getClientId().startsWith("MBR"))
                .map(SignUp::getClientId)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, FamilyMember> emailToMember = familyMemberRepo.findAllWithFamilyByAppUser(appClientId)
                .stream()
                .filter(m -> m.getMemberRef() != null && accountRefs.contains(m.getMemberRef()))
                .filter(m -> m.getEmail() != null && !m.getEmail().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        m -> m.getEmail().trim().toLowerCase(),
                        m -> m,
                        (a, b) -> a));   // keep first on duplicate email

        // Normalized full name of the logged-in member — used as the primary match key
        // because GroupMember has no FK to family_member and email can be shared across
        // family members (e.g. a child account using the parent's email address).
        String selfName = self != null
                ? ((self.getFirstName() != null ? self.getFirstName().trim() : "") + " "
                   + (self.getLastName()  != null ? self.getLastName().trim()  : "")).trim().toLowerCase()
                : null;

        // Build a name→FamilyMember lookup for all members with accounts (for hasAccount check).
        // Keep email lookup as a secondary reference for resolving FamilyMember records.
        Map<String, FamilyMember> nameToMember = familyMemberRepo.findAllWithFamilyByAppUser(appClientId)
                .stream()
                .filter(m -> m.getMemberRef() != null && accountRefs.contains(m.getMemberRef()))
                .collect(java.util.stream.Collectors.toMap(
                        m -> ((m.getFirstName() != null ? m.getFirstName().trim() : "") + " "
                              + (m.getLastName()  != null ? m.getLastName().trim()  : "")).trim().toLowerCase(),
                        m -> m,
                        (a, b) -> a));   // keep first on duplicate name

        // Find all groups for this org, then filter to those containing the logged-in member
        List<Group> allGroups = groupRepo.findActiveByAppUser(appClientId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Group g : allGroups) {
            List<GroupMember> gms = groupMemberRepo.findByGroupActiveByAppUser(g.getId(), appClientId);
            // Is the logged-in member in this group?
            // Match by name (primary) — avoids false positives when family members share an email.
            boolean selfInGroup = selfName != null && gms.stream().anyMatch(gm -> {
                String gmName = ((gm.getFirstName() != null ? gm.getFirstName().trim() : "") + " "
                                 + (gm.getLastName() != null ? gm.getLastName().trim() : "")).trim().toLowerCase();
                return selfName.equals(gmName);
            });
            if (!selfInGroup) continue;

            List<Map<String, Object>> memberList = new ArrayList<>();
            for (GroupMember gm : gms) {
                String gmName = ((gm.getFirstName() != null ? gm.getFirstName().trim() : "") + " "
                                 + (gm.getLastName() != null ? gm.getLastName().trim() : "")).trim().toLowerCase();
                // isSelf: name match against the logged-in member
                boolean isSelf = selfName != null && selfName.equals(gmName);
                // Resolve to a FamilyMember record — try name first, fall back to email
                String gmEmail = gm.getEmail() != null ? gm.getEmail().trim().toLowerCase() : null;
                FamilyMember fm = nameToMember.get(gmName);
                if (fm == null && gmEmail != null) fm = emailToMember.get(gmEmail);
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id",        gm.getId());
                mm.put("firstName", gm.getFirstName());
                mm.put("lastName",  gm.getLastName());
                String gmNick = fm != null ? fm.getOtherName() : null;
                mm.put("nickname",    gmNick);
                mm.put("displayName", com.churchgeniuspro.util.MemberNameUtil.display(gm.getFirstName(), gm.getLastName(), gmNick));
                mm.put("email",     gm.getEmail());
                mm.put("phone",     fm != null ? fm.getPhone() : null);
                mm.put("hasEmail",  gm.getEmail() != null && !gm.getEmail().isBlank());
                mm.put("hasAccount", fm != null && !isSelf);
                mm.put("memberId",  fm != null ? fm.getId() : null);
                mm.put("isSelf",    isSelf);
                memberList.add(mm);
            }
            Map<String, Object> groupMap = new LinkedHashMap<>();
            groupMap.put("id",        g.getId());
            groupMap.put("groupName", g.getGroupName());
            groupMap.put("members",   memberList);
            result.add(groupMap);
        }

        return ResponseEntity.ok(result);
    }

    // -- Member API: send a new message ----------------------------------------

    @ResponseBody
    @PostMapping("/api/member/messages")
    public ResponseEntity<?> sendMessage(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("success", false, "error", "Not a member account"));
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        FamilyMember self = familyMemberRepo.findById(selfId).orElse(null);
        if (self == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        Object recipObj = body.get("recipientId");
        Integer recipId = recipObj instanceof Number n ? n.intValue()
                        : (recipObj != null ? Integer.parseInt(recipObj.toString()) : null);
        if (recipId == null) return ResponseEntity.badRequest().body(Map.of("error", "Recipient required."));
        if (recipId.equals(selfId)) return ResponseEntity.badRequest().body(Map.of("error", "Cannot message yourself."));
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        // The recipient id comes straight from the body: it must be a member of the same church.
        if (familyMemberRepo.findByIdAndTenant(recipId, appClientId).isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "Recipient not found."));

        String msgBody = str(body, "body");
        if (msgBody == null || msgBody.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Message body required."));

        String senderName = trim2(self.getFirstName()) + " " + trim2(self.getLastName());

        MemberMessage msg = new MemberMessage();
        msg.setSenderMemberId(selfId);
        msg.setSenderName(senderName.trim());
        msg.setRecipientMemberId(recipId);
        msg.setBody(msgBody.trim());
        msg.setAppClientId(appClientId);
        memberMessageRepo.save(msg);

        return ResponseEntity.ok(Map.of("success", true, "id", msg.getId()));
    }

    // -- Member API: inbox -----------------------------------------------------

    @ResponseBody
    @GetMapping("/api/member/messages/inbox")
    public ResponseEntity<?> getInbox(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, java.util.Collections.emptyList());
        if (guard != null) return guard;
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        List<MemberMessage> msgs = memberMessageRepo.findInbox(selfId);
        return ResponseEntity.ok(msgs.stream().map(this::msgToMap)
                .collect(java.util.stream.Collectors.toList()));
    }

    // -- Member API: sent ------------------------------------------------------

    @ResponseBody
    @GetMapping("/api/member/messages/sent")
    public ResponseEntity<?> getSent(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, java.util.Collections.emptyList());
        if (guard != null) return guard;
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        List<MemberMessage> msgs = memberMessageRepo.findSent(selfId);
        return ResponseEntity.ok(msgs.stream().map(this::msgToMap)
                .collect(java.util.stream.Collectors.toList()));
    }

    // -- Member API: message thread --------------------------------------------

    @ResponseBody
    @GetMapping("/api/member/messages/thread/{rootId}")
    public ResponseEntity<?> getThread(@PathVariable Long rootId, HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, java.util.Collections.emptyList());
        if (guard != null) return guard;
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        // Mark root as read if we are the recipient
        MemberMessage root = memberMessageRepo.findById(rootId).orElse(null);
        if (root != null && root.getRecipientMemberId().equals(selfId) && root.getReadAt() == null) {
            root.setReadAt(java.time.Instant.now());
            memberMessageRepo.save(root);
        }

        List<MemberMessage> thread = memberMessageRepo.findThread(rootId, selfId);
        return ResponseEntity.ok(thread.stream().map(m -> {
            Map<String, Object> mm = msgToMap(m);
            mm.put("isMine", m.getSenderMemberId().equals(selfId));
            return mm;
        }).collect(java.util.stream.Collectors.toList()));
    }

    // -- Member API: reply to a message ----------------------------------------

    @ResponseBody
    @PostMapping("/api/member/messages/{rootId}/reply")
    public ResponseEntity<?> replyMessage(@PathVariable Long rootId,
                                          @RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("success", false, "error", "Not a member account"));
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        MemberMessage root = memberMessageRepo.findById(rootId).orElse(null);
        if (root == null) return ResponseEntity.notFound().build();
        // Only a participant of this thread, in this church, may reply into it.
        boolean participant = selfId.equals(root.getSenderMemberId()) || selfId.equals(root.getRecipientMemberId());
        if (!participant || appClientId == null || !appClientId.equals(root.getAppClientId()))
            return ResponseEntity.notFound().build();

        // Reply goes to the OTHER party in the thread
        Integer recipId = root.getSenderMemberId().equals(selfId)
                ? root.getRecipientMemberId()
                : root.getSenderMemberId();

        String msgBody = str(body, "body");
        if (msgBody == null || msgBody.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Message body required."));

        FamilyMember self = familyMemberRepo.findById(selfId).orElse(null);
        String senderName = self != null
                ? (trim2(self.getFirstName()) + " " + trim2(self.getLastName())).trim()
                : "Member";

        MemberMessage reply = new MemberMessage();
        reply.setSenderMemberId(selfId);
        reply.setSenderName(senderName);
        reply.setRecipientMemberId(recipId);
        reply.setBody(msgBody.trim());
        reply.setParentId(rootId);
        reply.setAppClientId(appClientId);
        memberMessageRepo.save(reply);

        return ResponseEntity.ok(Map.of("success", true, "id", reply.getId()));
    }

    // -- Member API: mark message as read --------------------------------------

    @ResponseBody
    @PatchMapping("/api/member/messages/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable Long id, HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("success", false));
        if (guard != null) return guard;
        Integer selfId = memberIdFromSession(session);

        MemberMessage msg = memberMessageRepo.findById(id).orElse(null);
        if (msg == null) return ResponseEntity.notFound().build();
        if (!msg.getRecipientMemberId().equals(selfId))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        if (msg.getReadAt() == null) {
            msg.setReadAt(java.time.Instant.now());
            memberMessageRepo.save(msg);
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    // -- Member API: unread count ----------------------------------------------

    @ResponseBody
    @GetMapping("/api/member/messages/unread-count")
    public ResponseEntity<?> unreadCount(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("count", 0));
        if (guard != null) return guard;
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.ok(Map.of("count", 0));
        long count = memberMessageRepo.countUnread(selfId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    // -- Member API: delete messages ------------------------------------------

    /**
     * Soft-deletes one or more messages for the calling member.
     * Body: {@code { "ids": [1, 2, 3], "tab": "inbox" | "sent" }}
     * For inbox messages sets {@code deleted_by_recipient = true};
     * for sent messages sets {@code deleted_by_sender = true}.
     */
    @ResponseBody
    @DeleteMapping("/api/member/messages")
    public ResponseEntity<?> deleteMessages(@RequestBody Map<String, Object> body,
                                            HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("deleted", 0));
        if (guard != null) return guard;
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        String tab = body.get("tab") instanceof String s ? s : "inbox";
        Object rawIds = body.get("ids");
        if (!(rawIds instanceof java.util.List<?> idList) || idList.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No ids provided"));

        int deleted = 0;
        for (Object raw : idList) {
            try {
                Long msgId = raw instanceof Number n ? n.longValue()
                           : Long.parseLong(String.valueOf(raw));
                memberMessageRepo.findById(msgId).ifPresent(msg -> {
                    if ("sent".equals(tab) && selfId.equals(msg.getSenderMemberId())) {
                        msg.setDeletedBySender(true);
                        memberMessageRepo.save(msg);
                    } else if ("inbox".equals(tab) && selfId.equals(msg.getRecipientMemberId())) {
                        msg.setDeletedByRecipient(true);
                        memberMessageRepo.save(msg);
                    }
                });
                deleted++;
            } catch (NumberFormatException ignored) {}
        }
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    // -- Member API: send email to selected members ----------------------------

    @ResponseBody
    @PostMapping("/api/member/send-email")
    public ResponseEntity<?> sendEmailToMembers(@RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        try (com.churchgeniuspro.util.EmailActionScope __scope = com.churchgeniuspro.util.EmailActionScope.begin("member-send-email")) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("success", false, "error", "Not a member account"));
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        FamilyMember self = familyMemberRepo.findById(selfId).orElse(null);
        String senderName = self != null
                ? (trim2(self.getFirstName()) + " " + trim2(self.getLastName())).trim()
                : "A church member";

        String subject = str(body, "subject");
        String msgBody = str(body, "body");
        if (subject == null || subject.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Subject is required."));
        if (msgBody == null || msgBody.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Message body is required."));

        String htmlBody = "<p><strong>From:</strong> " + escHtml(senderName) + "</p>"
                + "<hr style='border:none;border-top:1px solid #eee;margin:12px 0;'/>"
                + "<div style='font-size:14px;line-height:1.7;color:#333;'>"
                + msgBody.replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>")
                + "</div>";

        // The former "directEmail" raw-address path was removed: it let any member
        // account relay arbitrary mail to any address under the church's sender
        // identity. memberHome.html never sets it. Recipients are member ids only.
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        @SuppressWarnings("unchecked")
        List<Object> recipIds = body.get("recipientIds") instanceof List<?> l
                ? (List<Object>) l : List.of();
        if (recipIds.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Please select at least one recipient."));

        int sent = 0;
        for (Object idObj : recipIds) {
            Integer rid = idObj instanceof Number n ? n.intValue()
                        : Integer.parseInt(idObj.toString());
            FamilyMember recip = familyMemberRepo.findByIdAndTenant(rid, appClientId).orElse(null);
            if (recip == null || recip.getEmail() == null || recip.getEmail().isBlank()) continue;
            try {
                emailService.sendOrgEmail(recip.getEmail(),
                        subject + " (from " + senderName + ")",
                        htmlBody, appClientId);
                sent++;
            } catch (Exception ignored) { /* non-fatal */ }
        }

        // Phase B: on a Trial/Demo tenant with a verified test address one test copy
        // went there and the recipients were simulated; say so instead of "sent".
        EmailService.Delivery d = emailService.delivery(appClientId);
        if (d.test()) {
            return ResponseEntity.ok(Map.of("success", true, "sent", 0,
                    "testEmailsSent", __scope.testEmailsSent(), "simulated", __scope.simulated(), "testEmail", d.testEmail(),
                    "message", "Trial/Demo test: " + __scope.testEmailsSent() + " test email sent to " + d.testEmail()
                             + " — " + __scope.simulated() + " recipient(s) simulated, none emailed."));
        }
        if (d.blocked()) {
            return ResponseEntity.ok(Map.of("success", true, "sent", 0, "blocked", sent, "blockReason", d.reason()));
        }
        return ResponseEntity.ok(Map.of("success", true, "sent", sent));
            }
    }

    // -- Member API: send email to all group members with email ----------------

    @ResponseBody
    @PostMapping("/api/member/groups/{groupId}/email")
    public ResponseEntity<?> sendGroupEmail(@PathVariable Integer groupId,
                                            @RequestBody Map<String, Object> body,
                                            HttpServletRequest request) {
        try (com.churchgeniuspro.util.EmailActionScope __scope = com.churchgeniuspro.util.EmailActionScope.begin("member-group-email")) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("success", false, "error", "Not a member account"));
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        Integer selfId = memberIdFromSession(session);
        FamilyMember self = selfId != null ? familyMemberRepo.findById(selfId).orElse(null) : null;
        String selfEmail = self != null && self.getEmail() != null ? self.getEmail().trim().toLowerCase() : null;

        // Verify logged-in member is in the group
        Group group = groupRepo.findById(groupId).orElse(null);
        if (group == null || group.isDeleteFlag()) return ResponseEntity.status(404).body(Map.of("error", "Group not found"));

        List<GroupMember> gms = groupMemberRepo.findByGroupActiveByAppUser(groupId, appClientId);
        boolean selfInGroup = selfEmail != null && gms.stream()
                .anyMatch(gm -> selfEmail.equals(gm.getEmail() != null ? gm.getEmail().trim().toLowerCase() : null));
        if (!selfInGroup) return ResponseEntity.status(403).body(Map.of("error", "Not a member of this group"));

        String subject = str(body, "subject");
        String msgBody = str(body, "body");
        if (msgBody == null || msgBody.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Message body required."));

        String senderName = self != null ? (trim2(self.getFirstName()) + " " + trim2(self.getLastName())).trim() : "Member";

        // Build a name→email lookup from FamilyMember directory so we can resolve
        // group members whose GroupMember row has no email stored
        Map<String, String> nameToEmail = familyMemberRepo.findAllWithFamilyByAppUser(appClientId)
                .stream()
                .filter(fm -> fm.getEmail() != null && !fm.getEmail().isBlank()
                           && !fm.isDeleteFlag())
                .collect(java.util.stream.Collectors.toMap(
                        fm -> (trim2(fm.getFirstName()) + " " + trim2(fm.getLastName())).trim().toLowerCase(),
                        fm -> fm.getEmail().trim(),
                        (a, b) -> a));  // keep first on duplicate name

        String fullSubject = (subject != null && !subject.isBlank())
                ? subject : "Message from " + senderName + " — " + group.getGroupName();
        String htmlBody = "<p><b>From:</b> " + senderName + "</p><p>" + msgBody.replace("\n", "<br>") + "</p>";

        int sent = 0;
        int noEmail = 0;
        for (GroupMember gm : gms) {
            // Resolve email: prefer the stored GroupMember email, fall back to directory lookup by name
            String gmEmail = gm.getEmail() != null && !gm.getEmail().isBlank()
                    ? gm.getEmail().trim().toLowerCase()
                    : nameToEmail.get((trim2(gm.getFirstName()) + " " + trim2(gm.getLastName())).trim().toLowerCase());

            if (gmEmail == null) { noEmail++; continue; }
            if (gmEmail.equalsIgnoreCase(selfEmail != null ? selfEmail : "")) continue; // don't email yourself
            try {
                emailService.sendOrgEmail(gmEmail, fullSubject, htmlBody, appClientId);
                sent++;
            } catch (Exception e) {
                log.warn("Group email failed to {}: {}", gmEmail, e.getMessage());
            }
        }

        String message = sent == 1 ? "Email sent to 1 member."
                       : sent > 1  ? "Email sent to " + sent + " members."
                       : noEmail > 0 ? "No emails sent — group members have no email addresses on file."
                       : "No other members to email.";
        return ResponseEntity.ok(Map.of("success", true, "sent", sent, "message", message));
            }
    }

    // -- Member API: send SMS to a member ---------------------------------------

    @ResponseBody
    @PostMapping("/api/member/send-sms")
    public ResponseEntity<?> sendSmsToMember(@RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("success", false, "error", "Not a member account"));
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        FamilyMember self = familyMemberRepo.findById(selfId).orElse(null);
        String senderName = self != null
                ? (trim2(self.getFirstName()) + " " + trim2(self.getLastName())).trim()
                : "A church member";

        String phone = str(body, "phone");
        String msg   = str(body, "message");
        if (phone == null) return ResponseEntity.badRequest().body(Map.of("error", "Phone required."));
        if (msg   == null) return ResponseEntity.badRequest().body(Map.of("error", "Message required."));
        // The number must be a member of this church. A member could otherwise use the
        // church's Twilio/WhatsApp identity to message any phone in the world.
        String target = memberPhoneInTenant(phone, appClientId);
        if (target == null) return ResponseEntity.status(404).body(Map.of("error", "That number is not in your church directory."));

        try {
            whatsAppSenderService.sendSmsToPhone(target, senderName + ": " + msg, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send SMS: " + e.getMessage()));
        }
    }

    // -- Member API: send WhatsApp to a member ----------------------------------

    @ResponseBody
    @PostMapping("/api/member/send-whatsapp")
    public ResponseEntity<?> sendWhatsAppToMember(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, Map.of("success", false, "error", "Not a member account"));
        if (guard != null) return guard;
        String appClientId = resolveAppClientId(session);
        Integer selfId = memberIdFromSession(session);
        if (selfId == null) return ResponseEntity.status(401).body(Map.of("error", "Member not found"));

        FamilyMember self = familyMemberRepo.findById(selfId).orElse(null);
        String senderName = self != null
                ? (trim2(self.getFirstName()) + " " + trim2(self.getLastName())).trim()
                : "A church member";

        String phone = str(body, "phone");
        String msg   = str(body, "message");
        if (phone == null) return ResponseEntity.badRequest().body(Map.of("error", "Phone required."));
        if (msg   == null) return ResponseEntity.badRequest().body(Map.of("error", "Message required."));
        // The number must be a member of this church. A member could otherwise use the
        // church's Twilio/WhatsApp identity to message any phone in the world.
        String target = memberPhoneInTenant(phone, appClientId);
        if (target == null) return ResponseEntity.status(404).body(Map.of("error", "That number is not in your church directory."));

        try {
            whatsAppSenderService.sendWhatsAppToPhone(target, senderName + ": " + msg, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send WhatsApp: " + e.getMessage()));
        }
    }

    // -- Member: worship schedule ---------------------------------------------

    /**
     * Returns all worship assignments for groups that contain the logged-in member.
     * Matching is done by comparing the member's full name (case-insensitive) against
     * WorshipGroupMember.memberName entries for each instrument in each group.
     *
     * Response shape per group:
     * {
     *   groupId, groupName,
     *   myInstruments: ["Guitar", ...],
     *   assignments: [
     *     { assignmentDate, slots: [{ instrumentId, instrumentName, members:[{memberName, isMe}] }],
     *       songs: [{songTitle, isHeading}] }
     *   ]
     * }
     */
    @GetMapping("/api/member/worship-schedule")
    public ResponseEntity<?> getMemberWorshipSchedule(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        ResponseEntity<?> guard = guardMemberApi(session, java.util.Collections.emptyList());
        if (guard != null) return guard;

        String appClientId = resolveAppClientId(session);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "No org"));

        // Resolve the logged-in member's full name
        Integer selfId = memberIdFromSession(session);
        FamilyMember self = selfId != null ? familyMemberRepo.findById(selfId).orElse(null) : null;
        String selfName = self != null
                ? ((self.getFirstName() != null ? self.getFirstName().trim() : "") +
                   (self.getLastName()  != null ? " " + self.getLastName().trim() : "")).trim()
                : null;
        if (selfName == null || selfName.isEmpty())
            return ResponseEntity.ok(List.of());

        // Find all worship groups for this org
        List<WorshipGroup> groups = worshipGroupRepo.findByClientIdAndDeleteFlagFalse(appClientId);

        // Load all assignments for this org once
        List<WorshipAssignment> allAssignments =
                worshipAssignmentRepo.findByClientIdAndDeleteFlagFalseOrderByAssignmentDateAsc(appClientId);

        List<Map<String, Object>> result = new ArrayList<>();

        for (WorshipGroup group : groups) {
            // Get all instruments for this group (ordered by id for stable column order)
            List<WorshipInstrument> instruments =
                    worshipInstrumentRepo.findByGroupIdAndDeleteFlagFalse(group.getId());
            if (instruments.isEmpty()) continue;

            // instrument id -> name map
            Map<Long, String> instNameMap = new LinkedHashMap<>();
            for (WorshipInstrument inst : instruments) instNameMap.put(inst.getId(), inst.getInstrumentName());

            // Collect all member names that appear in any instrument of this group (roster)
            // member name -> list of instruments they belong to in this group
            Map<String, List<String>> rosterInstruments = new java.util.LinkedHashMap<>();
            for (WorshipInstrument inst : instruments) {
                List<WorshipGroupMember> gms =
                        worshipGroupMemberRepo.findByInstrumentIdAndDeleteFlagFalseOrderByRotationOrderAsc(inst.getId());
                for (WorshipGroupMember gm : gms) {
                    String name = gm.getMemberName() != null ? gm.getMemberName().trim() : "";
                    if (!name.isEmpty()) {
                        rosterInstruments.computeIfAbsent(name, k -> new ArrayList<>()).add(inst.getInstrumentName());
                    }
                }
            }

            // Is the logged-in member in this group at all?
            String matchedSelfName = rosterInstruments.keySet().stream()
                    .filter(n -> n.equalsIgnoreCase(selfName))
                    .findFirst().orElse(null);
            if (matchedSelfName == null) continue; // member not in this group

            // Get assignments for this group only, sorted by date
            List<WorshipAssignment> groupAssignments = allAssignments.stream()
                    .filter(a -> a.getGroupId().equals(group.getId()))
                    .toList();

            // Build pivot table rows: one row per assignment date
            // Each row: date -> { instrumentName -> [memberName, ...] }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (WorshipAssignment a : groupAssignments) {
                List<WorshipAssignmentMember> amList =
                        worshipAssignmentMemberRepo.findByAssignmentIdOrderBySortOrderAsc(a.getId());

                // Build instrument -> members map for this date
                Map<String, List<Map<String, Object>>> cellMap = new LinkedHashMap<>();
                for (WorshipInstrument inst : instruments) {
                    cellMap.put(inst.getInstrumentName(), new ArrayList<>());
                }
                for (WorshipAssignmentMember am : amList) {
                    String instName = instNameMap.get(am.getInstrumentId());
                    if (instName == null) continue;
                    String mName = am.getMemberName() != null ? am.getMemberName().trim() : "";
                    boolean isMe = mName.equalsIgnoreCase(selfName);
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("name",  mName);
                    cell.put("isMe",  isMe);
                    cellMap.computeIfAbsent(instName, k -> new ArrayList<>()).add(cell);
                }

                // Skip rows with no members assigned (empty assignment records)
                if (amList.isEmpty()) continue;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date",  a.getAssignmentDate() != null ? a.getAssignmentDate().toString() : "");
                row.put("cells", cellMap);
                // Does this row include the logged-in member?
                boolean iAmInRow = amList.stream().anyMatch(am ->
                        am.getMemberName() != null && am.getMemberName().trim().equalsIgnoreCase(selfName));
                row.put("iAmAssigned", iAmInRow);
                rows.add(row);
            }

            Map<String, Object> groupMap = new LinkedHashMap<>();
            groupMap.put("groupName",       group.getGroupName());
            groupMap.put("selfName",         matchedSelfName);
            groupMap.put("instruments",      new ArrayList<>(instNameMap.values()));
            groupMap.put("rows",             rows);
            result.add(groupMap);
        }

        return ResponseEntity.ok(result);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the display date for a meeting, advancing a recurring series to
     * the next occurrence on or after today.
     *
     * @param baseDate   the original meeting_date stored in the DB
     * @param occurrence "One Time", "Weekly", "Daily", or null
     * @param endDate    last date of the series (null = no end)
     * @param today      reference date (LocalDate.now())
     * @return ISO date string (yyyy-MM-dd), or null if the series has ended
     */
    private String nextOccurrenceDate(LocalDate baseDate, String occurrence,
                                      LocalDate endDate, LocalDate today) {
        if (baseDate == null) return null;
        if (occurrence == null || "One Time".equalsIgnoreCase(occurrence)
                || !baseDate.isBefore(today)) {
            // One-time meeting or already in the future — use as-is
            return baseDate.toString();
        }
        LocalDate candidate;
        if ("Weekly".equalsIgnoreCase(occurrence)) {
            long weeksNeeded = java.time.temporal.ChronoUnit.WEEKS.between(baseDate, today);
            candidate = baseDate.plusWeeks(weeksNeeded);
            if (candidate.isBefore(today)) candidate = candidate.plusWeeks(1);
        } else if ("Daily".equalsIgnoreCase(occurrence)) {
            candidate = today;
        } else {
            // Unknown occurrence type — fall back to base date
            return baseDate.toString();
        }
        // Exclude if the series has already ended
        if (endDate != null && candidate.isAfter(endDate)) return null;
        return candidate.toString();
    }

    /**
     * Returns true when the session belongs to an authenticated staff user
     * (SuperAdmin, Admin, Accountant, User, etc.) who is not a Member portal user.
     * Staff sessions carry a {@code username} attribute; member sessions carry
     * {@code memberId} instead.
     */
    private boolean isStaffSession(jakarta.servlet.http.HttpSession session) {
        if (session == null) return false;
        Object username = session.getAttribute("username");
        if (username == null) return false;
        // Church sessions are not staff in the member-portal context
        Object church = session.getAttribute("church");
        if (Boolean.TRUE.equals(church) || "true".equalsIgnoreCase(String.valueOf(church))) return false;
        return true;
    }

    /**
     * Guard for member-only API endpoints.
     * <ul>
     *   <li>Returns {@code null} when the caller is a Member portal user — proceed normally.</li>
     *   <li>Returns a 200 empty response when the caller is an authenticated staff user
     *       (so {@code memberHome.html} renders cleanly without 401 errors).</li>
     *   <li>Returns a 401 response when the session is missing or does not belong to
     *       any authenticated user.</li>
     * </ul>
     *
     * @param session  the current HTTP session (may be null)
     * @param emptyBody  the body to return in the 200-empty case (e.g. an empty list)
     */
    private ResponseEntity<?> guardMemberApi(jakarta.servlet.http.HttpSession session, Object emptyBody) {
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        // Allow Member portal users with a memberId
        boolean isMember = "Member".equals(session.getAttribute("role"))
                && session.getAttribute("memberId") != null;
        if (isMember) return null;
        // Allow staff users who have a linked member account (memberId set by getMemberFamily)
        if (isStaffSession(session)) {
            boolean hasLinkedMember = session.getAttribute("memberId") != null;
            if (hasLinkedMember) return null;  // real data — pass through
            return ResponseEntity.ok(emptyBody);  // no linked member — return empty 200
        }
        return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
    }

    /** Resolve the church's appClientId from the session. */
    private String resolveAppClientId(jakarta.servlet.http.HttpSession session) {
        Object v = session.getAttribute("appClientId");
        if (v instanceof String s && !s.isBlank()) return s;
        // Fallback: if clientId is a church login it IS the appClientId
        Object cid = session.getAttribute("clientId");
        if (cid instanceof String s && !s.startsWith("MBR") && !s.startsWith("USR")) return s;
        return null;
    }

    /** Resolve the logged-in member's FamilyMember.id from the session. */
    private Integer memberIdFromSession(jakarta.servlet.http.HttpSession session) {
        Object v = session.getAttribute("memberId");
        return v instanceof Number n ? n.intValue() : null;
    }

    /** Null-safe trim returning empty string for null. */
    private String trim2(String s) {
        return s != null ? s.trim() : "";
    }

    /** Escape HTML special characters for safe inline rendering. */
    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ── GET /api/member/my-permissions ───────────────────────────────────────

    /**
     * Returns the calling member's own {@code memberPrivileges} JSON fresh from
     * the database so that memberHome.html can enforce tab visibility without
     * waiting for a re-login.
     *
     * <p>Accessible only when the session carries a {@code memberId} attribute
     * (i.e. the user is logged in as a member portal account).
     */
    @GetMapping("/api/member/my-permissions")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getMyPermissions(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        Object memberIdObj = session.getAttribute("memberId");
        Integer memberId = memberIdObj instanceof Number n ? n.intValue() : null;
        // Staff sessions (no memberId) — return null permissions without error
        if (memberId == null) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("permissions", null);
            return ResponseEntity.ok(empty);
        }
        FamilyMember fm = familyMemberRepo.findById(memberId).orElse(null);
        String privJson = fm != null ? fm.getMemberPrivileges() : null;
        Map<String, Object> res = new HashMap<>();
        res.put("permissions", privJson);
        return ResponseEntity.ok(res);
    }

    // ── requireMemberPrivilege helper ────────────────────────────────────────

    /**
     * Guards a member-facing endpoint by reading the member's privilege map
     * fresh from the database.
     *
     * <p>Returns {@code null} when access is allowed (caller should proceed).
     * Returns a 401/403 {@link ResponseEntity} when access should be denied
     * (caller should return it immediately).
     *
     * <p>Logic:
     * <ol>
     *   <li>No session or no memberId → {@code null} (not a member session; let
     *       other auth handle it).</li>
     *   <li>No {@code memberPrivileges} stored → allow (member has default full
     *       access).</li>
     *   <li>Privilege map has no {@code member.*} keys → allow (staff-style map
     *       accidentally stored; ignore it).</li>
     *   <li>No {@code member.*} key is {@code true} → allow (degenerate / empty
     *       map; treat as full access).</li>
     *   <li>{@code privKey} is explicitly {@code false} → 403.</li>
     *   <li>Otherwise → allow.</li>
     * </ol>
     */
    ResponseEntity<?> requireMemberPrivilege(jakarta.servlet.http.HttpSession session, String privKey) {
        if (session == null) return null;
        Object memberIdObj = session.getAttribute("memberId");
        Integer memberId = memberIdObj instanceof Number n ? n.intValue() : null;
        if (memberId == null) return null;

        FamilyMember fm = familyMemberRepo.findById(memberId).orElse(null);
        if (fm == null) return null;

        String privJson = fm.getMemberPrivileges();
        if (privJson == null) return null;

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> privMap = mapper.readValue(privJson, Map.class);

            boolean isMemberPrivMap = privMap.keySet().stream()
                    .anyMatch(k -> k.startsWith("member."));
            if (!isMemberPrivMap) return null;

            boolean anyMemberKeyTrue = privMap.entrySet().stream()
                    .anyMatch(e -> e.getKey().startsWith("member.") && Boolean.TRUE.equals(e.getValue()));
            if (!anyMemberKeyTrue) return null;

            Object val = privMap.get(privKey);
            if (Boolean.FALSE.equals(val)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "Access denied by privileges."));
            }
        } catch (Exception ignored) { /* malformed JSON → allow */ }
        return null;
    }

    /** Convert a MemberMessage to a response map. */
    private Map<String, Object> msgToMap(com.churchgeniuspro.hibernate.MemberMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              msg.getId());
        m.put("senderMemberId",  msg.getSenderMemberId());
        m.put("senderName",      msg.getSenderName() != null ? msg.getSenderName() : "");
        m.put("recipientMemberId", msg.getRecipientMemberId());
        m.put("body",            msg.getBody() != null ? msg.getBody() : "");
        m.put("sentAt",          msg.getSentAt() != null ? msg.getSentAt().toString() : "");
        m.put("readAt",          msg.getReadAt() != null ? msg.getReadAt().toString() : null);
        m.put("parentId",        msg.getParentId());
        m.put("appClientId",     msg.getAppClientId());
        return m;
    }



    /**
     * Returns the E.164 form of {@code phone} when it belongs to an active member of
     * {@code appClientId}'s directory, else null. Compared in normalised form so the
     * directory's stored formatting does not matter.
     */
    private String memberPhoneInTenant(String phone, String appClientId) {
        String wanted = com.churchgeniuspro.util.PhoneNumbers.toE164(phone);
        if (wanted == null || appClientId == null) return null;
        boolean known = familyMemberRepo.findAllWithFamilyByAppUser(appClientId).stream()
                .map(m -> com.churchgeniuspro.util.PhoneNumbers.toE164(m.getPhone()))
                .anyMatch(wanted::equals);
        return known ? wanted : null;
    }
}
