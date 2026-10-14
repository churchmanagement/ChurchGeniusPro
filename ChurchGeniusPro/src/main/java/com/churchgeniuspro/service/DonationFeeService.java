package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.DonationSettings;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Works out the Stripe processing fee for a donation — Stripe's own figure when it
 * has one, a clearly-labelled estimate from the church's configured rate when it
 * does not, and a refusal when neither is possible (approval requirement 3: never
 * invent a fee).
 *
 * <p>Order:
 * <ol>
 *   <li>The donation already carries Stripe's fee ({@code fee_source = STRIPE}) → use it.</li>
 *   <li>It has a charge id → ask Stripe for the charge's balance transaction through
 *       the church's own gateway ({@link ChurchStripeGateway}, never platform
 *       billing). Found → store it as {@code STRIPE}. This is also how an ACH
 *       donation picks up its real fee once it settles.</li>
 *   <li>Settings have a fee rate → {@code round_half_up(charge × % + fixed, 2)},
 *       labelled {@code ESTIMATED}.</li>
 *   <li>Otherwise → {@link FeeUnavailableException}; the donation cannot be posted yet.</li>
 * </ol>
 *
 * <p>Every amount is two-decimal {@link BigDecimal}; cents from Stripe are converted
 * exactly, and the estimate is rounded once, HALF_UP, at the end.
 */
@Service
public class DonationFeeService {

    private static final Logger log = LoggerFactory.getLogger(DonationFeeService.class);

    /** The fee is unknown and cannot be estimated. Posting must not proceed. */
    public static class FeeUnavailableException extends RuntimeException {
        public FeeUnavailableException(String message) { super(message); }
    }

    /** The resolved fee and where it came from. */
    public record Resolved(BigDecimal fee, BigDecimal net, String source, boolean estimated, String whyEstimated) {
        public Resolved(BigDecimal fee, BigDecimal net, String source, boolean estimated) { this(fee, net, source, estimated, null); }
    }

    /** Why a refresh did not produce Stripe's fee — shown to the reviewer, never guessed around. */
    public enum RefreshStatus { FOUND, NO_CHARGE_ID, NO_STRIPE_KEY, NOT_SETTLED, KEY_LACKS_PERMISSION, STRIPE_ERROR, NETWORK_ERROR }

    public record Refresh(RefreshStatus status, Optional<ChurchStripeGateway.FeeInfo> fee, String detail) {
        public boolean found() { return status == RefreshStatus.FOUND; }
        public String message() {
            return switch (status) {
                case FOUND -> "Stripe reported the actual fee.";
                case NO_CHARGE_ID -> "No Stripe charge id is stored for this donation, so the actual fee cannot be looked up.";
                case NO_STRIPE_KEY -> "This church has no Stripe secret key configured, so the actual fee cannot be looked up.";
                case NOT_SETTLED -> "Stripe has not reported a fee for this charge yet (the payment may not have settled).";
                case KEY_LACKS_PERMISSION -> "Stripe refused the lookup: the church's Stripe key is not permitted to read charges or balance transactions. "
                        + "A restricted key needs 'Charges: read' and 'Balance transactions: read'. Until then only an estimated fee is possible.";
                case STRIPE_ERROR -> "Stripe returned an error for this charge" + (detail != null ? " (" + detail + ")" : "") + ".";
                case NETWORK_ERROR -> "Stripe could not be reached" + (detail != null ? " (" + detail + ")" : "") + ".";
            };
        }
    }

    private final DonationRepository      donationRepo;
    private final StripeSettingsRepository stripeRepo;
    private final ChurchStripeGateway     gateway;

    public DonationFeeService(DonationRepository donationRepo,
                              StripeSettingsRepository stripeRepo,
                              ChurchStripeGateway gateway) {
        this.donationRepo = donationRepo;
        this.stripeRepo   = stripeRepo;
        this.gateway      = gateway;
    }

    /**
     * Resolves the fee for {@code d}, refreshing from Stripe when it is not yet
     * known. Persists an actual fee it learns (so the next look is free) but never
     * persists an estimate — an estimate is recomputed from the current rate every
     * time, and only the posted Expense row fixes it.
     *
     * @throws FeeUnavailableException when neither Stripe nor the settings can supply a fee
     */
    @Transactional
    public Resolved resolve(Donation d, DonationSettings settings) {
        if (d.hasActualFee()) {
            return new Resolved(d.getStripeFee(), netOf(d, d.getStripeFee()), Donation.FEE_SOURCE_STRIPE, false);
        }
        Refresh fetched = refresh(d);
        if (fetched.found()) {
            return new Resolved(d.getStripeFee(), netOf(d, d.getStripeFee()), Donation.FEE_SOURCE_STRIPE, false);
        }
        // Anything but FOUND is "Stripe did not tell us" — never an actual fee. A
        // permission refusal is logged loudly because it is a configuration problem
        // that would otherwise hide behind estimates indefinitely.
        if (fetched.status() == RefreshStatus.KEY_LACKS_PERMISSION) {
            log.warn("Fee lookup for donation {} refused by Stripe (client {}): the church's Stripe key lacks permission to read charges/balance transactions; using an ESTIMATE if a rate is configured.",
                    d.getId(), d.getClientId());
        }
        if (settings != null && settings.hasFeeRate()) {
            BigDecimal fee = estimate(d.getAmount(), settings.getFeePercent(), settings.getFeeFixed());
            return new Resolved(fee, netOf(d, fee), Donation.FEE_SOURCE_ESTIMATED, true, fetched.message());
        }
        throw new FeeUnavailableException(
                "Stripe has not reported the processing fee for this donation yet"
              + (d.getStripeChargeId() == null ? " (no charge id is stored)" : "")
              + ", and no Stripe fee rate is configured in Donation Settings to estimate it. "
              + "Enter the fee % and fixed fee in Donation Settings, or try again after the payment settles.");
    }

    /**
     * Asks Stripe for the charge's balance transaction and, if present, stores the
     * actual fee on the donation. Empty when the charge id is missing, the church's
     * Stripe key is not configured, Stripe errors, or the payment has not settled.
     * Never throws.
     */
    @Transactional
    public Optional<ChurchStripeGateway.FeeInfo> refreshFromStripe(Donation d) {
        return refresh(d).fee();
    }

    /** As {@link #refreshFromStripe}, but says why when nothing came back. Never throws; never logs a key. */
    @Transactional
    public Refresh refresh(Donation d) {
        if (d.getStripeChargeId() == null || d.getClientId() == null) {
            return new Refresh(RefreshStatus.NO_CHARGE_ID, Optional.empty(), null);
        }
        StripeSettings ss = stripeRepo.findByClientId(d.getClientId()).orElse(null);
        if (ss == null || ss.getSecretKey() == null || ss.getSecretKey().isBlank()) {
            return new Refresh(RefreshStatus.NO_STRIPE_KEY, Optional.empty(), null);
        }
        try {
            Map<String, Object> charge = gateway.retrieveCharge(ss.getSecretKey(), d.getStripeChargeId());
            if (charge.containsKey("error")) {
                String msg = ChurchStripeGateway.errorMessage(charge);          // Stripe's text only — never the key
                if (ChurchStripeGateway.isPermissionError(charge)) {
                    return new Refresh(RefreshStatus.KEY_LACKS_PERMISSION, Optional.empty(), msg);
                }
                log.debug("Fee refresh for donation {}: Stripe said {}", d.getId(), msg);
                return new Refresh(RefreshStatus.STRIPE_ERROR, Optional.empty(), msg);
            }
            Optional<ChurchStripeGateway.FeeInfo> fi = ChurchStripeGateway.feeInfoFromCharge(charge);
            if (fi.isEmpty()) return new Refresh(RefreshStatus.NOT_SETTLED, Optional.empty(), null);
            apply(d, fi.get());
            return new Refresh(RefreshStatus.FOUND, fi, null);
        } catch (Exception ex) {
            log.warn("Fee refresh for donation {} failed: {}", d.getId(), ex.getClass().getSimpleName());
            return new Refresh(RefreshStatus.NETWORK_ERROR, Optional.empty(), ex.getClass().getSimpleName());
        }
    }

    /** Records Stripe's figures on the donation (used at save time and on refresh). */
    public void apply(Donation d, ChurchStripeGateway.FeeInfo f) {
        d.setStripeFee(f.fee());
        d.setStripeNet(f.net());
        d.setStripeBalanceTransactionId(f.balanceTransactionId());
        d.setFeeSource(Donation.FEE_SOURCE_STRIPE);
        if (d.getId() != null) donationRepo.save(d);
    }

    /** {@code round_half_up(amount × percent/100 + fixed, 2)} — the brief's $100.00 × 2.9% + $0.30 = $3.20. */
    public static BigDecimal estimate(BigDecimal amount, BigDecimal percent, BigDecimal fixed) {
        BigDecimal pct = percent.movePointLeft(2);
        return amount.multiply(pct).add(fixed).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal netOf(Donation d, BigDecimal fee) {
        if (d.getStripeNet() != null && d.hasActualFee()) return d.getStripeNet();
        return d.getAmount().subtract(fee).setScale(2, RoundingMode.HALF_UP);
    }
}
