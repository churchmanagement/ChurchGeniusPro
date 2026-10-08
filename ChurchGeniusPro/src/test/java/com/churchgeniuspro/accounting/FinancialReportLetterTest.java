package com.churchgeniuspro.accounting;

import com.churchgeniuspro.controller.AccountingReportController;
import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.FinancialReportLetter;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FinancialReportLetterRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.service.FinancialReportLetterService;
import com.churchgeniuspro.util.RichTextSanitizer;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The giving statement's wording, above and below the contribution table.
 *
 * <p>It was two literals inside two page scripts, so every church sent the same
 * letter. It is now one church's own text, stored per tenant, rendered into both
 * the staff Year-End Tax Report and each member's Financial Report.
 *
 * <p>Three things have to hold, and each has its own section below. The letter
 * must never come out blank — a church that has never edited it, one whose row
 * cannot be read, and a deployment without the service at all must all print the
 * original wording. One church's text must never reach another's letters. And
 * because this text is authored by staff and rendered as HTML into every
 * congregant's browser, what can be stored has to be narrower than what can be
 * typed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Financial Report — editable letter text")
class FinancialReportLetterTest {

    private static final String TENANT = "CHR-church-01";
    private static final String OTHER  = "CHR-church-02";

    /* ── what may be stored ─────────────────────────────────────────────── */

    @Nested
    @DisplayName("the sanitiser")
    class Sanitiser {

        @Test
        @DisplayName("keeps the formatting the toolbar produces")
        void keepsBasicFormatting() {
            String html = "<p>Thank you for your <b>faithful</b> <i>giving</i> and "
                        + "<u>generous</u> support.<br/>Blessings.</p>"
                        + "<ul><li>One</li><li>Two</li></ul>";
            assertThat(RichTextSanitizer.sanitize(html)).isEqualTo(html.replace("<br/>", "<br/>"));
        }

        @Test
        @DisplayName("keeps the letter's own classes, so the printed design survives an edit")
        void keepsLetterClasses() {
            String out = RichTextSanitizer.sanitize(
                    "<div class=\"ltr-disclaimer\">Notice</div><p class=\"ltr-closing\">Grateful</p>");
            assertThat(out).contains("class=\"ltr-disclaimer\"", "class=\"ltr-closing\"");
        }

        @Test
        @DisplayName("drops every other class, so page styles cannot be borrowed")
        void dropsForeignClasses() {
            String out = RichTextSanitizer.sanitize("<div class=\"sidebar ltr-closing topbar\">x</div>");
            assertThat(out).isEqualTo("<div class=\"ltr-closing\">x</div>");
        }

        @Test
        @DisplayName("a script is removed with its contents, not escaped into view")
        void dropsScriptAndItsContent() {
            String out = RichTextSanitizer.sanitize("<p>Hi</p><script>alert(document.cookie)</script><p>Bye</p>");
            assertThat(out).isEqualTo("<p>Hi</p><p>Bye</p>");
            assertThat(out).doesNotContain("alert", "cookie");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "<img src=x onerror=alert(1)>",
            "<a href=\"javascript:alert(1)\">click</a>",
            "<iframe src=\"https://evil.example\"></iframe>",
            "<object data=\"evil\"></object>",
            "<svg><animate onbegin=alert(1)></svg>",
            "<form action=\"https://evil.example\"><input name=pw></form>",
            "<style>body{display:none}</style>"
        })
        @DisplayName("nothing that executes or exfiltrates survives")
        void dropsActiveContent(String payload) {
            String out = RichTextSanitizer.sanitize("<p>before</p>" + payload + "<p>after</p>");
            assertThat(out).contains("before", "after");
            assertThat(out.toLowerCase())
                    .doesNotContain("<img", "<a ", "href", "<iframe", "<object", "<svg",
                                    "<form", "<input", "<style", "onerror", "onbegin", "javascript:");
        }

        @Test
        @DisplayName("an event handler on an allowed tag is dropped, the tag is kept")
        void dropsHandlersFromAllowedTags() {
            String out = RichTextSanitizer.sanitize("<p onclick=\"steal()\" onmouseover=\"x()\">Hello</p>");
            assertThat(out).isEqualTo("<p>Hello</p>");
        }

        @Test
        @DisplayName("colour and alignment survive; sizing and backgrounds do not")
        void filtersStyles() {
            String out = RichTextSanitizer.sanitize(
                    "<p style=\"text-align:center;color:#663311;font-size:99px;"
                  + "background:url(https://evil.example/p.png)\">Centred</p>");
            assertThat(out).contains("text-align:center", "color:#663311");
            assertThat(out).doesNotContain("font-size", "background", "url(");
        }

        @Test
        @DisplayName("a style value that hides an expression is refused whole")
        void refusesCraftedStyleValues() {
            String out = RichTextSanitizer.sanitize(
                    "<span style=\"color:expression(alert(1))\">x</span>");
            assertThat(out).isEqualTo("<span>x</span>");
        }

        @Test
        @DisplayName("text that looks like markup is escaped, not obeyed")
        void escapesStrayMarkup() {
            String out = RichTextSanitizer.sanitize("5 < 6 & 7 > 3 <notatag");
            assertThat(out).isEqualTo("5 &lt; 6 &amp; 7 &gt; 3 &lt;notatag");
        }

        @Test
        @DisplayName("existing entities are left alone rather than double-escaped")
        void keepsEntities() {
            assertThat(RichTextSanitizer.sanitize("<p>Faith &amp; Hope &#8212; always</p>"))
                    .isEqualTo("<p>Faith &amp; Hope &#8212; always</p>");
        }

        @Test
        @DisplayName("an unclosed tag cannot swallow the rest of the letter")
        void closesUnbalancedTags() {
            assertThat(RichTextSanitizer.sanitize("<div><b>bold")).isEqualTo("<div><b>bold</b></div>");
            assertThat(RichTextSanitizer.sanitize("<b>a</i>b</b>")).isEqualTo("<b>ab</b>");
        }

        @Test
        @DisplayName("null and blank are empty, never null")
        void nullSafe() {
            assertThat(RichTextSanitizer.sanitize(null)).isEmpty();
            assertThat(RichTextSanitizer.sanitize("   ")).isEmpty();
        }

        @Test
        @DisplayName("the built-in wording passes through its own sanitiser unchanged")
        void defaultsAreAlreadyClean() {
            assertThat(RichTextSanitizer.sanitize(FinancialReportLetterService.DEFAULT_INTRO_HTML))
                    .isEqualTo(FinancialReportLetterService.DEFAULT_INTRO_HTML);
            assertThat(RichTextSanitizer.sanitize(FinancialReportLetterService.DEFAULT_CLOSING_HTML))
                    .isEqualTo(FinancialReportLetterService.DEFAULT_CLOSING_HTML);
        }
    }

    /* ── what a church gets back ────────────────────────────────────────── */

    @Nested
    @DisplayName("the wording a church gets")
    class Wording {

        @Mock FinancialReportLetterRepository repo;
        FinancialReportLetterService service;

        @BeforeEach
        void setUp() {
            service = new FinancialReportLetterService(repo);
            when(repo.findFirstByAppClientIdAndDeleteFlagFalse(anyString())).thenReturn(Optional.empty());
        }

        private FinancialReportLetter row(String intro, String closing) {
            FinancialReportLetter r = new FinancialReportLetter();
            r.setAppClientId(TENANT);
            r.setIntroHtml(intro);
            r.setClosingHtml(closing);
            return r;
        }

        @Test
        @DisplayName("a church that has never edited it gets the original letter, not a blank one")
        void defaultsWhenNoRow() {
            Map<String, String> out = service.rendered(TENANT, "Grace Chapel", 2026);
            assertThat(out.get("introHtml"))
                    .contains("Thank you for your faithful giving", "Grace Chapel", "2026");
            assertThat(out.get("closingHtml"))
                    .contains("Important Tax Notice", "We are truly grateful");
        }

        @Test
        @DisplayName("the placeholders carry the church name and tax year")
        void substitutesPlaceholders() {
            when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT))
                    .thenReturn(Optional.of(row("<p>{ChurchName} — {Year}</p>", "<p>bye</p>")));
            assertThat(service.rendered(TENANT, "Grace Chapel", 2026).get("introHtml"))
                    .isEqualTo("<p>Grace Chapel — 2026</p>");
        }

        @Test
        @DisplayName("a church name with markup in it cannot break out of the letter")
        void escapesSubstitutedValues() {
            when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT))
                    .thenReturn(Optional.of(row("<p>{ChurchName}</p>", "")));
            assertThat(service.rendered(TENANT, "Faith & <script>alert(1)</script>", 2026).get("introHtml"))
                    .isEqualTo("<p>Faith &amp; &lt;script&gt;alert(1)&lt;/script&gt;</p>");
        }

        @Test
        @DisplayName("a section the church deliberately cleared stays cleared")
        void storedEmptyIsHonoured() {
            when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT))
                    .thenReturn(Optional.of(row("<p>ours</p>", "")));
            Map<String, String> out = service.rendered(TENANT, "Grace Chapel", 2026);
            assertThat(out.get("introHtml")).isEqualTo("<p>ours</p>");
            assertThat(out.get("closingHtml")).isEmpty();
        }

        @Test
        @DisplayName("a database problem prints the original letter rather than failing")
        void lookupFailureFallsBack() {
            org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                    .when(repo).findFirstByAppClientIdAndDeleteFlagFalse(TENANT);
            assertThat(service.rendered(TENANT, "Grace Chapel", 2026).get("introHtml"))
                    .contains("Thank you for your faithful giving", "Grace Chapel");
        }

        @Test
        @DisplayName("saving stores the sanitised form, never what was typed")
        void saveSanitises() {
            service.save(TENANT, "<p onclick=\"x()\">Hi<script>steal()</script></p>", "<p>Bye</p>", "pastor");

            org.mockito.ArgumentCaptor<FinancialReportLetter> saved =
                    org.mockito.ArgumentCaptor.forClass(FinancialReportLetter.class);
            verify(repo).save(saved.capture());
            assertThat(saved.getValue().getIntroHtml()).isEqualTo("<p>Hi</p>");
            assertThat(saved.getValue().getAppClientId()).isEqualTo(TENANT);
            assertThat(saved.getValue().getUpdatedBy()).isEqualTo("pastor");
        }

        @Test
        @DisplayName("an edit updates this church's row — it never creates a second one")
        void saveUpdatesInPlace() {
            FinancialReportLetter existing = row("<p>old</p>", "<p>old</p>");
            existing.setId(9);
            when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(existing));

            service.save(TENANT, "<p>new</p>", "<p>new</p>", "pastor");

            org.mockito.ArgumentCaptor<FinancialReportLetter> saved =
                    org.mockito.ArgumentCaptor.forClass(FinancialReportLetter.class);
            verify(repo).save(saved.capture());
            assertThat(saved.getValue().getId()).isEqualTo(9);
        }

        @Test
        @DisplayName("a save without a tenant is refused rather than left unowned")
        void saveNeedsATenant() {
            assertThatThrownBy(() -> service.save(null, "<p>x</p>", "", "pastor"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.save("  ", "<p>x</p>", "", "pastor"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("absurdly long text is refused")
        void saveRejectsOverlongText() {
            String huge = "<p>" + "x".repeat(RichTextSanitizer.MAX_LENGTH) + "</p>";
            assertThatThrownBy(() -> service.save(TENANT, huge, "", "pastor"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("restoring the default retires this church's row and nothing else")
        void resetSoftDeletesOwnRow() {
            FinancialReportLetter existing = row("<p>ours</p>", "<p>ours</p>");
            when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(existing));

            assertThat(service.resetToDefault(TENANT)).isTrue();
            assertThat(existing.isDeleteFlag()).isTrue();
            verify(repo).save(existing);
            verify(repo, never()).deleteAll();
        }

        @Test
        @DisplayName("restoring when there is nothing to restore changes nothing")
        void resetWithoutARowIsHarmless() {
            assertThat(service.resetToDefault(TENANT)).isFalse();
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("the editor is shown the text as authored, placeholders intact")
        void editingViewKeepsPlaceholders() {
            Map<String, Object> edit = service.forEditing(TENANT);
            assertThat(String.valueOf(edit.get("introHtml"))).contains("{ChurchName}", "{Year}");
            assertThat(edit).containsEntry("customised", false);
        }
    }

    /* ── one church's text stays one church's ───────────────────────────── */

    @Nested
    @DisplayName("tenant scoping")
    class Scoping {

        @Mock FinancialReportLetterRepository repo;
        FinancialReportLetterService service;

        @BeforeEach
        void setUp() { service = new FinancialReportLetterService(repo); }

        @Test
        @DisplayName("a church reads only its own row")
        void readsOwnRowOnly() {
            FinancialReportLetter mine = new FinancialReportLetter();
            mine.setAppClientId(TENANT);
            mine.setIntroHtml("<p>ours</p>");
            mine.setClosingHtml("");
            when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(mine));
            when(repo.findFirstByAppClientIdAndDeleteFlagFalse(OTHER)).thenReturn(Optional.empty());

            assertThat(service.rendered(TENANT, "Ours", 2026).get("introHtml")).isEqualTo("<p>ours</p>");
            // The other church is untouched by our edit: it still gets the default.
            assertThat(service.rendered(OTHER, "Theirs", 2026).get("introHtml"))
                    .contains("Thank you for your faithful giving", "Theirs");
        }

        @Test
        @DisplayName("a save is written to the caller's tenant, whatever the row said before")
        void saveStampsTheCallersTenant() {
            FinancialReportLetter strayRow = new FinancialReportLetter();
            strayRow.setAppClientId(OTHER);
            when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(strayRow));

            service.save(TENANT, "<p>x</p>", "", "pastor");

            org.mockito.ArgumentCaptor<FinancialReportLetter> saved =
                    org.mockito.ArgumentCaptor.forClass(FinancialReportLetter.class);
            verify(repo).save(saved.capture());
            assertThat(saved.getValue().getAppClientId()).isEqualTo(TENANT);
        }
    }

    /* ── the two screens that show the letter ───────────────────────────── */

    @Nested
    @DisplayName("the staff Year-End Tax Report")
    class StaffReport {

        @Mock IncomeRepository             incomeRepo;
        @Mock ExpenseRepository            expenseRepo;
        @Mock PurposeRepository            purposeRepo;
        @Mock ChurchRegistrationRepository churchRepo;
        @Mock FinancialReportLetterRepository letterRepo;

        AccountingReportController controller;

        @BeforeEach
        void setUp() {
            controller = new AccountingReportController(incomeRepo, expenseRepo, purposeRepo, churchRepo);
            controller.setLetterService(new FinancialReportLetterService(letterRepo));

            ChurchRegistration reg = new ChurchRegistration();
            reg.setChurchName("Grace Chapel");
            when(churchRepo.findByClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(reg));
            when(incomeRepo.reportAnnualIncomeByMemberFiltered(anyInt(), anyString(), any(), any()))
                    .thenReturn(List.of());
            when(incomeRepo.reportIncomeEntriesForTaxYear(anyInt(), anyString(), any(), any()))
                    .thenReturn(List.of());
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(anyString()))
                    .thenReturn(Optional.empty());
        }

        private MockHttpServletRequest staffRequest() {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("username",    "pastor@grace");
            session.setAttribute("role",        "Admin");
            session.setAttribute("appClientId", TENANT);
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/reports/tax");
            req.setSession(session);
            return req;
        }

        private MockHttpServletRequest memberRequest() {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("role",        "Member");
            session.setAttribute("memberId",    7);
            session.setAttribute("appClientId", TENANT);
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/reports/letter-content");
            req.setSession(session);
            return req;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> bodyOf(ResponseEntity<?> res) {
            return (Map<String, Object>) res.getBody();
        }

        @Test
        @DisplayName("the report carries the letter text, so the letters cannot render without it")
        void reportCarriesLetterText() {
            Map<String, Object> body = bodyOf(controller.taxReport(2026, null, null, staffRequest()));
            assertThat(String.valueOf(body.get("introHtml")))
                    .contains("Thank you for your faithful giving", "Grace Chapel", "2026");
            assertThat(String.valueOf(body.get("closingHtml"))).contains("Important Tax Notice");
        }

        @Test
        @DisplayName("without the service wired up the letter still reads correctly")
        void worksWithoutTheService() {
            controller.setLetterService(null);
            Map<String, Object> body = bodyOf(controller.taxReport(2026, null, null, staffRequest()));
            assertThat(String.valueOf(body.get("introHtml"))).contains("Grace Chapel", "2026");
            assertThat(String.valueOf(body.get("introHtml"))).doesNotContain("{ChurchName}", "{Year}");
        }

        @Test
        @DisplayName("staff who may generate the statements may word them")
        void staffCanReadAndWrite() {
            assertThat(controller.getLetterContent(staffRequest()).getStatusCode().value()).isEqualTo(200);

            Map<String, String> edit = new HashMap<>();
            edit.put("introHtml",   "<p>Our own thanks</p>");
            edit.put("closingHtml", "<p>Our own notice</p>");
            assertThat(controller.saveLetterContent(edit, staffRequest()).getStatusCode().value()).isEqualTo(200);
            verify(letterRepo).save(any(FinancialReportLetter.class));
        }

        @Test
        @DisplayName("a member cannot read or rewrite the church's letter")
        void memberIsRefused() {
            assertThat(controller.getLetterContent(memberRequest()).getStatusCode().value()).isEqualTo(403);
            assertThat(controller.saveLetterContent(Map.of("introHtml", "<p>x</p>"), memberRequest())
                    .getStatusCode().value()).isEqualTo(403);
            assertThat(controller.resetLetterContent(memberRequest()).getStatusCode().value()).isEqualTo(403);
            verify(letterRepo, never()).save(any(FinancialReportLetter.class));
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRefused() {
            MockHttpServletRequest anon = new MockHttpServletRequest("GET", "/api/reports/letter-content");
            assertThat(controller.getLetterContent(anon).getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("a saved edit is stored for the caller's church, not a client-supplied one")
        void saveIgnoresAnyClientSuppliedTenant() {
            Map<String, String> edit = new HashMap<>();
            edit.put("introHtml",   "<p>Ours</p>");
            edit.put("closingHtml", "");
            edit.put("clientId",    OTHER);          // ignored: the tenant comes from the session

            controller.saveLetterContent(edit, staffRequest());

            org.mockito.ArgumentCaptor<FinancialReportLetter> saved =
                    org.mockito.ArgumentCaptor.forClass(FinancialReportLetter.class);
            verify(letterRepo).save(saved.capture());
            assertThat(saved.getValue().getAppClientId()).isEqualTo(TENANT);
        }
    }

    /* ── and when the church itself is deleted ──────────────────────────── */

    @Nested
    @DisplayName("deleting a tenant")
    class TenantDeletion {

        // The tenant purge is now built by TenantPurgePlanner from the live catalogue
        // (see M7 / TenantPurgePlanner and its dedicated test + IT). Here we assert only
        // what this feature needs: the church's giving-statement wording is one of the
        // tenant-owned tables the purge removes, it goes before the tenant root, and the
        // purge never emits an unscoped statement. plannedTableOrder / plannedStatements
        // are pure (no database), so this stays a plain unit test.
        private final com.churchgeniuspro.service.TenantPurgePlanner planner =
                new com.churchgeniuspro.service.TenantPurgePlanner();

        private final java.util.Map<String, String> tenantCols = java.util.Map.of(
                "financial_report_letter", "app_client_id",
                "church_registration",     "client_id",
                "service_client",          "client_id");
        private final java.util.Map<String, java.util.List<String[]>> noFks = java.util.Map.of();
        private final java.util.Set<String> existing = tenantCols.keySet();

        @Test
        @DisplayName("takes the church's wording with it, before the tenant root — nothing is left orphaned")
        void wordingIsDeletedWithTheTenant() {
            java.util.List<String> order = planner.plannedTableOrder(tenantCols, noFks, existing);
            assertThat(order).contains("financial_report_letter");
            assertThat(order.indexOf("financial_report_letter"))
                    .as("the letter is deleted before the tenant root")
                    .isLessThan(order.indexOf("service_client"));
        }

        @Test
        @DisplayName("and cannot reach another church's, because every statement names one tenant")
        void theCascadeNamesOneTenantOnly() {
            java.util.List<String> stmts = planner.plannedStatements(tenantCols, noFks, existing);
            assertThat(stmts).isNotEmpty();
            assertThat(stmts).allSatisfy(s -> assertThat(s)
                    .as("every statement must be client-scoped: %s", s).contains("?"));
            assertThat(stmts).anySatisfy(s -> assertThat(s)
                    .isEqualTo("DELETE FROM financial_report_letter WHERE app_client_id = ?"));
        }
    }

    /* ── the button that opens the editor ───────────────────────────────── */

    @Nested
    @DisplayName("the Edit letter text button")
    class EditorButton {

        private String page() throws java.io.IOException {
            return java.nio.file.Files.readString(
                    java.nio.file.Paths.get("src/main/resources/static/tax-report.html"));
        }

        @Test
        @DisplayName("opens the editor")
        void opensTheEditor() throws Exception {
            assertThat(page()).contains("class=\"btn-letter-text\" onclick=\"toggleLetterText()\"");
        }

        /**
         * session.js takes over every {@code .btn-admin-settings} button on a page:
         * it calls {@code removeAttribute('onclick')} and rebuilds it as the Admin
         * Settings dropdown, and hides it from anyone who is not Admin or
         * SuperAdmin. Borrowing the class for its looks therefore silently costs
         * you the click handler — and, for this page, the Accountant, who is
         * allowed to word the letter. Scanned across every page, because the trap
         * catches whoever adds a header button next, not just this one.
         */
        @Test
        @DisplayName("no page gives an Admin Settings button its own onclick — session.js strips it")
        void noPageHangsItsOwnHandlerOnTheSettingsButton() throws Exception {
            java.util.List<String> offenders = new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> pages =
                         java.nio.file.Files.walk(
                                 java.nio.file.Paths.get("src/main/resources/static"))) {
                for (java.nio.file.Path p : pages.filter(f -> f.toString().endsWith(".html")).toList()) {
                    for (String tag : java.nio.file.Files.readString(p).split("<button")) {
                        String head = tag.substring(0, Math.min(tag.length(), 400));
                        if (head.contains("btn-admin-settings") && head.contains("onclick")
                                && head.indexOf('>') > head.indexOf("onclick")) {
                            offenders.add(p.getFileName().toString());
                        }
                    }
                }
            }
            assertThat(offenders)
                    .withFailMessage("These pages put an onclick on a .btn-admin-settings button, "
                                   + "which session.js removes — give the button its own class "
                                   + "instead: %s", offenders)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the member's Financial Report")
    class MemberStatement {

        @Mock FamilyMemberRepository          familyMemberRepo;
        @Mock ChurchRegistrationRepository    churchRegRepo;
        @Mock IncomeRepository                incomeRepo;
        @Mock FinancialReportLetterRepository letterRepo;

        MembershipFormController controller;

        @BeforeEach
        void setUp() {
            controller = new MembershipFormController(
                    null, null, null, null, familyMemberRepo, churchRegRepo, null, null, null,
                    null, null, null, incomeRepo, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);
            controller.setLetterService(new FinancialReportLetterService(letterRepo));

            FamilyMember self = new FamilyMember();
            self.setFirstName("Grace");
            self.setLastName("Hall");
            when(familyMemberRepo.findById(7)).thenReturn(Optional.of(self));

            ChurchRegistration reg = new ChurchRegistration();
            reg.setChurchName("Grace Chapel");
            when(churchRegRepo.findByClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(reg));
            when(incomeRepo.reportIncomeEntriesForTaxYear(anyInt(), anyString(), any(), any()))
                    .thenReturn(List.of());
        }

        private MockHttpServletRequest memberRequest() {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("role",        "Member");
            session.setAttribute("memberId",    7);
            session.setAttribute("appClientId", TENANT);
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/member/tax-report");
            req.setSession(session);
            return req;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> report() {
            return (Map<String, Object>) controller.getMemberTaxReport(2026, memberRequest()).getBody();
        }

        @Test
        @DisplayName("a member sees the church's own wording, not the built-in default")
        void memberSeesTheChurchsWording() {
            FinancialReportLetter row = new FinancialReportLetter();
            row.setAppClientId(TENANT);
            row.setIntroHtml("<p class=\"ltr-body\">{ChurchName} thanks you for {Year}.</p>");
            row.setClosingHtml("<div class=\"ltr-disclaimer\">Our notice</div>");
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(row));

            Map<String, Object> body = report();
            assertThat(String.valueOf(body.get("introHtml")))
                    .isEqualTo("<p class=\"ltr-body\">Grace Chapel thanks you for 2026.</p>");
            assertThat(String.valueOf(body.get("closingHtml"))).contains("Our notice");
        }

        @Test
        @DisplayName("a church that has never edited it still gets today's letter")
        void unchangedChurchSeesTheOriginal() {
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.empty());
            Map<String, Object> body = report();
            assertThat(String.valueOf(body.get("introHtml")))
                    .contains("Thank you for your faithful giving", "Grace Chapel", "2026");
            assertThat(String.valueOf(body.get("closingHtml")))
                    .contains("Important Tax Notice", "We are truly grateful");
        }

        @Test
        @DisplayName("a member never sees another church's wording, whatever exists in the table")
        void anotherChurchsWordingIsUnreachable() {
            FinancialReportLetter theirs = new FinancialReportLetter();
            theirs.setAppClientId(OTHER);
            theirs.setIntroHtml("<p>Another church's letter</p>");
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(OTHER)).thenReturn(Optional.of(theirs));
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.empty());

            Map<String, Object> body = report();

            assertThat(String.valueOf(body.get("introHtml"))).doesNotContain("Another church");
            assertThat(String.valueOf(body.get("introHtml"))).contains("Thank you for your faithful giving");
            verify(letterRepo, never()).findFirstByAppClientIdAndDeleteFlagFalse(OTHER);
            verify(letterRepo, never()).findAll();
        }

        @Test
        @DisplayName("the contribution table and totals are untouched by any of this")
        void tableIsUnchanged() {
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.empty());
            Map<String, Object> body = report();
            assertThat(body).containsKeys("year", "churchName", "member", "grandTotal");
            verify(incomeRepo).reportIncomeEntriesForTaxYear(eq(2026), eq(TENANT), eq(7), eq(null));
        }
    }
}
