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
        assertThat(detail).contains("approveMembership(${d.id}, this)", "approveSignup(${d.id}, this)", "closeDetail()");
    }

    @Test
    @DisplayName("Pending Applications table has #, Family Name, Members, Submitted, Declaration — no City")
    void tableColumns() throws IOException {
        String s = page();
        String head = s.substring(s.indexOf("<table class=\"req-tbl\">"), s.indexOf("</thead>", s.indexOf("<table class=\"req-tbl\">")));
        assertThat(head).contains("<th>#</th>", "<th>Family Name</th>", "<th>Members</th>", "<th>Submitted</th>", "<th>Declaration</th>")
                        .doesNotContain("<th>City</th>");
        assertThat(s).doesNotContain("colspan=\"6\"");
    }
}
