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

    /** FICA config for a year, from DB; falls back to seeded 2026 data. */
    public FicaConfig loadFica(int year) {
        Optional<FicaRate> row = ficaRepo.findByEffectiveYear(year);
        if (row.isEmpty()) {
            if (year == Federal2026TaxData.YEAR) return Federal2026TaxData.fica();
            throw new IllegalStateException("No FICA rates configured for year " + year);
        }
        FicaRate r = row.get();
        return new FicaConfig(year, r.getSocialSecurityRate(), r.getSocialSecurityWageBase(),
                r.getMedicareRate(), r.getAdditionalMedicareRate(), r.getAdditionalMedicareThreshold());
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
