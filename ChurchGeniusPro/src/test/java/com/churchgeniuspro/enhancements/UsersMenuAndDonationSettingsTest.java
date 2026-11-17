package com.churchgeniuspro.enhancements;

import com.churchgeniuspro.util.RoleGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-11-16 — items 9 (Users is for the church login only) and 6 (Donation Review explains its disabled buttons). */
@DisplayName("Users menu is church-only; Donation Review explains why Save is off")
class UsersMenuAndDonationSettingsTest {

    static final Path STATIC = Path.of("src/main/resources/static");

    static MockHttpServletRequest as(String role, boolean church) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", "CHR-1"); s.setAttribute("appClientId", "CHR-1"); s.setAttribute("username", "u");
        s.setAttribute("role", role); s.setAttribute("church", church);
        r.setSession(s);
        return r;
    }

    @Test @DisplayName("server: only the church login passes the Users gate — SuperAdmin, Admin and the rest are refused")
    void usersGate() throws Exception {
        assertThat(RoleGuard.requireChurch(as("Church", true))).isNull();
        for (String role : new String[] { "SuperAdmin", "Admin", "Accountant", "User", "Member" }) {
            assertThat(RoleGuard.requireChurch(as(role, false))).as(role).isNotNull();
        }
        // every /api/users handler goes through that gate (ownerClientId) — none answers without it
        String src = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AppUserController.java"));
        Matcher m = Pattern.compile("@(?:Get|Post|Put|Patch|Delete)Mapping\\(\"(/api/users[^\"]*)\"\\)").matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
            int next = src.indexOf("Mapping(\"", m.end());
            String body = src.substring(m.end(), next < 0 ? src.length() : next);
            if (m.group(1).equals("/api/users/linked-roles")) continue;      // a login's OWN linked accounts (account switcher)
            assertThat(body).as("%s must check the church login", m.group(1))
                    .containsAnyOf("ownerClientId(", "requireChurch(", "if (!isChurch) return ResponseEntity.status(403)");
        }
        assertThat(n).isGreaterThanOrEqualTo(10);
    }

    @Test @DisplayName("menu: Users is rendered for the church login only, and every page that draws the menu also loads the role filter")
    void usersMenu() throws Exception {
        String shell = Files.readString(STATIC.resolve("shell.js"));
        assertThat(shell).containsPattern("id: 'admin/users',[^\\n]*href: '/viewusers',\\s*churchOnly: true");
        assertThat(shell).contains("if (child.churchOnly && localStorage.getItem('church') !== 'true') return;");
        try (var files = Files.list(STATIC)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".html"))::iterator) {
                String html = Files.readString(p);
                if (!html.contains("src=\"/shell.js\"")) continue;
                if (p.getFileName().toString().equals("memberHome.html")) continue;   // has its own session handling
                assertThat(html).as("%s draws the side menu, so it must load session.js (role + permission filter)", p.getFileName())
                        .contains("/session.js");
            }
        }
    }

    @Test @DisplayName("Donation Review: buttons off until settings are saved, with the reason and what is missing; the server refuses too")
    void donationSettings() throws Exception {
        String page = Files.readString(STATIC.resolve("donationReview.html"));
        assertThat(page).contains("are turned off until the required Donation Settings are")
                .contains("Still needed: ").contains("MISSING_LABEL")
                .contains("Turned off until the required Donation Settings are saved")
                .contains("const dis = (postBusy || !settingsComplete) ? ' disabled' : '';")
                .contains("bs.disabled = reviewBusy || postBusy || !anyPostable || !settingsComplete;");
        String ctl = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/DonationPostingController.java"));
        assertThat(ctl).contains("SETTINGS_INCOMPLETE");
    }
}
