package com.churchgeniuspro.ai.intel;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * AI Phase 2 — the deterministic engagement rules. Pure function of the member's counts, so the
 * same facts always give the same signal and the same reasons. No model, no amounts, no names.
 *
 * <p>Signals (one per member):
 * <ul>
 *   <li>{@code NEW} — first attendance within {@code newDays} days (whatever else is true).</li>
 *   <li>{@code QUIET} — nothing on record in the last 365 days: no attendance, no gift, no group, not serving.</li>
 *   <li>{@code AT_RISK} — was a regular (≥ {@code regularMinDays} days in the 90 before the last 30) and has
 *       not attended in the last {@code atRiskGapDays}; or gave in the prior year and not at all this year
 *       while attendance is also thin.</li>
 *   <li>{@code WATCH} — attendance in the last 30 days dropped to zero after some in the last 90, or giving
 *       stopped (gifts in the year, none in 90 days), or the only tie is one of groups / serving / gifts.</li>
 *   <li>{@code ENGAGED} — everything else with recent activity.</li>
 * </ul>
 */
public final class EngagementSignalRules {

    private EngagementSignalRules() {}

    /** Thresholds; defaults mirror application.properties {@code ai.intel.*}. */
    public record Thresholds(int newDays, int regularMinDays, int atRiskGapDays, int watchGapDays) {
        public static final Thresholds DEFAULT = new Thresholds(90, 3, 42, 30);
    }

    /** Facts about one member on the snapshot day. */
    public record Facts(int attendance30, int attendance90, int attendance365, LocalDate firstAttended, LocalDate lastAttended,
                        int gifts90, int gifts365, int giftsPrior365, LocalDate lastGift, int groups, boolean serving) {}

    public record Outcome(String signal, List<String> reasons) {}

    public static Outcome evaluate(Facts f, LocalDate today, Thresholds t) {
        List<String> why = new ArrayList<>();
        boolean anyYear = f.attendance365() > 0 || f.gifts365() > 0 || f.groups() > 0 || f.serving();

        if (f.firstAttended() != null && !f.firstAttended().isAfter(today)
                && ChronoUnit.DAYS.between(f.firstAttended(), today) < t.newDays()) {
            why.add("First attended " + f.firstAttended() + " (" + ChronoUnit.DAYS.between(f.firstAttended(), today) + " days ago).");
            why.add(f.attendance90() + " attendance day" + (f.attendance90() == 1 ? "" : "s") + " in the last 90 days.");
            return new Outcome("NEW", why);
        }

        if (!anyYear) {
            why.add("No attendance, gift, group or serving in the last 365 days.");
            if (f.lastAttended() != null) why.add("Last attended " + f.lastAttended() + ".");
            if (f.lastGift() != null) why.add("Last gift " + f.lastGift() + ".");
            return new Outcome("QUIET", why);
        }

        long gap = f.lastAttended() == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(f.lastAttended(), today);
        int earlier90 = Math.max(0, f.attendance90() - f.attendance30());   // days in the 90 window but not the last 30

        // AT_RISK: a regular who stopped
        if (earlier90 >= t.regularMinDays() && f.attendance30() == 0 && gap >= t.atRiskGapDays()) {
            why.add("Attended " + earlier90 + " day" + (earlier90 == 1 ? "" : "s") + " between 90 and 30 days ago, none in the last 30.");
            why.add("Last attended " + f.lastAttended() + " (" + gap + " days ago).");
            return new Outcome("AT_RISK", why);
        }
        // AT_RISK: giving stopped year over year, little attendance
        if (f.giftsPrior365() > 0 && f.gifts365() == 0 && f.attendance90() == 0) {
            why.add(f.giftsPrior365() + " gift" + (f.giftsPrior365() == 1 ? "" : "s") + " recorded in the previous 12 months, none in the last 12.");
            why.add(f.attendance90() == 0 ? "No attendance in the last 90 days." : f.attendance90() + " attendance days in the last 90.");
            return new Outcome("AT_RISK", why);
        }

        // WATCH
        if (f.attendance90() > 0 && f.attendance30() == 0 && gap >= t.watchGapDays()) {
            why.add("Attended " + f.attendance90() + " day" + (f.attendance90() == 1 ? "" : "s") + " in the last 90, none in the last 30.");
            why.add("Last attended " + f.lastAttended() + " (" + gap + " days ago).");
            return new Outcome("WATCH", why);
        }
        if (f.gifts365() > 0 && f.gifts90() == 0 && f.attendance30() == 0) {
            why.add(f.gifts365() + " gift" + (f.gifts365() == 1 ? "" : "s") + " in the last 12 months, none in the last 90 days.");
            why.add("No attendance in the last 30 days.");
            return new Outcome("WATCH", why);
        }
        if (f.attendance365() == 0) {
            why.add("No attendance recorded in the last 365 days.");
            if (f.gifts365() > 0) why.add(f.gifts365() + " gift" + (f.gifts365() == 1 ? "" : "s") + " in the last 12 months.");
            if (f.groups() > 0) why.add("In " + f.groups() + " group" + (f.groups() == 1 ? "" : "s") + ".");
            if (f.serving()) why.add("Serving as a volunteer.");
            return new Outcome("WATCH", why);
        }

        // ENGAGED
        why.add(f.attendance30() + " attendance day" + (f.attendance30() == 1 ? "" : "s") + " in the last 30, " + f.attendance90() + " in the last 90.");
        if (f.gifts90() > 0) why.add(f.gifts90() + " gift" + (f.gifts90() == 1 ? "" : "s") + " in the last 90 days.");
        if (f.groups() > 0) why.add("In " + f.groups() + " group" + (f.groups() == 1 ? "" : "s") + ".");
        if (f.serving()) why.add("Serving as a volunteer.");
        return new Outcome("ENGAGED", why);
    }

    public static final List<String> SIGNALS = List.of("ENGAGED", "WATCH", "AT_RISK", "NEW", "QUIET");

    public static boolean isSignal(String s) { return s != null && SIGNALS.contains(s); }
}
