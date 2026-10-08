package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Expense;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Expense}.
 */
@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Integer> {

    /**
     * Duplicate detection for Bank Import: active expense records for this org on a
     * given date with a given amount. The caller further compares ref/check number.
     */
    List<Expense> findByAppClientIdAndExpenseDateAndAmountAndDeleteFlagFalse(
            String appClientId, java.time.LocalDate expenseDate, BigDecimal amount);

    /**
     * Hard duplicate check for imports (Bank Import / Plaid): is this exact source
     * transaction already on the ledger for this church? Financial audit H8.
     */
    Optional<Expense> findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(
            String appClientId, String importRef);

    /**
     * Latest quick-add expense templates for the Accountant dashboard.
     * Returns up to 10 records (limit applied in the service).
     */
    @Query("SELECT e FROM Expense e " +
           "JOIN FETCH e.purpose " +
           "JOIN FETCH e.mainSource " +
           "LEFT JOIN FETCH e.transactionType " +
           "WHERE e.quickAdd = true AND e.deleteFlag = false " +
           "AND (:appClientId IS NULL OR e.appClientId = :appClientId) " +
           "ORDER BY e.createdDate DESC")
    List<Expense> findQuickAddByAppUser(@Param("appClientId") String appClientId);

    // ── Accounting Report queries ──────────────────────────────────────────

    /**
     * Expense Report: individual entries grouped by main source for a date range.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [main_name, purpose_name, expense_date, amount, method, ref_no, note].
     */
    @Query(value =
           "SELECT ms.source_name AS main_name, p.purpose_name, " +
           "       e.expense_date, e.amount, tt.type_name AS method, e.ref_no, e.note " +
           "FROM   expense     e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "JOIN   purpose     p  ON p.id  = e.purpose_id " +
           "LEFT JOIN transaction_type tt ON tt.id = e.transaction_type_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  e.expense_date BETWEEN :startDate AND :endDate " +
           "  AND  e.app_client_id = :appClientId " +
           "ORDER  BY ms.source_name, e.expense_date",
           nativeQuery = true)
    List<Object[]> reportExpenseByMainSource(
            @Param("startDate")   java.sql.Date startDate,
            @Param("endDate")     java.sql.Date endDate,
            @Param("appClientId") String appClientId);

    /**
     * Date Range Transactions report — expense side.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [id, expense_date, main_name, purpose_name,
     *                   amount, method, ref_no, note].
     */
    @Query(value =
           "SELECT e.id, e.expense_date, ms.source_name AS main_name, p.purpose_name, " +
           "       e.amount, tt.type_name AS method, e.ref_no, e.note " +
           "FROM   expense     e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "JOIN   purpose     p  ON p.id  = e.purpose_id " +
           "LEFT JOIN transaction_type tt ON tt.id = e.transaction_type_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  e.expense_date BETWEEN :startDate AND :endDate " +
           "  AND  e.app_client_id = :appClientId " +
           "ORDER  BY e.expense_date DESC",
           nativeQuery = true)
    List<Object[]> reportExpenseTransactionsInRange(
            @Param("startDate")   java.sql.Date startDate,
            @Param("endDate")     java.sql.Date endDate,
            @Param("appClientId") String appClientId);

    // ── Year-scoped queries ───────────────────────────────────────────────
    // Each "...ForYear..." method is a default wrapper that turns the year into a
    // half-open date range [Jan 1, next Jan 1) for an "...InRange..." query, so the
    // predicate can use idx_expense_tenant_date (Flyway V4) instead of scanning every
    // year the church has. Ledger scalability, part A.

    private static java.sql.Date yearStart(int year) {
        return java.sql.Date.valueOf(LocalDate.of(year, 1, 1));
    }

    private static java.sql.Date nextYearStart(int year) {
        return java.sql.Date.valueOf(LocalDate.of(year + 1, 1, 1));
    }

    /**
     * Financial Report: expense totals grouped by main source for a year.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [main_name, total].
     */
    default List<Object[]> reportFinancialExpenseByYear(int year, String appClientId) {
        return reportFinancialExpenseInRange(yearStart(year), nextYearStart(year), appClientId);
    }

    @Query(value =
           "SELECT ms.source_name AS main_name, SUM(e.amount) AS total " +
           "FROM   expense     e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  e.app_client_id = :appClientId " +
           "  AND  e.expense_date >= :fromDate AND e.expense_date < :toDate " +
           "GROUP  BY ms.source_name " +
           "ORDER  BY ms.source_name",
           nativeQuery = true)
    List<Object[]> reportFinancialExpenseInRange(
            @Param("fromDate")    java.sql.Date fromDate,
            @Param("toDate")      java.sql.Date toDate,
            @Param("appClientId") String appClientId);

    /**
     * Financial Report expense breakdown: grouped by main category then purpose.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [main_name, purpose_name, total].
     */
    default List<Object[]> reportFinancialExpenseByYearWithPurpose(int year, String appClientId) {
        return reportFinancialExpenseWithPurposeInRange(yearStart(year), nextYearStart(year), appClientId);
    }

    @Query(value =
           "SELECT ms.source_name AS main_name, p.purpose_name AS purpose_name, SUM(e.amount) AS total " +
           "FROM   expense     e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "JOIN   purpose     p  ON p.id  = e.purpose_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  e.app_client_id = :appClientId " +
           "  AND  e.expense_date >= :fromDate AND e.expense_date < :toDate " +
           "GROUP  BY ms.source_name, p.purpose_name " +
           "ORDER  BY ms.source_name, p.purpose_name",
           nativeQuery = true)
    List<Object[]> reportFinancialExpenseWithPurposeInRange(
            @Param("fromDate")    java.sql.Date fromDate,
            @Param("toDate")      java.sql.Date toDate,
            @Param("appClientId") String appClientId);

    /**
     * One page of active expense records for a church, most-recent first, with
     * purpose, fund and payment method fetched. Replaces the former unbounded
     * {@code findAllActiveByAppUser}, which the Expense page and the Accountant
     * dashboard both used to load every row the church ever recorded (ledger
     * scalability, part A). {@code id} is the final sort key so paging is stable.
     * The dashboard's activity feed passes {@code PageRequest.of(0, 10)}.
     */
    @Query("SELECT e FROM Expense e " +
           "JOIN FETCH e.purpose " +
           "JOIN FETCH e.mainSource " +
           "LEFT JOIN FETCH e.transactionType " +
           "WHERE e.deleteFlag = false " +
           "AND e.appClientId = :appClientId " +
           "ORDER BY e.expenseDate DESC, e.createdDate DESC, e.id DESC")
    List<Expense> findActivePageByAppUser(@Param("appClientId") String appClientId, Pageable page);

    /**
     * Active expense records for a specific purpose, filtered by appClientId,
     * ordered most-recent first.
     */
    @Query("SELECT e FROM Expense e " +
           "JOIN FETCH e.purpose " +
           "JOIN FETCH e.mainSource " +
           "LEFT JOIN FETCH e.transactionType " +
           "WHERE e.purpose.id = :purposeId AND e.deleteFlag = false " +
           "AND (:appClientId IS NULL OR e.appClientId = :appClientId) " +
           "ORDER BY e.expenseDate DESC, e.createdDate DESC")
    List<Expense> findByPurposeActiveByAppUser(
            @Param("purposeId")   Integer purposeId,
            @Param("appClientId") String appClientId);

    // ── AI Search query ───────────────────────────────────────────────────

    /**
     * AI Search: returns expense records filtered by appClientId, optional name
     * (matched against purpose_name or main source name), and a date range.
     * Returns rows of: [main_name, purpose_name, expense_date, amount, method, ref_no, note].
     */
    @Query(value =
           "SELECT ms.source_name AS main_name, p.purpose_name, " +
           "       e.expense_date, e.amount, tt.type_name AS method, e.ref_no, e.note " +
           "FROM   expense     e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "JOIN   purpose     p  ON p.id  = e.purpose_id " +
           "LEFT JOIN transaction_type tt ON tt.id = e.transaction_type_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "  AND  (:name IS NULL " +
           "        OR LOWER(p.purpose_name)   LIKE LOWER(CONCAT('%', :name, '%')) " +
           "        OR LOWER(ms.source_name)   LIKE LOWER(CONCAT('%', :name, '%'))) " +
           "  AND  e.expense_date BETWEEN :startDate AND :endDate " +
           "ORDER  BY e.expense_date DESC",
           nativeQuery = true)
    List<Object[]> searchExpense(@Param("appClientId") String appClientId,
                                 @Param("name")        String name,
                                 @Param("startDate")   java.sql.Date startDate,
                                 @Param("endDate")     java.sql.Date endDate);

    /**
     * Expense Report (filtered): entries grouped by main source for a date range,
     * with optional purposeId filter. Scoped to the logged-in org via appClientId.
     * Returns rows of: [main_name, purpose_name, expense_date, amount, method, ref_no, note].
     */
    @Query(value =
           "SELECT e.id, ms.source_name AS main_name, p.purpose_name, " +
           "       e.expense_date, e.amount, tt.type_name AS method, e.ref_no, e.note " +
           "FROM   expense     e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "JOIN   purpose     p  ON p.id  = e.purpose_id " +
           "LEFT JOIN transaction_type tt ON tt.id = e.transaction_type_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  e.expense_date BETWEEN :startDate AND :endDate " +
           "  AND  e.app_client_id = :appClientId " +
           "  AND  (CAST(:purposeId AS integer) IS NULL OR e.purpose_id = CAST(:purposeId AS integer)) " +
           "ORDER  BY ms.source_name, e.expense_date",
           nativeQuery = true)
    List<Object[]> reportExpenseFiltered(
            @Param("startDate")   java.sql.Date startDate,
            @Param("endDate")     java.sql.Date endDate,
            @Param("appClientId") String appClientId,
            @Param("purposeId")   Integer purposeId);

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<Expense> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);
}
