package com.churchgeniuspro.membership;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the empty member portal found during the Play Store
 * screenshot pass (2026-10-15).
 *
 * <p>{@code MemberSessionResolver.resolve} runs outside a transaction and reads
 * {@code self.getFamily().getAppClientId()}. {@code FamilyMember.family} is
 * {@code FetchType.LAZY} and {@code spring.jpa.open-in-view=false}, so any lookup
 * the resolver uses must JOIN FETCH the family or every member-portal request
 * after the first ({@code memberId} already in the session) fails with
 * {@code LazyInitializationException} — {@code /api/member/family} and
 * {@code /api/member/give/config} returned 500 and the portal rendered blank.
 *
 * <p>A Mockito test cannot see this (mocks never return proxies), so the guard is
 * on the query text: both resolver lookups must fetch the family eagerly.
 */
@DisplayName("Member lookups used by MemberSessionResolver must JOIN FETCH family")
class MemberLookupFetchesFamilyTest {

    private static final Path REPO = Path.of("src/main/java/com/churchgeniuspro/repository/FamilyMemberRepository.java");

    @Test
    @DisplayName("findByIdAndTenant fetches the family (session re-read on every member request)")
    void findByIdAndTenantFetchesFamily() throws Exception {
        assertThat(queryFor("findByIdAndTenant")).containsIgnoringCase("LEFT JOIN FETCH m.family");
    }

    @Test
    @DisplayName("findByMemberRef fetches the family (first resolve after login)")
    void findByMemberRefFetchesFamily() throws Exception {
        assertThat(queryFor("findByMemberRef")).containsIgnoringCase("LEFT JOIN FETCH m.family");
    }

    @Test
    @DisplayName("findActiveByEmailAndTenant fetches the family (staff login resolved by e-mail)")
    void findActiveByEmailAndTenantFetchesFamily() throws Exception {
        assertThat(queryFor("findActiveByEmailAndTenant")).containsIgnoringCase("LEFT JOIN FETCH m.family");
    }

    /** The @Query string literal immediately preceding the named method. */
    private static String queryFor(String method) throws Exception {
        String src = Files.readString(REPO);
        Pattern p = Pattern.compile("@Query\\(((?:\\s*\"[^\"]*\"\\s*\\+?)+)\\)\\s*[\\w.<>]+\\s+" + method + "\\(");
        Matcher m = p.matcher(src);
        assertThat(m.find()).as("@Query before %s", method).isTrue();
        return m.group(1).replace("\"", "").replace("+", " ").replaceAll("\\s+", " ");
    }
}
