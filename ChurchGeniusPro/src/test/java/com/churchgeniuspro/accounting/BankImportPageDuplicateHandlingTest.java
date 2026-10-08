package com.churchgeniuspro.accounting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Bank Import review page must route every save through the server-side
 * duplicate check, and interpret a 409 from it correctly.
 *
 * <p>Financial audit H8. Before this, the only duplicate check was a separate
 * pre-save ping to {@code /api/bank-import/check-duplicate}; if that call itself
 * failed (network error), the page fell back to saving anyway with no further
 * check — a real duplicate could reach the ledger. This reads the page and pins
 * the shape that closes that hole: every save carries the row's server-computed
 * {@code importRef}, a 409 from the save itself is interpreted exactly like a
 * 409 from the pre-check (never swallowed into a generic error), and "Save as
 * New" is offered only for a soft (overridable) duplicate, never a hard one.
 */
@DisplayName("Bank Import page — duplicate handling (H8)")
class BankImportPageDuplicateHandlingTest {

    private static String page() throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/bank-import.html"));
    }

    @Test
    @DisplayName("the save payload for both Income and Expense carries the row's importRef")
    void payloadCarriesImportRef() throws IOException {
        String p = page();
        int fn = p.indexOf("function buildSavePayload");
        assertThat(fn).as("buildSavePayload").isPositive();
        int end = p.indexOf("\n  }\n", fn);
        String body = p.substring(fn, end);
        assertThat(body).contains("function buildSavePayload(t)");
        // Both branches (income and expense) must forward it.
        assertThat(body.split("importRef:importRef", -1)).hasSizeGreaterThanOrEqualTo(3); // 2 occurrences + tail
    }

    @Test
    @DisplayName("buildSavePayload is called with the row being saved, not with no arguments")
    void callerPassesTheRow() throws IOException {
        String p = page();
        assertThat(p).contains("buildSavePayload(t)");
        assertThat(p).doesNotContain("buildSavePayload()");
    }

    @Test
    @DisplayName("doSave sends a force flag, and a 409 duplicate response is routed to showDup — never swallowed")
    void doSaveHandles409() throws IOException {
        String p = page();
        int fn = p.indexOf("function doSave(spec, overwriteId, force)");
        assertThat(fn).as("doSave(spec, overwriteId, force)").isPositive();
        int end = p.indexOf("\n  }\n", fn);
        String body = p.substring(fn, end);
        assertThat(body).contains("force: !!force");
        assertThat(body).contains("res.status===409 && res.j.duplicate");
        assertThat(body).contains("showDup(spec, res.j.duplicate)");
    }

    @Test
    @DisplayName("the pre-check network-failure fallback no longer silently saves past a real duplicate")
    void precheckFailureFallbackIsSafe() throws IOException {
        String p = page();
        // The fallback still calls doSave (unchanged UX on a flaky network), but doSave
        // itself now enforces the duplicate check server-side — see doSaveHandles409.
        int mSave = p.indexOf("$('mSave').addEventListener");
        assertThat(mSave).isPositive();
        int end = p.indexOf("\n  });\n", mSave);
        String body = p.substring(mSave, end);
        assertThat(body).contains(".catch(function(){");
        assertThat(body).contains("doSave(spec, null);");
    }

    @Test
    @DisplayName("showDup offers \"Save as New\" only for a soft duplicate, never for a hard one")
    void saveAsNewHiddenForHardDuplicate() throws IOException {
        String p = page();
        int fn = p.indexOf("function showDup(spec, dup)");
        assertThat(fn).as("showDup").isPositive();
        int end = p.indexOf("\n  }\n", fn);
        String body = p.substring(fn, end);
        assertThat(body).contains("var isHard = !!dup.hard;");
        assertThat(body).contains("(isHard ? '' : '<button class=\"btn sm\" id=\"mDupNew\">Save as New</button>')");
    }

    @Test
    @DisplayName("\"Save as New\" resends with force:true, so the server's soft check doesn't 409 again")
    void saveAsNewForcesTheRetry() throws IOException {
        String p = page();
        int fn = p.indexOf("function showDup(spec, dup)");
        int end = p.indexOf("\n  }\n", fn);
        String body = p.substring(fn, end);
        assertThat(body).contains("doSave(spec, null, true);");
    }
}
