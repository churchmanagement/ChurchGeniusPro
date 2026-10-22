package com.churchgeniuspro.ai;

import com.churchgeniuspro.service.AiNavigationCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Search Phase G — the navigation catalogue must never be MORE permissive than the page routes
 * it describes. For every catalogue page (member tabs excepted — they are governed by member keys)
 * the controller method mapped to that path is located in the sources and its RoleGuard calls are
 * read: the roles the route admits, whether it admits the church login, and the page-permission key
 * it checks. The catalogue entry may be equal or stricter, never looser:
 * <ul>
 *   <li>catalogue roles ⊆ route roles;</li>
 *   <li>catalogue admits the church login only if the route does;</li>
 *   <li>when the route checks a page key, the catalogue carries the same key;</li>
 *   <li>an opt-in page (requireFeature) carries the same feature key.</li>
 * </ul>
 * A route that cannot be found is a failure too — a catalogue link must point at a real, guarded page.
 */
@DisplayName("AI Search — navigation catalogue vs. route guards")
class AiNavigationCatalogRouteCrossCheckTest {

    static final Set<String> ADMIN = Set.of("SuperAdmin", "Admin");
    static final Set<String> ADMIN_OR_USER = Set.of("SuperAdmin", "Admin", "User");
    static final Set<String> ACCT_OR_ADMIN = Set.of("SuperAdmin", "Admin", "Accountant");
    static final Set<String> STAFF = Set.of("SuperAdmin", "Admin", "Accountant", "User");

    /** What a route admits: staff roles, church login, page key, opt-in feature key. */
    record Route(String file, Set<String> roles, boolean church, String pageKey, String optInKey) {}

    static final Pattern MAPPING = Pattern.compile("@(Get|Request)Mapping\\s*\\(([^)]*)\\)");
    static final Pattern ANY_MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\b");

    static Map<String, Route> routes() throws IOException {
        Map<String, Route> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java/com/churchgeniuspro"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                if (!src.contains("@Controller") && !src.contains("@RestController")) continue;
                // A class-level @RequestMapping prefixes every method path (e.g. /api/reports + /income).
                String prefix = "";
                Matcher cd = Pattern.compile("(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+)*class\\s+\\w+").matcher(src);
                int cls = cd.find() ? cd.start() : src.length();
                Matcher cm = Pattern.compile("@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\"(/[^\"]*)\"").matcher(src.substring(0, cls));
                if (cm.find()) prefix = cm.group(1);
                Matcher m = MAPPING.matcher(src);
                while (m.find()) {
                    if (m.start() < cls) continue;                             // the class-level mapping itself
                    // body = from this mapping to the next mapping annotation (or EOF)
                    int start = m.end();
                    Matcher next = ANY_MAPPING.matcher(src); int end = src.length();
                    if (next.find(start)) end = next.start();
                    String body = src.substring(start, end);
                    Route r = guards(p.getFileName().toString(), body);
                    Matcher path = Pattern.compile("\"(/[^\"]*)\"").matcher(m.group(2));
                    while (path.find()) out.putIfAbsent(prefix + path.group(1), r);
                }
            }
        }
        return out;
    }

    static Route guards(String file, String body) {
        Set<String> roles = null; boolean church = true; String pageKey = null, optIn = null;
        // Each RoleGuard call narrows the route (intersection), exactly as the sequence of early returns does.
        if (body.contains("requireAuth("))               { roles = and(roles, STAFF); }
        if (body.contains("requireStaffOrMember("))      { roles = and(roles, STAFF); church = false; }
        if (body.contains("requireAdminOrMember("))      { roles = and(roles, ADMIN); church = false; }
        if (body.contains("requireAdminOrUser("))        { roles = and(roles, ADMIN_OR_USER); church = false; }
        if (body.contains("requireAccountantOrAdmin(") || body.contains("requireAdminOrAccountant(")) { roles = and(roles, ACCT_OR_ADMIN); church = false; }
        if (body.contains("requireAccountant("))         { roles = and(roles, Set.of("SuperAdmin", "Accountant")); church = false; }
        if (body.contains("requirePayroll("))            { roles = and(roles, ACCT_OR_ADMIN); }
        if (body.contains("requireAdminOrChurch("))      { roles = and(roles, ADMIN); }
        if (body.contains("requireAdmin("))              { roles = and(roles, ADMIN); church = false; }
        if (body.contains("requireSuperAdmin("))         { roles = and(roles, Set.of("SuperAdmin")); church = false; }
        if (body.contains("requireChurch("))             { roles = and(roles, Set.of()); }
        Matcher pk = Pattern.compile("requirePagePermission\\([^,]+,\\s*\"([^\"]+)\"").matcher(body);
        if (pk.find()) pageKey = pk.group(1);
        else { Matcher pp = Pattern.compile("requirePermission\\([^,]+,\\s*\"([^\"]+)\"").matcher(body); if (pp.find()) pageKey = pp.group(1); }
        Matcher ft = Pattern.compile("requireFeature\\([^,]+,\\s*(?:RoleGuard\\.)?([A-Z_]+|\"[^\"]+\")").matcher(body);
        if (ft.find()) { String k = ft.group(1); optIn = k.startsWith("\"") ? k.substring(1, k.length() - 1) : constant(k); }
        if (roles == null) roles = STAFF;                 // only a page-key / feature gate: any signed-in staff login
        return new Route(file, roles, church, pageKey, optIn);
    }
    static Set<String> and(Set<String> a, Set<String> b) { if (a == null) return new HashSet<>(b); Set<String> r = new HashSet<>(a); r.retainAll(b); return r; }
    static String constant(String name) {
        try { return String.valueOf(com.churchgeniuspro.util.RoleGuard.class.getField(name).get(null)); } catch (Exception e) { return name; }
    }

    static String path(String url) { int c = url.indexOf('?'); if (c > 0) url = url.substring(0, c); c = url.indexOf('#'); if (c > 0) url = url.substring(0, c); return url; }

    @Test @DisplayName("every catalogue page maps to a real route and is never more permissive than that route's guards")
    void catalogueNeverLooserThanRoutes() throws IOException {
        Map<String, Route> routes = routes();
        List<String> problems = new ArrayList<>(), stricter = new ArrayList<>();
        int checked = 0;
        for (AiNavigationCatalog.Page p : AiNavigationCatalog.PAGES) {
            AiNavigationCatalog.Rule rule = p.rule();
            if (rule.memberKey() != null) continue;                        // member tabs: governed by member.* keys, checked elsewhere
            Route r = routes.get(path(p.url()));
            if (r == null) { problems.add(p.url() + ": no controller route found"); continue; }
            checked++;
            if (rule.optInKey() != null) {
                if (!rule.optInKey().equals(r.optInKey())) problems.add(p.url() + ": catalogue opt-in key " + rule.optInKey() + " but route gate " + r.optInKey());
                continue;
            }
            if (!r.roles().containsAll(rule.roles())) problems.add(p.url() + ": catalogue roles " + rule.roles() + " ⊄ route roles " + r.roles() + " (" + r.file() + ")");
            if (rule.church() && !r.church())            problems.add(p.url() + ": catalogue admits the church login but the route does not (" + r.file() + ")");
            if (r.pageKey() != null && !r.pageKey().equals(rule.pageKey()))
                problems.add(p.url() + ": route checks page key " + r.pageKey() + " but catalogue has " + rule.pageKey() + " (" + r.file() + ")");
            if (r.optInKey() != null)                     problems.add(p.url() + ": route is opt-in (" + r.optInKey() + ") but catalogue is not");
            if (!rule.roles().equals(r.roles()) || (r.church() && !rule.church()) || (rule.pageKey() != null && r.pageKey() == null))
                stricter.add(p.url() + ": catalogue " + rule.roles() + (rule.church() ? "+church" : "") + "/" + rule.pageKey()
                           + " vs route " + r.roles() + (r.church() ? "+church" : "") + "/" + r.pageKey());
        }
        System.out.println("[catalogue cross-check] routes checked: " + checked + "; catalogue stricter than route (allowed): " + stricter.size());
        stricter.forEach(s -> System.out.println("  stricter  " + s));
        assertThat(problems).as("catalogue entries looser than their routes, or unmapped").isEmpty();
        assertThat(checked).isGreaterThanOrEqualTo(50);
    }
}
