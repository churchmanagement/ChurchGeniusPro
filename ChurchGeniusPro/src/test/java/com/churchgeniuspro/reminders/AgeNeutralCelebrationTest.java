package com.churchgeniuspro.reminders;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.service.ReminderSchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Birthday and anniversary messages are age-neutral (user decision 2026-11-04):
 * "Sam's Birthday", "John & Mary's Wedding Anniversary" — never an age, an ordinal
 * ("18th Birthday") or a number of years, even when the birth / wedding year is on file.
 * Applies to the reminder EMAILS only; AI search answers and other searches still show
 * the stored year (user decision 2026-11-04).
 */
@DisplayName("Birthday and anniversary reminders never show an age or ordinal")
class AgeNeutralCelebrationTest {

    /** "18th", "1st", "turn 18", "18 years", a 4-digit year. */
    static final Pattern AGE = Pattern.compile("\\b\\d+(st|nd|rd|th)\\b|\\bturns?\\b|\\b\\d+\\s+years?\\b|\\b(19|20)\\d{2}\\b",
            Pattern.CASE_INSENSITIVE);

    static String call(String method, Class<?>[] types, Object... args) throws Exception {
        ReminderSchedulerService svc = org.mockito.Mockito.mock(ReminderSchedulerService.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        Method m = ReminderSchedulerService.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return (String) m.invoke(svc, args);
    }

    static String text(String html) { return html.replaceAll("<[^>]+>", " "); }

    static FamilyMember sam() {
        FamilyMember m = new FamilyMember();
        m.setFirstName("Sam"); m.setLastName("Lee");
        m.setBirthdayYear(2008); m.setBirthdayMonth(11); m.setBirthdayDay(4);
        return m;
    }

    @Test @DisplayName("birthday emails to the person and to the church: no age even with a birth year on file")
    void birthdays() throws Exception {
        String own = text(call("buildBirthdayCelebrantBody", new Class<?>[]{FamilyMember.class}, sam()));
        String all = text(call("buildBirthdayAudienceBody", new Class<?>[]{FamilyMember.class}, sam()));
        assertThat(own).contains("Happy Birthday, Sam").doesNotContainPattern(AGE);
        assertThat(all).contains("Lee").contains("birthday!").doesNotContainPattern(AGE);
    }

    @Test @DisplayName("anniversary emails: \"wedding anniversary\" with no number of years, wedding year on file or not")
    void anniversaries() throws Exception {
        Class<?>[] t = {String.class, Integer.class};
        for (Integer year : new Integer[]{1999, null, 5}) {
            String own = text(call("buildAnniversaryCelebrantBody", t, "John &amp; Mary", year));
            String all = text(call("buildAnniversaryAudienceBody", t, "John &amp; Mary", year));
            assertThat(own).contains("Happy Anniversary, John").contains("wedding anniversary").doesNotContainPattern(AGE);
            assertThat(all).contains("John &amp; Mary").contains("wedding anniversary!").doesNotContainPattern(AGE);
        }
    }

    @Test @DisplayName("no ordinal or age wording is left in the reminder emails (searches are not affected)")
    void sources() throws Exception {
        String sched = Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/ReminderSchedulerService.java"));
        assertThat(sched).doesNotContain("ordinal(age)").doesNotContain("ordinal(years)").doesNotContain("yearsMarried(")
                .doesNotContain("today you turn");
    }
}
