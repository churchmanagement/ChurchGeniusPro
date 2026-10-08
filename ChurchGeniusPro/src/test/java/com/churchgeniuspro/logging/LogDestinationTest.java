package com.churchgeniuspro.logging;

import com.churchgeniuspro.service.LogRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where log files go and which of them the retention sweeper is willing to delete.
 *
 * <p>Both are decisions that only reveal themselves as wrong in production — a log
 * directory that resolves to ephemeral storage loses the audit trail silently at the next
 * restart, and an over-eager sweeper deletes files it does not own. Pinned here.
 */
class LogDestinationTest {

    // ── Directory resolution ──────────────────────────────────────────────────
    //
    // The regression these guard against: on the Linux App Service Java SE container the
    // process runs as root, so HOME=/root — container-local scratch storage, wiped on every
    // restart. The original implementation trusted HOME and quietly wrote the entire audit
    // trail there. It looked like it was working right up until the first restart.

    /** Pretends the App Service share is mounted at {@code /home}, as on Linux. */
    private static final java.util.function.Predicate<String> LINUX_APP_SERVICE =
            path -> path.equals("/home") || path.equals("/home/site") || path.equals("/root");

    /** Pretends the share is at {@code D:\home}, as on Windows. */
    private static final java.util.function.Predicate<String> WINDOWS_APP_SERVICE =
            path -> path.equals("D:\\home") || path.equals("D:\\home/site");

    @Test
    @DisplayName("HOME=/root is rejected — it exists, but it is NOT the persistent share")
    void homeIsNotTrustedBlindly() {
        assertEquals("/home/LogFiles/churchgeniuspro",
                LogDirectoryPropertyDefiner.resolve(null, "instance", "/root", LINUX_APP_SERVICE),
                "logs written under /root vanish on every restart — the whole point of this class");
    }

    @Test
    @DisplayName("on Linux App Service the share is found at /home whatever HOME says")
    void linuxAppServiceResolvesToShare() {
        for (String home : new String[]{"/root", "/home", "/home/", null, "", "/nonsense"}) {
            assertEquals("/home/LogFiles/churchgeniuspro",
                    LogDirectoryPropertyDefiner.resolve(null, "instance", home, LINUX_APP_SERVICE),
                    "HOME=" + home);
        }
    }

    @Test
    @DisplayName("on Windows App Service HOME does point at the share, and is used")
    void windowsAppServiceUsesHome() {
        assertEquals("D:\\home/LogFiles/churchgeniuspro",
                LogDirectoryPropertyDefiner.resolve(null, "instance", "D:\\home", WINDOWS_APP_SERVICE));
        // ...and is still found even if HOME is missing entirely
        assertEquals("D:\\home/LogFiles/churchgeniuspro",
                LogDirectoryPropertyDefiner.resolve(null, "instance", null, WINDOWS_APP_SERVICE));
    }

    @Test
    @DisplayName("an explicit CGP_LOG_DIR always wins")
    void overrideWins() {
        assertEquals("/mnt/logs",
                LogDirectoryPropertyDefiner.resolve("/mnt/logs", "instance", "/root", LINUX_APP_SERVICE));
        assertEquals("/mnt/logs",
                LogDirectoryPropertyDefiner.resolve("  /mnt/logs/  ", null, null, LINUX_APP_SERVICE));
    }

    @Test
    @DisplayName("off App Service it falls back to ./logs, exactly as before this change")
    void localFallback() {
        assertEquals("logs",
                LogDirectoryPropertyDefiner.resolve(null, null, "/home/someone", p -> true));
        assertEquals("logs",
                LogDirectoryPropertyDefiner.resolve("", "", "/home/someone", p -> true));
    }

    @Test
    @DisplayName("on App Service with no recognisable share, a known mount still beats ./logs")
    void appServiceWithoutMarkerStillPrefersTheShare() {
        // Marker missing, but /home exists — an ephemeral relative directory would be worse.
        assertEquals("/home/LogFiles/churchgeniuspro",
                LogDirectoryPropertyDefiner.resolve(null, "instance", null, "/home"::equals));
    }

    @Test
    @DisplayName("the fallback never lands on HOME — that is what caused the original bug")
    void fallbackNeverUsesHome() {
        // /root exists and is writable, /home exists, neither has the marker. The old
        // ordering picked HOME and wrote the audit trail to scratch storage.
        assertEquals("/home/LogFiles/churchgeniuspro",
                LogDirectoryPropertyDefiner.resolve(null, "instance", "/root",
                        path -> path.equals("/root") || path.equals("/home")));

        // And when only HOME exists, we would rather write nowhere durable than pretend.
        assertEquals("logs",
                LogDirectoryPropertyDefiner.resolve(null, "instance", "/root", "/root"::equals));
    }

    @Test
    @DisplayName("nothing resolvable at all is the only case that falls back to ./logs")
    void nothingResolvable() {
        assertEquals("logs",
                LogDirectoryPropertyDefiner.resolve(null, "instance", "/root", p -> false));
    }

    // ── Per-instance filename suffix ──────────────────────────────────────────

    @Test
    @DisplayName("the instance suffix is empty by default, keeping the requested file names")
    void instanceSuffixIsOptional() {
        assertEquals("", InstanceIdPropertyDefiner.shortInstanceId(null));
        assertEquals("", InstanceIdPropertyDefiner.shortInstanceId("   "));
    }

    @Test
    @DisplayName("a 64-character Azure instance id is shortened to something filename-safe")
    void instanceSuffixIsShortened() {
        String azureId = "a".repeat(64);
        assertEquals("-aaaaaaaa", InstanceIdPropertyDefiner.shortInstanceId(azureId));
        assertEquals("-short", InstanceIdPropertyDefiner.shortInstanceId("short"));
    }

    // ── Retention sweeper safety ──────────────────────────────────────────────

    @Test
    @DisplayName("the sweeper recognises this application's own log files")
    void sweeperMatchesOwnFiles() {
        assertTrue(LogRetentionService.isManagedLogFile("security-2026-08-17.log"));
        assertTrue(LogRetentionService.isManagedLogFile("error-2026-08-17.log"));
        assertTrue(LogRetentionService.isManagedLogFile("application-2026-08-17.log"));
        assertTrue(LogRetentionService.isManagedLogFile("security-2026-08-17-a1b2c3d4.log"));
        // files from the previous logging setup, so old archives are cleaned up too
        assertTrue(LogRetentionService.isManagedLogFile("churchgeniuspro.log.2026-05-18.0.gz"));
    }

    @Test
    @DisplayName("the sweeper refuses to touch anything it did not write")
    void sweeperLeavesForeignFilesAlone() {
        assertFalse(LogRetentionService.isManagedLogFile("GeoLite2-City.mmdb"),
                "the GeoIP database lives near the logs — deleting it would be a bad day");
        assertFalse(LogRetentionService.isManagedLogFile("eventlog.xml"));
        assertFalse(LogRetentionService.isManagedLogFile("notes.txt"));
        assertFalse(LogRetentionService.isManagedLogFile("backup-2026-01-01.sql"));
        assertFalse(LogRetentionService.isManagedLogFile("docker.log.other-app"));
        assertFalse(LogRetentionService.isManagedLogFile(".gitkeep"));
    }
}
