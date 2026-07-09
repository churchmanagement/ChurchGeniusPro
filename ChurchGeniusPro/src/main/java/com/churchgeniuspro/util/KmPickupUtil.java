package com.churchgeniuspro.util;

import com.churchgeniuspro.hibernate.KmCheckin;
import com.churchgeniuspro.hibernate.KmChildSetup;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Shared pickup-deadline math for the Kids Ministry overdue-alert feature, used by
 * both the dashboard controller and the background alert scheduler so the two can
 * never disagree on when a child is overdue.
 */
public final class KmPickupUtil {

    private KmPickupUtil() {}

    /**
     * Effective pickup deadline for a check-in: the per-child override if set,
     * otherwise today's configured expiration time (falling back to the default
     * pickup time). Returns {@code null} when no deadline is configured.
     */
    public static LocalDateTime effectiveDeadline(KmCheckin ci, KmChildSetup setup) {
        if (ci == null) return null;
        if (ci.getPickupDeadline() != null) return ci.getPickupDeadline();
        if (setup == null) return null;
        String t = (setup.getPickupExpirationTime() != null && !setup.getPickupExpirationTime().isBlank())
                ? setup.getPickupExpirationTime() : setup.getDefaultPickupTime();
        if (t == null || t.isBlank() || !t.matches("\\d{1,2}:\\d{2}")) return null;
        LocalDate day = ci.getCheckinTime() != null ? ci.getCheckinTime().toLocalDate() : LocalDate.now();
        return day.atTime(LocalTime.parse(padTime(t)));
    }

    /** LocalTime.parse needs HH:mm — pad a single-digit hour ("9:30" → "09:30"). */
    public static String padTime(String t) {
        String[] p = t.split(":");
        return String.format("%02d:%s", Integer.parseInt(p[0]), p[1]);
    }
}
