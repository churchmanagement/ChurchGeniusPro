package com.churchgeniuspro.payroll.model;

/**
 * Whether a deduction is taken from pay before or after taxes are computed.
 *
 * <p>Note that <em>scope</em> (pre/post tax) is distinct from <em>which</em>
 * taxes a pre-tax deduction is exempt from. A 401(k) deferral and a Section 125
 * medical premium are both {@link #PRE_TAX}, but the 401(k) is exempt only from
 * federal/state income tax (still subject to Social Security and Medicare),
 * while the Section 125 premium is exempt from income tax <em>and</em> FICA.
 * That distinction is captured by the per-deduction exemption flags on
 * {@code DeductionInput} / {@code DeductionDefinition}.
 */
public enum DeductionScope {
    PRE_TAX,
    POST_TAX
}
