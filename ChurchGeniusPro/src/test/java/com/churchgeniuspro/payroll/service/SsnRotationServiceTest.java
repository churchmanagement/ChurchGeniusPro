package com.churchgeniuspro.payroll.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.churchgeniuspro.payroll.entity.PayrollAuditLog;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.repository.PayrollAuditLogRepository;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Security audit 2026-10-07, Phase 5: counts only, explicit state handling, one row per transaction. */
@DisplayName("SsnRotationService")
class SsnRotationServiceTest {

    static final String KEY_NEW = SsnCryptoRotationTest.b64(32, 9);

    PayrollEmployeeRepository repo = mock(PayrollEmployeeRepository.class);
    PayrollAuditLogRepository audit = mock(PayrollAuditLogRepository.class);
    List<PayrollEmployee> rows = new ArrayList<>();
    ListAppender<ILoggingEvent> appender; Logger logger;

    @BeforeEach void setUp() {
        when(repo.findAll()).thenReturn(rows);
        when(repo.findById(anyLong())).thenAnswer(inv -> rows.stream().filter(e -> e.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        logger = (Logger) LoggerFactory.getLogger(SsnRotationService.class);
        appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
    }
    @AfterEach void release() { logger.detachAppender(appender); }

    private PayrollEmployee emp(long id, String client, String ssn) {
        PayrollEmployee e = new PayrollEmployee(); e.setId(id); e.setAppClientId(client); e.setSsnLast4(ssn); rows.add(e); return e;
    }

    /** The production shape: one legacy plaintext row + one row under the derived key, plus edge cases. */
    private SsnCrypto seedProductionShape() {
        String underDerived = new SsnCrypto("", "").encrypt("4455");
        SsnCrypto rotating = new SsnCrypto(KEY_NEW, "derived");
        emp(1L, "CHR-A", "1122");                      // LEGACY_PLAINTEXT
        emp(2L, "CHR-A", underDerived);                // PREVIOUS_KEY
        emp(3L, "CHR-B", null);                        // BLANK
        emp(4L, "CHR-B", rotating.encrypt("9900"));    // CURRENT_KEY
        emp(5L, "CHR-B", "not-a-ciphertext");          // UNREADABLE
        return rotating;
    }

    @Test @DisplayName("dry run counts by explicit state and writes nothing")
    void dryRun() {
        SsnCrypto c = seedProductionShape();
        Map<String, Object> out = new SsnRotationService(repo, audit, c, null).status();
        assertThat(out).containsEntry("dryRun", true).containsEntry("status", "success").containsEntry("rotationInProgress", true)
                .containsEntry("examined", 5).containsEntry("blank", 1).containsEntry("legacyPlaintext", 1)
                .containsEntry("alreadyOnCurrentKey", 1).containsEntry("wouldRotate", 2).containsEntry("unreadable", 1).containsEntry("pending", 2);
        assertThat(out.keySet()).as("counts only").doesNotContain("unreadableEmployeeIds", "rotated");
        assertThat(out.values()).noneMatch(v -> v instanceof List);
        verify(repo, never()).save(any());
        verify(audit, never()).save(any());
        assertThat(rows.get(0).getSsnLast4()).isEqualTo("1122");
    }

    @Test @DisplayName("apply rotates plaintext + previous-key rows, skips current, reports unreadable, audits counts per tenant")
    void apply() {
        SsnCrypto c = seedProductionShape();
        String currentBefore = rows.get(3).getSsnLast4();
        SsnRotationService svc = new SsnRotationService(repo, audit, c, null);
        Map<String, Object> out = svc.rotate(false, "admin@cgp");
        assertThat(out).containsEntry("dryRun", false).containsEntry("rotated", 2).containsEntry("legacyPlaintext", 1)
                .containsEntry("alreadyOnCurrentKey", 1).containsEntry("unreadable", 1).containsEntry("pending", 0);
        SsnCrypto afterRotation = new SsnCrypto(KEY_NEW, "");     // previous key removed
        assertThat(afterRotation.decrypt(rows.get(0).getSsnLast4())).isEqualTo("1122");
        assertThat(afterRotation.decrypt(rows.get(1).getSsnLast4())).isEqualTo("4455");
        assertThat(rows.get(3).getSsnLast4()).as("current-key row untouched").isEqualTo(currentBefore);
        assertThat(rows.get(4).getSsnLast4()).as("unreadable row untouched").isEqualTo("not-a-ciphertext");
        verify(repo, times(2)).save(any());
        ArgumentCaptor<PayrollAuditLog> cap = ArgumentCaptor.forClass(PayrollAuditLog.class);
        verify(audit, times(1)).save(cap.capture());   // only CHR-A had rotated rows
        PayrollAuditLog row = cap.getValue();
        assertThat(row.getAppClientId()).isEqualTo("CHR-A");
        assertThat(row.getAction()).isEqualTo("SSN_KEY_ROTATED");
        assertThat(row.getActor()).isEqualTo("admin@cgp");
        assertThat(row.getEntityId()).isNull();
        assertThat(row.getDetails()).contains("2 SSN last-4").doesNotContain("1122").doesNotContain("4455");
        // second run is a no-op
        Map<String, Object> again = svc.rotate(false, "admin@cgp");
        assertThat(again).containsEntry("rotated", 0).containsEntry("alreadyOnCurrentKey", 3).containsEntry("pending", 0);
        verify(audit, times(1)).save(any());
    }

    @Test @DisplayName("refuses when the current key is the derived development key")
    void refusesWithoutConfiguredKey() {
        emp(1L, "CHR-A", "1122");
        Map<String, Object> out = new SsnRotationService(repo, audit, new SsnCrypto("", ""), null).rotate(false, "x");
        assertThat(out).containsEntry("status", "error");
        assertThat(String.valueOf(out.get("message"))).contains("PAYROLL_SSN_ENC_KEY");
        verify(repo, never()).save(any());
    }

    @Test @DisplayName("one failing row does not stop the others and is counted unreadable")
    void oneBadRowDoesNotAbort() {
        SsnCrypto c = seedProductionShape();
        when(repo.findById(1L)).thenReturn(Optional.empty());   // row vanished mid-run
        Map<String, Object> out = new SsnRotationService(repo, audit, c, null).rotate(false, null);
        assertThat(out).containsEntry("rotated", 1).containsEntry("unreadable", 2);
        assertThat(new SsnCrypto(KEY_NEW, "").decrypt(rows.get(1).getSsnLast4())).isEqualTo("4455");
    }

    @Test @DisplayName("logs carry ids and counts only — never a value")
    void logsNoValues() {
        SsnCrypto c = seedProductionShape();
        new SsnRotationService(repo, audit, c, null).rotate(false, null);
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent ev : appender.list) {
            assertThat(ev.getFormattedMessage()).doesNotContain("1122").doesNotContain("4455").doesNotContain("9900").doesNotContain(KEY_NEW);
        }
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)).anyMatch(m -> m.contains("employee 5") && m.contains("neither key"));
    }
}
