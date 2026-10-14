package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Donation Review — who an online donation belongs to (approval decision 3):
 * link only when unambiguous, default to Visitor otherwise, never create anyone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContributorMatcher — unambiguous match or Visitor")
class ContributorMatcherTest {

    private static final String CLIENT = "CHR-1";

    @Mock FamilyMemberRepository repo;
    ContributorMatcher matcher;

    FamilyMember binny, binny2, priya;

    @BeforeEach
    void setUp() {
        matcher = new ContributorMatcher(repo);
        binny  = member(11, "Binny", "Varghese", "binny@example.org", "(555) 010-6676", "Member");
        binny2 = member(12, "Binny", "Varghese", "binny.v@example.org", null, "Guest");
        priya  = member(21, "Priya", "Paul", "priya@example.org", "555-010-2291", "Visitor");
        when(repo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());
        when(repo.findActiveByNameAndTenant(anyString(), anyString(), eq(CLIENT))).thenReturn(List.of());
        when(repo.findActiveWithPhoneByTenant(CLIENT)).thenReturn(List.of(binny, priya));
    }

    static FamilyMember member(int id, String first, String last, String email, String phone, String type) {
        FamilyMember m = new FamilyMember();
        m.setId(id); m.setFirstName(first); m.setLastName(last);
        m.setEmail(email); m.setPhone(phone); m.setMemberType(type);
        m.setAppClientId(CLIENT);
        return m;
    }

    static Donation donor(String first, String last, String email, String phone) {
        Donation d = new Donation();
        d.setClientId(CLIENT); d.setFirstName(first); d.setLastName(last);
        d.setEmail(email); d.setPhone(phone);
        return d;
    }

    @Test
    @DisplayName("one person with that email, same name → that person, their own category")
    void emailMatchLinksPerson() {
        when(repo.findActiveByEmailAndTenant("binny@example.org", CLIENT)).thenReturn(List.of(binny));
        when(repo.findActiveByNameAndTenant("Binny", "Varghese", CLIENT)).thenReturn(List.of(binny, binny2));
        ContributorMatcher.Match m = matcher.match(donor("Binny", "Varghese", "binny@example.org", null), CLIENT);
        assertThat(m.matched()).isTrue();
        assertThat(m.memberId()).isEqualTo(11);
        assertThat(m.category()).isEqualTo("Member");
        assertThat(m.reason()).isEqualTo(ContributorMatcher.Reason.EMAIL_MATCH);
    }

    @Test
    @DisplayName("email is upper/lower-cased and padded by the donor → still matches (repo is asked for the trimmed value)")
    void emailIsTrimmed() {
        when(repo.findActiveByEmailAndTenant("Binny@Example.org", CLIENT)).thenReturn(List.of(binny));
        ContributorMatcher.Match m = matcher.match(donor("Binny", "Varghese", "  Binny@Example.org ", null), CLIENT);
        assertThat(m.matched()).isTrue();
        verify(repo).findActiveByEmailAndTenant("Binny@Example.org", CLIENT);
    }

    @Test
    @DisplayName("phone matches on digits only, with or without the US country code")
    void phoneMatchOnDigits() {
        ContributorMatcher.Match m = matcher.match(donor("Priya", "Paul", null, "+1 555 010 2291"), CLIENT);
        assertThat(m.matched()).isTrue();
        assertThat(m.memberId()).isEqualTo(21);
        assertThat(m.category()).isEqualTo("Visitor");          // her Family-page category, whatever it is
        assertThat(m.reason()).isEqualTo(ContributorMatcher.Reason.PHONE_MATCH);
    }

    @Test
    @DisplayName("two people share the donor's email (a family inbox) → no link, Visitor")
    void sharedInboxIsAmbiguous() {
        when(repo.findActiveByEmailAndTenant("family@example.org", CLIENT)).thenReturn(List.of(binny, priya));
        ContributorMatcher.Match m = matcher.match(donor("Binny", "Varghese", "family@example.org", null), CLIENT);
        assertThat(m.matched()).isFalse();
        assertThat(m.category()).isEqualTo("Visitor");
        assertThat(m.reason()).isEqualTo(ContributorMatcher.Reason.AMBIGUOUS);
    }

    @Test
    @DisplayName("name matches Priya but the email given belongs to nobody with that name → Visitor, no link")
    void nameMatchesButContactConflicts() {
        when(repo.findActiveByNameAndTenant("Priya", "Paul", CLIENT)).thenReturn(List.of(priya));
        ContributorMatcher.Match m = matcher.match(donor("Priya", "Paul", "someone-else@example.org", null), CLIENT);
        assertThat(m.matched()).isFalse();
        assertThat(m.category()).isEqualTo("Visitor");
        assertThat(m.reason()).isEqualTo(ContributorMatcher.Reason.NAME_MATCHES_CONTACT_CONFLICTS);
    }

    @Test
    @DisplayName("email belongs to Binny but the donor typed Priya's name → conflict, no link")
    void contactHitsOnePersonNameAnother() {
        when(repo.findActiveByEmailAndTenant("binny@example.org", CLIENT)).thenReturn(List.of(binny));
        when(repo.findActiveByNameAndTenant("Priya", "Paul", CLIENT)).thenReturn(List.of(priya));
        ContributorMatcher.Match m = matcher.match(donor("Priya", "Paul", "binny@example.org", null), CLIENT);
        assertThat(m.matched()).isFalse();
        assertThat(m.reason()).isEqualTo(ContributorMatcher.Reason.NAME_MATCHES_CONTACT_CONFLICTS);
    }

    @Test
    @DisplayName("nobody matches anything → Visitor, no link")
    void noMatchIsVisitor() {
        ContributorMatcher.Match m = matcher.match(donor("New", "Person", "new@example.org", "555 999 0000"), CLIENT);
        assertThat(m.matched()).isFalse();
        assertThat(m.category()).isEqualTo("Visitor");
        assertThat(m.reason()).isEqualTo(ContributorMatcher.Reason.NO_MATCH);
    }

    @Test
    @DisplayName("a name alone, with no email or phone to confirm it, is never enough to link")
    void nameAloneIsNotEnough() {
        when(repo.findActiveByNameAndTenant("Binny", "Varghese", CLIENT)).thenReturn(List.of(binny));
        ContributorMatcher.Match m = matcher.match(donor("Binny", "Varghese", null, null), CLIENT);
        assertThat(m.matched()).isFalse();
        assertThat(m.reason()).isEqualTo(ContributorMatcher.Reason.NO_IDENTITY);
    }

    @Test
    @DisplayName("an anonymous donation (no name, no contact) → Visitor without touching the repository")
    void anonymous() {
        ContributorMatcher.Match m = matcher.match(donor(null, null, null, null), CLIENT);
        assertThat(m.matched()).isFalse();
        assertThat(m.reason()).isEqualTo(ContributorMatcher.Reason.NO_IDENTITY);
        verify(repo, never()).findActiveByEmailAndTenant(anyString(), anyString());
        verify(repo, never()).findActiveWithPhoneByTenant(anyString());
    }

    @Test
    @DisplayName("the matcher only reads — it never saves a family member")
    void neverWrites() {
        when(repo.findActiveByEmailAndTenant("new@example.org", CLIENT)).thenReturn(List.of());
        matcher.match(donor("New", "Person", "new@example.org", null), CLIENT);
        verify(repo, never()).save(any());
        verify(repo, never()).saveAll(any());
    }

    @Test
    @DisplayName("a reviewer's hand-picked person must be this church's and active")
    void verifyChosen() {
        when(repo.findByIdAndTenant(11, CLIENT)).thenReturn(Optional.of(binny));
        when(repo.findByIdAndTenant(99, CLIENT)).thenReturn(Optional.empty());   // another church's id
        FamilyMember inactive = member(31, "Old", "Member", null, null, "Member");
        inactive.setInactive(true);
        when(repo.findByIdAndTenant(31, CLIENT)).thenReturn(Optional.of(inactive));

        assertThat(matcher.verifyChosen(11, CLIENT)).isPresent();
        assertThat(matcher.verifyChosen(99, CLIENT)).isEmpty();
        assertThat(matcher.verifyChosen(31, CLIENT)).isEmpty();
        assertThat(matcher.verifyChosen(null, CLIENT)).isEmpty();
    }

    @Test
    @DisplayName("category falls back to Member when the Family record has none, and is canonicalised")
    void categoryOf() {
        assertThat(ContributorMatcher.categoryOf(member(1, "a", "b", null, null, null))).isEqualTo("Member");
        assertThat(ContributorMatcher.categoryOf(member(1, "a", "b", null, null, "guest"))).isEqualTo("Guest");
        assertThat(ContributorMatcher.isCategory("visitor")).isTrue();
        assertThat(ContributorMatcher.isCategory("Donor")).isFalse();
    }
}
