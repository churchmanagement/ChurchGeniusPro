package com.churchgeniuspro.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side-effect work (e-mail, SMS, push) AFTER the surrounding transaction has
 * committed, or immediately when there is none (error-handling audit 2026-10-28, M14).
 * Inside a transaction a mail send holds the database connection and the rows' locks
 * for up to the SMTP timeout per recipient, and a notification could go out for a
 * write that is then rolled back. Exceptions from the work never surface to the caller.
 */
public final class AfterCommit {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    public static void run(String what, Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { safely(what, work); }
            });
        } else {
            safely(what, work);
        }
    }

    private static void safely(String what, Runnable work) {
        try { work.run(); }
        catch (Exception e) { log.warn("[AfterCommit] {} failed — {}", what, LogSafe.exception(e)); }
    }
}
