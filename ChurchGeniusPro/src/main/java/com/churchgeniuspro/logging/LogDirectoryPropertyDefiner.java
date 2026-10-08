package com.churchgeniuspro.logging;

import ch.qos.logback.core.PropertyDefinerBase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Decides where log files are written, so production gets a durable location with no
 * configuration at all.
 *
 * <h3>Why this does not simply use {@code $HOME}</h3>
 * It used to, and that was wrong. On Azure App Service the persistent Azure Files share is
 * mounted at {@code /home} (Linux) or {@code D:\home} (Windows) — but on the Linux Java SE
 * container the process runs as root, so {@code HOME} is <b>{@code /root}</b>, which is
 * container-local scratch storage wiped on every restart, redeploy and instance move.
 * Trusting {@code HOME} therefore sent the entire audit trail somewhere that looked like it
 * was working and silently evaporated. On Windows App Service {@code HOME} does point at the
 * share, which is why the mistake was easy to make and easy to miss.
 *
 * <p>So the share is identified by a property only it has: it is the directory that contains
 * {@code site} (as in {@code /home/site/wwwroot}). {@code HOME} is still consulted first —
 * it is correct on Windows — but only accepted if it passes that test.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code CGP_LOG_DIR} — an explicit override always wins.</li>
 *   <li>On App Service (detected by {@code WEBSITE_INSTANCE_ID}), the first of
 *       {@code $HOME}, {@code /home}, {@code D:\home} that actually looks like the share
 *       → {@code <share>/LogFiles/churchgeniuspro}.</li>
 *   <li>Anything else → {@code logs}, relative to the working directory.</li>
 * </ol>
 *
 * <p>{@code <share>/LogFiles} is also the directory the Kudu console, the App Service
 * "Log stream" blade and the FTP endpoint expose, so the files are reachable without a
 * deployment or an SSH session.
 *
 * @see com.churchgeniuspro.service.LogRetentionService#reportLogDestination()
 */
public class LogDirectoryPropertyDefiner extends PropertyDefinerBase {

    public static final String OVERRIDE_VAR    = "CGP_LOG_DIR";
    public static final String APP_SERVICE_VAR = "WEBSITE_INSTANCE_ID";
    public static final String HOME_VAR        = "HOME";

    /** Fallback when not on App Service and nothing is configured. */
    public static final String LOCAL_DEFAULT = "logs";

    /** Where App Service mounts the persistent share, per OS. */
    static final String LINUX_SHARE   = "/home";
    static final String WINDOWS_SHARE = "D:\\home";

    /** Subdirectory that exists only on the App Service share — used to identify it. */
    static final String SHARE_MARKER = "site";

    /** Path appended to the share once found. */
    static final String LOG_SUBPATH = "LogFiles/churchgeniuspro";

    @Override
    public String getPropertyValue() {
        return resolve(System.getenv(OVERRIDE_VAR),
                       System.getenv(APP_SERVICE_VAR),
                       System.getenv(HOME_VAR));
    }

    /**
     * Resolution against the real filesystem. Also used by {@code LogRetentionService} so
     * the sweeper and the appenders can never disagree about which directory holds the logs.
     */
    public static String resolve(String override, String appServiceInstanceId, String home) {
        return resolve(override, appServiceInstanceId, home,
                       path -> Files.isDirectory(Path.of(path)));
    }

    /**
     * Resolution with the filesystem check injected, so the rules are unit-testable without
     * needing an App Service share to exist.
     *
     * @param isDirectory returns true when the given path is an existing directory
     */
    static String resolve(String override, String appServiceInstanceId, String home,
                          Predicate<String> isDirectory) {

        if (override != null && !override.isBlank()) {
            return stripTrailingSeparator(override.trim());
        }

        boolean onAppService = appServiceInstanceId != null && !appServiceInstanceId.isBlank();
        if (!onAppService) {
            return LOCAL_DEFAULT;
        }

        List<String> candidates = new ArrayList<>(3);
        if (home != null && !home.isBlank()) {
            candidates.add(stripTrailingSeparator(home.trim()));
        }
        candidates.add(LINUX_SHARE);
        candidates.add(WINDOWS_SHARE);

        // First pass: the directory must actually look like the App Service share. This is
        // what rejects HOME=/root, which exists but is not the share.
        for (String candidate : candidates) {
            if (isDirectory.test(candidate + "/" + SHARE_MARKER)) {
                return candidate + "/" + LOG_SUBPATH;
            }
        }

        // Second pass: the marker was not found anywhere. We still know we are on App
        // Service, so a known share location beats an ephemeral relative directory.
        //
        // HOME is deliberately NOT a candidate here. It is the value that caused the
        // original bug — /root exists and is writable, so including it would resolve
        // happily to scratch storage and reintroduce exactly the silent data loss this
        // second pass is meant to avoid. If the share cannot be identified, a known
        // mount point or nothing.
        for (String share : new String[]{LINUX_SHARE, WINDOWS_SHARE}) {
            if (isDirectory.test(share)) {
                return share + "/" + LOG_SUBPATH;
            }
        }

        return LOCAL_DEFAULT;
    }

    private static String stripTrailingSeparator(String path) {
        String p = path;
        while (p.length() > 1 && (p.endsWith("/") || p.endsWith("\\"))) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
