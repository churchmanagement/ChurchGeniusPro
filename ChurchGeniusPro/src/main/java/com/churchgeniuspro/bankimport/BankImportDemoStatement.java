package com.churchgeniuspro.bankimport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * A fictional bank statement for trial/demo tenants to try Bank Import with.
 *
 * <p>Bank Import stores nothing until someone imports rows, so "demo data" for it
 * is a statement to upload. This one is generated, never a real export: payees are
 * invented, there is no account or routing number anywhere in it, and the dates
 * are the 30 days before {@code today} so it always looks current. Descriptions use
 * the categorizer's own keywords so every row shows a sensible category and fund.
 */
public final class BankImportDemoStatement {

    private BankImportDemoStatement() {}

    /** File name shown on the page; says plainly that it is sample data. */
    public static final String FILE_NAME = "sample-bank-statement-DEMO.csv";

    // {days before today, description, signed amount}
    private static final Object[][] ROWS = {
        {  1, "Sunday offering deposit",                      "1840.00" },
        {  2, "Online giving payout - DEMO GIVING PLATFORM",   "965.40" },
        {  3, "Metro Electric Utility - autopay",             "-312.18" },
        {  5, "Greenleaf Office Supplies",                     "-86.47" },
        {  7, "Tithe deposit - mobile check",                 "500.00" },
        {  8, "Sunday offering deposit",                      "1625.00" },
        {  9, "Youth retreat registration fees",              "420.00" },
        { 11, "Riverside Water & Sewer Dept",                 "-74.90" },
        { 12, "Payroll - staff wages (DEMO)",                "-2850.00" },
        { 14, "Missions partner support transfer",            "-400.00" },
        { 15, "Sunday offering deposit",                      "1712.50" },
        { 17, "Hilltop Lawn & Maintenance",                   "-180.00" },
        { 19, "Church software subscription",                 "-49.00" },
        { 21, "Interest earned",                                "3.12" },
        { 22, "Sunday offering deposit",                      "1588.00" },
        { 24, "Building fund gift",                           "750.00" },
        { 26, "Monthly service charge",                        "-12.00" },
        { 28, "Community food bank donation (benevolence)",   "-150.00" },
    };

    /** The statement as CSV with Date, Description, Amount columns (credits positive). */
    public static String csv(LocalDate today) {
        DateTimeFormatter us = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        StringBuilder sb = new StringBuilder("Date,Description,Amount\n");
        for (Object[] r : ROWS) {
            LocalDate d = today.minusDays((Integer) r[0]);
            sb.append(d.format(us)).append(',')
              .append('"').append(((String) r[1]).replace("\"", "\"\"")).append('"').append(',')
              .append(new BigDecimal((String) r[2]).toPlainString()).append('\n');
        }
        return sb.toString();
    }
}
