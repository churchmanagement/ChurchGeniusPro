package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.PromiseVerse;
import com.churchgeniuspro.repository.PromiseVerseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills a church's Daily Verse list for a whole year in one action.
 *
 * <p><b>Why a year only has to be loaded once.</b> A verse is stored against a
 * DAY OF THE YEAR, not a date — {@code promise_verse.day_number} is 1–366 and
 * carries no year at all. So a list loaded for 2026 is the same list 2027 reads,
 * and the church is never asked to reload it. The year on the setup screen is
 * there to answer "how many days does this year have, and which date is day 200",
 * not to file the verses under a year.
 *
 * <p>This only ever adds to what a church already has. {@link Mode#FILL} — the
 * default — writes a verse into the days that are empty and does not touch a day
 * the church has already written itself; {@link Mode#REPLACE} overwrites, and is
 * the only path that discards an existing verse, which is why the screen asks
 * before using it.
 *
 * <p>Every read and write is keyed on the caller's {@code clientId}: the rows are
 * created with it, the existing-day check is scoped by it, and no method here
 * takes a row id. One church cannot see, fill or overwrite another's list.
 */
@Service
public class DailyVerseSetupService {

    private static final Logger log = LoggerFactory.getLogger(DailyVerseSetupService.class);

    /** What to do about days that already have a verse. */
    public enum Mode {
        /** Write only into empty days. The church's own verses are left alone. */
        FILL,
        /** Overwrite every day with the stock verse. Discards existing entries. */
        REPLACE;

        public static Mode of(String s) {
            return "replace".equalsIgnoreCase(s) ? REPLACE : FILL;
        }
    }

    /** What one run did. */
    public record Result(int year, int daysInYear, int added, int replaced, int keptExisting,
                         int totalAfter) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year",         year);
            m.put("daysInYear",   daysInYear);
            m.put("added",        added);
            m.put("replaced",     replaced);
            m.put("keptExisting", keptExisting);
            m.put("totalAfter",   totalAfter);
            return m;
        }
    }

    private final PromiseVerseRepository repo;
    private final DailyVerseLibrary      library;

    public DailyVerseSetupService(PromiseVerseRepository repo, DailyVerseLibrary library) {
        this.repo    = repo;
        this.library = library;
    }

    /** Days in the given year — 366 in a leap year, so 29 February is covered. */
    public static int daysInYear(int year) {
        return Year.isLeap(year) ? 366 : 365;
    }

    /** A sensible year range for the screen's dropdown: this year and the next few. */
    public static List<Integer> selectableYears() {
        int now = LocalDate.now().getYear();
        List<Integer> years = new ArrayList<>();
        for (int y = now - 1; y <= now + 3; y++) years.add(y);
        return years;
    }

    /**
     * How much of the year this church has covered, and what the gaps are.
     *
     * @return {@code year}, {@code daysInYear}, {@code filled}, {@code missing},
     *         {@code libraryAvailable} and a short {@code sample} of the first
     *         few days with the date each one falls on in that year
     */
    public Map<String, Object> coverage(String clientId, int year) {
        int days = daysInYear(year);
        boolean[] present = presentDays(clientId, days);

        int filled = 0;
        for (int d = 1; d <= days; d++) if (present[d]) filled++;

        List<Map<String, Object>> sample = new ArrayList<>();
        for (int d = 1; d <= Math.min(5, days); d++) {
            DailyVerseLibrary.Entry e = library.forDay(d);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day",       d);
            row.put("date",      LocalDate.ofYearDay(year, d).toString());
            row.put("hasVerse",  present[d]);
            row.put("reference", e == null ? "" : e.reference());
            row.put("text",      e == null ? "" : e.text());
            sample.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year",             year);
        out.put("daysInYear",       days);
        out.put("filled",           filled);
        out.put("missing",          days - filled);
        out.put("librarySize",      library.size());
        out.put("libraryAvailable", !library.isEmpty());
        out.put("sample",           sample);
        return out;
    }

    /**
     * Loads the stock year into this church's list.
     *
     * @param clientId the caller's tenant, resolved from the session — never from the request body
     * @throws IllegalArgumentException on a blank tenant, an implausible year, or an empty library
     */
    @Transactional
    public Result populate(String clientId, int year, Mode mode) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Missing tenant");
        }
        if (year < 1900 || year > 2200) {
            throw new IllegalArgumentException("Please choose a valid year.");
        }
        if (library.isEmpty()) {
            throw new IllegalArgumentException(
                "The verse library is not available on this server, so verses cannot be loaded "
              + "automatically. You can still add verses individually on the Promise Verse screen.");
        }

        int days = daysInYear(year);
        // One scoped read of this church's list; the loop below never widens it.
        List<PromiseVerse> existing = repo.findByClientIdAndDeleteFlagFalseOrderByDayNumberAsc(clientId);
        Map<Integer, PromiseVerse> byDay = new LinkedHashMap<>();
        for (PromiseVerse v : existing) {
            if (v.getDayNumber() != null) byDay.putIfAbsent(v.getDayNumber(), v);
        }

        List<PromiseVerse> toSave = new ArrayList<>();
        int added = 0, replaced = 0, kept = 0;

        for (int day = 1; day <= days; day++) {
            DailyVerseLibrary.Entry stock = library.forDay(day);
            if (stock == null) continue;
            PromiseVerse row = byDay.get(day);

            if (row == null) {
                PromiseVerse v = new PromiseVerse();
                v.setClientId(clientId);                 // always the caller's own tenant
                v.setDayNumber(day);
                v.setReference(stock.reference());
                v.setVerseText(stock.text());
                toSave.add(v);
                added++;
            } else if (mode == Mode.REPLACE) {
                row.setReference(stock.reference());
                row.setVerseText(stock.text());
                toSave.add(row);
                replaced++;
            } else {
                kept++;
            }
        }

        if (!toSave.isEmpty()) repo.saveAll(toSave);

        int totalAfter = repo.findByClientIdAndDeleteFlagFalseOrderByDayNumberAsc(clientId).size();
        log.info("Daily verses loaded for {} — year={} mode={} added={} replaced={} kept={} total={}",
                 clientId, year, mode, added, replaced, kept, totalAfter);
        return new Result(year, days, added, replaced, kept, totalAfter);
    }

    /** Which day numbers this church already has, indexed 1..days. */
    private boolean[] presentDays(String clientId, int days) {
        boolean[] present = new boolean[days + 1];
        if (clientId == null || clientId.isBlank()) return present;
        for (PromiseVerse v : repo.findByClientIdAndDeleteFlagFalseOrderByDayNumberAsc(clientId)) {
            Integer d = v.getDayNumber();
            if (d != null && d >= 1 && d <= days) present[d] = true;
        }
        return present;
    }
}
