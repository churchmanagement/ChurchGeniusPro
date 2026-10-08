package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.config.*;
import com.churchgeniuspro.payroll.entity.*;
import com.churchgeniuspro.payroll.model.FilingStatus;
import com.churchgeniuspro.payroll.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads effective-dated tax configuration from the database into the pure
 * {@code com.churchgeniuspro.payroll.config} objects the engine consumes, and
 * seeds the verified 2026 federal/FICA tables on first use.
 *
 * <p>This is the seam that makes rates configurable: change a row (or add a new
 * effective year) and the engine picks it up — no recompilation. If a year has
 * not been seeded into the DB yet, federal/FICA loads fall back to the in-code
 * {@link Federal2026TaxData} for 2026 so the system is usable out of the box.
 * The one exception is the 2026 FICA row itself: its values are statutory and
 * this build seeds them, so a stored row that disagrees with them is refused
 * (see {@link #loadFica}) rather than trusted — database audit C1.
 */
@Service
public class TaxConfigService {

    private static final Logger log = LoggerFactory.getLogger(TaxConfigService.class);

    private final FederalTaxBracketRepository federalBracketRepo;
    private final FederalStandardDeductionRepository stdDeductionRepo;
    private final FicaRateRepository ficaRepo;
    private final StateTaxConfigRepository stateConfigRepo;
    private final StateTaxBracketRepository stateBracketRepo;

    public TaxConfigService(FederalTaxBracketRepository federalBracketRepo,
                            FederalStandardDeductionRepository stdDeductionRepo,
                            FicaRateRepository ficaRepo,
                            StateTaxConfigRepository stateConfigRepo,
                            StateTaxBracketRepository stateBracketRepo) {
        this.federalBracketRepo = federalBracketRepo;
        this.stdDeductionRepo = stdDeductionRepo;
        this.ficaRepo = ficaRepo;
        this.stateConfigRepo = stateConfigRepo;
        this.stateBracketRepo = stateBracketRepo;
    }

    // ── Seeding ────────────────────────────────────────────────────────────

    /** Writes the verified 2026 federal + FICA tables to the DB if absent. Idempotent. */
    @Transactional
    public void seed2026IfAbsent() {
        if (!federalBracketRepo.existsByEffectiveYear(Federal2026TaxData.YEAR)) {
            FederalWithholdingConfig fed = Federal2026TaxData.federalWithholding();
            persistSchedules(Federal2026TaxData.YEAR, "STANDARD", fed.getStandardSchedules());
            persistSchedules(Federal2026TaxData.YEAR, "STEP2", fed.getStep2CheckboxSchedules());
            for (Map.Entry<FilingStatus, BigDecimal> e : fed.getStandardDeductionAddBack().entrySet()) {
                FederalStandardDeduction sd = new FederalStandardDeduction();
                sd.setEffectiveYear(Federal2026TaxData.YEAR);
                sd.setFilingGroup(groupCode(e.getKey()));
                sd.setAmount(e.getValue());
                stdDeductionRepo.save(sd);
            }
            log.info("TaxConfigService: seeded 2026 federal withholding tables");
        }
        if (ficaRepo.findByEffectiveYear(Federal2026TaxData.YEAR).isEmpty()) {
            FicaConfig f = Federal2026TaxData.fica();
            FicaRate row = new FicaRate();
            row.setEffectiveYear(Federal2026TaxData.YEAR);
            row.setSocialSecurityRate(f.getSocialSecurityRate());
            row.setSocialSecurityWageBase(f.getSocialSecurityWageBase());
            row.setMedicareRate(f.getMedicareRate());
            row.setAdditionalMedicareRate(f.getAdditionalMedicareRate());
            row.setAdditionalMedicareThreshold(f.getAdditionalMedicareThreshold());
            ficaRepo.save(row);
            log.info("TaxConfigService: seeded 2026 FICA rates");
        }
    }

    private void persistSchedules(int year, String type, Map<FilingStatus, TaxRateSchedule> schedules) {
        for (Map.Entry<FilingStatus, TaxRateSchedule> e : schedules.entrySet()) {
            String group = groupCode(e.getKey());
            int order = 0;
            for (TaxBracket b : e.getValue().getBrackets()) {
                FederalTaxBracket row = new FederalTaxBracket();
                row.setEffectiveYear(year);
                row.setScheduleType(type);
                row.setFilingGroup(group);
                row.setLowerBound(b.getLowerBound());
                row.setUpperBound(b.getUpperBound());
                row.setBaseTax(b.getBase());
                row.setRate(b.getRate());
                row.setSortOrder(order++);
                federalBracketRepo.save(row);
            }
        }
    }

    // ── Loading into pure config ───────────────────────────────────────────

    /** Federal withholding config for a year, from DB; falls back to seeded 2026 data. */
    public FederalWithholdingConfig loadFederal(int year) {
        List<FederalTaxBracket> rows = federalBracketRepo.findByEffectiveYearOrderBySortOrderAsc(year);
        if (rows.isEmpty()) {
            if (year == Federal2026TaxData.YEAR) return Federal2026TaxData.federalWithholding();
            throw new IllegalStateException("No federal withholding tables configured for year " + year);
        }
        Map<FilingStatus, List<TaxBracket>> std = new EnumMap<>(FilingStatus.class);
        Map<FilingStatus, List<TaxBracket>> step2 = new EnumMap<>(FilingStatus.class);
        for (FederalTaxBracket r : rows) {
            FilingStatus group = groupOf(r.getFilingGroup());
            TaxBracket b = new TaxBracket(r.getLowerBound(), r.getUpperBound(), r.getBaseTax(), r.getRate());
            ("STEP2".equalsIgnoreCase(r.getScheduleType()) ? step2 : std)
                    .computeIfAbsent(group, k -> new ArrayList<>()).add(b);
        }
        Map<FilingStatus, TaxRateSchedule> stdSched = toSchedules(std);
        Map<FilingStatus, TaxRateSchedule> step2Sched = toSchedules(step2);

        Map<FilingStatus, BigDecimal> addBack = new EnumMap<>(FilingStatus.class);
        for (FederalStandardDeduction sd : stdDeductionRepo.findByEffectiveYear(year)) {
            addBack.put(groupOf(sd.getFilingGroup()), sd.getAmount());
        }
        return new FederalWithholdingConfig(year, addBack, stdSched, step2Sched);
    }

    /**
     * FICA config for a year, from DB; falls back to seeded 2026 data.
     *
     * <p>For 2026 — the year whose statutory values this build carries in
     * {@link Federal2026TaxData} and seeds from — a stored row that differs from them is
     * refused rather than used. Database audit C1: the rate columns were created as
     * {@code numeric(38,2)}, so the seed came back as {@code 0.06 / 0.01 / 0.01} and every
     * paystub withheld at those rates while the application looked healthy. The startup
     * repair in {@code SchemaFixService} corrects that exact damage; anything it could not
     * correct must stop payroll, loudly, instead of reaching another paystub.
     *
     * @throws IllegalStateException when no rates exist for a year other than 2026, or when
     *         the stored 2026 row does not match the statutory 2026 values
     */
    public FicaConfig loadFica(int year) {
        Optional<FicaRate> row = ficaRepo.findByEffectiveYear(year);
        if (row.isEmpty()) {
            if (year == Federal2026TaxData.YEAR) return Federal2026TaxData.fica();
            throw new IllegalStateException("No FICA rates configured for year " + year);
        }
        FicaRate r = row.get();
        FicaConfig stored = new FicaConfig(year, r.getSocialSecurityRate(), r.getSocialSecurityWageBase(),
                r.getMedicareRate(), r.getAdditionalMedicareRate(), r.getAdditionalMedicareThreshold());
        if (year == Federal2026TaxData.YEAR) {
            requireStatutory(stored, Federal2026TaxData.fica());
        }
        return stored;
    }

    /** Refuses a stored FICA row whose values differ from the statutory ones for that year. */
    static void requireStatutory(FicaConfig stored, FicaConfig statutory) {
        List<String> wrong = new ArrayList<>();
        compare(wrong, "Social Security rate", stored.getSocialSecurityRate(), statutory.getSocialSecurityRate());
        compare(wrong, "Medicare rate", stored.getMedicareRate(), statutory.getMedicareRate());
        compare(wrong, "Additional Medicare rate", stored.getAdditionalMedicareRate(), statutory.getAdditionalMedicareRate());
        compare(wrong, "Social Security wage base", stored.getSocialSecurityWageBase(), statutory.getSocialSecurityWageBase());
        compare(wrong, "Additional Medicare threshold", stored.getAdditionalMedicareThreshold(), statutory.getAdditionalMedicareThreshold());
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("The FICA rates stored for " + statutory.getEffectiveYear()
                    + " differ from the statutory values (" + String.join("; ", wrong)
                    + "). Payroll cannot run until payroll_fica_rate is corrected — the application repairs "
                    + "the two-decimal rounding at startup; otherwise see migrate_production.sql, "
                    + "'Database audit C1'.");
        }
    }

    private static void compare(List<String> wrong, String label, BigDecimal stored, BigDecimal statutory) {
        if (stored == null || stored.compareTo(statutory) != 0) {
            wrong.add(label + " is " + (stored == null ? "missing" : stored.stripTrailingZeros().toPlainString())
                    + " instead of " + statutory.toPlainString());
        }
    }

    /**
     * State withholding config for a year + state. Returns a no-income-tax config
     * when nothing is configured for that state (no state is seeded by default).
     */
    public StateWithholdingConfig loadState(int year, String stateCode) {
        if (stateCode == null || stateCode.isBlank()) return StateWithholdingConfig.none("");
        Optional<StateTaxConfig> cfgOpt = stateConfigRepo.findByEffectiveYearAndStateCode(year, stateCode);
        if (cfgOpt.isEmpty() || !cfgOpt.get().isHasIncomeTax()) {
            return StateWithholdingConfig.none(stateCode);
        }
        StateTaxConfig cfg = cfgOpt.get();
        List<StateTaxBracket> brackets = stateBracketRepo.findByStateConfigIdOrderBySortOrderAsc(cfg.getId());

        Map<FilingStatus, TaxRateSchedule> byStatus = new EnumMap<>(FilingStatus.class);
        TaxRateSchedule defaultSchedule;
        if (brackets.isEmpty()) {
            // Flat-rate state.
            defaultSchedule = TaxRateSchedule.flat(cfg.getFlatRate() == null ? 0d : cfg.getFlatRate().doubleValue());
        } else {
            Map<FilingStatus, List<TaxBracket>> grouped = new EnumMap<>(FilingStatus.class);
            List<TaxBracket> shared = new ArrayList<>();
            for (StateTaxBracket sb : brackets) {
                TaxBracket b = new TaxBracket(sb.getLowerBound(), sb.getUpperBound(), sb.getBaseTax(), sb.getRate());
                if (sb.getFilingGroup() == null) shared.add(b);
                else grouped.computeIfAbsent(groupOf(sb.getFilingGroup()), k -> new ArrayList<>()).add(b);
            }
            for (Map.Entry<FilingStatus, List<TaxBracket>> e : grouped.entrySet()) {
                byStatus.put(e.getKey(), new TaxRateSchedule(e.getValue()));
            }
            defaultSchedule = shared.isEmpty() ? null : new TaxRateSchedule(shared);
        }
        return new StateWithholdingConfig(stateCode, true, byStatus, defaultSchedule,
                cfg.getAnnualStandardDeduction(), cfg.getLocalTaxRate());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Map<FilingStatus, TaxRateSchedule> toSchedules(Map<FilingStatus, List<TaxBracket>> in) {
        Map<FilingStatus, TaxRateSchedule> out = new EnumMap<>(FilingStatus.class);
        for (Map.Entry<FilingStatus, List<TaxBracket>> e : in.entrySet()) {
            out.put(e.getKey(), new TaxRateSchedule(e.getValue()));
        }
        return out;
    }

    /** FilingStatus → schedule-group code. */
    public static String groupCode(FilingStatus status) {
        switch (status.scheduleStatus()) {
            case MARRIED_FILING_JOINTLY: return "MFJ";
            case HEAD_OF_HOUSEHOLD:      return "HOH";
            default:                     return "SINGLE";
        }
    }

    /** Schedule-group code → FilingStatus (group representative). */
    public static FilingStatus groupOf(String code) {
        if ("MFJ".equalsIgnoreCase(code))  return FilingStatus.MARRIED_FILING_JOINTLY;
        if ("HOH".equalsIgnoreCase(code))  return FilingStatus.HEAD_OF_HOUSEHOLD;
        return FilingStatus.SINGLE;
    }
}
