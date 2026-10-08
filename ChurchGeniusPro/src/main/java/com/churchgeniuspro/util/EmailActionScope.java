package com.churchgeniuspro.util;

import java.util.HashSet;
import java.util.Set;

/**
 * Names the email <em>action</em> a thread is performing — "meeting notification
 * for meeting 7", "birthday reminders for tenant X today" — so that a Trial/Demo
 * tenant whose mail is redirected to its verified test address receives ONE test
 * email per action, however many real recipients the action has.
 *
 * <p>The identity is the action, never the recipient: the dedup key is
 * {@code action | tenant [| sub]}. Two different actions on the same tenant (a
 * meeting notification and then an event reminder) each produce their own test
 * email, even to the same test address. A send made with no scope open is an
 * action of its own.
 *
 * <pre>
 *   try (EmailActionScope s = EmailActionScope.begin("meeting-notify:" + id)) {
 *       for (String to : recipients) emailService.sendOrgEmail(to, subject, body, clientId);
 *       int testEmails = s.testEmailsSent(), simulated = s.simulated();
 *   }
 * </pre>
 *
 * <p>{@link #sub(String)} refines the key inside a wider scope — a scheduler job
 * that reminds about several events sets {@code sub("event:" + id)} per event, so
 * each event is its own test email while a job that wishes 20 birthdays stays one.
 *
 * <p>Thread-bound, like a transaction. Nothing here touches delivery for a tenant
 * that is not redirected; {@code EmailService} consults it only on that path.
 */
public final class EmailActionScope implements AutoCloseable {

    private static final ThreadLocal<EmailActionScope> CURRENT = new ThreadLocal<>();

    private final EmailActionScope parent;
    private final String action;
    private String sub;
    private final Set<String> delivered = new HashSet<>();
    private int testEmailsSent;
    private int simulated;

    private EmailActionScope(EmailActionScope parent, String action) {
        this.parent = parent;
        this.action = action;
    }

    /** Opens a scope for {@code action} on this thread; close it (try-with-resources) when done. */
    public static EmailActionScope begin(String action) {
        EmailActionScope s = new EmailActionScope(CURRENT.get(), action == null ? "" : action);
        CURRENT.set(s);
        return s;
    }

    /** The scope open on this thread, or null. */
    public static EmailActionScope current() {
        return CURRENT.get();
    }

    /** Refines the dedup key for the sends that follow (null clears it). */
    public EmailActionScope sub(String sub) {
        this.sub = (sub == null || sub.isBlank()) ? null : sub;
        return this;
    }

    public String action() { return action; }

    /**
     * Claims the one test email for this action and tenant. True the first time for a
     * key — the caller delivers — and false afterwards, which the caller records with
     * {@link #recordSimulated()} and does not send.
     */
    public boolean claim(String clientId) {
        String key = action + "|" + (clientId == null ? "" : clientId) + (sub != null ? "|" + sub : "");
        return delivered.add(key);
    }

    public void recordTestEmailSent() { testEmailsSent++; }
    public void recordSimulated()     { simulated++; }

    /** Test emails actually delivered within this scope. */
    public int testEmailsSent() { return testEmailsSent; }

    /** Recipients whose copy was represented by an earlier test email and not sent. */
    public int simulated() { return simulated; }

    @Override
    public void close() {
        if (CURRENT.get() == this) {
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
        }
    }
}
