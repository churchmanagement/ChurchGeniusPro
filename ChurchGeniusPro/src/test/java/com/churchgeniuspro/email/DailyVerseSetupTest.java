package com.churchgeniuspro.email;

import com.churchgeniuspro.controller.PromiseVerseController;
import com.churchgeniuspro.hibernate.PromiseVerse;
import com.churchgeniuspro.repository.PromiseVerseRepository;
import com.churchgeniuspro.service.DailyVerseLibrary;
import com.churchgeniuspro.service.DailyVerseSetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Loading a whole year of Daily Verses in one action.
 *
 * <p>"Include Daily Verse" has always worked by picking a verse at random from
 * the church's own {@code promise_verse} rows. Filling that list meant typing 365
 * entries by hand, so in practice churches had a handful and every email quoted
 * the same three. This adds the one-click year.
 *
 * <p>Two things are worth stating because they are the whole design. A verse is
 * filed against a DAY OF THE YEAR, not a date, so a list loaded once is reused
 * every year afterwards and the church is never asked to reload it — the year on
 * the screen only decides how many days there are and which date each one falls
 * on. And loading defaults to filling the GAPS: a church that has written its own
 * verse for a day keeps it, because the point is to remove the typing, not to
 * overwrite the church's own words.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Daily Verse — load a year")
class DailyVerseSetupTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    /* ── the bundled verses ─────────────────────────────────────────────── */

    @Nested
    @DisplayName("the bundled verse library")
    class Library {

        private final DailyVerseLibrary library = new DailyVerseLibrary();

        @Test
        @DisplayName("covers a leap year, with no day left blank")
        void coversALeapYear() {
            assertThat(library.size()).isEqualTo(DailyVerseLibrary.DAYS);
            assertThat(library.isEmpty()).isFalse();
            for (DailyVerseLibrary.Entry e : library.entries()) {
                assertThat(e.reference()).isNotBlank();
                assertThat(e.text()).isNotBlank();
            }
        }

        @Test
        @DisplayName("never repeats a verse across the year")
        void everyDayIsADifferentVerse() {
            Set<String> refs = new HashSet<>();
            for (DailyVerseLibrary.Entry e : library.entries()) refs.add(e.reference());
            assertThat(refs).hasSize(library.size());
        }

        @Test
        @DisplayName("carries scripture, not the dataset's markup")
        void textIsClean() {
            for (DailyVerseLibrary.Entry e : library.entries()) {
                assertThat(e.text())
                        .as("verse %s", e.reference())
                        .doesNotContain("<", ">", "  ")
                        .doesNotContain("chief Musician", "A Psalm of");   // psalm headings
                assertThat(e.text().trim()).isEqualTo(e.text());
                assertThat(e.reference()).matches(".+ \\d+:\\d+");
            }
        }

        @Test
        @DisplayName("a day beyond the list wraps rather than coming back empty")
        void dayLookupWraps() {
            assertThat(library.forDay(1)).isNotNull();
            assertThat(library.forDay(366)).isNotNull();
            assertThat(library.forDay(0)).isNull();
        }
    }

    /* ── loading a year ─────────────────────────────────────────────────── */

    @Nested
    @DisplayName("loading")
    class Loading {

        @Mock PromiseVerseRepository repo;
        DailyVerseSetupService service;

        /** The rows the repository is currently holding for each tenant. */
        private final Map<String, List<PromiseVerse>> stored = new HashMap<>();

        @BeforeEach
        void setUp() {
            service = new DailyVerseSetupService(repo, new DailyVerseLibrary());
            stored.clear();
            when(repo.findByClientIdAndDeleteFlagFalseOrderByDayNumberAsc(anyString()))
                    .thenAnswer(i -> new ArrayList<>(stored.getOrDefault(i.getArgument(0), List.of())));
            when(repo.saveAll(any())).thenAnswer(i -> {
                Iterable<PromiseVerse> rows = i.getArgument(0);
                for (PromiseVerse v : rows) {
                    stored.computeIfAbsent(v.getClientId(), k -> new ArrayList<>());
                    if (!stored.get(v.getClientId()).contains(v)) stored.get(v.getClientId()).add(v);
                }
                return rows;
            });
        }

        private PromiseVerse own(String tenant, int day, String text) {
            PromiseVerse v = new PromiseVerse();
            v.setClientId(tenant);
            v.setDayNumber(day);
            v.setReference("Our Reference " + day);
            v.setVerseText(text);
            stored.computeIfAbsent(tenant, k -> new ArrayList<>()).add(v);
            return v;
        }

        @ParameterizedTest(name = "{0} → {1} days")
        @CsvSource({ "2026,365", "2027,365", "2028,366", "2024,366", "2100,365", "2000,366" })
        @DisplayName("a leap year gets a 29 February verse; an ordinary year does not")
        void leapYearsAreCovered(int year, int expected) {
            assertThat(DailyVerseSetupService.daysInYear(year)).isEqualTo(expected);
            assertThat(service.populate(OURS, year, DailyVerseSetupService.Mode.FILL).added())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("an empty church ends up with a verse for every day")
        void fillsAnEmptyYear() {
            DailyVerseSetupService.Result r =
                    service.populate(OURS, 2026, DailyVerseSetupService.Mode.FILL);

            assertThat(r.added()).isEqualTo(365);
            assertThat(r.replaced()).isZero();
            assertThat(stored.get(OURS)).hasSize(365);
            Set<Integer> days = new HashSet<>();
            for (PromiseVerse v : stored.get(OURS)) days.add(v.getDayNumber());
            assertThat(days).hasSize(365).contains(1, 200, 365);
        }

        @Test
        @DisplayName("a church's own verses survive a load — only the gaps are filled")
        void keepsWhatTheChurchWrote() {
            PromiseVerse mine = own(OURS, 42, "Our own words for day 42");

            DailyVerseSetupService.Result r =
                    service.populate(OURS, 2026, DailyVerseSetupService.Mode.FILL);

            assertThat(r.added()).isEqualTo(364);
            assertThat(r.keptExisting()).isEqualTo(1);
            assertThat(mine.getVerseText()).isEqualTo("Our own words for day 42");
            assertThat(mine.getReference()).isEqualTo("Our Reference 42");
        }

        @Test
        @DisplayName("replace is the one path that overwrites, and it says so in its counts")
        void replaceOverwrites() {
            PromiseVerse mine = own(OURS, 42, "Our own words for day 42");

            DailyVerseSetupService.Result r =
                    service.populate(OURS, 2026, DailyVerseSetupService.Mode.REPLACE);

            assertThat(r.replaced()).isEqualTo(1);
            assertThat(r.added()).isEqualTo(364);
            assertThat(r.keptExisting()).isZero();
            assertThat(mine.getVerseText()).isNotEqualTo("Our own words for day 42");
        }

        @Test
        @DisplayName("loading twice does not double the list — the second run has nothing to add")
        void isIdempotent() {
            service.populate(OURS, 2026, DailyVerseSetupService.Mode.FILL);
            DailyVerseSetupService.Result again =
                    service.populate(OURS, 2026, DailyVerseSetupService.Mode.FILL);

            assertThat(again.added()).isZero();
            assertThat(again.keptExisting()).isEqualTo(365);
            assertThat(stored.get(OURS)).hasSize(365);
        }

        @Test
        @DisplayName("a list loaded for one year already covers the next — nothing to reload")
        void oneLoadServesEveryYear() {
            service.populate(OURS, 2026, DailyVerseSetupService.Mode.FILL);

            // The church comes back in 2027: every day is already answered.
            Map<String, Object> next = service.coverage(OURS, 2027);
            assertThat(next).containsEntry("missing", 0);
            assertThat(service.populate(OURS, 2027, DailyVerseSetupService.Mode.FILL).added()).isZero();

            // 2028 is a leap year, so exactly one day — 29 February — is new.
            assertThat(service.populate(OURS, 2028, DailyVerseSetupService.Mode.FILL).added()).isEqualTo(1);
        }

        @Test
        @DisplayName("coverage reports the gaps, and dates the sample against the chosen year")
        void coverageDescribesTheYear() {
            own(OURS, 1, "day one");

            Map<String, Object> c = service.coverage(OURS, 2026);

            assertThat(c).containsEntry("year", 2026)
                         .containsEntry("daysInYear", 365)
                         .containsEntry("filled", 1)
                         .containsEntry("missing", 364)
                         .containsEntry("libraryAvailable", true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sample = (List<Map<String, Object>>) c.get("sample");
            assertThat(sample).isNotEmpty();
            assertThat(sample.get(0)).containsEntry("date", "2026-01-01")
                                     .containsEntry("hasVerse", true);
            assertThat(sample.get(1)).containsEntry("date", "2026-01-02")
                                     .containsEntry("hasVerse", false);
        }

        @Test
        @DisplayName("a nonsense year is refused rather than written")
        void refusesAnImplausibleYear() {
            assertThatThrownBy(() -> service.populate(OURS, 12, DailyVerseSetupService.Mode.FILL))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repo, never()).saveAll(any());
        }

        /* ── tenant scoping ─────────────────────────────────────────────── */

        @Test
        @DisplayName("every row is written to the church that asked for it")
        void rowsBelongToTheCaller() {
            service.populate(OURS, 2026, DailyVerseSetupService.Mode.FILL);

            assertThat(stored.get(OURS)).isNotEmpty();
            assertThat(stored.get(OURS)).allSatisfy(v ->
                    assertThat(v.getClientId()).isEqualTo(OURS));
            assertThat(stored.get(THEIRS)).isNull();
            verify(repo, never()).findByClientIdAndDeleteFlagFalseOrderByDayNumberAsc(THEIRS);
        }

        @Test
        @DisplayName("one church's load leaves another church's verses untouched")
        void oneChurchsLoadCannotReachAnother() {
            PromiseVerse theirs = own(THEIRS, 1, "Their own words");

            service.populate(OURS, 2026, DailyVerseSetupService.Mode.REPLACE);

            assertThat(theirs.getVerseText()).isEqualTo("Their own words");
            assertThat(stored.get(THEIRS)).hasSize(1);
            // …and their day 1 did not count as ours, so ours was written too.
            assertThat(stored.get(OURS)).hasSize(365);
        }

        @Test
        @DisplayName("a load without a tenant is refused rather than written unowned")
        void refusesABlankTenant() {
            assertThatThrownBy(() -> service.populate(null, 2026, DailyVerseSetupService.Mode.FILL))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.populate("  ", 2026, DailyVerseSetupService.Mode.FILL))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repo, never()).saveAll(any());
        }

        @Test
        @DisplayName("no library means a clear message, not a silent no-op")
        void refusesWhenTheLibraryIsMissing() {
            DailyVerseSetupService empty = new DailyVerseSetupService(repo, new DailyVerseLibrary() {
                @Override public boolean isEmpty() { return true; }
                @Override public int size() { return 0; }
            });
            assertThatThrownBy(() -> empty.populate(OURS, 2026, DailyVerseSetupService.Mode.FILL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not available");
        }
    }

    /* ── the endpoints ──────────────────────────────────────────────────── */

    @Nested
    @DisplayName("the setup endpoints")
    class Endpoints {

        @Mock PromiseVerseRepository repo;
        PromiseVerseController controller;

        @BeforeEach
        void setUp() {
            controller = new PromiseVerseController(repo);
            controller.setSetupService(new DailyVerseSetupService(repo, new DailyVerseLibrary()));
            when(repo.findByClientIdAndDeleteFlagFalseOrderByDayNumberAsc(anyString()))
                    .thenReturn(List.of());
            when(repo.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        }

        private MockHttpServletRequest staff(String tenant, String role) {
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("username",    "staff@" + tenant);
            s.setAttribute("role",        role);
            s.setAttribute("appClientId", tenant);
            s.setAttribute("clientId",    tenant);
            MockHttpServletRequest r = new MockHttpServletRequest();
            r.setSession(s);
            return r;
        }

        private int status(ResponseEntity<?> r) { return r.getStatusCode().value(); }

        @Test
        @DisplayName("an admin can see the gaps and load the year")
        void adminCanLoad() {
            assertThat(status(controller.coverage(2026, staff(OURS, "Admin")))).isEqualTo(200);

            Map<String, Object> body = new HashMap<>();
            body.put("year", 2026);
            assertThat(status(controller.populate(body, staff(OURS, "SuperAdmin")))).isEqualTo(200);
            verify(repo).saveAll(any());
        }

        @Test
        @DisplayName("the verses are written to the session's church, never one named in the body")
        void bodyCannotChooseTheChurch() {
            Map<String, Object> body = new HashMap<>();
            body.put("year", 2026);
            body.put("clientId", THEIRS);          // ignored

            controller.populate(body, staff(OURS, "Admin"));

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<PromiseVerse>> saved =
                    org.mockito.ArgumentCaptor.forClass((Class) List.class);
            verify(repo).saveAll(saved.capture());
            assertThat(saved.getValue()).isNotEmpty();
            assertThat(saved.getValue()).allSatisfy(v ->
                    assertThat(v.getClientId()).isEqualTo(OURS));
            verify(repo, never()).findByClientIdAndDeleteFlagFalseOrderByDayNumberAsc(THEIRS);
        }

        @Test
        @DisplayName("a non-admin staff member cannot load or inspect the list")
        void nonAdminIsRefused() {
            assertThat(status(controller.coverage(2026, staff(OURS, "User")))).isEqualTo(403);
            assertThat(status(controller.populate(Map.of("year", 2026), staff(OURS, "Accountant"))))
                    .isEqualTo(403);
            verify(repo, never()).saveAll(any());
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRefused() {
            MockHttpServletRequest anon = new MockHttpServletRequest();
            assertThat(status(controller.coverage(2026, anon))).isEqualTo(403);
            assertThat(status(controller.populate(Map.of(), anon))).isEqualTo(403);
            verify(repo, never()).saveAll(any());
        }

        @Test
        @DisplayName("29 February can still be edited by hand — the day cap allows 366")
        void leapDayIsEditable() {
            when(repo.existsByClientIdAndDayNumberAndDeleteFlagFalse(OURS, 366)).thenReturn(false);
            when(repo.save(any(PromiseVerse.class))).thenAnswer(i -> i.getArgument(0));

            Map<String, Object> body = new HashMap<>();
            body.put("dayNumber", 366);
            body.put("reference", "Psalms 118:24");
            body.put("verseText", "This is the day which the LORD hath made.");

            assertThat(status(controller.create(body, staff(OURS, "Admin")))).isEqualTo(200);
        }
    }
}
