package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Income;
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
 * Spring Data JPA repository for {@link Income} entities.
 */
@Repository
public interface IncomeRepository extends JpaRepository<Income, Integer> {

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
     * One page of non-deleted income records for a church, most-recent first, with
     * member, fund and payment method fetched (open-in-view is disabled).
     * Replaces the former unbounded {@code findAllActiveByAppUser}: the Income page
     * used to receive every row the church ever recorded (ledger scalability, part A —
     * 800K rows was 2.7 s and ~100 MB of JSON per page load). {@code id} is the final
     * sort key so paging is stable when several rows share a date and creation time.
     */
    @Query("SELECT i FROM Income i " +
           "LEFT JOIN FETCH i.member " +
           "JOIN FETCH i.subSource ss " +
           "JOIN FETCH ss.mainSource " +
           "LEFT JOIN FETCH i.transactionType " +
           "WHERE i.deleteFlag = false " +
           "AND i.appClientId = :appClientId " +
           "ORDER BY i.incomeDate DESC, i.createdDate DESC, i.id DESC")
    List<Income> findActivePageByAppUser(@Param("appClientId") String appClientId, Pageable page);

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
     * Non-deleted income in a date range for one church, with member + fund
     * eagerly fetched (open-in-view is disabled, so lazy access would fail).
     * Used by the AI data search ("What was the income for March?",
     * "Did Anson give tithe this month?").
     */
    @Query("SELECT i FROM Income i " +
           "LEFT JOIN FETCH i.member " +
           "JOIN FETCH i.subSource ss " +
           "WHERE i.deleteFlag = false " +
           "AND i.appClientId = :appClientId " +
           "AND i.incomeDate >= :fromDate AND i.incomeDate <= :toDate " +
           "ORDER BY i.incomeDate ASC")
    List<Income> findRangeByAppClientId(@Param("appClientId") String appClientId,
                                        @Param("fromDate") java.time.LocalDate fromDate,
                                        @Param("toDate")   java.time.LocalDate toDate);

    /**
     * The latest N income rows for the dashboard's activity feed. Callers pass
     * {@code PageRequest.of(0, n)}; without the limit this query returned every row
     * the church ever recorded just to show ten of them (ledger scalability, part A).
     */
    @Query("SELECT i FROM Income i " +
           "JOIN FETCH i.subSource ss " +
           "JOIN FETCH ss.mainSource " +
           "LEFT JOIN FETCH i.transactionType " +
           "WHERE i.deleteFlag = false " +
           "AND i.appClientId = :appClientId " +
           "ORDER BY i.incomeDate DESC, i.createdDate DESC, i.id DESC")
    List<Income> findRecentForDashboardByAppUser(@Param("appClientId") String appClientId, Pageable page);

    /**
     * Grand total filtered by appClientId.
     */
    @Query("SELECT SUM(i.amount) FROM Income i WHERE i.deleteFlag = false " +
           "AND i.appClientId = :appClientId")
    java.math.BigDecimal sumTotalAmountByAppUser(@Param("appClientId") String appClientId);

    // ── Year-scoped queries ───────────────────────────────────────────────
    // Each "...ForYear..." method is a default wrapper that turns the year into a
    // half-open date range [Jan 1, next Jan 1) for an "...InRange..." query.
    // "EXTRACT(YEAR FROM income_date) = :year" cannot use an index, so it read every
    // year the church has to find one; the range predicate uses idx_income_tenant_date
    // (Flyway V4). Ledger scalability, part A.

    private static java.sql.Date yearStart(int year) {
        return java.sql.Date.valueOf(LocalDate.of(year, 1, 1));
    }

    private static java.sql.Date nextYearStart(int year) {
        return java.sql.Date.valueOf(LocalDate.of(year + 1, 1, 1));
    }

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
           "  AND  i.app_client_id = :appClientId " +
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
           "  AND  i.app_client_id = :appClientId " +
           "ORDER  BY i.income_date DESC",
           nativeQuery = true)
    List<Object[]> reportIncomeTransactionsInRange(
            @Param("startDate")   java.sql.Date startDate,
            @Param("endDate")     java.sql.Date endDate,
            @Param("appClientId") String appClientId);

    /**
     * Financial Report: income totals grouped by main-source → sub-source for a year.
     * Scoped to the logged-in org via appClientId.
     * Returns rows of: [main_name, sub_name, total].
     */
    default List<Object[]> reportFinancialIncomeByYear(int year, String appClientId) {
        return reportFinancialIncomeInRange(yearStart(year), nextYearStart(year), appClientId);
    }

    @Query(value =
           "SELECT ms.source_name AS main_name, ss.source_name AS sub_name, " +
           "       SUM(i.amount) AS total " +
           "FROM   income     i " +
           "JOIN   sub_source    ss ON ss.id = i.sub_source_id " +
           "JOIN   main_source   ms ON ms.id = ss.main_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  i.app_client_id = :appClientId " +
           "  AND  i.income_date >= :fromDate AND i.income_date < :toDate " +
           "GROUP  BY ms.source_name, ss.source_name " +
           "ORDER  BY ms.source_name, ss.source_name",
           nativeQuery = true)
    List<Object[]> reportFinancialIncomeInRange(
            @Param("fromDate")    java.sql.Date fromDate,
            @Param("toDate")      java.sql.Date toDate,
            @Param("appClientId") String appClientId);

    /**
     * Year-End Tax Report (filtered): annual totals per member per sub-source,
     * with optional memberId filter. Scoped to the logged-in org via appClientId.
     * Returns rows of: [member_id (null for guests), first_name, last_name, guest_name,
     *                   sub_name, main_name, total, cnt].
     * Uses LEFT JOIN so guest entries (member_id IS NULL) are included.
     * Only sub-sources marked tax-deductible are included — a Year-End Tax
     * Report is a charitable-contribution statement, not a general income
     * report, so event fees, bookstore sales and other non-gift income
     * filed under a member no longer appear on it. Financial audit H9.
     */
    default List<Object[]> reportAnnualIncomeByMemberFiltered(int year, String appClientId,
                                                             Integer memberId, String guestName) {
        return reportIncomeByMemberInRange(yearStart(year), nextYearStart(year), appClientId, memberId, guestName);
    }

    @Query(value =
           "SELECT fm.id, fm.first_name, fm.last_name, i.guest_name, " +
           "       ss.source_name AS sub_name, ms.source_name AS main_name, " +
           "       SUM(i.amount) AS total, COUNT(i.id) AS cnt " +
           "FROM   income     i " +
           "LEFT JOIN family_member fm ON fm.id  = i.member_id " +
           "JOIN   sub_source    ss ON ss.id  = i.sub_source_id " +
           "JOIN   main_source   ms ON ms.id  = ss.main_source_id " +
           "WHERE  i.delete_flag = false " +
           "  AND  ss.tax_deductible = true " +
           "  AND  i.app_client_id = :appClientId " +
           "  AND  i.income_date >= :fromDate AND i.income_date < :toDate " +
           "  AND  (CAST(:memberId AS integer) IS NULL OR fm.id = CAST(:memberId AS integer)) " +
           "  AND  (:guestName IS NULL OR i.guest_name = :guestName) " +
           "GROUP  BY fm.id, fm.first_name, fm.last_name, i.guest_name, ss.source_name, ms.source_name " +
           "ORDER  BY fm.last_name NULLS LAST, fm.first_name NULLS LAST, i.guest_name NULLS LAST, ms.source_name, ss.source_name",
           nativeQuery = true)
    List<Object[]> reportIncomeByMemberInRange(
            @Param("fromDate")    java.sql.Date fromDate,
            @Param("toDate")      java.sql.Date toDate,
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
     * Only sub-sources marked tax-deductible are included — see the note on
     * {@link #reportAnnualIncomeByMemberFiltered}. Also used, unfiltered by
     * member, for the member portal's own giving statement, so this one
     * filter covers both the staff and member-facing statements.
     * Financial audit H9.
     * <p>The tenant predicate is deliberately still optional here: the member portal
     * ({@code MembershipFormController.resolveAppClientId}) can resolve a null
     * appClientId for a member session, and the query is already bounded by memberId.
     */
    default List<Object[]> reportIncomeEntriesForTaxYear(int year, String appClientId,
                                                         Integer memberId, String guestName) {
        return reportIncomeEntriesInRange(yearStart(year), nextYearStart(year), appClientId, memberId, guestName);
    }

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
           "  AND  ss.tax_deductible = true " +
           "  AND  i.income_date >= :fromDate AND i.income_date < :toDate " +
           "  AND  (:appClientId IS NULL OR i.app_client_id = :appClientId) " +
           "  AND  (CAST(:memberId AS integer) IS NULL OR fm.id = CAST(:memberId AS integer)) " +
           "  AND  (:guestName IS NULL OR i.guest_name = :guestName) " +
           "ORDER  BY fm.last_name NULLS LAST, fm.first_name NULLS LAST, i.guest_name NULLS LAST, i.income_date",
           nativeQuery = true)
    List<Object[]> reportIncomeEntriesInRange(
            @Param("fromDate")    java.sql.Date fromDate,
            @Param("toDate")      java.sql.Date toDate,
            @Param("appClientId") String appClientId,
            @Param("memberId")    Integer memberId,
            @Param("guestName")   String guestName);

    /**
     * Returns distinct non-empty guest_name values for the given org and year.
     * Used to populate the tax-report member/contributor filter dropdown.
     * Returns a list of String (the guest_name values), ordered alphabetically.
     */
    default List<String> findDistinctGuestNames(String appClientId, Integer year) {
        return year == null
                ? findDistinctGuestNamesAllTime(appClientId)
                : findDistinctGuestNamesInRange(yearStart(year), nextYearStart(year), appClientId);
    }

    @Query(value =
           "SELECT DISTINCT i.guest_name " +
           "FROM   income i " +
           "WHERE  i.delete_flag = false " +
           "  AND  i.guest_name IS NOT NULL AND i.guest_name <> '' " +
           "  AND  i.app_client_id = :appClientId " +
           "ORDER  BY i.guest_name",
           nativeQuery = true)
    List<String> findDistinctGuestNamesAllTime(@Param("appClientId") String appClientId);

    @Query(value =
           "SELECT DISTINCT i.guest_name " +
           "FROM   income i " +
           "WHERE  i.delete_flag = false " +
           "  AND  i.guest_name IS NOT NULL AND i.guest_name <> '' " +
           "  AND  i.app_client_id = :appClientId " +
           "  AND  i.income_date >= :fromDate AND i.income_date < :toDate " +
           "ORDER  BY i.guest_name",
           nativeQuery = true)
    List<String> findDistinctGuestNamesInRange(
            @Param("fromDate")    java.sql.Date fromDate,
            @Param("toDate")      java.sql.Date toDate,
            @Param("appClientId") String appClientId);

    /**
     * Duplicate detection for Bank Import: active income records for this org on a
     * given date with a given amount. The caller further compares ref/check number.
     */
    List<Income> findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(
            String appClientId, java.time.LocalDate incomeDate, BigDecimal amount);

    /**
     * Hard duplicate check for imports (Bank Import / Plaid): is this exact source
     * transaction already on the ledger for this church? Financial audit H8.
     */
    Optional<Income> findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(
            String appClientId, String importRef);

    /**
     * Pledge collection total: sum of a specific member's active income to a
     * specific fund (sub-source), scoped to the org and bounded to a date
     * window — normally one campaign's [createdDate, endDate]. Used to compute
     * a pledge's "collected"/"Amount Given" dynamically so it stays correct
     * even when a campaign's fund is changed or contributions predate the
     * pledge, while keeping two campaigns that share a fund from each
     * claiming the same gifts (financial audit M4). {@code endDate} may be
     * null for a campaign with no end, in which case there is no upper bound.
     * Returns null when there are no matching rows (caller treats as zero).
     */
    @Query("SELECT SUM(i.amount) FROM Income i " +
           "WHERE i.deleteFlag = false " +
           "AND i.member.id = :memberId " +
           "AND i.subSource.id = :subSourceId " +
           "AND (:appClientId IS NULL OR i.appClientId = :appClientId) " +
           "AND i.incomeDate >= :startDate " +
           "AND (:endDate IS NULL OR i.incomeDate <= :endDate)")
    BigDecimal sumMemberSubSourceTotal(@Param("memberId")    Integer memberId,
                                       @Param("subSourceId") Integer subSourceId,
                                       @Param("appClientId") String  appClientId,
                                       @Param("startDate")   LocalDate startDate,
                                       @Param("endDate")     LocalDate endDate);

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<Income> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);
}
