package com.churchgeniuspro.payroll.pdf;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.service.SsnCrypto;
import com.churchgeniuspro.payroll.service.W2Box;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Financial audit M12d: {@code PaystubPdfService} and {@code W2PdfService} each
 * formatted money through a single {@code private static final DecimalFormat}
 * field shared by every call. {@link DecimalFormat} is documented as not
 * thread-safe (it mutates internal digit-buffer state while formatting), and a
 * Spring {@code @Service} bean is a singleton invoked from the application's
 * whole request-handling thread pool — so two employees downloading a paystub
 * or W-2 at the same moment could corrupt each other's dollar figures on a
 * legal, employee-facing tax document. The fix removes the shared field
 * entirely; both services now build a fresh {@link DecimalFormat} on every
 * {@code money()} call, which has no shared mutable state to race on.
 *
 * <p>This is verified two ways: a structural/reflection check that the shared
 * static field is actually gone (so the fix can't silently regress), and a live
 * concurrency test that drives the real PDF-generation code path from many
 * threads at once with distinct, verifiable dollar amounts and reads the
 * figures back out of the rendered PDF text.
 */
@DisplayName("PaystubPdfService / W2PdfService — thread-safe money formatting under concurrent downloads (M12d)")
class PaystubPdfMoneyFormatConcurrencyTest {

    @Nested
    @DisplayName("structural: the shared static DecimalFormat field is gone")
    class NoSharedFormatterField {

        private static boolean hasStaticDecimalFormatField(Class<?> type) {
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && DecimalFormat.class.isAssignableFrom(f.getType())) {
                    return true;
                }
            }
            return false;
        }

        @Test
        @DisplayName("PaystubPdfService no longer declares a static DecimalFormat field")
        void paystubPdfServiceHasNoStaticFormatter() {
            assertThat(hasStaticDecimalFormatField(PaystubPdfService.class)).isFalse();
        }

        @Test
        @DisplayName("W2PdfService no longer declares a static DecimalFormat field")
        void w2PdfServiceHasNoStaticFormatter() {
            assertThat(hasStaticDecimalFormatField(W2PdfService.class)).isFalse();
        }
    }

    @Nested
    @DisplayName("live concurrency: distinct amounts across simultaneous downloads never cross-contaminate")
    class ConcurrentGeneration {

        private final SsnCrypto ssnCrypto = new SsnCrypto(""); // dev-derived key; no external config needed
        private final PaystubPdfService paystubPdfService = new PaystubPdfService(null, null, null, ssnCrypto);
        private final W2PdfService w2PdfService = new W2PdfService(ssnCrypto);

        /** A distinct, always-positive, always-unique dollar amount per task index. */
        private static BigDecimal amountFor(int i) {
            long cents = 150_000L + (long) i * 400_123L; // e.g. i=0 -> 1500.00, i=59 -> 237,572.57
            return BigDecimal.valueOf(cents, 2);
        }

        private static String dollar(BigDecimal amount) {
            return "$" + new DecimalFormat("#,##0.00").format(amount);
        }

        private static Paystub paystubFor(int i, BigDecimal amount) {
            Paystub s = new Paystub();
            s.setId((long) i);
            s.setEmployeeName("Employee " + i);
            s.setPayPeriodStart(LocalDate.of(2026, 1, 1));
            s.setPayPeriodEnd(LocalDate.of(2026, 1, 15));
            s.setPayDate(LocalDate.of(2026, 1, 16));
            s.setGrossEarnings(amount);
            s.setNetPay(amount);
            s.setYtdGross(amount);
            s.setYtdNetPay(amount);
            s.setTotalTaxes(BigDecimal.ZERO);
            s.setYtdTaxes(BigDecimal.ZERO);
            s.setPreTaxDeductions(BigDecimal.ZERO);
            s.setPostTaxDeductions(BigDecimal.ZERO);
            return s;
        }

        private static W2Box w2For(int i, BigDecimal amount) {
            W2Box w2 = new W2Box();
            w2.setEmployeeName("Employee " + i);
            w2.setTaxYear(2026);
            w2.setBox1WagesTipsOtherComp(amount);
            w2.setBox2FederalIncomeTax(amount);
            w2.setBox3SocialSecurityWages(amount);
            w2.setBox4SocialSecurityTax(amount);
            w2.setBox5MedicareWages(amount);
            w2.setBox6MedicareTax(amount);
            w2.setBox16StateWages(amount);
            w2.setBox17StateIncomeTax(amount);
            w2.setBox18LocalWages(amount);
            w2.setBox19LocalIncomeTax(amount);
            return w2;
        }

        private static String extractText(byte[] pdfBytes) throws Exception {
            try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
                return new PDFTextStripper().getText(doc);
            }
        }

        /** Runs {@code tasks} jobs on a small shared pool, all released at once, and collects [text, expectedDollar] pairs in order. */
        private static List<String[]> runConcurrently(int tasks, int poolSize,
                                                       java.util.function.IntFunction<Callable<String[]>> jobFactory) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(poolSize);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String[]>> futures = new ArrayList<>();
                for (int i = 0; i < tasks; i++) {
                    Callable<String[]> job = jobFactory.apply(i);
                    futures.add(pool.submit(() -> {
                        start.await();
                        return job.call();
                    }));
                }
                start.countDown();
                List<String[]> results = new ArrayList<>();
                for (Future<String[]> f : futures) {
                    results.add(f.get(30, TimeUnit.SECONDS));
                }
                return results;
            } finally {
                pool.shutdown();
            }
        }

        /** Asserts every task's PDF text contains its own expected figure and none of a sampled other task's. */
        private static void assertNoCrossContamination(List<String[]> results) {
            int n = results.size();
            for (int i = 0; i < n; i++) {
                String text = results.get(i)[0];
                String own = results.get(i)[1];
                assertThat(text).as("task %d must show its own amount", i).contains(own);

                String other = results.get((i + 1) % n)[1];
                if (!other.equals(own)) {
                    assertThat(text).as("task %d must not show task %d's amount instead", i, (i + 1) % n)
                            .doesNotContain(other);
                }
            }
        }

        @Test
        @DisplayName("40 concurrent paystub PDF downloads each show their own correct gross/net figures, never another's")
        void concurrentPaystubDownloadsStayCorrect() throws Exception {
            int tasks = 40;
            List<String[]> results = runConcurrently(tasks, 12, i -> () -> {
                BigDecimal amount = amountFor(i);
                Paystub stub = paystubFor(i, amount);
                byte[] pdf = paystubPdfService.generate(stub, List.of(), null, null);
                return new String[]{extractText(pdf), dollar(amount)};
            });

            assertNoCrossContamination(results);
        }

        @Test
        @DisplayName("40 concurrent W-2 PDF downloads each show their own correct box figures, never another's")
        void concurrentW2DownloadsStayCorrect() throws Exception {
            int tasks = 40;
            List<String[]> results = runConcurrently(tasks, 12, i -> () -> {
                BigDecimal amount = amountFor(i);
                W2Box w2 = w2For(i, amount);
                byte[] pdf = w2PdfService.generate(w2, null, null);
                return new String[]{extractText(pdf), dollar(amount)};
            });

            assertNoCrossContamination(results);
        }

        @Test
        @DisplayName("a mixed batch of simultaneous paystub AND W-2 downloads (the real request-pool shape) never cross-contaminates")
        void mixedPaystubAndW2DownloadsStayCorrect() throws Exception {
            int tasks = 30; // even indices -> paystub, odd indices -> W-2
            List<String[]> results = runConcurrently(tasks, 10, i -> () -> {
                BigDecimal amount = amountFor(i);
                byte[] pdf = (i % 2 == 0)
                        ? paystubPdfService.generate(paystubFor(i, amount), List.of(), null, null)
                        : w2PdfService.generate(w2For(i, amount), null, null);
                return new String[]{extractText(pdf), dollar(amount)};
            });

            assertNoCrossContamination(results);
        }
    }
}
