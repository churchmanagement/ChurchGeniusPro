package com.churchgeniuspro.service;

import com.churchgeniuspro.controller.InvoiceController;
import com.churchgeniuspro.controller.ServiceAdminBillingController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.webfilter.AccountStatusFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 5: additional charges, invoices (draft → review → send → paid / void), the
 * secure invoice link, and billing reminders. Repositories are in-memory fakes that
 * implement the conditional updates the way the JPQL does.
 */
@DisplayName("Phase 5 — charges, invoices, invoice link, billing reminders")
class BillingPhase5Test extends BillingTestBase {

    // ══ Additional charges ═════════════════════════════════════════════════

    @Nested @DisplayName("Additional charges")
    class Charges {
        @Test @DisplayName("created unbilled with amount, period and client; bad input refused")
        void create() {
            ClientCharge c = charge("CHR-1", "Data migration", "$150");
            assertThat(c.getStatus()).isEqualTo(ClientCharge.UNBILLED);
            assertThat(c.getAmount()).isEqualByComparingTo("150.00");
            assertThat(c.getBillingPeriod()).isEqualTo(java.time.YearMonth.from(TODAY.plusDays(10)).toString());
            assertThatThrownBy(() -> charge("CHR-1", "x", "0")).hasMessageContaining("more than $0.00");
            assertThatThrownBy(() -> charge("CHR-1", " ", "5")).hasMessageContaining("Description");
            assertThatThrownBy(() -> charge("NOPE", "x", "5")).hasMessageContaining("Client not found");
            assertThatThrownBy(() -> billing.createCharge(new BillingService.ChargeForm("CHR-1", "x", "5", "2026-13", null), "a"))
                    .hasMessageContaining("Billing period");
            assertThatThrownBy(() -> charge("CHR-1", "x", "-5")).hasMessageContaining("Amount");
        }

        @Test @DisplayName("mark paid / void work on a free unbilled charge only")
        void paidAndVoid() {
            ClientCharge a = charge("CHR-1", "Training", "75");
            billing.markChargePaid(a.getId(), TODAY, "check", "admin");
            assertThat(a.getStatus()).isEqualTo(ClientCharge.PAID);
            assertThat(a.getPaymentMethod()).isEqualTo("CHECK");
            assertThatThrownBy(() -> billing.voidCharge(a.getId(), "admin")).hasMessageContaining("Only an unbilled charge");

            ClientCharge b = charge("CHR-1", "Setup", "20");
            billing.createManualDraft("CHR-1", "admin");                  // reserves b on the draft
            assertThatThrownBy(() -> billing.voidCharge(b.getId(), "admin")).hasMessageContaining("Remove it from that invoice");
            assertThatThrownBy(() -> billing.markChargePaid(b.getId(), TODAY, "CASH", "admin")).hasMessageContaining("Remove it from that invoice");
            assertThatThrownBy(() -> billing.markChargePaid(charge("CHR-1", "z", "1").getId(), TODAY, "bitcoin", "a"))
                    .hasMessageContaining("payment method");
        }

        @Test @DisplayName("editing a charge on a draft updates the draft's line and totals; refused once billed")
        void editFollowsDraft() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "admin").invoice();
            assertThat(inv.getTotal()).isEqualByComparingTo("60.00");
            billing.updateCharge(c.getId(), new BillingService.ChargeForm("CHR-1", "Setup fee", "35", c.getBillingPeriod(), null), "admin");
            assertThat(linesOf(inv.getId())).anySatisfy(l -> {
                assertThat(l.getChargeId()).isEqualTo(c.getId());
                assertThat(l.getAmount()).isEqualByComparingTo("35.00");
            });
            assertThat(invRows.get(inv.getId()).getTotal()).isEqualByComparingTo("75.00");
            billing.send(inv.getId(), null, "admin");
            assertThat(c.getStatus()).isEqualTo(ClientCharge.BILLED);
            assertThatThrownBy(() -> billing.updateCharge(c.getId(), new BillingService.ChargeForm("CHR-1", "x", "1", null, null), "a"))
                    .hasMessageContaining("Only an unbilled charge");
        }
    }

    // ══ Drafts ═════════════════════════════════════════════════════════════

    @Nested @DisplayName("Creating draft invoices")
    class Drafts {
        @Test @DisplayName("renewal: client's own (negotiated) price, next period, unbilled charges reserved")
        void renewal() {
            ClientCharge c = charge("CHR-1", "Extra SMS pack", "15");
            BillingService.Draft d = billing.createRenewalDraft("CHR-1", "admin");
            BillingInvoice inv = d.invoice();
            assertThat(d.created()).isTrue();
            assertThat(inv.getStatus()).isEqualTo(BillingInvoice.DRAFT);
            assertThat(inv.getInvoiceNumber()).matches("INV-\\d{4}-\\d{5}");
            assertThat(inv.getDueDate()).isEqualTo(paid.getEndDate());
            assertThat(inv.getPeriodStart()).isEqualTo(paid.getEndDate());
            assertThat(inv.getPeriodEnd()).isEqualTo(paid.getEndDate().plusMonths(1).minusDays(1));
            List<BillingInvoiceLine> ls = linesOf(inv.getId());
            assertThat(ls).hasSize(2);
            assertThat(ls.get(0).getKind()).isEqualTo(BillingInvoiceLine.SUBSCRIPTION);
            assertThat(ls.get(0).getAmount()).isEqualByComparingTo("40.00");          // not the 49.00 list price
            assertThat(ls.get(0).getDescription()).contains("Standard plan").contains("Monthly");
            assertThat(ls.get(1).getChargeId()).isEqualTo(c.getId());
            assertThat(inv.getSubtotal()).isEqualByComparingTo("55.00");
            assertThat(inv.getTotal()).isEqualByComparingTo("55.00");
            assertThat(c.getInvoiceId()).isEqualTo(inv.getId());
            assertThat(c.getStatus()).isEqualTo(ClientCharge.UNBILLED);
            assertThat(inv.getBillToEmail()).isEqualTo("chr-1@church.test");
        }

        @Test @DisplayName("renewal without a client price falls back to the plan list price; yearly uses the yearly period")
        void listPriceFallback() {
            client(2, "CHR-2", "STANDARD", null, TODAY.plusDays(3)).setBillingFrequency("YEARLY");
            BillingInvoice inv = billing.createRenewalDraft("CHR-2", "a").invoice();
            assertThat(inv.getTotal()).isEqualByComparingTo("490.00");
            assertThat(inv.getPeriodEnd()).isEqualTo(TODAY.plusDays(3).plusYears(1).minusDays(1));
        }

        @Test @DisplayName("a second renewal request returns the existing invoice; a charge is never on two drafts")
        void noDuplicate() {
            charge("CHR-1", "Setup", "20");
            BillingInvoice first = billing.createRenewalDraft("CHR-1", "a").invoice();
            BillingService.Draft again = billing.createRenewalDraft("CHR-1", "b");
            assertThat(again.created()).isFalse();
            assertThat(again.invoice().getId()).isEqualTo(first.getId());
            BillingInvoice manual = billing.createManualDraft("CHR-1", "a").invoice();
            assertThat(linesOf(manual.getId())).isEmpty();
        }

        @Test @DisplayName("the unique index (V11) settles a simultaneous create: the loser gets the winner's invoice")
        void raceReturnsExisting() {
            BillingInvoice winner = billing.createRenewalDraft("CHR-1", "a").invoice();
            // The second caller's look-up ran before the winner committed.
            when(invoices.findByClientIdAndKindAndPeriodStartAndStatusIn(eq("CHR-1"), anyString(), any(), anyCollection()))
                    .thenReturn(List.of()).thenReturn(List.of(winner));
            BillingService.Draft d = billing.createRenewalDraft("CHR-1", "b");
            assertThat(d.created()).isFalse();
            assertThat(d.invoice().getId()).isEqualTo(winner.getId());
        }

        @Test @DisplayName("renewal invoices are refused for trial, sample/demo and free clients")
        void notForTrials() {
            client(3, "CHR-3", "TRIAL", null, TODAY.plusDays(5));
            client(4, "TRIAL-4", "STANDARD", null, TODAY.plusDays(5));
            client(5, "CHR-5", "FREE", null, TODAY.plusDays(5));
            for (String cid : List.of("CHR-3", "TRIAL-4", "CHR-5")) {
                assertThatThrownBy(() -> billing.createRenewalDraft(cid, "a")).as(cid).hasMessageContaining("paid plan");
            }
        }

        @Test @DisplayName("from a subscription request: requested plan and frequency at list price, requester billed; sample trials refused")
        void fromRequest() {
            client(6, "CHR-6", "TRIAL", null, TODAY.plusDays(5));
            SubscriptionRequest r = new SubscriptionRequest();
            r.setId(9L); r.setClientId("CHR-6"); r.setPlanCode("STANDARD"); r.setBillingFrequency("YEARLY");
            r.setStatus(SubscriptionRequest.NEW); r.setFirstName("Ann"); r.setLastName("Lee"); r.setRegisteredEmail("ann@church.test");
            when(requests.findById(9L)).thenReturn(Optional.of(r));
            BillingInvoice inv = billing.createRequestDraft(9L, "a").invoice();
            assertThat(inv.getKind()).isEqualTo(BillingInvoice.KIND_REQUEST);
            assertThat(inv.getTotal()).isEqualByComparingTo("490.00");
            assertThat(inv.getBillToEmail()).isEqualTo("ann@church.test");
            assertThat(inv.getBillToName()).isEqualTo("Ann Lee");
            assertThat(billing.createRequestDraft(9L, "a").created()).isFalse();

            SubscriptionRequest s = new SubscriptionRequest();
            s.setId(10L); s.setClientId("TRIAL-7"); s.setStatus(SubscriptionRequest.NEW); s.setRegisterAsNewClient(true);
            when(requests.findById(10L)).thenReturn(Optional.of(s));
            assertThatThrownBy(() -> billing.createRequestDraft(10L, "a")).hasMessageContaining("Register the new client first");
            r.setStatus(SubscriptionRequest.DECLINED);
            invRows.clear();
            assertThatThrownBy(() -> billing.createRequestDraft(9L, "a")).hasMessageContaining("not open");
        }
    }

    // ══ Review ═════════════════════════════════════════════════════════════

    @Nested @DisplayName("Review before sending")
    class Review {
        @Test @DisplayName("lines, discount, due date and email can be changed; Subtotal − Discount = Amount Due")
        void edits() {
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            List<BillingService.LineForm> ls = currentLines(inv.getId());
            ls.set(0, new BillingService.LineForm("SUBSCRIPTION", "Standard plan — renewal", "45.00", null));
            ls.add(new BillingService.LineForm("CUSTOM", "Onboarding session", "30", null));
            BillingService.DraftForm f = new BillingService.DraftForm(inv.getVersion(), "Treasurer", "pay@church.test",
                    TODAY.plusDays(20), "Thank you!", "10", ls);
            BillingInvoice after = billing.updateDraft(inv.getId(), f, "a");
            assertThat(after.getSubtotal()).isEqualByComparingTo("75.00");
            assertThat(after.getDiscount()).isEqualByComparingTo("10.00");
            assertThat(after.getTotal()).isEqualByComparingTo("65.00");
            assertThat(after.getBillToEmail()).isEqualTo("pay@church.test");
            assertThat(after.getDueDate()).isEqualTo(TODAY.plusDays(20));
            assertThat(linesOf(inv.getId())).extracting(BillingInvoiceLine::getDescription)
                    .containsExactly("Standard plan — renewal", "Onboarding session");
        }

        @Test @DisplayName("discount above the subtotal, negative amounts, bad email and stale screens are refused")
        void refusals() {
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            assertThatThrownBy(() -> billing.updateDraft(inv.getId(), form(inv, "40.01", currentLines(inv.getId())), "a"))
                    .hasMessageContaining("Discount cannot be more than the subtotal");
            List<BillingService.LineForm> neg = List.of(new BillingService.LineForm("CUSTOM", "Credit", "-5", null));
            assertThatThrownBy(() -> billing.updateDraft(inv.getId(), form(inv, "0", neg), "a")).hasMessageContaining("Line amount");
            BillingService.DraftForm badMail = new BillingService.DraftForm(inv.getVersion(), null, "nope", inv.getDueDate(), null, "0",
                    currentLines(inv.getId()));
            assertThatThrownBy(() -> billing.updateDraft(inv.getId(), badMail, "a")).hasMessageContaining("billing email");
            assertThatThrownBy(() -> billing.updateDraft(inv.getId(), form(inv, "0", List.of()), "a")).hasMessageContaining("at least one line");
            BillingService.DraftForm stale = new BillingService.DraftForm(inv.getVersion() - 1, null, "a@b.co", inv.getDueDate(), null, "0",
                    currentLines(inv.getId()));
            assertThatThrownBy(() -> billing.updateDraft(inv.getId(), stale, "a")).hasMessageContaining("changed by someone else");
        }

        @Test @DisplayName("removing a charge line releases the charge; a free unbilled charge can be added; charge amounts come from the charge")
        void chargeLines() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            ClientCharge later = charge("CHR-1", "Training", "50");
            List<BillingService.LineForm> ls = new ArrayList<>(currentLines(inv.getId()));
            ls.removeIf(l -> l.chargeId() != null);
            ls.add(new BillingService.LineForm("CHARGE", null, "0.01", later.getId()));   // amount ignored
            BillingInvoice after = billing.updateDraft(inv.getId(), form(inv, "0", ls), "a");
            assertThat(c.getInvoiceId()).isNull();
            assertThat(later.getInvoiceId()).isEqualTo(inv.getId());
            assertThat(after.getTotal()).isEqualByComparingTo("90.00");

            ClientCharge other = charge("CHR-1", "Other", "5");
            other.setClientId("CHR-OTHER");
            List<BillingService.LineForm> bad = new ArrayList<>(currentLines(inv.getId()));
            bad.add(new BillingService.LineForm("CHARGE", null, null, other.getId()));
            assertThatThrownBy(() -> billing.updateDraft(inv.getId(), form(invRows.get(inv.getId()), "0", bad), "a"))
                    .hasMessageContaining("no longer available");
        }

        @Test @DisplayName("a sent invoice is frozen")
        void frozen() {
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            billing.send(inv.getId(), inv.getVersion(), "a");
            assertThatThrownBy(() -> billing.updateDraft(inv.getId(), form(invRows.get(inv.getId()), "0", currentLines(inv.getId())), "a"))
                    .hasMessageContaining("Only a draft can be edited");
        }
    }

    // ══ Send / re-send / paid / void ═══════════════════════════════════════

    @Nested @DisplayName("Send, re-send, mark paid, void")
    class Lifecycle {
        @Test @DisplayName("send freezes totals, stores only the token hash, bills the charges and emails the link (no amount in subject)")
        void send() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            BillingService.Sent s = billing.send(inv.getId(), inv.getVersion(), "admin");
            BillingInvoice sent = invRows.get(inv.getId());
            String token = tokenOf(s.link());
            assertThat(s.link()).startsWith("https://app.test/invoice.html?t=");
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
            assertThat(sent.getStatus()).isEqualTo(BillingInvoice.SENT);
            assertThat(sent.getAccessTokenHash()).isEqualTo(BillingService.hash(token)).isNotEqualTo(token).hasSize(64);
            assertThat(sent.getAccessTokenExpires()).isEqualTo(sent.getDueDate().plusDays(BillingService.LINK_VALID_DAYS_AFTER_DUE));
            assertThat(sent.getIssueDate()).isEqualTo(TODAY);
            assertThat(sent.getTotal()).isEqualByComparingTo("60.00");
            assertThat(c.getStatus()).isEqualTo(ClientCharge.BILLED);
            assertThat(mails).hasSize(1);
            assertThat(mails.get(0)[0]).isEqualTo("chr-1@church.test");
            assertThat(mails.get(0)[1]).contains(sent.getInvoiceNumber()).doesNotContain("$");
            assertThat(mails.get(0)[2]).contains(token).contains("$60.00");
            assertThat(mails.get(0)[3]).isEqualTo("ChurchGeniusPro Billing");
            assertThatThrownBy(() -> billing.send(inv.getId(), null, "admin")).hasMessageContaining("already sent");
        }

        @Test @DisplayName("an email failure leaves the invoice a draft with no link and the charges unbilled")
        void sendFailure() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            mailFails = true;
            assertThatThrownBy(() -> billing.send(inv.getId(), null, "a")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still a draft");
            BillingInvoice now = invRows.get(inv.getId());
            assertThat(now.getStatus()).isEqualTo(BillingInvoice.DRAFT);
            assertThat(now.getAccessTokenHash()).isNull();
            assertThat(c.getStatus()).isEqualTo(ClientCharge.UNBILLED);
            mailFails = false;
            assertThat(billing.send(inv.getId(), null, "a").link()).isNotBlank();
        }

        @Test @DisplayName("send after someone edited the draft is refused (version check)")
        void sendStale() {
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            Long seen = inv.getVersion();
            billing.updateDraft(inv.getId(), form(inv, "5", currentLines(inv.getId())), "other admin");
            assertThatThrownBy(() -> billing.send(inv.getId(), seen, "a")).hasMessageContaining("changed by someone else");
            assertThat(mails).isEmpty();
        }

        @Test @DisplayName("re-send mints a new link and the old one stops working; a failed re-send keeps the old link")
        void resend() {
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            String first = tokenOf(billing.send(inv.getId(), null, "a").link());
            String second = tokenOf(billing.resend(inv.getId(), "a").link());
            assertThat(second).isNotEqualTo(first);
            assertThat(billing.publicView(first, null)).isEmpty();
            assertThat(billing.publicView(second, null)).isPresent();
            assertThat(invRows.get(inv.getId()).getSendCount()).isEqualTo(2);
            mailFails = true;
            assertThatThrownBy(() -> billing.resend(inv.getId(), "a")).hasMessageContaining("previous link still works");
            assertThat(billing.publicView(second, null)).isPresent();
            assertThat(invRows.get(inv.getId()).getSendCount()).isEqualTo(2);
        }

        @Test @DisplayName("mark paid: sent → paid once, charges paid, client subscription untouched")
        void markPaid() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            assertThatThrownBy(() -> billing.markPaid(inv.getId(), TODAY, "CHECK", "1001", "a")).hasMessageContaining("sent, unpaid");
            billing.send(inv.getId(), null, "a");
            LocalDate endBefore = paid.getEndDate();
            billing.markPaid(inv.getId(), TODAY, "check", "1001", "admin");
            BillingInvoice now = invRows.get(inv.getId());
            assertThat(now.getStatus()).isEqualTo(BillingInvoice.PAID);
            assertThat(now.getPaymentMethod()).isEqualTo("CHECK");
            assertThat(now.getPaymentReference()).isEqualTo("1001");
            assertThat(c.getStatus()).isEqualTo(ClientCharge.PAID);
            assertThat(c.getPaidDate()).isEqualTo(TODAY);
            assertThat(paid.getEndDate()).isEqualTo(endBefore);
            assertThat(paid.getPaymentStatus()).isEqualTo("PAID");
            verify(clients, never()).save(any());
            assertThatThrownBy(() -> billing.markPaid(inv.getId(), TODAY, "CHECK", null, "a")).hasMessageContaining("sent, unpaid");
            assertThatThrownBy(() -> billing.voidInvoice(inv.getId(), null, "a")).hasMessageContaining("can be voided");
            assertThatThrownBy(() -> billing.markPaid(inv.getId(), TODAY.plusDays(1), "CHECK", null, "a"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("void: link revoked, charges unbilled again and free for the next invoice")
        void voidIt() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            String token = tokenOf(billing.send(inv.getId(), null, "a").link());
            billing.voidInvoice(inv.getId(), "wrong amount", "admin");
            assertThat(invRows.get(inv.getId()).getStatus()).isEqualTo(BillingInvoice.VOID);
            assertThat(billing.publicView(token, null)).isEmpty();
            assertThat(c.getStatus()).isEqualTo(ClientCharge.UNBILLED);
            assertThat(c.getInvoiceId()).isNull();
            // A voided renewal does not block a new one for the same date.
            BillingService.Draft again = billing.createRenewalDraft("CHR-1", "a");
            assertThat(again.created()).isTrue();
            assertThat(c.getInvoiceId()).isEqualTo(again.invoice().getId());
        }
    }

    // ══ Secure invoice page ════════════════════════════════════════════════

    @Nested @DisplayName("Secure invoice link")
    class Link {
        String token;
        BillingInvoice inv;

        @BeforeEach
        void sendOne() {
            inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            token = tokenOf(billing.send(inv.getId(), null, "a").link());
        }

        @Test @DisplayName("a valid token shows that invoice only, with no ids or client id")
        void valid() {
            Map<String, Object> m = billing.publicView(token, null).orElseThrow();
            assertThat(m).containsEntry("invoiceNumber", inv.getInvoiceNumber()).containsEntry("state", "DUE")
                    .doesNotContainKeys("id", "clientId", "accessTokenHash", "version", "billToEmail");
            assertThat(m.get("total")).isEqualTo(new BigDecimal("40.00"));
            assertThat((List<?>) m.get("lines")).hasSize(1);
        }

        @Test @DisplayName("wrong, blank, expired and draft tokens get the same empty result")
        void refused() {
            assertThat(billing.publicView("x" + token.substring(1), null)).isEmpty();
            assertThat(billing.publicView("", null)).isEmpty();
            assertThat(billing.publicView(null, null)).isEmpty();
            invRows.get(inv.getId()).setAccessTokenExpires(TODAY.minusDays(1));
            assertThat(billing.publicView(token, null)).isEmpty();
            invRows.get(inv.getId()).setAccessTokenExpires(TODAY);
            assertThat(billing.publicView(token, null)).isPresent();
            invRows.get(inv.getId()).setStatus(BillingInvoice.DRAFT);
            assertThat(billing.publicView(token, null)).isEmpty();
        }

        @Test @DisplayName("another church's session cannot open it; the church's own session can")
        void sessions() {
            assertThat(billing.publicView(token, "CHR-OTHER")).isEmpty();
            assertThat(billing.publicView(token, "CHR-1")).isPresent();
        }

        @Test @DisplayName("overdue and paid states")
        void states() {
            invRows.get(inv.getId()).setDueDate(TODAY.minusDays(1));
            assertThat(billing.publicView(token, null).orElseThrow()).containsEntry("state", "OVERDUE");
            billing.markPaid(inv.getId(), TODAY, "CASH", null, "a");
            assertThat(billing.publicView(token, null).orElseThrow()).containsEntry("state", "PAID")
                    .containsEntry("paidDate", TODAY.toString());
        }

        @Test @DisplayName("the API sends no-store / no-referrer, a generic 404, and throttles repeated bad tokens")
        void controller() {
            InvoiceController c = new InvoiceController(billing);
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/invoice/view");
            req.setRemoteAddr("10.0.0.9");
            ResponseEntity<Map<String, Object>> ok = c.view(token, req);
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
            assertThat(ok.getHeaders().getCacheControl()).contains("no-store");
            assertThat(ok.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
            ResponseEntity<Map<String, Object>> miss = c.view("bogus", req);
            assertThat(miss.getStatusCode().value()).isEqualTo(404);
            assertThat(miss.getHeaders().getCacheControl()).contains("no-store");
            for (int i = 1; i < 10; i++) c.view("bogus" + i, req);
            assertThat(c.view(token, req).getStatusCode().value()).isEqualTo(429);
            MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/invoice/view");
            other.setRemoteAddr("10.0.0.10");
            assertThat(c.view(token, other).getStatusCode().value()).isEqualTo(200);
        }

        @Test @DisplayName("a church session for another client gets the generic 404")
        void controllerSession() {
            InvoiceController c = new InvoiceController(billing);
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/invoice/view");
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("appClientId", "CHR-OTHER");
            s.setAttribute("username", "pastor");
            s.setAttribute("role", "Admin");
            req.setSession(s);
            assertThat(c.view(token, req).getStatusCode().value()).isEqualTo(404);
            s.setAttribute("appClientId", "CHR-1");
            assertThat(c.view(token, req).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test @DisplayName("an expired church can reach /api/invoice, and the public filters list it")
    void filters() throws Exception {
        ServiceClientRepository cr = mock(ServiceClientRepository.class);
        ServiceClient expired = client(8, "CHR-8", "STANDARD", "10", AppClock.today());
        when(cr.findByClientId("CHR-8")).thenReturn(Optional.of(expired));
        DemoRoleAccessRepository access = mock(DemoRoleAccessRepository.class);
        when(access.findByUsername(anyString())).thenReturn(Optional.empty());
        AccountStatusService status = new AccountStatusService(cr, access);
        for (String uri : List.of("/api/invoice/view", "/api/members")) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("appClientId", "CHR-8");
            session.setAttribute("username", "pastor");
            MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
            req.setSession(session);
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
            new AccountStatusFilter(status).doFilter(req, res, chain);
            assertThat(res.getStatus()).as(uri).isEqualTo(uri.startsWith("/api/invoice") ? 200 : 403);
        }
        String auth = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/churchgeniuspro/webfilter/AuthFilter.java"));
        String agree = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/churchgeniuspro/webfilter/DemoTrialAgreementFilter.java"));
        assertThat(auth).contains("\"/api/invoice\",");
        assertThat(agree).contains("\"/api/invoice\"");
    }

    @Test @DisplayName("Service Admin billing endpoints refuse anyone else")
    void adminOnly() {
        ServiceAdminBillingController c = new ServiceAdminBillingController(billing, settings);
        MockHttpServletRequest anon = new MockHttpServletRequest();
        MockHttpServletRequest church = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession(); s.setAttribute("role", "Admin"); church.setSession(s);
        for (MockHttpServletRequest r : List.of(anon, church)) {
            assertThat(c.upcoming(r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.invoices(r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.charges(r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.createCharge(Map.of(), r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.create(Map.of("clientId", "CHR-1"), r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.send(1L, null, r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.markPaid(1L, null, r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.voidInvoice(1L, null, r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.putSettings(Map.of("clientEnabled", true), r).getStatusCode().value()).isEqualTo(401);
        }
        verifyNoInteractions(email);
        verify(settings, never()).set(anyString(), any(), any());
    }

    @Test @DisplayName("Upcoming lists paid-plan clients due within 10 days with their renewal invoice")
    void upcoming() {
        client(11, "CHR-11", "PRO", null, TODAY.plusDays(11));                 // too far
        client(12, "CHR-12", "TRIAL", null, TODAY.plusDays(2));                // trial
        client(13, "DEMO-13", "PRO", null, TODAY.plusDays(2));                 // demo
        client(14, "CHR-14", "PRO", null, TODAY).setPaymentStatus("NOT_REQUIRED");
        client(15, "CHR-15", "PRO", null, TODAY);
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        List<Map<String, Object>> up = billing.upcoming();
        assertThat(up).extracting(m -> m.get("clientId")).containsExactlyInAnyOrder("CHR-1", "CHR-15");
        Map<String, Object> one = up.stream().filter(m -> "CHR-1".equals(m.get("clientId"))).findFirst().orElseThrow();
        assertThat(one).containsEntry("invoiceId", inv.getId()).containsEntry("invoiceStatus", "DRAFT").containsEntry("daysLeft", 10L);
        assertThat((BigDecimal) one.get("price")).isEqualByComparingTo("40.00");
    }

    // ══ Billing reminders ══════════════════════════════════════════════════

    @Nested @DisplayName("Billing reminders")
    class Reminders {
        ReminderSentLogRepository sentLog;
        BillingReminderScheduler job;
        boolean supportOn, clientOn;

        @BeforeEach
        void wire() {
            sentLog = mock(ReminderSentLogRepository.class);
            when(sentLog.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(anyString(), anyString(), anyString(), any()))
                    .thenAnswer(i -> sentLogRows.stream().anyMatch(r -> r.getAppClientId().equals(i.getArgument(0))
                            && r.getReminderType().equals(i.getArgument(1)) && r.getReferenceKey().equals(i.getArgument(2))
                            && r.getSentDate().equals(i.getArgument(3))));
            when(sentLog.saveAndFlush(any(ReminderSentLog.class))).thenAnswer(i -> { sentLogRows.add(i.getArgument(0)); return i.getArgument(0); });
            doAnswer(i -> { sentLogRows.remove((ReminderSentLog) i.getArgument(0)); return null; }).when(sentLog).delete(any(ReminderSentLog.class));
            when(settings.isEnabled(PlatformSettingService.BILLING_REMINDER_SUPPORT_ENABLED)).thenAnswer(i -> supportOn);
            when(settings.isEnabled(PlatformSettingService.BILLING_REMINDER_CLIENT_ENABLED)).thenAnswer(i -> clientOn);
            job = new BillingReminderScheduler(billing, settings, sentLog, email);
        }

        @Test @DisplayName("both switches off (the default): nothing is read, created or sent")
        void offByDefault() {
            assertThat(job.runFor(TODAY)).isEmpty();
            assertThat(mails).isEmpty();
            assertThat(invRows).isEmpty();
            verify(clients, never()).findByEndDateBetweenAndStatusAndDeleteFlagFalseOrderByEndDateAscIdAsc(any(), any(), any());
        }

        @Test @DisplayName("support only: one digest at 10/3/0 days, once per day; no invoice is created or emailed to the client")
        void supportOnly() {
            supportOn = true;
            client(2, "CHR-2", "PRO", null, TODAY.plusDays(3));
            client(3, "CHR-3", "PRO", null, TODAY.plusDays(4));               // not a reminder day
            List<BillingReminderScheduler.Row> rows = job.runFor(TODAY);
            assertThat(rows).extracting(r -> r.client().getClientId()).containsExactlyInAnyOrder("CHR-1", "CHR-2");
            assertThat(mails).hasSize(1);
            assertThat(mails.get(0)[0]).isEqualTo("support@cgp.test");
            assertThat(mails.get(0)[2]).contains("Church CHR-1").contains("Church CHR-2").contains("No invoice yet").doesNotContain("CHR-3");
            assertThat(invRows).isEmpty();
            job.runFor(TODAY);
            assertThat(mails).hasSize(1);
        }

        @Test @DisplayName("client on: creates and sends the renewal invoice at 10 days, re-sends it at 3 and 0 days, once each")
        void clientInvoices() {
            clientOn = true;
            LocalDate due = paid.getEndDate();                        // TODAY + 10
            job.runFor(TODAY);
            assertThat(invRows.values()).singleElement().satisfies(i -> {
                assertThat(i.getStatus()).isEqualTo(BillingInvoice.SENT);
                assertThat(i.getSentBy()).isEqualTo(BillingReminderScheduler.ACTOR);
            });
            assertThat(mails).hasSize(1);
            assertThat(mails.get(0)[1]).startsWith("Invoice ");
            job.runFor(TODAY);                                        // same day again
            assertThat(mails).hasSize(1);
            job.runFor(due.minusDays(3));
            assertThat(mails).hasSize(2);
            assertThat(mails.get(1)[1]).startsWith("Reminder: invoice ");
            assertThat(mails.get(1)[2]).contains("due in 3 days");
            job.runFor(due);
            assertThat(mails).hasSize(3);
            assertThat(mails.get(2)[2]).contains("due today");
            assertThat(invRows).hasSize(1);
        }

        @Test @DisplayName("client on: drafts, paid and voided renewal invoices are left alone")
        void leftAlone() {
            clientOn = true; supportOn = true;
            client(2, "CHR-2", "PRO", null, TODAY.plusDays(10));
            client(3, "CHR-3", "PRO", null, TODAY.plusDays(10));
            BillingInvoice draft = billing.createRenewalDraft("CHR-1", "admin").invoice();
            BillingInvoice paidInv = billing.createRenewalDraft("CHR-2", "admin").invoice();
            billing.send(paidInv.getId(), null, "admin");
            billing.markPaid(paidInv.getId(), TODAY, "CHECK", null, "admin");
            BillingInvoice voided = billing.createRenewalDraft("CHR-3", "admin").invoice();
            billing.voidInvoice(voided.getId(), null, "admin");
            mails.clear();
            List<BillingReminderScheduler.Row> rows = job.runFor(TODAY);
            assertThat(rows).extracting(BillingReminderScheduler.Row::outcome).containsExactlyInAnyOrder(
                    "Draft " + draft.getInvoiceNumber() + " is waiting for review — not sent automatically",
                    "Paid (" + paidInv.getInvoiceNumber() + ")",
                    "Renewal invoice was voided — not re-created");
            assertThat(mails).hasSize(1);                                  // the support digest only
            assertThat(mails.get(0)[0]).isEqualTo("support@cgp.test");
            assertThat(invRows.get(draft.getId()).getStatus()).isEqualTo(BillingInvoice.DRAFT);
        }

        @Test @DisplayName("D: a failed automatic email leaves a draft that is NEVER sent automatically on later reminder days")
        void failedAutoEmailStaysDraft() {
            clientOn = true; supportOn = true;
            mailFails = true;
            List<BillingReminderScheduler.Row> rows = job.runFor(TODAY);
            assertThat(rows.get(0).outcome()).contains("email failed").contains("will not be sent automatically");
            BillingInvoice d = invRows.values().iterator().next();
            assertThat(d.getStatus()).isEqualTo(BillingInvoice.DRAFT);
            mailFails = false;
            mails.clear();
            for (LocalDate day : List.of(TODAY, paid.getEndDate().minusDays(3), paid.getEndDate())) {   // same day again, 3 days, 0 days
                List<BillingReminderScheduler.Row> later = job.runFor(day);
                assertThat(later.get(0).outcome()).as(day.toString())
                        .contains("waiting for review").contains("not sent automatically").contains("automatic email failed");
            }
            assertThat(invRows.get(d.getId()).getStatus()).isEqualTo(BillingInvoice.DRAFT);
            assertThat(invRows).hasSize(1);
            // Only Support Email summaries went out, each naming the waiting draft.
            assertThat(mails).isNotEmpty().allSatisfy(m -> {
                assertThat(m[0]).isEqualTo("support@cgp.test");
                assertThat(m[2]).contains(d.getInvoiceNumber()).contains("waiting for review");
            });
        }

        @Test @DisplayName("a $0 client gets no invoice")
        void zeroPrice() {
            clientOn = true;
            paid.setSubscriptionPrice(BigDecimal.ZERO);
            assertThat(job.runFor(TODAY).get(0).outcome()).contains("$0.00");
            assertThat(mails).isEmpty();
        }
    }

    // ══ Fixes after the read-only verification (A, B, C, D, expiry, charge month) ══

    @Nested @DisplayName("A — Send is atomic")
    class AtomicSend {
        @Test @DisplayName("Sent and Billed happen in the same step, before the email")
        void sentAndBilledTogether() throws Exception {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            List<String> seenAtEmail = new ArrayList<>();
            doAnswer(i -> { seenAtEmail.add(invRows.get(inv.getId()).getStatus() + "/" + c.getStatus()); return null; })
                    .when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());
            billing.send(inv.getId(), null, "a");
            assertThat(seenAtEmail).containsExactly("SENT/BILLED");
            org.mockito.InOrder order = inOrder(invoices, charges, email);
            order.verify(invoices).lockById(inv.getId());
            order.verify(invoices).markSent(eq(inv.getId()), anyLong(), any(), any(), any(), anyString(), any(), any(), any(), any());
            order.verify(charges).billForInvoice(eq(inv.getId()), any(), any());
            order.verify(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());
        }

        @Test @DisplayName("email failure: one compensation returns the invoice to Draft and the charges to Unbilled (still reserved)")
        void compensation() throws Exception {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            mailFails = true;
            assertThatThrownBy(() -> billing.send(inv.getId(), null, "a")).hasMessageContaining("still a draft and its charges are unbilled");
            assertThat(invRows.get(inv.getId()).getStatus()).isEqualTo(BillingInvoice.DRAFT);
            assertThat(invRows.get(inv.getId()).getAccessTokenHash()).isNull();
            assertThat(c.getStatus()).isEqualTo(ClientCharge.UNBILLED);
            assertThat(c.getInvoiceId()).isEqualTo(inv.getId());
            verify(charges).unbillForInvoice(eq(inv.getId()), any(), any());
        }

        @Test @DisplayName("an invoice voided while its email was being sent is not touched by the compensation")
        void voidedDuringEmail() throws Exception {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            doAnswer(i -> { billing.voidInvoice(inv.getId(), "changed mind", "other admin"); throw new RuntimeException("SMTP down"); })
                    .when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());
            assertThatThrownBy(() -> billing.send(inv.getId(), null, "a")).hasMessageContaining("changed by someone else");
            assertThat(invRows.get(inv.getId()).getStatus()).isEqualTo(BillingInvoice.VOID);
            assertThat(c.getStatus()).isEqualTo(ClientCharge.UNBILLED);
            assertThat(c.getInvoiceId()).isNull();
            verify(charges, never()).unbillForInvoice(anyLong(), any(), any());
        }

        @Test @DisplayName("if the compensation itself fails, the invoice stays Sent WITH Billed charges (consistent) and the admin is told")
        void compensationFails() throws Exception {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            mailFails = true;
            when(invoices.revertSend(anyLong(), anyString())).thenThrow(new RuntimeException("db down"));
            assertThatThrownBy(() -> billing.send(inv.getId(), null, "a"))
                    .hasMessageContaining("marked Sent with its charges billed").hasMessageContaining("Re-send");
            assertThat(invRows.get(inv.getId()).getStatus()).isEqualTo(BillingInvoice.SENT);
            assertThat(c.getStatus()).isEqualTo(ClientCharge.BILLED);
        }

        @Test @DisplayName("charges are never billed when the invoice does not move to Sent; a lost charge stops the send")
        void noBillingWithoutSent() throws Exception {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            Long seen = inv.getVersion();
            billing.updateDraft(inv.getId(), form(inv, "1", currentLines(inv.getId())), "other admin");
            assertThatThrownBy(() -> billing.send(inv.getId(), seen, "a")).hasMessageContaining("changed by someone else");
            when(invoices.markSent(anyLong(), anyLong(), any(), any(), any(), anyString(), any(), any(), any(), any())).thenReturn(0);
            assertThatThrownBy(() -> billing.send(inv.getId(), null, "a")).hasMessageContaining("changed by someone else");
            verify(charges, never()).billForInvoice(anyLong(), any(), any());
            assertThat(c.getStatus()).isEqualTo(ClientCharge.UNBILLED);
            assertThat(mails).isEmpty();
        }

        @Test @DisplayName("a charge line whose charge is no longer held by the draft refuses the send before anything changes")
        void lostCharge() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            c.setInvoiceId(999L);   // as if taken elsewhere
            assertThatThrownBy(() -> billing.send(inv.getId(), null, "a")).hasMessageContaining("no longer available");
            assertThat(invRows.get(inv.getId()).getStatus()).isEqualTo(BillingInvoice.DRAFT);
            verify(invoices, never()).markSent(anyLong(), anyLong(), any(), any(), any(), anyString(), any(), any(), any(), any());
        }
    }

    @Nested @DisplayName("B — a charge is claimed by exactly one invoice")
    class ChargeClaims {
        @Test @DisplayName("two drafts created at the same moment: the one that loses the claim does not include the charge")
        void concurrentCreation() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            // Both creators listed the charge as free before either reserved it.
            List<ClientCharge> listedFree = List.of(c);
            when(charges.findByClientIdAndStatusAndInvoiceIdIsNullOrderByCreatedAtAscIdAsc(eq("CHR-1"), anyString())).thenReturn(listedFree);
            when(charges.findByClientIdAndStatusAndInvoiceIdIsNullAndBillingPeriodLessThanEqualOrderByCreatedAtAscIdAsc(eq("CHR-1"), anyString(), anyString()))
                    .thenReturn(listedFree);
            BillingInvoice manual = billing.createManualDraft("CHR-1", "admin").invoice();          // Service Admin
            BillingInvoice renewal = billing.createRenewalDraft("CHR-1", BillingReminderScheduler.ACTOR).invoice(); // reminder job
            long onManual = linesOf(manual.getId()).stream().filter(l -> c.getId().equals(l.getChargeId())).count();
            long onRenewal = linesOf(renewal.getId()).stream().filter(l -> c.getId().equals(l.getChargeId())).count();
            assertThat(onManual + onRenewal).isEqualTo(1);
            assertThat(onManual).isEqualTo(1);
            assertThat(c.getInvoiceId()).isEqualTo(manual.getId());
            assertThat(invRows.get(renewal.getId()).getTotal()).isEqualByComparingTo("40.00");   // subscription only
            billing.send(manual.getId(), null, "a");
            billing.send(renewal.getId(), null, "a");
            assertThat(c.getStatus()).isEqualTo(ClientCharge.BILLED);
            assertThat(invRows.values().stream().filter(i -> !"VOID".equals(i.getStatus()))
                    .flatMap(i -> linesOf(i.getId()).stream()).filter(l -> c.getId().equals(l.getChargeId())).count()).isEqualTo(1);
        }

        @Test @DisplayName("adding a charge in review that another invoice just claimed is refused")
        void reviewAddLosesClaim() {
            BillingInvoice a = billing.createManualDraft("CHR-1", "admin").invoice();
            ClientCharge c = charge("CHR-1", "Setup", "20");           // free, listed in A's review screen
            BillingInvoice b = billing.createManualDraft("CHR-1", "admin2").invoice();   // B claims it first
            assertThat(c.getInvoiceId()).isEqualTo(b.getId());
            List<BillingService.LineForm> ls = List.of(new BillingService.LineForm("CHARGE", null, null, c.getId()));
            assertThatThrownBy(() -> billing.updateDraft(a.getId(), form(a, "0", ls), "admin"))
                    .hasMessageContaining("no longer available");
            assertThat(c.getInvoiceId()).isEqualTo(b.getId());
        }

        @Test @DisplayName("void/mark-paid of a charge claimed a moment ago is refused, not applied over the claim")
        void staleChargeActions() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            when(charges.findById(c.getId())).thenAnswer(i -> {           // caller's copy: still free
                ClientCharge copy = new ClientCharge();
                copy.setId(c.getId()); copy.setStatus(ClientCharge.UNBILLED); copy.setClientId("CHR-1");
                return Optional.of(copy);
            });
            c.setInvoiceId(777L);                                          // …but the row was just claimed
            assertThatThrownBy(() -> billing.voidCharge(c.getId(), "a")).hasMessageContaining("changed at the same moment");
            assertThatThrownBy(() -> billing.markChargePaid(c.getId(), TODAY, "CASH", "a")).hasMessageContaining("changed at the same moment");
            assertThat(c.getStatus()).isEqualTo(ClientCharge.UNBILLED);
            assertThat(c.getInvoiceId()).isEqualTo(777L);
        }

        @Test @DisplayName("voiding returns the charges to Unbilled exactly once")
        void voidOnce() {
            ClientCharge c = charge("CHR-1", "Setup", "20");
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            billing.send(inv.getId(), null, "a");
            billing.voidInvoice(inv.getId(), null, "a");
            assertThatThrownBy(() -> billing.voidInvoice(inv.getId(), null, "a")).hasMessageContaining("can be voided");
            verify(charges, times(1)).releaseForInvoice(eq(inv.getId()), any(), any());
            assertThat(c.getStatus()).isEqualTo(ClientCharge.UNBILLED);
            assertThat(c.getInvoiceId()).isNull();
        }
    }

    @Nested @DisplayName("C — renewal identity is the billing date, not the due date")
    class RenewalIdentity {
        @Test @DisplayName("create renewal → change its due date → another renewal for the same billing period is refused")
        void dueDateEditDoesNotAllowSecond() {
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "admin").invoice();          // 1. create
            LocalDate billingDate = inv.getPeriodStart();
            billing.updateDraft(inv.getId(), new BillingService.DraftForm(inv.getVersion(), null, "chr-1@church.test",
                    billingDate.plusDays(14), null, "0", currentLines(inv.getId())), "admin");        // 2. change due date
            assertThat(invRows.get(inv.getId()).getDueDate()).isEqualTo(billingDate.plusDays(14));
            assertThat(invRows.get(inv.getId()).getPeriodStart()).isEqualTo(billingDate);
            BillingService.Draft again = billing.createRenewalDraft("CHR-1", "admin");             // 3. attempt another
            assertThat(again.created()).isFalse();                                                  // 4. refused: the existing one
            assertThat(again.invoice().getId()).isEqualTo(inv.getId());
            assertThat(invRows).hasSize(1);
            assertThat(billing.upcoming().get(0)).containsEntry("invoiceId", inv.getId());
        }

        @Test @DisplayName("the reminder job does not create a second renewal after a due-date edit")
        void reminderAfterDueDateEdit() {
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "admin").invoice();
            billing.send(inv.getId(), null, "admin");
            invRows.get(inv.getId()).setDueDate(inv.getPeriodStart().plusDays(14));   // (sent invoices are frozen; simulates an edited draft)
            ReminderSentLogRepository sentLog = mock(ReminderSentLogRepository.class);
            when(sentLog.saveAndFlush(any(ReminderSentLog.class))).thenAnswer(i -> i.getArgument(0));
            when(settings.isEnabled(PlatformSettingService.BILLING_REMINDER_CLIENT_ENABLED)).thenReturn(true);
            new BillingReminderScheduler(billing, settings, sentLog, email).runFor(TODAY);
            assertThat(invRows).hasSize(1);
        }

        @Test @DisplayName("the database rule (simulated V11) refuses a second live renewal for the same billing date")
        void databaseRule() {
            BillingInvoice first = billing.createRenewalDraft("CHR-1", "admin").invoice();
            BillingInvoice dup = new BillingInvoice();
            dup.setClientId("CHR-1"); dup.setKind(BillingInvoice.KIND_RENEWAL); dup.setStatus(BillingInvoice.DRAFT);
            dup.setPeriodStart(first.getPeriodStart()); dup.setDueDate(first.getDueDate().plusDays(30));
            assertThatThrownBy(() -> invoices.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test @DisplayName("manual and subscription-request invoices may share the renewal's date")
        void sharedDates() {
            client(6, "CHR-6", "STANDARD", "40", TODAY);
            BillingInvoice renewal = billing.createRenewalDraft("CHR-6", "a").invoice();
            BillingInvoice manual = billing.createManualDraft("CHR-6", "a").invoice();
            SubscriptionRequest r = new SubscriptionRequest();
            r.setId(31L); r.setClientId("CHR-6"); r.setPlanCode("PRO"); r.setBillingFrequency("MONTHLY");
            r.setStatus(SubscriptionRequest.NEW); r.setRegisteredEmail("x@church.test");
            when(requests.findById(31L)).thenReturn(Optional.of(r));
            BillingInvoice request = billing.createRequestDraft(31L, "a").invoice();
            assertThat(List.of(renewal.getDueDate(), manual.getDueDate(), request.getDueDate())).containsOnly(TODAY);
            assertThat(renewal.getPeriodStart()).isEqualTo(request.getPeriodStart());
            assertThat(invRows.values()).hasSize(3).allMatch(i -> "DRAFT".equals(i.getStatus()));
        }
    }

    @Nested @DisplayName("Invoice link expiry: strictly due date + 60 days")
    class Expiry {
        @Test @DisplayName("an overdue invoice sent late still expires 60 days after its due date; re-send keeps it")
        void lateSend() {
            client(20, "CHR-20", "PRO", "10", TODAY.minusDays(5));
            BillingInvoice inv = billing.createRenewalDraft("CHR-20", "a").invoice();
            String first = tokenOf(billing.send(inv.getId(), null, "a").link());
            assertThat(invRows.get(inv.getId()).getAccessTokenExpires()).isEqualTo(TODAY.minusDays(5).plusDays(60));
            billing.resend(inv.getId(), "a");
            assertThat(invRows.get(inv.getId()).getAccessTokenExpires()).isEqualTo(TODAY.minusDays(5).plusDays(60));
            assertThat(billing.publicView(first, null)).isEmpty();
        }

        @Test @DisplayName("works through the expiry day (Chicago date), not the day after")
        void boundary() {
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            String t = tokenOf(billing.send(inv.getId(), null, "a").link());
            assertThat(invRows.get(inv.getId()).getAccessTokenExpires()).isEqualTo(inv.getDueDate().plusDays(60));
            invRows.get(inv.getId()).setAccessTokenExpires(AppClock.today());
            assertThat(billing.publicView(t, null)).isPresent();
            invRows.get(inv.getId()).setAccessTokenExpires(AppClock.today().minusDays(1));
            assertThat(billing.publicView(t, null)).isEmpty();
        }

        @Test @DisplayName("sending or re-sending when due date + 60 has already passed is refused (the link would be dead)")
        void alreadyExpired() {
            client(21, "CHR-21", "PRO", "10", TODAY.minusDays(61));
            BillingInvoice inv = billing.createRenewalDraft("CHR-21", "a").invoice();
            assertThatThrownBy(() -> billing.send(inv.getId(), null, "a")).hasMessageContaining("already be expired");
            assertThat(invRows.get(inv.getId()).getStatus()).isEqualTo(BillingInvoice.DRAFT);
            billing.updateDraft(inv.getId(), new BillingService.DraftForm(invRows.get(inv.getId()).getVersion(), null, "a@b.co",
                    TODAY, null, "0", currentLines(inv.getId())), "a");
            billing.send(inv.getId(), null, "a");
            invRows.get(inv.getId()).setDueDate(TODAY.minusDays(61));
            assertThatThrownBy(() -> billing.resend(inv.getId(), "a")).hasMessageContaining("cannot be re-sent");
        }
    }

    @Nested @DisplayName("Renewal charge selection by billing month")
    class ChargeMonth {
        @Test @DisplayName("a renewal takes charges of its billing month and earlier, never a later month's")
        void monthRule() {
            java.time.YearMonth billingMonth = java.time.YearMonth.from(paid.getEndDate());
            ClientCharge same = chargeFor("CHR-1", "This month", "10", billingMonth.toString());
            ClientCharge earlier = chargeFor("CHR-1", "Last month", "5", billingMonth.minusMonths(1).toString());
            ClientCharge later = chargeFor("CHR-1", "Next month", "99", billingMonth.plusMonths(1).toString());
            BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
            assertThat(linesOf(inv.getId())).extracting(BillingInvoiceLine::getChargeId).containsExactlyInAnyOrder(null, same.getId(), earlier.getId());
            assertThat(later.getInvoiceId()).isNull();
            assertThat(inv.getTotal()).isEqualByComparingTo("55.00");
            // The later charge stays free: a manual invoice, or the review screen, can still take it.
            assertThat(((List<?>) billing.detail(inv.getId()).get("availableCharges"))).hasSize(1);
        }
    }

    @Test @DisplayName("normal flow still works end to end: edit, send, re-send, mark paid; and edit, send, void")
    void normalFlows() {
        ClientCharge c = charge("CHR-1", "Setup", "20");
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        billing.updateDraft(inv.getId(), form(inv, "5", currentLines(inv.getId())), "a");
        String l1 = tokenOf(billing.send(inv.getId(), null, "a").link());
        String l2 = tokenOf(billing.resend(inv.getId(), "a").link());
        assertThat(billing.publicView(l1, null)).isEmpty();
        assertThat(billing.publicView(l2, null).orElseThrow()).containsEntry("total", new BigDecimal("55.00"));
        billing.markPaid(inv.getId(), TODAY, "CHECK", "42", "a");
        assertThat(c.getStatus()).isEqualTo(ClientCharge.PAID);
        assertThat(billing.publicView(l2, null).orElseThrow()).containsEntry("state", "PAID");

        ClientCharge d = charge("CHR-1", "Training", "50");
        BillingInvoice m = billing.createManualDraft("CHR-1", "a").invoice();
        billing.send(m.getId(), null, "a");
        assertThat(d.getStatus()).isEqualTo(ClientCharge.BILLED);
        billing.voidInvoice(m.getId(), null, "a");
        assertThat(d.getStatus()).isEqualTo(ClientCharge.UNBILLED);
        assertThat(d.getInvoiceId()).isNull();
    }
}
