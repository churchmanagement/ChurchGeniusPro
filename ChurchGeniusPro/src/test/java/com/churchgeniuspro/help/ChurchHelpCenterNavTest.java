package com.churchgeniuspro.help;

import com.churchgeniuspro.service.HelpArticle;
import com.churchgeniuspro.service.HelpContentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Church logins see only the Admin section, so the Help Center is listed there for them
 * (Admin → Help Center), opening the "Account &amp; User Management" card that covers the
 * Users page. Staff keep More → Help Center unchanged. (2026-10-29)
 */
@DisplayName("Admin → Help Center for the church account")
class ChurchHelpCenterNavTest {

    static String read(String p) throws IOException { return Files.readString(Paths.get(p)); }
    static final String STATIC = "src/main/resources/static/";

    @Test @DisplayName("the Admin menu lists Help Center for church logins only; More → Help Center is unchanged")
    void menuItems() throws IOException {
        String shell = read(STATIC + "shell.js");
        Matcher admin = Pattern.compile("\\{ id: 'admin/help-center',[^}]*\\}").matcher(shell);
        assertThat(admin.find()).as("admin/help-center menu item").isTrue();
        assertThat(admin.group()).contains("href: '/helpCenter#account-management'", "churchOnly: true");
        assertThat(shell).contains("{ id: 'more/help-center',    label: 'Help Center',    icon: '&#x1F4DA;', href: '/helpCenter'    }");
        // churchOnly items are skipped for every other role when the menu is built.
        assertThat(shell).contains("if (child.churchOnly && localStorage.getItem('church') !== 'true') return;");
    }

    @Test @DisplayName("the church menu filter lets the Help Center item through")
    void churchFilterAllowsIt() throws IOException {
        String session = read(STATIC + "session.js").replace("\r", "");
        int start = session.indexOf("function applyChurchNavFilter()");
        int end = session.indexOf("function reorderNav(", start);
        String filter = session.substring(start, end);
        assertThat(filter).contains("var isHelp     = onclick.indexOf('/helpCenter') !== -1;")
                .contains("var show = isUsers || isStripe || isWhatsapp || isTickets || isAi || isHelp;");
    }

    @Test @DisplayName("the Help Center has the Account & User Management card with all seven topics, church-only")
    void card() throws IOException {
        String page = read(STATIC + "helpCenter.html");
        int start = page.indexOf("id=\"hcAccountMgmt\"");
        assertThat(start).isPositive();
        String card = page.substring(start, page.indexOf("<!-- ═══", start));
        assertThat(page.substring(page.lastIndexOf("<div", start), start)).contains("hc-church-only");
        assertThat(card).contains("data-keywords=\"account-management ");   // matched by openFromHash
        for (String topic : List.of("How to Add a User", "How to Invite a User", "How to Set Permissions",
                "How to Configure Temporary Access", "How to Link Accounts", "How to Use the AI Search Screen",
                "How to View Member Accounts"))
            assertThat(card).as(topic).contains(topic);
        assertThat(card).contains("href=\"/viewusers\"", "href=\"/temporaryAccess\"", "href=\"/ai-assistant\"");
        assertThat(card).doesNotContain("<img");   // button mock-ups only, never screenshots of church data
        // hidden until /api/session confirms a church login, and immune to the category filter
        assertThat(page).contains(".hc-church-only:not(.hc-church-visible) { display: none !important; }",
                "var church = s.church === true || s.church === 'true';",
                "el.classList.add('hc-church-visible')",
                "window.addEventListener('hashchange', openFromHash);");
        // church logins see Admin → Help Center highlighted; staff keep More
        assertThat(page).contains("{ activePage: 'admin/help-center', pageTitle: 'Help Center', openSection: 'admin' }",
                "{ activePage: 'helpCenter', pageTitle: 'Help Center', openSection: 'more' }");
    }

    @Test @DisplayName("quick answers cover the new topics and no longer tell admins to set a password")
    void quickAnswers() throws IOException {
        String page = read(STATIC + "helpCenter.html");
        for (String id : List.of("add-user", "invite-user", "link-accounts", "member-accounts", "temporary-access", "users-permissions"))
            assertThat(page).as("HC_KB " + id).contains("id: '" + id + "'");
        assertThat(page).doesNotContain("'Set a temporary password'");
    }

    @Test @DisplayName("the Help Assistant's articles carry the same instructions")
    void serverArticles() {
        HelpContentCatalog catalog = new HelpContentCatalog();
        HelpArticle users = catalog.byId("users_permissions");
        assertThat(users.detail).contains("Invite", "Link Accounts", "Member Portal Users", "Limited");
        assertThat(users.detail).doesNotContain("link several church accounts");
        assertThat(String.join(" ", catalog.byId("temporary_access").steps)).contains("6-digit code", "/tempLogin");
    }
}
