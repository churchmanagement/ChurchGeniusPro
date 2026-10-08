package com.churchgeniuspro.membership;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public membership form had inputs that were never sent: the primary member's
 * address line 2 and nickname, each member's nickname, and the spouse's wedding
 * anniversary (its selects had no handler and were reset on every re-render). The
 * admin review screen can only show what reaches the server.
 */
@DisplayName("Membership form submits every field it collects")
class MembershipFormSubmitsAllFieldsTest {

    private static String page() throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/membershipForm.html"));
    }

    @Test
    @DisplayName("every display helper the form calls in its templates is defined (adding a member used to throw on formatPhone)")
    void helpersDefined() throws IOException {
        String s = page();
        assertThat(s).contains("function formatPhone(p) {").contains("function esc(s) {");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\+ (\\w+)\\(m\\.").matcher(s);
        while (m.find()) assertThat(s).as(m.group(1) + "() is used on a member but not defined").contains("function " + m.group(1) + "(");
    }

    @Test
    @DisplayName("primary member payload carries address line 2 and nickname")
    void primaryPayload() throws IOException {
        String s = page();
        String primary = s.substring(s.indexOf("const primary = {"), s.indexOf("const primaryMember = Object.assign"));
        assertThat(primary).contains("address2:", "getElementById('pAddress2')", "otherName:", "getElementById('pNickName')")
                .contains("address1:", "city:", "state:", "country:", "pinCode:", "phonePrivate:", "emailPrivate:", "addressPrivate:", "photo:");
    }

    @Test
    @DisplayName("each family member carries nickname and keeps the anniversary chosen in the Roles table")
    void memberPayload() throws IOException {
        String s = page();
        String member = s.substring(s.indexOf("const member = {"), s.indexOf("if (editingIndex !== null) {", s.indexOf("const member = {")));
        assertThat(member).contains("otherName:", "getElementById('mNickName')", "anniversaryMonth:", "anniversaryDay:", "anniversaryYear:");
        assertThat(s).contains("getElementById('mNickName') && (document.getElementById('mNickName').value = m.otherName || '')");
    }

    @Test
    @DisplayName("the spouse anniversary selects store their value on the member and keep it across re-renders")
    void anniversarySelects() throws IOException {
        String s = page();
        assertThat(s).contains("function onAnnivChange(index)")
                .contains("onchange=\"onAnnivChange(' + row.index + ')\"")
                .contains("m.anniversaryMonth = v('annivMonth'); m.anniversaryDay = v('annivDay'); m.anniversaryYear = v('annivYear');")
                .contains("selIf(i+1, fm.anniversaryMonth)").contains("selIf(i+1, fm.anniversaryDay)").contains("selIf(i, fm.anniversaryYear)");
    }
}
