package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Financial audit H9. Two pieces of {@link SourceService} that {@link
 * DonationIncomePostingService} depends on:
 * <ul>
 *   <li>the "Online Giving" &rarr; "Online Donations" category a posted
 *       donation is filed under is created once per church, on first use,
 *       and reused after that — never duplicated, even under a race (there
 *       is no unique database constraint on a source's name); and</li>
 *   <li>a sub-source's tax-deductible flag can be toggled independently of
 *       its name.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SourceServiceOnlineDonationsTest {

    private static final String CLIENT = "CHR-1";
    private static final String MAIN_NAME = "Online Giving";
    private static final String SUB_NAME  = "Online Donations";

    @Mock MainSourceRepository mainRepo;
    @Mock SubSourceRepository  subRepo;

    private SourceService service;

    @BeforeEach
    void setUp() {
        service = new SourceService(mainRepo, subRepo);
    }

    @Nested
    @DisplayName("find-or-create the online-donations category")
    class FindOrCreate {

        @Test
        @DisplayName("creates both the main and sub source on first use, tax-deductible by default")
        void createsOnFirstUse() {
            MainSource main = new MainSource();
            main.setId(10);
            main.setSourceName(MAIN_NAME);

            when(mainRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(MAIN_NAME, CLIENT))
                    .thenReturn(Optional.empty());
            when(mainRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(MAIN_NAME, CLIENT))
                    .thenReturn(false);
            when(mainRepo.save(any(MainSource.class))).thenReturn(main);
            // createSubSource validates the parent exists before creating the child.
            when(mainRepo.findByIdAndAppClientIdAndDeleteFlagFalse(10, CLIENT)).thenReturn(Optional.of(main));

            when(subRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(SUB_NAME, CLIENT))
                    .thenReturn(Optional.empty());
            when(subRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndMainSource_Id(SUB_NAME, 10))
                    .thenReturn(false);
            when(subRepo.save(any(SubSource.class))).thenAnswer(inv -> {
                SubSource s = inv.getArgument(0);
                s.setId(20);
                return s;
            });

            SubSource result = service.findOrCreateOnlineDonationsSubSource(CLIENT);

            assertThat(result.getId()).isEqualTo(20);
            assertThat(result.getSourceName()).isEqualTo(SUB_NAME);
            assertThat(result.getMainSource().getSourceName()).isEqualTo(MAIN_NAME);
            // The Java-side field default — every category this creates is a real gift.
            assertThat(result.isTaxDeductible()).isTrue();
            verify(mainRepo, times(1)).save(any(MainSource.class));
            verify(subRepo, times(1)).save(any(SubSource.class));
        }

        @Test
        @DisplayName("reuses the existing category on a later call — never creates a second one")
        void reusesExisting() {
            MainSource main = new MainSource();
            main.setId(10);
            main.setSourceName(MAIN_NAME);
            SubSource sub = new SubSource();
            sub.setId(20);
            sub.setSourceName(SUB_NAME);
            sub.setMainSource(main);

            when(mainRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(MAIN_NAME, CLIENT))
                    .thenReturn(Optional.of(main));
            when(subRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(SUB_NAME, CLIENT))
                    .thenReturn(Optional.of(sub));

            SubSource result = service.findOrCreateOnlineDonationsSubSource(CLIENT);

            assertThat(result.getId()).isEqualTo(20);
            verify(mainRepo, never()).save(any());
            verify(subRepo, never()).save(any());
        }

        @Test
        @DisplayName("a concurrent first-ever create falls back to the winner's row instead of failing")
        void racedCreateFallsBackToExistingRow() {
            MainSource winner = new MainSource();
            winner.setId(11);
            winner.setSourceName(MAIN_NAME);

            // First check (before attempting to create) finds nothing; the
            // re-query after the failed create finds the other request's row.
            when(mainRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(MAIN_NAME, CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(winner));
            when(mainRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(MAIN_NAME, CLIENT))
                    .thenReturn(false);
            when(mainRepo.save(any(MainSource.class)))
                    .thenThrow(new DataIntegrityViolationException("concurrent insert"));
            when(mainRepo.findByIdAndAppClientIdAndDeleteFlagFalse(11, CLIENT)).thenReturn(Optional.of(winner));

            when(subRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(SUB_NAME, CLIENT))
                    .thenReturn(Optional.empty());
            when(subRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndMainSource_Id(SUB_NAME, 11))
                    .thenReturn(false);
            when(subRepo.save(any(SubSource.class))).thenAnswer(inv -> {
                SubSource s = inv.getArgument(0);
                s.setId(21);
                return s;
            });

            SubSource result = service.findOrCreateOnlineDonationsSubSource(CLIENT);

            assertThat(result.getMainSource().getId()).isEqualTo(11);
            assertThat(result.getId()).isEqualTo(21);
        }
    }

    @Nested
    @DisplayName("tax-deductible flag")
    class TaxDeductibleFlag {

        @Test
        @DisplayName("setTaxDeductible flips the flag and saves")
        void setsAndSaves() {
            SubSource ss = new SubSource();
            ss.setId(5);
            ss.setSourceName("Facility Rental");
            ss.setAppClientId(CLIENT);
            when(subRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, CLIENT)).thenReturn(Optional.of(ss));
            when(subRepo.save(any(SubSource.class))).thenAnswer(inv -> inv.getArgument(0));

            SubSource result = service.setTaxDeductible(5, false, CLIENT);

            assertThat(result.isTaxDeductible()).isFalse();
            verify(subRepo).save(ss);
        }

        @Test
        @DisplayName("an unknown or another church's id is rejected like any other source lookup")
        void unknownIdRejected() {
            when(subRepo.findByIdAndAppClientIdAndDeleteFlagFalse(99, CLIENT)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> service.setTaxDeductible(99, true, CLIENT));
        }
    }
}
