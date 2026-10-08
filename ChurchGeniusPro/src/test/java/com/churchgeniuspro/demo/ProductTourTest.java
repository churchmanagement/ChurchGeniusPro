package com.churchgeniuspro.demo;

import com.churchgeniuspro.controller.DemoTrialAgreementController;
import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.repository.DemoClientSettingsRepository;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first-login product tour for a trial or demo staff account.
 *
 * <p>It rides on the machinery the trial agreement already uses: the state lives
 * on the {@code demo_role_access} row, so it is recorded once per LOGIN rather
 * than per session and signing in again does not replay it. That row carries its
 * tenant, which is why nothing here re-derives one — there is no path by which a
 * church could read or write another's tour state.
 *
 * <p>What the tests below pin is mostly about WHO sees it, because that is where
 * this kind of feature goes wrong: a paying church being shown a tour of the
 * product it bought, a child's portal login being walked through the accounting
 * setup, or the tour landing on top of the agreement popup that has to be
 * answered first.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial/Demo — product tour")
class ProductTourTest {

    private static final String TRIAL = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000456";
    private static final String DEMO  = TestDataService.DEMO_CLIENT_PREFIX  + "1757300000123";
    private static final String REAL  = "CHR-real-church";
    private static final int    SIGNUP = 42;

    @Mock DemoRoleAccessRepository     accessRepo;
    @Mock DemoClientSettingsRepository settingsRepo;
    @Mock JdbcTemplate                 jdbc;

    DemoAccessService             demoAccess;
    DemoTrialAgreementController  controller;
    DemoRoleAccess                window;

    @BeforeEach
    void setUp() {
        demoAccess = new DemoAccessService(accessRepo, settingsRepo, jdbc);
        controller = new DemoTrialAgreementController(demoAccess);

        window = new DemoRoleAccess();
        window.setId(1L);
        window.setSignupId(SIGNUP);
        window.setClientId(TRIAL);
        window.setUsername("pastor_1757");
        window.setRoleLabel("SuperAdmin");
        window.setEndDate(LocalDate.now().plusDays(25));
        window.setBlocked(false);
        window.setAgreementAcceptedAt(LocalDateTime.now());   // agreement already answered

        when(accessRepo.findBySignupId(SIGNUP)).thenReturn(Optional.of(window));
        when(accessRepo.findByUsername(anyString())).thenReturn(Optional.of(window));
        when(accessRepo.findById(1L)).thenReturn(Optional.of(window));   // Reset looks up by row id
        when(accessRepo.findByClientId(anyString())).thenReturn(java.util.List.of(window));
        when(accessRepo.save(any(DemoRoleAccess.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** A staff session for a tenant, shaped the way sign-in builds one. */
    private MockHttpServletRequest staff(String tenant, String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username",    "pastor_1757");
        s.setAttribute("role",        role);
        s.setAttribute("appClientId", tenant);
        s.setAttribute("clientId",    tenant);
        s.setAttribute(com.churchgeniuspro.webfilter.DemoTrialAgreementFilter.ATTR_SIGNUP_ID, SIGNUP);
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setSession(s);
        return r;
    }

    private MockHttpServletRequest portalMember() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role",        "Member");
        s.setAttribute("memberId",    7);
        s.setAttribute("appClientId", TRIAL);
        s.setAttribute(com.churchgeniuspro.webfilter.DemoTrialAgreementFilter.ATTR_SIGNUP_ID, SIGNUP);
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setSession(s);
        return r;
    }

    private boolean shows(MockHttpServletRequest req) {
        ResponseEntity<Map<String, Object>> res = controller.productTour(req);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return Boolean.TRUE.equals(res.getBody().get("show"));
    }

    /* ── who sees it ────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("who is shown the tour")
    class Audience {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = { "SuperAdmin", "Admin" })
        @DisplayName("a trial administrator, on their first sign-in")
        void trialStaffSeeIt(String role) {
            assertThat(shows(staff(TRIAL, role))).isTrue();
        }

        @Test
        @DisplayName("a demo account too — same first-run problem")
        void demoStaffSeeIt() {
            window.setClientId(DEMO);
            assertThat(shows(staff(DEMO, "SuperAdmin"))).isTrue();
        }

        @Test
        @DisplayName("a real church is never shown a tour of the product it bought")
        void realChurchNeverSeesIt() {
            // A paying tenant has no demo_role_access row at all, which is what
            // makes this true rather than a check that could be forgotten.
            when(accessRepo.findBySignupId(SIGNUP)).thenReturn(Optional.empty());
            when(accessRepo.findByUsername(anyString())).thenReturn(Optional.empty());
            assertThat(shows(staff(REAL, "SuperAdmin"))).isFalse();
        }

        @Test
        @DisplayName("a Member portal login is not walked through the admin screens")
        void memberPortalDoesNotSeeIt() {
            assertThat(shows(portalMember())).isFalse();
        }

        @Test
        @DisplayName("it waits for the agreement — never two popups at once")
        void waitsForTheAgreement() {
            window.setAgreementAcceptedAt(null);
            assertThat(shows(staff(TRIAL, "SuperAdmin"))).isFalse();
        }

        @Test
        @DisplayName("a blocked or expired login is not toured round an account it cannot use")
        void blockedOrExpiredDoesNotSeeIt() {
            window.setBlocked(true);
            assertThat(shows(staff(TRIAL, "SuperAdmin"))).isFalse();

            window.setBlocked(false);
            window.setEndDate(LocalDate.now().minusDays(1));
            assertThat(shows(staff(TRIAL, "SuperAdmin"))).isFalse();
        }

        @Test
        @DisplayName("an anonymous request is not a sign-in")
        void anonymousDoesNotSeeIt() {
            assertThat(shows(new MockHttpServletRequest())).isFalse();
        }
    }

    /* ── shown once ─────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("shown once")
    class Once {

        @Test
        @DisplayName("finishing it stops it coming back on the next sign-in")
        void finishingIsRemembered() {
            assertThat(shows(staff(TRIAL, "SuperAdmin"))).isTrue();

            controller.completeProductTour(staff(TRIAL, "SuperAdmin"));

            assertThat(window.isProductTourDone()).isTrue();
            assertThat(shows(staff(TRIAL, "SuperAdmin"))).isFalse();
        }

        @Test
        @DisplayName("skipping records the same thing — both mean 'not again'")
        void skippingIsRemembered() {
            // The script posts to the same endpoint for Skip, the ✕ and Finish.
            controller.completeProductTour(staff(TRIAL, "SuperAdmin"));
            assertThat(shows(staff(TRIAL, "SuperAdmin"))).isFalse();
        }

        @Test
        @DisplayName("the timestamp records when it was first seen, not when it was last closed")
        void firstCompletionWins() {
            controller.completeProductTour(staff(TRIAL, "SuperAdmin"));
            LocalDateTime first = window.getProductTourAt();

            controller.completeProductTour(staff(TRIAL, "SuperAdmin"));

            assertThat(window.getProductTourAt()).isEqualTo(first);
            verify(accessRepo, org.mockito.Mockito.times(1)).save(any(DemoRoleAccess.class));
        }

        @Test
        @DisplayName("completing for a non-demo session is harmless, not an error")
        void completingWithoutAWindowIsHarmless() {
            when(accessRepo.findBySignupId(SIGNUP)).thenReturn(Optional.empty());
            when(accessRepo.findByUsername(anyString())).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> res =
                    controller.completeProductTour(staff(REAL, "SuperAdmin"));

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody()).containsEntry("completed", true);
            verify(accessRepo, never()).save(any(DemoRoleAccess.class));
        }

        @Test
        @DisplayName("a Service Admin reset hands the login to a new person, who sees it again")
        void resetReplaysTheTour() {
            controller.completeProductTour(staff(TRIAL, "SuperAdmin"));
            assertThat(window.isProductTourDone()).isTrue();

            demoAccess.reissue(1L, "pastor_new");

            assertThat(window.isProductTourDone()).isFalse();
            assertThat(window.isAgreementAccepted()).isFalse();
        }
    }

    /* ── the tour itself ────────────────────────────────────────────────── */

    @Nested
    @DisplayName("what the tour covers")
    class Content {

        private String script() throws IOException {
            return Files.readString(Paths.get("src/main/resources/static/product-tour.js"));
        }

        @Test
        @DisplayName("every feature the tour was asked to introduce is in it")
        void coversEveryRequestedFeature() throws IOException {
            assertThat(script()).contains(
                    // Initial setup
                    "Account Settings", "Meeting Categories", "Email Settings", "Daily Verse",
                    // Additional features
                    "Favorites", "Switch Accounts", "Private Page Access", "NTag Landing Page",
                    "NTag &amp; Barcode Login".replace("&amp;", "&"), "Public Screens",
                    "Digital Song Book", "Recurring Transactions", "Scan Check",
                    "Automatic Worship Assignment",
                    // AI
                    "Search", "Meetings", "Income &amp; Expense".replace("&amp;", "&"),
                    "Type", "Voice", "Converse");
        }

        @Test
        @DisplayName("each feature says where to find it, so the tour can be followed")
        void everyFeatureSaysWhereItLives() throws IOException {
            String s = script();
            // The "where" lines name real destinations in this application.
            assertThat(s).contains("ACCOUNT SETTINGS in the top bar", "ADMIN SETTINGS in the top bar",
                                   "Admin → Private Page Access", "Admin → NTAG Login",
                                   "More → Public Screens", "My Profile → Song Book",
                                   "Accounting → Income or Expense",
                                   "General → Ministry → Worship Planning",
                                   "Email Settings → Daily Verse Setup");
        }

        @Test
        @DisplayName("it can be left at any point, forwards or backwards")
        void navigationIsComplete() throws IOException {
            String s = script();
            assertThat(s).contains("Skip this step", "Skip tour", "← Back", "Next →", "Finish");
            // Escape and the backdrop close it: this is an introduction, not a gate
            // — unlike the agreement popup, which deliberately traps focus.
            assertThat(s).contains("if (e.key === 'Escape'");
            assertThat(s).contains("Step ' + (step + 1) + ' of '");
        }

        @Test
        @DisplayName("closing it any way records it, so it cannot silently return")
        void everyExitRecords() throws IOException {
            String s = script();
            assertThat(s).contains("/api/demo/product-tour/complete");
            // finish() is what the ✕, Skip tour, Escape, the backdrop and Finish all call.
            assertThat(s).contains("function finish() {");
        }

        @Test
        @DisplayName("it shows how to install the app on Android and iPhone, before the last step")
        void includesInstallTheAppStep() throws IOException {
            String s = script();
            assertThat(s).contains("key: 'install'", "Install the app on your phone",
                    "Android (Chrome)", "churchgeniuspro.net", "Install app", "Add to Home screen",
                    "iPhone / iPad (Safari)", "Add to Home Screen",
                    "Member Home and Dashboard shortcuts", "not in the Play Store",
                    "not from a link inside", "Allow notifications",
                    "/helpCenter#install-app");
            // It is the step just before "That's the tour", so the tour still ends on the summary.
            assertThat(s.indexOf("key: 'install'")).isGreaterThan(s.indexOf("key: 'ai'"))
                                                   .isLessThan(s.indexOf("key: 'finish'"));
            // Step lists are rendered escaped, like every other piece of card text.
            assertThat(s).contains("'<li>' + esc(x) + '</li>'");
        }

        @Test
        @DisplayName("the Help Center carries the same install instructions, as a card and a search answer")
        void helpCenterHasInstallInstructions() throws IOException {
            String h = Files.readString(Paths.get("src/main/resources/static/helpCenter.html"));
            assertThat(h).contains("id=\"hcInstallApp\"", "data-cat=\"mobile-app\"",
                    "filterCat(this,'mobile-app')", "Install the App (Android &amp; iPhone)",
                    "Install app", "Add to Home Screen", "id: 'install-app'",
                    "data-keywords=\"install-app ");
        }

        @Test
        @DisplayName("it is loaded on every signed-in page, like the agreement popup")
        void shellLoadsIt() throws IOException {
            assertThat(Files.readString(Paths.get("src/main/resources/static/shell.js")))
                    .contains("/product-tour.js");
        }
    }
}
