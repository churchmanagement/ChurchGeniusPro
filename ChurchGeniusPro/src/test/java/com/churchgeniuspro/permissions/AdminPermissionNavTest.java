package com.churchgeniuspro.permissions;

import com.churchgeniuspro.util.PermissionApiCatalog;
import com.churchgeniuspro.util.PermissionCatalog;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.RolePermissionDefaults;
import com.churchgeniuspro.webfilter.PermissionApiFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-06 — Admin permissions, side navigation and direct URL access: Admin defaults carry no
 * Accounting key (not even Payroll), a never-saved Admin is held to those defaults on pages and
 * APIs, the accounting APIs require the page's key, parent keys follow their children, and AI
 * Connections / Account Privacy use the standard layout.
 */
@DisplayName("Admin permissions, navigation and direct URL access")
class AdminPermissionNavTest {

    static final Path STATIC = Path.of("src/main/resources/static");
    static String file(String name) throws Exception { return Files.readString(STATIC.resolve(name)); }

    static MockHttpServletRequest staff(String role, String privileges, String method, String uri) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u"); s.setAttribute("role", role);
        s.setAttribute("appUserId", 1); s.setAttribute("church", false); s.setAttribute("clientId", "CHR-1");
        if (privileges != null) s.setAttribute("privileges", privileges);
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri); r.setSession(s); return r;
    }

    static int filter(MockHttpServletRequest r) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new PermissionApiFilter().doFilter(r, res, chain);
        return chain.getRequest() != null ? 200 : res.getStatus();
    }

    @Test @DisplayName("server defaults mirror viewUsers.html ROLE_DEFAULTS; Admin has no Accounting key")
    void defaultsMirrorTheScreen() throws Exception {
        String html = file("viewUsers.html");
        Matcher block = Pattern.compile("const ROLE_DEFAULTS = \\{(.*?)\\};", Pattern.DOTALL).matcher(html);
        assertThat(block.find()).isTrue();
        Map<String, List<String>> screen = new LinkedHashMap<>();
        Matcher row = Pattern.compile("(\\w+):\\s*\\[([^\\]]*)\\]").matcher(block.group(1).replaceAll("//[^\\n]*", ""));
        while (row.find()) {
            screen.put(row.group(1), Arrays.stream(row.group(2).split(",")).map(x -> x.trim().replace("'", ""))
                    .filter(x -> !x.isEmpty()).toList());
        }
        RolePermissionDefaults.ROLE_DEFAULTS.forEach((role, prefixes) ->
                assertThat(screen.get(role)).as(role).containsExactlyElementsOf(prefixes));

        Map<String, Boolean> admin = RolePermissionDefaults.map("Admin");
        assertThat(admin.keySet()).containsExactlyInAnyOrderElementsOf(PermissionCatalog.keys());
        admin.forEach((k, v) -> { if (k.equals("accounting") || k.startsWith("accounting.")) assertThat(v).as(k).isFalse(); });
        assertThat(admin.get("admin.family")).isTrue();
        assertThat(RolePermissionDefaults.map("SuperAdmin").values()).containsOnly(true);
        assertThat(RolePermissionDefaults.map("Accountant").get("accounting.payroll")).isTrue();
        // only Admin gets defaults when nothing is saved; other roles unchanged
        assertThat(RolePermissionDefaults.APPLY_WHEN_NOTHING_SAVED).containsExactly("Admin");
        assertThat(RolePermissionDefaults.effective("User", null)).isNull();
        assertThat(RolePermissionDefaults.effective("Admin", "{\"x\":true}")).isEqualTo("{\"x\":true}");
        assertThat(RolePermissionDefaults.effective("Admin", null)).contains("\"accounting.payroll\":false");
    }

    @Test @DisplayName("never-saved Admin: Income/Expense/Payroll pages denied, Families allowed; granted → allowed")
    void pageGuards() {
        for (String key : List.of("accounting.income", "accounting.expense", "accounting.payroll", "accounting.reports")) {
            assertThat(RoleGuard.requirePagePermission(staff("Admin", null, "GET", "/x"), key)).as(key).isNotNull();
            assertThat(RoleGuard.requirePagePermission(staff("Admin", TestPerms.ADMIN_WITH_ACCOUNTING, "GET", "/x"), key)).isNull();
            // SuperAdmin / Accountant with nothing saved: unchanged
            assertThat(RoleGuard.requirePagePermission(staff("SuperAdmin", null, "GET", "/x"), key)).isNull();
            assertThat(RoleGuard.requirePagePermission(staff("Accountant", null, "GET", "/x"), key)).isNull();
        }
        assertThat(RoleGuard.requirePagePermission(staff("Admin", null, "GET", "/x"), "admin.family")).isNull();
        // explicit grant of Income only
        String incomeOnly = "{\"accounting\":true,\"accounting.income\":true,\"accounting.expense\":false}";
        assertThat(RoleGuard.requirePagePermission(staff("Admin", incomeOnly, "GET", "/x"), "accounting.income")).isNull();
        assertThat(RoleGuard.requirePagePermission(staff("Admin", incomeOnly, "GET", "/x"), "accounting.expense")).isNotNull();
        // main menu unticked → every page under it denied, even with a child still ticked
        String parentOff = "{\"accounting\":false,\"accounting.income\":true}";
        assertThat(RoleGuard.requirePagePermission(staff("Admin", parentOff, "GET", "/x"), "accounting.income")).isNotNull();
        assertThat(RoleGuard.requirePagePermission(staff("Accountant", parentOff, "GET", "/x"), "accounting.income")).isNotNull();
        assertThat(RoleGuard.pagePermissionAllows("{\"general\":false}", "general.attendance")).isFalse();
        assertThat(RoleGuard.pagePermissionAllows("{\"general\":true}", "general.attendance")).isTrue();
        assertThat(RoleGuard.pagePermissionAllows("{}", "general.attendance")).isTrue();          // missing = allowed
        assertThat(RoleGuard.pagePermissionAllows("{\"member\":false}", "member.profile")).isTrue(); // only staff main menus
    }

    @Test @DisplayName("accounting APIs: 403 without the key, pass with it; shared drop-downs any-of; others untouched")
    void apiFilter() throws Exception {
        assertThat(PermissionCatalog.keys()).containsAll(PermissionApiCatalog.keys());
        assertThat(filter(staff("Admin", null, "GET", "/api/income"))).isEqualTo(403);
        assertThat(filter(staff("Admin", null, "GET", "/api/expense/12"))).isEqualTo(403);
        assertThat(filter(staff("Admin", null, "GET", "/api/payroll/employees"))).isEqualTo(403);
        assertThat(filter(staff("Admin", null, "POST", "/api/payroll/runs"))).isEqualTo(403);
        assertThat(filter(staff("Admin", TestPerms.ADMIN_WITH_ACCOUNTING, "GET", "/api/income"))).isEqualTo(200);
        String incomeOnly = "{\"accounting.income\":true,\"accounting.expense\":false}";
        assertThat(filter(staff("Admin", incomeOnly, "GET", "/api/income"))).isEqualTo(200);
        assertThat(filter(staff("Admin", "{\"accounting\":false,\"accounting.income\":true}", "GET", "/api/income"))).isEqualTo(403);
        assertThat(filter(staff("Admin", incomeOnly, "GET", "/api/expense"))).isEqualTo(403);
        // pledges page alone may read the contributor list
        String pledgesOnly = "{\"accounting.income\":false,\"accounting.pledges\":true}";
        assertThat(filter(staff("Admin", pledgesOnly, "GET", "/api/income/contributors"))).isEqualTo(200);
        assertThat(filter(staff("Admin", pledgesOnly, "GET", "/api/income"))).isEqualTo(403);
        // Bank Sync follows Bank Import; verification steps and Plaid's webhook stay open
        assertThat(filter(staff("Admin", null, "GET", "/api/plaid/review/pending"))).isEqualTo(403);
        assertThat(filter(staff("Admin", null, "POST", "/api/plaid/link-token"))).isEqualTo(403);
        assertThat(filter(staff("Admin", "{\"accounting.bankimport\":true}", "GET", "/api/plaid/items"))).isEqualTo(200);
        assertThat(filter(staff("Admin", null, "GET", "/api/plaid/gate/status"))).isEqualTo(200);
        assertThat(filter(staff("Admin", null, "POST", "/api/plaid/webhook"))).isEqualTo(200);
        // writes on /api/income keep the controller's edit/delete checks (not this filter)
        assertThat(filter(staff("Admin", null, "POST", "/api/income"))).isEqualTo(200);
        // not gated paths, other roles and sessions pass through
        assertThat(filter(staff("Admin", null, "GET", "/api/families"))).isEqualTo(200);
        assertThat(filter(staff("Admin", null, "GET", "/api/income-report"))).isEqualTo(200);
        assertThat(filter(staff("SuperAdmin", null, "GET", "/api/income"))).isEqualTo(200);
        assertThat(filter(staff("Accountant", null, "GET", "/api/income"))).isEqualTo(200);
        MockHttpServletRequest church = staff("SuperAdmin", "{\"accounting.income\":false}", "GET", "/api/income");
        church.getSession().setAttribute("church", true);
        assertThat(filter(church)).isEqualTo(200);
        MockHttpServletRequest member = staff("Member", "{}", "GET", "/api/income");
        assertThat(filter(member)).isEqualTo(200);   // MemberApiScopeFilter governs members
        assertThat(filter(new MockHttpServletRequest("GET", "/api/income"))).isEqualTo(200);   // AuthFilter → 401
        // the response names the code
        MockHttpServletResponse res = new MockHttpServletResponse();
        new PermissionApiFilter().doFilter(staff("Admin", null, "GET", "/api/income"), res, new MockFilterChain());
        assertThat(res.getContentAsString()).contains("PERMISSION_DENIED").contains("accounting.income");
    }

    @Test @DisplayName("sign-in, refresher, tokens and workflows read the effective map")
    void wiring() throws Exception {
        Path main = Path.of("src/main/java/com/churchgeniuspro");
        for (String f : List.of("controller/LoginController.java", "service/PermissionRefresher.java",
                "mcp/PersonalAccessTokenService.java", "workflow/WorkflowEngine.java")) {
            assertThat(Files.readString(main.resolve(f))).as(f).contains("RolePermissionDefaults.effective(");
        }
        assertThat(Files.readString(main.resolve("webfilter/PermissionApiFilterConfig.java"))).contains("/api/*");
    }

    @Test @DisplayName("navigation: no Accounting for Admin by role, empty sections hidden, parent keys derived on save")
    void navigationSources() throws Exception {
        String session = file("session.js"), shell = file("shell.js"), users = file("viewUsers.html");
        assertThat(session).contains("function hideEmptyNavSections()").contains("anyChild");
        assertThat(session.replaceAll("//[^\\n]*", "")).doesNotContainPattern("role\\s*===?\\s*'Admin'[^;]*payroll");
        assertThat(users).contains("function deriveParentKeys(map)");
        assertThat(users.indexOf("deriveParentKeys(_permMap)")).isGreaterThan(users.indexOf("function savePermissions"));
        Matcher adminDefaults = Pattern.compile("Admin:\\s*\\[([^\\]]*)\\]").matcher(users);
        assertThat(adminDefaults.find()).isTrue();
        assertThat(adminDefaults.group(1)).doesNotContain("accounting");
        assertThat(shell).contains("ROLE_ALLOWED");
    }

    @Test @DisplayName("every page with the side menu script uses the standard app layout")
    void everyShellPageHasTheSidebar() throws Exception {
        List<String> missing = new java.util.ArrayList<>();
        try (var files = Files.list(STATIC)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".html")).toList()) {
                String html = Files.readString(f);
                if (html.contains("src=\"/shell.js\"") && !(html.contains("id=\"sidebar\"") && html.contains("class=\"main-area\"")))
                    missing.add(f.getFileName().toString());
            }
        }
        assertThat(missing).as("pages loading shell.js without the standard sidebar layout").isEmpty();
    }

    @Test @DisplayName("Add/Edit Family on phones: members and roles stack inside their cards")
    void familyPhoneLayout() throws Exception {
        String html = file("family.html");
        int media = html.indexOf("@media (max-width: 700px)");
        assertThat(media).isPositive();
        assertThat(media).as("phone rules come after the base table rules").isGreaterThan(html.indexOf(".roles-table select {"));
        assertThat(html).contains("<td class=\"mt-actions\">").contains("data-label=\"Email\" class=\"mt-email\"")
                .contains(".members-table thead { display: none; }").contains(".roles-table thead { display: none; }");
    }

    @Test @DisplayName("AI Connections, Account Privacy and Automations use the standard app layout")
    void standardLayout() throws Exception {
        for (String page : List.of("apiTokens.html", "accountPrivacy.html", "workflows.html")) {
            String html = file(page);
            assertThat(html).as(page).contains("<div class=\"app\">").contains("<aside class=\"sidebar\" id=\"sidebar\">")
                    .contains("class=\"main-area\"").contains("id=\"topbar\"").contains("class=\"page-content\"");
            assertThat(html).as(page).doesNotContain("app-shell");
            // the standard base reset, or the side-menu buttons fall back to the browser's button font
            assertThat(html).as(page).contains("button { cursor:pointer; font-family:inherit;");
        }
    }
}
