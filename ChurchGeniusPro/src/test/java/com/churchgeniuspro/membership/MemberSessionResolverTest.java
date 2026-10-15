package com.churchgeniuspro.membership;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.service.MemberSessionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Item 12 — a staff e-mail that matches several member records (production: the
 * SuperAdmin is Head of four families, three of them one-person duplicates) must
 * resolve to the real household, deterministically, inside the staff user's church.
 */
@DisplayName("MemberSessionResolver — one rule for member, token and staff sessions")
class MemberSessionResolverTest {

    static final String A = "CHURCH-A", B = "CHURCH-B";
    FamilyMemberRepository members;
    AppUserRepository users;
    MemberSessionResolver resolver;

    @BeforeEach
    void setUp() {
        members = mock(FamilyMemberRepository.class);
        users = mock(AppUserRepository.class);
        resolver = new MemberSessionResolver(members, users);
    }

    static Family family(int id, String tenant) { Family f = new Family(); f.setId(id); f.setAppClientId(tenant); return f; }
    static FamilyMember member(int id, Family f, String role, String email) {
        FamilyMember m = new FamilyMember(); m.setId(id); m.setFamily(f); m.setRole(role); m.setEmail(email); m.setAppClientId(f.getAppClientId());
        return m;
    }
    static MockHttpSession staff(int appUserId, String tenant) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "staff@x.org"); s.setAttribute("role", "SuperAdmin"); s.setAttribute("appUserId", appUserId);
        s.setAttribute("appClientId", tenant); s.setAttribute("clientId", "USR-" + appUserId);
        return s;
    }
    AppUser user(int id, String email, String tenant) {
        AppUser u = new AppUser(); u.setId(id); u.setEmail(email); u.setClientId(tenant);
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("duplicate e-mail: the household with the most active members wins; the link is cached on the session")
    void largestHouseholdWins() {
        user(1, "Anson@X.org", A);
        Family real = family(71, A), dup1 = family(146, A), dup2 = family(147, A), dup3 = family(194, A);
        FamilyMember m71 = member(71, real, "Head", "anson@x.org");
        FamilyMember m222 = member(222, dup1, "Head", "anson@x.org");
        FamilyMember m223 = member(223, dup2, "Head", "anson@x.org");
        FamilyMember m330 = member(330, dup3, "Head", "anson@x.org");
        // unordered, the real household NOT first — mirrors the production query having no ORDER BY
        when(members.findActiveByEmailAndTenant("anson@x.org", A)).thenReturn(List.of(m222, m330, m71, m223));
        when(members.findActiveMembersByFamilyId(71)).thenReturn(List.of(m71, member(147, real, "Spouse", null), member(148, real, "Parent", null), member(149, real, "Child", null), member(150, real, "Child", null)));
        when(members.findActiveMembersByFamilyId(146)).thenReturn(List.of(m222));
        when(members.findActiveMembersByFamilyId(147)).thenReturn(List.of(m223));
        when(members.findActiveMembersByFamilyId(194)).thenReturn(List.of(m330));

        MockHttpSession s = staff(1, A);
        MemberSessionResolver.Resolved r = resolver.resolve(s).orElseThrow();
        assertThat(r.memberId()).isEqualTo(71);
        assertThat(r.appClientId()).isEqualTo(A);
        assertThat(s.getAttribute("memberId")).isEqualTo(71);
        verify(members).findActiveByEmailAndTenant("anson@x.org", A);   // lower-cased, tenant-scoped
    }

    @Test
    @DisplayName("tie-breakers: equal households → Head role, then lowest id")
    void tieBreakers() {
        user(2, "t@x.org", A);
        Family f1 = family(1, A), f2 = family(2, A), f3 = family(3, A);
        FamilyMember spouseLow = member(5, f1, "Spouse", "t@x.org");
        FamilyMember headHigh = member(9, f2, "Head of Household", "t@x.org");
        FamilyMember headHigher = member(12, f3, "Head", "t@x.org");
        when(members.findActiveByEmailAndTenant("t@x.org", A)).thenReturn(List.of(spouseLow, headHigher, headHigh));
        when(members.findActiveMembersByFamilyId(anyInt())).thenReturn(List.of(spouseLow, headHigh));   // same size for all
        assertThat(resolver.resolve(staff(2, A)).orElseThrow().memberId()).isEqualTo(9);
    }

    @Test
    @DisplayName("a staff user with no matching member resolves to empty (caller decides: empty 200 or 401)")
    void unlinkedStaff() {
        user(3, "nobody@x.org", A);
        when(members.findActiveByEmailAndTenant("nobody@x.org", A)).thenReturn(List.of());
        assertThat(resolver.resolve(staff(3, A))).isEmpty();
    }

    @Test
    @DisplayName("tenant isolation: the lookup uses the staff user's own church, and a match from another church is discarded")
    void tenantIsolation() {
        user(4, "shared@x.org", B);
        when(members.findActiveByEmailAndTenant("shared@x.org", B)).thenReturn(List.of());
        MockHttpSession s = staff(4, B);
        assertThat(resolver.resolve(s)).isEmpty();
        verify(members, never()).findActiveByEmailAndTenant("shared@x.org", A);
        assertThat(s.getAttribute("appClientId")).isEqualTo(B);

        // and a cached memberId is re-read inside the session's church only
        MockHttpSession cached = new MockHttpSession();
        cached.setAttribute("role", "Member"); cached.setAttribute("memberId", 99); cached.setAttribute("appClientId", A);
        when(members.findByIdAndTenant(99, A)).thenReturn(Optional.empty());
        assertThat(resolver.resolve(cached)).isEmpty();
        verify(members, never()).findById(99);
    }

    @Test
    @DisplayName("member login and MBR token still resolve as before")
    void memberPaths() {
        Family f = family(7, A);
        FamilyMember m = member(40, f, "Child", null); m.setMemberRef("MBR-abc");
        MockHttpSession login = new MockHttpSession();
        login.setAttribute("role", "Member"); login.setAttribute("memberId", 40); login.setAttribute("appClientId", A);
        when(members.findByIdAndTenant(40, A)).thenReturn(Optional.of(m));
        assertThat(resolver.resolve(login).orElseThrow().memberId()).isEqualTo(40);

        MockHttpSession token = new MockHttpSession();
        token.setAttribute("clientId", "MBR-abc");
        when(members.findByMemberRef("MBR-abc")).thenReturn(Optional.of(m));
        MemberSessionResolver.Resolved r = resolver.resolve(token).orElseThrow();
        assertThat(r.memberId()).isEqualTo(40);
        assertThat(token.getAttribute("appClientId")).isEqualTo(A);
        assertThat(token.getAttribute("memberRef")).isEqualTo("MBR-abc");
    }

    @Test
    @DisplayName("memberHome renders every member the API returns, and asks for the give config only after the family load")
    void pageRendersAllMembers() throws Exception {
        String page = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/memberHome.html"));
        assertThat(page).contains("familyLoaded.then(function () { initGivePayment(); })");
        assertThat(page).contains("res.status === 401 || res.status === 403");
        // the family table is built from the full members array (no head-only filter)
        assertThat(page).contains("renderFamilyTable(members)");
    }
    @Test
    @DisplayName("an un-fetched (uninitialised) family proxy is never touched: the tenant comes from a scalar query")
    void lazyFamilyProxyIsNotInitialised() {
        // A Family subclass that behaves like an uninitialised Hibernate proxy: any
        // state access throws exactly what Hibernate throws outside a session.
        class LazyFamily extends Family implements org.hibernate.proxy.HibernateProxy {
            @Override public String getAppClientId() { throw new org.hibernate.LazyInitializationException("no session"); }
            @Override public org.hibernate.proxy.LazyInitializer getHibernateLazyInitializer() {
                org.hibernate.proxy.LazyInitializer li = mock(org.hibernate.proxy.LazyInitializer.class);
                when(li.isUninitialized()).thenReturn(true);
                return li;
            }
            @Override public Object writeReplace() { return this; }
        }
        FamilyMember m = new FamilyMember(); m.setId(258); m.setFamily(new LazyFamily()); m.setAppClientId(null);
        when(members.findTenantOfMember(258)).thenReturn(A);
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member"); s.setAttribute("memberId", 258); s.setAttribute("appClientId", A);
        when(members.findByIdAndTenant(258, A)).thenReturn(Optional.of(m));

        Optional<MemberSessionResolver.Resolved> r = resolver.resolve(s);

        assertThat(r).isPresent();
        assertThat(r.get().appClientId()).isEqualTo(A);
        verify(members).findTenantOfMember(258);
    }

}
