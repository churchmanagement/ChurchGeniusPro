package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.DonationSettings;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.util.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Posts a reviewed online donation to accounting — the full charge as Income and
 * Stripe's processing fee as a separate Expense (the approved gross-with-fee mode)
 * — and records the links back on the donation.
 *
 * <h2>Why the net equals the bank deposit</h2>
 * For a $100.00 gift Stripe keeps $3.20 and deposits $96.80. Income $100.00 −
 * Expense $3.20 = $96.80, so the Financial Report's net matches the bank while the
 * donor's tax statement (a sum of Income) still shows the $100.00 they gave.
 *
 * <h2>Why it cannot post twice</h2>
 * Five independent guards, any one of which is enough:
 * <ol>
 *   <li>the donation row is locked ({@code SELECT … FOR UPDATE}) for the whole post,
 *       so a double click or two tabs serialise and the second sees the first's result;</li>
 *   <li>{@code donation.posted_income_id} short-circuits before anything is written;</li>
 *   <li>an existing income row with this PaymentIntent as {@code import_ref} — the
 *       former automatic posting — makes the donation "already posted (historical)"
 *       and nothing is written (approval decision 7: no backfill);</li>
 *   <li>{@code IncomeService}/{@code ExpenseService} throw {@link DuplicateImportException}
 *       on a repeated {@code import_ref}, backed by the partial unique indexes
 *       {@code ux_income_import_ref} / {@code ux_expense_import_ref};</li>
 *   <li>the whole post is ONE transaction: Income, Expense and the donation's posting
 *       links commit together or not at all.</li>
 * </ol>
 *
 * <h2>What it never does</h2>
 * It never creates or edits a family member, never creates a transaction type (the
 * method always comes from Donation Settings — the card label goes in the note),
 * never changes an existing Income/Expense row, and never trusts an amount from the
 * client: every figure is recomputed here from the donation row and the settings.
 */
@Service
public class DonationPostingService {

    private static final Logger log = LoggerFactory.getLogger(DonationPostingService.class);

    /** Marks posted rows as created by the review workflow (as {@code created_by}). */
    public static final String POSTED_BY = "donation-review";

    /** {@code import_ref} of the fee Expense: the PaymentIntent id plus this suffix. */
    public static final String FEE_REF_SUFFIX = ":stripe-fee";

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    // ── exceptions the controller maps to HTTP statuses ───────────────────────
    public static class NotFoundException          extends RuntimeException { public NotFoundException(String m)          { super(m); } }
    public static class InvalidReviewException     extends RuntimeException { public InvalidReviewException(String m)     { super(m); } }
    /** A donation whose charge included a donor-covered fee: not supported in this phase (approval decision 6). */
    public static class UnsupportedDonationException extends RuntimeException { public UnsupportedDonationException(String m) { super(m); } }

    /** Reviewer's per-donation edits. Every field optional; null = use the default. */
    public record Overrides(String category, Integer matchedMemberId, boolean clearMember,
                            LocalDate incomeDate, Integer incomeSubSourceId, Integer incomeTransactionTypeId, String incomeNote,
                            LocalDate expenseDate, Integer expensePurposeId, Integer expenseMainSourceId, Integer expenseTransactionTypeId, String expenseNote) {
        public static final Overrides NONE = new Overrides(null, null, false, null, null, null, null, null, null, null, null, null);

        /** From the request body; unknown keys are ignored, bad values are reported by {@code build}. */
        public static Overrides fromBody(Map<String, Object> b) {
            if (b == null) return NONE;
            return new Overrides(
                    DonationSettingsService.str(b.get("category")),
                    DonationSettingsService.toInt(b.get("matchedMemberId")),
                    Boolean.TRUE.equals(b.get("clearMember")),
                    date(b.get("incomeDate")),
                    DonationSettingsService.toInt(b.get("incomeSubSourceId")),
                    DonationSettingsService.toInt(b.get("incomeTransactionTypeId")),
                    DonationSettingsService.str(b.get("incomeNote")),
                    date(b.get("expenseDate")),
                    DonationSettingsService.toInt(b.get("expensePurposeId")),
                    DonationSettingsService.toInt(b.get("expenseMainSourceId")),
                    DonationSettingsService.toInt(b.get("expenseTransactionTypeId")),
                    DonationSettingsService.str(b.get("expenseNote")));
        }
        private static LocalDate date(Object o) {
            String s = DonationSettingsService.str(o);
            if (s == null) return null;
            try { return LocalDate.parse(s); } catch (Exception e) { throw new InvalidReviewException("Invalid date: " + s); }
        }
    }

    /** What posting would write — shown on the review screen, then written exactly as shown. */
    public static final class Proposal {
        public Long donationId;
        public String donor, donorEmail, donorPhone, paymentMethod, donorNote, paymentIntentId;
        /** The donation's reference number (DON-000042) — stored as Ref # on the Income and fee Expense. */
        public String donationNumber;
        public LocalDate donationDate;
        public String category; public Integer matchedMemberId; public String matchedMemberName; public String matchReason;
        public BigDecimal gross, fee, net, incomeAmount, expenseAmount;
        public String feeSource; public boolean feeEstimated;
        public Integer incomeSubSourceId;  public String incomeFund;
        public Integer incomeTransactionTypeId; public String incomeMethod;
        public LocalDate incomeDate; public String incomeNote;
        public Integer expensePurposeId; public String expenseType;
        public Integer expenseMainSourceId; public String expenseFund;
        public Integer expenseTransactionTypeId; public String expenseMethod;
        public LocalDate expenseDate; public String expenseNote;
        public boolean expenseSkipped;                   // fee is $0.00 → no expense row
        public final List<String> warnings = new ArrayList<>();
        public boolean alreadyPosted; public boolean historical; public Integer existingIncomeId, existingExpenseId;
        /** The guest name the income row will carry when no member is linked. */
        public String guestName;

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("donationId", donationId);
            m.put("donationNumber", donationNumber);
            m.put("donor", donor); m.put("donorEmail", donorEmail); m.put("donorPhone", donorPhone);
            m.put("paymentMethod", paymentMethod); m.put("donorNote", donorNote);
            m.put("paymentIntentId", paymentIntentId);
            m.put("donationDate", donationDate != null ? donationDate.toString() : null);
            m.put("category", category); m.put("matchedMemberId", matchedMemberId);
            m.put("matchedMemberName", matchedMemberName); m.put("matchReason", matchReason);
            m.put("guestName", guestName);
            m.put("gross", gross); m.put("fee", fee); m.put("net", net);
            m.put("feeSource", feeSource); m.put("feeEstimated", feeEstimated);
            m.put("incomeAmount", incomeAmount); m.put("expenseAmount", expenseAmount);
            m.put("incomeSubSourceId", incomeSubSourceId); m.put("incomeFund", incomeFund);
            m.put("incomeTransactionTypeId", incomeTransactionTypeId); m.put("incomeMethod", incomeMethod);
            m.put("incomeDate", incomeDate != null ? incomeDate.toString() : null); m.put("incomeNote", incomeNote);
            m.put("expensePurposeId", expensePurposeId); m.put("expenseType", expenseType);
            m.put("expenseMainSourceId", expenseMainSourceId); m.put("expenseFund", expenseFund);
            m.put("expenseTransactionTypeId", expenseTransactionTypeId); m.put("expenseMethod", expenseMethod);
            m.put("expenseDate", expenseDate != null ? expenseDate.toString() : null); m.put("expenseNote", expenseNote);
            m.put("expenseSkipped", expenseSkipped);
            m.put("warnings", warnings);
            m.put("alreadyPosted", alreadyPosted); m.put("historical", historical);
            m.put("existingIncomeId", existingIncomeId); m.put("existingExpenseId", existingExpenseId);
            return m;
        }
    }

    /** Outcome of one post. */
    public record PostResult(Long donationId, String status, Integer incomeId, Integer expenseId, String message, boolean historical) {
        public static final String POSTED = "POSTED", ALREADY_POSTED = "ALREADY_POSTED", FAILED = "FAILED";
        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("donationId", donationId); m.put("status", status);
            m.put("incomeId", incomeId); m.put("expenseId", expenseId);
            m.put("message", message); m.put("historical", historical);
            return m;
        }
    }

    private final DonationRepository        donationRepo;
    private final DonationSettingsService   settingsService;
    private final DonationFeeService        feeService;
    private final ContributorMatcher        matcher;
    private final IncomeService             incomeService;
    private final ExpenseService            expenseService;
    private final IncomeRepository          incomeRepo;
    private final ExpenseRepository         expenseRepo;
    private final SubSourceRepository       subSourceRepo;
    private final MainSourceRepository      mainSourceRepo;
    private final PurposeRepository         purposeRepo;
    private final TransactionTypeRepository transactionTypeRepo;
    private final TransactionTemplate       tx;

    public DonationPostingService(DonationRepository donationRepo,
                                  DonationSettingsService settingsService,
                                  DonationFeeService feeService,
                                  ContributorMatcher matcher,
                                  IncomeService incomeService,
                                  ExpenseService expenseService,
                                  IncomeRepository incomeRepo,
                                  ExpenseRepository expenseRepo,
                                  SubSourceRepository subSourceRepo,
                                  MainSourceRepository mainSourceRepo,
                                  PurposeRepository purposeRepo,
                                  TransactionTypeRepository transactionTypeRepo,
                                  PlatformTransactionManager txManager) {
        this.donationRepo        = donationRepo;
        this.settingsService     = settingsService;
        this.feeService          = feeService;
        this.matcher             = matcher;
        this.incomeService       = incomeService;
        this.expenseService      = expenseService;
        this.incomeRepo          = incomeRepo;
        this.expenseRepo         = expenseRepo;
        this.subSourceRepo       = subSourceRepo;
        this.mainSourceRepo      = mainSourceRepo;
        this.purposeRepo         = purposeRepo;
        this.transactionTypeRepo = transactionTypeRepo;
        // Each post is its own transaction, also inside a batch: one donation's failure
        // never rolls back another's, and one donation's rows never half-commit.
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── preview ─────────────────────────────────────────────────────────────

    /**
     * What {@link #post} would write, with the reviewer's overrides applied and
     * validated. Reads the settings, resolves the fee (which may ask Stripe and store
     * an actual fee), builds the proposal. Writes nothing to income or expense.
     *
     * @throws DonationSettingsService.IncompleteSettingsException settings unusable
     * @throws DonationFeeService.FeeUnavailableException          fee unknown and not estimable
     * @throws InvalidReviewException                              an override is invalid
     */
    public Proposal preview(Long donationId, String clientId, Overrides o) {
        DonationSettings settings = settingsService.requireComplete(clientId);
        Donation d = donationRepo.findByIdAndClientId(donationId, clientId)
                .orElseThrow(() -> new NotFoundException("Donation not found."));
        Proposal p = new Proposal();
        if (markIfAlreadyPosted(d, clientId, p)) return p;
        DonationFeeService.Resolved fee = feeService.resolve(d, settings);
        return build(d, settings, fee, o, clientId, p);
    }

    // ── post ────────────────────────────────────────────────────────────────

    /** Posts one donation in its own transaction. Never returns a half-written result. */
    public PostResult post(Long donationId, String clientId, Overrides o, String username) {
        try {
            return tx.execute(status -> doPost(donationId, clientId, o, username));
        } catch (DuplicateImportException dup) {
            // The unique index caught a concurrent post between our lock and the insert:
            // the transaction has rolled back; report what already exists.
            Donation d = donationRepo.findByIdAndClientId(donationId, clientId).orElse(null);
            return alreadyPostedResult(d, clientId, "This donation was already posted.");
        }
    }

    /**
     * Posts each donation independently (its own transaction, its own outcome). A
     * failure on one leaves the others untouched; the caller gets every outcome.
     * Uses the configured settings and defaults only — no per-donation overrides.
     */
    public List<PostResult> postBatch(Collection<Long> ids, String clientId, String username) {
        List<PostResult> out = new ArrayList<>();
        for (Long id : ids) {
            try {
                out.add(post(id, clientId, Overrides.NONE, username));
            } catch (DonationSettingsService.IncompleteSettingsException ex) {
                out.add(new PostResult(id, PostResult.FAILED, null, null, ex.getMessage(), false));
                break;   // the same settings fail for every remaining id — stop, don't spam
            } catch (RuntimeException ex) {
                out.add(new PostResult(id, PostResult.FAILED, null, null, ex.getMessage(), false));
            }
        }
        return out;
    }

    private PostResult doPost(Long donationId, String clientId, Overrides o, String username) {
        DonationSettings settings = settingsService.requireComplete(clientId);

        // Guard 1 — the row lock. Everything after this line is serialised per donation.
        Donation d = donationRepo.lockByIdAndClientId(donationId, clientId)
                .orElseThrow(() -> new NotFoundException("Donation not found."));

        // Guards 2 + 3 — already posted by this workflow, or by the former automatic one.
        Proposal p = new Proposal();
        if (markIfAlreadyPosted(d, clientId, p)) {
            return new PostResult(d.getId(), PostResult.ALREADY_POSTED, p.existingIncomeId, p.existingExpenseId,
                    p.historical ? "Already posted to Income before the review workflow existed (income #" + p.existingIncomeId + ")."
                                 : "Already posted.", p.historical);
        }

        DonationFeeService.Resolved fee = feeService.resolve(d, settings);
        build(d, settings, fee, o, clientId, p);                 // validates every override
        if (!p.warnings.isEmpty() && p.warnings.stream().anyMatch(w -> w.startsWith("BLOCK:"))) {
            throw new UnsupportedDonationException(p.warnings.stream().filter(w -> w.startsWith("BLOCK:"))
                    .findFirst().get().substring(6).trim());
        }

        // Guard 4 (inside the services) + Guard 5 (this transaction).
        Income income = incomeService.createIncome(
                p.matchedMemberId, p.incomeSubSourceId, p.incomeDate, p.incomeTransactionTypeId,
                /* refNo */ p.donationNumber, p.incomeAmount, p.incomeNote, p.guestName, /* quickAdd */ false,
                clientId, POSTED_BY, /* importRef */ ledgerRef(d),
                /* force — the PaymentIntent id is a precise fingerprint; two unrelated
                   same-day same-amount gifts must not be flagged as duplicates of each other */ true);

        Expense expense = null;
        if (!p.expenseSkipped) {
            expense = expenseService.createExpense(
                    p.expensePurposeId, p.expenseMainSourceId, p.expenseDate, p.expenseTransactionTypeId,
                    /* refNo */ p.donationNumber, p.expenseAmount, p.expenseNote, /* quickAdd */ false,
                    clientId, POSTED_BY, feeImportRef(d), true);
        }

        LocalDateTime now = LocalDateTime.now(AppClock.ZONE);
        d.setContributorCategory(p.category);
        d.setMatchedMemberId(p.matchedMemberId);
        d.setPostedIncomeId(income.getId());
        d.setPostedExpenseId(expense != null ? expense.getId() : null);
        d.setPostedAt(now);
        d.setPostedBy(clip(username, 320));
        // Posting completes the review (review fields stay independent: Mark Pending
        // later only clears these three and never touches the posting links or ledger).
        d.setReviewStatus(Donation.REVIEW_COMPLETED);
        d.setReviewCompletedAt(now);
        d.setReviewCompletedBy(clip(username, 320));
        donationRepo.save(d);

        log.info("Donation posted: client={} donation={} income={} expense={} gross={} fee={} ({}) by={}",
                clientId, d.getId(), income.getId(), expense != null ? expense.getId() : null,
                p.incomeAmount, p.fee, p.feeSource, username);
        return new PostResult(d.getId(), PostResult.POSTED, income.getId(),
                expense != null ? expense.getId() : null,
                p.feeEstimated ? "Posted. The Stripe fee was estimated — refresh it once the payment settles." : "Posted.",
                false);
    }

    // ── the proposal ────────────────────────────────────────────────────────

    /** True (and {@code p} filled) when Income already exists for this donation. Writes nothing. */
    private boolean markIfAlreadyPosted(Donation d, String clientId, Proposal p) {
        p.donationId = d.getId();
        if (d.isPosted()) {
            p.alreadyPosted = true; p.historical = false;
            p.existingIncomeId = d.getPostedIncomeId(); p.existingExpenseId = d.getPostedExpenseId();
            return true;
        }
        {
            Optional<Income> existing = incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(clientId, ledgerRef(d));
            if (existing.isPresent()) {
                // Posted by the former automatic hook. Reported, not adopted (decision 7).
                p.alreadyPosted = true; p.historical = true;
                p.existingIncomeId = existing.get().getId();
                p.existingExpenseId = expenseRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(clientId, feeImportRef(d))
                        .map(Expense::getId).orElse(null);
                return true;
            }
        }
        return false;
    }

    private Proposal build(Donation d, DonationSettings s, DonationFeeService.Resolved fee, Overrides o,
                           String clientId, Proposal p) {
        if (o == null) o = Overrides.NONE;
        p.donationId      = d.getId();
        p.donationNumber  = d.donationNumber();
        p.donor           = donorName(d);
        p.donorEmail      = d.getEmail();
        p.donorPhone      = d.getPhone();
        p.paymentMethod   = d.getPaymentMethod();
        p.donorNote       = d.getNote();
        p.paymentIntentId = d.getStripePaymentIntentId();
        p.donationDate    = d.getDonatedAt() != null ? d.getDonatedAt().toLocalDate() : LocalDate.now(AppClock.ZONE);

        // ── amounts: computed here, never from the client ──
        BigDecimal gross = d.getAmount().setScale(2, java.math.RoundingMode.HALF_UP);
        p.gross = gross; p.fee = fee.fee(); p.net = fee.net();
        p.feeSource = fee.source(); p.feeEstimated = fee.estimated();
        p.incomeAmount  = gross;
        p.expenseAmount = fee.fee();
        if (fee.fee().signum() == 0) {
            p.expenseSkipped = true;
            p.warnings.add("Stripe reported no fee for this donation, so no Expense will be created.");
        }
        if (fee.fee().compareTo(gross) >= 0) {
            p.warnings.add("BLOCK: The fee (" + money(fee.fee()) + ") is not less than the donation (" + money(gross) + "); check the fee rate.");
        }
        if (fee.estimated()) {
            p.warnings.add("The Stripe fee is ESTIMATED from the configured rate, not Stripe's actual fee."
                    + (fee.whyEstimated() != null ? " " + fee.whyEstimated() : ""));
        }
        if (d.getFeeCovered() != null && d.getFeeCovered().signum() > 0) {
            // Decision 6: fee-cover is not offered in this phase; a row carrying one is
            // refused rather than guessed at (its charge mixes the gift and a fee cover).
            p.warnings.add("BLOCK: This donation includes a donor-covered fee of " + money(d.getFeeCovered())
                    + ", which this workflow does not support yet. Post it by hand from the Income page.");
        }
        if (d.getStatus() != null && !"succeeded".equalsIgnoreCase(d.getStatus())) {
            p.warnings.add("BLOCK: Stripe's payment status is '" + d.getStatus() + "', not succeeded.");
        }

        // ── contributor ──
        // A Member Portal gift already knows its giver (verified against the church
        // at save time); a public donation is matched on the details the donor typed.
        ContributorMatcher.Match match;
        if (d.isMemberContribution() && d.getMemberId() != null) {
            Optional<FamilyMember> giver = matcher.verifyChosen(d.getMemberId(), clientId);
            match = giver.map(g -> new ContributorMatcher.Match(g, ContributorMatcher.categoryOf(g), ContributorMatcher.Reason.EMAIL_MATCH))
                         .orElseGet(() -> matcher.match(d, clientId));
            p.matchReason = giver.isPresent() ? "MEMBER_PORTAL" : match.reason().name();
        } else {
            match = matcher.match(d, clientId);
            p.matchReason = match.reason().name();
        }
        Integer memberId = match.memberId();
        String memberName = match.memberName();
        if (o.clearMember()) { memberId = null; memberName = null; }
        if (o.matchedMemberId() != null) {
            FamilyMember chosen = matcher.verifyChosen(o.matchedMemberId(), clientId)
                    .orElseThrow(() -> new InvalidReviewException("The selected person was not found for this church."));
            memberId = chosen.getId();
            memberName = ((chosen.getFirstName() == null ? "" : chosen.getFirstName()) + " "
                        + (chosen.getLastName() == null ? "" : chosen.getLastName())).trim();
            p.matchReason = "CHOSEN_BY_REVIEWER";
        }
        // A previously saved choice (a preview that was then posted) stays unless overridden.
        if (o.matchedMemberId() == null && !o.clearMember() && d.getMatchedMemberId() != null && memberId == null) {
            Optional<FamilyMember> prev = matcher.verifyChosen(d.getMatchedMemberId(), clientId);
            if (prev.isPresent()) { memberId = prev.get().getId(); memberName = (prev.get().getFirstName() + " " + prev.get().getLastName()).trim(); }
        }
        String category = o.category() != null ? o.category()
                : d.getContributorCategory() != null ? d.getContributorCategory()
                : match.category();
        if (!ContributorMatcher.isCategory(category)) {
            throw new InvalidReviewException("Contributor category must be Member, Guest or Visitor.");
        }
        p.category = ContributorMatcher.canonicalCategory(category);
        p.matchedMemberId = memberId; p.matchedMemberName = memberName;
        p.guestName = memberId == null ? p.donor : null;
        if (memberId == null && ContributorMatcher.CATEGORY_MEMBER.equals(p.category)) {
            p.warnings.add("Marked as Member but not linked to a person: the Income row will carry the donor's name, not a member record. Pick the person to credit their statement.");
        }
        if (!match.matched() && o.matchedMemberId() == null) {
            p.warnings.add("No existing person matched this donor unambiguously (" + reasonText(match.reason()) + ").");
        }

        // ── income side ──
        // A Member Portal gift defaults to the purpose the member chose (if the church
        // still has it); the settings' fund is the fallback and the public-page default.
        Integer defaultFund = s.getIncomeSubSourceId();
        if (d.isMemberContribution() && d.getSubSourceId() != null) {
            if (subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(d.getSubSourceId(), clientId).isPresent()) {
                defaultFund = d.getSubSourceId();
            } else {
                p.warnings.add("The purpose the member chose no longer exists; the Donation Settings fund is used instead.");
            }
        }
        p.incomeSubSourceId = o.incomeSubSourceId() != null ? o.incomeSubSourceId() : defaultFund;
        SubSource fund = subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(p.incomeSubSourceId, clientId)
                .orElseThrow(() -> new InvalidReviewException("Income fund was not found for this church."));
        p.incomeFund = fund.getSourceName();
        p.incomeTransactionTypeId = o.incomeTransactionTypeId() != null ? o.incomeTransactionTypeId() : s.getIncomeTransactionTypeId();
        TransactionType incomeMethod = transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(p.incomeTransactionTypeId, clientId)
                .orElseThrow(() -> new InvalidReviewException("Income method was not found for this church."));
        p.incomeMethod = incomeMethod.getTypeName();
        LocalDate defaultDate = DonationSettings.DATE_TODAY.equals(s.getDateMode()) ? LocalDate.now(AppClock.ZONE) : p.donationDate;
        p.incomeDate = o.incomeDate() != null ? o.incomeDate() : defaultDate;
        p.incomeNote = o.incomeNote() != null ? clip(o.incomeNote(), 4000)
                : fill(s.getIncomeNoteTemplate() != null ? s.getIncomeNoteTemplate() : DonationSettings.DEFAULT_INCOME_NOTE, d, p);

        // ── expense side ──
        p.expensePurposeId = o.expensePurposeId() != null ? o.expensePurposeId() : s.getExpensePurposeId();
        Purpose purpose = purposeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(p.expensePurposeId, clientId)
                .orElseThrow(() -> new InvalidReviewException("Expense type was not found for this church."));
        p.expenseType = purpose.getPurposeName();
        p.expenseMainSourceId = o.expenseMainSourceId() != null ? o.expenseMainSourceId() : s.getExpenseMainSourceId();
        MainSource expFund = mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(p.expenseMainSourceId, clientId)
                .orElseThrow(() -> new InvalidReviewException("Expense fund was not found for this church."));
        p.expenseFund = expFund.getSourceName();
        p.expenseTransactionTypeId = o.expenseTransactionTypeId() != null ? o.expenseTransactionTypeId() : s.getExpenseTransactionTypeId();
        TransactionType expMethod = transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(p.expenseTransactionTypeId, clientId)
                .orElseThrow(() -> new InvalidReviewException("Expense method was not found for this church."));
        p.expenseMethod = expMethod.getTypeName();
        p.expenseDate = o.expenseDate() != null ? o.expenseDate() : p.incomeDate;
        String expNote = o.expenseNote() != null ? clip(o.expenseNote(), 4000)
                : fill(s.getExpenseNoteTemplate() != null ? s.getExpenseNoteTemplate() : DonationSettings.DEFAULT_EXPENSE_NOTE, d, p);
        // The fee Expense must always say which donation it belongs to, even when the
        // church saved a note template from before {number}/{donor} existed (a saved
        // template is used verbatim) or the reviewer typed their own note.
        expNote = withDonationIdentity(expNote, p);
        if (fee.estimated() && !expNote.toLowerCase(Locale.ROOT).contains("estimated")) {
            expNote = expNote + " (fee estimated at the configured rate; Stripe's actual fee was not yet available)";
        }
        p.expenseNote = expNote;
        return p;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * The {@code import_ref} the Income row carries: the Stripe PaymentIntent id when
     * there is one, else a per-donation key ({@code donation:<id>}) — a demo/trial
     * seeded donation has no PaymentIntent, and a null ref would leave it without
     * the unique-index guard (and give every such donation the same fee ref).
     */
    public static String ledgerRef(Donation d) {
        String pi = d.getStripePaymentIntentId();
        return pi != null && !pi.isBlank() ? pi : "donation:" + d.getId();
    }

    /** The {@code import_ref} of the fee Expense — the income's ref plus {@link #FEE_REF_SUFFIX}. */
    public static String feeImportRef(Donation d) { return ledgerRef(d) + FEE_REF_SUFFIX; }

    private PostResult alreadyPostedResult(Donation d, String clientId, String msg) {
        if (d == null) return new PostResult(null, PostResult.ALREADY_POSTED, null, null, msg, false);
        Proposal p = new Proposal();
        markIfAlreadyPosted(d, clientId, p);
        return new PostResult(d.getId(), PostResult.ALREADY_POSTED, p.existingIncomeId, p.existingExpenseId, msg, p.historical);
    }

    /** Appends "(donation DON-…, from <donor>)" for whichever of the two the note does not already contain. */
    static String withDonationIdentity(String note, Proposal p) {
        String n = note == null ? "" : note.trim();
        String lower = n.toLowerCase(Locale.ROOT);
        boolean hasNumber = p.donationNumber == null || lower.contains(p.donationNumber.toLowerCase(Locale.ROOT));
        boolean hasDonor  = p.donor == null || p.donor.isBlank() || lower.contains(p.donor.toLowerCase(Locale.ROOT));
        if (hasNumber && hasDonor) return n;
        StringBuilder add = new StringBuilder();
        if (!hasNumber) add.append("donation ").append(p.donationNumber);
        if (!hasDonor)  add.append(add.length() > 0 ? ", " : "").append("from ").append(p.donor);
        return clip((n.isEmpty() ? "" : n + " ") + "(" + add + ")", 4000);
    }

    /** Fills the note template. Placeholders: {card} {donorNote} {gross} {fee} {net} {date} {donor} {pi} {number}. */
    static String fill(String template, Donation d, Proposal p) {
        String card  = d.getPaymentMethod() != null ? d.getPaymentMethod() : "Online";
        String dnote = d.getNote() != null && !d.getNote().isBlank() ? d.getNote().trim() : "";
        String prefix = d.isMemberContribution() ? "Member portal contribution" : "Online donation";
        String donorNote = dnote.isEmpty() ? prefix : prefix + " — " + dnote;
        String out = template
                .replace("{card}",      card)
                .replace("{donorNote}", donorNote)
                .replace("{gross}",     money(p.gross))
                .replace("{fee}",       money(p.fee))
                .replace("{net}",       money(p.net))
                .replace("{date}",      p.donationDate != null ? p.donationDate.format(LONG_DATE) : "")
                .replace("{donor}",     p.donor != null ? p.donor : "")
                .replace("{number}",    d.donationNumber() != null ? d.donationNumber() : "")
                .replace("{pi}",        d.getStripePaymentIntentId() != null ? d.getStripePaymentIntentId() : "");
        return clip(out.trim(), 4000);
    }

    static String money(BigDecimal v) { return v == null ? "" : "$" + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(); }

    public static String donorName(Donation d) {
        String first = d.getFirstName() != null ? d.getFirstName().trim() : "";
        String last  = d.getLastName()  != null ? d.getLastName().trim()  : "";
        String full  = (first + " " + last).trim();
        return full.isEmpty() ? "Online Donor" : full;
    }

    private static String reasonText(ContributorMatcher.Reason r) {
        return switch (r) {
            case AMBIGUOUS -> "more than one person shares these contact details";
            case NAME_MATCHES_CONTACT_CONFLICTS -> "the name matches a person but the email/phone given is not theirs";
            case NO_IDENTITY -> "not enough identity information was given";
            default -> "no person has this email or phone";
        };
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
