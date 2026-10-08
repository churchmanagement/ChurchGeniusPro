package com.churchgeniuspro.plaid;

import com.churchgeniuspro.plaid.config.PlaidProperties;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.BankSyncVerificationRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.service.PlaidAuditService;
import com.churchgeniuspro.plaid.service.PlaidTokenCipher;
import com.churchgeniuspro.plaid.service.PlaidTokenRotationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rotating {@code PLAID_TOKEN_ENC_KEY}.
 *
 * <p>The stakes make the negative cases the important ones: this key is what
 * every stored bank token was encrypted with, and a rotation that loses a token
 * costs a church a reconnection through Plaid Link. So what is pinned here is
 * that the plaintext survives the round trip, that the pass is idempotent, and
 * that a row it cannot read is reported rather than overwritten.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Plaid token key rotation")
class PlaidTokenRotationTest {

    private static final String OLD_KEY = key((byte) 0x11);
    private static final String NEW_KEY = key((byte) 0x22);
    private static final String THIRD_KEY = key((byte) 0x33);

    private static String key(byte fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, fill);
        return Base64.getEncoder().encodeToString(k);
    }

    @Mock private PlaidItemRepository            itemRepo;
    @Mock private BankSyncVerificationRepository verificationRepo;
    @Mock private PlaidAuditService              audit;

    private PlaidProperties props;
    private PlaidTokenCipher cipher;
    private PlaidTokenRotationService rotation;

    @BeforeEach
    void setUp() {
        props = new PlaidProperties();
        props.setTokenEncKey(NEW_KEY);
        props.setTokenEncKeyPrevious(OLD_KEY);
        cipher = new PlaidTokenCipher(props);
        rotation = new PlaidTokenRotationService(itemRepo, verificationRepo, cipher, audit);
        when(itemRepo.save(any(PlaidItem.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** A value encrypted with an arbitrary key, as an older deployment would have stored it. */
    private static String encryptedWith(String keyB64, String plaintext) {
        PlaidProperties p = new PlaidProperties();
        p.setTokenEncKey(keyB64);
        return new PlaidTokenCipher(p).encrypt(plaintext);
    }

    private PlaidItem item(int id, String storedToken) {
        PlaidItem it = new PlaidItem();
        it.setId(id);
        it.setItemId("item-" + id);
        it.setAccessTokenEnc(storedToken);
        when(itemRepo.findById(id)).thenReturn(Optional.of(it));
        return it;
    }

    /* ── the cipher's overlap ───────────────────────────────────────────── */

    @Nested
    @DisplayName("two-key overlap")
    class Overlap {

        @Test
        @DisplayName("a token written with the old key still opens after the new key is deployed")
        void oldTokensStillOpen() {
            String stored = encryptedWith(OLD_KEY, "access-sandbox-abc123");

            // This is what makes the rotation zero-downtime: the application keeps
            // working from the moment the new key is deployed.
            assertThat(cipher.decrypt(stored)).isEqualTo("access-sandbox-abc123");
        }

        @Test
        @DisplayName("new writes use the current key only")
        void newWritesUseCurrentKey() {
            String stored = cipher.encrypt("access-production-xyz");

            PlaidProperties newOnly = new PlaidProperties();
            newOnly.setTokenEncKey(NEW_KEY);
            assertThat(new PlaidTokenCipher(newOnly).decrypt(stored)).isEqualTo("access-production-xyz");

            // And emphatically not the old one — nothing is written with it.
            PlaidProperties oldOnly = new PlaidProperties();
            oldOnly.setTokenEncKey(OLD_KEY);
            assertThatThrownBy(() -> new PlaidTokenCipher(oldOnly).decrypt(stored))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a value readable by neither key fails rather than returning rubbish")
        void unknownKeyFails() {
            String stored = encryptedWith(THIRD_KEY, "orphaned-token");
            // AES-GCM is authenticated, which is what makes try-one-then-the-other
            // safe: a wrong key cannot produce plausible plaintext.
            assertThatThrownBy(() -> cipher.decrypt(stored))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("needsRotation distinguishes old-key, current-key and unreadable")
        void needsRotationClassifies() {
            assertThat(cipher.needsRotation(encryptedWith(OLD_KEY, "t"))).isTrue();
            assertThat(cipher.needsRotation(cipher.encrypt("t"))).isFalse();
            assertThat(cipher.needsRotation(encryptedWith(THIRD_KEY, "t"))).isFalse();
            assertThat(cipher.needsRotation("")).isFalse();
            assertThat(cipher.needsRotation(null)).isFalse();
        }

        @Test
        @DisplayName("with no previous key configured there is no rotation in progress")
        void noPreviousKey() {
            props.setTokenEncKeyPrevious("");
            assertThat(cipher.rotationInProgress()).isFalse();
            assertThat(cipher.needsRotation(encryptedWith(OLD_KEY, "t"))).isFalse();
        }
    }

    /* ── the pass ───────────────────────────────────────────────────────── */

    @Test
    @DisplayName("the plaintext survives the round trip unchanged")
    void plaintextSurvives() {
        String plaintext = "access-production-9f2c-4471-abcd";
        PlaidItem it = item(1, encryptedWith(OLD_KEY, plaintext));
        when(itemRepo.findAll()).thenReturn(List.of(it));

        rotation.rotate(false, "admin");

        // The whole point: same token, new key.
        assertThat(cipher.decrypt(it.getAccessTokenEnc())).isEqualTo(plaintext);
        PlaidProperties newOnly = new PlaidProperties();
        newOnly.setTokenEncKey(NEW_KEY);
        assertThat(new PlaidTokenCipher(newOnly).decrypt(it.getAccessTokenEnc())).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("a dry run reports the work without writing anything")
    void dryRunWritesNothing() {
        PlaidItem it = item(1, encryptedWith(OLD_KEY, "tok"));
        String before = it.getAccessTokenEnc();
        when(itemRepo.findAll()).thenReturn(List.of(it));

        Map<String, Object> out = rotation.rotate(true, null);

        assertThat(out.get("wouldRotate")).isEqualTo(1);
        assertThat(out.get("pending")).isEqualTo(1);
        assertThat(it.getAccessTokenEnc()).isEqualTo(before);
        verify(itemRepo, never()).save(any(PlaidItem.class));
    }

    @Test
    @DisplayName("re-running changes nothing — the pass is idempotent")
    void idempotent() {
        PlaidItem it = item(1, encryptedWith(OLD_KEY, "tok"));
        when(itemRepo.findAll()).thenReturn(List.of(it));

        rotation.rotate(false, "admin");
        String afterFirst = it.getAccessTokenEnc();

        Map<String, Object> second = rotation.rotate(false, "admin");

        assertThat(second.get("rotated")).isEqualTo(0);
        assertThat(second.get("alreadyOnCurrentKey")).isEqualTo(1);
        assertThat(it.getAccessTokenEnc()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("an unreadable row is reported and left untouched, and the rest still rotate")
    void unreadableRowIsIsolated() {
        PlaidItem good    = item(1, encryptedWith(OLD_KEY, "good-token"));
        PlaidItem orphan  = item(2, encryptedWith(THIRD_KEY, "orphan"));
        PlaidItem alsoOk  = item(3, encryptedWith(OLD_KEY, "another-token"));
        String orphanBefore = orphan.getAccessTokenEnc();
        when(itemRepo.findAll()).thenReturn(List.of(good, orphan, alsoOk));

        Map<String, Object> out = rotation.rotate(false, "admin");

        assertThat(out.get("rotated")).isEqualTo(2);
        assertThat(out.get("unreadableItemIds")).isEqualTo(List.of(2));
        assertThat(out.get("warning")).asString().contains("reconnected");
        // Never overwrite what could not be read — that would destroy the only
        // copy of a token that a different key might still open.
        assertThat(orphan.getAccessTokenEnc()).isEqualTo(orphanBefore);
        assertThat(cipher.decrypt(good.getAccessTokenEnc())).isEqualTo("good-token");
        assertThat(cipher.decrypt(alsoOk.getAccessTokenEnc())).isEqualTo("another-token");
    }

    @Test
    @DisplayName("the blank stub on a soft-deleted item is skipped, not rewritten")
    void blankStubSkipped() {
        // access_token_enc is NOT NULL; a soft-delete blanks it to "".
        PlaidItem deleted = item(1, "");
        when(itemRepo.findAll()).thenReturn(List.of(deleted));

        Map<String, Object> out = rotation.rotate(false, "admin");

        assertThat(out.get("blankStubs")).isEqualTo(1);
        assertThat(out.get("rotated")).isEqualTo(0);
        assertThat(deleted.getAccessTokenEnc()).isEmpty();
        verify(itemRepo, never()).save(any(PlaidItem.class));
    }

    @Test
    @DisplayName("a mixed estate converges after one pass")
    void mixedEstateConverges() {
        List<PlaidItem> items = new ArrayList<>(List.of(
                item(1, encryptedWith(OLD_KEY, "a")),
                item(2, cipher.encrypt("b")),          // already migrated
                item(3, encryptedWith(OLD_KEY, "c")),
                item(4, "")));                          // soft-deleted stub
        when(itemRepo.findAll()).thenReturn(items);

        rotation.rotate(false, "admin");
        Map<String, Object> after = rotation.rotate(true, null);

        assertThat(after.get("pending")).isEqualTo(0);
        assertThat(after.get("alreadyOnCurrentKey")).isEqualTo(3);
    }

    @Test
    @DisplayName("a missing key is refused before anything is touched")
    void missingKeyRefused() {
        // Build the item BEFORE stubbing findAll: item() stubs findById, and a
        // when(...) nested inside another when(...) is what Mockito calls
        // unfinished stubbing.
        PlaidItem it = item(1, "whatever");
        props.setTokenEncKey("");
        when(itemRepo.findAll()).thenReturn(List.of(it));

        Map<String, Object> out = rotation.rotate(false, "admin");

        assertThat(out.get("status")).isEqualTo("error");
        assertThat(out.get("message")).asString().contains("PLAID_TOKEN_ENC_KEY");
        verify(itemRepo, never()).save(any(PlaidItem.class));
    }
}
