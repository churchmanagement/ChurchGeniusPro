package com.churchgeniuspro.payroll.engine;

import com.churchgeniuspro.payroll.model.DeductionScope;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A single deduction line for one pay period. The {@code reduces*} flags control
 * which tax bases this deduction lowers — the key distinction that makes a
 * Section 125 premium differ from a 401(k) deferral:
 *
 * <ul>
 *   <li><b>Medical / dental / vision (Section 125)</b>: pre-tax, reduces federal,
 *       state, <em>and</em> FICA wages.</li>
 *   <li><b>401(k) traditional deferral</b>: pre-tax, reduces federal and state
 *       wages but <em>not</em> FICA wages (still subject to Social Security and
 *       Medicare).</li>
 *   <li><b>Post-tax</b> (garnishment, charitable, Roth, etc.): reduces no tax base.</li>
 * </ul>
 */
@Data
public class DeductionLine {

    private String name;
    private DeductionScope scope;
    private BigDecimal amount;
    private boolean reducesFederalTaxable;
    private boolean reducesStateTaxable;
    private boolean reducesFicaWages;

    public DeductionLine() {}

    private DeductionLine(String name, DeductionScope scope, BigDecimal amount,
                          boolean fed, boolean state, boolean fica) {
        this.name = name;
        this.scope = scope;
        this.amount = amount == null ? BigDecimal.ZERO : amount;
        this.reducesFederalTaxable = fed;
        this.reducesStateTaxable = state;
        this.reducesFicaWages = fica;
    }

    /** Section 125 pre-tax benefit (medical/dental/vision): exempt from income tax AND FICA. */
    public static DeductionLine section125(String name, BigDecimal amount) {
        return new DeductionLine(name, DeductionScope.PRE_TAX, amount, true, true, true);
    }

    /** Traditional 401(k)/403(b) deferral: exempt from income tax, NOT from FICA. */
    public static DeductionLine retirement401k(String name, BigDecimal amount) {
        return new DeductionLine(name, DeductionScope.PRE_TAX, amount, true, true, false);
    }

    /** Generic pre-tax deduction with explicit exemption flags. */
    public static DeductionLine preTax(String name, BigDecimal amount,
                                       boolean fed, boolean state, boolean fica) {
        return new DeductionLine(name, DeductionScope.PRE_TAX, amount, fed, state, fica);
    }

    /** Post-tax deduction (garnishment, charitable, union dues, Roth): no tax effect. */
    public static DeductionLine postTax(String name, BigDecimal amount) {
        return new DeductionLine(name, DeductionScope.POST_TAX, amount, false, false, false);
    }

    public BigDecimal amountOrZero() {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    public boolean isPreTax() { return scope == DeductionScope.PRE_TAX; }
}
