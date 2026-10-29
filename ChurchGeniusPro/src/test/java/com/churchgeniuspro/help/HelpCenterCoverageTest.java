package com.churchgeniuspro.help;

import com.churchgeniuspro.repository.HelpAuditLogRepository;
import com.churchgeniuspro.service.HelpArticle;
import com.churchgeniuspro.service.HelpAssistantService;
import com.churchgeniuspro.service.HelpContentCatalog;
import com.churchgeniuspro.service.OpenAiVoiceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Help Center refresh (2026-10-29): every card is filed under a category and says which roles it is for,
 * every server article carries a role list, and a login only sees articles for features its role can use.
 */
@DisplayName("Help Center: categories, role-aware cards and articles")
class HelpCenterCoverageTest {

    static final Set<String> CATS = Set.of("getting-started", "members", "events", "ministry", "communication",
            "accounting", "admin", "member-portal", "mobile-app");
    static final Set<String> ROLES = Set.of("SuperAdmin", "Admin", "Accountant", "User", "Limited", "Member", "Church");

    static String page() throws IOException { return Files.readString(Paths.get("src/main/resources/static/helpCenter.html")); }

    @Test @DisplayName("every card has a known category, a role list of known roles, and a pill for its category")
    void cards() throws IOException {
        String p = page();
        Matcher m = Pattern.compile("<div class=\"hc-card[^\"]*\" id=\"([^\"]+)\"([^>]*)>").matcher(p);
        int n = 0;
        while (m.find()) {
            n++;
            String id = m.group(1), attrs = m.group(2);
            Matcher cat = Pattern.compile("data-cat=\"([^\"]+)\"").matcher(attrs);
            assertThat(cat.find()).as(id + " data-cat").isTrue();
            assertThat(CATS).as(id + " category").contains(cat.group(1));
            assertThat(p).contains("filterCat(this,'" + cat.group(1) + "')");
            if (id.equals("hcAccountMgmt")) continue;          // church-only by its own class
            Matcher roles = Pattern.compile("data-roles=\"([^\"]+)\"").matcher(attrs);
            assertThat(roles.find()).as(id + " data-roles").isTrue();
            assertThat(ROLES).as(id + " roles").containsAll(Arrays.asList(roles.group(1).split(",")));
        }
        assertThat(n).isGreaterThanOrEqualTo(55);
        // each new area is present
        for (String id : List.of("hc-donation-give", "hc-family-report", "hc-membership", "hc-calendar", "hc-meetings",
                "hc-kids", "hc-sunday-school", "hc-worship", "hc-songbook", "hc-stripe", "hc-public-screens",
                "hc-member-give", "hc-member-settings", "hcInstallApp", "hcAccountMgmt"))
            assertThat(p).as(id).contains("id=\"" + id + "\"");
        // no screenshots of church data
        assertThat(p.substring(p.indexOf("id=\"hcGrid\""), p.indexOf("const HC_KB"))).doesNotContain("<img");
    }

    @Test @DisplayName("cards a role cannot use are hidden in a way the category filter cannot undo")
    void gating() throws IOException {
        String p = page();
        assertThat(p).contains(".hc-card.hc-role-hidden { display: none !important; }",
                "card.classList.toggle('hc-role-hidden', !!hide);",
                "var role = church ? 'Church' : (s.role || 'User');");
    }

    @Test @DisplayName("quick answers have unique ids")
    void kbIds() throws IOException {
        Matcher m = Pattern.compile("\n    id: '([^']+)'").matcher(page());
        List<String> ids = new ArrayList<>();
        while (m.find()) ids.add(m.group(1));
        assertThat(ids).hasSizeGreaterThan(100).doesNotHaveDuplicates();
    }

    @Test @DisplayName("every article has a role list and a known category; ids are unique")
    void articles() {
        HelpContentCatalog c = new HelpContentCatalog();
        Set<String> cats = Set.of("Getting Started", "Members", "Events", "Ministry", "Communication", "Accounting", "Admin", "Member Portal", "Help");
        assertThat(c.all().stream().map(a -> a.id).collect(Collectors.toList())).doesNotHaveDuplicates();
        for (HelpArticle a : c.all()) {
            assertThat(a.roles()).as(a.id + " roles").isNotEmpty();
            assertThat(ROLES).as(a.id + " roles").containsAll(a.roles());
            assertThat(cats).as(a.id + " category").contains(a.category);
        }
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("a Member Portal login is no longer listed staff articles; staff are not listed member-only ones")
    void articleListFollowsRole() {
        HelpAssistantService svc = new HelpAssistantService(new HelpContentCatalog(), mock(OpenAiVoiceService.class), mock(HelpAuditLogRepository.class));
        Set<String> member = svc.articles(null, "Member", false).stream().map(m -> String.valueOf(m.get("id"))).collect(Collectors.toSet());
        assertThat(member).contains("member_give", "member_settings", "install_app")
                .doesNotContain("income", "payroll", "donation_give", "members", "users_permissions", "stripe_integration");
        Set<String> accountant = svc.articles(null, "Accountant", false).stream().map(m -> String.valueOf(m.get("id"))).collect(Collectors.toSet());
        assertThat(accountant).contains("income", "donation_give").doesNotContain("member_give", "users_permissions");
        Set<String> church = svc.articles(null, "SuperAdmin", true).stream().map(m -> String.valueOf(m.get("id"))).collect(Collectors.toSet());
        assertThat(church).contains("users_permissions", "stripe_integration").doesNotContain("income");
    }
}
