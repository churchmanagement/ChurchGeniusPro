package com.churchgeniuspro.help;

import com.churchgeniuspro.repository.HelpAuditLogRepository;
import com.churchgeniuspro.service.HelpAssistantService;
import com.churchgeniuspro.service.HelpContentCatalog;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The AI Search bar and the Help Center assistant must know EVERY feature of the product and
 * explain it, while only offering to open what the user may access. "What is ticketing?" used
 * to answer "not a feature available for your account" because Ticketing was in no catalogue
 * and both prompts equated "unknown" with "not permitted".
 */
@DisplayName("Assistant knowledge: every feature known, permissions still respected")
class AssistantKnowledgeTest {

    static String read(String p) throws IOException { return Files.readString(Paths.get(p)); }

    /** Menu href → the help article that documents it. Adding a menu item without an article fails this test. */
    static final Map<String, String> MENU_TO_ARTICLE = new LinkedHashMap<>();
    static {
        MENU_TO_ARTICLE.put("/songbook", "song_book");            MENU_TO_ARTICLE.put("/viewusers", "users_permissions");
        MENU_TO_ARTICLE.put("/viewfamily", "members");            MENU_TO_ARTICLE.put("/groups", "groups");
        MENU_TO_ARTICLE.put("/membershipRequests", "membership_requests"); MENU_TO_ARTICLE.put("/unsubscribed-list", "email_settings");
        MENU_TO_ARTICLE.put("/filesUpload", "files_notes");       MENU_TO_ARTICLE.put("/stripeIntegration", "stripe_integration");
        MENU_TO_ARTICLE.put("/whatsappIntegration", "whatsapp_integration"); MENU_TO_ARTICLE.put("/private-access-settings", "private_page_access");
        MENU_TO_ARTICLE.put("/tickets", "ticketing");             MENU_TO_ARTICLE.put("/ai-assistant", "ai_assistant");
        MENU_TO_ARTICLE.put("/ntagAccess", "ntag_login");         MENU_TO_ARTICLE.put("/income", "income");
        MENU_TO_ARTICLE.put("/expense", "expense");               MENU_TO_ARTICLE.put("/bank-import", "bank_import");
        MENU_TO_ARTICLE.put("/bankSync", "bank_sync");            MENU_TO_ARTICLE.put("/pledges", "pledges");
        MENU_TO_ARTICLE.put("/accountingReports", "reports");     MENU_TO_ARTICLE.put("/donation-review", "donation_give");
        MENU_TO_ARTICLE.put("/payroll", "payroll");               MENU_TO_ARTICLE.put("/meetings", "create_meeting");
        MENU_TO_ARTICLE.put("/attendance", "attendance");         MENU_TO_ARTICLE.put("/events", "create_event");
        MENU_TO_ARTICLE.put("/ministry", "check_in_child");       MENU_TO_ARTICLE.put("/volunteers", "assign_volunteers");
        MENU_TO_ARTICLE.put("/notifyEmail", "email_settings");    MENU_TO_ARTICLE.put("/reminders", "reminders");
        MENU_TO_ARTICLE.put("/certificates", "certificates");     MENU_TO_ARTICLE.put("/publicScreens", "public_screens");
        MENU_TO_ARTICLE.put("/followups", "followups");           MENU_TO_ARTICLE.put("/helpCenter", "help_assistant");
        MENU_TO_ARTICLE.put("/admin/songbook-access", "song_book"); MENU_TO_ARTICLE.put("/eventReminders", "reminders");
        MENU_TO_ARTICLE.put("/autoReminders", "reminders");       MENU_TO_ARTICLE.put("/oneReminders", "reminders");
        MENU_TO_ARTICLE.put("/guessIt", "guess_it");             MENU_TO_ARTICLE.put("/temporaryAccess", "temporary_access");
        MENU_TO_ARTICLE.put("/emailSettings", "email_settings");  MENU_TO_ARTICLE.put("/kidsMinistry", "check_in_child");
        MENU_TO_ARTICLE.put("/worshipPlanning", "worship_planning"); MENU_TO_ARTICLE.put("/prayerRequest", "prayer");
    }

    static Set<String> menuHrefs() throws IOException {
        String shell = read("src/main/resources/static/shell.js");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\{ id: '[^']+',\\s*label: '[^']+',\\s*icon: '[^']+',\\s*href: '(/[^'#]*)'").matcher(shell);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test @DisplayName("every shell.js menu page is in the AI Search bar's DESTS (with a description) and has a Help Center article")
    void inventoryComplete() throws IOException {
        Set<String> hrefs = menuHrefs();
        assertThat(hrefs).hasSizeGreaterThanOrEqualTo(35);
        String js = read("src/main/resources/static/ai-assistant.js");
        String dests = js.substring(js.indexOf("var DESTS = ["), js.indexOf("];", js.indexOf("var DESTS = [")));
        HelpContentCatalog catalog = new HelpContentCatalog();
        for (String href : hrefs) {
            assertThat(dests).as("DESTS entry for " + href).contains("target:'" + href + "'");
            String article = MENU_TO_ARTICLE.get(href);
            assertThat(article).as("MENU_TO_ARTICLE has no article for menu page " + href).isNotNull();
            assertThat(catalog.byId(article)).as("help article " + article + " for " + href).isNotNull();
        }
        // Every DESTS entry carries a description the model can explain from.
        Matcher e = Pattern.compile("\\{ label:'([^']+)'").matcher(dests);
        int n = 0;
        while (e.find()) {
            n++;
            int start = e.start(), next = e.end() < dests.length() ? dests.indexOf("{ label:", e.end()) : -1, end = next < 0 ? dests.length() : next;
            assertThat(dests.substring(start, end)).as("desc for " + e.group(1)).contains("desc:");
        }
        assertThat(n).isGreaterThanOrEqualTo(60);
        assertThat(dests).contains("label:'Ticketing'", "label:'AI Assistant'", "label:'Donation/Give'", "label:'Bank Sync'", "label:'Song Book'", "label:'Attendance'");
    }

    @Test @DisplayName("AI Search context carries allFeatures with availability; the prompt explains unavailable features instead of denying their existence")
    void aiSearchPrompt() throws IOException {
        String js = read("src/main/resources/static/ai-assistant.js");
        assertThat(js).contains("allFeatures:all", "available:isAvailable(d)")
                .contains("if (perms && perms[d.perm] === false) return false;")
                .contains("if (d.optIn && role === 'Member' && !(perms && perms[d.perm] === true)) return false;");
        // The local resolver answers "What is X?" from the catalogue instead of treating it as navigation.
        assertThat(js).contains("explain:explain", "if (c.explain && !c.where){", "intent:'explain'")
                .contains("is not enabled for your current role, permissions or plan — an administrator can enable it.")
                .contains("pages: avail && !d.dynamic ?");
        String svc = read("src/main/java/com/churchgeniuspro/service/OpenAiVoiceService.java");
        assertThat(svc).contains("allFeatures (array of {label,url,category,description,available}")
                .contains("never say it does not exist or is not a feature")
                .contains("Only put pages in relevantPages, and only suggest opening pages, that appear in availablePages/reports")
                .doesNotContain("say it is not available for their current account and leave relevantPages empty");
    }

    @Test @DisplayName("Help assistant (keyword fallback): a feature the login cannot use is explained and marked restricted, with no steps or link; an allowed one gets steps and a link")
    void keywordFallbackRespectsPermissions() {
        OpenAiVoiceService openAi = mock(OpenAiVoiceService.class);
        when(openAi.isEnabled()).thenReturn(false);
        HelpAssistantService svc = new HelpAssistantService(new HelpContentCatalog(), openAi, mock(HelpAuditLogRepository.class));
        Map<String, Object> denied = svc.assist("{\"more.ticketing\":false}", "User", false, "What is ticketing?", "", false, "C1", "u");
        assertThat(String.valueOf(denied.get("answer"))).contains("support request").contains("not enabled for your account").doesNotContain("does not exist");
        assertThat(denied).containsEntry("restricted", true);
        assertThat((List<?>) denied.get("steps")).isEmpty();
        assertThat((List<?>) denied.get("articles")).isEmpty();
        Map<String, Object> ok = svc.assist(null, "Admin", false, "What is ticketing?", "", false, "C1", "u");
        assertThat(String.valueOf(ok.get("answer"))).contains("support request").doesNotContain("not enabled");
        assertThat((List<?>) ok.get("steps")).isNotEmpty();
        assertThat((List<Map<String, Object>>) ok.get("articles")).singleElement().satisfies(a -> assertThat(a).containsEntry("id", "ticketing"));
    }

    @Test @DisplayName("Help assistant (AI): the whole catalogue is sent with accessible flags; article links only for accessible ones; restricted echoed")
    void aiGrounding() throws Exception {
        OpenAiVoiceService openAi = mock(OpenAiVoiceService.class);
        when(openAi.isEnabled()).thenReturn(true);
        when(openAi.chatJson(anyString(), anyString())).thenReturn(new ObjectMapper().readTree(
                "{\"answer\":\"Ticketing sends a support request. It is not enabled for your account.\",\"steps\":[],\"troubleshooting\":[],\"articleIds\":[\"ticketing\",\"members\"],\"category\":\"Support\",\"followUpQuestion\":null,\"restricted\":true}"));
        HelpAssistantService svc = new HelpAssistantService(new HelpContentCatalog(), openAi, mock(HelpAuditLogRepository.class));
        Map<String, Object> r = svc.assist("{\"more.ticketing\":false}", "User", false, "What is ticketing?", "", false, "C1", "u");
        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(openAi).chatJson(sys.capture(), anyString());
        assertThat(sys.getValue()).contains("[accessible=false] ARTICLE id=ticketing", "[accessible=true] ARTICLE id=members", "[accessible=true] ARTICLE id=plans_trial")
                .contains("never say such a feature does not exist").doesNotContain("reply that they do not have access to it");
        assertThat(r).containsEntry("restricted", true);
        assertThat((List<Map<String, Object>>) r.get("articles")).extracting(a -> a.get("id")).containsExactly("members");   // ticketing link withheld
    }

    @Test @DisplayName("Help Center page cards cover the newer features too")
    void helpCenterCards() throws IOException {
        String page = read("src/main/resources/static/helpCenter.html");
        for (String id : List.of("ticketing", "donation-give", "membership-requests", "stripe-integration", "email-settings", "private-page-access", "temporary-access", "favorites", "users-permissions", "pledges", "install-app", "ai-assistant"))
            assertThat(page).as("HC_KB card " + id).contains("id: '" + id + "'");
    }
}
