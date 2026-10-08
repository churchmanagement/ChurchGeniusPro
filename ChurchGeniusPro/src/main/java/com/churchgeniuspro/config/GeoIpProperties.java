package com.churchgeniuspro.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for IP → city/state/country resolution, bound from {@code geoip.*}.
 *
 * <p>Backed by the MaxMind GeoLite2 City database: a local file, so no member's IP address
 * is ever sent to a third party and a login never waits on an outbound HTTP call.
 *
 * <p>The database file is <b>not</b> in this repository — MaxMind's licence does not permit
 * redistribution, and it is ~60MB. See {@code scripts/update-geoip.sh} and LOGGING.md for
 * how to place and refresh it. If the file is absent the application starts normally, logs
 * one warning, and records {@code city=- state=- country=-}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "geoip")
public class GeoIpProperties {

    /** Master switch. When {@code false} every lookup returns "unknown" without touching disk. */
    private boolean enabled = true;

    /**
     * Path to {@code GeoLite2-City.mmdb}.
     *
     * <p>Defaults to {@code $HOME/data/GeoLite2-City.mmdb}, which on Azure App Service is the
     * persistent share — so the file survives redeployments and does not need to be part of
     * the build artifact. Override with the {@code GEOIP_DB_PATH} environment variable.
     */
    private String databasePath = "";

    /**
     * Number of resolved addresses held in memory. Church traffic repeats the same handful
     * of addresses all day, so even a small cache removes almost all lookups.
     */
    private int cacheSize = 5000;
}
