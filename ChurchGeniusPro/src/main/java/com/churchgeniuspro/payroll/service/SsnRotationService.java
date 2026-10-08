package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.entity.PayrollAuditLog;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.repository.PayrollAuditLogRepository;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Re-encrypts stored payroll SSN last-4 values onto the current
 * {@code PAYROLL_SSN_ENC_KEY} (security audit 2026-10-07, Phase 5). Mirrors
 * {@code PlaidTokenRotationService}.
 *
 * <p>Every row is first {@link SsnCrypto#classify classified} — BLANK, LEGACY_PLAINTEXT,
 * CURRENT_KEY, PREVIOUS_KEY or UNREADABLE — and only LEGACY_PLAINTEXT and PREVIOUS_KEY
 * rows are rewritten, each in its own transaction so one bad row cannot roll back the
 * rest. Results are counts only: no SSN value and no employee id ever leaves the
 * server. An unreadable row is logged server-side by employee id so it can be
 * investigated, with no value.
 *
 * <p>Refuses to run (status {@code error}) when the current key is the derived
 * development key: there is nothing safe to rotate onto.
 */
@Service
public class SsnRotationService {

    private static final Logger log = LoggerFactory.getLogger(SsnRotationService.class);

    private final PayrollEmployeeRepository employeeRepo;
    private final PayrollAuditLogRepository auditRepo;
    private final SsnCrypto cipher;
    private final SsnRotationService self;   // transactional proxy for per-row commits

    public SsnRotationService(PayrollEmployeeRepository employeeRepo,
                              PayrollAuditLogRepository auditRepo,
                              SsnCrypto cipher,
                              @org.springframework.context.annotation.Lazy SsnRotationService self) {
        this.employeeRepo = employeeRepo;
        this.auditRepo = auditRepo;
        this.cipher = cipher;
        this.self = self == null ? this : self;
    }

    /** What a rotation would do. Read-only. */
    public Map<String, Object> status() {
        return rotate(true, null);
    }

    /**
     * @param dryRun true = count only, write nothing
     * @param actor  who asked, for the audit trail (may be null)
     */
    public Map<String, Object> rotate(boolean dryRun, String actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("rotationInProgress", cipher.rotationInProgress());
        if (!cipher.isConfigured()) {
            out.put("status", "error");
            out.put("message", "PAYROLL_SSN_ENC_KEY is not set — nothing to rotate onto.");
            return out;
        }
        int examined = 0, blank = 0, legacyPlaintext = 0, alreadyCurrent = 0, rotated = 0, unreadable = 0;
        Map<String, Integer> rotatedPerTenant = new TreeMap<>();
        for (PayrollEmployee e : employeeRepo.findAll()) {
            examined++;
            SsnCrypto.State state = cipher.classify(e.getSsnLast4());
            switch (state) {
                case BLANK -> blank++;
                case CURRENT_KEY -> alreadyCurrent++;
                case UNREADABLE -> {
                    unreadable++;
                    log.warn("SSN rotation: payroll employee {} (client {}) is readable with neither key", e.getId(), e.getAppClientId());
                }
                case LEGACY_PLAINTEXT, PREVIOUS_KEY -> {
                    if (state == SsnCrypto.State.LEGACY_PLAINTEXT) legacyPlaintext++;
                    if (dryRun) { rotated++; continue; }
                    try {
                        self.reEncryptOne(e.getId());
                        rotated++;
                        rotatedPerTenant.merge(e.getAppClientId() == null ? "" : e.getAppClientId(), 1, Integer::sum);
                    } catch (Exception ex) {
                        // One bad row must not cost the rest their rotation.
                        unreadable++;
                        log.warn("SSN rotation: payroll employee {} could not be re-encrypted — {}", e.getId(), ex.getMessage());
                    }
                }
            }
        }
        out.put("status", "success");
        out.put("examined", examined);
        out.put("blank", blank);
        out.put("legacyPlaintext", legacyPlaintext);
        out.put("alreadyOnCurrentKey", alreadyCurrent);
        out.put(dryRun ? "wouldRotate" : "rotated", rotated);
        out.put("unreadable", unreadable);
        out.put("pending", dryRun ? rotated : 0);
        if (!dryRun) {
            String who = actor == null || actor.isBlank() ? "SERVICE_ADMIN" : actor;
            rotatedPerTenant.forEach((clientId, n) -> {
                PayrollAuditLog row = new PayrollAuditLog();
                row.setAppClientId(clientId.isEmpty() ? null : clientId);
                row.setEntityType("PayrollEmployee");
                row.setEntityId(null);
                row.setAction("SSN_KEY_ROTATED");
                row.setActor(who);
                row.setDetails("Re-encrypted " + n + " SSN last-4 value(s) onto the current key");
                auditRepo.save(row);
            });
        }
        log.info("SSN rotation {}: examined={} blank={} legacyPlaintext={} alreadyCurrent={} {}={} unreadable={}",
                dryRun ? "(dry run)" : "(applied)", examined, blank, legacyPlaintext, alreadyCurrent,
                dryRun ? "wouldRotate" : "rotated", rotated, unreadable);
        return out;
    }

    /** One row, one transaction. The plaintext exists only inside {@link SsnCrypto#reEncrypt}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reEncryptOne(Long employeeId) {
        PayrollEmployee e = employeeRepo.findById(employeeId).orElseThrow();
        e.setSsnLast4(cipher.reEncrypt(e.getSsnLast4()));
        employeeRepo.save(e);
    }
}
