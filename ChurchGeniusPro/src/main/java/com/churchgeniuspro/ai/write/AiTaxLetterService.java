package com.churchgeniuspro.ai.write;

import com.churchgeniuspro.service.FinancialReportLetterService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 2026-11-20 — the "AI draft" of the Year-End Tax Report letter. The model writes ONLY the wording around the
 * contribution table: a thank-you paragraph above it and a closing paragraph below it.
 *
 * <p>What it is given: nothing about anybody. No names, addresses, gifts, totals, church name or year — it writes
 * with the placeholders {ChurchName} and {Year}, which are filled in here afterwards. The contribution table and the
 * total are drawn from the database by the same code as the default letter, and the tax notice ("No goods or
 * services were provided…") is the fixed built-in sentence, not the model's. So an AI draft cannot contain, change or
 * invent a financial figure: wording that carries a dollar sign or a number is refused.
 *
 * <p>The result is a draft to review; nothing is stored. A church that likes it saves it as its own letter text on
 * the Tax Report page, through the page's existing editor.
 */
@Service
public class AiTaxLetterService {

    private static final Logger log = LoggerFactory.getLogger(AiTaxLetterService.class);

    public static final int MAX_PART = 900;

    /** The tax notice every statement carries — fixed text, the same as the built-in letter's. */
    public static final String TAX_NOTICE_HTML =
            "<div class=\"ltr-disclaimer\"><strong>Important Tax Notice:</strong> No goods or "
          + "services were provided to you in exchange for these contributions. This statement "
          + "is provided in accordance with IRS requirements for charitable contributions. "
          + "Please retain this letter for your tax records. We recommend consulting a qualified "
          + "tax professional for guidance on deductibility.</div>";

    static final String SYSTEM =
            "You write the wording of a church's year-end charitable contribution statement letter. "
          + "Return JSON only: {\"intro\": \"...\", \"closing\": \"...\"}. "
          + "\"intro\" is one short paragraph (2 to 3 sentences) placed above a table of the donor's gifts: it thanks the donor "
          + "for giving to {ChurchName} during the {Year} calendar year and says this letter is their official contribution "
          + "statement for income tax purposes. \"closing\" is one short paragraph (2 to 3 sentences) placed below the table: "
          + "warm thanks for their partnership and support of the ministry. "
          + "Rules: write the church as the exact placeholder {ChurchName} and the year as the exact placeholder {Year}; "
          + "never write a donor's name, a greeting line, a signature, an address, an amount, a total, a number, a date or a "
          + "dollar sign; do not give tax advice and do not restate the tax notice (it is added separately); plain text only, "
          + "no markdown, no HTML; professional and warm; American English.";

    private static final Pattern FIGURE = Pattern.compile("[$\\d]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*(church\\s*_?\\s*name|year)\\s*\\}", Pattern.CASE_INSENSITIVE);

    private final OpenAiVoiceService openAi;

    public AiTaxLetterService(OpenAiVoiceService openAi) { this.openAi = openAi; }

    public boolean isEnabled() { return openAi != null && openAi.isEnabled(); }

    /**
     * @return introHtml, closingHtml (placeholders filled, text escaped), or null when no usable draft came back
     */
    public Map<String, String> draft(String churchName, int year, String tone) {
        if (!isEnabled()) return null;
        String ask = "Write the intro and closing." + (tone == null || tone.isBlank() ? "" : " Tone: " + tone.trim().toLowerCase() + ".");
        JsonNode node;
        try { node = openAi.chatJson(SYSTEM, ask, 0.6); }
        catch (RuntimeException e) { log.warn("[TaxLetter] draft failed: {}", e.getClass().getSimpleName()); return null; }
        if (node == null) return null;
        String intro = clean(node.path("intro").asText("")), closing = clean(node.path("closing").asText(""));
        if (!usable(intro) || !usable(closing)) { log.info("[TaxLetter] draft refused (empty, too long, or carries a figure)"); return null; }
        return render(intro, closing, churchName, year);
    }

    /** Wording → the letter's HTML. Text is escaped; only the placeholders are filled in. */
    static Map<String, String> render(String intro, String closing, String churchName, int year) {
        Map<String, String> filled = FinancialReportLetterService.fillPlaceholders(
                paragraphs(intro, "ltr-body"), TAX_NOTICE_HTML + paragraphs(closing, "ltr-closing"), churchName, year);
        Map<String, String> out = new LinkedHashMap<>(filled);
        out.put("draft", "true");
        return out;
    }

    /** Usable = some text, not too long, and no figure of any kind once the placeholders are set aside. */
    static boolean usable(String s) {
        if (s == null || s.isBlank() || s.length() > MAX_PART) return false;
        return !FIGURE.matcher(PLACEHOLDER.matcher(s).replaceAll("")).find();
    }

    static String clean(String s) {
        return s == null ? "" : AiWritingService.sanitize(s, MAX_PART + 200).trim();
    }

    private static String paragraphs(String text, String cls) {
        StringBuilder sb = new StringBuilder();
        for (String p : text.split("\\n\\s*\\n|\\r?\\n")) {
            if (p.isBlank()) continue;
            sb.append("<p class=\"").append(cls).append("\">").append(HtmlUtils.htmlEscape(p.trim())).append("</p>");
        }
        return sb.toString();
    }
}
