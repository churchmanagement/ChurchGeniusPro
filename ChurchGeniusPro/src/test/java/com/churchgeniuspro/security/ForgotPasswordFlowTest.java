package com.churchgeniuspro.security;

import com.churchgeniuspro.config.LoginProtectionProperties;
import com.churchgeniuspro.controller.ForgotPasswordController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.PasswordResetService;
import com.churchgeniuspro.service.RememberMeService;
import com.churchgeniuspro.util.PasswordUtil;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Forgot Password end to end through the real controller and the real PasswordResetService (2026-11-11): which
 * accounts get a link, the link itself, the stored token, delivery failures, rate limiting, and the reset.
 */
@DisplayName("Forgot Password — request, e-mail, link, reset")
class ForgotPasswordFlowTest {

    static final String GENERIC = "If an account with that username and email exists, a reset link has been sent.";

    LoginRepository logins; AppUserRepository appUsers; ChurchRegistrationRepository churches;
    FamilyMemberRepository members; ServiceClientRepository clients; EmailService email;
    final Map<String, PasswordResetToken> tokenTable = new LinkedHashMap<>();   // stored value → row
    final Map<String, SignUp> signups = new LinkedHashMap<>();
    MockMvc mvc; PublicSendLimiter limiter;

    @BeforeEach
    void setUp() throws Exception {
        logins = mock(LoginRepository.class); appUsers = mock(AppUserRepository.class); churches = mock(ChurchRegistrationRepository.class);
        members = mock(FamilyMemberRepository.class); clients = mock(ServiceClientRepository.class); email = mock(EmailService.class);
        when(logins.findActiveByUsername(anyString())).thenAnswer(i -> signups.values().stream()
                .filter(s -> s.getUsername().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(clients.isActiveSubscription(eq("CHR-1"), any())).thenReturn(true);
        when(members.findActiveByEmail(anyString())).thenReturn(List.of());
        when(email.getChurchName(anyString())).thenReturn("Grace Church");

        PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
        when(tokens.save(any())).thenAnswer(i -> { PasswordResetToken t = i.getArgument(0); if (t.getId() == null) t.setId((long) tokenTable.size() + 1); tokenTable.put(t.getToken(), t); return t; });
        when(tokens.findByToken(anyString())).thenAnswer(i -> Optional.ofNullable(tokenTable.get(i.getArgument(0))));
        when(tokens.findFirstByUsernameOrderByIdDesc(anyString())).thenAnswer(i -> tokenTable.values().stream()
                .filter(t -> t.getUsername().equals(i.getArgument(0))).reduce((a, b) -> b));
        doAnswer(i -> { tokenTable.values().removeIf(t -> t.getUsername().equals(i.getArgument(0))); return null; }).when(tokens).deleteByUsername(anyString());
        LoginProtectionProperties props = new LoginProtectionProperties();
        PasswordResetService resets = new PasswordResetService(tokens, props);
        LoginProtectionService protection = mock(LoginProtectionService.class);
        when(protection.check(any(), any())).thenReturn(new LoginProtectionService.GuardResult(false, 0L, false));

        ForgotPasswordController c = new ForgotPasswordController(logins, appUsers, churches, members, clients, tokens, email,
                resets, protection, mock(RememberMeService.class));
        ReflectionTestUtils.setField(c, "baseUrl", "https://churchgeniuspro.net");
        limiter = new PublicSendLimiter();
        ReflectionTestUtils.setField(c, "sendLimiter", limiter);
        mvc = MockMvcBuilders.standaloneSetup(c).build();
    }

    SignUp login(int id, String username, String clientId, boolean church) {
        SignUp s = new SignUp(); s.setId(id); s.setUsername(username); s.setClientId(clientId); s.setChurch(church);
        s.setActive(true); s.setDeleted(false); s.setPassword(PasswordUtil.encode("OldPassword1!"));
        signups.put(username, s); return s;
    }
    AppUser staff(String userId, String mail) {
        AppUser u = new AppUser(); u.setUserId(userId); u.setClientId("CHR-1"); u.setEmail(mail); u.setEnabled(true);
        when(appUsers.findByUserIdAndDeleteFlagFalse(userId)).thenReturn(Optional.of(u)); return u;
    }
    FamilyMember member(String ref, String role, String mail, Family fam) {
        FamilyMember m = new FamilyMember(); m.setMemberRef(ref); m.setRole(role); m.setEmail(mail); m.setFamily(fam);
        if (ref != null) when(members.findByMemberRef(ref)).thenReturn(Optional.of(m)); return m;
    }
    Family family(int id) { Family f = new Family(); f.setId(id); f.setAppClientId("CHR-1"); return f; }

    MvcResult request(String user, String mail) throws Exception {
        return mvc.perform(post("/api/forgot-password").contentType("application/json")
                .content("{\"username\":\"" + user + "\",\"email\":\"" + mail + "\"}")).andReturn();
    }
    String sentLink() throws Exception {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email, atLeastOnce()).sendAccountEmailOrThrow(anyString(), anyString(), body.capture(), any());
        Matcher m = Pattern.compile("href='(https://churchgeniuspro\\.net/resetPassword\\?token=[A-Za-z0-9_-]+)'").matcher(body.getValue());
        assertThat(m.find()).as("button link in the mail").isTrue();
        return m.group(1);
    }

    @Test @DisplayName("1-4: staff, church and member logins get a mail with a Reset button pointing at https://churchgeniuspro.net/resetPassword")
    void validRequestsSendTheLink() throws Exception {
        login(1, "pastor@grace.org", "USR-1", false); staff("USR-1", "Pastor@Grace.org");
        assertThat(request("PASTOR@grace.org", "pastor@grace.org").getResponse().getContentAsString()).contains(GENERIC);
        verify(email).sendAccountEmailOrThrow(eq("Pastor@Grace.org"), contains("Password Reset"), contains("Reset My Password"), isNull());
        assertThat(sentLink()).startsWith("https://churchgeniuspro.net/resetPassword?token=");

        login(2, "grace_church", "CHR-1", true);
        ChurchRegistration cr = new ChurchRegistration(); cr.setEmail(" office@grace.org "); cr.setChurchName("Grace Church");
        when(churches.findByClientIdAndDeleteFlagFalse("CHR-1")).thenReturn(Optional.of(cr));
        request("grace_church", "office@grace.org");
        verify(email).sendAccountEmailOrThrow(eq("office@grace.org"), anyString(), anyString(), isNull());   // stored spaces no longer break the match

        SignUp m = login(3, "mary@home.org", "MBR-3", false); m.setActive(null);   // NULL active signs in → can reset too
        member("MBR-3", "Spouse", "mary@home.org", family(10));
        request("mary@home.org", "mary@home.org");
        verify(email).sendAccountEmailOrThrow(eq("mary@home.org"), anyString(), anyString(), isNull());
    }

    @Test @DisplayName("members: Son/Daughter go to the Head of Household; a member whose member_ref was never filled is found by e-mail")
    void memberEdges() throws Exception {
        Family f = family(20);
        FamilyMember head = member(null, "Head of Household", "dad@home.org", f);
        when(members.findActiveMembersByFamilyId(20)).thenReturn(List.of(head));
        login(4, "son_login", "MBR-4", false); member("MBR-4", "Son", null, f);
        request("son_login", "dad@home.org");
        verify(email).sendAccountEmailOrThrow(eq("dad@home.org"), anyString(), anyString(), isNull());

        login(5, "lee@home.org", "MBR-5", false);              // no family_member.member_ref = MBR-5
        FamilyMember lee = member(null, "Adult", "lee@home.org", f);
        when(members.findActiveByEmail("lee@home.org")).thenReturn(List.of(lee));
        request("lee@home.org", "lee@home.org");
        verify(email).sendAccountEmailOrThrow(eq("lee@home.org"), anyString(), anyString(), isNull());
    }

    @Test @DisplayName("10: wrong e-mail, unknown user, inactive or expired account → no mail, and exactly the same response")
    void noEnumeration() throws Exception {
        login(1, "pastor@grace.org", "USR-1", false); staff("USR-1", "pastor@grace.org");
        SignUp off = login(2, "off@grace.org", "USR-2", false); staff("USR-2", "off@grace.org"); off.setActive(false);
        login(3, "lapsed@x.org", "USR-3", false); AppUser l = staff("USR-3", "lapsed@x.org"); l.setClientId("CHR-OLD");
        Set<String> bodies = new HashSet<>(); Set<Integer> codes = new HashSet<>();
        for (String[] r : new String[][] { {"pastor@grace.org", "other@x.org"}, {"nobody", "nobody@x.org"}, {"off@grace.org", "off@grace.org"}, {"lapsed@x.org", "lapsed@x.org"} }) {
            MvcResult res = request(r[0], r[1]); bodies.add(res.getResponse().getContentAsString()); codes.add(res.getResponse().getStatus());
        }
        assertThat(bodies).hasSize(1); assertThat(codes).containsExactly(200);
        verify(email, never()).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
        assertThat(tokenTable).isEmpty();
    }

    @Test @DisplayName("security: only the SHA-256 is stored; a delivery failure discards the token so the retry is not swallowed by the cooldown; the cooldown still stops floods")
    void tokenStorageAndDeliveryFailure() throws Exception {
        login(1, "pastor@grace.org", "USR-1", false); staff("USR-1", "pastor@grace.org");
        doThrow(new jakarta.mail.MessagingException("535 Authentication failed")).when(email)
                .sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
        assertThat(request("pastor@grace.org", "pastor@grace.org").getResponse().getContentAsString()).contains(GENERIC);
        assertThat(tokenTable).as("no orphan token after a failed send").isEmpty();

        reset(email);
        request("pastor@grace.org", "pastor@grace.org");
        String raw = sentLink().replaceAll(".*token=", "");
        assertThat(tokenTable).hasSize(1).doesNotContainKey(raw).containsKey(PasswordResetService.hash(raw));
        request("pastor@grace.org", "pastor@grace.org");          // within the 2-minute cooldown
        verify(email, times(1)).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
    }

    @Test @DisplayName("5-9: the link resets the password once; used, expired, made-up and stored-hash values are refused")
    void resetWithTheLink() throws Exception {
        SignUp s = login(1, "pastor@grace.org", "USR-1", false); staff("USR-1", "pastor@grace.org");
        request("pastor@grace.org", "pastor@grace.org");
        String raw = sentLink().replaceAll(".*token=", "");
        assertThat(mvc.perform(get("/api/reset-password/validate").param("token", raw)).andReturn().getResponse().getContentAsString()).contains("\"valid\":true");
        assertThat(mvc.perform(get("/api/reset-password/validate").param("token", PasswordResetService.hash(raw))).andReturn().getResponse().getContentAsString())
                .as("the stored value is not a usable link").contains("\"valid\":false");
        assertThat(resetWith(raw, "NewPassword2@")).isEqualTo(200);
        assertThat(PasswordUtil.matches("NewPassword2@", s.getPassword())).isTrue();
        assertThat(PasswordUtil.matches("OldPassword1!", s.getPassword())).isFalse();
        assertThat(s.getCredentialVersion()).as("other sessions end (credential version)").isEqualTo(1);
        assertThat(resetWith(raw, "Another3#Pass")).as("single use").isEqualTo(400);
        assertThat(resetWith("made-up-token", "Another3#Pass")).isEqualTo(400);

        tokenTable.clear(); reset(email);
        request("pastor@grace.org", "pastor@grace.org");
        String raw2 = sentLink().replaceAll(".*token=", "");
        tokenTable.values().forEach(t -> t.setExpiryTime(new Date(System.currentTimeMillis() - 1000)));
        assertThat(resetWith(raw2, "Another3#Pass")).as("expired").isEqualTo(400);
    }

    int resetWith(String token, String pw) throws Exception {
        return mvc.perform(post("/api/reset-password").contentType("application/json")
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + pw + "\"}")).andReturn().getResponse().getStatus();
    }

    @Test @DisplayName("rate limit: the 6th request from one IP inside 10 minutes is refused; the username is escaped in the mail")
    void rateLimitAndEscaping() throws Exception {
        for (int i = 0; i < 5; i++) assertThat(request("u" + i, "u@x.org").getResponse().getStatus()).isEqualTo(200);
        assertThat(request("u9", "u@x.org").getResponse().getStatus()).isEqualTo(429);
        limiter.setEnabled(false);
        login(1, "<b>x</b>", "USR-1", false); staff("USR-1", "x@grace.org");
        request("<b>x</b>", "x@grace.org");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).sendAccountEmailOrThrow(anyString(), anyString(), body.capture(), any());
        assertThat(body.getValue()).contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x</b>");
    }
}
