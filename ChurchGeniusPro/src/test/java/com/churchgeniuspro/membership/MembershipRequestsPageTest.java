package com.churchgeniuspro.membership;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Membership Requests page. Opening a request showed "❌ Network error." because the
 * detail view called formatPhone(), which neither the page nor shell.js/session.js
 * defines — the ReferenceError landed in openDetail's catch-all.
 */
@DisplayName("Membership Requests page")
class MembershipRequestsPageTest {

    private static String page() throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/membershipRequests.html"));
    }

    @Test
    @DisplayName("every display helper the detail view calls is defined on the page")
    void detailHelpersAreDefined() throws IOException {
        String s = page();
        Matcher m = Pattern.compile("\\$\\{(\\w+)\\(").matcher(s);
        while (m.find()) {
            String fn = m.group(1);
            assertThat(s).as(fn + "() is called in a template but must be defined on the page")
                    .contains("function " + fn + "(");
        }
        assertThat(s).contains("function formatPhone(p) {");
    }

    @Test
    @DisplayName("the review panel shows every submitted field: full address, family phone/email, and per member gender, DOB, anniversary, nickname, contact, own address, privacy flags, photo")
    void reviewPanelShowsEverything() throws IOException {
        String s = page();
        String detail = s.substring(s.indexOf("function renderDetail(d) {"), s.indexOf("function closeDetail() {"));
        assertThat(detail).contains("Family Address", "Family Phone", "Family Email", "Submitted", "Declaration", "Request Type")
                .contains("d.familyAddress1", "d.familyAddress2", "d.familyCity", "d.familyState", "d.familyCountry", "d.familyPinCode", "d.familyPhone", "d.familyEmail")
                .contains("'Gender'", "'Date of Birth'", "'Wedding Anniversary'", "'Nickname / Other Name'", "'Phone'", "'Email'", "Address")
                .contains("m.birthdayMonth", "m.anniversaryMonth", "m.otherName", "m.phonePrivate", "m.emailPrivate", "m.addressPrivate",
                          "m.sameAsFamilyAddress", "m.photoData", "m.comments", "m.middleName")
                .contains("Same as family address");
        // Approve / approve-login / back actions are untouched
        assertThat(detail).contains("approveMembership(${d.id}, this)", "applyMembership(${d.id}, this)", "approveSignup(${d.id}, this)", "closeDetail()");
    }

    @Test
    @DisplayName("V15: the table has #, Family Name, Submitted Form, Type, Submitted, Status — the Declaration column is gone (acceptance is enforced at submission and shown in the detail)")
    void tableColumns() throws IOException {
        String s = page();
        String head = s.substring(s.indexOf("<table class=\"req-tbl\">"), s.indexOf("</thead>", s.indexOf("<table class=\"req-tbl\">")));
        assertThat(head).contains("<th>#</th>", "<th>Family Name</th>", "<th>Submitted Form</th>", "<th>Type</th>", "<th>Submitted</th>", "<th>Status</th>")
                        .doesNotContain("<th>Declaration</th>", "<th>City</th>", "<th>Members</th>");
        assertThat(s).doesNotContain("colspan=\"5\"").contains("colspan=\"6\"");
        // the link says what it does and carries the live count from the list payload (no per-row fetch)
        assertThat(s).contains("Review Submitted Form (${n} member${n === 1 ? '' : 's'})").doesNotContain("loadMemberCount");
        // declaration still shown in the detail panel
        assertThat(s).contains("${field('Declaration', d.declarationAccepted ? '✅ Accepted' : dash)}");
    }

    @Test
    @DisplayName("V15 actions: Approve (status only), Approve and Update (apply), status moves, delete with the agreed confirmation, context-sensitive messages")
    void workflowActions() throws IOException {
        String s = page();
        assertThat(s).contains("/api/membership-requests/${id}/approve", "/api/membership-requests/${id}/apply",
                               "/api/membership-requests/${id}/status", "fetch(`/api/membership-requests/${id}`, { method: 'DELETE' })");
        assertThat(s).doesNotContain("Move this family to the Members list");
        assertThat(s).contains("This will update the existing family/member record with the requested changes and update the membership renewal date. Do you want to continue?")
                     .contains("No profile changes were submitted. This action will update the membership renewal date. Do you want to continue?")
                     .contains("Are you sure you want to delete this membership request? This action will remove the request from the Membership Requests page but will not delete the associated family or member record.")
                     .contains("Changing the status does NOT undo the changes already applied");
        assertThat(s).contains("✅ Approve and Update", "✅ Approve and Add as Member", "CGP_PERMS['admin.membership.delete']");
        // misleading old messages are gone
        assertThat(s).doesNotContain("An approval email has been sent", "Member renewal date updated.");
        // the diff view
        assertThat(s).contains("function renderChanges(d)", "Renewal without profile changes", "Profile photo will be replaced", "chg-old", "chg-new");
        // a photo pair is drawn only when the server reports a usable new photo AND it is present to show; unsupported uploads are explained
        assertThat(s).contains("const photo = m.photoChanged && sub.photoData ?").contains("photoUnsupported").contains("cannot be displayed or saved");
    }
}
