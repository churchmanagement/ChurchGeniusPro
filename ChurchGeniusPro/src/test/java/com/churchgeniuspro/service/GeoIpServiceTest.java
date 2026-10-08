package com.churchgeniuspro.service;

import com.churchgeniuspro.config.GeoIpProperties;
import com.churchgeniuspro.model.GeoLocationBO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Geolocation is decoration on an audit record, so every one of these tests is really the
 * same assertion: whatever goes wrong, the sign-in still works and the line still reads.
 *
 * <p>No {@code .mmdb} fixture is committed — MaxMind's licence does not allow
 * redistributing the database — so these cover the degraded paths, which are the ones that
 * would actually break a production login.
 */
class GeoIpServiceTest {

    @Test
    @DisplayName("a missing database file disables lookup instead of failing startup")
    void missingDatabaseDegradesQuietly() {
        GeoIpService service = serviceWithPath("/definitely/not/a/real/path/GeoLite2-City.mmdb");

        assertFalse(service.isAvailable());
        assertTrue(service.resolve("203.0.113.7").isUnknown());
    }

    @Test
    @DisplayName("a corrupt database file is handled the same way as a missing one")
    void corruptDatabaseDegradesQuietly() throws Exception {
        java.io.File bogus = java.io.File.createTempFile("not-really-a-geoip-db", ".mmdb");
        bogus.deleteOnExit();
        java.nio.file.Files.writeString(bogus.toPath(), "this is not an mmdb file");

        GeoIpService service = serviceWithPath(bogus.getAbsolutePath());

        assertFalse(service.isAvailable(), "a file that will not parse must not be treated as usable");
        assertTrue(service.resolve("203.0.113.7").isUnknown());
    }

    @Test
    @DisplayName("geoip.enabled=false skips the database entirely")
    void disabledSkipsEverything() {
        GeoIpProperties props = new GeoIpProperties();
        props.setEnabled(false);
        GeoIpService service = new GeoIpService(props);
        service.init();

        assertFalse(service.isAvailable());
        assertTrue(service.resolve("203.0.113.7").isUnknown());
    }

    @Test
    @DisplayName("null, blank and malformed addresses never throw")
    void badInputIsSafe() {
        GeoIpService service = serviceWithPath("/nope/GeoLite2-City.mmdb");

        assertDoesNotThrow(() -> {
            assertTrue(service.resolve(null).isUnknown());
            assertTrue(service.resolve("").isUnknown());
            assertTrue(service.resolve("   ").isUnknown());
            assertTrue(service.resolve("not-an-ip-at-all").isUnknown());
            assertTrue(service.resolve("999.999.999.999").isUnknown());
        });
    }

    @Test
    @DisplayName("hostnames are rejected, so a log write can never trigger a DNS lookup")
    void hostnamesAreRejected() {
        assertTrue(GeoIpService.looksLikeIpAddress("203.0.113.7"));
        assertTrue(GeoIpService.looksLikeIpAddress("2001:db8::1"));
        assertTrue(GeoIpService.looksLikeIpAddress("::1"));

        // InetAddress.getByName() on any of these would go to the resolver and block.
        assertFalse(GeoIpService.looksLikeIpAddress("evil.example.com"));
        assertFalse(GeoIpService.looksLikeIpAddress("localhost"));
        assertFalse(GeoIpService.looksLikeIpAddress("attacker-controlled-header"));
        assertFalse(GeoIpService.looksLikeIpAddress(""));
        assertFalse(GeoIpService.looksLikeIpAddress("a".repeat(200)));
    }

    @Test
    @DisplayName("GeoLocationBO normalises blanks and exposes nulls for database columns")
    void geoLocationNormalisation() {
        GeoLocationBO blank = GeoLocationBO.of(null, "  ", "");
        assertTrue(blank.isUnknown());
        assertEquals("-", blank.city());
        assertNull(blank.cityOrNull(), "database columns should hold NULL, not a dash");

        GeoLocationBO real = GeoLocationBO.of(" Olathe ", "Kansas", "United States");
        assertEquals("Olathe", real.city(), "values are trimmed");
        assertEquals("Kansas", real.regionOrNull());
        assertFalse(real.isUnknown());
    }

    private static GeoIpService serviceWithPath(String path) {
        GeoIpProperties props = new GeoIpProperties();
        props.setDatabasePath(path);
        GeoIpService service = new GeoIpService(props);
        service.init();
        return service;
    }
}
