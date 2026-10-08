package com.churchgeniuspro.membergive;

import com.churchgeniuspro.controller.DonationController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Income posting for member contributions, the Donation Review split, and the pages. */
@DisplayName("Member contributions — income posting, Donation Review tabs, pages")
class MemberContributionPostingAndReviewTest {

    static final String CID = "CHR-1";

    @Test
    @DisplayName("a member contribution is posted to the member and the purpose they chose; a public donation as before (by email)")
    void postingUsesMemberAndPurpose() {
        IncomeService incomeService = mock(IncomeService.class);
        SourceService sourceService = mock(SourceService.class);
        FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
        TransactionTypeRepository ttRepo = mock(TransactionTypeRepository.class);
        SubSourceRepository subRepo = mock(SubSourceRepository.class);
        SubSource tithe = new SubSource(); tithe.setId(7); tithe.setAppClientId(CID);
        SubSource online = new SubSource(); online.setId(99); online.setAppClientId(CID);
        when(subRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, CID)).thenReturn(Optional.of(tithe));
        when(sourceService.findOrCreateOnlineDonationsSubSource(CID)).thenReturn(online);
        TransactionType card = new TransactionType(); card.setId(3);
        when(ttRepo.findFirstByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(anyString(), eq(CID))).thenReturn(Optional.of(card));
        FamilyMember anson = new FamilyMember(); anson.setId(10);
        when(memberRepo.findByIdAndTenant(10, CID)).thenReturn(Optional.of(anson));
        when(memberRepo.findActiveByEmailAndTenant("guest@x.org", CID)).thenReturn(List.of());
        DonationIncomePostingService poster = new DonationIncomePostingService(incomeService, sourceService, memberRepo, ttRepo);
        poster.setSubSourceRepo(subRepo);

        Donation member = new Donation(); member.setClientId(CID); member.setSource("MEMBER_PORTAL"); member.setMemberId(10); member.setSubSourceId(7);
        member.setAmount(new BigDecimal("10.00")); member.setIntendedAmount(new BigDecimal("10.00")); member.setFeeCovered(BigDecimal.ZERO);
        member.setPaymentMethod("Visa •••• 4242"); member.setStripePaymentIntentId("pi_m1");
        assertThat(poster.postToIncome(member)).isTrue();
        ArgumentCaptor<Integer> memberId = ArgumentCaptor.forClass(Integer.class), sub = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(incomeService).createIncome(memberId.capture(), sub.capture(), any(), eq(3), isNull(), eq(new BigDecimal("10.00")),
                note.capture(), isNull(), eq(false), eq(CID), anyString(), eq("pi_m1"), eq(true));
        assertThat(memberId.getValue()).isEqualTo(10);
        assertThat(sub.getValue()).isEqualTo(7);
        assertThat(note.getValue()).startsWith("Member portal contribution");

        reset(incomeService);
        Donation pub = new Donation(); pub.setClientId(CID); pub.setFirstName("Guest"); pub.setEmail("guest@x.org");
        pub.setAmount(new BigDecimal("5.00")); pub.setIntendedAmount(new BigDecimal("5.00")); pub.setFeeCovered(BigDecimal.ZERO);
        pub.setPaymentMethod("Card"); pub.setStripePaymentIntentId("pi_p1");
        assertThat(poster.postToIncome(pub)).isTrue();
        verify(incomeService).createIncome(isNull(), eq(99), any(), eq(3), isNull(), eq(new BigDecimal("5.00")),
                startsWith("Online donation"), eq("Guest"), eq(false), eq(CID), anyString(), eq("pi_p1"), eq(true));
    }

    @Test
    @DisplayName("Donation Review: the Donations list excludes member contributions; the Member Contributions list has only them, with the purpose")
    void reviewListsAreSeparate() {
        DonationRepository donationRepo = mock(DonationRepository.class);
        SubSourceRepository subRepo = mock(SubSourceRepository.class);
        SubSource tithe = new SubSource(); tithe.setId(7); tithe.setAppClientId(CID); tithe.setSourceName("Tithe");
        when(subRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, CID)).thenReturn(Optional.of(tithe));
        DonationController c = new DonationController(donationRepo, mock(StripeSettingsRepository.class), mock(PublicScreenLinkRepository.class),
                mock(ServiceClientRepository.class), mock(ChurchLogoRepository.class), mock(EmailService.class),
                mock(SubscriptionService.class), mock(DonationIncomePostingService.class), new PublicSendLimiter());
        c.setSubSourceRepo(subRepo);
        Donation pub = new Donation(); pub.setId(1L); pub.setClientId(CID); pub.setFirstName("Binny"); pub.setAmount(new BigDecimal("100.00")); pub.setCurrency("usd"); pub.setStatus("succeeded");
        Donation mem = new Donation(); mem.setId(2L); mem.setClientId(CID); mem.setFirstName("Anson"); mem.setAmount(new BigDecimal("10.00")); mem.setCurrency("usd"); mem.setStatus("succeeded");
        mem.setSource("MEMBER_PORTAL"); mem.setMemberId(10); mem.setSubSourceId(7);
        when(donationRepo.findByClientIdOrderByDonatedAtDesc(CID)).thenReturn(List.of(pub, mem));
        MockHttpServletRequest req = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "a"); s.setAttribute("role", "Accountant"); s.setAttribute("appClientId", CID); req.setSession(s);

        @SuppressWarnings("unchecked") Map<String, Object> donations = (Map<String, Object>) c.getDonations(req).getBody();
        @SuppressWarnings("unchecked") List<Map<String, Object>> drows = (List<Map<String, Object>>) donations.get("rows");
        assertThat(drows).extracting(r -> r.get("id")).containsExactly(1L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> dtot = (List<Map<String, Object>>) donations.get("totals");
        assertThat((BigDecimal) dtot.get(0).get("total")).isEqualByComparingTo("100.00");

        @SuppressWarnings("unchecked") Map<String, Object> members = (Map<String, Object>) c.getDonations("member", req).getBody();
        @SuppressWarnings("unchecked") List<Map<String, Object>> mrows = (List<Map<String, Object>>) members.get("rows");
        assertThat(mrows).hasSize(1);
        assertThat(mrows.get(0)).containsEntry("id", 2L).containsEntry("memberId", 10).containsEntry("purpose", "Tithe");
        @SuppressWarnings("unchecked") List<Map<String, Object>> mtot = (List<Map<String, Object>>) members.get("totals");
        assertThat((BigDecimal) mtot.get(0).get("total")).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("the gateway reads the card label from the expanded charge, and treats a missing charge as 'Card'")
    void chargeInfo() {
        ChurchStripeGateway.ChargeInfo ci = ChurchStripeGateway.chargeInfo(Map.of("latest_charge",
                Map.of("id", "ch_1", "payment_method_details", Map.of("type", "card", "card", Map.of("brand", "amex", "last4", "0005")))));
        assertThat(ci.chargeId()).isEqualTo("ch_1"); assertThat(ci.paymentMethod()).isEqualTo("Amex •••• 0005");
        assertThat(ChurchStripeGateway.chargeInfo(Map.of()).paymentMethod()).isEqualTo("Card");
        assertThat(ChurchStripeGateway.PAYMENT_INTENT_ID.matcher("pi_3ABCdef").matches()).isTrue();
        assertThat(ChurchStripeGateway.PAYMENT_INTENT_ID.matcher("pi_1/../x").matches()).isFalse();
    }

    @Test
    @DisplayName("pages: Give pays through Stripe (no no-payment submit), Donation Review has both tabs, nav says Donation/Give")
    void pages() throws IOException {
        String home = Files.readString(Paths.get("src/main/resources/static/memberHome.html"));
        assertThat(home).contains("'/api/member/give/config'", "'/api/member/give/intent'", "'/api/member/give/save'",
                "giveStripe.confirmPayment(", "redirect: 'if_required'", "id=\"givePaymentElement\"", "https://js.stripe.com/v3/");
        assertThat(home).doesNotContain("fetch('/api/member/give',");
        String review = Files.readString(Paths.get("src/main/resources/static/donationReview.html"));
        assertThat(review).contains("id=\"tabDonations\"", "id=\"tabMember\"", "Member Contributions", "?source=member", "class=\"col-member\"");
        String shell = Files.readString(Paths.get("src/main/resources/static/shell.js"));
        assertThat(shell).contains("label: 'Donation/Give'");
    }
}
