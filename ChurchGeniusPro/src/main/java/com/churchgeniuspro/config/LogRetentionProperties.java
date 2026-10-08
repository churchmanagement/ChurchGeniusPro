package com.churchgeniuspro.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retention policy for the daily log files, bound from {@code logging.cgp.*}.
 *
 * <p>Logback's own {@code maxHistory} already deletes files it still tracks. This exists
 * because that guarantee has a hole: logback only removes files matching the exact
 * {@code fileNamePattern} of a <em>currently configured</em> appender. Files left behind by
 * a scaled-in instance, a renamed appender, or a period when per-instance filenames were
 * enabled are invisible to it and would sit on the share indefinitely. The sweeper in
 * {@code LogRetentionService} closes that hole.
 */
@Data
@Component
@ConfigurationProperties(prefix = "logging.cgp")
public class LogRetentionProperties {

    /** Set {@code false} to disable the sweeper (logback's own cleanup still runs). */
    private boolean purgeEnabled = true;

    /** Days to keep. Files whose last-modified time is older than this are deleted. */
    private int retentionDays = 30;

    /**
     * Directory to sweep. Blank means "resolve exactly as logback does" — the
     * {@code CGP_LOG_DIR} override, else the persistent App Service share, else
     * {@code logs}. Leave blank unless the sweeper must target somewhere else.
     */
    private String directory = "";

    /** Cron for the sweep (default 03:40 daily, after the login-protection purge). */
    private String purgeCron = "0 40 3 * * *";
}
