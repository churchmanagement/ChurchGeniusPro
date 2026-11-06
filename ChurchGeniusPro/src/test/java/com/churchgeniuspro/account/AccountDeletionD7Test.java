package com.churchgeniuspro.account;

import com.churchgeniuspro.controller.AccountPrivacyController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchDeletionRequest;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.PersonalAccessToken;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.PasswordUtil;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("D7 — staff login deletion and whole-church deletion")
class AccountDeletionD7Test {

    static final String CID = "CHR-TEST1", REF = "USR-abc", PW = "Correct-horse-1";

    // ── staff login ─────────────────────────────────────────────────────────

    @Nested @DisplayName("Delete my login (staff)")
    class Staff {
        LoginRepository logins = mock(LoginRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        UserPermissionsRepository perms = mock(UserPermissionsRepository.class);
        PersonalAccessTokenRepository tokens = mock(PersonalAccessTokenRepository.class);
        PushSubscriptionRepository push = mock(PushSubscriptionRepository.class);
        AiConsentRepository consents = mock(AiConsentRepository.class);
        UserFavoriteRepository favs = mock(UserFavoriteRepository.class);
        DashboardPreferenceRepository dash = mock(DashboardPreferenceRepository.class);
        TrialTipStateRepository tips = mock(TrialTipStateRepository.class);
        RememberMeService remember = mock(RememberMeService.class);
        PublicSubmissionNotificationService notes = mock(PublicSubmissionNotificationService.class);
        EmailService email = mock(EmailService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StaffAccountDeletionService svc = new StaffAccountDeletionService(logins, users, perms, tokens, push, consents,
                favs, dash, tips, remember, notes, email, jdbc);
        AppUser user; SignUp login; PersonalAccessToken tok;

        @BeforeEach void setUp() {
            user = new AppUser();
            user.setId(7); user.setUserId(REF); user.setClientId(CID); user.setRole("Admin");
            user.setFirstName("Ruth"); user.setLastName("Treasurer"); user.setEmail("ruth@example.org");
            user.setPhone("913-555-0100"); user.setAddress1("1 Main"); user.setCity("Olathe"); user.setEnabled(true);
            login = new SignUp();
            login.setId(55); login.setClientId(REF); login.setUsername("ruth"); login.setPassword(PasswordUtil.encode(PW));
            login.setActive(true); login.setDeleted(false);
            tok = new PersonalAccessToken();
            when(users.findByIdAndClientId(7, CID)).thenReturn(Optional.of(user));
            when(logins.findByClientId(REF)).thenReturn(Optional.of(login));
            when(tokens.findByAppClientIdAndAppUserIdOrderByCreatedAtDesc(CID, 7)).thenReturn(List.of(tok));
            when(push.findByUserKeyAndActiveTrue(REF)).thenReturn(List.of());
            when(consents.findByAppClientIdAndLoginId(CID, 55)).thenReturn(List.of());
            when(perms.findByAppUserId(7)).thenReturn(Optional.empty());
            when(dash.findByUsernameAndClientId(any(), any())).thenReturn(Optional.empty());
        }

        @Test @DisplayName("deletes the login, erases contact details, revokes tokens and devices, tells the church")
        void deletes() {
            StaffAccountDeletionService.Outcome o = svc.deleteOwnLogin(REF, 7, CID, PW);
            assertThat(o.ok()).isTrue();
            assertThat(o.email()).isEqualTo("ruth@example.org");
            assertThat(login.getDeleted()).isTrue();
            assertThat(login.getActive()).isFalse();
            assertThat(login.getPassword()).isEmpty();
            assertThat(login.getUsername()).startsWith("deleted-55-");
            assertThat(login.getClientId()).isEqualTo(REF + "#deleted-55");
            assertThat(user.isDeleteFlag()).isTrue();
            assertThat(user.isEnabled()).isFalse();
            assertThat(user.getEmail()).isEqualTo("deleted-7@deleted.invalid");
            assertThat(user.getPhone()).isNull();
            assertThat(user.getAddress1()).isNull();
            assertThat(user.getCity()).isNull();
            assertThat(user.getFirstName()).isEqualTo("Ruth");           // kept for history
            assertThat(tok.getRevokedAt()).isNotNull();
            verify(remember).revokeAllFor(55);
            verify(notes).record(eq(CID), eq(PublicSubmissionNotificationService.Type.ACCOUNT_DELETION), any(), any(), any());
        }

        @Test @DisplayName("refuses a wrong password, another church's user, a mismatched login and the last SuperAdmin")
        void refusals() {
            assertThat(svc.deleteOwnLogin(REF, 7, CID, "wrong").code()).isEqualTo("BAD_PASSWORD");
            assertThat(svc.deleteOwnLogin(REF, 7, "CHR-OTHER", PW).status()).isEqualTo(404);
            assertThat(svc.deleteOwnLogin("USR-someone-else", 7, CID, PW).status()).isEqualTo(404);
            user.setRole("SuperAdmin");
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
            assertThat(svc.deleteOwnLogin(REF, 7, CID, PW).code()).isEqualTo("LAST_SUPERADMIN");
            assertThat(login.getDeleted()).isFalse();
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
            assertThat(svc.deleteOwnLogin(REF, 7, CID, PW).ok()).isTrue();
        }
    }

    // ── whole church ───────────────────────────────────────────────────────

    @Nested @DisplayName("Delete the whole church")
    class Church {
        ChurchDeletionRequestRepository requests = mock(ChurchDeletionRequestRepository.class);
        ChurchRegistrationRepository churches = mock(ChurchRegistrationRepository.class);
        LoginRepository logins = mock(LoginRepository.class);
        TenantPurgePlanner planner = mock(TenantPurgePlanner.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        EmailService email = mock(EmailService.class);
        ChurchDeletionService svc = new ChurchDeletionService(requests, churches, logins, planner, jdbc, tx, email);
        List<ChurchDeletionRequest> saved = new ArrayList<>();

        @BeforeEach @SuppressWarnings("unchecked") void setUp() {
            ChurchRegistration c = new ChurchRegistration();
            c.setChurchName("Grace  Community Church"); c.setEmail("office@grace.example");
            when(churches.findByClientIdAndDeleteFlagFalse(CID)).thenReturn(Optional.of(c));
            SignUp login = new SignUp(); login.setId(9); login.setPassword(PasswordUtil.encode(PW)); login.setDeleted(false);
            when(logins.findById(9)).thenReturn(Optional.of(login));
            when(requests.findFirstByChurchClientIdAndStatusInOrderByIdDesc(eq(CID), any())).thenReturn(Optional.empty());
            when(requests.save(any())).thenAnswer(i -> { ChurchDeletionRequest r = i.getArgument(0); if (r.getId() == null) r.setId(1L); saved.add(r); return r; });
            when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
        }

        String linkToken() {
            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(email).sendAccountEmail(eq("office@grace.example"), anyString(), html.capture(), eq(CID));
            String h = html.getValue();
            int i = h.indexOf("token=");
            return h.substring(i + 6, h.indexOf('"', i));
        }

        @Test @DisplayName("request checks password + name, mails the REGISTERED address, stores only the token hash")
        void request() {
            assertThat(svc.request(CID, 9, "pastor", "SuperAdmin", "nope", "Grace Community Church").code()).isEqualTo("BAD_PASSWORD");
            assertThat(svc.request(CID, 9, "pastor", "SuperAdmin", PW, "Grace Church").code()).isEqualTo("NAME_MISMATCH");
            assertThat(svc.request(CID, 99, "pastor", "SuperAdmin", PW, "x").code()).isEqualTo("SIGN_IN_AGAIN");
            ChurchDeletionService.Outcome o = svc.request(CID, 9, "pastor", "SuperAdmin", PW, " grace community   CHURCH ");
            assertThat(o.ok()).isTrue();
            assertThat(o.message()).contains("o***@grace.example");
            ChurchDeletionRequest r = o.request();
            assertThat(r.getStatus()).isEqualTo("REQUESTED");
            assertThat(r.getNotifyEmail()).isEqualTo("office@grace.example");
            String token = linkToken();
            assertThat(token).hasSizeGreaterThan(40);
            assertThat(r.getTokenHash()).hasSize(64).isNotEqualTo(token);
            assertThat(r.getTokenExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        }

        @Test @DisplayName("an open request blocks a second one")
        void secondRequest() {
            ChurchDeletionRequest open = new ChurchDeletionRequest();
            open.setStatus("VERIFIED"); open.setChurchClientId(CID);
            when(requests.findFirstByChurchClientIdAndStatusInOrderByIdDesc(eq(CID), any())).thenReturn(Optional.of(open));
            assertThat(svc.request(CID, 9, "pastor", "SuperAdmin", PW, "Grace Community Church").code()).isEqualTo("ALREADY_REQUESTED");
        }

        @Test @DisplayName("verify: one use, expiry, unknown token")
        void verifyLink() {
            svc.request(CID, 9, "pastor", "SuperAdmin", PW, "Grace Community Church");
            String token = linkToken();
            ChurchDeletionRequest r = saved.get(0);
            when(requests.findByTokenHash(r.getTokenHash())).thenReturn(Optional.of(r));
            assertThat(svc.verify("made-up").ok()).isFalse();
            assertThat(svc.verify(token).ok()).isTrue();
            assertThat(r.getStatus()).isEqualTo("VERIFIED");
            assertThat(r.getTokenHash()).isNull();
            assertThat(svc.verify(token).ok()).isFalse();

            ChurchDeletionRequest old = new ChurchDeletionRequest();
            old.setStatus("REQUESTED"); old.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
            when(requests.findByTokenHash(anyString())).thenReturn(Optional.of(old));
            assertThat(svc.verify("expired-token").code()).isEqualTo("EXPIRED");
            assertThat(old.getStatus()).isEqualTo("EXPIRED");
        }

        @Test @DisplayName("execute: only VERIFIED, only with the typed client id; purges with the billing tables kept")
        void execute() {
            ChurchDeletionRequest r = new ChurchDeletionRequest();
            r.setId(5L); r.setChurchClientId(CID); r.setChurchName("Grace"); r.setStatus("REQUESTED"); r.setNotifyEmail("office@grace.example");
            when(requests.findById(5L)).thenReturn(Optional.of(r));
            assertThat(svc.execute(5L, "service-admin:sam", CID).code()).isEqualTo("NOT_VERIFIED");
            r.setStatus("VERIFIED");
            assertThat(svc.execute(5L, "service-admin:sam", "CHR-WRONG").code()).isEqualTo("CONFIRM");
            verifyNoInteractions(planner);
            when(planner.purgeChurch(jdbc, CID, ChurchDeletionService.KEEP)).thenReturn(Map.of("family", 3, "income", 4));
            ChurchDeletionService.Outcome o = svc.execute(5L, "service-admin:sam", CID);
            assertThat(o.ok()).isTrue();
            assertThat(r.getStatus()).isEqualTo("DELETED");
            assertThat(r.getDeletedRows()).isEqualTo(7);
            verify(jdbc).update("UPDATE service_client SET delete_flag = true, status = 'DELETED' WHERE client_id = ?", CID);
            assertThat(ChurchDeletionService.KEEP).contains("billing_invoice", "billing_invoice_line", "billing_payment_event",
                    "client_charge", "subscription_change", "service_client");
            assertThat(svc.execute(5L, "service-admin:sam", CID).code()).isEqualTo("NOT_VERIFIED");   // once only
        }

        @Test @DisplayName("the request table is never purged with the church")
        void requestTableSurvivesPurge() throws Exception {
            Field f = ChurchDeletionRequest.class.getDeclaredField("churchClientId");
            assertThat(f.getAnnotation(jakarta.persistence.Column.class).name()).isEqualTo("church_client_id");
            for (Field x : ChurchDeletionRequest.class.getDeclaredFields()) {
                jakarta.persistence.Column c = x.getAnnotation(jakarta.persistence.Column.class);
                if (c != null) assertThat(c.name()).isNotIn("client_id", "app_client_id");
            }
        }
    }

    // ── who may use the page ───────────────────────────────────────────────

    @Nested @DisplayName("Account & Privacy API gates")
    class Gates {
        StaffAccountDeletionService staff = mock(StaffAccountDeletionService.class);
        ChurchDeletionService church = mock(ChurchDeletionService.class);
        AccountPrivacyController c = new AccountPrivacyController(staff, church, mock(RememberMeService.class),
                mock(SecurityAuditService.class), mock(PublicSendLimiter.class));

        MockHttpServletRequest req(Map<String, Object> attrs) {
            MockHttpServletRequest r = new MockHttpServletRequest();
            MockHttpSession s = new MockHttpSession();
            attrs.forEach(s::setAttribute);
            r.setSession(s);
            return r;
        }
        Map<String, Object> staffAttrs(String role) {
            Map<String, Object> m = new HashMap<>(Map.of("username", "ruth", "role", role, "appClientId", CID,
                    "appUserId", 7, "clientId", REF, "loginId", 55, "church", false));
            return m;
        }

        @Test @DisplayName("staff and church get the page data; member, temp-access, NTAG, API token and anonymous do not")
        void gates() {
            assertThat(c.status(req(staffAttrs("Admin"))).getBody()).containsEntry("kind", "STAFF")
                    .containsEntry("canDeleteLogin", true).containsEntry("canRequestChurchDeletion", false);
            assertThat(c.status(req(staffAttrs("SuperAdmin"))).getBody()).containsEntry("canRequestChurchDeletion", true);
            assertThat(c.status(req(Map.of("username", "owner", "church", true, "role", "church", "clientId", CID, "loginId", 1))).getBody())
                    .containsEntry("kind", "CHURCH").containsEntry("canDeleteLogin", false).containsEntry("canRequestChurchDeletion", true);
            Map<String, Object> temp = staffAttrs("User"); temp.put("tempAccessId", 3L);
            Map<String, Object> ntag = staffAttrs("User"); ntag.put("ntagCredId", 3L);
            Map<String, Object> token = staffAttrs("Admin"); token.put("apiToken", true);
            Map<String, Object> member = new HashMap<>(Map.of("username", "jane", "role", "Member", "memberId", 4, "appClientId", CID));
            for (Map<String, Object> a : List.of(temp, ntag, token, member)) {
                assertThat(c.status(req(a)).getStatusCode().value()).as(a.toString()).isEqualTo(403);
            }
            assertThat(c.status(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
        }

        @Test @DisplayName("church login cannot use Delete my login; Admin cannot request church deletion; confirm box required")
        void refusals() {
            Map<String, Object> body = new HashMap<>(Map.of("password", PW, "confirm", true));
            ResponseEntity<Map<String, Object>> r1 = c.deleteStaff(body,
                    req(Map.of("username", "owner", "church", true, "role", "church", "clientId", CID)), new MockHttpServletResponse());
            assertThat(r1.getStatusCode().value()).isEqualTo(403);
            assertThat(c.requestChurch(new HashMap<>(Map.of("password", PW, "churchName", "x", "confirm", true)),
                    req(staffAttrs("Admin"))).getStatusCode().value()).isEqualTo(403);
            assertThat(c.deleteStaff(new HashMap<>(Map.of("password", PW)), req(staffAttrs("Admin")), new MockHttpServletResponse())
                    .getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(staff);
            verify(church, never()).request(any(), any(), any(), any(), any(), any());
        }

        @Test @DisplayName("a successful staff deletion ends the session")
        void staffSessionEnds() {
            when(staff.deleteOwnLogin(REF, 7, CID, PW)).thenReturn(new StaffAccountDeletionService.Outcome(true, 200, null, null, "r@x.org", "Ruth"));
            MockHttpServletRequest r = req(staffAttrs("Admin"));
            MockHttpSession s = (MockHttpSession) r.getSession(false);
            assertThat(c.deleteStaff(new HashMap<>(Map.of("password", PW, "confirm", true)), r, new MockHttpServletResponse())
                    .getStatusCode().value()).isEqualTo(200);
            assertThat(s.isInvalid()).isTrue();
        }

        @Test @DisplayName("service admin endpoints need the service admin session")
        void serviceAdmin() {
            assertThat(c.adminList(req(staffAttrs("SuperAdmin"))).getStatusCode().value()).isEqualTo(401);
            assertThat(c.adminDelete(1L, Map.of("clientId", CID), req(staffAttrs("SuperAdmin"))).getStatusCode().value()).isEqualTo(401);
            when(church.list()).thenReturn(List.of());
            assertThat(c.adminList(req(Map.of("serviceAdminId", 1, "serviceAdminUsername", "sam"))).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test @DisplayName("page, nav, footer, public verify page and Service Admin card are wired")
    void wiring() throws Exception {
        String shell = Files.readString(Path.of("src/main/resources/static/shell.js"));
        assertThat(shell).contains("href: '/accountPrivacy', churchOnly: true").contains("id: 'more/account-privacy'")
                .contains("https://www.churchgeniuspro.com/privacy");
        assertThat(Files.readString(Path.of("src/main/resources/static/session.js"))).contains("isAcct");
        assertThat(Files.readString(Path.of("src/main/resources/static/accountPrivacy.html")))
                .contains("/api/account/staff/delete").contains("/api/account/church-deletion").contains("Privacy Policy");
        assertThat(Files.readString(Path.of("src/main/resources/static/churchDeletionVerify.html")))
                .contains("/api/public/church-deletion/verify").contains("history.replaceState");
        assertThat(Files.readString(Path.of("src/main/resources/static/serviceadminhome.html")))
                .contains("/api/serviceadmin/church-deletions");
        assertThat(Files.readString(Path.of("src/main/resources/static/memberHome.html"))).contains("mbrPrivacyLinks");
        assertThat(Files.readString(Path.of("src/main/resources/static/accountDeletion.html")))
                .contains("Delete my login").contains("Delete the whole church account");
        assertThat(Files.readString(Path.of("scripts/sql/migrate_production.sql")))
                .contains("CREATE TABLE IF NOT EXISTS church_deletion_request");
    }
}
