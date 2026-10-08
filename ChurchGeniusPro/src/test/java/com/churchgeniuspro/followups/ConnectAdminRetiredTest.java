package com.churchgeniuspro.followups;

import com.churchgeniuspro.controller.PublicEngagementController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connect Submissions was merged into Follow-Ups → "Connect With Us" (2026-10-01).
 * Pins the three things that make the merge safe: old links land on the new tab,
 * no menu still points at the retired page, and the tab keeps every action the
 * old page had (all via the unchanged /api/connect-admin endpoints).
 */
class ConnectAdminRetiredTest {

    private static String read(String p) throws Exception {
        return Files.readString(Path.of(p));
    }

    @Test
    @DisplayName("/connectAdmin redirects to the Follow-Ups Connect tab")
    void oldRouteRedirects() {
        PublicEngagementController c = new PublicEngagementController(null, null);
        assertThat(c.connectAdminPage()).isEqualTo("redirect:/followups?tab=connect");
    }

    @Test
    @DisplayName("no navigation entry or page still links to the retired page")
    void noNavEntry() throws Exception {
        assertThat(read("src/main/resources/static/shell.js")).doesNotContain("href: '/connectAdmin'");
        assertThat(Path.of("src/main/resources/static/connect-admin.html")).doesNotExist();
    }

    @Test
    @DisplayName("Follow-Ups Connect tab keeps every action of the old page")
    void tabHasEveryAction() throws Exception {
        String fu = read("src/main/resources/static/followups.html");
        assertThat(fu)
            .contains("fetch('/api/connect-admin')")                      // list
            .contains("'/api/connect-admin/'+mgCur.id+'/assign'")         // assign + unassign
            .contains("window.mgUnassign")
            .contains("'/api/connect-admin/'+mgCur.id+'/status'")         // status
            .contains("fetch('/api/connect-admin/'+id, { method:'DELETE' })") // delete
            .contains("linkedType=CONNECT")                               // follow-up history
            .contains("id=\"mgFuDue\"").contains("id=\"mgFuPriority\"")   // add follow-up w/ due + priority
            .contains("confirmationSent")
            .contains("['Gender',mgCur.gender]").contains("['Marital Status',mgCur.maritalStatus]")
            .contains("['Birth Date',mgCur.birthDate]")
            .contains("t === 'connect'");                                 // ?tab=connect deep link
    }
}
