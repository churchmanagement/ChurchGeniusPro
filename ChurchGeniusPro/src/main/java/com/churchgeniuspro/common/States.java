package com.churchgeniuspro.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility class providing a mapping of integer codes (1–50) to
 * U.S. state two-letter abbreviations, ordered alphabetically by state name.
 *
 * <p>The {@code STATE_CODES} map is unmodifiable and safe for shared use.
 * The integer key corresponds to the {@code state} column in {@code AddressDTO}.
 */
public final class States {

    /** Unmodifiable map of numeric code → two-letter U.S. state abbreviation. */
    public static final Map<Integer, String> STATE_CODES;

    static {
        LinkedHashMap<Integer, String> map = new LinkedHashMap<>();

        map.put( 1, "AL");  // Alabama
        map.put( 2, "AK");  // Alaska
        map.put( 3, "AZ");  // Arizona
        map.put( 4, "AR");  // Arkansas
        map.put( 5, "CA");  // California
        map.put( 6, "CO");  // Colorado
        map.put( 7, "CT");  // Connecticut
        map.put( 8, "DE");  // Delaware
        map.put( 9, "FL");  // Florida
        map.put(10, "GA");  // Georgia
        map.put(11, "HI");  // Hawaii
        map.put(12, "ID");  // Idaho
        map.put(13, "IL");  // Illinois
        map.put(14, "IN");  // Indiana
        map.put(15, "IA");  // Iowa
        map.put(16, "KS");  // Kansas
        map.put(17, "KY");  // Kentucky
        map.put(18, "LA");  // Louisiana
        map.put(19, "ME");  // Maine
        map.put(20, "MD");  // Maryland
        map.put(21, "MA");  // Massachusetts
        map.put(22, "MI");  // Michigan
        map.put(23, "MN");  // Minnesota
        map.put(24, "MS");  // Mississippi
        map.put(25, "MO");  // Missouri
        map.put(26, "MT");  // Montana
        map.put(27, "NE");  // Nebraska
        map.put(28, "NV");  // Nevada
        map.put(29, "NH");  // New Hampshire
        map.put(30, "NJ");  // New Jersey
        map.put(31, "NM");  // New Mexico
        map.put(32, "NY");  // New York
        map.put(33, "NC");  // North Carolina
        map.put(34, "ND");  // North Dakota
        map.put(35, "OH");  // Ohio
        map.put(36, "OK");  // Oklahoma
        map.put(37, "OR");  // Oregon
        map.put(38, "PA");  // Pennsylvania
        map.put(39, "RI");  // Rhode Island
        map.put(40, "SC");  // South Carolina
        map.put(41, "SD");  // South Dakota
        map.put(42, "TN");  // Tennessee
        map.put(43, "TX");  // Texas
        map.put(44, "UT");  // Utah
        map.put(45, "VT");  // Vermont
        map.put(46, "VA");  // Virginia
        map.put(47, "WA");  // Washington
        map.put(48, "WV");  // West Virginia
        map.put(49, "WI");  // Wisconsin
        map.put(50, "WY");  // Wyoming

        STATE_CODES = Collections.unmodifiableMap(map);
    }

    /**
     * Returns the two-letter state abbreviation for the given code,
     * or {@code null} if the code is not in the range 1–50.
     *
     * @param code numeric state code (1–50)
     * @return two-letter abbreviation, e.g. {@code "TX"}, or {@code null}
     */
    public static String getCode(int code) {
        return STATE_CODES.get(code);
    }

    /**
     * Returns the numeric code for a given two-letter abbreviation (case-insensitive),
     * or {@code -1} if not found.
     *
     * @param abbreviation two-letter state code, e.g. {@code "TX"}
     * @return numeric code (1–50), or {@code -1} if not found
     */
    public static int getCodeByAbbreviation(String abbreviation) {
        if (abbreviation == null) return -1;
        String upper = abbreviation.toUpperCase();
        return STATE_CODES.entrySet()
                .stream()
                .filter(e -> e.getValue().equals(upper))
                .mapToInt(Map.Entry::getKey)
                .findFirst()
                .orElse(-1);
    }

    private States() {
        // utility class — no instantiation
    }
}
