package com.churchgeniuspro.errors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet (error-handling audit 2026-10-28, M4 / M5 / M6 / M12): pages read the
 * status before the body, failed mutations are reported, and the service worker
 * never replays an answer from its cache.
 *
 * <p><b>Unchecked parse</b> — the body is read as JSON while nothing looks at the
 * status, so a 401/403/500 error body is rendered as data:
 * <ol>
 *   <li>{@code .then(r => r.json())} / {@code .then(function(r){ return r.json(); })};</li>
 *   <li>{@code await (await fetch(...)).json()};</li>
 *   <li>{@code X = await fetch(...);} immediately followed by {@code Y = await X.json()}
 *       with no {@code X.ok} / {@code X.status} on the fetch statement, the json line or
 *       the six lines after it.</li>
 * </ol>
 * Use {@code CGP_api(url, opts)} (static/cgp-api.js), which rejects with the server's
 * message (and reference) on any non-2xx answer, or test {@code res.ok} yourself.
 *
 * <p>The pages migrated in this phase must have none (two documented Guess It sites
 * excepted); every other page may not get worse than {@link #UNMIGRATED_CEILING}.
 * Lower the ceiling when you migrate a page — never raise it.
 */
@DisplayName("Ratchet — pages check the status before using a response; failures are reported")
class PageFetchRatchetTest {

    static final Path STATIC = Paths.get("src/main/resources/static");

    /** Pages migrated to CGP_api in the 2026-10-28 Mediums phase (value = allowed unchecked parses). */
    static final Map<String, Integer> MIGRATED = new LinkedHashMap<>();
    static {
        for (String p : new String[] {
                "volunteers.html", "kidsMinistry.html", "followups.html", "pledges.html", "attendance.html",
                "temporaryAccess.html", "home.html", "meeting.html", "viewFamily.html", "family.html",
                "sundaySchool.html", "event.html", "event-volunteers.html", "income.html", "expense.html",
                "serviceadminhome.html", "memberWorship.html"}) {
            MIGRATED.put(p, 0);
        }
        // Guess It: the game-state answers (gameEnded, eliminated, alreadyWon, …) arrive in
        // non-2xx bodies and ARE the data the page renders (audit L1) — giSubmitAnswer, ggmPoll.
        MIGRATED.put("memberHome.html", 2);
    }

    /** Unchecked parses left on every page not in {@link #MIGRATED} (was 179 site-wide before this phase). */
    static final int UNMIGRATED_CEILING = 57;

    /**
     * Swallowed mutations still allowed: memberWorship.html generateAutoAssignments() deletes the
     * old rows with {@code .catch(() => {})} — that code sits in the Automatic Assignment region
     * pinned by SHA-256 in worship/ManualAssignmentPageTest and needs its own approved change.
     */
    static final Map<String, Integer> SWALLOWED_MUTATION_ALLOWED = Map.of("memberWorship.html", 1);

    static final Pattern JSON_THEN = Pattern.compile(
            "\\.then\\(\\s*(?:function\\s*\\(\\s*(\\w+)\\s*\\)\\s*\\{\\s*return\\s+\\1\\.json\\(\\)\\s*;?\\s*\\}"
          + "|\\(?\\s*(\\w+)\\s*\\)?\\s*=>\\s*\\2\\.json\\(\\)\\s*)\\s*\\)");
    static final Pattern AWAIT_AWAIT = Pattern.compile("await\\s*\\(\\s*await\\s+fetch\\(");
    static final Pattern FETCH_ASSIGN = Pattern.compile("(?:(?:const|let|var)\\s+)?(\\w+)\\s*=\\s*await\\s+fetch\\(");
    static final Pattern NETWORK_ALERT = Pattern.compile("alert\\(\\s*['\"]Network error[^'\"]*['\"]\\s*\\)");
    static final Pattern SWALLOWED_MUTATION = Pattern.compile(
            "method\\s*:\\s*'(?:DELETE|PUT|POST|PATCH)'[^;]{0,400}?\\)\\s*\\.catch\\(\\s*(?:"
          + "function\\s*\\(\\s*\\w*\\s*\\)\\s*\\{\\s*(?:/\\*[^*]*\\*/)?\\s*\\}"
          + "|\\(\\s*\\w*\\s*\\)\\s*=>\\s*\\{\\s*\\}"
          + "|\\(\\s*\\)\\s*=>\\s*(?:null|undefined|0))\\s*\\)", Pattern.DOTALL);

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static String read(String page) throws IOException {
        return read(STATIC.resolve(page));
    }

    /** Line numbers of unchecked parses (rules 1–3 above). Package-visible for CgpApiHelperTest. */
    static List<Integer> uncheckedParses(String s) {
        List<Integer> out = new ArrayList<>();
        Matcher m = JSON_THEN.matcher(s);
        while (m.find()) out.add(lineOf(s, m.start()));
        m = AWAIT_AWAIT.matcher(s);
        while (m.find()) out.add(lineOf(s, m.start()));
        String[] lines = s.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher f = FETCH_ASSIGN.matcher(lines[i]);
            if (!f.find()) continue;
            String var = Pattern.quote(f.group(1));
            int j = i;
            while (j < lines.length && j < i + 12
                    && !lines[j].substring(j == i ? f.end() : 0).contains(");")) j++;
            int k = j + 1;
            if (k >= lines.length) continue;
            if (!Pattern.compile("\\bawait\\s+" + var + "\\.json\\(\\)").matcher(lines[k]).find()) continue;
            StringBuilder window = new StringBuilder();
            for (int w = i; w < Math.min(lines.length, k + 7); w++) window.append(lines[w]).append('\n');
            if (Pattern.compile("\\b" + var + "\\.(ok|status)\\b").matcher(window).find()) continue;
            out.add(k + 1);
        }
        return out;
    }

    static int lineOf(String s, int index) {
        int n = 1;
        for (int i = 0; i < index; i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    @DisplayName("migrated pages: no response body is used without a status check")
    void migratedPagesCheckTheStatus() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> e : MIGRATED.entrySet()) {
            List<Integer> hits = uncheckedParses(read(e.getKey()));
            if (hits.size() > e.getValue()) problems.add(e.getKey() + " lines " + hits + " (allowed " + e.getValue() + ")");
        }
        assertThat(problems).as("use CGP_api(url, opts) or test res.ok before reading the body").isEmpty();
    }

    @Test
    @DisplayName("migrated pages load cgp-api.js in <head>, before any page script")
    void migratedPagesLoadTheHelper() throws IOException {
        for (String page : MIGRATED.keySet()) {
            String s = read(page);
            int tag = s.indexOf("<script src=\"/cgp-api.js\"></script>");
            assertThat(tag).as(page + " loads /cgp-api.js").isGreaterThan(0);
            assertThat(tag).as(page + ": the helper must load in <head>").isLessThan(s.indexOf("</head>"));
            int firstInline = s.indexOf("<script>");
            if (firstInline >= 0) assertThat(tag).as(page + ": helper before the first inline script").isLessThan(firstInline);
        }
    }

    @Test
    @DisplayName("migrated pages: no hard-coded alert('Network error') and no swallowed mutation")
    void failuresAreReported() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String page : MIGRATED.keySet()) {
            String s = read(page);
            if (count(NETWORK_ALERT, s) > 0) problems.add(page + ": alert('Network error…') — use CGP_errorMessage(e, …)");
            int swallowed = count(SWALLOWED_MUTATION, s);
            int allowed = SWALLOWED_MUTATION_ALLOWED.getOrDefault(page, 0);
            if (swallowed > allowed) problems.add(page + ": " + swallowed + " mutation(s) with an empty .catch (allowed " + allowed + ")");
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every other page: unchecked parses may only go down")
    void unmigratedPagesDoNotGetWorse() throws IOException {
        int total = 0;
        List<String> where = new ArrayList<>();
        try (Stream<Path> files = Files.walk(STATIC)) {
            for (Path p : (Iterable<Path>) files.filter(f -> {
                String n = f.toString().replace('\\', '/');
                return (n.endsWith(".html") || n.endsWith(".js")) && !n.contains("/assets/");
            })::iterator) {
                String rel = STATIC.relativize(p).toString().replace('\\', '/');
                if (MIGRATED.containsKey(rel)) continue;
                int n = uncheckedParses(read(p)).size();
                if (n > 0) { total += n; where.add(rel + "=" + n); }
            }
        }
        assertThat(total)
                .as("unchecked parses outside the migrated pages (ceiling " + UNMIGRATED_CEILING
                        + "; lower it when you migrate a page, never raise it): " + where)
                .isLessThanOrEqualTo(UNMIGRATED_CEILING);
    }

    @Test
    @DisplayName("sw.js: cache-first only for images and fonts; JSON is never served from the cache")
    void serviceWorkerNeverCachesAnswers() throws IOException {
        String sw = read("sw.js");
        assertThat(count(Pattern.compile("respondWith\\(cacheFirst\\(req\\)\\)"), sw)).isEqualTo(1);
        int guard = sw.indexOf("if (isCacheableAsset(req, url)) {");
        int use = sw.indexOf("respondWith(cacheFirst(req))");
        assertThat(guard).as("cacheFirst is guarded by isCacheableAsset").isGreaterThan(0);
        assertThat(use).isGreaterThan(guard).isLessThan(guard + 120);
        Matcher ext = Pattern.compile("const CACHEABLE_EXT = /(.*)/i;").matcher(sw);
        assertThat(ext.find()).isTrue();
        assertThat(ext.group(1)).doesNotContain("json").doesNotContain("txt").doesNotContain("html");
        assertThat(sw).contains("req.destination === 'image' || req.destination === 'font'");
        assertThat(sw).contains("if (url.search) return false;");
    }

    @Test
    @DisplayName("the mutations the audit named refresh from the server instead of splicing on any status")
    void namedMutationsAreChecked() throws IOException {
        assertThat(read("family.html"))
                .contains("CGP_api(`/api/families/${familyId}/members/${m.id}`, { method: 'DELETE' })\n        .then(() => removeFamilyMemberRow(index, m))");
        assertThat(read("memberHome.html"))
                .contains("CGP_api('/api/families/' + currentFamilyId + '/members/' + m.id, { method:'DELETE' })")
                .contains("await CGP_api('/api/member/ss/teacher/exams/' + examId, { method:'PUT', headers:{'Content-Type':'application/json'}, body: JSON.stringify({status:'Published'}) });")
                .contains("await CGP_api('/api/member/ss/teacher/exams/' + examId, { method:'PUT', headers:{'Content-Type':'application/json'}, body: JSON.stringify({status:'Closed'}) });");
        for (String page : new String[] {"kidsMinistry.html", "sundaySchool.html"}) {
            assertThat(read(page)).as(page)
                    .contains("try { await CGP_api(`/api/ss/classes/${id}`, { method:'DELETE' }); } catch (e) { CGP_showError(e, 'The change was not saved.'); return; }")
                    .doesNotContain("\n  await fetch(`/api/ss/");
        }
        assertThat(read("attendance.html"))
                .contains("CGP_api('/api/attendance/records/'+id, { method:'DELETE' })")
                .contains(".catch(function(e){ CGP_showError(e, 'The record was not saved.'); });");
        assertThat(read("temporaryAccess.html"))
                .contains("CGP_api('/api/temp-access/'+id+'/revoke', { method:'POST' })")
                .contains("CGP_api('/api/temp-access/'+id, { method:'DELETE' })");
        assertThat(read("volunteers.html"))
                .contains("CGP_api('/api/volunteers/roles/' + id, { method:'DELETE' })")
                .doesNotContain("fetch('/api/volunteers/roles/' + id, { method:'DELETE' })");
        assertThat(read("memberWorship.html"))
                .contains("manualRows = manualRows.filter(r => !deleted.has(r));");
        assertThat(read("meeting.html"))
                .contains("return CGP_errorMessage(e, 'The image could not be saved.');")
                .contains("but the image was not: ${imgProblem}");
        assertThat(read("income.html")).contains("Income record saved, but the check image was not: ");
        assertThat(read("expense.html")).contains("Expense record saved, but the check image was not: ");
        assertThat(read("home.html"))
                .contains("const data = await CGP_api(`/api/admin/dashboard?clientId=${encodeURIComponent(clientId)}`);")
                .contains("CGP_showError(e, 'The dashboard could not be loaded.');");
        assertThat(read("viewFamily.html"))
                .contains("const list = await CGP_api(`/api/members?${params}`);")
                .contains("CGP_errorMessage(e, 'Failed to search members.')");
    }

    @Test
    @DisplayName("session.js makes the helper available on every page that loads it")
    void sessionJsInjectsTheHelper() throws IOException {
        String s = read("session.js");
        assertThat(s).contains("if (window.CGP_api || document.querySelector('script[src=\"/cgp-api.js\"]')) return;")
                     .contains("s.src = '/cgp-api.js';");
    }
}
