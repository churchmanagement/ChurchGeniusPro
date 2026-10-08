package com.churchgeniuspro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The 366 verses a church can load into its Daily Verse list in one click.
 *
 * <p>Setting up the Daily Verse used to mean typing 365 entries by hand on the
 * Promise Verse screen, which is why almost nobody had more than a handful and
 * "Include Daily Verse" repeated the same few lines. This is the stock year:
 * one verse per day of the year, ready to be copied into a church's own list,
 * which it then owns and can edit or replace verse by verse.
 *
 * <p><b>Text and licensing.</b> King James Version — public domain. The text was
 * taken verbatim from a published KJV dataset (the MIT-licensed {@code bible-kjv}
 * package) by {@code tools/build-daily-verses.py}, not transcribed by hand, so no
 * verse is a paraphrase or a misquotation. The only thing the build script removes
 * is a psalm's editorial heading ("A Psalm of David.", "To the chief Musician…")
 * and Psalm 119's acrostic letters, which sit inside verse 1 in the KJV and are
 * titles rather than words of the verse. Regenerate with that script if the list
 * is ever revised; do not hand-edit the JSON.
 *
 * <p>Read once at startup from {@code daily-verses.json} and held immutable. A
 * missing or unreadable file leaves the library empty rather than failing the
 * application: the church simply gets "no verses available to load", and every
 * other part of the Daily Verse feature is unaffected.
 */
@Service
public class DailyVerseLibrary {

    private static final Logger log = LoggerFactory.getLogger(DailyVerseLibrary.class);

    /** A leap year's worth, so 29 February has its own verse rather than borrowing one. */
    public static final int DAYS = 366;

    /** One stock verse. {@code day} is the day of the year it is offered for. */
    public record Entry(int day, String reference, String text) {}

    private final List<Entry> entries;

    public DailyVerseLibrary() {
        this.entries = load();
        log.info("Daily verse library loaded — {} verses", entries.size());
    }

    /** Every stock verse, day 1 first. Never null; empty if the file could not be read. */
    public List<Entry> entries() { return entries; }

    public boolean isEmpty() { return entries.isEmpty(); }

    public int size() { return entries.size(); }

    /**
     * The stock verse offered for a day of the year.
     *
     * @param day 1–366
     * @return null when the day is out of range or the library is empty
     */
    public Entry forDay(int day) {
        if (day < 1 || entries.isEmpty()) return null;
        // Wraps rather than returning nothing, so a 366-day year is still covered
        // even if the bundled list is ever shortened.
        return entries.get((day - 1) % entries.size());
    }

    private static List<Entry> load() {
        try (InputStream in = new ClassPathResource("daily-verses.json").getInputStream()) {
            List<Map<String, Object>> raw = new ObjectMapper().readValue(in, List.class);
            List<Entry> out = new ArrayList<>(raw.size());
            for (Map<String, Object> m : raw) {
                Object day  = m.get("day");
                String ref  = String.valueOf(m.getOrDefault("reference", "")).trim();
                String text = String.valueOf(m.getOrDefault("text", "")).trim();
                if (ref.isEmpty() || text.isEmpty()) continue;
                int d = day instanceof Number n ? n.intValue() : out.size() + 1;
                out.add(new Entry(d, ref, text));
            }
            return Collections.unmodifiableList(out);
        } catch (Exception e) {
            log.error("Daily verse library could not be read — the one-click load will be "
                    + "unavailable, but existing verses are unaffected: {}", e.getMessage());
            return List.of();
        }
    }
}
