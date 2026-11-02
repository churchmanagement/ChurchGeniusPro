package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.EventListService;
import com.churchgeniuspro.service.EventRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Retention switches, cutoffs, CSV guard, endpoint protection and the Past-tab page contract (2026-11-02). */
@DisplayName("Event retention + Past paging — unit")
class EventRetentionUnitTest {

    private EventRetentionService svc(boolean on, boolean hard, int a, int d) {
        return new EventRetentionService(mock(NamedParameterJdbcTemplate.class), mock(PlatformTransactionManager.class), on, hard, a, d, 200);
    }

    @Test @DisplayName("Switches off: the nightly job touches nothing")
    void nightlyOffDoesNothing() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        new EventRetentionService(jdbc, mock(PlatformTransactionManager.class), false, true, 5, 10, 200).nightly();
        verifyNoInteractions(jdbc);
    }

    @Test @DisplayName("Default properties ship with both switches off")
    void propertiesDefaultOff() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertThat(props).contains("events.retention.enabled=${EVENTS_RETENTION_ENABLED:false}")
                         .contains("events.retention.hard-delete.enabled=${EVENTS_RETENTION_HARD_DELETE_ENABLED:false}");
    }

    @Test @DisplayName("Cutoffs are calendar years in the church date; leap day maps to 28 Feb; delete always after archive")
    void cutoffs() {
        EventRetentionService s = svc(false, false, 5, 10);
        assertThat(s.archiveCutoff(LocalDate.of(2026, 11, 2))).isEqualTo(LocalDate.of(2021, 11, 2));
        assertThat(s.deleteCutoff(LocalDate.of(2026, 11, 2))).isEqualTo(LocalDate.of(2016, 11, 2));
        assertThat(s.archiveCutoff(LocalDate.of(2025, 3, 1))).isEqualTo(LocalDate.of(2020, 3, 1));
        assertThat(s.archiveCutoff(LocalDate.of(2024, 2, 29))).isEqualTo(LocalDate.of(2019, 2, 28));
        EventRetentionService bad = svc(false, false, 5, 3);       // misconfigured: delete before archive
        assertThat(bad.deleteCutoff(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2020, 1, 1));
    }

    @Test @DisplayName("CSV cells are quoted and formula-leading values neutralised")
    void csvCell() {
        assertThat(ServiceAdminEventRetentionController.cell("=HYPERLINK(1)")).isEqualTo("\"'=HYPERLINK(1)\"");
        assertThat(ServiceAdminEventRetentionController.cell("+1")).isEqualTo("\"'+1\"");
        assertThat(ServiceAdminEventRetentionController.cell("Say \"hi\", ok")).isEqualTo("\"Say \"\"hi\"\", ok\"");
        assertThat(ServiceAdminEventRetentionController.cell(null)).isEqualTo("\"\"");
    }

    @Test @DisplayName("Retention endpoints refuse anyone but a Service Admin, and there is no run-now endpoint")
    void endpointsGuarded() throws Exception {
        EventRetentionService s = mock(EventRetentionService.class);
        ServiceAdminEventRetentionController c = new ServiceAdminEventRetentionController(s);
        MockHttpServletRequest none = new MockHttpServletRequest();
        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.getSession(true).setAttribute("role", "Admin");
        for (MockHttpServletRequest r : new MockHttpServletRequest[] { none, admin }) {
            assertThat(c.dryRun(500, r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.csv(r).getStatusCode().value()).isEqualTo(401);
            assertThat(c.restore(5, r).getStatusCode().value()).isEqualTo(401);
        }
        verifyNoInteractions(s);
        String src = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/ServiceAdminEventRetentionController.java"));
        assertThat(src).doesNotContain("purge(").doesNotContain("archive(");
    }

    @Test @DisplayName("Past list: bad sort / cursor refused before any query; blank church gets nothing")
    void pastValidation() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        EventListService l = new EventListService(jdbc, null);
        assertThatThrownBy(() -> l.past("C1", null, null, "drop table", null, 10)).isInstanceOf(EventListService.BadRequest.class);
        assertThatThrownBy(() -> l.past("C1", null, null, "name", "o-1", 10)).isInstanceOf(EventListService.BadRequest.class);
        assertThatThrownBy(() -> l.past("C1", null, null, "newest", "2026-13-01|5", 10)).isInstanceOf(EventListService.BadRequest.class);
        assertThat(l.past(" ", null, null, null, null, 10).rows()).isEmpty();
        assertThat(l.upcoming(null)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test @DisplayName("Events page: Past is server-paged 10 at a time with compact labelled actions; tabs decided by the server")
    void eventsPageContract() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/events.html"));
        assertThat(html).contains("id=\"pastList\"").contains("id=\"pastMoreBtn\"").contains("PAST_PAGE = 10")
                        .contains("/api/events/past").contains("/api/events?view=upcoming").contains("/api/events/stats");
        for (String label : new String[] { "Check-In", "Edit", "Registrants", "Volunteers", "Invite", "Delete" })
            assertThat(html).as(label).containsPattern("pastAct\\('[^']+', '" + label + "'");
        assertThat(html).contains("title=\"' + label + '\" aria-label=\"' + label + '\"");
        assertThat(html).doesNotContain("isUpcomingEvent(");       // no browser-UTC tab decision any more
    }

    @Test @DisplayName("Access Audit page loads 10 rows and pages with the last row's time + id")
    void accessAuditPageContract() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/private-access-settings.html"));
        assertThat(html).contains("AUDIT_PAGE = 10").contains("id=\"auditMoreBtn\"").contains("beforeTs=").contains("beforeId=");
    }
}
