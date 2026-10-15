package com.churchgeniuspro.mobile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Mobile Phase 2: the store-facing controls must stay on the pages. */
class Phase2PageRatchetTest {

    private static String read(String name) throws Exception {
        return Files.readString(Path.of("src/main/resources/static").resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("member portal: delete-account flow, report/block controls, blocked list, support contact")
    void memberPortal() throws Exception {
        String h = read("memberHome.html");
        assertThat(h).contains("id=\"deleteAccountModal\"").contains("/api/member/account/delete")
                     .contains("Your church keeps its membership and giving records")
                     .contains("id=\"reportMsgModal\"").contains("/api/member/messages/' + Number(reportMsgTarget.id) + '/report")
                     .contains("class=\"msg-act-report\"").contains("class=\"msg-act-block\"")
                     .contains("id=\"blockedMembersList\"").contains("/api/member/blocks")
                     .contains("mailto:support@churchgeniuspro.com").contains("href=\"/account-deletion\"")
                     .contains("window._cgpIsChildSession = isChildRole");
    }

    @Test
    @DisplayName("Follow-Ups has the Reported Content tab wired to the staff API and the bell deep link")
    void followUps() throws Exception {
        String h = read("followups.html");
        assertThat(h).contains("id=\"fuTab_reports\"").contains("id=\"fuPane_reports\"")
                     .contains("/api/content-reports?status=All").contains("/api/content-reports/'+id+'/resolve")
                     .contains("t === 'reports'");
    }

    @Test
    @DisplayName("the public account-deletion page exists and states the retention rule")
    void publicPage() throws Exception {
        String h = read("accountDeletion.html");
        assertThat(h).contains("Delete your ChurchGeniusPro account").contains("membership record, giving history and year-end statements")
                     .contains("support@churchgeniuspro.com");
        assertThat(read("login.html")).contains("urlParams.get('deleted') === '1'");
    }
}
