package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Income;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Income} entities.
 */
@Repository
public interface IncomeRepository extends JpaRepository<Income, Integer> {

    /**
     * Returns all non-deleted income records ordered by income date descending,
     * with member and sub-source eagerly fetched to avoid N+1 queries.
     */
    @Query("SELECT i FROM Income i " +
           "JOIN FETCH i.member " +
           "JOIN FETCH i.subSource ss " +
           "JOIN FETCH ss.mainSource " +
           "WHERE i.deleteFlag = false " +
           "ORDER BY i.incomeDate DESC, i.createdDate DESC")
    List<Income> findAllActive();

    /**
     * Returns all non-deleted income records for a specific member,
     * ordered most-recent first.
     */
    @Query("SELECT i FROM Income i " +
           "JOIN FETCH i.member " +
           "JOIN FETCH i.subSource ss " +
           "JOIN FETCH ss.mainSource " +
           "WHERE i.member.id = :memberId " +
           "AND i.deleteFlag = false " +
           "ORDER BY i.incomeDate DESC, i.createdDate DESC")
    List<Income> findByMemberActive(@Param("memberId") Integer memberId);

    /**
     * Grand total of all active income records.
     * No joins — always returns the correct sum.
     * Returns null when the table is empty (handled in the controller).
     */
    @Query("SELECT SUM(i.amount) FROM Income i WHERE i.deleteFlag = false")
    BigDecimal sumTotalAmount();

    /**
     * Dashboard aggregate: subcategory totals with their main category.
     * Native SQL — avoids Hibernate 6 JPQL path-navigation issues.
     * Returns rows of: [sub_id, sub_name, main_id, main_name, total].
     */
    @Query(value =
           "SELECT ss.id          AS sub_id, " +
           "       ss.source_name AS sub_name, " +
           "       ms.id          AS main_id, " +
           "       ms.source_name AS main_name, " +
           "       SUM(i.amount)  AS total " +
           "FROM   income     i " +
           "JOIN   sub_source ss ON ss.id            = i.sub_source_id " +
           "JOIN   main_source ms ON ms.id           = ss.main_source_id " +
           "WHERE  i.delete_flag = false " +
           "GROUP  BY ss.id, ss.source_name, ms.id, ms.source_name " +
           "ORDER  BY ms.source_name ASC, ss.source_name ASC",
           nativeQuery = true)
    List<Object[]> sumAmountBySubCategoryNative();

    /**
     * Dashboard aggregate: income totals per month for a given year.
     * Native SQL. Returns rows of: [month (1-12), total].
     */
    @Query(value =
           "SELECT EXTRACT(MONTH FROM income_date)::int AS m, " +
           "       SUM(amount) AS total " +
           "FROM   income " +
           "WHERE  delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM income_date) = :year " +
           "GROUP  BY m",
           nativeQuery = true)
    List<Object[]> sumAmountByMonthForYear(@Param("year") int year);

    /**
     * Recent income records for the activity feed.
     * No member join — safe regardless of member data integrity.
     */
    @Query("SELECT i FROM Income i " +
           "JOIN FETCH i.subSource ss " +
           "JOIN FETCH ss.mainSource " +
           "WHERE i.deleteFlag = false " +
           "ORDER BY i.incomeDate DESC, i.createdDate DESC")
    List<Income> findRecentForDashboard();

    /**
     * Returns all non-deleted income records filtered by appClientId, ordered most-recent first.
     */
    @Query("SELECT i FROM Income i " +
           "LEFT JOIN FETCH i.member " +
           "JOIN FETCH i.subSource ss " +
           "JOIN FETCH ss.mainSource " +
           "LEFT JOIN FETCH i.transactionType " +
           "WHERE i.deleteFlag = false " +
           "AND (:appClientId IS NULL OR i.appClientId = :appClientId) " +
           "ORDER BY i.incomeDate DESC, i.createdDate DESC")
    List<Income> findAllActiveByAppUser(@Param("appClientId") String appClientId);

    /**
     * Returns all non-deleted income records for a member, filtered by appClientId.
     */
    @Query("SELECT i FROM Income i " +
           "JOIN FETCH i.member " +
           "JOIN FETCH i.subSource ss " +
           "JOIN FETCH ss.mainSource " +
           "LEFT JOIN FETCH i.transactionType " +
           "WHERE i.member.id = :memberId " +
           "AND i.deleteFlag = false " +
           "AND (:appClientId IS NULL OR i.appClientId = :appClientId) " +
           "ORDER BY i.incomeDate DESC, i.createdDate DESC")
    List<Income> findByMemberActiveByAppUser(
            @Param("memberId")    Integer memberId,
            @Param("appClientId") String appClientId);

    /**
     * Recent income for dashboard, filtered by appClientId.
     */
    @Query("SELECT i FROM Income i " +
           "JOIN FETCH i.subSource ss " +
           "JOIN FETCH ss.mainSource " +
           "LEFT JOIN FETCH i.transactionType " +
           "WHERE i.deleteFlag = false " +
           "AND (:appClientId IS NULL OR i.appClientId = :appClientId) " +
           "ORDER BY i.incomeDate DESC, i.createdDate DESC")
    List<Income> findRecentForDashboardByAppUser(@Param("appClientId") String appClientId);

    /**
     * Grand total filtered by appClientId.
     */
    @Query("SELECT SUM(i.amount) FROM Income i WHERE i.deleteFlag = false " +
           "AND (:appClientId IS NULL OR i.appClientId = :appClientId)")
    java.math.BigDecimal sumTotalAmountByAppUser(@Param("appClientId") String appClientId);

    /**
     * Income Statistics chart: totals grouped by month AND sub-source for a given year.
     * Native SQL. Returns rows of: [month (1-12), sub_id, sub_name, total].
     */
    @Query(value =
           "SELECT EXTRACT(MONTH FROM i.income_date)::int AS m, " +
           "       ss.id          AS sub_id, " +
           "       ss.source_name AS sub_name, " +
           "       SUM(i.amount)  AS total " +
           "FROM   income     i " +
           "JOIN   sub_source ss ON ss.id = i.sub_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM i.income_date) = :year " +
           "GROUP  BY m, ss.id, ss.source_name " +
           "ORDER  BY m ASC, ss.source_name ASC",
           nativeQuery = true)
    List<Object[]> sumAmountBySubCategoryAndMonthForYear(@Param("year") int year);

    /**
     * Dashboard aggregate: subcategory totals with their main category, filtered by appClientId.
     * Returns rows of: [sub_id, sub_name, main_id, main_name, total].
     */
    @Query(value =
           "SELECT ss.id          AS sub_id, " +
           "       ss.source_name AS sub_name, " +
           "       ms.id          AS main_id, " +
           "       ms.source_name AS main_name, " +
           "       SUM(i.amount)  AS total " +
           "FROM   income     i " +
           "JOIN   sub_source ss ON ss.id            = i.sub_source_id " +
           "JOIN   main_source ms ON ms.id           = ss.main_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "GROUP  BY ss.id, ss.source_name, ms.id, ms.source_name " +
           "ORDER  BY ms.source_name ASC, ss.source_name ASC",
           nativeQuery = true)
    List<Object[]> sumAmountBySubCategoryNativeByAppUser(@Param("appClientId") String appClientId);

    /**
     * Dashboard aggregate: income totals per month for a given year, filtered by appClientId.
     * Returns rows of: [month (1-12), total].
     */
    @Query(value =
           "SELECT EXTRACT(MONTH FROM income_date)::int AS m, " +
           "       SUM(amount) AS total " +
           "FROM   income " +
           "WHERE  delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM income_date) = :year " +
           "  AND  (:appClientId IS NULL OR app_client_id = :appClientId) " +
           "GROUP  BY m",
           nativeQuery = true)
    List<Object[]> sumAmountByMonthForYearByAppUser(
            @Param("year")        int year,
            @Param("appClientId") String appClientId);

    /**
     * Income Statistics chart: totals grouped by month AND sub-source for a given year,
     * filtered by appClientId. Returns rows of: [month (1-12), sub_id, sub_name, total].
     */
    @Query(value =
           "SELECT EXTRACT(MONTH FROM i.income_date)::int AS m, " +
           "       ss.id          AS sub_id, " +
           "       ss.source_name AS sub_name, " +
           "       SUM(i.amount)  AS total " +
           "FROM   income     i " +
           "JOIN   sub_source ss ON ss.id = i.sub_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM i.income_date) = :year " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "GROUP  BY m, ss.id, ss.source_name " +
           "ORDER  BY m ASC, ss.source_name ASC",
           nativeQuery = true)
    List<Object[]> sumAmountBySubCategoryAndMonthForYearByAppUser(
            @Param("year")        int year,
            @Param("appClientId") String appClientId);

    /**
     * Latest quick-add income templates for the Accountant dashboard.
     * Returns up to 10 records (limit applied in the service).
     */
    @Query("SELECT i FROM Income i " +
           "JOIN FETCH i.member " +
           "JOIN FETCH i.subSource ss " +
           "JOIN FETCH ss.mainSource " +
           "LEFT JOIN FETCH i.transactionType " +
           "WHERE i.quickAdd = true AND i.deleteFlag = false " +
           "AND (:appClientId IS NULL OR i.appClientId = :appClientId) " +
           "ORDER BY i.createdDate DESC")
    List<Income> findQuickAddByAppUser(@Param("appClientId") String appClientId);

    // ── Accounting Report queries ──────────────────────────────────────────

    /**
     * Income Report: total per member per sub-source for a date range.
     * Returns rows of: [member_id, first_name, last_name, middle_name,
     *                   sub_id, sub_name, total, cnt].
     */
    @Query(value =
           "SELECT fm.id, fm.first_name, fm.last_name, fm.middle_name, " +
           "       ss.id AS sub_id, ss.source_name AS sub_name, " +
           "       SUM(i.amount) AS total, COUNT(i.id) AS cnt " +
           "FROM   income i " +
           "JOIN   family_member fm ON fm.id = i.member_id " +
           "JOIN   sub_source    ss ON ss.id = i.sub_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  i.income_date BETWEEN :startDate AND :endDate " +
           "GROUP  BY fm.id, fm.first_name, fm.last_name, fm.middle_name, ss.id, ss.source_name " +
           "ORDER  BY fm.last_name, fm.first_name, ss.source_name",
           nativeQuery = true)
    List<Object[]> reportIncomeByMemberAndSubSource(
            @Param("startDate") java.sql.Date startDate,
            @Param("endDate")   java.sql.Date endDate);

    /**
     * Income Report — individual transaction rows with optional member + sub-source filters.
     * Scoped to the logged-in org via appClientId (i.app_client_id).
     * Returns rows of: [id, income_date, first_name, last_name, guest_name,
     *                   sub_name, main_name, amount, method, ref_no, note]
     * ordered by income_date ASC.
     * Uses LEFT JOIN on family_member so guest entries (member_id IS NULL) are included.
     * When memberId filter is provided as a negative value (e.g. -1 for a specific guest_name),
     * the caller handles filtering separately; pass null to include all.
     */
    @Query(value =
           "SELECT i.id, i.income_date, fm.first_name, fm.last_name, i.guest_name, " +
           "       ss.source_name AS sub_name, ms.source_name AS main_name, " +
           "       i.amount, tt.type_name AS method, i.ref_no, i.note " +
           "FROM   income i " +
           "LEFT JOIN family_member fm ON fm.id  = i.member_id " +
           "JOIN   sub_source    ss ON ss.id  = i.sub_source_id " +
           "JOIN   main_source   ms ON ms.id  = ss.main_source_id " +
           "LEFT JOIN transaction_type tt ON tt.id = i.transaction_type_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  i.income_date BETWEEN :startDate AND :endDate " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "  AND  (CAST(:memberId AS integer) IS NULL OR fm.id = CAST(:memberId AS integer)) " +
           "  AND  (CAST(:subSourceId AS integer) IS NULL OR ss.id = CAST(:subSourceId AS integer)) " +
           "ORDER  BY i.income_date ASC, fm.last_name ASC NULLS LAST, fm.first_name ASC NULLS LAST, i.guest_name ASC NULLS LAST",
           nativeQuery = true)
    List<Object[]> reportIncomeTransactionsFiltered(
            @Param("startDate")   java.sql.Date startDate,
            @Param("endDate")     java.sql.Date endDate,
            @Param("appClientId") String appClientId,
            @Param("memberId")    Integer memberId,
            @Param("subSourceId") Integer subSourceId);

    /**
     * Date Range Transactions report — income side.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [id, income_date, first_name, last_name, guest_name,
     *                   sub_name, main_name, amount, method, ref_no, note].
     * Uses LEFT JOIN on family_member so guest entries (member_id IS NULL) are included.
     */
    @Query(value =
           "SELECT i.id, i.income_date, fm.first_name, fm.last_name, i.guest_name, " +
           "       ss.source_name AS sub_name, ms.source_name AS main_name, " +
           "       i.amount, tt.type_name AS method, i.ref_no, i.note " +
           "FROM   income     i " +
           "LEFT JOIN family_member fm ON fm.id  = i.member_id " +
           "JOIN   sub_source    ss ON ss.id  = i.sub_source_id " +
           "JOIN   main_source   ms ON ms.id  = ss.main_source_id " +
           "LEFT JOIN transaction_type tt ON tt.id = i.transaction_type_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  i.income_date BETWEEN :startDate AND :endDate " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "ORDER  BY i.income_date DESC",
           nativeQuery = true)
    List<Object[]> reportIncomeTransactionsInRange(
            @Param("startDate")   java.sql.Date startDate,
            @Param("endDate")     java.sql.Date endDate,
            @Param("appClientId") String appClientId);

    /**
     * Year-End Tax Report: annual totals per member per sub-source.
     * Returns rows of: [member_id, first_name, last_name,
     *                   sub_name, main_name, total, cnt].
     */
    @Query(value =
           "SELECT fm.id, fm.first_name, fm.last_name, " +
           "       ss.source_name AS sub_name, ms.source_name AS main_name, " +
           "       SUM(i.amount) AS total, COUNT(i.id) AS cnt " +
           "FROM   income     i " +
           "JOIN   family_member fm ON fm.id  = i.member_id " +
           "JOIN   sub_source    ss ON ss.id  = i.sub_source_id " +
           "JOIN   main_source   ms ON ms.id  = ss.main_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM i.income_date) = :year " +
           "GROUP  BY fm.id, fm.first_name, fm.last_name, ss.source_name, ms.source_name " +
           "ORDER  BY fm.last_name, fm.first_name, ms.source_name, ss.source_name",
           nativeQuery = true)
    List<Object[]> reportAnnualIncomeByMember(@Param("year") int year);

    /**
     * Financial Report: income totals grouped by main-source → sub-source for a year.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [main_name, sub_name, total].
     */
    @Query(value =
           "SELECT ms.source_name AS main_name, ss.source_name AS sub_name, " +
           "       SUM(i.amount) AS total " +
           "FROM   income     i " +
           "JOIN   sub_source    ss ON ss.id = i.sub_source_id " +
           "JOIN   main_source   ms ON ms.id = ss.main_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM i.income_date) = :year " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "GROUP  BY ms.source_name, ss.source_name " +
           "ORDER  BY ms.source_name, ss.source_name",
           nativeQuery = true)
    List<Object[]> reportFinancialIncomeByYear(
            @Param("year")        int year,
            @Param("appClientId") String appClientId);

    /**
     * Income Report (filtered): totals per member per sub-source for a date range,
     * with optional memberId and subSourceId filters.
     * Pass null to skip a filter.
     * Returns rows of: [member_id, first_name, last_name, middle_name,
     *                   sub_id, sub_name, total, cnt].
     */
    @Query(value =
           "SELECT fm.id, fm.first_name, fm.last_name, fm.middle_name, " +
           "       ss.id AS sub_id, ss.source_name AS sub_name, " +
           "       SUM(i.amount) AS total, COUNT(i.id) AS cnt " +
           "FROM   income i " +
           "JOIN   family_member fm ON fm.id = i.member_id " +
           "JOIN   sub_source    ss ON ss.id = i.sub_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  i.income_date BETWEEN :startDate AND :endDate " +
           "  AND  (CAST(:memberId AS integer) IS NULL OR i.member_id = CAST(:memberId AS integer)) " +
           "  AND  (CAST(:subSourceId AS integer) IS NULL OR i.sub_source_id = CAST(:subSourceId AS integer)) " +
           "GROUP  BY fm.id, fm.first_name, fm.last_name, fm.middle_name, ss.id, ss.source_name " +
           "ORDER  BY fm.last_name, fm.first_name, ss.source_name",
           nativeQuery = true)
    List<Object[]> reportIncomeFiltered(
            @Param("startDate")   java.sql.Date startDate,
            @Param("endDate")     java.sql.Date endDate,
            @Param("memberId")    Integer memberId,
            @Param("subSourceId") Integer subSourceId);

    /**
     * Year-End Tax Report (filtered): annual totals per member per sub-source,
     * with optional memberId filter. Scoped to the logged-in org via appClientId.
     * Returns rows of: [member_id (null for guests), first_name, last_name, guest_name,
     *                   sub_name, main_name, total, cnt].
     * Uses LEFT JOIN so guest entries (member_id IS NULL) are included.
     */
    @Query(value =
           "SELECT fm.id, fm.first_name, fm.last_name, i.guest_name, " +
           "       ss.source_name AS sub_name, ms.source_name AS main_name, " +
           "       SUM(i.amount) AS total, COUNT(i.id) AS cnt " +
           "FROM   income     i " +
           "LEFT JOIN family_member fm ON fm.id  = i.member_id " +
           "JOIN   sub_source    ss ON ss.id  = i.sub_source_id " +
           "JOIN   main_source   ms ON ms.id  = ss.main_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM i.income_date) = :year " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "  AND  (CAST(:memberId AS integer) IS NULL OR fm.id = CAST(:memberId AS integer)) " +
           "  AND  (:guestName IS NULL OR i.guest_name = :guestName) " +
           "GROUP  BY fm.id, fm.first_name, fm.last_name, i.guest_name, ss.source_name, ms.source_name " +
           "ORDER  BY fm.last_name NULLS LAST, fm.first_name NULLS LAST, i.guest_name NULLS LAST, ms.source_name, ss.source_name",
           nativeQuery = true)
    List<Object[]> reportAnnualIncomeByMemberFiltered(
            @Param("year")        int year,
            @Param("appClientId") String appClientId,
            @Param("memberId")    Integer memberId,
            @Param("guestName")   String guestName);

    // ── AI Search query ───────────────────────────────────────────────────

    /**
     * AI Search: returns income records filtered by appClientId, optional member name
     * (matched against first_name or last_name), optional fund/category keyword
     * (matched against sub_source or main_source name), and a date range.
     * Returns rows of: [id, income_date, first_name, last_name,
     *                   main_name, sub_name, amount, method, ref_no, note].
     */
    @Query(value =
           "SELECT i.id, i.income_date, fm.first_name, fm.last_name, " +
           "       ms.source_name AS main_name, ss.source_name AS sub_name, " +
           "       i.amount, tt.type_name AS method, i.ref_no, i.note " +
           "FROM   income i " +
           "JOIN   family_member fm ON fm.id  = i.member_id " +
           "JOIN   sub_source    ss ON ss.id  = i.sub_source_id " +
           "JOIN   main_source   ms ON ms.id  = ss.main_source_id " +
           "LEFT JOIN transaction_type tt ON tt.id = i.transaction_type_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "  AND  (:name IS NULL OR LOWER(fm.first_name) LIKE LOWER(CONCAT('%', :name, '%')) " +
           "        OR LOWER(fm.last_name) LIKE LOWER(CONCAT('%', :name, '%'))) " +
           "  AND  (:category IS NULL " +
           "        OR LOWER(ss.source_name) LIKE LOWER(CONCAT('%', :category, '%')) " +
           "        OR LOWER(ms.source_name) LIKE LOWER(CONCAT('%', :category, '%'))) " +
           "  AND  i.income_date BETWEEN :startDate AND :endDate " +
           "ORDER  BY i.income_date DESC",
           nativeQuery = true)
    List<Object[]> searchIncome(@Param("appClientId") String appClientId,
                                @Param("name")        String name,
                                @Param("category")    String category,
                                @Param("startDate")   java.sql.Date startDate,
                                @Param("endDate")     java.sql.Date endDate);

    /**
     * Tax Report individual entries: one row per income transaction for letter-format PDFs.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [member_id (null for guests), first_name, last_name, guest_name,
     *                   address1, address2, city, state, pin_code,
     *                   income_date, main_name, sub_name, amount, method].
     * Uses LEFT JOIN so guest entries (member_id IS NULL) are included.
     */
    @Query(value =
           "SELECT fm.id, fm.first_name, fm.last_name, i.guest_name, " +
           "       fm.address1, fm.address2, fm.city, fm.state, fm.pin_code, " +
           "       i.income_date, ms.source_name AS main_name, ss.source_name AS sub_name, " +
           "       i.amount, tt.type_name AS method " +
           "FROM   income i " +
           "LEFT JOIN family_member fm ON fm.id  = i.member_id " +
           "JOIN   sub_source    ss ON ss.id  = i.sub_source_id " +
           "JOIN   main_source   ms ON ms.id  = ss.main_source_id " +
           "LEFT JOIN transaction_type tt ON tt.id = i.transaction_type_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  EXTRACT(YEAR FROM i.income_date) = :year " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "  AND  (CAST(:memberId AS integer) IS NULL OR fm.id = CAST(:memberId AS integer)) " +
           "  AND  (:guestName IS NULL OR i.guest_name = :guestName) " +
           "ORDER  BY fm.last_name NULLS LAST, fm.first_name NULLS LAST, i.guest_name NULLS LAST, i.income_date",
           nativeQuery = true)
    List<Object[]> reportIncomeEntriesForTaxYear(
            @Param("year")        int year,
            @Param("appClientId") String appClientId,
            @Param("memberId")    Integer memberId,
            @Param("guestName")   String guestName);

    /**
     * Returns distinct non-empty guest_name values for the given org and year.
     * Used to populate the tax-report member/contributor filter dropdown.
     * Returns a list of String (the guest_name values), ordered alphabetically.
     */
    @Query(value =
           "SELECT DISTINCT i.guest_name " +
           "FROM   income i " +
           "WHERE  i.delete_flag = false " +
           "  AND  i.guest_name IS NOT NULL AND i.guest_name <> '' " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "  AND  (:year IS NULL OR EXTRACT(YEAR FROM i.income_date) = :year) " +
           "ORDER  BY i.guest_name",
           nativeQuery = true)
    List<String> findDistinctGuestNames(
            @Param("appClientId") String appClientId,
            @Param("year")        Integer year);

    /**
     * Duplicate detection for Bank Import: active income records for this org on a
     * given date with a given amount. The caller further compares ref/check number.
     */
    List<Income> findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(
            String appClientId, java.time.LocalDate incomeDate, BigDecimal amount);

    /**
     * Pledge collection total: sum of all active income a specific member has given
     * to a specific fund (sub-source), scoped to the org. Used to compute a pledge's
     * "collected"/"Amount Given" dynamically so it stays correct even when a
     * campaign's fund is changed or contributions predate the pledge.
     * Returns null when there are no matching rows (caller treats as zero).
     */
    @Query("SELECT SUM(i.amount) FROM Income i " +
           "WHERE i.deleteFlag = false " +
           "AND i.member.id = :memberId " +
           "AND i.subSource.id = :subSourceId " +
           "AND (:appClientId IS NULL OR i.appClientId = :appClientId)")
    BigDecimal sumMemberSubSourceTotal(@Param("memberId")    Integer memberId,
                                       @Param("subSourceId") Integer subSourceId,
                                       @Param("appClientId") String  appClientId);
}
