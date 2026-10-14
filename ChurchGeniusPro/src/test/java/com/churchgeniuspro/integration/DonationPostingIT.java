package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.DonationPostingController;
import com.churchgeniuspro.controller.DonationReviewController;
import com.churchgeniuspro.controller.DonationSettingsController;
import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.DonationPostingService;
import com.churchgeniuspro.service.PermissionRefresher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Donation Review posting against real PostgreSQL (V14 schema, the real
 * {@code ux_income_import_ref} / {@code ux_expense_import_ref} indexes, real row
 * locks): the $100 / $3.20 example end to end; two concurrent posts of one donation
 * produce exactly one Income and one Expense; a batch is per-donation isolated;
 * another church cannot post or see this church's donation; Return to Pending
 * keeps the ledger rows and blocks re-posting; and the Income/Financial/Tax report
 * totals come out at gross income and net balance. Requires Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "app.base-url=http://localhost")
class DonationPostingIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired DonationRepository donations;
    @Autowired IncomeRepository incomes;
    @Autowired ExpenseRepository expenses;
    @Autowired SubSourceRepository subSources;
    @Autowired MainSourceRepository mainSources;
    @Autowired PurposeRepository purposes;
    @Autowired TransactionTypeRepository transactionTypes;
    @Autowired DonationSettingsController settingsCtl;
    @Autowired DonationPostingController postingCtl;
    @Autowired DonationReviewController reviewCtl;
    @Autowired DonationPostingService posting;
    @Autowired JdbcTemplate jdbc;

    String client;
    Integer tithe, bankTransfer, admin, general;

    static MockHttpServletRequest staff(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "bookkeeper@" + clientId.toLowerCase() + ".test");
        s.setAttribute("role", "Accountant");
        s.setAttribute("appClientId", clientId);
        s.setAttribute(PermissionRefresher.CHECKED_AT, System.currentTimeMillis());
        req.setSession(s);
        return req;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    @BeforeEach
    void church() {
        client = "CHR-" + UUID.randomUUID().toString().substring(0, 8);
        MainSource gen = new MainSource(); gen.setSourceName("General"); gen.setAppClientId(client); gen = mainSources.saveAndFlush(gen);
        general = gen.getId();
        SubSource t = new SubSource(); t.setSourceName("Tithe"); t.setMainSource(gen); t.setAppClientId(client); t.setTaxDeductible(true);
        tithe = subSources.saveAndFlush(t).getId();
        TransactionType bt = new TransactionType(); bt.setTypeName("Bank Transfer"); bt.setAppClientId(client);
        bankTransfer = transactionTypes.saveAndFlush(bt).getId();
        Purpose a = new Purpose(); a.setPurposeName("Administrative"); a.setAppClientId(client);
        admin = purposes.saveAndFlush(a).getId();

        Map<String, Object> s = new HashMap<>();
        s.put("incomeSubSourceId", tithe); s.put("incomeTransactionTypeId", bankTransfer);
        s.put("expensePurposeId", admin); s.put("expenseMainSourceId", general); s.put("expenseTransactionTypeId", bankTransfer);
        s.put("dateMode", "DONATION_DATE"); s.put("feePercent", "2.9"); s.put("feeFixed", "0.30");
        ResponseEntity<?> r = settingsCtl.put(s, staff(client));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(body(r)).containsEntry("complete", true);
    }

    Donation donation(String amount, BigDecimal stripeFee) {
        Donation d = new Donation();
        d.setClientId(client);
        d.setFirstName("Binny"); d.setLastName("Varghese"); d.setEmail("binny@" + client.toLowerCase() + ".test");
        d.setAmount(new BigDecimal(amount)); d.setIntendedAmount(new BigDecimal(amount)); d.setFeeCovered(BigDecimal.ZERO);
        d.setCurrency("USD"); d.setStatus("succeeded");
        d.setPaymentMethod("Visa •••• 6676");
        d.setNote("Rajan Uncle");
        d.setStripePaymentIntentId("pi_it_" + UUID.randomUUID().toString().replace("-", ""));
        d.setDonatedAt(LocalDateTime.of(2026, 9, 27, 10, 15));
        if (stripeFee != null) {
            d.setStripeFee(stripeFee); d.setStripeNet(new BigDecimal(amount).subtract(stripeFee));
            d.setStripeBalanceTransactionId("txn_it"); d.setFeeSource(Donation.FEE_SOURCE_STRIPE);
        }
        return donations.saveAndFlush(d);
    }

    @Test
    @DisplayName("V14 schema is present: donation_settings table and the donation fee/posting columns")
    void schemaPresent() {
        assertThat(jdbc.queryForObject("select to_regclass('public.donation_settings') is not null", Boolean.class)).isTrue();
        for (String col : List.of("stripe_fee", "stripe_net", "fee_source", "contributor_category", "matched_member_id", "posted_income_id", "posted_expense_id")) {
            Integer n = jdbc.queryForObject("select count(*) from information_schema.columns where table_name='donation' and column_name=?", Integer.class, col);
            assertThat(n).as(col).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("$100.00 with Stripe's $3.20: Income $100.00 + Expense $3.20 linked back, net balance $96.80 in the reports")
    void endToEnd() {
        Donation d = donation("100.00", new BigDecimal("3.20"));
        ResponseEntity<?> r = postingCtl.post(d.getId(), Map.of(), staff(client));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(body(r)).containsEntry("status", "POSTED");
        Integer incomeId = (Integer) body(r).get("incomeId"), expenseId = (Integer) body(r).get("expenseId");

        Income inc = incomes.findById(incomeId).orElseThrow();
        assertThat(inc.getAmount()).isEqualByComparingTo("100.00");
        assertThat(inc.getSubSource().getId()).isEqualTo(tithe);
        assertThat(inc.getTransactionType().getTypeName()).isEqualTo("Bank Transfer");   // not the card label
        assertThat(inc.getImportRef()).isEqualTo(d.getStripePaymentIntentId());
        assertThat(inc.getNote()).startsWith("Visa •••• 6676 — Online donation — Rajan Uncle");
        assertThat(inc.getCreatedBy()).isEqualTo("donation-review");
        Expense exp = expenses.findById(expenseId).orElseThrow();
        assertThat(exp.getAmount()).isEqualByComparingTo("3.20");
        assertThat(exp.getPurpose().getId()).isEqualTo(admin);
        assertThat(exp.getImportRef()).isEqualTo(d.getStripePaymentIntentId() + ":stripe-fee");

        Donation after = donations.findById(d.getId()).orElseThrow();
        assertThat(after.getPostedIncomeId()).isEqualTo(incomeId);
        assertThat(after.getPostedExpenseId()).isEqualTo(expenseId);
        assertThat(after.isReviewCompleted()).isTrue();

        // No card-label transaction type was created for this church.
        assertThat(transactionTypes.findFirstByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse("Visa •••• 6676", client)).isEmpty();

        // Report arithmetic from the ledger: gross income, fee expense, net = bank deposit.
        BigDecimal income = jdbc.queryForObject("select coalesce(sum(amount),0) from income where app_client_id=? and delete_flag=false", BigDecimal.class, client);
        BigDecimal expense = jdbc.queryForObject("select coalesce(sum(amount),0) from expense where app_client_id=? and delete_flag=false", BigDecimal.class, client);
        assertThat(income).isEqualByComparingTo("100.00");
        assertThat(expense).isEqualByComparingTo("3.20");
        assertThat(income.subtract(expense)).isEqualByComparingTo("96.80");
        // Tax statement reads income on tax-deductible funds: the full gift.
        BigDecimal tax = jdbc.queryForObject("select coalesce(sum(i.amount),0) from income i join sub_source ss on ss.id=i.sub_source_id where i.app_client_id=? and i.delete_flag=false and ss.tax_deductible=true", BigDecimal.class, client);
        assertThat(tax).isEqualByComparingTo("100.00");

        // A second post is a no-op.
        ResponseEntity<?> again = postingCtl.post(d.getId(), Map.of(), staff(client));
        assertThat(body(again)).containsEntry("status", "ALREADY_POSTED").containsEntry("incomeId", incomeId);
        assertThat(jdbc.queryForObject("select count(*) from income where app_client_id=?", Integer.class, client)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from expense where app_client_id=?", Integer.class, client)).isEqualTo(1);
    }

    @Test
    @DisplayName("eight concurrent posts of one donation → exactly one Income and one Expense")
    void concurrentPostsProduceOneRowEach() throws Exception {
        Donation d = donation("100.00", new BigDecimal("3.20"));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit((Callable<String>) () -> {
                go.await();
                try {
                    return posting.post(d.getId(), client, DonationPostingService.Overrides.NONE, "t").status();
                } catch (RuntimeException ex) {
                    return "EX:" + ex.getClass().getSimpleName();
                }
            }));
        }
        go.countDown();
        List<String> statuses = new ArrayList<>();
        for (Future<String> f : results) statuses.add(f.get());
        pool.shutdown();

        assertThat(statuses).as(statuses.toString()).contains("POSTED");
        assertThat(statuses.stream().filter("POSTED"::equals).count()).isEqualTo(1);
        assertThat(statuses).allSatisfy(s -> assertThat(s).isIn("POSTED", "ALREADY_POSTED"));
        assertThat(jdbc.queryForObject("select count(*) from income where app_client_id=? and import_ref=?", Integer.class, client, d.getStripePaymentIntentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from expense where app_client_id=? and import_ref=?", Integer.class, client, d.getStripePaymentIntentId() + ":stripe-fee")).isEqualTo(1);
    }

    @Test
    @DisplayName("batch: one donation refused (fee unknown, no rate) does not stop or roll back the others")
    void batchIsolation() {
        // Remove the fee rate so an unknown fee cannot be estimated.
        Map<String, Object> s = new HashMap<>();
        s.put("incomeSubSourceId", tithe); s.put("incomeTransactionTypeId", bankTransfer);
        s.put("expensePurposeId", admin); s.put("expenseMainSourceId", general); s.put("expenseTransactionTypeId", bankTransfer);
        s.put("dateMode", "DONATION_DATE");
        settingsCtl.put(s, staff(client));

        Donation a = donation("100.00", new BigDecimal("3.20"));
        Donation b = donation("25.00", null);                 // no Stripe fee, no charge id, no rate → refused
        Donation c = donation("50.00", new BigDecimal("1.75"));
        ResponseEntity<?> r = postingCtl.postBatch(Map.of("ids", List.of(a.getId(), b.getId(), c.getId())), staff(client));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(body(r)).containsEntry("posted", 2).containsEntry("failed", 1);
        assertThat(donations.findById(a.getId()).orElseThrow().isPosted()).isTrue();
        assertThat(donations.findById(b.getId()).orElseThrow().isPosted()).isFalse();
        assertThat(donations.findById(c.getId()).orElseThrow().isPosted()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from income where app_client_id=?", Integer.class, client)).isEqualTo(2);
    }

    @Test
    @DisplayName("another church cannot preview, post, or batch-post this church's donation; its own view is empty")
    void tenantIsolation() {
        Donation d = donation("100.00", new BigDecimal("3.20"));
        String other = "CHR-OTHER-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> s = new HashMap<>();
        MainSource gen = new MainSource(); gen.setSourceName("G"); gen.setAppClientId(other); gen = mainSources.saveAndFlush(gen);
        SubSource t = new SubSource(); t.setSourceName("T"); t.setMainSource(gen); t.setAppClientId(other); t = subSources.saveAndFlush(t);
        TransactionType bt = new TransactionType(); bt.setTypeName("B"); bt.setAppClientId(other); bt = transactionTypes.saveAndFlush(bt);
        Purpose a = new Purpose(); a.setPurposeName("A"); a.setAppClientId(other); a = purposes.saveAndFlush(a);
        s.put("incomeSubSourceId", t.getId()); s.put("incomeTransactionTypeId", bt.getId());
        s.put("expensePurposeId", a.getId()); s.put("expenseMainSourceId", gen.getId()); s.put("expenseTransactionTypeId", bt.getId());
        s.put("dateMode", "DONATION_DATE"); s.put("feePercent", "2.9"); s.put("feeFixed", "0.30");
        assertThat(settingsCtl.put(s, staff(other)).getStatusCode().value()).isEqualTo(200);

        assertThat(postingCtl.preview(d.getId(), staff(other)).getStatusCode().value()).isEqualTo(404);
        assertThat(postingCtl.post(d.getId(), Map.of(), staff(other)).getStatusCode().value()).isEqualTo(404);
        assertThat(postingCtl.postBatch(Map.of("ids", List.of(d.getId())), staff(other)).getStatusCode().value()).isEqualTo(404);
        assertThat(donations.findById(d.getId()).orElseThrow().isPosted()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from income where app_client_id=?", Integer.class, other)).isZero();

        // Our church's settings cannot reference the other church's fund.
        Map<String, Object> bad = new HashMap<>(s);
        assertThat(settingsCtl.put(bad, staff(client)).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("Return to Pending keeps the ledger rows and the posting links; re-posting is refused as already posted")
    void returnToPendingDoesNotRepost() {
        Donation d = donation("100.00", new BigDecimal("3.20"));
        Integer incomeId = (Integer) body(postingCtl.post(d.getId(), Map.of(), staff(client))).get("incomeId");
        ResponseEntity<?> back = reviewCtl.setReviewStatus(Map.of("ids", List.of(d.getId()), "status", "PENDING"), staff(client));
        assertThat(body(back)).containsEntry("updated", 1);
        Donation after = donations.findById(d.getId()).orElseThrow();
        assertThat(after.isReviewCompleted()).isFalse();
        assertThat(after.getPostedIncomeId()).isEqualTo(incomeId);
        assertThat(incomes.findById(incomeId)).isPresent();
        ResponseEntity<?> again = postingCtl.post(d.getId(), Map.of(), staff(client));
        assertThat(body(again)).containsEntry("status", "ALREADY_POSTED");
        assertThat(jdbc.queryForObject("select count(*) from income where app_client_id=?", Integer.class, client)).isEqualTo(1);
    }

    @Test
    @DisplayName("a donation the old automatic hook already posted is reported as historical and left untouched")
    void historicalIsReportedNotAdopted() {
        Donation d = donation("100.00", new BigDecimal("3.20"));
        // Simulate the former automatic posting: an income row with the PaymentIntent as import_ref.
        jdbc.update("insert into income (sub_source_id, income_date, transaction_type_id, amount, note, quick_add, import_ref, app_client_id, delete_flag, created_date, created_by) "
                  + "values (?, ?, ?, ?, ?, false, ?, ?, false, now(), 'online-giving')",
                tithe, java.sql.Date.valueOf("2026-09-27"), bankTransfer, new BigDecimal("100.00"), "Online donation", d.getStripePaymentIntentId(), client);
        ResponseEntity<?> r = postingCtl.post(d.getId(), Map.of(), staff(client));
        assertThat(body(r)).containsEntry("status", "ALREADY_POSTED").containsEntry("historical", true);
        Donation after = donations.findById(d.getId()).orElseThrow();
        assertThat(after.getPostedIncomeId()).isNull();                // decision 7: no backfill
        assertThat(after.isReviewCompleted()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from income where app_client_id=?", Integer.class, client)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from expense where app_client_id=?", Integer.class, client)).isZero();

        ResponseEntity<?> rec = postingCtl.reconciliation(staff(client));
        @SuppressWarnings("unchecked") Map<String, Integer> counts = (Map<String, Integer>) body(rec).get("counts");
        assertThat(counts).containsEntry("POSTED_HISTORICAL", 1);
    }
}
