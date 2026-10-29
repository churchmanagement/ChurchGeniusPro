package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ReminderSentLog;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import com.churchgeniuspro.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

/**
 * Claim-before-send for the reminder scheduler (error-handling audit 2026-10-28, L5).
 *
 * <p>Writes the {@code reminder_sent_log} row that makes a reminder "taken" for today,
 * BEFORE the reminder is sent. It used to be a private {@code @Transactional(REQUIRES_NEW)}
 * method inside {@link ReminderSchedulerService} — inert, since Spring proxies don't
 * intercept self-calls — and its {@code catch (Exception)} read every failure, a database
 * outage included, as "already sent", so a reminder could vanish for the day with no trace.
 *
 * <p>Now the insert runs in a NEW transaction started programmatically (so it commits on its
 * own, and a constraint violation rolls back only that transaction — never a caller's), and
 * the outcome is decided OUTSIDE it:
 * <ul>
 *   <li>{@link Claim#CLAIMED} — the row was inserted: send.</li>
 *   <li>{@link Claim#ALREADY_SENT} — only a unique-key violation: someone already claimed it.</li>
 *   <li>{@link Claim#FAILED} — anything else (database down, timeout …): logged at ERROR with
 *       the stack trace; the caller skips this reminder for this run and nothing is recorded,
 *       so it is never reported as sent. Sending without a claim was rejected: with the
 *       database failing, the once-only guard would be gone and a retry could double-send.</li>
 * </ul>
 */
@Service
public class ReminderClaimService {

    private static final Logger log = LoggerFactory.getLogger(ReminderClaimService.class);

    public enum Claim { CLAIMED, ALREADY_SENT, FAILED }

    private final ReminderSentLogRepository sentLogRepo;
    private final TransactionTemplate requiresNew;   // null in plain unit tests

    @org.springframework.beans.factory.annotation.Autowired
    public ReminderClaimService(ReminderSentLogRepository sentLogRepo,
                                ObjectProvider<PlatformTransactionManager> txManager) {
        this.sentLogRepo = sentLogRepo;
        PlatformTransactionManager tm = txManager == null ? null : txManager.getIfAvailable();
        if (tm != null) {
            this.requiresNew = new TransactionTemplate(tm);
            this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else {
            this.requiresNew = null;
        }
    }

    /** Without a transaction manager (unit tests): the repository's own transaction applies. */
    public ReminderClaimService(ReminderSentLogRepository sentLogRepo) {
        this(sentLogRepo, null);
    }

    public Claim claim(String clientId, String reminderType, String referenceKey, LocalDate today) {
        ReminderSentLog row = new ReminderSentLog();
        row.setAppClientId(clientId);
        row.setReminderType(reminderType);
        row.setReferenceKey(referenceKey);
        row.setSentDate(today);
        try {
            if (requiresNew != null) {
                requiresNew.executeWithoutResult(st -> sentLogRepo.saveAndFlush(row));
            } else {
                sentLogRepo.saveAndFlush(row);
            }
            return Claim.CLAIMED;
        } catch (DataIntegrityViolationException dup) {
            return Claim.ALREADY_SENT;
        } catch (RuntimeException e) {
            log.error("Reminder claim failed — reminder NOT sent this run: tenant={} type={} key={}: {}",
                    clientId, reminderType, referenceKey, LogSafe.exception(e), e);
            return Claim.FAILED;
        }
    }
}
