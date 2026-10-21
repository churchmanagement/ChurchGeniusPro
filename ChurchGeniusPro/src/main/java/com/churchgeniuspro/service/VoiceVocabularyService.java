package com.churchgeniuspro.service;

import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.util.MemberNameUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the vocabulary hint ("prompt") sent with each transcription request.
 *
 * <p>The transcription model guesses uncommon words — a contributor's name — as common
 * ones ("Anson" → "Handsome", "Mathew" → "Matthew"). The API's {@code prompt} field is
 * its own mechanism for biasing recognition toward expected words, at no extra cost,
 * so each request carries the words the page is about to match against: the church's
 * contributor names when the Contributor field is being filled, its fund names for
 * Fund, its payment methods for Method, and the field/command words in every case.
 *
 * <p>The hint is capped (the service reads at most ~224 tokens of it), so a large
 * church gets the first names in alphabetical order rather than all of them; the
 * page-side fuzzy matching still handles the rest.
 */
@Service
public class VoiceVocabularyService {

    /** Rough character budget for ~224 tokens of short names. */
    public static final int MAX_CHARS = 900;

    static final String FIELD_WORDS_INCOME  = "Set Contributor, Set Fund, Set Amount, Set Date, Set Method, Set Reference, Set Note, Done, Clear, Reset Form, Save Now.";
    static final String FIELD_WORDS_EXPENSE = "Set Expense, Set Fund, Set Amount, Set Date, Set Method, Set Reference, Set Note, Done, Clear, Reset Form, Save Now.";

    private final FamilyMemberRepository memberRepo;
    private final SubSourceRepository subSourceRepo;
    private final TransactionTypeRepository transactionTypeRepo;

    public VoiceVocabularyService(FamilyMemberRepository memberRepo, SubSourceRepository subSourceRepo,
                                  TransactionTypeRepository transactionTypeRepo) {
        this.memberRepo = memberRepo;
        this.subSourceRepo = subSourceRepo;
        this.transactionTypeRepo = transactionTypeRepo;
    }

    /**
     * The hint for one request.
     * @param clientId  the church
     * @param context   page context (income | expense | home | meeting …)
     * @param expecting the field being filled ("contributor", "fund", "method", "amount", …) or blank
     */
    public String promptFor(String clientId, String context, String expecting) {
        String ctx = context == null ? "" : context.trim().toLowerCase(Locale.ROOT);
        String exp = expecting == null ? "" : expecting.trim().toLowerCase(Locale.ROOT);
        if (clientId == null || clientId.isBlank()) return null;
        if (!(ctx.equals("income") || ctx.equals("expense"))) return null;

        StringBuilder sb = new StringBuilder("ChurchGeniusPro ").append(ctx).append(" entry. ");
        sb.append(ctx.equals("expense") ? FIELD_WORDS_EXPENSE : FIELD_WORDS_INCOME);

        List<String> names = new ArrayList<>();
        if (ctx.equals("income") && (exp.isEmpty() || exp.equals("contributor"))) names.addAll(contributorNames(clientId));
        if (exp.isEmpty() || exp.equals("fund"))   names.addAll(fundNames(clientId));
        if (exp.isEmpty() || exp.equals("method")) names.addAll(methodNames(clientId));
        if (exp.equals("amount")) sb.append(" Amounts in dollars, e.g. 100, 250.50, one hundred fifty.");

        if (!names.isEmpty()) {
            sb.append(" Names: ");
            int n = 0;
            for (String name : names) {
                if (sb.length() + name.length() + 2 > MAX_CHARS) break;
                if (n++ > 0) sb.append(", ");
                sb.append(name);
            }
            sb.append('.');
        }
        return sb.toString();
    }

    List<String> contributorNames(String clientId) {
        Set<String> out = new LinkedHashSet<>();
        try {
            memberRepo.findContributorsByAppUser(clientId).forEach(m ->
                    add(out, MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName())));
        } catch (RuntimeException ignored) { /* a hint is best-effort */ }
        return new ArrayList<>(out);
    }

    List<String> fundNames(String clientId) {
        Set<String> out = new LinkedHashSet<>();
        try { subSourceRepo.findAllActiveByAppUser(clientId).forEach(s -> add(out, s.getSourceName())); }
        catch (RuntimeException ignored) { }
        return new ArrayList<>(out);
    }

    List<String> methodNames(String clientId) {
        Set<String> out = new LinkedHashSet<>();
        try { transactionTypeRepo.findActiveByAppUser(clientId).forEach(t -> add(out, t.getTypeName())); }
        catch (RuntimeException ignored) { }
        return new ArrayList<>(out);
    }

    private static void add(Set<String> out, String s) {
        if (s == null) return;
        String t = s.trim().replaceAll("\\s+", " ");
        if (!t.isEmpty()) out.add(t);
    }

    /**
     * The transcription language for a user's locale preference. The service accepts an
     * ISO-639-1 language only, so every English variety maps to "en"; an unknown or
     * non-English value falls back to the configured default (null = keep it).
     */
    public static String languageFor(String locale) {
        if (locale == null || locale.isBlank()) return null;
        String l = locale.trim().toLowerCase(Locale.ROOT);
        if (l.startsWith("en")) return "en";
        return null;
    }
}
