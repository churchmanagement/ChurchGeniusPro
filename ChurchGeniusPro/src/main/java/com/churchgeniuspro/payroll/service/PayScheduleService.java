package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.model.PayFrequency;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates upcoming pay periods for a given {@link PayFrequency} (requirement #8,
 * automatic payroll schedule generation). The {@code anchor} is the first pay
 * date; subsequent periods follow the cadence.
 */
@Service
public class PayScheduleService {

    /**
     * Generate {@code count} consecutive pay periods starting at {@code anchorPayDate}.
     *
     * <ul>
     *   <li>WEEKLY: 7-day periods ending on the pay date.</li>
     *   <li>BIWEEKLY: 14-day periods ending on the pay date.</li>
     *   <li>SEMIMONTHLY: the 1st-15th (paid on the 16th) and 16th-end (paid the 1st).</li>
     *   <li>MONTHLY: full calendar month, paid the 1st of the next month.</li>
     *   <li>DAILY / HOURLY: single-day periods.</li>
     * </ul>
     */
    public List<PayPeriod> generate(PayFrequency frequency, LocalDate anchorPayDate, int count) {
        List<PayPeriod> out = new ArrayList<>();
        if (frequency == null || anchorPayDate == null || count <= 0) return out;

        switch (frequency) {
            case WEEKLY:
                for (int i = 0; i < count; i++) {
                    LocalDate pay = anchorPayDate.plusWeeks(i);
                    out.add(new PayPeriod(pay.minusDays(6), pay, pay));
                }
                break;
            case BIWEEKLY:
                for (int i = 0; i < count; i++) {
                    LocalDate pay = anchorPayDate.plusWeeks(2L * i);
                    out.add(new PayPeriod(pay.minusDays(13), pay, pay));
                }
                break;
            case SEMIMONTHLY:
                generateSemiMonthly(anchorPayDate, count, out);
                break;
            case MONTHLY:
                for (int i = 0; i < count; i++) {
                    LocalDate pay = anchorPayDate.plusMonths(i).withDayOfMonth(1);
                    LocalDate prevMonth = pay.minusMonths(1);
                    out.add(new PayPeriod(prevMonth.withDayOfMonth(1),
                            prevMonth.with(TemporalAdjusters.lastDayOfMonth()), pay));
                }
                break;
            case DAILY:
            case HOURLY:
            default:
                for (int i = 0; i < count; i++) {
                    LocalDate d = anchorPayDate.plusDays(i);
                    out.add(new PayPeriod(d, d, d));
                }
                break;
        }
        return out;
    }

    private void generateSemiMonthly(LocalDate anchor, int count, List<PayPeriod> out) {
        LocalDate cursor = anchor;
        for (int i = 0; i < count; i++) {
            LocalDate periodStart, periodEnd, pay;
            if (cursor.getDayOfMonth() <= 15) {
                // First half: 1st–15th, paid mid-month (use the anchor's day as the pay day-of-month).
                periodStart = cursor.withDayOfMonth(1);
                periodEnd = cursor.withDayOfMonth(15);
                pay = cursor;
                cursor = cursor.withDayOfMonth(Math.min(16, cursor.lengthOfMonth()));
            } else {
                // Second half: 16th–end, paid on/after month end.
                periodStart = cursor.withDayOfMonth(16);
                periodEnd = cursor.with(TemporalAdjusters.lastDayOfMonth());
                pay = cursor;
                cursor = cursor.plusMonths(1).withDayOfMonth(Math.min(anchor.getDayOfMonth(), 15));
            }
            out.add(new PayPeriod(periodStart, periodEnd, pay));
        }
    }
}
