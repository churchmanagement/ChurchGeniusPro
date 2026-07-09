package com.churchgeniuspro.plaid.controller;

import com.churchgeniuspro.plaid.service.BankSyncGateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Page routes for the Bank Sync (Plaid) feature.
 *
 * <p>Access to {@code /bankSync} is gated: on <em>every</em> request the user must
 * be logged in AND have passed the email-verification step (or present a valid
 * 30-day trusted-device token). Otherwise the request is forwarded to the
 * verification page — there is no way to reach the Bank Sync page directly via
 * URL, history, bookmark, or any other path without passing the gate.
 */
@Controller
public class PlaidPageController {

    private final BankSyncGateService gate;

    public PlaidPageController(BankSyncGateService gate) {
        this.gate = gate;
    }

    @GetMapping("/bankSync")
    public String bankSyncPage(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null) return "redirect:/login.html";
        // Bank Sync is a staff feature (needs an app_user); others go home.
        if (!(s.getAttribute("appUserId") instanceof Integer)) return "redirect:/home";
        // Enforce email verification / trusted device on EVERY request.
        if (!gate.isVerified(request)) {
            return "forward:/bankSyncVerify.html";
        }
        return "forward:/plaidReview.html";
    }

    /** The token-verification page (served when the gate has not been passed). */
    @GetMapping("/bankSyncVerify")
    public String bankSyncVerifyPage(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null) return "redirect:/login.html";
        if (!(s.getAttribute("appUserId") instanceof Integer)) return "redirect:/home";
        // Already verified? Skip straight to the page.
        if (gate.isVerified(request)) return "forward:/plaidReview.html";
        return "forward:/bankSyncVerify.html";
    }
}
