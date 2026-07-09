package com.churchgeniuspro.plaid.util;

import com.churchgeniuspro.plaid.entity.ChurchPlaidSetting;
import com.churchgeniuspro.plaid.repository.ChurchPlaidSettingRepository;
import com.churchgeniuspro.plaid.service.BankSyncGateService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Central access checks for the Plaid feature. Returns a human-readable denial
 * reason, or null when access is allowed (same convention as RoleGuard).
 *
 * <p>Enforces three requirements: the church has Plaid enabled, the user holds a
 * financial role, and the user has passed the Bank Sync email-verification gate
 * (or presents a valid trusted-device token). Delegating the verification check
 * to {@link BankSyncGateService} keeps the page gate and the API guard in lockstep
 * so the API cannot be used to bypass verification.
 */
@Component
public class PlaidGuard {

    private final ChurchPlaidSettingRepository settingRepo;
    private final BankSyncGateService gate;

    public PlaidGuard(ChurchPlaidSettingRepository settingRepo,
                      BankSyncGateService gate) {
        this.settingRepo = settingRepo;
        this.gate = gate;
    }

    /** Roles permitted to manage bank connections and review financial transactions. */
    private boolean hasFinancialRole(HttpServletRequest req) {
        String role = SessionUtil.getRole(req);
        return "SuperAdmin".equals(role) || "Admin".equals(role) || "Accountant".equals(role);
    }

    /**
     * Full gate for connecting a bank / accessing Plaid features.
     * Returns a denial reason, or null if allowed.
     */
    public String requireBankAccess(HttpServletRequest req) {
        String clientId = SessionUtil.getAppClientId(req);
        if (clientId == null) return "No active church context.";
        if (!hasFinancialRole(req)) {
            return "You need an Admin or authorized financial role to manage bank connections.";
        }
        if (!isEmailVerified(req)) {
            return "Email verification is required before accessing Bank Sync.";
        }
        if (!isPlaidEnabled(clientId)) {
            return "Bank sync is not enabled for your organization. Contact your administrator.";
        }
        return null;
    }

    /** Whether the church has Plaid turned on (Service-Admin controlled). */
    public boolean isPlaidEnabled(String clientId) {
        return settingRepo.findByClientId(clientId)
                .map(ChurchPlaidSetting::isPlaidEnabled)
                .orElse(false);
    }

    /** Whether automatic sync is enabled for the church (requires Plaid enabled too). */
    public boolean isSyncEnabled(String clientId) {
        return settingRepo.findByClientId(clientId)
                .map(s -> s.isPlaidEnabled() && s.isSyncEnabled())
                .orElse(false);
    }

    /**
     * Whether the current request has passed the Bank Sync verification gate —
     * either verified this session or via a valid 30-day trusted-device token.
     */
    public boolean isEmailVerified(HttpServletRequest req) {
        return gate.isVerified(req);
    }
}
