package com.churchgeniuspro.payroll.config;

import com.churchgeniuspro.payroll.model.FilingStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Seeded federal withholding + FICA configuration for tax year <b>2026</b>.
 *
 * <p><b>Sources (verified at build time):</b>
 * <ul>
 *   <li>Federal income-tax percentage-method schedules (automated payroll
 *       systems, Worksheet 1A): IRS Publication 15-T (2026), reflecting the
 *       One Big Beautiful Bill Act (P.L. 119-21). Bracket figures cross-checked
 *       against a Pub 15-T reproduction; the standard-deduction add-back
 *       ($12,900 MFJ / $8,600 other, line 1g) plus each Standard schedule's
 *       zero-tax threshold equals exactly twice the corresponding Step-2-checkbox
 *       threshold, an internal consistency check that the figures satisfy.</li>
 *   <li>Social Security wage base $184,500 and 6.2% rate; Medicare 1.45%;
 *       Additional Medicare 0.9% over $200,000: IRS / SSA 2026 figures.</li>
 * </ul>
 *
 * <p>This class only <em>constructs</em> the configuration objects; persistence
 * and effective-dated lookup live in the service/entity layer. To add a future
 * year, add an analogous factory (or seed the tax-config tables) — no engine
 * changes are required.
 */
public final class Federal2026TaxData {

    public static final int YEAR = 2026;

    private Federal2026TaxData() {}

    // ── Federal income tax withholding ────────────────────────────────────────

    public static FederalWithholdingConfig federalWithholding() {
        Map<FilingStatus, BigDecimal> addBack = new EnumMap<>(FilingStatus.class);
        addBack.put(FilingStatus.MARRIED_FILING_JOINTLY, bd(12_900));
        addBack.put(FilingStatus.SINGLE,                 bd(8_600));
        addBack.put(FilingStatus.HEAD_OF_HOUSEHOLD,      bd(8_600));

        Map<FilingStatus, TaxRateSchedule> standard = new EnumMap<>(FilingStatus.class);
        standard.put(FilingStatus.MARRIED_FILING_JOINTLY, schedule(new double[][] {
                {0,        19_300,   0,        0.00},
                {19_300,   44_100,   0,        0.10},
                {44_100,   120_100,  2_480,    0.12},
                {120_100,  230_700,  11_600,   0.22},
                {230_700,  422_850,  35_932,   0.24},
                {422_850,  531_750,  82_048,   0.32},
                {531_750,  788_000,  116_896,  0.35},
                {788_000,  -1,       206_584,  0.37},
        }));
        standard.put(FilingStatus.SINGLE, schedule(new double[][] {
                {0,        7_500,    0,        0.00},
                {7_500,    19_900,   0,        0.10},
                {19_900,   57_900,   1_240,    0.12},
                {57_900,   113_200,  5_800,    0.22},
                {113_200,  209_275,  17_966,   0.24},
                {209_275,  263_725,  41_024,   0.32},
                {263_725,  648_100,  58_448,   0.35},
                {648_100,  -1,       192_979,  0.37},
        }));
        standard.put(FilingStatus.HEAD_OF_HOUSEHOLD, schedule(new double[][] {
                {0,        15_550,   0,        0.00},
                {15_550,   33_250,   0,        0.10},
                {33_250,   83_000,   1_770,    0.12},
                {83_000,   121_250,  7_740,    0.22},
                {121_250,  217_300,  16_155,   0.24},
                {217_300,  271_750,  39_207,   0.32},
                {271_750,  656_150,  56_631,   0.35},
                {656_150,  -1,       191_171,  0.37},
        }));

        Map<FilingStatus, TaxRateSchedule> step2 = new EnumMap<>(FilingStatus.class);
        step2.put(FilingStatus.MARRIED_FILING_JOINTLY, schedule(new double[][] {
                {0,        16_100,   0,        0.00},
                {16_100,   28_500,   0,        0.10},
                {28_500,   66_500,   1_240,    0.12},
                {66_500,   121_800,  5_800,    0.22},
                {121_800,  217_875,  17_966,   0.24},
                {217_875,  272_325,  41_024,   0.32},
                {272_325,  400_450,  58_448,   0.35},
                {400_450,  -1,       103_292,  0.37},
        }));
        step2.put(FilingStatus.SINGLE, schedule(new double[][] {
                {0,        8_050,    0,        0.00},
                {8_050,    14_250,   0,        0.10},
                {14_250,   33_250,   620,      0.12},
                {33_250,   60_900,   2_900,    0.22},
                {60_900,   108_938,  8_983,    0.24},
                {108_938,  136_163,  20_512,   0.32},
                {136_163,  328_350,  29_224,   0.35},
                {328_350,  -1,       96_490,   0.37},
        }));
        step2.put(FilingStatus.HEAD_OF_HOUSEHOLD, schedule(new double[][] {
                {0,        12_075,   0,        0.00},
                {12_075,   20_925,   0,        0.10},
                {20_925,   45_800,   885,      0.12},
                {45_800,   64_925,   3_870,    0.22},
                {64_925,   112_950,  8_078,    0.24},
                {112_950,  140_175,  19_604,   0.32},
                {140_175,  332_375,  28_316,   0.35},
                {332_375,  -1,       95_586,   0.37},
        }));

        return new FederalWithholdingConfig(YEAR, addBack, standard, step2);
    }

    // ── FICA ──────────────────────────────────────────────────────────────────

    public static FicaConfig fica() {
        return new FicaConfig(
                YEAR,
                new BigDecimal("0.062"),     // Social Security employee rate
                bd(184_500),                 // Social Security wage base
                new BigDecimal("0.0145"),    // Medicare employee rate
                new BigDecimal("0.009"),     // Additional Medicare rate
                bd(200_000));                // Additional Medicare threshold
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static TaxRateSchedule schedule(double[][] rows) {
        List<com.churchgeniuspro.payroll.config.TaxBracket> brackets = new ArrayList<>();
        for (double[] r : rows) {
            brackets.add(TaxBracket.of(r[0], r[1], r[2], r[3]));
        }
        return new TaxRateSchedule(brackets);
    }

    private static BigDecimal bd(long v) { return BigDecimal.valueOf(v); }

    /** The schedule groups that are seeded (MFS maps onto SINGLE). */
    public static List<FilingStatus> seededGroups() {
        return Arrays.asList(FilingStatus.MARRIED_FILING_JOINTLY, FilingStatus.SINGLE,
                FilingStatus.HEAD_OF_HOUSEHOLD);
    }
}
