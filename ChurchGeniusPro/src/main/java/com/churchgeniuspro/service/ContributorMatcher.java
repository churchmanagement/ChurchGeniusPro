package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which existing person, if any, an online donation belongs to, and the
 * contributor category to default the review to (approval decision 3).
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>Exactly one active member of this church shares the donor's email
 *       <em>or</em> phone, and the donor's name does not point at a different
 *       person → that member; category = their Family-page category.</li>
 *   <li>The name matches someone, but the email/phone the donor gave belongs to
 *       nobody with that name → no match; category = Visitor (name matches,
 *       contact conflicts).</li>
 *   <li>Anything else — nobody matches, or two people do → no match; category = Visitor.</li>
 * </ol>
 * The matcher never creates or edits a family member; it only reads. A reviewer can
 * override the result by naming a person, which {@link DonationPostingService}
 * verifies belongs to the same church.
 *
 * <p>The email rule is the one the former automatic posting used (one active member
 * with that email, else guest); phone and the name-conflict rule are new.
 */
@Service
public class ContributorMatcher {

    public static final String CATEGORY_MEMBER  = "Member";
    public static final String CATEGORY_GUEST   = "Guest";
    public static final String CATEGORY_VISITOR = "Visitor";
    public static final List<String> CATEGORIES = List.of(CATEGORY_MEMBER, CATEGORY_GUEST, CATEGORY_VISITOR);

    /** Why the match came out as it did — shown on the review screen. */
    public enum Reason {
        EMAIL_MATCH, PHONE_MATCH, EMAIL_AND_PHONE_MATCH,
        NAME_MATCHES_CONTACT_CONFLICTS, AMBIGUOUS, NO_MATCH, NO_IDENTITY
    }

    public record Match(FamilyMember member, String category, Reason reason) {
        public boolean matched() { return member != null; }
        public Integer memberId() { return member != null ? member.getId() : null; }
        public String memberName() {
            if (member == null) return null;
            return ((member.getFirstName() == null ? "" : member.getFirstName()) + " "
                  + (member.getLastName()  == null ? "" : member.getLastName())).trim();
        }
        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("matched",    matched());
            m.put("memberId",   memberId());
            m.put("memberName", memberName());
            m.put("category",   category);
            m.put("reason",     reason.name());
            return m;
        }
    }

    private final FamilyMemberRepository memberRepo;

    public ContributorMatcher(FamilyMemberRepository memberRepo) {
        this.memberRepo = memberRepo;
    }

    public Match match(Donation d, String clientId) {
        String email  = norm(d.getEmail());
        String digits = digits(d.getPhone());
        String first  = norm(d.getFirstName());
        String last   = norm(d.getLastName());

        if (email == null && digits == null && (first == null || last == null)) {
            return new Match(null, CATEGORY_VISITOR, Reason.NO_IDENTITY);
        }

        List<FamilyMember> emailHits = email == null ? List.of() : memberRepo.findActiveByEmailAndTenant(email, clientId);
        List<FamilyMember> phoneHits = new ArrayList<>();
        if (digits != null && digits.length() >= 7) {
            for (FamilyMember m : memberRepo.findActiveWithPhoneByTenant(clientId)) {
                String md = digits(m.getPhone());
                if (md != null && sameNumber(md, digits)) phoneHits.add(m);
            }
        }
        List<FamilyMember> nameHits = (first == null || last == null) ? List.of()
                : memberRepo.findActiveByNameAndTenant(first, last, clientId);

        // Distinct people reachable by the contact details the donor gave.
        Map<Integer, FamilyMember> byContact = new LinkedHashMap<>();
        for (FamilyMember m : emailHits) byContact.put(m.getId(), m);
        for (FamilyMember m : phoneHits) byContact.put(m.getId(), m);

        if (byContact.size() == 1) {
            FamilyMember person = byContact.values().iterator().next();
            boolean nameAgrees = nameHits.isEmpty() || nameHits.stream().anyMatch(n -> n.getId().equals(person.getId()));
            if (nameAgrees) {
                boolean e = emailHits.stream().anyMatch(m -> m.getId().equals(person.getId()));
                boolean p = phoneHits.stream().anyMatch(m -> m.getId().equals(person.getId()));
                Reason r = e && p ? Reason.EMAIL_AND_PHONE_MATCH : e ? Reason.EMAIL_MATCH : Reason.PHONE_MATCH;
                return new Match(person, categoryOf(person), r);
            }
            // The email/phone belongs to one person, the name to another: don't guess.
            return new Match(null, CATEGORY_VISITOR, Reason.NAME_MATCHES_CONTACT_CONFLICTS);
        }
        if (byContact.size() > 1) {
            return new Match(null, CATEGORY_VISITOR, Reason.AMBIGUOUS);
        }
        // No contact hit at all.
        if (!nameHits.isEmpty() && (email != null || digits != null)) {
            // Someone has this name. If they have an email/phone on file it is not the one
            // the donor gave — a conflict. If they have none on file there is nothing to
            // confirm the identity with. Either way: don't credit them automatically.
            boolean conflicts = nameHits.stream().anyMatch(n -> norm(n.getEmail()) != null || digits(n.getPhone()) != null);
            return new Match(null, CATEGORY_VISITOR, conflicts ? Reason.NAME_MATCHES_CONTACT_CONFLICTS : Reason.NO_IDENTITY);
        }
        if (nameHits.size() == 1 && email == null && digits == null) {
            // Name only, one person, nothing to contradict it — still not enough to
            // credit a statement on a name alone (the brief: incomplete identity ⇒ no auto-link).
            return new Match(null, CATEGORY_VISITOR, Reason.NO_IDENTITY);
        }
        return new Match(null, CATEGORY_VISITOR, Reason.NO_MATCH);
    }

    /** A person the reviewer chose by hand — must be this church's, active, not deleted. */
    public Optional<FamilyMember> verifyChosen(Integer memberId, String clientId) {
        if (memberId == null) return Optional.empty();
        return memberRepo.findByIdAndTenant(memberId, clientId).filter(m -> !m.isInactive());
    }

    /** The Family page's category for a person, defaulting to Member when blank (as the page does). */
    public static String categoryOf(FamilyMember m) {
        String t = m.getMemberType();
        if (t == null || t.isBlank()) return CATEGORY_MEMBER;
        for (String c : CATEGORIES) if (c.equalsIgnoreCase(t.trim())) return c;
        return t.trim();
    }

    public static boolean isCategory(String s) {
        return s != null && CATEGORIES.stream().anyMatch(c -> c.equalsIgnoreCase(s.trim()));
    }

    public static String canonicalCategory(String s) {
        for (String c : CATEGORIES) if (c.equalsIgnoreCase(s.trim())) return c;
        return s;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    static String norm(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Digits only; null when fewer than 7 remain (not a phone number). */
    static String digits(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        return d.length() >= 7 ? d : null;
    }

    /** Equal, or equal once a leading US country code is dropped from either side. */
    static boolean sameNumber(String a, String b) {
        if (a.equals(b)) return true;
        String a10 = a.length() == 11 && a.startsWith("1") ? a.substring(1) : a;
        String b10 = b.length() == 11 && b.startsWith("1") ? b.substring(1) : b;
        return a10.equals(b10);
    }
}
