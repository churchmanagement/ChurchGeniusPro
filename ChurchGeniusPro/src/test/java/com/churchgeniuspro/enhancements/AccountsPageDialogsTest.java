package com.churchgeniuspro.enhancements;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-19 — Accounts (/viewusers): the ✕ that did nothing, Invite → ReInvite, the Help window,
 * and the Public Screens security notice.
 */
class AccountsPageDialogsTest {

    static final Path STATIC = Path.of("src/main/resources/static");
    static String read(String f) throws Exception { return Files.readString(STATIC.resolve(f)); }

    /**
     * The Edit Member dialog's ✕, Cancel and Save, and the row's 📝 / 🔑 buttons, called functions that
     * were never defined, so clicking them did nothing. Every function an inline handler on the page
     * names must exist in the page's own script.
     */
    @Test @DisplayName("every button on the Accounts page calls a function that exists")
    void noDeadHandlers() throws Exception {
        String page = read("viewUsers.html");
        Set<String> called = new TreeSet<>();
        Matcher m = Pattern.compile("on(?:click|change|input|keyup|keydown|submit|focus|blur)=\"\\s*(?:if\\s*\\([^\"]*?\\)\\s*)?([A-Za-z_$][\\w$]*)\\s*\\(").matcher(page);
        while (m.find()) called.add(m.group(1));
        assertThat(called).contains("closeModal", "closeMemberEditModal", "closeLinkModal", "closePermModal", "openMemberEditModal", "saveMemberEdit", "sendMemberResetLink", "softDeleteMember", "openUsersHelp", "sendEmail");
        Set<String> knownDead = Set.of();
        Set<String> builtIn = Set.of("event", "this", "window", "document");
        for (String fn : called) {
            if (knownDead.contains(fn) || builtIn.contains(fn)) continue;
            assertThat(Pattern.compile("function\\s+" + Pattern.quote(fn) + "\\s*\\(").matcher(page).find()
                    || Pattern.compile("window\\." + Pattern.quote(fn) + "\\s*=").matcher(page).find())
                    .as("viewUsers.html calls " + fn + "() from a button but never defines it").isTrue();
        }
    }

    @Test @DisplayName("dialogs sit above the Genius Assistant panel, every ✕ is a real button, Escape closes")
    void dialogsAboveThePanel() throws Exception {
        String page = read("viewUsers.html");
        Matcher z = Pattern.compile("\\.modal-overlay \\{[^}]*z-index:\\s*(\\d+)").matcher(page);
        assertThat(z.find()).isTrue();
        Matcher panel = Pattern.compile("\\.ga-panel\\{[^}]*z-index:(\\d+)").matcher(read("ai-chat.js"));
        assertThat(panel.find()).isTrue();
        assertThat(Integer.parseInt(z.group(1))).as("dialog layer above the assistant panel").isGreaterThan(Integer.parseInt(panel.group(1)));
        assertThat(Pattern.compile("<button class=\"modal-close\"").matcher(page).find()).as("every ✕ carries type=button").isFalse();
        assertThat(page.split("<button type=\"button\" class=\"modal-close\"", -1).length - 1).isEqualTo(4);
        assertThat(page).contains("e.key !== 'Escape'").contains("closeMemberEditModal();").contains("window.CGP_usersHelp.close()");
    }

    @Test @DisplayName("Invite → ReInvite follows the status the server stores; the page never sends on its own")
    void inviteStatus() throws Exception {
        String page = read("viewUsers.html");
        assertThat(page).contains("u.inviteStatus === 'INVITED' || u.inviteStatus === 'ACCEPTED'").contains("const inviteLabel = invited ? 'ReInvite' : 'Invite';")
                .contains("if (_inviteInFlight[id]) return;").contains("a failed send changes nothing");
        // the only place the invitation endpoint is called is the click handler
        assertThat(page.split("/email`", -1).length - 1).isEqualTo(1);
        String send = page.substring(page.indexOf("async function sendEmail(id)"));
        send = send.substring(0, send.indexOf("UNLINK USER"));
        assertThat(send.indexOf("if (res.ok)")).isLessThan(send.indexOf("u.inviteStatus = 'INVITED'"));
        String service = Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/AppUserService.java"));
        assertThat(service).contains("m.put(\"inviteStatus\"").contains("u.setInviteToken(previousToken);");
        assertThat(service).doesNotContain("m.put(\"inviteToken\"");
    }

    @Test @DisplayName("Help window: four topics, loaded on the page, built from the Permissions screen's own list")
    void help() throws Exception {
        String page = read("viewUsers.html");
        String help = read("viewusers-help.js");
        assertThat(page).contains("<script src=\"/viewusers-help.js\"></script>").contains("id=\"btnUsersHelp\"").contains("window.CGP_usersHelp.open(topic || 'users', () => visiblePermTree())");
        assertThat(help).contains("id: 'users'").contains("id: 'privs'").contains("id: 'portal'").contains("id: 'links'");
        // every privilege key of the Permissions tree has a plain-language explanation
        Matcher k = Pattern.compile("\\{\\s*key:\\s*'([a-z.]+)'").matcher(page.substring(page.indexOf("const PERM_TREE"), page.indexOf("const ROLE_DEFAULTS")));
        int n = 0;
        while (k.find()) { n++; assertThat(help).as("help text for privilege " + k.group(1)).contains("'" + k.group(1) + "':"); }
        assertThat(n).isGreaterThan(50);
        // the words on the real screens
        assertThat(help).contains("＋ Add User").contains("Save User").contains("Save Permissions").contains("Select from Existing Member").contains("ReInvite")
                .contains("Member Signup").contains("Generate Link").contains("Select Page").contains("Expiration Date").contains("Generated Links").contains("Member Portal Users");
        assertThat(read("memberSignup.html")).contains("Email Address or Phone Number");
        assertThat(read("publicScreens.html")).contains("Generate a Public Link").contains("Select Page").contains("Expiration Date").contains("Generated Links");
        // text only: the help never calls the server and never claims the links are encrypted
        assertThat(help).doesNotContain("fetch(").doesNotContain("XMLHttpRequest");
        assertThat(help.toLowerCase()).doesNotContain("encrypted");
    }

    @Test @DisplayName("Public Screens notice says what the links really are: random, per church, per page, revocable — not 'encrypted'")
    void securityNotice() throws Exception {
        String page = read("publicScreens.html");
        assertThat(page).contains("id=\"linkSecurityNotice\"").contains("Security &amp; Privacy Notice").contains("your church only").contains("Each church receives its own unique links")
                .contains("can never open another church's pages or information").contains("encrypted (HTTPS) connection");
        assertThat(page).doesNotContain("encrypted client ID").doesNotContain("links generated by ChurchGeniusPro are encrypted");
        // what the notice promises is what the code does (PublicLinkResolverTest exercises it):
        String resolver = Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/PublicLinkResolver.java"));
        assertThat(resolver).contains("new byte[32]").contains("RANDOM.nextBytes(b)").contains("linkRepo.findByToken(param.trim())")
                .contains("if (!isActive(l))").contains("if (!pageUrl.equals(l.getPageUrl()))").doesNotContain("decrypt(");
    }
}
