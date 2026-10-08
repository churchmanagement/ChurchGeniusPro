package com.churchgeniuspro.model;

/**
 * Approximate physical location derived from an IP address.
 *
 * <p>City-level IP geolocation is a hint, not a fact — VPNs, mobile carrier NAT and
 * corporate egress routinely place a user hundreds of miles from where they are. It is
 * useful for spotting "this account signed in from three countries in an hour", and it is
 * not evidence of where anyone actually was.
 *
 * <p>{@link #unknown()} is returned whenever lookup is unavailable or the address is
 * private, so callers never have to null-check.
 */
public record GeoLocationBO(String city, String region, String country) {

    /** Placeholder written to the log when a location could not be determined. */
    public static final String UNKNOWN_VALUE = "-";

    private static final GeoLocationBO UNKNOWN =
            new GeoLocationBO(UNKNOWN_VALUE, UNKNOWN_VALUE, UNKNOWN_VALUE);

    /** Shared instance for "no location available". */
    public static GeoLocationBO unknown() {
        return UNKNOWN;
    }

    /** Null-safe factory — blank components become {@link #UNKNOWN_VALUE}. */
    public static GeoLocationBO of(String city, String region, String country) {
        return new GeoLocationBO(blankToUnknown(city), blankToUnknown(region), blankToUnknown(country));
    }

    /** True when nothing at all could be resolved. */
    public boolean isUnknown() {
        return UNKNOWN_VALUE.equals(city) && UNKNOWN_VALUE.equals(region) && UNKNOWN_VALUE.equals(country);
    }

    /** {@code null} instead of the placeholder, for nullable database columns. */
    public String cityOrNull()    { return orNull(city); }
    /** {@code null} instead of the placeholder, for nullable database columns. */
    public String regionOrNull()  { return orNull(region); }
    /** {@code null} instead of the placeholder, for nullable database columns. */
    public String countryOrNull() { return orNull(country); }

    private static String orNull(String v) {
        return UNKNOWN_VALUE.equals(v) ? null : v;
    }

    private static String blankToUnknown(String v) {
        return (v == null || v.isBlank()) ? UNKNOWN_VALUE : v.trim();
    }
}
