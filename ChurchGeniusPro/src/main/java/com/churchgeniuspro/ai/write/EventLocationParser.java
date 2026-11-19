package com.churchgeniuspro.ai.write;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an event's location out of plain text, with rules only (no model call).
 *
 * <p>Used by {@link AiFormDraftService} for "Describe your event": the model is asked for the
 * location, and this class (a) finds it in the description when the model left it out and
 * (b) splits a one-line address ("16127 S Bradley Dr Olathe KS 66062") into the Event form's own
 * fields. The result uses the form's keys: {@code address1, address2, city, state, country,
 * pinCode}. {@code state} is a two-letter US code; {@code country} is one of the form's values.
 *
 * <p>Nothing is guessed: a part that cannot be read is left out, and a venue name with no street
 * ("Fellowship Hall") goes into {@code address1} as written.
 */
public final class EventLocationParser {

    private EventLocationParser() {}

    public static final List<String> KEYS = List.of("address1", "address2", "city", "state", "country", "pinCode");
    public static final Set<String> COUNTRIES = Set.of("USA", "CAN", "GBR", "AUS", "IND", "OTHER");

    private static final Map<String, String> STATE_BY_NAME = new LinkedHashMap<>();
    static {
        String[][] s = {
            {"AL","Alabama"},{"AK","Alaska"},{"AZ","Arizona"},{"AR","Arkansas"},{"CA","California"},{"CO","Colorado"},{"CT","Connecticut"},
            {"DE","Delaware"},{"FL","Florida"},{"GA","Georgia"},{"HI","Hawaii"},{"ID","Idaho"},{"IL","Illinois"},{"IN","Indiana"},{"IA","Iowa"},
            {"KS","Kansas"},{"KY","Kentucky"},{"LA","Louisiana"},{"ME","Maine"},{"MD","Maryland"},{"MA","Massachusetts"},{"MI","Michigan"},
            {"MN","Minnesota"},{"MS","Mississippi"},{"MO","Missouri"},{"MT","Montana"},{"NE","Nebraska"},{"NV","Nevada"},{"NH","New Hampshire"},
            {"NJ","New Jersey"},{"NM","New Mexico"},{"NY","New York"},{"NC","North Carolina"},{"ND","North Dakota"},{"OH","Ohio"},{"OK","Oklahoma"},
            {"OR","Oregon"},{"PA","Pennsylvania"},{"RI","Rhode Island"},{"SC","South Carolina"},{"SD","South Dakota"},{"TN","Tennessee"},
            {"TX","Texas"},{"UT","Utah"},{"VT","Vermont"},{"VA","Virginia"},{"WA","Washington"},{"WV","West Virginia"},{"WI","Wisconsin"},{"WY","Wyoming"}};
        for (String[] p : s) STATE_BY_NAME.put(p[1].toLowerCase(Locale.ROOT), p[0]);
    }
    private static final Set<String> STATE_CODES = Set.copyOf(STATE_BY_NAME.values());

    /** The two-letter code for "KS", "ks", "Kansas" or "KS - Kansas"; null when it is not a US state. */
    public static String stateCode(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("\\.$", "");
        if (t.isEmpty()) return null;
        Matcher lead = Pattern.compile("^([A-Za-z]{2})\\s*[-–—]\\s*.+$").matcher(t);
        if (lead.matches() && STATE_CODES.contains(lead.group(1).toUpperCase(Locale.ROOT))) return lead.group(1).toUpperCase(Locale.ROOT);
        if (t.length() == 2 && STATE_CODES.contains(t.toUpperCase(Locale.ROOT))) return t.toUpperCase(Locale.ROOT);
        return STATE_BY_NAME.get(t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
    }

    /** One of the form's country values for "USA", "United States", "Canada" …; null when unknown. */
    public static String countryCode(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(Locale.ROOT).replace(".", "").replaceAll("\\s+", " ");
        Matcher lead = Pattern.compile("^([a-z]{3})\\s*[-–—]\\s*.+$").matcher(t);
        if (lead.matches() && COUNTRIES.contains(lead.group(1).toUpperCase(Locale.ROOT))) return lead.group(1).toUpperCase(Locale.ROOT);
        return switch (t) {
            case "usa", "us", "united states", "united states of america", "america" -> "USA";
            case "can", "canada" -> "CAN";
            case "gbr", "uk", "united kingdom", "great britain", "england" -> "GBR";
            case "aus", "australia" -> "AUS";
            case "ind", "india" -> "IND";
            case "other" -> "OTHER";
            default -> null;
        };
    }

    // ── 1. find the location inside a description ───────────────────────────────────────────

    /** Words after which a period is part of an address ("S. Bradley Dr. Olathe"), not the end of it. */
    private static final Set<String> ABBREV = Set.of("st", "dr", "ave", "rd", "blvd", "ln", "ct", "hwy", "pkwy", "pl", "ter", "cir", "trl",
            "n", "s", "e", "w", "ne", "nw", "se", "sw", "ste", "apt", "mt", "ft", "no", "bldg", "rm");

    private static final Pattern LABELLED = Pattern.compile(
            "(?i)\\b(?:location|venue|address|place|where)\\s*(?:is|will be)?\\s*[:\\-–—=]\\s*");
    private static final Pattern AT = Pattern.compile("(?i)(?:\\bat|(?<!\\S)@)\\s+");
    private static final Pattern IN_THE = Pattern.compile(
            "(?i)\\bin\\s+(?=(?:the|our)\\s+(?:[A-Za-z'&.]+\\s+){0,4}(?:hall|room|sanctuary|gym|gymnasium|center|centre|church|chapel|building|park|auditorium|cafeteria|lobby|basement|pavilion|parking lot|lawn|library|kitchen)\\b)");
    private static final Pattern TIME_AHEAD = Pattern.compile(
            "(?i)^(?:about\\s+|around\\s+)?(?:\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?m\\.?|p\\.?m\\.?|o'?clock)\\b|\\d{1,2}:\\d{2}\\b|noon\\b|midnight\\b|\\d{1,2}\\s*(?:-|–|to)\\s*\\d{1,2}\\s*(?:a\\.?m\\.?|p\\.?m\\.?))");
    /** Where a location that started with "at …" stops when the sentence carries on. */
    private static final Pattern CARRY_ON = Pattern.compile(
            "(?i)\\s+(?:on|from|starting|beginning|begins|starts|until|till|with|for|and\\s+(?:is|will|we|it|the\\s+fee|registration)|registration|register|fee|cost|free|rsvp|max|capacity|limited|bring|please|ask)\\b"
          + "|\\s+at\\s+(?=\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?m|p\\.?m)|noon|midnight)"
          + "|\\s+(?:\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?m\\.?|p\\.?m\\.?))\\b");

    /**
     * The location named in a description, already split into the form's fields; empty when the
     * description names none. Recognised: "Location: …", "Venue: …", "Address: …", "Place: …",
     * "at …" (never a time), "@ …" and "in the … Hall / Room / Sanctuary …".
     */
    public static Map<String, String> fromDescription(String description) {
        String raw = find(description);
        return raw == null ? new LinkedHashMap<>() : parse(raw);
    }

    /** The location text as written in the description, or null. */
    public static String find(String description) {
        if (description == null || description.isBlank()) return null;
        String d = description.replace('\r', '\n');
        Matcher m = LABELLED.matcher(d);
        if (m.find()) {
            String got = clean(cut(d.substring(m.end()), false));
            if (got != null) return got;
        }
        m = AT.matcher(d);
        while (m.find()) {
            String rest = d.substring(m.end());
            if (TIME_AHEAD.matcher(rest).find()) continue;                       // "at 7:30 pm"
            Matcher again = LABELLED.matcher(rest);
            if (again.lookingAt()) rest = rest.substring(again.end());           // "at Location: …"
            String got = clean(cut(rest, true));
            if (got != null && looksLikePlace(got)) return got;
        }
        m = IN_THE.matcher(d);
        if (m.find()) {
            String got = clean(cut(d.substring(m.end()), true));
            if (got != null) return got;
        }
        return null;
    }

    /** Ends the candidate at the end of its line / sentence (and, for "at …", where the sentence carries on). */
    private static String cut(String rest, boolean sentence) {
        int end = rest.length();
        int nl = rest.indexOf('\n'); if (nl >= 0) end = Math.min(end, nl);
        int semi = rest.indexOf(';'); if (semi >= 0) end = Math.min(end, semi);
        Matcher dot = Pattern.compile("([A-Za-z0-9]+)\\.(?=\\s|$)").matcher(rest);
        while (dot.find()) {
            if (dot.end() > end) break;
            if (ABBREV.contains(dot.group(1).toLowerCase(Locale.ROOT)) && dot.end() < rest.length()
                    && !rest.substring(dot.end()).isBlank()
                    && !Pattern.compile("^\\s+[A-Z][a-z]+\\s+[a-z]").matcher(rest.substring(dot.end())).find()) continue;
            end = Math.min(end, dot.end() - 1);
            break;
        }
        String s = rest.substring(0, end);
        if (sentence) {
            Matcher c = CARRY_ON.matcher(s);
            if (c.find()) s = s.substring(0, c.start());
        }
        return s;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("^[\"'“”(\\[]+|[\"'“”)\\]]+$", "").replaceAll("[\\s,.;:!]+$", "").replaceAll("\\s+", " ").trim();
        return t.length() < 3 || t.length() > 200 ? null : t;
    }

    /** "at" introduces many things ("at no cost", "at least 10"); keep only what reads as a place. */
    private static boolean looksLikePlace(String s) {
        if (s.matches("^\\d+\\s+\\S.*")) return true;                             // "16127 S Bradley Dr"
        if (s.matches("(?i)^(?:no|least|most|all|once|first|last|this|that|your|their|any|home\\s+or)\\b.*")) return false;
        return Character.isUpperCase(s.charAt(0)) || s.toLowerCase(Locale.ROOT).matches("^(?:the|our)\\s+.+");
    }

    // ── 2. split one line into the form's fields ────────────────────────────────────────────

    private static final Pattern ZIP_END = Pattern.compile("(?:^|[\\s,])(\\d{5}(?:-\\d{4})?)$");
    private static final Pattern UNIT = Pattern.compile("(?i)[,\\s]+((?:apt|apartment|suite|ste|unit|bldg|building|room|rm|floor|fl)\\.?\\s*#?\\s*[A-Za-z0-9-]+|#\\s*[A-Za-z0-9-]+)\\s*$");
    private static final Set<String> SUFFIX = Set.of("st", "street", "dr", "drive", "ave", "avenue", "rd", "road", "blvd", "boulevard", "ln", "lane",
            "ct", "court", "way", "pkwy", "parkway", "hwy", "highway", "pl", "place", "ter", "terrace", "cir", "circle", "trl", "trail", "sq", "square",
            "loop", "run", "pike", "row", "path", "plaza", "expy", "expressway", "fwy", "freeway", "crossing", "xing");
    private static final Set<String> DIRECTION = Set.of("n", "s", "e", "w", "ne", "nw", "se", "sw", "north", "south", "east", "west");

    /** Splits "16127 S Bradley Dr, Olathe, KS 66062" (commas optional) into the form's fields. */
    public static Map<String, String> parse(String location) {
        Map<String, String> out = new LinkedHashMap<>();
        String s = clean(location);
        if (s == null) return out;

        // country at the end
        Matcher cm = Pattern.compile("(?i)[,\\s]+(USA|U\\.S\\.A\\.?|United States(?: of America)?|Canada|United Kingdom|Australia|India)$").matcher(s);
        if (cm.find()) { String c = countryCode(cm.group(1)); if (c != null) { out.put("country", c); s = s.substring(0, cm.start()).trim(); } }

        // zip at the end
        Matcher zm = ZIP_END.matcher(s);
        String zip = null;
        if (zm.find()) { zip = zm.group(1); s = s.substring(0, zm.start(1)).replaceAll("[\\s,]+$", ""); }

        // state at the end: a full name, or a two-letter code (upper case, or after a comma / before a zip)
        String state = null;
        for (Map.Entry<String, String> e : STATE_BY_NAME.entrySet()) {
            Matcher sm = Pattern.compile("(?i)(?:^|[\\s,])(" + Pattern.quote(e.getKey()) + ")$").matcher(s);
            if (sm.find() && sm.start(1) > 0) {
                // "New York" must win over "York"; "West Virginia" over "Virginia" — longest name that fits
                if (state == null || e.getKey().length() > nameLength(state)) { state = e.getValue(); }
            }
        }
        if (state != null) {
            String name = nameOf(state);
            s = s.substring(0, s.length() - name.length()).replaceAll("[\\s,]+$", "");
        } else {
            Matcher sm = Pattern.compile("(?:^|[\\s,])([A-Za-z]{2})$").matcher(s);
            if (sm.find() && sm.start(1) > 0 && STATE_CODES.contains(sm.group(1).toUpperCase(Locale.ROOT))) {
                String code = sm.group(1);
                boolean confident = code.equals(code.toUpperCase(Locale.ROOT)) || zip != null || s.charAt(sm.start(1) - 1) == ',' || s.substring(0, sm.start(1)).trim().endsWith(",");
                if (confident) { state = code.toUpperCase(Locale.ROOT); s = s.substring(0, sm.start(1)).replaceAll("[\\s,]+$", ""); }
            }
        }

        String street, city = null, second = null;
        String[] parts = s.split("\\s*,\\s*");
        if (parts.length >= 2 && (state != null || zip != null)) {
            city = parts[parts.length - 1];
            if (parts.length >= 3 && !startsWithNumber(parts[0]) && startsWithNumber(parts[1])) {
                second = parts[0]; street = join(parts, 1, parts.length - 1);    // "Grace Hall, 12 Main St, Olathe"
            } else street = join(parts, 0, parts.length - 1);
        } else if (parts.length >= 2) {
            if (!startsWithNumber(parts[0]) && startsWithNumber(parts[1])) { second = parts[0]; street = join(parts, 1, parts.length); }
            else street = s;
        } else {
            street = s;
            if ((state != null || zip != null) && startsWithNumber(s)) {         // no commas: split after the street type
                String[] w = s.split("\\s+");
                int cutAt = -1;
                for (int i = w.length - 1; i >= 2; i--) {
                    if (SUFFIX.contains(w[i].toLowerCase(Locale.ROOT).replace(".", ""))) { cutAt = i; break; }
                }
                if (cutAt >= 0) {
                    int after = cutAt + 1;
                    if (after < w.length - 1 && DIRECTION.contains(w[after].toLowerCase(Locale.ROOT).replace(".", ""))) after++;   // "Main St NW Olathe"
                    while (after < w.length - 1 && w[after].matches("(?i)(?:apt|apartment|suite|ste|unit|bldg|rm|room|floor|fl)\\.?|#.*")) after += w[after].startsWith("#") && w[after].length() > 1 ? 1 : 2;
                    if (after < w.length) { street = String.join(" ", java.util.Arrays.copyOfRange(w, 0, after)); city = String.join(" ", java.util.Arrays.copyOfRange(w, after, w.length)); }
                }
            }
        }

        if (street != null) {
            Matcher um = UNIT.matcher(street);
            if (um.find() && um.start() > 0) { String unit = um.group(1).trim(); street = street.substring(0, um.start()).trim(); second = second == null ? unit : second + ", " + unit; }
        }
        put(out, "address1", street, 120);
        put(out, "address2", second, 120);
        put(out, "city", city, 80);
        if (state != null) out.put("state", state);
        if (zip != null) out.put("pinCode", zip);
        if (!out.containsKey("country") && (state != null || (zip != null && city != null))) out.put("country", "USA");
        // keep the form's order
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String k : KEYS) if (out.containsKey(k)) ordered.put(k, out.get(k));
        return ordered;
    }

    private static void put(Map<String, String> out, String k, String v, int max) {
        if (v == null) return;
        String t = v.trim().replaceAll("[\\s,]+$", "");
        if (!t.isEmpty()) out.put(k, t.length() > max ? t.substring(0, max) : t);
    }
    private static boolean startsWithNumber(String s) { return s != null && s.trim().matches("^\\d+\\s+\\S.*"); }
    private static String join(String[] a, int from, int to) { return String.join(", ", java.util.Arrays.copyOfRange(a, from, to)); }
    private static String nameOf(String code) { for (Map.Entry<String, String> e : STATE_BY_NAME.entrySet()) if (e.getValue().equals(code)) return e.getKey(); return ""; }
    private static int nameLength(String code) { return nameOf(code).length(); }
}
