package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.DeviceSessionController;
import com.churchgeniuspro.controller.LoginController;
import com.churchgeniuspro.hibernate.RememberMeToken;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.RememberMeService;
import com.churchgeniuspro.service.SecurityAuditService;
import com.churchgeniuspro.util.PasswordUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mobile Phase 6 through the real endpoints: the app shell's device secret
 * ({@code /login} → {@code deviceToken}, {@code /api/auth/device-login}, logout) and
 * the "Your devices" endpoints under {@code /api/auth/devices}.
 */
class DeviceLoginWebTest {

    private static final String PASSWORD = "CorrectHorse7";
    private static final String HASH     = PasswordUtil.encode(PASSWORD);
    private static final String APP_UA   = "Mozilla/5.0 (Linux; Android 14; Pixel 8) Chrome/120 Mobile ChurchGeniusProApp/1.0 (android)";
    private static final String WEB_UA   = "Mozilla/5.0 (Windows NT 10.0) Chrome/130";
    private static final ObjectMapper JSON = new ObjectMapper();

    private InMemoryRememberMeTokens store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LoginRepository loginRepo = mock(LoginRepository.class);
        store = new InMemoryRememberMeTokens(loginRepo);

        SignUp member = store.signup(7, "member@example.org", "MBR-token-1");
        member.setPassword(HASH);
        member.setChurch(false);
        when(loginRepo.findByUsernameAndDeletedFalse("member@example.org")).thenReturn(Optional.of(member));
        when(loginRepo.findActiveByUsername("member@example.org")).thenReturn(Optional.of(member));

        FamilyMemberRepository familyRepo = mock(FamilyMemberRepository.class);
        when(familyRepo.findByMemberRef(anyString())).thenReturn(Optional.empty());
        when(familyRepo.findActiveByEmail(anyString())).thenReturn(List.of());

        LoginProtectionService protection = mock(LoginProtectionService.class);
        when(protection.check(any(), anyString())).thenReturn(new LoginProtectionService.GuardResult(false, 0L, false));

        RememberMeService rememberMe = new RememberMeService(store.tokens, store.loginRepo);
        LoginController login = new LoginController(
                loginRepo, mock(AppUserRepository.class), mock(ChurchRegistrationRepository.class),
                mock(ServiceClientRepository.class), mock(UserPermissionsRepository.class), familyRepo,
                protection, mock(SecurityAuditService.class), mock(com.churchgeniuspro.service.DemoAccessService.class),
                rememberMe, mock(PushSubscriptionRepository.class));
        DeviceSessionController devices = new DeviceSessionController(rememberMe, loginRepo);
        mvc = MockMvcBuilders.standaloneSetup(login, devices).build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MvcResult login(String ua, boolean rememberMe, String deviceName) throws Exception {
        String body = "{\"username\":\"member@example.org\",\"password\":\"" + PASSWORD + "\",\"rememberMe\":" + rememberMe
                + (deviceName == null ? "" : ",\"deviceName\":\"" + deviceName + "\",\"deviceStore\":true") + "}";
        return mvc.perform(post("/login").contentType("application/json").header("User-Agent", ua).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private static JsonNode json(MvcResult r) throws Exception { return JSON.readTree(r.getResponse().getContentAsString()); }

    private MvcResult deviceLogin(String ua, String token) throws Exception {
        return mvc.perform(post("/api/auth/device-login").contentType("application/json").header("User-Agent", ua)
                .content("{\"token\":\"" + token + "\"}")).andReturn();
    }

    private String appLoginToken() throws Exception {
        return json(login(APP_UA, true, "Pixel 8")).get("deviceToken").asText();
    }

    // ── device secret lifecycle ──────────────────────────────────────────────

    @Test
    @DisplayName("app + Remember me: a device secret in the body, NO remember cookie, hash-only row with platform/device name")
    void appLoginIssuesDeviceSecret() throws Exception {
        MvcResult r = login(APP_UA, true, "Pixel 8");
        String token = json(r).get("deviceToken").asText();
        assertThat(token).hasSizeGreaterThan(30);
        Cookie c = r.getResponse().getCookie(RememberMeService.COOKIE_NAME);
        assertThat(c == null || c.getMaxAge() == 0).as("no live remember cookie for the app").isTrue();

        RememberMeToken row = store.rows.values().iterator().next();
        assertThat(row.getTokenHash()).isEqualTo(RememberMeService.sha256Hex(token)).isNotEqualTo(token);
        assertThat(row.getPlatform()).isEqualTo("android");
        assertThat(row.getDeviceLabel()).isEqualTo("Pixel 8");
    }

    @Test
    @DisplayName("app WITHOUT deviceStore (1.0 build, or no lock screen) keeps the Phase 1 cookie — no deviceToken")
    void appWithoutSecureStoreFallsBackToCookie() throws Exception {
        MvcResult r = mvc.perform(post("/login").contentType("application/json").header("User-Agent", APP_UA)
                .content("{\"username\":\"member@example.org\",\"password\":\"" + PASSWORD + "\",\"rememberMe\":true,\"deviceName\":\"Pixel 8\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(r).has("deviceToken")).isFalse();
        Cookie c = r.getResponse().getCookie(RememberMeService.COOKIE_NAME);
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isGreaterThan(0);
        assertThat(store.rows.values().iterator().next().getPlatform()).isEqualTo("android");
    }

    @Test
    @DisplayName("browser + Remember me is unchanged: cookie set, no deviceToken in the body")
    void browserLoginUnchanged() throws Exception {
        MvcResult r = login(WEB_UA, true, null);
        assertThat(json(r).has("deviceToken")).isFalse();
        assertThat(r.getResponse().getCookie(RememberMeService.COOKIE_NAME)).isNotNull();
        assertThat(store.rows.values().iterator().next().getPlatform()).isEqualTo("web");
    }

    @Test
    @DisplayName("device-login signs in, rotates the secret, and the old value still works inside the grace window")
    void deviceLoginRotates() throws Exception {
        String t1 = appLoginToken();
        JsonNode r1 = json(deviceLogin(APP_UA, t1));
        assertThat(r1.get("status").asText()).isEqualTo("success");
        String t2 = r1.get("deviceToken").asText();
        assertThat(t2).isNotEqualTo(t1);
        assertThat(store.rows).hasSize(1);

        assertThat(json(deviceLogin(APP_UA, t1)).get("status").asText()).as("previous value, inside the grace window").isEqualTo("success");
        assertThat(json(deviceLogin(APP_UA, t2)).get("status").asText()).isEqualTo("success");
        assertThat(json(deviceLogin(APP_UA, t1)).get("status").asText()).as("two generations old → refused").isEqualTo("none");
    }

    @Test
    @DisplayName("unknown or revoked secret → none; a browser User-Agent cannot use device-login at all")
    void refused() throws Exception {
        assertThat(json(deviceLogin(APP_UA, "not-a-real-token")).get("status").asText()).isEqualTo("none");
        String t = appLoginToken();
        assertThat(json(deviceLogin(WEB_UA, t)).get("status").asText()).isEqualTo("none");
        mvc.perform(post("/api/auth/logout").contentType("application/json").header("User-Agent", APP_UA)
                .content("{\"deviceToken\":\"" + t + "\"}")).andExpect(status().isOk());
        assertThat(json(deviceLogin(APP_UA, t)).get("status").asText()).as("after logout").isEqualTo("none");
        assertThat(store.rows.values().iterator().next().getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("logout with the device secret revokes only that device; a remembered browser stays")
    void logoutIsDeviceScoped() throws Exception {
        login(WEB_UA, true, null);
        String t = appLoginToken();
        assertThat(store.rows).hasSize(2);
        mvc.perform(post("/api/auth/logout").contentType("application/json").header("User-Agent", APP_UA)
                .content("{\"deviceToken\":\"" + t + "\"}")).andExpect(status().isOk());
        long live = store.rows.values().stream().filter(x -> x.getRevokedAt() == null).count();
        assertThat(live).isEqualTo(1);
        assertThat(store.rows.values().stream().filter(x -> x.getRevokedAt() == null).findFirst().get().getPlatform()).isEqualTo("web");
    }

    // ── /api/auth/devices ────────────────────────────────────────────────────

    private MockHttpSession signedIn() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "member@example.org");
        s.setAttribute("clientId", "MBR-token-1");
        return s;
    }

    @Test
    @DisplayName("devices: 401 without a session; lists live rows with the current one marked")
    void listDevices() throws Exception {
        mvc.perform(get("/api/auth/devices")).andExpect(status().isUnauthorized());
        login(WEB_UA, true, null);
        String t = appLoginToken();
        JsonNode r = json(mvc.perform(get("/api/auth/devices").param("deviceToken", t).session(signedIn()))
                .andExpect(status().isOk()).andReturn());
        assertThat(r.get("devices")).hasSize(2);
        JsonNode app = r.get("devices").get(1);
        assertThat(app.get("platform").asText()).isEqualTo("android");
        assertThat(app.get("label").asText()).isEqualTo("Pixel 8");
        assertThat(app.get("current").asBoolean()).isTrue();
        assertThat(r.get("devices").get(0).get("current").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("revoke one device (own rows only) and revoke-others keeps the caller's row")
    void revokeDevices() throws Exception {
        login(WEB_UA, true, null);
        login(WEB_UA, true, null);
        String t = appLoginToken();
        List<Long> ids = store.rows.keySet().stream().sorted().toList();

        mvc.perform(delete("/api/auth/devices/" + ids.get(0)).session(signedIn())).andExpect(status().isOk());
        assertThat(store.rows.get(ids.get(0)).getRevokedAt()).isNotNull();
        mvc.perform(delete("/api/auth/devices/999").session(signedIn())).andExpect(status().isNotFound());

        // another login's row is invisible
        SignUp other = store.signup(8, "other@example.org", "MBR-other");
        RememberMeToken foreign = new RememberMeToken();
        foreign.setSignupId(other.getId()); foreign.setTokenHash("x"); foreign.setPlatform("web");
        foreign.setCreatedAt(java.time.Instant.now()); foreign.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
        store.tokens.save(foreign);
        mvc.perform(delete("/api/auth/devices/" + foreign.getId()).session(signedIn())).andExpect(status().isNotFound());
        assertThat(foreign.getRevokedAt()).isNull();

        JsonNode r = json(mvc.perform(post("/api/auth/devices/revoke-others").contentType("application/json")
                .content("{\"deviceToken\":\"" + t + "\"}").session(signedIn())).andExpect(status().isOk()).andReturn());
        assertThat(r.get("revoked").asInt()).isEqualTo(1);           // the second browser row
        assertThat(json(deviceLogin(APP_UA, t)).get("status").asText()).as("caller's own device kept").isEqualTo("success");
    }
}
