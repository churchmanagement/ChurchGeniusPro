package com.churchgeniuspro.ai.action;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

/**
 * 2026-11-11 — lets a guided conversation carry on after its review card was decided, whichever way the user
 * decided it (the card's button or a typed / spoken "yes" / "no"). The proposal is already EXECUTED (or
 * REJECTED) when these run; an implementation only adds to the RESPONSE (a follow-up question, choices,
 * {@code pending}) and moves its own session draft on. It must never write records itself and must ignore
 * tokens that are not its own.
 */
public interface AiActionFollowUp {

    /** After a successful Save. {@code result} is what the tool returned; {@code response} is what the user will see. */
    default void confirmed(HttpServletRequest req, String clientId, String token, String tool,
                           Map<String, Object> result, Map<String, Object> response) { }

    /** After a Cancel on the card. */
    default void rejected(HttpServletRequest req, String clientId, String token, Map<String, Object> response) { }
}
