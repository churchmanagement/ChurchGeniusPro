package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.repository.ServiceClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-16 (item 11) — Service Admin → Account Health → client login overview: every registered church with its
 * last SUCCESSFUL sign-in, who it was, and whether it was within the last 30 days.
 */
@DisplayName("Account Health — client login overview")
class AccountLoginOverviewTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    ServiceClientRepository clients = mock(ServiceClientRepository.class);
    PlaidItemRepository items = mock(PlaidItemRepository.class);
    PlaidGuard guard = mock(PlaidGuard.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AccountHealthService svc;

    static ServiceClient sc(int id, String cid, String name, String status, LocalDate end) {
        ServiceClient c = new ServiceClient();
        c.setId(id); c.setClientId(cid); c.setChurchName(name);
        c.setStatus(status); c.setEndDate(end); c.setDeleteFlag(false); c.setSubscriptionType("PRO");
        return c;
    }
    static Map<String, Object> row(String church, String user, LocalDate day, int hour) {
        Map<String, Object> m = new HashMap<>();
        m.put("church", church); m.put("username", user); m.put("last_at", Timestamp.valueOf(day.atTime(hour, 15)));
        return m;
    }

    @BeforeEach
    void setUp() {
        svc = new AccountHealthService(clients, items, guard, jdbc);
        when(clients.findAllByDeleteFlagFalseOrderByIdDesc()).thenReturn(List.of(
                sc(1, "CHR-busy",  "Busy Church",  "Active", TODAY.plusDays(90)),
                sc(2, "CHR-quiet", "Quiet Church", "Active", TODAY.plusDays(90)),
                sc(3, "CHR-never", "Never Church", "Active", TODAY.plusDays(90)),
                sc(4, "CHR-hold",  "Hold Church",  "Hold",   TODAY.plusDays(90)),
                sc(5, "CHR-edge",  "Edge Church",  "Active", TODAY.plusDays(90))));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                row("CHR-busy",  "pastor",    TODAY.minusDays(3), 9),
                row("CHR-busy",  "treasurer", TODAY.minusDays(1), 18),     // the later one wins
                row("CHR-quiet", "admin",     TODAY.minusDays(40), 8),
                row("CHR-hold",  "owner",     TODAY.minusDays(2), 7),
                row("CHR-edge",  "edge",      TODAY.minusDays(30), 0),     // exactly 30 days ago counts
                row("CHR-gone",  "ghost",     TODAY.minusDays(1), 7)));    // a removed church is not listed
        when(items.findByDeleteFlagFalse()).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> logins() { return (List<Map<String, Object>>) svc.report(TODAY).get("logins"); }

    private Map<String, Object> of(String cid) {
        return logins().stream().filter(x -> cid.equals(x.get("clientId"))).findFirst().orElseThrow();
    }

    @Test @DisplayName("every registered church is listed with its last successful login, the user and the 30-day flag")
    void overview() {
        assertThat(logins()).hasSize(5).extracting(x -> x.get("clientId")).doesNotContain("CHR-gone");

        assertThat(of("CHR-busy")).containsEntry("churchName", "Busy Church").containsEntry("status", "Active")
                .containsEntry("lastLoginUser", "treasurer").containsEntry("loggedInRecently", true)
                .containsEntry("lastLogin", TODAY.minusDays(1).atTime(18, 15).toString());
        assertThat(of("CHR-quiet")).containsEntry("lastLoginUser", "admin").containsEntry("loggedInRecently", false);
        assertThat(of("CHR-never")).containsEntry("lastLogin", null).containsEntry("lastLoginUser", null)
                .containsEntry("loggedInRecently", false);
        assertThat(of("CHR-hold")).containsEntry("status", "Hold").containsEntry("accountActive", false)
                .containsEntry("loggedInRecently", true);
        assertThat(of("CHR-edge")).containsEntry("loggedInRecently", true);
    }

    @Test @DisplayName("churches with no recent login come first; the count and the window are reported")
    @SuppressWarnings("unchecked")
    void orderAndCounts() {
        assertThat(logins()).extracting(x -> x.get("clientId"))
                .containsExactly("CHR-never", "CHR-quiet", "CHR-busy", "CHR-hold", "CHR-edge");
        Map<String, Object> r = svc.report(TODAY);
        assertThat((Map<String, Object>) r.get("counts")).containsEntry("noRecentLogin", 2);
        assertThat(r).containsEntry("recentLoginDays", 30);
    }

    @Test @DisplayName("only successful sign-ins are read, and nothing is written")
    void successfulOnly() {
        svc.report(TODAY);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture());
        assertThat(sql.getValue()).contains("l.outcome = 'SUCCESS'").startsWith("SELECT")
                .doesNotContainIgnoringCase("update ").doesNotContainIgnoringCase("delete ");
    }

    @Test @DisplayName("an unreadable sign-in history still lists every church (as no login recorded)")
    void historyUnavailable() {
        when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("down"));
        assertThat(logins()).hasSize(5).allSatisfy(x -> assertThat(x).containsEntry("loggedInRecently", false));
    }

    @Test @DisplayName("page: six tabs, every card in exactly one tab, Public Website Visitors removed, login overview shown")
    void page() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/serviceadminhome.html"));
        for (String t : List.of("Client Management", "Data Management", "Platform Management", "Account Health",
                "Billing and Subscriptions", "Support and Messaging"))
            assertThat(html).contains(">" + t + "</button>");
        assertThat(html).doesNotContain("webStatsCard", "loadWebStats", "Public Website Visitors");

        int at = html.indexOf("var TABS = {");
        String block = html.substring(at, html.indexOf("};", at));
        List<String> cards = List.of("registerPanelWrap", "registeredClientsCard", "trialRequestCard", "trialLinkCard",
                "subscriptionRequestCard", "testDataCard", "etlOverviewCard", "backupCard", "trialBackfillCard",
                "churchDeletionCard", "eventRetentionCard", "platformSettingsCard", "acctHealthCard", "billingCard",
                "plansCard", "promoCard", "promoReportCard", "ticketCard", "messagingCard");
        for (String id : cards) {
            assertThat(block.split("'" + id + "'", -1).length - 1).as("card %s is in exactly one tab", id).isEqualTo(1);
            assertThat(html).as("card %s exists", id).contains("id=\"" + id + "\"");
        }
        // every list card on the page is assigned
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<div class=\"list-card\"( id=\"([A-Za-z]+)\")?").matcher(html);
        while (m.find()) assertThat(m.group(2)).as("a list card without an id cannot be assigned to a tab").isNotNull().isIn(cards);

        assertThat(html).contains("id=\"ahLogins\"", "Client login overview", "ahRenderLogins(d);", "'No login recorded'",
                "ah-login-none", "Failed sign-in attempts are not counted");
    }
}
