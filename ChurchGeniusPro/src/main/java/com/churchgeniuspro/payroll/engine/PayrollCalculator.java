package com.churchgeniuspro.payroll.engine;

import com.churchgeniuspro.payroll.config.FederalWithholdingConfig;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.config.StateWithholdingConfig;
import com.churchgeniuspro.payroll.model.FilingStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, stateless payroll calculation engine. Given one employee's inputs for a
 * single pay period plus the effective tax configuration, it produces a fully
 * itemized {@link PayrollCalculationResult}.
 *
 * <p>Federal income-tax withholding follows IRS Publication 15-T, Worksheet 1A
 * (Percentage Method for Automated Payroll Systems), for 2020-or-later Forms W-4:
 * <pre>
 *   annualWage      = periodFederalTaxableWage × payPeriodsPerYear
 *                     + W-4 Step 4(a) − W-4 Step 4(b)
 *   annualTax       = federalSchedule(annualWage, status, step2)   // applies the
 *                                                                   // standard-deduction add-back
 *   annualTax       = max(0, annualTax − W-4 Step 3 credits)
 *   perPeriodFed    = annualTax / payPeriodsPerYear
 *   federalWH       = max(0, perPeriodFed + W-4 Step 4(c))
 * </pre>
 *
 * <p>FICA uses prior year-to-date FICA wages to honor the Social Security wage
 * base and the Additional Medicare threshold. State withholding is applied only
 * when a {@link StateWithholdingConfig} with an income tax is supplied.
 *
 * <p>The engine has no Spring or JPA dependencies, so it is trivially
 * unit-testable; the service layer maps entities into {@link PayrollCalculationInput}.
 */
public final class PayrollCalculator {

    private PayrollCalculator() {}

    public static PayrollCalculationResult calculate(PayrollCalculationInput in,
                                                     FederalWithholdingConfig federal,
                                                     FicaConfig fica,
                                                     StateWithholdingConfig state) {
        PayrollCalculationResult r = new PayrollCalculationResult();
        int periods = in.payPeriodsPerYear();

        // ── 1. Gross earnings ──────────────────────────────────────────────
        BigDecimal gross = BigDecimal.ZERO;
        for (EarningLine e : in.getEarnings()) {
            gross = gross.add(e.amountOrZero());
            r.getEarningItems().add(new PaystubLineItem(
                    label(e), money(e.amountOrZero()), null));
        }

        // ── 2. Deduction partitioning ──────────────────────────────────────
        BigDecimal preTaxTotal = BigDecimal.ZERO;
        BigDecimal postTaxTotal = BigDecimal.ZERO;
        BigDecimal federalExempt = BigDecimal.ZERO; // pre-tax that lowers federal taxable
        BigDecimal ficaExempt = BigDecimal.ZERO;    // pre-tax that lowers FICA wages
        BigDecimal stateExempt = BigDecimal.ZERO;   // pre-tax that lowers state taxable

        for (DeductionLine d : in.getDeductions()) {
            BigDecimal amt = d.amountOrZero();
            if (d.isPreTax()) {
                preTaxTotal = preTaxTotal.add(amt);
                if (d.isReducesFederalTaxable()) federalExempt = federalExempt.add(amt);
                if (d.isReducesFicaWages())      ficaExempt = ficaExempt.add(amt);
                if (d.isReducesStateTaxable())   stateExempt = stateExempt.add(amt);
                r.getPreTaxItems().add(new PaystubLineItem(d.getName(), money(amt), null));
            } else {
                postTaxTotal = postTaxTotal.add(amt);
                r.getPostTaxItems().add(new PaystubLineItem(d.getName(), money(amt), null));
            }
        }

        // ── 3. Tax bases for this period ───────────────────────────────────
        BigDecimal federalTaxable = floorZero(gross.subtract(federalExempt));
        BigDecimal ficaWage       = floorZero(gross.subtract(ficaExempt));
        BigDecimal stateTaxable   = floorZero(gross.subtract(stateExempt));

        // ── 4. Federal income-tax withholding (Worksheet 1A) ───────────────
        W4Input w4 = in.getW4();
        FilingStatus status = w4.getFilingStatus() == null ? FilingStatus.SINGLE : w4.getFilingStatus();
        BigDecimal annualWage = federalTaxable.multiply(BigDecimal.valueOf(periods))
                .add(w4.step4aOrZero())
                .subtract(w4.step4bOrZero());
        BigDecimal annualTax = federal.annualWithholding(annualWage, status, w4.isStep2Checked());
        annualTax = floorZero(annualTax.subtract(w4.step3OrZero()));
        BigDecimal perPeriodFed = annualTax.divide(BigDecimal.valueOf(periods), 2, RoundingMode.HALF_UP);
        BigDecimal federalWH = floorZero(perPeriodFed.add(w4.step4cOrZero()));
        federalWH = money(federalWH);

        // ── 5. FICA ────────────────────────────────────────────────────────
        BigDecimal ytdFicaBefore = in.getPriorYtd().getFicaWagesOrZero();
        BigDecimal ss          = money(fica.socialSecurity(ficaWage, ytdFicaBefore));
        BigDecimal medicare    = money(fica.medicare(ficaWage));
        BigDecimal addlMedicare= money(fica.additionalMedicare(ficaWage, ytdFicaBefore));

        // ── 6. State + local ───────────────────────────────────────────────
        BigDecimal stateWH = BigDecimal.ZERO;
        BigDecimal localTax = BigDecimal.ZERO;
        if (state != null && state.isHasIncomeTax()) {
            BigDecimal annualStateWage = stateTaxable.multiply(BigDecimal.valueOf(periods));
            BigDecimal annualStateTax = state.annualWithholding(annualStateWage, status);
            stateWH = money(annualStateTax.divide(BigDecimal.valueOf(periods), 2, RoundingMode.HALF_UP));
            localTax = money(state.localTax(stateTaxable));
        }

        // ── 7. Totals and net pay ──────────────────────────────────────────
        BigDecimal totalTaxes = federalWH.add(ss).add(medicare).add(addlMedicare).add(stateWH).add(localTax);
        BigDecimal netPay = money(gross.subtract(preTaxTotal).subtract(totalTaxes).subtract(postTaxTotal));

        // ── 8. Populate result ─────────────────────────────────────────────
        r.setGrossEarnings(money(gross));
        r.setFederalTaxableWages(money(federalTaxable));
        r.setFicaWages(money(ficaWage));
        r.setStateTaxableWages(money(stateTaxable));
        r.setTotalPreTaxDeductions(money(preTaxTotal));
        r.setTotalPostTaxDeductions(money(postTaxTotal));
        r.setFederalWithholding(federalWH);
        r.setSocialSecurity(ss);
        r.setMedicare(medicare);
        r.setAdditionalMedicare(addlMedicare);
        r.setStateWithholding(stateWH);
        r.setLocalTax(localTax);
        r.setTotalTaxes(money(totalTaxes));
        r.setNetPay(netPay);

        // Roll YTD forward first so tax line items can show YTD figures.
        YtdAmounts newYtd = in.getPriorYtd().plus(r);
        r.setNewYtd(newYtd);

        r.getTaxItems().add(new PaystubLineItem("Federal income tax", federalWH, newYtd.getFederalWithholding()));
        r.getTaxItems().add(new PaystubLineItem("Social Security",     ss,        newYtd.getSocialSecurity()));
        r.getTaxItems().add(new PaystubLineItem("Medicare",            medicare,  newYtd.getMedicare()));
        if (addlMedicare.signum() > 0) {
            r.getTaxItems().add(new PaystubLineItem("Additional Medicare", addlMedicare, newYtd.getAdditionalMedicare()));
        }
        if (stateWH.signum() > 0) {
            r.getTaxItems().add(new PaystubLineItem("State income tax", stateWH, newYtd.getStateWithholding()));
        }
        if (localTax.signum() > 0) {
            r.getTaxItems().add(new PaystubLineItem("Local tax", localTax, newYtd.getLocalTax()));
        }
        return r;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String label(EarningLine e) {
        if (e.getDescription() != null && !e.getDescription().isEmpty()) return e.getDescription();
        return e.getType() == null ? "Earnings" : e.getType().name();
    }

    private static BigDecimal floorZero(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
