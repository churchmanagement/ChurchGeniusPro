package com.churchgeniuspro.kids;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.util.KidsPortalEligibility;
import com.churchgeniuspro.util.KidsPortalEligibility.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Kids Portal 13+ rule (2026-11-04): who may hold a member login, by the church's record. */
@DisplayName("Kids Portal 13+ — eligibility rule")
class KidsPortalEligibilityTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    static Status child(Integer y, Integer m, Integer d) { return KidsPortalEligibility.check("Child", y, m, d, TODAY); }

    @Test @DisplayName("child role: missing, partial, impossible or future date of birth = MISSING_DOB")
    void missingOrBrokenDates() {
        assertThat(child(null, null, null)).isEqualTo(Status.MISSING_DOB);
        assertThat(child(2010, 5, null)).isEqualTo(Status.MISSING_DOB);
        assertThat(child(null, 5, 1)).isEqualTo(Status.MISSING_DOB);
        assertThat(child(2010, 2, 30)).isEqualTo(Status.MISSING_DOB);     // not a real date
        assertThat(child(2010, 13, 1)).isEqualTo(Status.MISSING_DOB);
        assertThat(child(1850, 1, 1)).isEqualTo(Status.MISSING_DOB);
        assertThat(child(2027, 1, 1)).isEqualTo(Status.MISSING_DOB);      // future
    }

    @Test @DisplayName("13th birthday boundary: the day before is refused, the birthday itself is allowed")
    void birthdayBoundary() {
        assertThat(child(2013, 11, 5)).isEqualTo(Status.UNDER_13);   // turns 13 tomorrow
        assertThat(child(2013, 11, 4)).isEqualTo(Status.OK);         // turns 13 today
        assertThat(child(2013, 11, 3)).isEqualTo(Status.OK);
        assertThat(child(2016, 1, 1)).isEqualTo(Status.UNDER_13);
        assertThat(child(2008, 6, 1)).isEqualTo(Status.OK);
    }

    @Test @DisplayName("29 February birthday reaches 13 on 1 March in a non-leap year")
    void leapDay() {
        LocalDate dob = LocalDate.of(2012, 2, 29);                    // 13th birthday falls in 2025 (not leap)
        assertThat(KidsPortalEligibility.thirteenthBirthday(dob)).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(KidsPortalEligibility.check("Child", 2012, 2, 29, LocalDate.of(2025, 2, 28))).isEqualTo(Status.UNDER_13);
        assertThat(KidsPortalEligibility.check("Child", 2012, 2, 29, LocalDate.of(2025, 3, 1))).isEqualTo(Status.OK);
        // a leap 13th birthday stays on 29 Feb
        assertThat(KidsPortalEligibility.thirteenthBirthday(LocalDate.of(2011, 2, 28))).isEqualTo(LocalDate.of(2024, 2, 28));
    }

    @Test @DisplayName("every child spelling counts; D1: under 13 on any other role is refused, a blank date on an adult role is not")
    void rolesAndD1() {
        for (String r : new String[]{"Child", "son", "Daughter", "Kid", "kids", "minor"}) {
            assertThat(KidsPortalEligibility.isChildRole(r)).as(r).isTrue();
            assertThat(KidsPortalEligibility.check(r, null, null, null, TODAY)).as(r).isEqualTo(Status.MISSING_DOB);
        }
        assertThat(KidsPortalEligibility.isChildRole("")).isFalse();
        assertThat(KidsPortalEligibility.isChildRole(null)).isFalse();
        assertThat(KidsPortalEligibility.check("Adult", 2017, 1, 1, TODAY)).isEqualTo(Status.UNDER_13);
        assertThat(KidsPortalEligibility.check("Head of Household", 2017, 1, 1, TODAY)).isEqualTo(Status.UNDER_13);
        assertThat(KidsPortalEligibility.check("Adult", null, null, null, TODAY)).isEqualTo(Status.OK);
        assertThat(KidsPortalEligibility.check("Spouse", 1980, 2, 30, TODAY)).isEqualTo(Status.OK);
        assertThat(KidsPortalEligibility.check("Adult", 1990, 4, 4, TODAY)).isEqualTo(Status.OK);
    }

    @Test @DisplayName("messages and login-page reasons match the refusal, and nothing is said when allowed")
    void messagesAndReasons() {
        FamilyMember kid = new FamilyMember(); kid.setRole("Child");
        assertThat(KidsPortalEligibility.message(kid)).isEqualTo(KidsPortalEligibility.MISSING_DOB_MESSAGE);
        assertThat(KidsPortalEligibility.reason(kid)).isEqualTo("dob");
        LocalDate ten = com.churchgeniuspro.util.AppClock.today().minusYears(10);
        kid.setBirthdayYear(ten.getYear()); kid.setBirthdayMonth(ten.getMonthValue()); kid.setBirthdayDay(ten.getDayOfMonth());
        assertThat(KidsPortalEligibility.message(kid)).isEqualTo(KidsPortalEligibility.UNDER_13_KIDS_MESSAGE);
        assertThat(KidsPortalEligibility.reason(kid)).isEqualTo("age");
        kid.setRole("Adult");
        assertThat(KidsPortalEligibility.message(kid)).isEqualTo(KidsPortalEligibility.UNDER_13_MEMBER_MESSAGE);
        assertThat(KidsPortalEligibility.reason(kid)).isEqualTo("age-member");
        kid.setBirthdayYear(ten.getYear() - 5);
        assertThat(KidsPortalEligibility.message(kid)).isNull();
        assertThat(KidsPortalEligibility.reason(kid)).isNull();
        assertThat(KidsPortalEligibility.message((FamilyMember) null)).isNull();
        // messages never contain the stored date
        assertThat(KidsPortalEligibility.UNDER_13_KIDS_MESSAGE).doesNotContainPattern("\\d{4}");
    }

    @Test @DisplayName("the check uses the church's date (America/Chicago), not the server's")
    void churchDate() {
        // 23:30 in Chicago on Nov 4 is already Nov 5 in UTC; the rule must still say Nov 4.
        java.time.ZonedDateTime evening = java.time.ZonedDateTime.of(2026, 11, 4, 23, 30, 0, 0, com.churchgeniuspro.util.AppClock.ZONE);
        LocalDate churchDay = evening.toLocalDate();
        LocalDate utcDay = evening.withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
        assertThat(utcDay).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(KidsPortalEligibility.check("Child", 2013, 11, 5, churchDay)).isEqualTo(Status.UNDER_13);
        assertThat(KidsPortalEligibility.check("Child", 2013, 11, 5, utcDay)).isEqualTo(Status.OK);
    }
}
