package com.churchgeniuspro.worship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level guarantees for {@code memberWorship.html} (Assignments tab):
 *
 * <ul>
 *   <li>Automatic Assignment is UNCHANGED — its markup and its generation /
 *       rotation / skip-rebalance / pivot-rendering JavaScript are pinned to the
 *       SHA-256 of the pre-manual-work file (2026-10-08). Any edit inside those
 *       regions fails here and must be a deliberate, reviewed change.</li>
 *   <li>Manual Assignment never generates anything: its code region does not
 *       reference the auto-assign endpoint or the rotation machinery.</li>
 *   <li>Manual Assignment reads and writes only its own workflow (type=manual,
 *       scheduleType=manual) and has every management action the user asked for.</li>
 * </ul>
 */
@DisplayName("memberWorship.html — Manual Assignment page contract")
class ManualAssignmentPageTest {

    private static String page() throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/memberWorship.html"));
    }

    private static String region(String s, String from, String to) {
        int i = s.indexOf(from);
        assertThat(i).as("region start: " + from).isGreaterThanOrEqualTo(0);
        int j = s.indexOf(to, i);
        assertThat(j).as("region end: " + to).isGreaterThan(i);
        return s.substring(i, j);
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    // Automatic Assignment regions — hashes of the file as it was BEFORE the manual-assignment work.
    private static final String AUTO_MARKUP_START = "    <!-- Auto -->\n";
    private static final String AUTO_MARKUP_END   = "  </div><!-- /tab-assignments -->";
    private static final String AUTO_MARKUP_SHA   = "6d38f9ae48b29405";

    private static final String AUTO_JS1_START = "/* ════════════════════════════════════════════════\n   AUTO ASSIGNMENTS";
    private static final String AUTO_JS1_END   = "/* ── PDF/Print helpers ── */";
    private static final String AUTO_JS1_SHA   = "b0c7a182dec0e715";

    private static final String AUTO_JS2_START = "/* ── Skip-dates picker helpers ── */";
    private static final String AUTO_JS2_END   = "async function loadInlineSongs(";
    private static final String AUTO_JS2_SHA   = "be0b9b1966d01d35";

    @Test
    @DisplayName("Automatic Assignment markup and JavaScript are byte-identical to the pre-work file")
    void automaticAssignmentUnchanged() throws Exception {
        String s = page();
        assertThat(sha256(region(s, AUTO_MARKUP_START, AUTO_MARKUP_END))).startsWith(AUTO_MARKUP_SHA);
        assertThat(sha256(region(s, AUTO_JS1_START, AUTO_JS1_END))).startsWith(AUTO_JS1_SHA);
        assertThat(sha256(region(s, AUTO_JS2_START, AUTO_JS2_END))).startsWith(AUTO_JS2_SHA);
        // and the Automatic tab's own calls still send type 'auto' and read the unfiltered list
        String js1 = region(s, AUTO_JS1_START, AUTO_JS1_END);
        assertThat(js1).contains("fetch('/api/worship/assignments')")
                       .contains("assignmentType: row.assignmentType || 'auto'");
    }

    @Test
    @DisplayName("Manual Assignment never generates: no auto-assign call, no rotation code in its region")
    void manualNeverGenerates() throws Exception {
        String s = page();
        String manualJs = region(s, "/* ════════════════════════════════════════════════\n   MANUAL ASSIGNMENTS",
                                    "/* ════════════════════════════════════════════════\n   AUTO ASSIGNMENTS");
        String manualMarkup = region(s, "    <div id=\"tab-assign-manual\">", AUTO_MARKUP_START);
        for (String forbidden : new String[] {"/api/worship/auto-assign", "generateAutoAssignments",
                "rebalanceGroupRotation", "_groupRotationPools", "rotationOrder", "autoSaveGroup(", "toggleSkipRow"}) {
            assertThat(manualJs).as("manual JS must not reference " + forbidden).doesNotContain(forbidden);
            assertThat(manualMarkup).as("manual markup must not reference " + forbidden).doesNotContain(forbidden);
        }
        // the only write paths: the empty-dates helper, the manual upsert, the skip flag, delete
        assertThat(manualJs).contains("/api/worship/assignments/manual-dates")
                            .contains("assignmentType: 'manual'")
                            .contains("/skip'")
                            .contains("method: 'DELETE'");
    }

    @Test
    @DisplayName("Manual Assignment reads only its own rows (type=manual, skipped included) and its own song lists")
    void manualReadsItsOwnWorkflow() throws Exception {
        String s = page();
        String manualJs = region(s, "/* ════════════════════════════════════════════════\n   MANUAL ASSIGNMENTS",
                                    "/* ════════════════════════════════════════════════\n   AUTO ASSIGNMENTS");
        assertThat(manualJs).contains("type=manual&includeSkipped=true");
        String helpers = region(s, "   WORKFLOW HELPERS (shared by the Automatic and Manual tabs)",
                                   "/* ════════════════════════════════════════════════\n   MANUAL ASSIGNMENTS");
        assertThat(helpers).contains("return sched === 'manual' ? '&scheduleType=manual' : '';");
        assertThat(helpers).contains("return sched === 'manual' ? { scheduleType: 'manual' } : {};");
        assertThat(helpers).contains("return sched === 'manual' ? manualRows : autoPreview;");
        // every shared helper the Automatic tab calls without a workflow argument defaults to 'auto'
        for (String fn : new String[] {"function openCellAdd(groupId, dateStr, iid, sched = 'auto')",
                "async function onCellDrop(event, groupId, dateStr, iid, sched = 'auto')",
                "function removeCellMember(groupId, dateStr, iid, memberId, sched = 'auto')",
                "function onMemberChipDragStart(event, groupId, dateStr, iid, memberId, sched = 'auto')",
                "async function openWeekSongMgr(groupId, dateStr, sched = 'auto')",
                "async function loadInlineSongs(groupId, dateStr, sched = 'auto')",
                "function buildGroupTableHtml(groupId, sched = 'auto')",
                "function buildGroupTableData(groupId, sched = 'auto')",
                "function printGroupTable(groupId, sched = 'auto')",
                "function downloadGroupPdf(groupId, sched = 'auto')",
                "function printAllGroupTables(sched = 'auto')",
                "function downloadAllGroupsPdf(sched = 'auto')"}) {
            assertThat(s).as(fn).contains(fn);
        }
        // a chip can never be dragged from one workflow's table into the other's
        assertThat(s).contains("if ((src.sched || 'auto') !== sched) return;");
    }

    @Test
    @DisplayName("Manual Assignment offers every management action: add dates, skip/unskip, songs, print, PDF, delete")
    void manualHasAllManagementFeatures() throws Exception {
        String s = page();
        String manualJs = region(s, "/* ════════════════════════════════════════════════\n   MANUAL ASSIGNMENTS",
                                    "/* ════════════════════════════════════════════════\n   AUTO ASSIGNMENTS");
        String manualMarkup = region(s, "    <div id=\"tab-assign-manual\">", AUTO_MARKUP_START);
        assertThat(manualMarkup).contains("id=\"manualGroup\"").contains("id=\"manualDow\"")
                .contains("id=\"manualStartDate\"").contains("id=\"manualWeeks\"")
                .contains("onclick=\"addManualDates()\"")
                .contains("Start date only (1 date)");
        assertThat(manualJs)
                .contains("toggleManualSkip(")                                  // skip / unskip
                .contains("members are kept")                                   // skip keeps members
                .contains("openSongMgr(${groupId}")                             // manage songs (library)
                .contains("openWeekSongMgr(${groupId},'${dateStr}','manual')")  // songs per date
                .contains("printGroupTable(${groupId},'manual')")
                .contains("downloadGroupPdf(${groupId},'manual')")
                .contains("printAllGroupTables('manual')")
                .contains("downloadAllGroupsPdf('manual')")
                .contains("deleteManualRow(")
                .contains("deleteManualGroup(")
                .contains("openCellAdd(${groupId},'${dateStr}',${iid},'manual')")
                .contains("onCellDrop(event,${groupId},'${dateStr}',${iid},'manual')")
                .contains("renderMemberRoster(groupId, rows, instruments)");     // roster drag
        // the Manual tab's "Skip" never clears members on the client either
        assertThat(manualJs).doesNotContain("row.slots    = [];").doesNotContain("row.slots = [];");
    }
}
