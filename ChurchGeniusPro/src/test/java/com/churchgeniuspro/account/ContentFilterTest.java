package com.churchgeniuspro.account;

import com.churchgeniuspro.util.ContentFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("D13 — content filter for Members Chat and public prayer requests")
class ContentFilterTest {

    @Test @DisplayName("refuses listed words, also disguised")
    void blocks() {
        for (String t : new String[]{"what the fuck", "FUCK!", "you are a b1tch", "sh1t happens", "f.u.c.k off",
                "s h i t", "fuuuuck", "@sshole move", "kys", "Shit.", "total bullshit", "porn link"}) {
            assertThat(ContentFilter.isBlocked(t)).as(t).isTrue();
        }
    }

    @Test @DisplayName("lets ordinary, Scripture and medical text through")
    void allows() {
        for (String t : new String[]{"Please pray for my mother's surgery on Tuesday", "Scunthorpe", "assistant",
                "class of 2026", "Dickens", "cocktail of medicines", "the great whore of Revelation 17",
                "then are ye bastards, and not sons (Hebrews 12:8)", "it is hard for thee to kick against the pricks",
                "magna cum laude", "a chink in the armour", "Dr. Hooker", "Van Dyke", "shiitake soup",
                "pray for the 3 kids", "room 101 at 5pm", "hello!", "", null, "Grace & peace — Jn 3:16"}) {
            assertThat(ContentFilter.isBlocked(t)).as(String.valueOf(t)).isFalse();
        }
        assertThat(ContentFilter.anyBlocked("fine", null, "also fine")).isFalse();
        assertThat(ContentFilter.anyBlocked("fine", "shit")).isTrue();
    }

    @Test @DisplayName("wired into chat send + reply and both public prayer paths; refusal explains why")
    void wiring() throws Exception {
        String chat = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/MembershipFormController.java"));
        assertThat(chat.split("ContentFilter.isBlocked\\(msgBody\\)", -1)).hasSize(3);
        assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/PrayerRequestController.java")))
                .contains("ContentFilter.anyBlocked(title, str(body, \"description\"), str(body, \"requesterName\"))");
        assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/PublicEngagementService.java")))
                .contains("ContentFilter.anyBlocked(str(b, \"requestText\"), first, last)");
        assertThat(ContentFilter.refusal()).containsEntry("code", "CONTENT_BLOCKED").containsKey("error");
        assertThat(ContentFilter.MESSAGE).contains("reword");
    }
}
