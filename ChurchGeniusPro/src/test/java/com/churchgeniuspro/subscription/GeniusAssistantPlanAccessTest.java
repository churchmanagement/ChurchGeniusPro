package com.churchgeniuspro.subscription;

import com.churchgeniuspro.config.SubscriptionPlanSeeder;
import com.churchgeniuspro.controller.SessionController;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.SubscriptionUsageRepository;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import com.churchgeniuspro.webfilter.SubscriptionFeatureFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Genius Assistant by plan (2026-11-11, user spec): Free — no assistant; Standard — text only (no Voice, no
 * Converse); Pro and Trial — text, Voice and Converse. Plans come from the REAL seeder; the checks go through the
 * REAL SubscriptionService, the REAL plan filter (the backend refusal for every other route) and /api/session
 * (what the client draws).
 */
@DisplayName("Genius Assistant — access by subscription plan")
class GeniusAssistantPlanAccessTest {

    final List<SubscriptionPlan> plans = new ArrayList<>();
    SubscriptionService subs;
    ServiceClientRepository clients;

    @BeforeEach
    void setUp() throws Exception {
        SubscriptionPlanRepository repo = mock(SubscriptionPlanRepository.class);
        when(repo.existsByPlanCodeIgnoreCase(anyString())).thenAnswer(i -> find(i.getArgument(0)).isPresent());
        when(repo.findByPlanCodeIgnoreCase(anyString())).thenAnswer(i -> find(i.getArgument(0)));
        when(repo.findAll()).thenAnswer(i -> new ArrayList<>(plans));
        when(repo.save(any())).thenAnswer(i -> { SubscriptionPlan p = i.getArgument(0); if (!plans.contains(p)) plans.add(p); return p; });
        Method m = SubscriptionPlanSeeder.class.getDeclaredMethod("seedSubscriptionPlans", SubscriptionPlanRepository.class);
        m.setAccessible(true);
        ((ApplicationRunner) m.invoke(new SubscriptionPlanSeeder(), repo)).run(null);
        assertThat(plans).extracting(SubscriptionPlan::getPlanCode).contains("FREE", "STANDARD", "PRO", "TRIAL");

        clients = mock(ServiceClientRepository.class);
        for (String plan : List.of("FREE", "STANDARD", "PRO", "TRIAL")) {
            ServiceClient sc = new ServiceClient(); sc.setClientId("CHR-" + plan); sc.setSubscriptionType(plan);
            when(clients.findByClientId("CHR-" + plan)).thenReturn(Optional.of(sc));
        }
        subs = new SubscriptionService(clients, repo, mock(SubscriptionUsageRepository.class));
        MessagingPolicy mp = mock(MessagingPolicy.class);
        when(mp.trialState(anyString())).thenAnswer(i -> "CHR-TRIAL".equals(i.getArgument(0)));
        subs.setMessagingPolicy(mp);
    }

    Optional<SubscriptionPlan> find(String code) { return plans.stream().filter(p -> p.getPlanCode().equalsIgnoreCase(code)).findFirst(); }

    MockHttpSession staff(String plan, String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", "CHR-" + plan); s.setAttribute("appClientId", "CHR-" + plan);
        s.setAttribute("username", "admin@" + plan.toLowerCase() + ".test"); s.setAttribute("role", role);
        s.setAttribute("church", false);
        return s;
    }

    int filterStatus(String plan, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRequestURI(path); req.setSession(staff(plan, "Admin"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new SubscriptionFeatureFilter(subs).doFilter(req, res, chain);
        return chain.getRequest() != null ? 200 : res.getStatus();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> session(String plan, String role) {
        SessionController c = new SessionController(mock(AppUserRepository.class));
        c.setSubscriptionService(subs);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/session");
        req.setSession(staff(plan, role));
        return c.getSession(req).getBody();
    }

    @ParameterizedTest(name = "{0}: assistant={1} voice={2} converse={3}")
    @CsvSource({ "FREE,false,false,false", "STANDARD,true,false,false", "PRO,true,true,true", "TRIAL,true,true,true" })
    @DisplayName("/api/session tells the client exactly what each plan may draw")
    void sessionFlags(String plan, boolean assistant, boolean voice, boolean converse) {
        for (String role : List.of("SuperAdmin", "Admin", "Accountant", "User")) {
            Map<String, Object> s = session(plan, role);
            assertThat(s.get("aiAssistant")).as(plan + " " + role).isEqualTo(assistant);
            assertThat(s.get("aiVoice")).as(plan + " voice").isEqualTo(voice);
            assertThat(s.get("aiConverse")).as(plan + " converse").isEqualTo(converse);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "FREE,false,false,false", "STANDARD,true,false,false", "PRO,true,true,true", "TRIAL,true,true,true" })
    @DisplayName("backend: every route is refused by the plan filter when the plan lacks it (no UI involved)")
    void backendRoutes(String plan, boolean text, boolean voice, boolean converse) throws Exception {
        for (String p : List.of("/api/ai-search/data", "/api/ai-search", "/api/ai-search/confirm", "/ai-assistant", "/ai-assistant.html"))
            assertThat(filterStatus(plan, p)).as(plan + " " + p).isEqualTo(text ? 200 : 403);
        for (String p : List.of("/api/voice/command", "/api/voice/extract", "/api/voice/status"))
            assertThat(filterStatus(plan, p)).as(plan + " " + p).isEqualTo(voice ? 200 : 403);
        for (String p : List.of("/api/ai-assist", "/api/help/assist"))
            assertThat(filterStatus(plan, p)).as(plan + " " + p).isEqualTo(converse ? 200 : 403);
    }

    @Test
    @DisplayName("a role without the Genius Assistant permission stays refused on every plan; Pro permission rules unchanged")
    void permissionStillApplies() {
        MockHttpSession s = staff("PRO", "Admin");
        s.setAttribute("privileges", "{\"more.aiassistant\":false}");
        SessionController c = new SessionController(mock(AppUserRepository.class));
        c.setSubscriptionService(subs);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/session"); req.setSession(s);
        assertThat(c.getSession(req).getBody().get("aiAssistant")).isEqualTo(false);
    }

    @Test
    @DisplayName("routes map to the features the plans switch")
    void catalog() {
        assertThat(SubscriptionFeatureCatalog.keyForPath("/ai-assistant")).isEqualTo("aiSearch");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/voice/command")).isEqualTo("aiVoice");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/ai-assist")).isEqualTo("aiConverse");
    }

    @Test
    @DisplayName("client: the engine and the panel follow the server's flags (no button, and no route around it)")
    void clientWiring() throws Exception {
        String eng = Files.readString(Path.of("src/main/resources/static/ai-assistant.js"));
        assertThat(eng).contains("session.voice=(s.aiVoice!==false);", "session.converse=(s.aiConverse!==false);",
                "if (!modeAllowed(m)){ toast(MODE_OFF_MSG); m='type'; opts.silent=true; }",
                "function canVoice(){ return !!window.VoiceOpenAI && session.voice!==false; }",
                "modes:function(){ return { voice:modeAllowed('voice'), converse:modeAllowed('converse') }; }");
        String chat = Files.readString(Path.of("src/main/resources/static/ai-chat.js"));
        assertThat(chat).contains("applyModes();", "['gaMic', m.voice !== false], ['gaConv', m.converse !== false]");
        assertThat(Files.readString(Path.of("src/main/resources/static/session.js"))).contains("'/ai-assistant': 'aiSearch'");
    }
}
