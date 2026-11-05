package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.filter.PeopleFilter;
import com.churchgeniuspro.ai.filter.PeopleFilter.*;
import com.churchgeniuspro.ai.filter.PeopleFilterParser;
import com.churchgeniuspro.service.AiQueryLexicon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Phase 1 — plain English → PeopleFilter, deterministically. Golden phrases (what a church
 * office actually types), negation per clause, the church's own names, and the claim rule that
 * keeps the older single-module answers untouched.
 */
@DisplayName("AI Phase 1 — PeopleFilterParser goldens")
class PeopleFilterParserTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 4);   // Wednesday
    static final PeopleFilterParser.Context CTX = new PeopleFilterParser.Context(
            List.of("Tithe", "Building Fund", "Missions"), List.of("Welcome Team", "Men's Breakfast", "Youth Group"),
            List.of("Roof Campaign 2026"), List.of("Spring Picnic", "Christmas Service"), 3, 12, 4, 6);

    /** Exactly what AiDataSearchService hands the route: norm + lexicon. */
    static String canon(String q) {
        String n = PeopleFilterParser.preNorm(String.valueOf(q)).toLowerCase(Locale.ROOT).replace("’", "'").replace("'s ", " ").replace("'", "")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return AiQueryLexicon.canonical(n);
    }
    static PeopleFilterParser.Result parse(String q) { return new PeopleFilterParser(CTX, TODAY).parse(canon(q)); }
    static List<Predicate> preds(String q) { return parse(q).filter().predicates(); }

    @Test @DisplayName("age bands in every common wording")
    void ages() {
        assertThat(preds("members between 18 and 30")).containsExactly(new AgeBand(18, 30));
        assertThat(preds("people aged 18 to 30")).containsExactly(new AgeBand(18, 30));
        assertThat(preds("members 18-30 years old")).containsExactly(new AgeBand(18, 30));
        assertThat(preds("members over 60")).containsExactly(new AgeBand(60, null));
        assertThat(preds("members older than 60")).containsExactly(new AgeBand(61, null));
        assertThat(preds("members 65+")).containsExactly(new AgeBand(65, null));
        assertThat(preds("members under 18")).containsExactly(new AgeBand(null, 17));
        assertThat(preds("list our seniors")).containsExactly(new AgeBand(65, null));
        assertThat(preds("show the teenagers")).containsExactly(new AgeBand(13, 19));
        assertThat(preds("young adults not in a group")).containsExactly(new AgeBand(18, 30), new InGroup(true, null));
        // "over 60" is an age, "over 60 days" is not
        assertThat(preds("members who attended over 3 times in the last 60 days")).containsExactly(
                new Attended(false, 4, TODAY.minusDays(59), TODAY, "in the last 60 days"));
    }

    @Test @DisplayName("gender, role, type, status, birth month, contact details")
    void simpleFacts() {
        assertThat(preds("women who attended this year")).startsWith(new Gender("Female"));
        assertThat(preds("list the men over 50")).containsExactlyInAnyOrder(new Gender("Male"), new AgeBand(50, null));
        assertThat(preds("visitors with no email")).containsExactly(new MemberType("Visitor"), new HasContact(true, "email"));
        assertThat(preds("guests who have a phone number")).containsExactly(new MemberType("Guest"), new HasContact(false, "phone"));
        assertThat(preds("non-members who attended twice")).startsWith(new MemberType("NonMember"));
        assertThat(preds("inactive members who gave last year")).startsWith(new Status("inactive"));
        assertThat(preds("members born in March")).containsExactly(new BirthMonth(3));
        assertThat(preds("who has a birthday in december")).containsExactly(new BirthMonth(12));
        assertThat(preds("heads of household without a phone")).containsExactly(new Role("Head"), new HasContact(true, "phone"));
        assertThat(preds("spouses who are not serving")).containsExactly(new Role("Spouse"), new Serving(true, TODAY.minusMonths(6)));
    }

    @Test @DisplayName("attendance: counts, windows, since, negation; giving: period, fund, negation")
    void attendanceAndGiving() {
        assertThat(preds("women who attended at least twice in the last 60 days but didnt give this year")).containsExactly(
                new Gender("Female"),
                new Attended(false, 2, TODAY.minusDays(59), TODAY, "in the last 60 days"),
                new Gave(true, TODAY.withDayOfYear(1), LocalDate.of(2026, 12, 31), "this year", null));
        assertThat(preds("members who attended 3 or more sundays since easter and gave to the building fund")).containsExactly(
                new Attended(false, 3, LocalDate.of(2026, 4, 6), TODAY, "since Apr 6, 2026"),
                new Gave(false, TODAY.withDayOfYear(1), TODAY, "this year", "Building Fund"));
        assertThat(preds("regular attenders who gave nothing in 2025")).containsExactly(
                new Attended(false, 3, TODAY.minusWeeks(12).plusDays(1), TODAY, "in the last 12 weeks"),
                new Gave(true, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "2025", null));
        assertThat(preds("men who have not attended since june")).containsExactly(
                new Gender("Male"), new Attended(true, 0, LocalDate.of(2026, 6, 1), TODAY, "since Jun 1, 2026"));
        assertThat(preds("adults who havent attended in the last 8 weeks and arent in a group")).containsExactly(
                new AgeBand(18, null), new Attended(true, 0, TODAY.minusWeeks(8).plusDays(1), TODAY, "in the last 8 weeks"), new InGroup(true, null));
        assertThat(preds("people who tithed last month but are not in the welcome team group")).containsExactly(
                new Gave(false, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "last month", "Tithe"), new InGroup(true, "Welcome Team"));   // a fund named like the word used restricts to it
    }

    @Test @DisplayName("groups, serving, campaigns and events by the church's own names")
    void names() {
        assertThat(preds("who is in the youth group and over 18")).containsExactly(new AgeBand(18, null), new InGroup(false, "Youth Group"));
        assertThat(preds("members in mens breakfast who are not serving")).containsExactly(new InGroup(false, "Men's Breakfast"), new Serving(true, TODAY.minusMonths(6)));
        assertThat(preds("adults who serve but arent in a group")).containsExactly(new AgeBand(18, null), new Serving(false, TODAY.minusMonths(6)), new InGroup(true, null));
        assertThat(preds("who pledged to the roof campaign 2026 but has not given this year")).containsExactly(
                new InCampaign(false, "Roof Campaign 2026"), new Gave(true, TODAY.withDayOfYear(1), LocalDate.of(2026, 12, 31), "this year", null));
        assertThat(preds("members who registered for spring picnic and are not in a group")).containsExactly(
                new RegisteredForEvent(false, "Spring Picnic"), new InGroup(true, null));
        PeopleFilterParser.Result r = parse("who registered for the gala");
        assertThat(r.claim()).isFalse();
        assertThat(r.clarify()).contains("Which event");
    }

    @Test @DisplayName("claim rule: two conditions, or one the older routes cannot answer; single plain giving / attendance / volunteers / children stay with the older routes")
    void claimRule() {
        assertThat(parse("women who attended twice in the last 60 days but didnt give this year").claim()).isTrue();
        assertThat(parse("members between 18 and 30").claim()).isTrue();
        assertThat(parse("visitors with no email").claim()).isTrue();
        assertThat(parse("members born in march").claim()).isTrue();
        assertThat(parse("who is in the youth group").claim()).isTrue();
        // left to the existing intents
        assertThat(parse("who gave last year but not this year").claim()).as("existing lapsed-givers question").isFalse();
        assertThat(parse("how much did we receive in contributions this year").claim()).isFalse();
        assertThat(parse("list volunteers").claim()).isFalse();
        // "regular attenders not in a group" parses too, but AiDataSearchService runs the cross-reference route first (service test)
        assertThat(preds("regular attenders not in a group")).containsExactly(
                new Attended(false, 3, TODAY.minusWeeks(12).plusDays(1), TODAY, "in the last 12 weeks"), new InGroup(true, null));
        assertThat(parse("show the children").claim()).isFalse();
        assertThat(parse("list deleted members").claim()).isTrue();   // was a strong fact; existing peopleByStatus runs first anyway
        assertThat(parse("how do i add a visitor").claim()).as("how-to never").isFalse();
        assertThat(parse("open the income page").filter().isEmpty()).isTrue();
        assertThat(parse("how many members do we have").filter().isEmpty()).isTrue();
    }

    @Test @DisplayName("JSON round-trip keeps every predicate; unknown kinds and bad values are refused")
    void json() {
        PeopleFilter f = parse("women who attended at least twice in the last 60 days but didnt give to missions this year and are not in the youth group").filter();
        assertThat(f.predicates()).hasSize(4);
        PeopleFilter back = PeopleFilter.fromJson(f.toJson());
        assertThat(back).isEqualTo(f);
        assertThat(back.without(0).predicates()).hasSize(3).doesNotContain(new Gender("Female"));
        assertThat(f.modules()).containsExactly("Members", "Attendance", "Income", "Groups");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PeopleFilter.fromJson(List.of(java.util.Map.of("kind", "DropTable"))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PeopleFilter.fromJson(List.of(java.util.Map.of("kind", "AgeBand", "min", 500))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PeopleFilter.fromJson(List.of(java.util.Map.of("kind", "Gave", "from", "2026-01-01' OR 1=1", "to", "2026-12-31"))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PeopleFilter.fromJson(List.of(java.util.Map.of("kind", "Status", "status", "'; drop"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
