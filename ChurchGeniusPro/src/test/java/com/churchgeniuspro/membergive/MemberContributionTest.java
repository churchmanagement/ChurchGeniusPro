package com.churchgeniuspro.membergive;

import com.churchgeniuspro.controller.MemberContributionController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.ChurchStripeGateway;
import com.churchgeniuspro.service.DonationIncomePostingService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Member Portal → Give / Contribute is a real Stripe payment through the church's
 * connected account, recorded only once Stripe says it succeeded, once per
 * PaymentIntent, for the signed-in member of the signed-in church.
 */
@DisplayName("Member contributions — Stripe payment, recording, isolation")
class MemberContributionTest {

    static final String CID = "CHR-1", OTHER = "CHR-2";

    DonationRepository donationRepo;
    StripeSettingsRepository stripeRepo;
    FamilyMemberRepository memberRepo;
    SubSourceRepository subSourceRepo;
    SubscriptionService subs;
    DonationIncomePostingService poster;
    ChurchStripeGateway gateway;
    FakeChurchStripe stripe;
    MemberContributionController controller;
    Map<String, Donation> saved = new HashMap<>();   // by PaymentIntent id

    @BeforeEach
    void setUp() {
        donationRepo = mock(DonationRepository.class);
        when(donationRepo.findByStripePaymentIntentId(anyString())).thenAnswer(i -> Optional.ofNullable(saved.get((String) i.getArgument(0))));
        when(donationRepo.save(any(Donation.class))).thenAnswer(i -> { Donation d = i.getArgument(0); d.setId((long) (saved.size() + 1)); saved.put(d.getStripePaymentIntentId(), d); return d; });
        stripeRepo = mock(StripeSettingsRepository.class);
        StripeSettings ss = new StripeSettings(); ss.setClientId(CID); ss.setPublishableKey("pk_test_church"); ss.setSecretKey("sk_test_church");
        when(stripeRepo.findByClientId(CID)).thenReturn(Optional.of(ss));
        when(stripeRepo.findByClientId(OTHER)).thenReturn(Optional.empty());
        memberRepo = mock(FamilyMemberRepository.class);
        FamilyMember anson = new FamilyMember(); anson.setId(10); anson.setFirstName("Anson"); anson.setLastName("Mathew");
        anson.setEmail("anson@x.org"); anson.setPhone("2145550101"); anson.setAppClientId(CID);
        when(memberRepo.findByIdAndTenant(10, CID)).thenReturn(Optional.of(anson));
        FamilyMember other = new FamilyMember(); other.setId(11); other.setFirstName("Other"); other.setAppClientId(CID);
        when(memberRepo.findByIdAndTenant(11, CID)).thenReturn(Optional.of(other));
        subSourceRepo = mock(SubSourceRepository.class);
        SubSource tithe = new SubSource(); tithe.setId(7); tithe.setAppClientId(CID); tithe.setSourceName("Tithe");
        when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, CID)).thenReturn(Optional.of(tithe));
        subs = mock(SubscriptionService.class);
        when(subs.isFeatureEnabled(CID, "onlineGiving")).thenReturn(true);
        when(subs.canAcceptOnlineGiving(CID)).thenReturn(true);
        poster = mock(DonationIncomePostingService.class);
        gateway = new ChurchStripeGateway(); stripe = new FakeChurchStripe(); gateway.setTransport(stripe);
        controller = new MemberContributionController(donationRepo, stripeRepo, memberRepo, subSourceRepo, subs, poster, gateway, new PublicSendLimiter());
    }

    static MockHttpServletRequest member(String clientId, int memberId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member"); s.setAttribute("memberId", memberId); s.setAttribute("appClientId", clientId);
        s.setAttribute("clientId", "MBR-" + memberId);
        req.setSession(s); req.setRemoteAddr("10.0.0." + memberId);
        return req;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    String intentFor(int memberId, String amount, int purpose) {
        ResponseEntity<?> r = controller.createIntent(Map.of("amount", amount, "subSourceId", purpose), member(CID, memberId));
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(200);
        return (String) body(r).get("paymentIntentId");
    }

    @Nested @DisplayName("config and intent")
    class Intent {
        @Test @DisplayName("config says enabled with the church's publishable key; not enabled without Stripe settings")
        void config() {
            assertThat(body(controller.config(member(CID, 10)))).containsEntry("onlineGivingEnabled", true).containsEntry("publishableKey", "pk_test_church");
            FamilyMember m = new FamilyMember(); m.setId(5); when(memberRepo.findByIdAndTenant(5, OTHER)).thenReturn(Optional.of(m));
            assertThat(body(controller.config(member(OTHER, 5)))).containsEntry("onlineGivingEnabled", false).doesNotContainKey("publishableKey");
            assertThat(controller.config(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        }

        @Test @DisplayName("the intent is for exactly the amount, in USD, on the church's account, stamped with member and purpose")
        void intentShape() {
            String id = intentFor(10, "10.00", 7);
            var p = stripe.creates.get(0);
            assertThat(p.getFirst("amount")).isEqualTo("1000");
            assertThat(p.getFirst("currency")).isEqualTo("usd");
            assertThat(p.getFirst("metadata[purpose]")).isEqualTo("cgp_member_contribution");
            assertThat(p.getFirst("metadata[client_id]")).isEqualTo(CID);
            assertThat(p.getFirst("metadata[member_id]")).isEqualTo("10");
            assertThat(p.getFirst("metadata[sub_source_id]")).isEqualTo("7");
            assertThat(stripe.secretsSeen).containsExactly("Bearer sk_test_church");
            assertThat(id).startsWith("pi_");
            assertThat(saved).as("nothing recorded before payment").isEmpty();
        }

        @Test @DisplayName("refused: foreign purpose, tiny amount, bad amount, no session, church without Stripe, plan without online giving")
        void refusals() {
            when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(555, CID)).thenReturn(Optional.empty());
            assertThat(body(controller.createIntent(Map.of("amount", "10", "subSourceId", 555), member(CID, 10)))).containsEntry("error", "Invalid purpose.");
            assertThat(body(controller.createIntent(Map.of("amount", "0.25", "subSourceId", 7), member(CID, 10)))).containsEntry("error", "Minimum contribution amount is $0.50.");
            assertThat(controller.createIntent(Map.of("amount", "abc", "subSourceId", 7), member(CID, 10)).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.createIntent(Map.of("amount", "10", "subSourceId", 7), new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
            when(subs.isFeatureEnabled(CID, "onlineGiving")).thenReturn(false);
            assertThat(controller.createIntent(Map.of("amount", "10", "subSourceId", 7), member(CID, 10)).getStatusCode().value()).isEqualTo(403);
            assertThat(stripe.creates).isEmpty();
        }

        @Test @DisplayName("a staff session is not a member: refused")
        void staffRefused() {
            MockHttpServletRequest req = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
            s.setAttribute("role", "Admin"); s.setAttribute("username", "a"); s.setAttribute("appClientId", CID); req.setSession(s);
            assertThat(controller.createIntent(Map.of("amount", "10", "subSourceId", 7), req).getStatusCode().value()).isEqualTo(401);
        }
    }

    @Nested @DisplayName("save")
    class Save {
        @Test @DisplayName("a succeeded payment is recorded once: amount from Stripe, member, purpose, card last four, posted to income")
        void recorded() {
            String id = intentFor(10, "10.00", 7);
            stripe.succeed(id, "visa", "4242");

            ResponseEntity<?> r = controller.save(Map.of("paymentIntentId", id, "note", "October"), member(CID, 10));
            assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(200);
            Donation d = saved.get(id);
            assertThat(d.getSource()).isEqualTo("MEMBER_PORTAL");
            assertThat(d.getMemberId()).isEqualTo(10);
            assertThat(d.getSubSourceId()).isEqualTo(7);
            assertThat(d.getAmount()).isEqualByComparingTo("10.00");
            assertThat(d.getIntendedAmount()).isEqualByComparingTo("10.00");
            assertThat(d.getPaymentMethod()).isEqualTo("Visa •••• 4242");
            assertThat(d.getStripeChargeId()).isEqualTo("ch_" + id);
            assertThat(d.getStatus()).isEqualTo("succeeded");
            assertThat(d.getFirstName()).isEqualTo("Anson"); assertThat(d.getEmail()).isEqualTo("anson@x.org");
            assertThat(d.getClientId()).isEqualTo(CID);
            assertThat(d.getNote()).isEqualTo("October");
            verify(poster).postToIncome(d);
            verify(subs).recordOnlineGiving(CID);

            // Retry / refresh: already recorded, nothing new.
            assertThat(body(controller.save(Map.of("paymentIntentId", id), member(CID, 10)))).containsEntry("message", "Contribution already recorded.");
            verify(donationRepo, times(1)).save(any(Donation.class));
            verify(poster, times(1)).postToIncome(any());
        }

        @Test @DisplayName("a failed or cancelled payment records nothing")
        void notSucceeded() {
            String id = intentFor(10, "10.00", 7);
            stripe.fail(id);
            ResponseEntity<?> r = controller.save(Map.of("paymentIntentId", id), member(CID, 10));
            assertThat(r.getStatusCode().value()).isEqualTo(400);
            assertThat(String.valueOf(body(r).get("error"))).contains("has not completed");
            assertThat(saved).isEmpty();
            verify(poster, never()).postToIncome(any());
            verify(subs, never()).recordOnlineGiving(anyString());
        }

        @Test @DisplayName("another member cannot record someone else's payment")
        void otherMemberRefused() {
            String id = intentFor(10, "10.00", 7);
            stripe.succeed(id, "visa", "4242");
            ResponseEntity<?> r = controller.save(Map.of("paymentIntentId", id), member(CID, 11));
            assertThat(r.getStatusCode().value()).isEqualTo(404);
            assertThat(saved).isEmpty();
        }

        @Test @DisplayName("an already-recorded payment of another church is not visible")
        void otherChurchRefused() {
            String id = intentFor(10, "10.00", 7);
            stripe.succeed(id, "visa", "4242");
            controller.save(Map.of("paymentIntentId", id), member(CID, 10));
            StripeSettings ss = new StripeSettings(); ss.setClientId(OTHER); ss.setPublishableKey("pk"); ss.setSecretKey("sk");
            when(stripeRepo.findByClientId(OTHER)).thenReturn(Optional.of(ss));
            when(subs.isFeatureEnabled(OTHER, "onlineGiving")).thenReturn(true);
            FamilyMember m = new FamilyMember(); m.setId(5); when(memberRepo.findByIdAndTenant(5, OTHER)).thenReturn(Optional.of(m));
            assertThat(controller.save(Map.of("paymentIntentId", id), member(OTHER, 5)).getStatusCode().value()).isEqualTo(404);
        }

        @Test @DisplayName("the amount recorded is Stripe's, not the browser's; an unknown or malformed id is refused")
        void amountFromStripe() {
            String id = intentFor(10, "10.00", 7);
            stripe.intents.get(id).put("amount", 1000L);       // what was actually charged
            stripe.succeed(id, "mastercard", "5555");
            controller.save(Map.of("paymentIntentId", id, "amount", "999"), member(CID, 10));
            assertThat(saved.get(id).getAmount()).isEqualByComparingTo("10.00");
            assertThat(controller.save(Map.of("paymentIntentId", "../x"), member(CID, 10)).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.save(Map.of("paymentIntentId", "pi_unknown1234"), member(CID, 10)).getStatusCode().value()).isEqualTo(400);
        }
    }
}
