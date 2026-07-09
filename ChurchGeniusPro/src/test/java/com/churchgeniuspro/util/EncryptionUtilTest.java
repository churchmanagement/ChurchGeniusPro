package com.churchgeniuspro.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit + concurrency tests for {@link EncryptionUtil}. The encrypted client-id
 * token is used in every public link, so a round-trip regression would break
 * NTAG/Connect/Prayer pages.
 */
class EncryptionUtilTest {

    @Test
    void roundTrip_returnsOriginal() throws Exception {
        String plain = "CLIENT-12345";
        String cipher = EncryptionUtil.encrypt(plain);
        assertNotEquals(plain, cipher, "ciphertext must differ from plaintext");
        assertEquals(plain, EncryptionUtil.decrypt(cipher));
    }

    @Test
    void encrypt_isUrlSafe_noPadding() throws Exception {
        String cipher = EncryptionUtil.encrypt("some/value+with=chars");
        assertFalse(cipher.contains("+"), "URL-safe base64 must not contain '+'");
        assertFalse(cipher.contains("/"), "URL-safe base64 must not contain '/'");
        assertFalse(cipher.contains("="), "base64 must be unpadded");
    }

    @Test
    void decrypt_garbage_throws() {
        assertThrows(Exception.class, () -> EncryptionUtil.decrypt("!!!not-base64!!!"));
    }

    /**
     * Concurrency: the cipher is created per-call, so many threads encrypting and
     * decrypting in parallel must never corrupt each other or throw.
     */
    @Test
    void concurrentRoundTrips_areThreadSafe() throws Exception {
        int threads = 32, perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        CompletableFuture<?>[] futures = new CompletableFuture[threads];

        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures[t] = CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String v = "tenant-" + id + "-" + i;
                        if (!v.equals(EncryptionUtil.decrypt(EncryptionUtil.encrypt(v)))) failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }, pool);
        }
        start.countDown();
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, failures.get(), "encryption round-trips must be correct under concurrency");
    }
}
