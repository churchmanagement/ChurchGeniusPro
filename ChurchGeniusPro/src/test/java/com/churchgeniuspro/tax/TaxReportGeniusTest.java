package com.churchgeniuspro.tax;

import com.churchgeniuspro.ai.guided.AiGuidedTaxService;
import com.churchgeniuspro.ai.guided.GuidedDrafts;
import com.churchgeniuspro.ai.write.AiTaxLetterService;
import com.churchgeniuspro.controller.AccountingReportController;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.FinancialReportLetterService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.service.TaxStatements;
import com.churchgeniuspro.util.AppClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-20 — Tax Report: contributor filter, template choice, AI draft of the wording, and the same report asked
 * for through Genius Assistant. One church's gifts stand in for the database (two churches, to prove the other one
 * never shows); the report builder, the controller, the AI wording service and the conversation are the real ones.
 */
@DisplayName("Tax Report — contributor filter, AI draft, Genius Assistant")
class TaxReportGeniusTest {

    static final String A = "CHR-A", B = "CHR-B";
    static final int YEAR = 2026;

    /** One gift: church, member id (null = by name), first, last, guest name, member type, city, state, date, fund, amount. */
    record Gift(String church, Integer id, String first, String last, String guest, String type, String city, String state, String date, String fund, String amount) {}

    List<Gift> gifts = new ArrayList<>();
    IncomeRepository incomes; ChurchRegistrationRepository churches; FinancialReportLetterService letters; OpenAiVoiceService openAi;
    AiAccessPolicy policy; AiGuidedTaxService tax; AiTaxLetterService aiLetters; AccountingReportController controller;
    boolean customised = false;

    @BeforeEach
    void setUp() {
        gifts.add(new Gift(A, 1, "John", "Smith", null, "Member", "Olathe", "KS", "2026-01-04", "Tithe", "100.00"));
        gifts.add(new Gift(A, 1, "John", "Smith", null, "Member", "Olathe", "KS", "2026-02-01", "Building Fund", "50.50"));
        gifts.add(new Gift(A, 2, "Joanna", "Smithson", null, "Guest", "Lenexa", "KS", "2026-03-01", "Tithe", "75.00"));
        gifts.add(new Gift(A, 3, "Mary", "Jones", null, null, "", "", "2026-03-08", "Tithe", "20.00"));           // blank type = Member
        gifts.add(new Gift(A, 4, "Vera", "Visitor", null, "Visitor", "", "", "2026-04-05", "Tithe", "10.00"));
        gifts.add(new Gift(A, null, null, null, "Walk In Family", null, null, null, "2026-05-03", "Tithe", "30.00"));
        gifts.add(new Gift(A, null, null, null, "", null, null, null, "2026-05-10", "Tithe", "5.00"));            // unattributed
        gifts.add(new Gift(A, 1, "John", "Smith", null, "Member", "Olathe", "KS", "2025-12-28", "Tithe", "999.00")); // another year
        gifts.add(new Gift(B, 9, "John", "Smith", null, "Member", "Dallas", "TX", "2026-01-04", "Tithe", "4444.00")); // another church

        incomes = mock(IncomeRepository.class);
        when(incomes.reportAnnualIncomeByMemberFiltered(anyInt(), any(), any(), any())).thenAnswer(i -> summary(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(incomes.reportIncomeEntriesForTaxYear(anyInt(), any(), any(), any())).thenAnswer(i -> entries(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(incomes.findMemberTypesForTaxReport(any(), anyList())).thenAnswer(i -> {
            List<Integer> ids = i.getArgument(1); String church = i.getArgument(0);
            Map<Integer, Object[]> out = new LinkedHashMap<>();
            for (Gift g : gifts) if (g.id() != null && g.church().equals(church) && ids.contains(g.id())) out.put(g.id(), new Object[] { g.id(), g.type() });
            return new ArrayList<>(out.values()); });
        churches = mock(ChurchRegistrationRepository.class);
        when(churches.findByClientIdAndDeleteFlagFalse(any())).thenAnswer(i -> { ChurchRegistration c = new ChurchRegistration(); c.setChurchName(A.equals(i.getArgument(0)) ? "Grace <Chapel>" : "Other Church"); return Optional.of(c); });
        letters = mock(FinancialReportLetterService.class);
        when(letters.rendered(any(), any(), any())).thenAnswer(i -> { Map<String, String> m = new LinkedHashMap<>();
            m.put("introHtml", "<p class=\"ltr-body\">OUR OWN INTRO</p>"); m.put("closingHtml", "<p>OUR OWN CLOSING</p>"); m.put("signatureName", "Rev. Pat"); m.put("signatureTitle", "Treasurer"); return m; });
        when(letters.forEditing(any())).thenAnswer(i -> new LinkedHashMap<>(Map.of("customised", customised)));
        openAi = mock(OpenAiVoiceService.class);
        when(openAi.isEnabled()).thenReturn(true);
        answer("{\"intro\":\"Thank you for giving to {ChurchName} in {Year}. This letter is your official statement.\",\"closing\":\"We are grateful for you.\"}");
        aiLetters = new AiTaxLetterService(openAi);
        policy = spy(new AiAccessPolicy());
        tax = new AiGuidedTaxService(policy, incomes, churches);
        tax.setLetters(letters); tax.setAiLetters(aiLetters);
        controller = new AccountingReportController(incomes, mock(ExpenseRepository.class), mock(PurposeRepository.class), churches);
        controller.setLetterService(letters);
    }

    void answer(String json) { try { when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(new ObjectMapper().readTree(json)); } catch (Exception e) { throw new RuntimeException(e); } }

    List<Gift> pick(int year, String church, Integer memberId, String guest) {
        return gifts.stream().filter(g -> g.church().equals(church) && g.date().startsWith(String.valueOf(year))
                && (memberId == null || memberId.equals(g.id())) && (guest == null || guest.equals(g.guest()))).collect(Collectors.toList());
    }
    List<Object[]> summary(int year, String church, Integer memberId, String guest) {
        Map<String, Object[]> out = new LinkedHashMap<>();
        for (Gift g : pick(year, church, memberId, guest)) {
            String k = g.id() + "|" + g.guest() + "|" + g.fund();
            Object[] r = out.computeIfAbsent(k, x -> new Object[] { g.id(), g.first(), g.last(), g.guest(), g.fund(), "Income", BigDecimal.ZERO, 0 });
            r[6] = ((BigDecimal) r[6]).add(new BigDecimal(g.amount())); r[7] = (Integer) r[7] + 1;
        }
        return new ArrayList<>(out.values());
    }
    List<Object[]> entries(int year, String church, Integer memberId, String guest) {
        List<Object[]> out = new ArrayList<>();
        for (Gift g : pick(year, church, memberId, guest))
            out.add(new Object[] { g.id(), g.first(), g.last(), g.guest(), g.id() == null ? null : "1 Main St", null, g.city(), g.state(), null, Date.valueOf(g.date()), "Income", g.fund(), new BigDecimal(g.amount()), "Check" });
        return out;
    }

    static MockHttpServletRequest req(String role, String church) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat"); r.getSession().setAttribute("role", role);
        r.getSession().setAttribute("appClientId", church); r.getSession().setAttribute("clientId", "USR-1");
        return r;
    }
    static MockHttpServletRequest same(MockHttpServletRequest r) { MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession()); return n; }
    Map<String, Object> say(MockHttpServletRequest r, String text) { return tax.handle(same(r), String.valueOf(r.getSession().getAttribute("appClientId")), text); }
    static String ans(Map<String, Object> m) { return String.valueOf(m.get("answer")); }
    @SuppressWarnings("unchecked") static List<String> choices(Map<String, Object> m) { return (List<String>) m.get("choices"); }
    @SuppressWarnings("unchecked") static Map<String, String> sum(Map<String, Object> m) { return (Map<String, String>) m.get("summary"); }
    @SuppressWarnings("unchecked") static Map<String, Object> statement(Map<String, Object> m) { return (Map<String, Object>) m.get("taxStatement"); }
    @SuppressWarnings("unchecked") static Map<String, Object> report(Map<String, Object> m) { return (Map<String, Object>) statement(m).get("report"); }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> people(Map<String, Object> report) { return (List<Map<String, Object>>) report.get("members"); }
    static List<String> names(Map<String, Object> report) { return people(report).stream().map(p -> String.valueOf(p.get("name"))).collect(Collectors.toList()); }
    @SuppressWarnings("unchecked") static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }
    @SuppressWarnings("unchecked") static Map<String, Object> link(Map<String, Object> m) { return (Map<String, Object>) m.get("link"); }

    // ── the report itself ─────────────────────────────────────────────────────

    @Test @DisplayName("contributor filter: All (default) / Members / Non-Members — figures unchanged, only who is included")
    void contributorFilter() {
        Map<String, Object> all = TaxStatements.build(incomes, A, "Grace", YEAR, null, null, null);
        assertThat(names(all)).containsExactlyInAnyOrder("Smith, John", "Smithson, Joanna", "Jones, Mary", "Visitor, Vera", "Walk In Family");
        assertThat((BigDecimal) all.get("grandTotal")).isEqualByComparingTo("285.50");
        assertThat((BigDecimal) all.get("unattributedTotal")).isEqualByComparingTo("5.00");
        assertThat(all.get("contributorType")).isEqualTo("all");

        Map<String, Object> members = TaxStatements.build(incomes, A, "Grace", YEAR, null, null, "members");
        assertThat(names(members)).containsExactlyInAnyOrder("Smith, John", "Jones, Mary");                       // a blank type is a Member
        assertThat((BigDecimal) members.get("grandTotal")).isEqualByComparingTo("170.50");

        Map<String, Object> non = TaxStatements.build(incomes, A, "Grace", YEAR, null, null, "nonmembers");
        assertThat(names(non)).containsExactlyInAnyOrder("Smithson, Joanna", "Visitor, Vera", "Walk In Family"); // Guest, Visitor, and a name with no record
        assertThat((BigDecimal) non.get("grandTotal")).isEqualByComparingTo("115.00");
        assertThat(((BigDecimal) members.get("grandTotal")).add((BigDecimal) non.get("grandTotal"))).isEqualByComparingTo((BigDecimal) all.get("grandTotal"));

        // each statement holds only that contributor's own gifts
        Map<String, Object> john = people(all).stream().filter(p -> "Smith, John".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat((BigDecimal) john.get("memberTotal")).isEqualByComparingTo("150.50");
        assertThat((List<?>) john.get("entries")).hasSize(2);
        assertThat(john).containsEntry("city", "Olathe").containsEntry("nonMember", false).containsEntry("memberType", "Member");
        assertThat(people(non).stream().filter(p -> p.get("id") == null).findFirst().orElseThrow()).containsEntry("nonMember", true).containsEntry("nameOnly", true);

        assertThat(TaxStatements.contributorType("Non-Members")).isEqualTo("nonmembers");
        assertThatThrownBy(() -> TaxStatements.contributorType("everyone-else")).isInstanceOf(IllegalArgumentException.class);
        // other church, other year: never mixed in
        assertThat(names(TaxStatements.build(incomes, B, "Other", YEAR, null, null, null))).containsExactly("Smith, John");
        assertThat((BigDecimal) TaxStatements.build(incomes, B, "Other", YEAR, null, null, null).get("grandTotal")).isEqualByComparingTo("4444.00");
    }

    @Test @DisplayName("the endpoint: filter and template parameters; old calls answer exactly as before; role and church from the session")
    void endpoint() {
        MockHttpServletRequest acct = req("Accountant", A);
        Map<String, Object> old = body(controller.taxReport(YEAR, null, null, acct));
        assertThat(names(old)).hasSize(5);
        assertThat(old).containsEntry("introHtml", "<p class=\"ltr-body\">OUR OWN INTRO</p>").containsEntry("signatureName", "Rev. Pat");
        assertThat(names(body(controller.taxReport(YEAR, null, null, "members", null, acct)))).containsExactlyInAnyOrder("Smith, John", "Jones, Mary");
        assertThat(names(body(controller.taxReport(YEAR, null, null, "nonmembers", null, acct)))).hasSize(3);
        Map<String, Object> def = body(controller.taxReport(YEAR, null, null, "all", "default", acct));
        assertThat(String.valueOf(def.get("introHtml"))).contains("Thank you for your faithful giving").contains("Grace &lt;Chapel&gt;").doesNotContain("OUR OWN");
        assertThat(String.valueOf(def.get("closingHtml"))).contains("Important Tax Notice");
        assertThat(controller.taxReport(YEAR, null, null, "bogus", null, acct).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.taxReport(YEAR, null, null, "members", null, req("User", A)).getStatusCode().value()).isEqualTo(403);
        // one contributor by id; a member id of another church finds nothing in this one
        assertThat(names(body(controller.taxReport(YEAR, 1, null, null, null, acct)))).containsExactly("Smith, John");
        assertThat(names(body(controller.taxReport(YEAR, 9, null, null, null, acct)))).isEmpty();
    }

    // ── the AI draft ──────────────────────────────────────────────────────────

    @Test @DisplayName("AI draft: wording only — the model gets no contributor and no figure; a draft with a number or a $ is refused; the tax notice is the fixed one")
    void aiDraftWording() throws Exception {
        Map<String, String> d = aiLetters.draft("Grace <Chapel>", YEAR, "warm");
        assertThat(d.get("introHtml")).isEqualTo("<p class=\"ltr-body\">Thank you for giving to Grace &lt;Chapel&gt; in 2026. This letter is your official statement.</p>");
        assertThat(d.get("closingHtml")).startsWith(AiTaxLetterService.TAX_NOTICE_HTML).endsWith("<p class=\"ltr-closing\">We are grateful for you.</p>");
        org.mockito.ArgumentCaptor<String> sys = org.mockito.ArgumentCaptor.forClass(String.class), usr = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(openAi).chatJson(sys.capture(), usr.capture(), anyDouble());
        String sent = sys.getValue() + " " + usr.getValue();
        assertThat(sent).doesNotContain("Grace").doesNotContain("2026").doesNotContain("Smith").doesNotContain("150").contains("{ChurchName}").contains("{Year}");

        for (String bad : List.of("{\"intro\":\"You gave $150.50 this year.\",\"closing\":\"Thanks.\"}", "{\"intro\":\"Thanks for 2026.\",\"closing\":\"Thanks.\"}",
                "{\"intro\":\"Thanks.\",\"closing\":\"Your total was 150 dollars.\"}", "{\"intro\":\"\",\"closing\":\"Thanks.\"}", "{\"other\":1}")) {
            answer(bad);
            assertThat(aiLetters.draft("Grace", YEAR, null)).as(bad).isNull();
        }
        answer("{\"intro\":\"<script>alert(1)</script>Thank you <b>friend</b>.\",\"closing\":\"Bless you.\"}");
        assertThat(aiLetters.draft("Grace", YEAR, null).get("introHtml")).doesNotContain("<script").doesNotContain("<b>").contains("Thank you");
        when(openAi.isEnabled()).thenReturn(false);
        assertThat(aiLetters.draft("Grace", YEAR, null)).isNull();
        String src = Files.readString(Path.of("src/main/java/com/churchgeniuspro/ai/write/AiTaxLetterService.java"));
        assertThat(src).as("the wording service has no way to read gifts").doesNotContain("IncomeRepository").doesNotContain("TaxStatements");
    }

    // ── Genius Assistant ──────────────────────────────────────────────────────

    @Test @DisplayName("start phrases, and the year is asked — never assumed")
    void startAndYear() {
        for (String q : List.of("Create a tax report for 2026.", "Generate my contribution tax statements for 2026.", "Download tax reports for all contributors for 2026.",
                "Create a tax report for John Smith for 2026.", "Generate tax reports for non-members for 2026.", "I need the year-end giving statements", "print donor statements"))
            assertThat(AiGuidedTaxService.isStartRequest(q)).as(q).isTrue();
        for (String q : List.of("tax report", "Open the tax report", "How do I create a tax report?", "What is a tax statement?", "Add an income of $50", "is the building fund tax deductible"))
            assertThat(AiGuidedTaxService.isStartRequest(q)).as(q).isFalse();

        MockHttpServletRequest r = req("Accountant", A);
        Map<String, Object> m = say(r, "Create a tax report");
        assertThat(ans(m)).isEqualTo("Which year would you like to generate the tax report for?");
        int now = AppClock.today().getYear();
        assertThat(choices(m)).containsExactly(String.valueOf(now), String.valueOf(now - 1), String.valueOf(now - 2));
        assertThat(ans(say(r, "this one"))).startsWith("I need a four-digit year");
        assertThat(ans(say(r, String.valueOf(now + 1)))).startsWith((now + 1) + " has not started yet.");
        m = say(r, "2026");
        assertThat(ans(m)).isEqualTo("Which contributors should I include?");
        assertThat(choices(m)).containsExactly("All (Members and Non-Members)", "Members only", "Non-Members only", "A specific contributor");
        verify(incomes, never()).reportAnnualIncomeByMemberFiltered(anyInt(), any(), any(), any());      // nothing read while only asking
    }

    @Test @DisplayName("a specific contributor: exact name, partial first or last name, more than ten, none, back to the groups — then template, summary, statement")
    void specificContributor() {
        MockHttpServletRequest r = req("SuperAdmin", A);
        Map<String, Object> m = say(r, "Create a tax report for John Smith for 2026.");
        assertThat(ans(m)).isEqualTo("I found one contributor matching “John Smith”. Is this the person?");
        assertThat(choices(m)).containsExactly("John Smith — Olathe, KS (member)", "Search again", "Choose All, Members or Non-Members instead");
        m = say(r, "Search again");
        assertThat(ans(m)).isEqualTo("What is the contributor's first and last name? Part of a name is fine.");
        m = say(r, "smith");                                                   // partial last name: both Smith and Smithson
        assertThat(ans(m)).startsWith("I found 2 contributors matching “smith”.");
        assertThat(choices(m)).contains("John Smith — Olathe, KS (member)", "Joanna Smithson — Lenexa, KS (non-member)");
        m = say(r, "jo");                                                      // partial first name, typed instead of choosing
        assertThat(choices(m)).contains("John Smith — Olathe, KS (member)", "Joanna Smithson — Lenexa, KS (non-member)", "Mary Jones (member)");
        m = say(r, "walk");                                                    // a contributor recorded by name only
        assertThat(choices(m).get(0)).isEqualTo("Walk In Family (non-member, by name)");
        m = say(r, "Zebedee");
        assertThat(ans(m)).isEqualTo("I couldn't find a contributor with tax-deductible gifts in 2026 matching “Zebedee”. You can try another name.");
        assertThat(choices(m)).containsExactly("Choose All, Members or Non-Members instead", "Cancel");
        // more than ten matches: not listed
        for (int i = 0; i < 12; i++) gifts.add(new Gift(A, 100 + i, "Lee" + (char) ('A' + i), "Park", null, "Member", "", "", "2026-06-07", "Tithe", "1.00"));
        m = say(r, "park");
        assertThat(ans(m)).isEqualTo("12 contributors match “park”. Please give a more specific first or last name so I can narrow it to ten or fewer.");
        assertThat(choices(m)).containsExactly("Choose All, Members or Non-Members instead", "Cancel");
        m = say(r, "leeb park");
        assertThat(choices(m).get(0)).isEqualTo("LeeB Park (member)");
        m = say(r, "Choose All, Members or Non-Members instead");
        assertThat(ans(m)).isEqualTo("Which contributors should I include?");
        m = say(r, "A specific contributor");
        m = say(r, "John Smith");
        m = say(r, "John Smith — Olathe, KS (member)");
        assertThat(ans(m)).isEqualTo("Which template should I use?");
        assertThat(choices(m)).containsExactly("Use the default template", "Generate an AI draft", "Use my template");
        m = say(r, "Use the default template");
        assertThat(ans(m)).isEqualTo("Here is what I'll generate. Shall I go ahead?");
        assertThat(sum(m)).containsEntry("Tax year", "2026").containsEntry("Contributors", "John Smith").containsEntry("Template", "Default template");
        assertThat(choices(m)).containsExactly("Generate", "Change the year", "Change the contributors", "Change the template", "Cancel");
        assertThat(m.get("taxStatement")).as("nothing is generated before the confirmation").isNull();

        m = say(r, "Generate");
        assertThat(m.get("intent")).isEqualTo("guided.statement");
        assertThat(ans(m)).startsWith("Here is the 2026 tax statement for John Smith: 2 gifts, total $150.50.");
        assertThat(names(report(m))).containsExactly("Smith, John");
        Map<String, Object> john = people(report(m)).get(0);
        assertThat((BigDecimal) john.get("memberTotal")).isEqualByComparingTo("150.50");
        assertThat(String.valueOf(report(m).get("introHtml"))).contains("Thank you for your faithful giving").contains("Grace &lt;Chapel&gt;");
        assertThat(statement(m)).containsEntry("filename", "tax-report-2026-john-smith.pdf").containsEntry("aiDraft", false).containsEntry("count", 1);
        assertThat(sum(m)).containsEntry("Gifts", "2").containsEntry("Total", "$150.50");
        assertThat(link(m)).containsEntry("url", "/tax-report?year=2026&memberId=1&template=default&auto=1");
        assertThat(m.get("pending")).isNull();
        assertThat(AiGuidedTaxService.hasDraft(r.getSession())).isFalse();
        assertThat(new ObjectMapper().valueToTree(report(m)).toString()).as("no other contributor, no other church").doesNotContain("Smithson").doesNotContain("4444").doesNotContain("Dallas");
    }

    @Test @DisplayName("groups: a small group is generated here for one download; a large one links to the Tax Report page with the choices already made")
    void groupsAndLargeBatches() {
        MockHttpServletRequest r = req("Admin", A);
        r.getSession().setAttribute("privileges", "{\"accounting\":true,\"accounting.reports\":true}");
        Map<String, Object> m = say(r, "Generate tax reports for non-members for 2026");
        assertThat(ans(m)).isEqualTo("Which template should I use?");
        m = say(r, "default");
        assertThat(sum(m)).containsEntry("Contributors", "Non-Members only");
        m = say(r, "yes");
        assertThat(ans(m)).startsWith("Here are the 2026 tax statements for 3 contributors (non-members only): total $115.00.");
        assertThat(names(report(m))).containsExactlyInAnyOrder("Smithson, Joanna", "Visitor, Vera", "Walk In Family");
        assertThat(statement(m)).containsEntry("filename", "tax-report-2026-nonmembers.pdf").containsEntry("count", 3);
        assertThat(sum(m)).containsEntry("Statements", "3");

        m = say(r, "Download tax reports for all contributors for 2026 with the default template");
        assertThat(sum(m)).containsEntry("Contributors", "All (Members and Non-Members)").containsEntry("Template", "Default template");
        tax.maxInChat = 4;                                                     // five contributors now count as a large batch
        m = say(r, "Generate");
        assertThat(m.get("intent")).isEqualTo("guided.redirect");
        assertThat(ans(m)).startsWith("5 contributors have tax-deductible gifts in 2026").contains(AiGuidedTaxService.REDIRECT_MSG);
        assertThat(link(m)).containsEntry("label", "Open Tax Report").containsEntry("url", "/tax-report?year=2026&type=all&template=default&auto=1");
        assertThat(m.get("taxStatement")).isNull();
        // one contributor is never a large batch
        m = say(r, "Create a tax report for Mary Jones for 2026 with the default template");
        m = say(r, choices(m).get(0)); m = say(r, "Generate");
        assertThat(statement(m)).containsEntry("count", 1);
        // members only; a year with nothing
        tax.maxInChat = 10;
        m = say(r, "Create tax statements for members only for 2026"); m = say(r, "Use the default template"); m = say(r, "Generate");
        assertThat(names(report(m))).containsExactlyInAnyOrder("Smith, John", "Jones, Mary");
        m = say(r, "Create tax statements for members only for 2024"); m = say(r, "Use the default template"); m = say(r, "Generate");
        assertThat(ans(m)).startsWith("No tax-deductible gifts were found for members only in 2024");
        assertThat(choices(m)).containsExactly("Change the year", "Change the contributors", "Cancel");
        m = say(r, "Change the year");
        m = say(r, "2026");
        assertThat(sum(m)).containsEntry("Tax year", "2026");
    }

    @Test @DisplayName("templates: AI draft is reviewed with real figures; my template needs saved letter text; a file template is refused with the other choices")
    void templates() {
        MockHttpServletRequest r = req("Accountant", A);
        Map<String, Object> m = say(r, "Create a tax report for John Smith for 2026");
        m = say(r, choices(m).get(0));
        m = say(r, "Use my template");
        assertThat(ans(m)).startsWith("Your church has not saved its own letter text yet").contains("cannot be uploaded here").contains("could not be filled in reliably");
        assertThat(choices(m)).containsExactly("Use the default template", "Generate an AI draft", "Cancel");
        assertThat(link(m)).containsEntry("url", "/tax-report");
        m = say(r, "Generate an AI draft");
        assertThat(sum(m)).containsEntry("Template", "AI draft");
        m = say(r, "Generate");
        assertThat(ans(m)).contains("The wording is an AI draft — the names and figures come from your records.");
        assertThat(statement(m)).containsEntry("aiDraft", true);
        assertThat(String.valueOf(report(m).get("introHtml"))).contains("Thank you for giving to Grace &lt;Chapel&gt; in 2026.");
        assertThat(String.valueOf(report(m).get("closingHtml"))).contains("Important Tax Notice").contains("We are grateful for you.");
        assertThat(report(m)).containsEntry("signatureName", "Rev. Pat");
        assertThat((BigDecimal) people(report(m)).get(0).get("memberTotal")).as("figures are the database's").isEqualByComparingTo("150.50");

        // the model fails → nothing generated, other templates offered
        answer("{\"intro\":\"You gave $5.\",\"closing\":\"x\"}");
        m = say(r, "Create a tax report for John Smith for 2026"); m = say(r, choices(m).get(0)); m = say(r, "ai draft"); m = say(r, "Generate");
        assertThat(ans(m)).startsWith("The AI draft could not be produced just now, so nothing was generated.");
        assertThat(m.get("taxStatement")).isNull();
        assertThat(choices(m)).contains("Use the default template");
        // AI not in the plan
        doReturn(false).when(policy).feature(anyString(), eq("aiWrite"));
        m = say(r, "Generate an AI draft");
        assertThat(ans(m)).startsWith("AI drafting is not part of your current plan.");
        doReturn(true).when(policy).feature(anyString(), anyString());
        // the church's own saved letter text
        customised = true;
        m = say(r, "Use my template");
        assertThat(sum(m)).containsEntry("Template", "My template (the church's letter text)");
        m = say(r, "Generate");
        assertThat(report(m)).containsEntry("introHtml", "<p class=\"ltr-body\">OUR OWN INTRO</p>").containsEntry("signatureTitle", "Treasurer");
        assertThat(link(m)).containsEntry("url", "/tax-report?year=2026&memberId=1&template=mine&auto=1");
    }

    @Test @DisplayName("access: the Tax Report page's own rule, every message; the other church's contributors are never found; cancel; another conversation")
    void accessAndIsolation() {
        for (String role : List.of("User", "Member")) {
            Map<String, Object> m = say(req(role, A), "Create a tax report for 2026");
            assertThat(m.get("denied")).as(role).isEqualTo(true);
            assertThat(ans(m)).isEqualTo("Your login doesn't have access to the Tax Report, so I can't generate one.");
        }
        MockHttpServletRequest noReports = req("Admin", A);
        noReports.getSession().setAttribute("privileges", "{\"accounting.reports\":false}");
        assertThat(say(noReports, "Create a tax report for 2026").get("denied")).isEqualTo(true);
        MockHttpServletRequest church = req("Church", A); church.getSession().setAttribute("church", true);
        assertThat(say(church, "Create a tax report for 2026").get("denied")).isEqualTo(true);
        MockHttpServletRequest temp = req("Accountant", A); temp.getSession().setAttribute("tempAccessId", 5L);
        assertThat(say(temp, "Create a tax report for 2026").get("denied")).isEqualTo(true);
        verify(incomes, never()).reportAnnualIncomeByMemberFiltered(anyInt(), any(), any(), any());

        // permission taken away in the middle
        MockHttpServletRequest r = req("Accountant", A);
        say(r, "Create a tax report for 2026");
        r.getSession().setAttribute("role", "User");
        assertThat(say(r, "Members only").get("denied")).isEqualTo(true);
        assertThat(AiGuidedTaxService.hasDraft(r.getSession())).isFalse();

        // church B's accountant sees only church B
        MockHttpServletRequest b = req("Accountant", B);
        Map<String, Object> m = say(b, "Create a tax report for John Smith for 2026");
        assertThat(choices(m).get(0)).isEqualTo("John Smith — Dallas, TX (member)");
        assertThat(ans(say(b, "Smithson"))).startsWith("I couldn't find a contributor");
        // cancel, and a different conversation's start
        assertThat(say(b, "cancel").get("intent")).isEqualTo("guided.cancelled");
        assertThat(AiGuidedTaxService.hasDraft(b.getSession())).isFalse();
        say(b, "Create a tax report");
        assertThat(say(b, "I received 3 checks and $90 in cash")).isNull();
        assertThat(AiGuidedTaxService.hasDraft(b.getSession())).isFalse();
        say(b, "Create a tax report");
        GuidedDrafts.clearOthers(b.getSession(), GuidedDrafts.RECORD);
        assertThat(AiGuidedTaxService.hasDraft(b.getSession())).isFalse();
    }

    @Test @DisplayName("wiring: one builder for the page and the assistant; the page's new controls; the shared letter renderer; nothing here writes")
    void wiring() throws Exception {
        String ctl = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AccountingReportController.java"));
        assertThat(ctl).contains("TaxStatements.build(incomeRepo, appClientId, churchName, year, memberId, guestName, contributorType)");
        String svc = Files.readString(Path.of("src/main/java/com/churchgeniuspro/ai/guided/AiGuidedTaxService.java"));
        assertThat(svc).contains("TaxStatements.build(").doesNotContain(".save(").doesNotContain("createIncome").doesNotContain("openAi");
        String route = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AiDataSearchController.java"));
        assertThat(route.indexOf("taxReports.handle(request, clientId, question)")).isGreaterThan(route.indexOf("manage.handle(request, clientId, question")).isLessThan(route.indexOf("batch.handle(request, clientId, question)"));
        String ai = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AiWritingController.java"));
        assertThat(ai).contains("@PostMapping(\"/api/ai/write/tax-letter\")").contains("RoleGuard.requireAccountantOrAdmin(req) != null");
        String page = Files.readString(Path.of("src/main/resources/static/tax-report.html"));
        assertThat(page).contains("id=\"contributorType\"", "<option value=\"all\" selected>All (Members and Non-Members)</option>", "<option value=\"members\">Members only</option>",
                "<option value=\"nonmembers\">Non-Members only</option>", "id=\"templateSelect\"", "<option value=\"mine\" selected>", "<option value=\"ai\">", "id=\"aiDraftBar\"",
                "/api/ai/write/tax-letter", "&contributorType=${encodeURIComponent(type)}", "function applyUrlChoices()", "<script src=\"/tax-letter.js\"></script>");
        String letter = Files.readString(Path.of("src/main/resources/static/tax-letter.js"));
        assertThat(letter).contains("function lettersHtml(data, year, churchLogoDataUrl)", "Total Contributions — ${year}", "function downloadPdf(el, filename)");
        String js = Files.readString(Path.of("src/main/resources/static/ai-assistant.js"));
        assertThat(js).contains("function looksLikeTaxReport(q)", "function wireTax(box)", "CGP_TAX_LETTER.lettersHtml(data.report, data.year, logo)", "sc.src='/tax-letter.js'");
    }
}
