package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

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
     * All active (non-deleted) expense records, eagerly fetching purpose and
     * mainSource to avoid N+1 queries.  Ordered most-recent first.
     */
    @Query("SELECT e FROM Expense e " +
           "JOIN FETCH e.purpose " +
           "JOIN FETCH e.mainSource " +
           "WHERE e.deleteFlag = false " +
           "ORDER BY e.expenseDate DESC, e.createdDate DESC")
    List<Expense> findAllActive();

    /**
     * All active expense records for a specific purpose, ordered most-recent
     * first.  Used to auto-fill form fields from the last entry.
     */
    @Query("SELECT e FROM Expense e " +
           "JOIN FETCH e.purpose " +
           "JOIN FETCH e.mainSource " +
           "WHERE e.purpose.id = :purposeId AND e.deleteFlag = false " +
           "ORDER BY e.expenseDate DESC, e.createdDate DESC")
    List<Expense> findByPurposeActive(@Param("purposeId") Integer purposeId);

    /**
     * Expense Statistics chart: totals grouped by month AND main source for a given year.
     * Native SQL. Returns rows of: [month (1-12), main_id, main_name, total].
     */
    @Query(value =
           "SELECT EXTRACT(MONTH FROM e.expense_date)::int AS m, " +
           "       ms.id          AS main_id, " +
           "       ms.source_name AS main_name, " +
           "       SUM(e.amount)  AS total " +
           "FROM   expense    e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM e.expense_date) = :year " +
           "GROUP  BY m, ms.id, ms.source_name " +
           "ORDER  BY m ASC, ms.source_name ASC",
           nativeQuery = true)
    List<Object[]> sumAmountByMainSourceAndMonthForYear(@Param("year") int year);

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
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
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
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "ORDER  BY e.expense_date DESC",
           nativeQuery = true)
    List<Object[]> reportExpenseTransactionsInRange(
            @Param("startDate")   java.sql.Date startDate,
            @Param("endDate")     java.sql.Date endDate,
            @Param("appClientId") String appClientId);

    /**
     * Financial Report: expense totals grouped by main source for a year.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [main_name, total].
     */
    @Query(value =
           "SELECT ms.source_name AS main_name, SUM(e.amount) AS total " +
           "FROM   expense     e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM e.expense_date) = :year " +
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "GROUP  BY ms.source_name " +
           "ORDER  BY ms.source_name",
           nativeQuery = true)
    List<Object[]> reportFinancialExpenseByYear(
            @Param("year")        int year,
            @Param("appClientId") String appClientId);

    /**
     * Financial Report expense breakdown: grouped by main category then purpose.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [main_name, purpose_name, total].
     */
    @Query(value =
           "SELECT ms.source_name AS main_name, p.purpose_name AS purpose_name, SUM(e.amount) AS total " +
           "FROM   expense     e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "JOIN   purpose     p  ON p.id  = e.purpose_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM e.expense_date) = :year " +
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "GROUP  BY ms.source_name, p.purpose_name " +
           "ORDER  BY ms.source_name, p.purpose_name",
           nativeQuery = true)
    List<Object[]> reportFinancialExpenseByYearWithPurpose(
            @Param("year")        int year,
            @Param("appClientId") String appClientId);

    /**
     * All active expense records filtered by appClientId, ordered most-recent first.
     */
    @Query("SELECT e FROM Expense e " +
           "JOIN FETCH e.purpose " +
           "JOIN FETCH e.mainSource " +
           "LEFT JOIN FETCH e.transactionType " +
           "WHERE e.deleteFlag = false " +
           "AND (:appClientId IS NULL OR e.appClientId = :appClientId) " +
           "ORDER BY e.expenseDate DESC, e.createdDate DESC")
    List<Expense> findAllActiveByAppUser(@Param("appClientId") String appClientId);

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

    /**
     * Expense Statistics chart: totals grouped by month AND main source for a given year,
     * filtered by appClientId. Returns rows of: [month (1-12), main_id, main_name, total].
     */
    @Query(value =
           "SELECT EXTRACT(MONTH FROM e.expense_date)::int AS m, " +
           "       ms.id          AS main_id, " +
           "       ms.source_name AS main_name, " +
           "       SUM(e.amount)  AS total " +
           "FROM   expense    e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM e.expense_date) = :year " +
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "GROUP  BY m, ms.id, ms.source_name " +
           "ORDER  BY m ASC, ms.source_name ASC",
           nativeQuery = true)
    List<Object[]> sumAmountByMainSourceAndMonthForYearByAppUser(
            @Param("year")        int year,
            @Param("appClientId") String appClientId);

    /**
     * Dashboard aggregate: expense totals grouped by main source (ALL time),
     * filtered by appClientId and delete_flag = false.
     * Returns rows of: [main_id, main_name, total].
     */
    @Query(value =
           "SELECT ms.id          AS main_id, " +
           "       ms.source_name AS main_name, " +
           "       SUM(e.amount)  AS total " +
           "FROM   expense     e " +
           "JOIN   main_source ms ON ms.id = e.main_source_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "GROUP  BY ms.id, ms.source_name " +
           "ORDER  BY ms.source_name ASC",
           nativeQuery = true)
    List<Object[]> sumAmountByMainSourceNativeByAppUser(@Param("appClientId") String appClientId);

    /**
     * Dashboard pie chart: expense totals grouped by purpose (ALL time),
     * filtered by appClientId and delete_flag = false.
     * Returns rows of: [purpose_id, purpose_name, total].
     */
    @Query(value =
           "SELECT p.id           AS purpose_id, " +
           "       p.purpose_name AS purpose_name, " +
           "       SUM(e.amount)  AS total " +
           "FROM   expense  e " +
           "JOIN   purpose  p ON p.id = e.purpose_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "GROUP  BY p.id, p.purpose_name " +
           "ORDER  BY total DESC",
           nativeQuery = true)
    List<Object[]> sumAmountByPurposeNativeByAppUser(@Param("appClientId") String appClientId);

    /**
     * Expense Statistics chart: totals grouped by month AND purpose for a given year,
     * filtered by appClientId. Returns rows of: [month (1-12), purpose_id, purpose_name, total].
     */
    @Query(value =
           "SELECT EXTRACT(MONTH FROM e.expense_date)::int AS m, " +
           "       p.id           AS purpose_id, " +
           "       p.purpose_name AS purpose_name, " +
           "       SUM(e.amount)  AS total " +
           "FROM   expense  e " +
           "JOIN   purpose  p ON p.id = e.purpose_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM e.expense_date) = :year " +
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "GROUP  BY m, p.id, p.purpose_name " +
           "ORDER  BY m ASC, p.purpose_name ASC",
           nativeQuery = true)
    List<Object[]> sumAmountByPurposeAndMonthForYearByAppUser(
            @Param("year")        int year,
            @Param("appClientId") String appClientId);

    /**
     * Dashboard pie chart: expense totals grouped by purpose for a given year,
     * filtered by appClientId and delete_flag = false.
     * Returns rows of: [purpose_id, purpose_name, total].
     */
    @Query(value =
           "SELECT p.id           AS purpose_id, " +
           "       p.purpose_name AS purpose_name, " +
           "       SUM(e.amount)  AS total " +
           "FROM   expense  e " +
           "JOIN   purpose  p ON p.id = e.purpose_id " +
           "WHERE  e.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM e.expense_date) = :year " +
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "GROUP  BY p.id, p.purpose_name " +
           "ORDER  BY total DESC",
           nativeQuery = true)
    List<Object[]> sumAmountByPurposeForYearByAppUser(@Param("year") int year,
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
           "  AND  (:appClientId IS NULL OR e.app_client_id = :appClientId) " +
           "  AND  (CAST(:purposeId AS integer) IS NULL OR e.purpose_id = CAST(:purposeId AS integer)) " +
           "ORDER  BY ms.source_name, e.expense_date",
           nativeQuery = true)
    List<Object[]> reportExpenseFiltered(
            @Param("startDate")   java.sql.Date startDate,
            @Param("endDate")     java.sql.Date endDate,
            @Param("appClientId") String appClientId,
            @Param("purposeId")   Integer purposeId);
}
