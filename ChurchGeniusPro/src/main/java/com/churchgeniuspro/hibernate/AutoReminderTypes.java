package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Look-up table that defines every reminder type offered by the system.
 *
 * <p>IDs are stable and used by {@code auto_reminder.reminder_type_id}:
 * <ul>
 *   <li>1  — Meeting Reminder</li>
 *   <li>2  — Weekly Meeting Reminder</li>
 *   <li>3  — Birthday</li>
 *   <li>4  — Wedding Anniversary</li>
 *   <li>5  — Monthly Birthday &amp; Wedding</li>
 *   <li>6  — Monthly Statement</li>
 *   <li>7  — New Year</li>
 *   <li>8  — Christmas</li>
 *   <li>9  — US Independence</li>
 *   <li>10 — Thanksgiving</li>
 *   <li>11 — Veterans Day</li>
 *   <li>12 — Presidents Day</li>
 *   <li>13 — Memorial Day</li>
 *   <li>14 — Labor Day</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "auto_reminder_types")
public class AutoReminderTypes {

    /** Stable numeric identifier — NOT auto-generated; seeded via SQL. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "name", nullable = false)
    private String name;
}
