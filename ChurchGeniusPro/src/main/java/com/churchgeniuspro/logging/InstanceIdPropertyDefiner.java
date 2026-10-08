package com.churchgeniuspro.logging;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * Supplies logback with a short, stable identifier for the current App Service instance.
 *
 * <p>Azure App Service sets {@code WEBSITE_INSTANCE_ID} to a 64-character hash, which is
 * unusable in a filename. This returns the first 8 characters, or an empty string when the
 * variable is absent (local development, tests, a plain VM).
 *
 * <p>Used only when {@code CGP_LOG_PER_INSTANCE_FILES=true}. The default is shared files
 * plus logback's prudent mode, which keeps the filenames exactly as specified
 * ({@code security-2026-08-17.log}) and is safe for several JVMs appending to one file on
 * the shared {@code /home} volume. Switch to per-instance files if the app is scaled out
 * far enough that lock contention on that volume starts to matter — see LOGGING.md.
 */
public class InstanceIdPropertyDefiner extends PropertyDefinerBase {

    static final String ENV_VAR = "WEBSITE_INSTANCE_ID";
    static final String TOGGLE_VAR = "CGP_LOG_PER_INSTANCE_FILES";
    private static final int SHORT_LENGTH = 8;

    @Override
    public String getPropertyValue() {
        if (!"true".equalsIgnoreCase(System.getenv(TOGGLE_VAR))) return "";
        return shortInstanceId(System.getenv(ENV_VAR));
    }

    /** Visible for testing. Returns {@code ""} for a null/blank id, else a "-xxxxxxxx" suffix. */
    static String shortInstanceId(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        return "-" + (trimmed.length() <= SHORT_LENGTH ? trimmed : trimmed.substring(0, SHORT_LENGTH));
    }
}
