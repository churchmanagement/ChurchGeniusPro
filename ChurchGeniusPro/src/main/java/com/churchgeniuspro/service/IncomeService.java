package com.churchgeniuspro.service;

import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.model.PageSlice;
import com.churchgeniuspro.repository.OffsetWindow;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service layer for {@link Income} CRUD operations and related lookups.
 */
@Service
public class IncomeService {

    private final IncomeRepository          incomeRepo;
    private final FamilyMemberRepository    memberRepo;
    private final SubSourceRepository       subSourceRepo;
    private final TransactionTypeRepository transactionTypeRepo;

    /**
     * Pledge module hook. @Lazy avoids any chance of a startup-time
     * circular reference (PledgeController constructs cleanly without
     * IncomeService, but @Lazy keeps the contract resilient if that
     * changes later). The auto-allocate calls are best-effort — failures
     * are swallowed so an unrelated pledge problem can't break Income.
     */
    private final PledgeController pledgeController;

    @Autowired
    public IncomeService(IncomeRepository          incomeRepo,
                         FamilyMemberRepository    memberRepo,
                         SubSourceRepository       subSourceRepo,
                         TransactionTypeRepository transactionTypeRepo,
                         @Lazy PledgeController    pledgeController) {
        this.incomeRepo          = incomeRepo;
        this.memberRepo          = memberRepo;
        this.subSourceRepo       = subSourceRepo;
        this.transactionTypeRepo = transactionTypeRepo;
        this.pledgeController    = pledgeController;
    }

    // ── Contributors ──────────────────────────────────────────────────────

    /**
     * Returns all non-deleted family members who have
     * {@code includeContributions = true}, for the Contributor dropdown.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getContributors(String appClientId) {
        return memberRepo.findContributorsByAppUser(appClientId)
                .stream()
                .map(this::memberToMap)
                .collect(Collectors.toList());
    }

    // ── Fund Sub-Sources ──────────────────────────────────────────────────

    /**
     * Returns all active sub-sources (across all main categories), for the
     * Fund dropdown.  Each entry includes the parent main-source name so the
     * frontend can display "Main / Sub" grouping if desired.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllSubSources(String appClientId) {
        return subSourceRepo.findAllActiveByAppUser(appClientId)
                .stream()
                .map(this::subSourceToMap)
                .collect(Collectors.toList());
    }

    // ── Last Income for Member ─────────────────────────────────────────────

    /**
     * Returns the most recent income record for the given member, or an empty
     * map if none exists.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getLastIncomeForMember(Integer memberId, String appClientId) {
        List<Income> records = incomeRepo.findByMemberActiveByAppUser(memberId, appClientId);
        if (records.isEmpty()) return Map.of();
        return incomeToMap(records.get(0));
    }

    // ── Income – List ─────────────────────────────────────────────────────

    /** Largest page a caller may request from {@link #getRecentIncomes}. */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * Returns one page of active income records, most-recent first.
     * {@code page} is zero-based; {@code size} is clamped to 1..{@link #MAX_PAGE_SIZE}.
     * The repository is asked for one extra row so {@code hasMore} needs no count query.
     */
    @Transactional(readOnly = true)
    public PageSlice<Map<String, Object>> getRecentIncomes(String appClientId, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        // Over-fetch by one using an explicit offset (PageRequest's offset must be a
        // multiple of its size, which size+1 would break).
        Pageable window = new OffsetWindow((long) safePage * safeSize, safeSize + 1);
        List<Map<String, Object>> rows = incomeRepo.findActivePageByAppUser(appClientId, window)
                .stream()
                .map(this::incomeToMap)
                .collect(Collectors.toList());
        return PageSlice.of(rows, safeSize);
    }

    // ── Quick Add – List ──────────────────────────────────────────────────

    /**
     * Returns the latest 10 income records marked as quick-add templates,
     * for the Accountant dashboard Quick Add panel.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getQuickAddIncomes(String appClientId) {
        return incomeRepo.findQuickAddByAppUser(appClientId)
                .stream()
                .limit(10)
                .map(this::incomeToMap)
                .collect(Collectors.toList());
    }

    // ── Income – Create ───────────────────────────────────────────────────

    @Transactional
    public Income createIncome(Integer    memberId,
                               Integer    subSourceId,
                               LocalDate  incomeDate,
                               Integer    transactionTypeId,
                               String     refNo,
                               BigDecimal amount,
                               String     note,
                               String     guestName,
                               boolean    quickAdd,
                               String     appClientId,
                               String     createdBy) {
        return createIncome(memberId, subSourceId, incomeDate, transactionTypeId, refNo, amount, note,
                guestName, quickAdd, appClientId, createdBy, null, false);
    }

    /**
     * Same as the shorter overload above, but for an import pipeline (Bank Import,
     * Plaid) that can identify the same source transaction across re-imports.
     * Financial audit H8.
     *
     * <p>{@code importRef} is a fingerprint of the source transaction — a bank
     * statement line or a Plaid transaction id — unique per church. When it is
     * already attached to an active income row, this throws a <em>hard</em>
     * {@link DuplicateImportException} rather than posting a second one for the
     * same statement line; that check cannot be overridden. When {@code importRef}
     * is null — every manual entry, and every existing caller of the shorter
     * overload — none of this runs at all: two members legitimately giving the
     * same amount on the same day is not a duplicate.
     *
     * <p>When {@code importRef} is non-null and {@code force} is false, a
     * <em>soft</em> check also runs: an active row already exists for this church
     * on the same date, for the same amount, regardless of its source. This is
     * what lets Bank Import and Plaid see each other's postings. It throws a
     * non-hard {@link DuplicateImportException} that the caller may override by
     * retrying with {@code force = true}.
     */
    @Transactional
    public Income createIncome(Integer    memberId,
                               Integer    subSourceId,
                               LocalDate  incomeDate,
                               Integer    transactionTypeId,
                               String     refNo,
                               BigDecimal amount,
                               String     note,
                               String     guestName,
                               boolean    quickAdd,
                               String     appClientId,
                               String     createdBy,
                               String     importRef,
                               boolean    force) {
        String ref = (importRef != null && !importRef.isBlank()) ? importRef.trim() : null;
        if (ref != null) {
            checkImportDuplicate(appClientId, ref, incomeDate, amount, force);
        }

        FamilyMember    member          = (memberId != null) ? findMemberOrThrow(memberId, appClientId) : null;
        SubSource       subSource       = findSubSourceOrThrow(subSourceId, appClientId);
        TransactionType transactionType = findTransactionTypeOrThrow(transactionTypeId, appClientId);

        Income income = new Income();
        income.setMember(member);
        income.setSubSource(subSource);
        income.setIncomeDate(incomeDate);
        income.setTransactionType(transactionType);
        income.setRefNo(refNo != null ? refNo.trim() : null);
        income.setAmount(amount);
        income.setNote(note != null ? note.trim() : null);
        income.setGuestName(member == null && guestName != null && !guestName.isBlank()
                ? guestName.trim() : null);
        income.setQuickAdd(quickAdd);
        income.setImportRef(ref);
        income.setAppClientId(appClientId);
        income.setCreatedBy(createdBy);
        income.setUpdatedBy(createdBy);
        income.setUpdatedDate(new Date());
        Income saved;
        try {
            saved = incomeRepo.save(income);
        } catch (DataIntegrityViolationException race) {
            // The per-tenant unique index caught a concurrent import of the same
            // statement line between our check above and this insert.
            throw duplicateFromRace("income", appClientId, ref, incomeDate, amount);
        }
        // Auto-credit pledge balance when the contributor matches a member
        // with an active pledge against this fund. Silent on failure.
        try {
            if (member != null && saved.getSubSource() != null) {
                pledgeController.applyIncomeToPledge(appClientId,
                        saved.getSubSource().getId(), member.getId(), saved.getAmount(),
                        saved.getIncomeDate());
            }
        } catch (Exception ignored) { /* pledge module is optional */ }
        return saved;
    }

    /** Financial audit H8 — see {@link #createIncome} above. */
    private void checkImportDuplicate(String appClientId, String importRef, LocalDate date, BigDecimal amount,
                                      boolean force) {
        Optional<Income> hard = incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(appClientId, importRef);
        if (hard.isPresent()) {
            Income existing = hard.get();
            throw new DuplicateImportException("This transaction was already imported.", "income",
                    existing.getId(), str(existing.getIncomeDate()), existing.getAmount(), existing.getRefNo(), true);
        }
        if (!force) {
            List<Income> possible = incomeRepo.findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(
                    appClientId, date, amount);
            if (!possible.isEmpty()) {
                Income existing = possible.get(0);
                throw new DuplicateImportException("A possible duplicate already exists for this date and amount.",
                        "income", existing.getId(), str(existing.getIncomeDate()), existing.getAmount(),
                        existing.getRefNo(), false);
            }
        }
    }

    /** Financial audit H8 — see {@link #createIncome} above. */
    private DuplicateImportException duplicateFromRace(String type, String appClientId, String importRef,
                                                        LocalDate fallbackDate, BigDecimal fallbackAmount) {
        return incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(appClientId, importRef)
                .map(existing -> new DuplicateImportException("This transaction was already imported.", type,
                        existing.getId(), str(existing.getIncomeDate()), existing.getAmount(), existing.getRefNo(), true))
                .orElseGet(() -> new DuplicateImportException("This transaction was already imported.", type,
                        null, str(fallbackDate), fallbackAmount, null, true));
    }

    private static String str(LocalDate d) { return d == null ? null : d.toString(); }

    // ── Income – Update ───────────────────────────────────────────────────

    @Transactional
    public Income updateIncome(Integer    id,
                               Integer    memberId,
                               Integer    subSourceId,
                               LocalDate  incomeDate,
                               Integer    transactionTypeId,
                               String     refNo,
                               BigDecimal amount,
                               String     note,
                               String     guestName,
                               boolean    quickAdd,
                               String     appClientId,
                               String     updatedBy) {
        Income income = findIncomeOrThrow(id, appClientId);
        // Snapshot the original allocation so we can reverse it on the
        // pledge side before applying the new one. Pledge auto-allocate is
        // additive — without these snapshots an edit would double-count.
        Integer    origMemberId    = income.getMember()    != null ? income.getMember().getId()    : null;
        Integer    origSubSourceId = income.getSubSource() != null ? income.getSubSource().getId() : null;
        BigDecimal origAmount      = income.getAmount();
        String     origClientId    = income.getAppClientId();
        LocalDate  origIncomeDate  = income.getIncomeDate();

        FamilyMember member = memberId != null ? findMemberOrThrow(memberId, appClientId) : null;
        income.setMember(member);
        income.setSubSource(findSubSourceOrThrow(subSourceId, appClientId));
        income.setIncomeDate(incomeDate);
        income.setTransactionType(findTransactionTypeOrThrow(transactionTypeId, appClientId));
        income.setRefNo(refNo != null ? refNo.trim() : null);
        income.setAmount(amount);
        income.setNote(note != null ? note.trim() : null);
        income.setGuestName(member == null && guestName != null && !guestName.isBlank()
                ? guestName.trim() : null);
        income.setQuickAdd(quickAdd);
        income.setUpdatedBy(updatedBy);
        income.setUpdatedDate(new Date());
        Income saved = incomeRepo.save(income);
        // Pledge re-allocation: take back the old amount (subtract), apply
        // the new amount (add). Same-row edits where neither member nor
        // fund changed amount-only end up as a single delta.
        try {
            if (origMemberId != null && origSubSourceId != null && origAmount != null) {
                pledgeController.adjustPledgeByDelta(origClientId, origSubSourceId,
                        origMemberId, origAmount.negate(), origIncomeDate);
            }
            if (saved.getMember() != null && saved.getSubSource() != null) {
                pledgeController.applyIncomeToPledge(saved.getAppClientId(),
                        saved.getSubSource().getId(), saved.getMember().getId(), saved.getAmount(),
                        saved.getIncomeDate());
            }
        } catch (Exception ignored) { /* pledge module is optional */ }
        return saved;
    }

    // ── Income – Unstar (remove from Recurring panel) ────────────────────

    @Transactional
    public void unstarIncome(Integer id, String appClientId) {
        Income income = findIncomeOrThrow(id, appClientId);
        income.setQuickAdd(false);
        incomeRepo.save(income);
    }

    // ── Income – Soft-Delete ──────────────────────────────────────────────

    @Transactional
    public void deleteIncome(Integer id, String appClientId) {
        Income income = findIncomeOrThrow(id, appClientId);
        // Capture original pledge-allocation inputs BEFORE marking deleted, so
        // we can credit back the pledge balance once the row is gone.
        String      origClientId    = income.getAppClientId();
        Integer     origSubSourceId = income.getSubSource() != null ? income.getSubSource().getId() : null;
        Integer     origMemberId    = income.getMember()    != null ? income.getMember().getId()    : null;
        java.math.BigDecimal origAmount     = income.getAmount();
        LocalDate             origIncomeDate = income.getIncomeDate();

        income.setDeleteFlag(true);
        incomeRepo.save(income);

        // Best-effort: reverse the pledge credit. Pledge module is optional —
        // failures here must not break income deletion.
        try {
            if (origMemberId != null && origSubSourceId != null && origAmount != null) {
                pledgeController.adjustPledgeByDelta(origClientId,
                        origSubSourceId, origMemberId, origAmount.negate(), origIncomeDate);
            }
        } catch (Exception ignored) { /* pledge module is optional */ }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> memberToMap(FamilyMember m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",        m.getId());
        map.put("firstName", m.getFirstName());
        map.put("lastName",  m.getLastName());
        map.put("nickname",  m.getOtherName());
        String fullName = com.churchgeniuspro.util.MemberNameUtil.display(
                m.getFirstName(), m.getLastName(), m.getOtherName());
        map.put("fullName", fullName);
        map.put("displayName", fullName);
        return map;
    }

    private Map<String, Object> subSourceToMap(SubSource ss) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",           ss.getId());
        map.put("sourceName",   ss.getSourceName());
        map.put("mainSourceId", ss.getMainSource().getId());
        map.put("mainSourceName", ss.getMainSource().getSourceName());
        return map;
    }

    private Map<String, Object> incomeToMap(Income i) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",         i.getId());
        FamilyMember m = i.getMember();
        map.put("memberId",   m != null ? m.getId() : null);
        String fullName = m != null
                ? com.churchgeniuspro.util.MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName())
                : "";
        map.put("memberName", fullName);
        map.put("guestName",  i.getGuestName());
        map.put("subSourceId",    i.getSubSource().getId());
        map.put("subSourceName",  i.getSubSource().getSourceName());
        map.put("mainSourceName", i.getSubSource().getMainSource().getSourceName());
        map.put("incomeDate",         i.getIncomeDate() != null ? i.getIncomeDate().toString() : null);
        TransactionType tt = i.getTransactionType();
        map.put("transactionTypeId",  tt != null ? tt.getId()       : null);
        map.put("method",             tt != null ? tt.getTypeName() : "");
        map.put("refNo",              i.getRefNo());
        map.put("amount",         i.getAmount());
        map.put("note",           i.getNote());
        map.put("quickAdd",       i.isQuickAdd());
        map.put("createdBy",      i.getCreatedBy());
        map.put("updatedBy",      i.getUpdatedBy());
        map.put("updatedDate",    i.getUpdatedDate() != null ? i.getUpdatedDate().toString() : null);
        return map;
    }

    // All lookups are tenant-scoped: an id that exists in another church yields the
    // same "not found" as an unknown id, so the API cannot be used as an oracle.

    private FamilyMember findMemberOrThrow(Integer id, String appClientId) {
        return memberRepo.findByIdAndTenant(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Family member not found: " + id));
    }

    private SubSource findSubSourceOrThrow(Integer id, String appClientId) {
        return subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Sub-source not found: " + id));
    }

    private Income findIncomeOrThrow(Integer id, String appClientId) {
        return incomeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Income record not found: " + id));
    }

    private TransactionType findTransactionTypeOrThrow(Integer id, String appClientId) {
        if (id == null) throw new IllegalArgumentException("Transaction type is required.");
        return transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction type not found: " + id));
    }
}
