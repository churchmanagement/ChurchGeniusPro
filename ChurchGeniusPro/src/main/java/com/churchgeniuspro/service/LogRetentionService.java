package com.churchgeniuspro.service;

import com.churchgeniuspro.config.LogRetentionProperties;
import com.churchgeniuspro.logging.LogDirectoryPropertyDefiner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Deletes log files older than the retention period.
 *
 * <p>A backstop for logback's own {@code maxHistory}, which only ever removes files that
 * match a currently configured appender's filename pattern. On Azure App Service the log
 * directory is a share that outlives any single instance, so it accumulates files logback
 * has no idea about: those written by an instance that has since been scaled in, or during
 * a period when per-instance filenames were switched on. Without this sweep those files
 * would sit there — with login records and IP addresses in them — indefinitely.
 *
 * <p>Deliberately conservative: it only ever touches regular files in the one directory
 * (never subdirectories) whose names match the log naming scheme, and it logs what it
 * removed. Age is taken from the filesystem's last-modified time rather than parsed out of
 * the filename, so a file that was still being appended to yesterday is never deleted for
 * carrying an old date in its name.
 */
@Service
public class LogRetentionService {

    private static final Logger LOG = LoggerFactory.getLogger(LogRetentionService.class);

    /** Only these are ever considered for deletion. */
    private static final String[] MANAGED_PREFIXES = {"security-", "error-", "application-", "churchgeniuspro"};
    private static final String[] MANAGED_SUFFIXES = {".log", ".gz", ".zip"};

    /**
     * Refuse to run with a retention shorter than this. Guards against a typo or a bad
     * environment variable silently wiping the audit trail.
     */
    private static final int MIN_RETENTION_DAYS = 1;

    private final LogRetentionProperties props;

    public LogRetentionService(LogRetentionProperties props) {
        this.props = props;
    }

    /**
     * Reports at startup where log files are going, and whether that location is actually
     * usable.
     *
     * <p>This exists because the failure mode it diagnoses is silent. If logback cannot
     * write — the directory is not creatable, the mount is read-only, or prudent mode
     * cannot take a lock on the Azure Files share — it fails internally and the only
     * outward symptom is a log directory that never appears. Nothing throws, the app
     * serves traffic normally, and the audit trail simply does not exist.
     *
     * <p>One line on stdout at startup turns that from an investigation into a glance.
     * It logs the resolved path, whether it exists and is writable, and the two
     * environment variables the resolution depends on.
     */
    @PostConstruct
    void reportLogDestination() {
        Path dir = resolveDirectory();
        String home     = System.getenv(LogDirectoryPropertyDefiner.HOME_VAR);
        String instance = System.getenv(LogDirectoryPropertyDefiner.APP_SERVICE_VAR);
        String override = System.getenv(LogDirectoryPropertyDefiner.OVERRIDE_VAR);

        boolean exists   = Files.isDirectory(dir);
        boolean writable = exists && Files.isWritable(dir);

        String detail = String.format(
                "logDir='%s' exists=%s writable=%s | HOME=%s WEBSITE_INSTANCE_ID=%s CGP_LOG_DIR=%s "
              + "CGP_LOG_PRUDENT=%s CGP_LOG_PER_INSTANCE_FILES=%s",
                dir.toAbsolutePath(), exists, writable,
                home, instance == null ? "(unset)" : "(set)",
                override == null ? "(unset)" : override,
                orUnset(System.getenv("CGP_LOG_PRUDENT")),
                orUnset(System.getenv("CGP_LOG_PER_INSTANCE_FILES")));

        if (writable) {
            LOG.info("Log destination OK — {}", detail);
        } else {
            // WARN, so it surfaces even when logging.level.root=WARN hides INFO.
            LOG.warn("LOG DESTINATION PROBLEM — no log files will be written. {}", detail);
        }
    }

    private static String orUnset(String value) {
        return (value == null || value.isBlank()) ? "(unset)" : value;
    }

    /**
     * Runs daily. The cron is configurable via {@code logging.cgp.purge-cron}.
     *
     * <p>Safe to run on every instance simultaneously: deleting an already-deleted file is
     * not an error, and each instance simply reports what it managed to remove.
     */
    @Scheduled(cron = "${logging.cgp.purge-cron:0 40 3 * * *}")
    public void purgeOldLogFiles() {
        if (!props.isPurgeEnabled()) return;

        int retentionDays = props.getRetentionDays();
        if (retentionDays < MIN_RETENTION_DAYS) {
            LOG.warn("Log retention purge skipped: logging.cgp.retention-days={} is below the {}-day minimum.",
                     retentionDays, MIN_RETENTION_DAYS);
            return;
        }

        Path dir = resolveDirectory();
        if (!Files.isDirectory(dir)) {
            LOG.debug("Log retention purge skipped: '{}' is not a directory (yet).", dir);
            return;
        }

        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<String> deleted = new ArrayList<>();
        long bytesFreed = 0;
        int failures = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                try {
                    if (!Files.isRegularFile(file)) continue;
                    if (!isManagedLogFile(file.getFileName().toString())) continue;

                    FileTime modified = Files.getLastModifiedTime(file);
                    if (!modified.toInstant().isBefore(cutoff)) continue;

                    long size = Files.size(file);
                    Files.delete(file);
                    deleted.add(file.getFileName().toString());
                    bytesFreed += size;

                } catch (IOException ex) {
                    // Another instance may have deleted it, or it may be locked mid-write.
                    // Neither is worth failing the sweep over.
                    failures++;
                    LOG.debug("Could not remove log file '{}': {}", file, ex.toString());
                }
            }
        } catch (IOException ex) {
            LOG.warn("Log retention purge could not read '{}': {}", dir, ex.toString());
            return;
        }

        if (!deleted.isEmpty()) {
            LOG.info("Log retention purge removed {} file(s) older than {} days from '{}' ({} KB freed): {}",
                     deleted.size(), retentionDays, dir, bytesFreed / 1024, deleted);
        } else {
            LOG.debug("Log retention purge found nothing older than {} days in '{}'.", retentionDays, dir);
        }
        if (failures > 0) {
            LOG.debug("Log retention purge skipped {} file(s) it could not remove this run.", failures);
        }
    }

    /** Same resolution logback uses, so the sweeper can never target the wrong directory. */
    private Path resolveDirectory() {
        String configured = props.getDirectory();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(LogDirectoryPropertyDefiner.resolve(
                System.getenv(LogDirectoryPropertyDefiner.OVERRIDE_VAR),
                System.getenv(LogDirectoryPropertyDefiner.APP_SERVICE_VAR),
                System.getenv(LogDirectoryPropertyDefiner.HOME_VAR)));
    }

    /**
     * True only for files this application produced. Anything else in the directory —
     * a diagnostic dump, an operator's notes, another app's output on a shared share —
     * is left strictly alone.
     */
    public static boolean isManagedLogFile(String fileName) {
        String lower = fileName.toLowerCase();

        boolean suffixOk = false;
        for (String suffix : MANAGED_SUFFIXES) {
            if (lower.endsWith(suffix)) { suffixOk = true; break; }
        }
        if (!suffixOk) return false;

        for (String prefix : MANAGED_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }
}
