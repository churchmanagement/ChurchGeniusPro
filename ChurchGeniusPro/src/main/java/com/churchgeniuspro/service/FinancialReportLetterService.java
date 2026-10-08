package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FinancialReportLetter;
import com.churchgeniuspro.repository.FinancialReportLetterRepository;
import com.churchgeniuspro.util.RichTextSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wording above and below the contribution table on a giving statement.
 *
 * <p>Both passages used to be literals inside two page scripts — the member
 * portal's Financial Report and the staff Year-End Tax Report — which meant every
 * church sent the same letter and none could adjust it. They now come from here,
 * so the two screens agree by construction and a church can say the letter in its
 * own words.
 *
 * <p><b>Absence is the default, not a blank.</b> A church with no stored row gets
 * {@link #DEFAULT_INTRO_HTML} and {@link #DEFAULT_CLOSING_HTML} — word for word
 * the letter as it read before it was editable. Nothing had to be back-filled for
 * existing churches, a church created tomorrow behaves the same, and a failed
 * lookup falls back to the same text rather than printing an empty statement.
 * "Restore the original wording" is therefore just the removal of a row.
 *
 * <p>Two placeholders are substituted at render time: {@code {ChurchName}} and
 * {@code {Year}}. They are what let one stored passage serve every member and
 * every tax year, and their values are HTML-escaped on the way in.
 */
@Service
public class FinancialReportLetterService {

    private static final Logger log = LoggerFactory.getLogger(FinancialReportLetterService.class);

    /** The paragraph above the contribution table, as the letter has always read. */
    public static final String DEFAULT_INTRO_HTML =
            "<p class=\"ltr-body\">Thank you for your faithful giving and generous support to "
          + "<strong>{ChurchName}</strong> during the <strong>{Year}</strong> calendar year. "
          + "This letter serves as your official charitable contribution statement for income "
          + "tax purposes.</p>";

    /** The tax notice and closing paragraph below the contribution table. */
    public static final String DEFAULT_CLOSING_HTML =
            "<div class=\"ltr-disclaimer\"><strong>Important Tax Notice:</strong> No goods or "
          + "services were provided to you in exchange for these contributions. This statement "
          + "is provided in accordance with IRS requirements for charitable contributions. "
          + "Please retain this letter for your tax records. We recommend consulting a qualified "
          + "tax professional for guidance on deductibility.</div>"
          + "<p class=\"ltr-closing\">We are truly grateful for your partnership and continued "
          + "support of our ministry. Your generosity makes a meaningful difference in our "
          + "congregation and community. May God bless you abundantly for your faithfulness.</p>";

    private static final Pattern CHURCH_NAME = Pattern.compile("\\{\\s*church\\s*_?\\s*name\\s*\\}",
                                                               Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR        = Pattern.compile("\\{\\s*year\\s*\\}",
                                                               Pattern.CASE_INSENSITIVE);

    private final FinancialReportLetterRepository repo;

    public FinancialReportLetterService(FinancialReportLetterRepository repo) {
        this.repo = repo;
    }

    /**
     * The letter text as it should appear on screen or in print, ready to write
     * into the page: sanitised when it was stored, placeholders filled in now.
     *
     * <p>Never throws and never returns blank text — a church that cannot be read
     * still gets a correct statement.
     *
     * @return keys {@code introHtml} and {@code closingHtml}
     */
    public Map<String, String> rendered(String clientId, String churchName, Object year) {
        String intro   = DEFAULT_INTRO_HTML;
        String closing = DEFAULT_CLOSING_HTML;
        String sigName = null, sigTitle = null;

        FinancialReportLetter row = find(clientId).orElse(null);
        if (row != null) {
            // A stored row wins even where a passage is empty: a church that
            // deliberately cleared one section means it to stay cleared.
            intro   = row.getIntroHtml()   != null ? row.getIntroHtml()   : "";
            closing = row.getClosingHtml() != null ? row.getClosingHtml() : "";
            sigName  = row.getSignatureName();
            sigTitle = row.getSignatureTitle();
        }

        Map<String, String> out = new LinkedHashMap<>();
        out.put("introHtml",   substitute(intro,   churchName, year));
        out.put("closingHtml", substitute(closing, churchName, year));
        // Signature: the stored text, or the defaults the pages always printed
        // (the church's name and "Finance Department"). Escaped: it is plain text.
        out.put("signatureName",  HtmlUtils.htmlEscape(blank(sigName)  ? nullToEmpty(churchName) : sigName.trim()));
        out.put("signatureTitle", HtmlUtils.htmlEscape(blank(sigTitle) ? DEFAULT_SIGNATURE_TITLE : sigTitle.trim()));
        return out;
    }

    /** The title printed under the signing name unless the church sets its own. */
    public static final String DEFAULT_SIGNATURE_TITLE = "Finance Department";

    /** Longest signature name or title accepted. */
    public static final int MAX_SIGNATURE_LENGTH = 200;

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /**
     * The built-in wording with its placeholders filled in — what a caller shows
     * when this service is not wired up at all, so an unconfigured deployment
     * still prints a correct letter rather than a literal {@code {ChurchName}}.
     */
    public static Map<String, String> defaults(String churchName, Object year) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("introHtml",   substitute(DEFAULT_INTRO_HTML,   churchName, year));
        out.put("closingHtml", substitute(DEFAULT_CLOSING_HTML, churchName, year));
        out.put("signatureName",  HtmlUtils.htmlEscape(nullToEmpty(churchName)));
        out.put("signatureTitle", DEFAULT_SIGNATURE_TITLE);
        return out;
    }

    /**
     * The stored text exactly as authored — placeholders intact — for the editor.
     *
     * @return {@code introHtml}, {@code closingHtml}, and {@code customised}:
     *         false when the church is still on the built-in wording
     */
    public Map<String, Object> forEditing(String clientId) {
        FinancialReportLetter row = find(clientId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("introHtml",   row != null ? nullToEmpty(row.getIntroHtml())   : DEFAULT_INTRO_HTML);
        out.put("closingHtml", row != null ? nullToEmpty(row.getClosingHtml()) : DEFAULT_CLOSING_HTML);
        out.put("customised",  row != null);
        out.put("defaultIntroHtml",   DEFAULT_INTRO_HTML);
        out.put("defaultClosingHtml", DEFAULT_CLOSING_HTML);
        // Blank in the editor means "use the default" (church name / Finance Department).
        out.put("signatureName",  row != null ? nullToEmpty(row.getSignatureName())  : "");
        out.put("signatureTitle", row != null ? nullToEmpty(row.getSignatureTitle()) : "");
        out.put("defaultSignatureTitle", DEFAULT_SIGNATURE_TITLE);
        return out;
    }

    /**
     * Stores this church's wording, replacing anything it had.
     *
     * <p>Both passages are sanitised here rather than at the edge, so no caller can
     * skip it. The tenant is whatever the caller resolved from the session; a blank
     * one is refused outright instead of writing an unowned row.
     *
     * @throws IllegalArgumentException on a blank tenant or over-long text
     */
    public void save(String clientId, String introHtml, String closingHtml, String updatedBy) {
        save(clientId, introHtml, closingHtml, null, null, updatedBy);
    }

    /**
     * As above, with the signature block. {@code signatureName} / {@code signatureTitle}
     * are plain text; blank stores null, which renders as the default. Markup is not
     * accepted in either — they are escaped when printed, so a tag would show literally.
     */
    public void save(String clientId, String introHtml, String closingHtml,
                     String signatureName, String signatureTitle, String updatedBy) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Missing tenant");
        }
        if (length(introHtml) > RichTextSanitizer.MAX_LENGTH
         || length(closingHtml) > RichTextSanitizer.MAX_LENGTH) {
            throw new IllegalArgumentException("Letter text is too long");
        }
        String sigName  = blank(signatureName)  ? null : signatureName.trim();
        String sigTitle = blank(signatureTitle) ? null : signatureTitle.trim();
        if (length(sigName) > MAX_SIGNATURE_LENGTH || length(sigTitle) > MAX_SIGNATURE_LENGTH) {
            throw new IllegalArgumentException("Signature name or title is too long (200 characters max)");
        }

        FinancialReportLetter row = find(clientId).orElseGet(FinancialReportLetter::new);
        row.setAppClientId(clientId);                       // never taken from the request
        row.setIntroHtml(RichTextSanitizer.sanitize(introHtml));
        row.setClosingHtml(RichTextSanitizer.sanitize(closingHtml));
        row.setSignatureName(sigName);
        row.setSignatureTitle(sigTitle);
        row.setUpdatedBy(updatedBy);
        row.setDeleteFlag(false);
        repo.save(row);
        log.info("Financial report letter text saved for {} by {}", clientId, updatedBy);
    }

    /**
     * Drops this church's wording so the built-in default is served again.
     *
     * <p>Soft-deleted rather than removed, matching how the rest of the application
     * retires a row, and scoped to the one tenant.
     *
     * @return true when there was something to restore
     */
    public boolean resetToDefault(String clientId) {
        if (clientId == null || clientId.isBlank()) return false;
        Optional<FinancialReportLetter> row = find(clientId);
        if (row.isEmpty()) return false;
        FinancialReportLetter r = row.get();
        r.setDeleteFlag(true);
        repo.save(r);
        log.info("Financial report letter text restored to default for {}", clientId);
        return true;
    }

    /* ── internals ──────────────────────────────────────────────────────── */

    /**
     * This tenant's row, or empty. A lookup failure is empty too: the letter is
     * worth more than the customisation, so a database problem prints the default
     * wording rather than an error or a blank page.
     */
    private Optional<FinancialReportLetter> find(String clientId) {
        if (clientId == null || clientId.isBlank()) return Optional.empty();
        try {
            return repo.findFirstByAppClientIdAndDeleteFlagFalse(clientId);
        } catch (Exception e) {
            log.warn("Financial report letter lookup failed for {} — {}", clientId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String substitute(String html, String churchName, Object year) {
        if (html == null || html.isEmpty()) return "";
        String name = HtmlUtils.htmlEscape(churchName == null ? "" : churchName);
        String yr   = HtmlUtils.htmlEscape(year == null ? "" : String.valueOf(year));
        String out  = CHURCH_NAME.matcher(html).replaceAll(Matcher.quoteReplacement(name));
        return YEAR.matcher(out).replaceAll(Matcher.quoteReplacement(yr));
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static int length(String s) { return s == null ? 0 : s.length(); }
}
