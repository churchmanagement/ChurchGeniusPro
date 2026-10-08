package com.churchgeniuspro.service;

import com.churchgeniuspro.config.GeoIpProperties;
import com.churchgeniuspro.model.GeoLocationBO;
import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves an IP address to an approximate city / state / country using the MaxMind
 * GeoLite2 City database held on local disk.
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>Never fails a login.</b> Every failure mode — file missing, file corrupt,
 *       unparseable address, address not in the database — degrades to
 *       {@link GeoLocationBO#unknown()}. Geolocation is decoration on an audit record; it
 *       is never worth turning a lookup problem into a failed sign-in.</li>
 *   <li><b>No outbound call.</b> The lookup is a memory-mapped read of a local file, so it
 *       adds microseconds to a login rather than a network round-trip, works when outbound
 *       traffic is restricted, and sends no member IP address to a third party.</li>
 *   <li><b>Cached.</b> A bounded LRU of resolved addresses. A congregation signs in from
 *       the same handful of addresses all week, so the cache absorbs nearly everything.</li>
 *   <li><b>Private addresses are skipped.</b> Loopback, RFC1918 and link-local addresses
 *       have no public location, so they are answered from a constant without a lookup.</li>
 * </ul>
 */
@Service
public class GeoIpService {

    private static final Logger LOG = LoggerFactory.getLogger(GeoIpService.class);

    /** Used when {@code geoip.database-path} is not set. Persistent on App Service. */
    private static final String DEFAULT_RELATIVE_PATH = "data/GeoLite2-City.mmdb";

    private final GeoIpProperties props;

    /** {@code null} when the database is unavailable — the service then always answers "unknown". */
    private volatile DatabaseReader reader;

    /** Bounded LRU: IP string → resolved location (including negative results). */
    private Map<String, GeoLocationBO> cache;

    public GeoIpService(GeoIpProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        int capacity = Math.max(64, props.getCacheSize());
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, GeoLocationBO> eldest) {
                return size() > capacity;
            }
        });

        if (!props.isEnabled()) {
            LOG.info("GeoIP lookup disabled (geoip.enabled=false) — login records will omit city/state/country.");
            return;
        }

        File db = resolveDatabaseFile();
        if (!db.isFile() || !db.canRead()) {
            // A warning, not an error: the app is fully functional without it.
            LOG.warn("GeoIP database not found at '{}' — login records will show city/state/country as '-'. "
                   + "Run scripts/update-geoip.sh (or set GEOIP_DB_PATH) to enable geolocation. See LOGGING.md.",
                     db.getAbsolutePath());
            return;
        }

        try {
            this.reader = new DatabaseReader.Builder(db).withCache(new CHMCache()).build();
            LOG.info("GeoIP database loaded from '{}' ({} KB).", db.getAbsolutePath(), db.length() / 1024);
        } catch (Exception ex) {
            LOG.warn("GeoIP database at '{}' could not be opened ({}) — continuing without geolocation.",
                     db.getAbsolutePath(), ex.getMessage());
        }
    }

    @PreDestroy
    void close() {
        DatabaseReader r = this.reader;
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
                // Shutting down anyway — nothing useful to do with this.
            }
        }
    }

    /**
     * Best-effort location for {@code ip}. Never throws, never blocks on the network,
     * never returns {@code null}.
     */
    public GeoLocationBO resolve(String ip) {
        if (ip == null || ip.isBlank() || reader == null) return GeoLocationBO.unknown();

        GeoLocationBO cached = cache.get(ip);
        if (cached != null) return cached;

        GeoLocationBO resolved = lookup(ip);
        cache.put(ip, resolved);   // negative results are cached too — a miss is stable
        return resolved;
    }

    private GeoLocationBO lookup(String ip) {
        try {
            if (!looksLikeIpAddress(ip)) return GeoLocationBO.unknown();

            InetAddress address = InetAddress.getByName(ip);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                return GeoLocationBO.unknown();   // private range — no public location exists
            }

            Optional<CityResponse> response = reader.tryCity(address);
            if (response.isEmpty()) return GeoLocationBO.unknown();

            CityResponse city = response.get();
            return GeoLocationBO.of(
                    city.getCity() != null ? city.getCity().getName() : null,
                    // "Most specific subdivision" is the state in the US, the province or
                    // county elsewhere — the closest equivalent across countries.
                    city.getMostSpecificSubdivision() != null ? city.getMostSpecificSubdivision().getName() : null,
                    city.getCountry() != null ? city.getCountry().getName() : null);

        } catch (Exception ex) {
            // Includes AddressNotFoundException, which is entirely routine.
            LOG.debug("GeoIP lookup failed for '{}': {}", ip, ex.toString());
            return GeoLocationBO.unknown();
        }
    }

    /** True when the database is loaded and lookups will be attempted. */
    public boolean isAvailable() {
        return reader != null;
    }

    private File resolveDatabaseFile() {
        String configured = props.getDatabasePath();
        if (configured != null && !configured.isBlank()) {
            return new File(configured.trim());
        }
        // $HOME is the persistent Azure Files share on App Service, and the user's home
        // directory elsewhere — either way a sensible place for a 60MB data file that
        // must outlive the deployment directory.
        String home = System.getenv("HOME");
        return (home == null || home.isBlank())
                ? new File(DEFAULT_RELATIVE_PATH)
                : Path.of(home, DEFAULT_RELATIVE_PATH).toFile();
    }

    /**
     * Rejects anything that is not plausibly a literal IP address, so a hostname can never
     * turn a log write into a blocking DNS query.
     */
    static boolean looksLikeIpAddress(String value) {
        if (value.isEmpty() || value.length() > 45) return false;
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') { hasDigit = true; continue; }
            boolean hexOrSeparator = (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':' || c == '%';
            if (!hexOrSeparator) return false;
        }
        return hasDigit;
    }
}
