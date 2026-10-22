package com.churchgeniuspro.service;

import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Period reports for the AI Search, built on the same grouped queries the report pages use
 * ({@code reportFinancialIncomeInRange} / {@code reportFinancialExpenseWithPurposeInRange}):
 * income by main source → fund, expenses by main source → purpose, and the net. Those queries
 * take a half-open range, so the caller's inclusive end date is passed as {@code to + 1 day}.
 * Totals are summed from the grouped rows — the same arithmetic as the Financial Report page.
 */
@Service
public class ReportQueryService {

    /** One grouped line: category (main source), sub-line (fund or purpose), total. */
    public record Line(String category, String item, BigDecimal total) {}
    public record Report(List<Line> income, BigDecimal totalIncome, List<Line> expenses, BigDecimal totalExpense) {
        public BigDecimal net() { return totalIncome.subtract(totalExpense); }
    }

    private final IncomeRepository incomeRepo;
    private final ExpenseRepository expenseRepo;

    public ReportQueryService(IncomeRepository incomeRepo, ExpenseRepository expenseRepo) {
        this.incomeRepo = incomeRepo; this.expenseRepo = expenseRepo;
    }

    @Transactional(readOnly = true)
    public Report income(String clientId, LocalDate from, LocalDate toInclusive) {
        List<Line> inc = lines(incomeRepo.reportFinancialIncomeInRange(Date.valueOf(from), Date.valueOf(toInclusive.plusDays(1)), clientId));
        return new Report(inc, sum(inc), List.of(), BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public Report expenses(String clientId, LocalDate from, LocalDate toInclusive) {
        List<Line> exp = lines(expenseRepo.reportFinancialExpenseWithPurposeInRange(Date.valueOf(from), Date.valueOf(toInclusive.plusDays(1)), clientId));
        return new Report(List.of(), BigDecimal.ZERO, exp, sum(exp));
    }

    @Transactional(readOnly = true)
    public Report financial(String clientId, LocalDate from, LocalDate toInclusive) {
        Report i = income(clientId, from, toInclusive), e = expenses(clientId, from, toInclusive);
        return new Report(i.income(), i.totalIncome(), e.expenses(), e.totalExpense());
    }

    private static List<Line> lines(List<Object[]> rows) {
        List<Line> out = new ArrayList<>();
        if (rows == null) return out;
        for (Object[] r : rows) out.add(new Line(str(r[0]), str(r[1]), num(r[2])));
        return out;
    }
    private static BigDecimal sum(List<Line> lines) { BigDecimal t = BigDecimal.ZERO; for (Line l : lines) t = t.add(l.total()); return t; }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static BigDecimal num(Object o) { return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString()); }
}
