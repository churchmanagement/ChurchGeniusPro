package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Posts a successfully-saved online {@link Donation} (Stripe online giving,
 * MidRegMeet event giving) into {@code income} so it appears everywhere the
 * rest of the app already reports on giving — the Year-End Tax Report, a
 * member's own giving statement, the Financial Report, the Income Report and
 * dashboard totals — without every one of those having to learn a second,
 * separate ledger. Financial audit H9: today {@code donation} rows are
 * invisible to all of them, even though the donor is told by email that the
 * gift is tax-deductible.
 *
 * <p>Call {@link #postToIncome} once, right after a donation is durably
 * saved — never before, and never as part of the same transaction as the
 * Stripe verification above it. This method never throws: every failure is
 * caught and logged, because a bug or a transient database hiccup here must
 * not turn an already-charged, already-recorded donation into an error page
 * for the donor. A donation that fails to post is not lost — it is still a
 * row in {@code donation} — an unusual gap a bookkeeper can spot and enter
 * by hand, exactly like any other reconciliation exception.
 *
 * <p>Idempotent: the Stripe PaymentIntent id is carried as the posted
 * income row's {@code importRef}, the same per-tenant-unique fingerprint
 * column Financial audit H8 added for Bank Import/Plaid (see the unique
 * index in {@code DatabaseIndexInitializer}), so re-posting the same
 * donation can never create a second income row for it. Both current
 * callers already guard the donation save itself against a duplicate
 * PaymentIntent id ({@code DonationController#saveDonation} and
 * {@code MidRegMeetController#midRegDonateSave} each check
 * {@code DonationRepository#findByStripePaymentIntentId} first), so under
 * normal operation this runs at most once per donation; the importRef
 * check is defense in depth, not the only thing preventing a duplicate.
 *
 * <p>Member matching is by email, and only when it is unambiguous: zero or
 * more than one active member of the same church sharing that email (a
 * shared family inbox, say) falls back to a guest entry under the donor's
 * own name rather than risk crediting the wrong family member's gift on a
 * tax statement.
 *
 * <p>This does not backfill donations recorded before this method existed
 * — that is a separate, one-time decision (it would retroactively change
 * past tax statements, and could double-count a donation a bookkeeper
 * already re-entered by hand while donations were invisible to reports)
 * left to the church/operator, not something this best-effort hook decides
 * on its own.
 */
@Service
public class DonationIncomePostingService {

    private static final Logger log = LoggerFactory.getLogger(DonationIncomePostingService.class);

    /** Payment-method label used when the caller didn't supply one. */
    private static final String DEFAULT_METHOD = "Online";

    /** Marks these rows as machine-posted rather than staff-entered. */
    private static final String POSTED_BY = "online-giving";

    private final IncomeService             incomeService;
    private final SourceService             sourceService;
    private final FamilyMemberRepository    memberRepo;
    private final TransactionTypeRepository transactionTypeRepo;

    /** Phase D: resolves the purpose a member chose. Optional for the existing constructions. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.SubSourceRepository subSourceRepo;
    public void setSubSourceRepo(com.churchgeniuspro.repository.SubSourceRepository r) { this.subSourceRepo = r; }

    public DonationIncomePostingService(IncomeService             incomeService,
                                        SourceService             sourceService,
                                        FamilyMemberRepository    memberRepo,
                                        TransactionTypeRepository transactionTypeRepo) {
        this.incomeService       = incomeService;
        this.sourceService       = sourceService;
        this.memberRepo          = memberRepo;
        this.transactionTypeRepo = transactionTypeRepo;
    }

    /**
     * Posts {@code donation} into {@code income}. Never throws. Returns true
     * if a new income row was posted, false if it was skipped — already
     * posted (the common, harmless case on any retry) or a real error,
     * either way logged, never propagated to the caller.
     */
    public boolean postToIncome(Donation donation) {
        if (donation == null || donation.getClientId() == null || donation.getAmount() == null) {
            return false;
        }
        String appClientId = donation.getClientId();
        try {
            // Phase D: a Member Portal contribution is posted to the purpose the member
            // chose (verified to be this church's at save time) and to that member —
            // no email matching. A public donation is posted exactly as before.
            SubSource subSource = null;
            if (donation.isMemberContribution() && donation.getSubSourceId() != null && subSourceRepo != null) {
                subSource = subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(donation.getSubSourceId(), appClientId).orElse(null);
            }
            if (subSource == null) subSource = sourceService.findOrCreateOnlineDonationsSubSource(appClientId);
            TransactionType txnType = findOrCreateTransactionType(
                    donation.getPaymentMethod() != null && !donation.getPaymentMethod().isBlank()
                            ? donation.getPaymentMethod() : DEFAULT_METHOD,
                    appClientId);

            Integer memberId = donation.isMemberContribution() && donation.getMemberId() != null
                    ? memberRepo.findByIdAndTenant(donation.getMemberId(), appClientId).map(FamilyMember::getId).orElse(null)
                    : matchMemberId(donation.getEmail(), appClientId);
            String guestName = memberId == null ? guestNameFor(donation) : null;

            LocalDate incomeDate = donation.getDonatedAt() != null
                    ? donation.getDonatedAt().toLocalDate() : LocalDate.now();

            // Financial audit M3: when the donor covered the processing fee, the
            // fee portion is not part of the gift — posting the full charge here
            // would overstate what this member/guest actually gave toward the
            // fund on every report that reads this ledger (the Year-End Tax
            // Report included). The note still records the full charge, so a
            // bookkeeper reconciling against Stripe's own charge amount isn't
            // left wondering why the two don't match.
            boolean feeWasCovered = donation.getFeeCovered() != null
                    && donation.getFeeCovered().compareTo(java.math.BigDecimal.ZERO) > 0;
            String note = (donation.isMemberContribution() ? "Member portal contribution" : "Online donation")
                    + (donation.getNote() != null && !donation.getNote().isBlank() ? " — " + donation.getNote().trim() : "")
                    + (feeWasCovered ? String.format(" (donor covered a $%.2f processing fee; $%.2f total charged)",
                            donation.getFeeCovered(), donation.getAmount()) : "");

            incomeService.createIncome(
                    memberId,
                    subSource.getId(),
                    incomeDate,
                    txnType.getId(),
                    /* refNo */ null,
                    donation.getIntendedAmountOrCharge(),
                    note,
                    guestName,
                    /* quickAdd */ false,
                    appClientId,
                    /* createdBy */ POSTED_BY,
                    /* importRef */ donation.getStripePaymentIntentId(),
                    /* force — skip the same-date/same-amount heuristic: the
                       PaymentIntent id above is already a precise match, so
                       two unrelated same-day, same-amount gifts must not be
                       flagged as a possible duplicate of each other */ true);
            return true;
        } catch (DuplicateImportException already) {
            log.debug("Donation {} for client {} was already posted to income.",
                    donation.getId(), appClientId);
            return false;
        } catch (Exception e) {
            log.warn("Could not post donation {} to income for client {}: {}",
                    donation.getId(), appClientId, e.getMessage());
            return false;
        }
    }

    private TransactionType findOrCreateTransactionType(String typeName, String appClientId) {
        String name = typeName.trim();
        return transactionTypeRepo
                .findFirstByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(name, appClientId)
                .orElseGet(() -> {
                    TransactionType t = new TransactionType();
                    t.setTypeName(name);
                    t.setAppClientId(appClientId);
                    try {
                        return transactionTypeRepo.save(t);
                    } catch (RuntimeException raced) {
                        // No unique constraint on type_name — see SourceService's
                        // find-or-create for the same reasoning. Use the winner's row.
                        return transactionTypeRepo
                                .findFirstByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(name, appClientId)
                                .orElseThrow(() -> raced);
                    }
                });
    }

    /** Unambiguous match only: exactly one active member with this email in this church. */
    private Integer matchMemberId(String email, String appClientId) {
        if (email == null || email.isBlank()) return null;
        List<FamilyMember> matches = memberRepo.findActiveByEmailAndTenant(email.trim(), appClientId);
        return matches.size() == 1 ? matches.get(0).getId() : null;
    }

    private static String guestNameFor(Donation d) {
        String first = d.getFirstName() != null ? d.getFirstName().trim() : "";
        String last  = d.getLastName()  != null ? d.getLastName().trim()  : "";
        String full  = (first + " " + last).trim();
        return full.isEmpty() ? "Online Donor" : full;
    }
}
