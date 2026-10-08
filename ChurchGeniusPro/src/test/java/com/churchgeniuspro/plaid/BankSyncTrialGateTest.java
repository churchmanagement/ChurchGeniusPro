package com.churchgeniuspro.plaid;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.plaid.entity.BankSyncVerification;
import com.churchgeniuspro.plaid.repository.BankSyncTrustedDeviceRepository;
import com.churchgeniuspro.plaid.repository.BankSyncVerificationRepository;
import com.churchgeniuspro.plaid.service.BankSyncGateService;
import com.churchgeniuspro.plaid.service.PlaidAuditService;
import com.churchgeniuspro.plaid.service.PlaidTokenCipher;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.MessagingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trial tenants skip the Bank Sync step-up gate; everyone else still passes it.
 *
 * <p>The gate is a security control, so the tests that matter most are the ones
 * asserting it is still CLOSED — for Standard, for Pro, for an unknown tenant,
 * and when the subscription lookup fails. Skipping is the narrow exception, not
 * the new default.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Bank Sync gate — Trial exemption")
class BankSyncTrialGateTest {

    private static final String TENANT  = "CHR-grace-01";
    private static final int    USER_ID = 51;

    @Mock private BankSyncVerificationRepository  verificationRepo;
    @Mock private BankSyncTrustedDeviceRepository deviceRepo;
    @Mock private PlaidTokenCipher                cipher;
    @Mock private EmailService                    emailService;
    @Mock private AppUserRepository               appUserRepository;
    @Mock private PlaidAuditService               audit;
    @Mock private MessagingPolicy                 messagingPolicy;

    private BankSyncGateService gate;

    @BeforeEach
    void setUp() {
        gate = new BankSyncGateService(verificationRepo, deviceRepo, cipher, emailService,
                                       appUserRepository, audit, messagingPolicy);
        AppUser u = new AppUser();
        u.setId(USER_ID);
        u.setClientId(TENANT);
        u.setEmail("treasurer@example.org");
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(verificationRepo.findByAppUserIdAndUsed(USER_ID, false)).thenReturn(List.of());
        when(cipher.encrypt(anyString())).thenReturn("enc");
    }

    /** A signed-in staff request for {@code TENANT}. */
    private MockHttpServletRequest staffRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/bankSync");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appUserId",   USER_ID);
        session.setAttribute("appClientId", TENANT);
        session.setAttribute("clientId",    TENANT);
        session.setAttribute("username",    "treasurer@example.org");
        req.setSession(session);
        return req;
    }

    private void subscription(boolean trial) {
        // Both spellings, because the real MessagingPolicy derives one from the
        // other: isTrial(id) is TRUE.equals(trialState(id)). The gate asks for the
        // three-valued answer so it can tell "not Trial" from "could not tell".
        when(messagingPolicy.isTrial(TENANT)).thenReturn(trial);
        when(messagingPolicy.trialState(TENANT)).thenReturn(trial);
    }

    /* ── Trial: the exemption ───────────────────────────────────────────── */

    @Test
    @DisplayName("Trial opens the page without verifying")
    void trialIsNotGated() {
        subscription(true);
        MockHttpServletRequest req = staffRequest();

        assertThat(gate.verificationRequired(req)).isFalse();
        assertThat(gate.isVerified(req)).isTrue();
    }

    @Test
    @DisplayName("Trial is sent no code and stores none")
    void trialSendsNoCode() {
        subscription(true);

        boolean sent = gate.sendCode(staffRequest(), false);

        assertThat(sent).isFalse();
        verify(emailService, never()).sendAccountEmail(anyString(), anyString(), anyString(), anyString());
        verify(verificationRepo, never()).save(any(BankSyncVerification.class));
    }

    @Test
    @DisplayName("Trial verifying without a code succeeds instead of failing a check it is exempt from")
    void trialVerifyShortCircuits() {
        subscription(true);

        BankSyncGateService.VerifyOutcome out =
                gate.verify(staffRequest(), new MockHttpServletResponse(), null, false);

        assertThat(out.ok()).isTrue();
    }

    /* ── everyone else: unchanged ───────────────────────────────────────── */

    @Test
    @DisplayName("a paid subscription is still gated")
    void paidPlanStillGated() {
        subscription(false);
        MockHttpServletRequest req = staffRequest();

        assertThat(gate.verificationRequired(req)).isTrue();
        assertThat(gate.isVerified(req)).isFalse();
    }

    @Test
    @DisplayName("a paid subscription still gets a code emailed and stored")
    void paidPlanStillSendsCode() {
        subscription(false);

        boolean sent = gate.sendCode(staffRequest(), false);

        assertThat(sent).isTrue();
        verify(emailService).sendAccountEmail(
                org.mockito.ArgumentMatchers.eq("treasurer@example.org"),
                anyString(), anyString(), org.mockito.ArgumentMatchers.eq(TENANT));
        verify(verificationRepo).save(any(BankSyncVerification.class));
    }

    @Test
    @DisplayName("a paid subscription cannot verify with an empty code")
    void paidPlanRejectsEmptyCode() {
        subscription(false);

        BankSyncGateService.VerifyOutcome out =
                gate.verify(staffRequest(), new MockHttpServletResponse(), null, false);

        assertThat(out.ok()).isFalse();
        assertThat(out.message()).contains("Enter the code");
    }

    /* ── the gate stays closed when the answer is unclear ───────────────── */

    @Test
    @DisplayName("a failed subscription lookup keeps the gate closed")
    void lookupFailureKeepsTheGate() {
        when(messagingPolicy.isTrial(TENANT)).thenThrow(new RuntimeException("db down"));

        assertThat(gate.verificationRequired(staffRequest())).isTrue();
    }

    @Test
    @DisplayName("a session with no tenant keeps the gate closed")
    void noTenantKeepsTheGate() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/bankSync");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appUserId", USER_ID);
        req.setSession(session);

        assertThat(gate.verificationRequired(req)).isTrue();
    }

    @Test
    @DisplayName("Trial does not make an unauthenticated request verified")
    void trialStillNeedsAStaffSession() {
        subscription(true);
        // No appUserId: not a signed-in staff user. The exemption removes the
        // step-up factor, never the requirement to be logged in.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/bankSync");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", TENANT);
        req.setSession(session);

        assertThat(gate.isVerified(req)).isFalse();
    }
}
