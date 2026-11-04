package com.churchgeniuspro.util;

import com.churchgeniuspro.hibernate.FamilyMember;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;

/**
 * Who may hold a member login, by age (Kids Portal 13+ rule, approved 2026-11-04).
 *
 * <p>The date of birth is ONLY the one in the church's family record (month, day and
 * year); nobody types or picks an age. Age is worked out on the church's calendar date
 * ({@link AppClock}, America/Chicago), so the answer does not flip with the server's
 * time zone in the evening.
 *
 * <ul>
 *   <li>Kids Portal login (family role reads as Child — see {@link FamilyRoles#canonical}):
 *       needs a full, real, not-future date of birth AND age 13 or older.
 *       Anything else = {@link Status#MISSING_DOB} or {@link Status#UNDER_13}.</li>
 *   <li>Any other member login (decision D1): refused when the record's date of birth
 *       shows the person is under 13. A blank or broken date on an adult role is NOT
 *       refused — otherwise every adult with no birth year would lose access.</li>
 * </ul>
 *
 * <p>A 29 February birthday reaches 13 on 1 March in a non-leap year.
 */
public final class KidsPortalEligibility {

    public static final int MIN_AGE = 13;

    public enum Status { OK, MISSING_DOB, UNDER_13 }

    /** Error code sent to the client with a refusal (also the login page's reason). */
    public static final String CODE = "KIDS_PORTAL_AGE";

    public static final String MISSING_DOB_MESSAGE =
            "Kids Portal needs your child's full date of birth in the church's records. "
          + "Please contact your church office (Admin) to update it.";
    public static final String UNDER_13_KIDS_MESSAGE =
            "Kids Portal is available for children aged 13 and older. "
          + "If the date of birth in the church's records is wrong, please contact your church office.";
    public static final String UNDER_13_MEMBER_MESSAGE =
            "Online accounts are available only to people aged 13 and older. "
          + "If the date of birth in the church's records is wrong, please contact your church office.";

    private KidsPortalEligibility() {}

    /** True when the stored family role makes this a Kids Portal login. */
    public static boolean isChildRole(String role) {
        return role != null && !role.isBlank() && FamilyRoles.CHILD.equals(FamilyRoles.canonical(role));
    }

    /**
     * The record's date of birth, or null when any part is missing, the parts are not
     * a real calendar date (30 February), or the date is after {@code today}.
     */
    public static LocalDate dateOfBirth(Integer year, Integer month, Integer day, LocalDate today) {
        if (year == null || month == null || day == null) return null;
        if (year < 1900 || year > 2100) return null;
        try {
            LocalDate dob = LocalDate.of(year, month, day);
            return dob.isAfter(today) ? null : dob;
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** The day the person turns {@value #MIN_AGE} (29 Feb → 1 Mar in a non-leap year). */
    public static LocalDate thirteenthBirthday(LocalDate dob) {
        int y = dob.getYear() + MIN_AGE;
        if (dob.getMonth() == Month.FEBRUARY && dob.getDayOfMonth() == 29 && !java.time.Year.isLeap(y)) {
            return LocalDate.of(y, 3, 1);
        }
        return dob.withYear(y);
    }

    public static boolean isUnder13(LocalDate dob, LocalDate today) {
        return today.isBefore(thirteenthBirthday(dob));
    }

    public static Status check(String role, Integer year, Integer month, Integer day, LocalDate today) {
        LocalDate dob = dateOfBirth(year, month, day, today);
        if (isChildRole(role)) {
            if (dob == null) return Status.MISSING_DOB;
            return isUnder13(dob, today) ? Status.UNDER_13 : Status.OK;
        }
        return dob != null && isUnder13(dob, today) ? Status.UNDER_13 : Status.OK;
    }

    public static Status check(FamilyMember fm, LocalDate today) {
        if (fm == null) return Status.OK;
        return check(fm.getRole(), fm.getBirthdayYear(), fm.getBirthdayMonth(), fm.getBirthdayDay(), today);
    }

    /** Checked against today's church date. */
    public static Status check(FamilyMember fm) {
        return check(fm, AppClock.today());
    }

    /** The user-facing reason for a refusal, or null when allowed. */
    public static String message(FamilyMember fm) {
        return fm == null ? null : message(check(fm), isChildRole(fm.getRole()));
    }

    public static String message(Status status, boolean childRole) {
        return switch (status) {
            case OK -> null;
            case MISSING_DOB -> MISSING_DOB_MESSAGE;
            case UNDER_13 -> childRole ? UNDER_13_KIDS_MESSAGE : UNDER_13_MEMBER_MESSAGE;
        };
    }

    /** Login-page reason for a redirect after a refusal: {@code dob}, {@code age} or {@code age-member}. */
    public static String reason(FamilyMember fm) {
        Status s = check(fm);
        if (s == Status.MISSING_DOB) return "dob";
        if (s == Status.UNDER_13) return isChildRole(fm.getRole()) ? "age" : "age-member";
        return null;
    }
}
