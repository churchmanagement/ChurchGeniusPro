package com.churchgeniuspro.ai.guided;

import jakarta.servlet.http.HttpSession;

import java.util.List;

/**
 * 2026-11-11 — every Genius Assistant conversation keeps its unfinished draft on the HTTP session under its own
 * key. Starting a new kind of request must drop the others, so an answer never lands in the wrong conversation.
 * One list here instead of each service knowing all the others.
 */
public final class GuidedDrafts {

    private GuidedDrafts() {}

    public static final String MEETING = "aiMeetingDraft";
    public static final String RECORD  = AiGuidedRecordService.SESSION_KEY;
    public static final String SETTING = AiGuidedSettingService.SESSION_KEY;
    public static final String GROUP   = "aiGroupDraft";
    public static final String PLEDGE  = "aiPledgeDraft";
    public static final String EMAIL   = "aiEmailDraft";
    public static final String ACTION_DRAFT = "aiActionDraft";
    public static final String ACTION_TOKEN = "aiActionPendingToken";

    private static final List<String> ALL = List.of(MEETING, RECORD, SETTING, GROUP, PLEDGE, EMAIL);

    /** Drops every conversation draft except {@code keep} (null = all). */
    public static void clearOthers(HttpSession s, String keep) {
        if (s == null) return;
        for (String k : ALL) if (!k.equals(keep)) s.removeAttribute(k);
    }

    /** True while any conversation (or an action card) waits for an answer. */
    public static boolean anyOpen(HttpSession s) {
        if (s == null) return false;
        for (String k : ALL) if (s.getAttribute(k) != null) return true;
        return s.getAttribute(ACTION_DRAFT) != null || s.getAttribute(ACTION_TOKEN) != null;
    }
}
