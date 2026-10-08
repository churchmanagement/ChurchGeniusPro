package com.churchgeniuspro.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit P4: production's monthly backup last ran on 2026-07-29, so the next was
 * due on 2026-08-29 — and a settings save on 2026-08-17 moved it to 2026-09-17, because the
 * next run was recomputed from the day of the save. August had no backup, and nothing
 * newer than July existed when the audit ran. The schedule now counts from the last run.
 */
@DisplayName("BackupService.nextRunAfterSave — saving the settings never postpones a due backup (DB audit P4)")
class BackupScheduleTest {

    @Test
    @DisplayName("production's case: last run 29 Jul, monthly, saved on 17 Aug → still due 29 Aug (was: 17 Sep)")
    void savingSettingsKeepsTheScheduleAnchoredToTheLastRun() {
        LocalDate next = BackupService.nextRunAfterSave(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 7, 29), 1);

        assertThat(next).isEqualTo(LocalDate.of(2026, 8, 29));
    }

    @Test
    @DisplayName("a backup that is already overdue when the settings are saved becomes due today, not a full interval later")
    void overdueBackupIsDueToday() {
        LocalDate today = LocalDate.of(2026, 9, 16);

        assertThat(BackupService.nextRunAfterSave(today, LocalDate.of(2026, 7, 29), 1)).isEqualTo(today);
    }

    @Test
    @DisplayName("exactly on the due day it stays the due day")
    void dueTodayStaysToday() {
        LocalDate today = LocalDate.of(2026, 8, 29);

        assertThat(BackupService.nextRunAfterSave(today, LocalDate.of(2026, 7, 29), 1)).isEqualTo(today);
    }

    @Test
    @DisplayName("a backup that has never run is scheduled one interval from today, as before")
    void neverRunStartsFromToday() {
        LocalDate today = LocalDate.of(2026, 9, 16);

        assertThat(BackupService.nextRunAfterSave(today, null, 3)).isEqualTo(LocalDate.of(2026, 12, 16));
    }

    @Test
    @DisplayName("changing the interval re-derives the next run from the last run")
    void intervalChangeCountsFromLastRun() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate lastRun = LocalDate.of(2026, 7, 29);

        assertThat(BackupService.nextRunAfterSave(today, lastRun, 3)).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(BackupService.nextRunAfterSave(today, lastRun, 6)).isEqualTo(LocalDate.of(2027, 1, 29));
    }

    @Test
    @DisplayName("interval 0 (or less) disables the schedule")
    void zeroIntervalDisables() {
        assertThat(BackupService.nextRunAfterSave(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 7, 29), 0)).isNull();
        assertThat(BackupService.nextRunAfterSave(LocalDate.of(2026, 9, 16), null, -1)).isNull();
    }

    @Test
    @DisplayName("month arithmetic clamps to real dates (31 Jan + 1 month = 28 Feb)")
    void monthEndIsClamped() {
        assertThat(BackupService.nextRunAfterSave(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 1, 31), 1))
                .isEqualTo(LocalDate.of(2027, 2, 28));
    }
}
