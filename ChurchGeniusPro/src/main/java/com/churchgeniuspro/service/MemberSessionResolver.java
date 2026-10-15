package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves "which family member is this session?" for the member portal — the one
 * rule shared by {@code /api/member/family}, {@code /api/member/give/*} and anything
 * else that shows a signed-in person their own household.
 *
 * <p>Three kinds of session can reach the portal:
 * <ol>
 *   <li><b>Member login</b> — {@code role=Member}, {@code memberId} set at login.</li>
 *   <li><b>Member token</b> — {@code clientId} is the member's {@code MBR…} ref.</li>
 *   <li><b>Staff login</b> (SuperAdmin, Admin, …) — no member id; the staff user's
 *       e-mail is matched to an active member of <em>their own church</em>.</li>
 * </ol>
 * Every lookup is tenant-scoped; a match can never move the session to another
 * church. The result is cached on the session ({@code memberId}, {@code memberRef},
 * {@code appClientId}) so later calls resolve with one query.
 *
 * <p>When a staff e-mail matches several member records in the church (a duplicate
 * household, a single-person test family), the choice is deterministic: the family
 * with the most active members, then a Head role, then the lowest member id — and a
 * warning names the family ids so the duplicates can be merged.
 */
public class MemberSessionResolver {

    private static final Logger log = LoggerFactory.getLogger(MemberSessionResolver.class);

    /** The resolved member and the church it belongs to. */
    public record Resolved(FamilyMember member, Integer memberId, String appClientId) {}

    private final FamilyMemberRepository familyMemberRepo;
    private final AppUserRepository appUserRepo;

    public MemberSessionResolver(FamilyMemberRepository familyMemberRepo, AppUserRepository appUserRepo) {
        this.familyMemberRepo = familyMemberRepo;
        this.appUserRepo = appUserRepo;
    }

    /** True when the session is a staff login (has a username and is not a church login). */
    public static boolean isStaffSession(HttpSession session) {
        if (session == null) return false;
        if (session.getAttribute("username") == null) return false;
        Object church = session.getAttribute("church");           // church logins are not staff here
        return !(Boolean.TRUE.equals(church) || "true".equalsIgnoreCase(String.valueOf(church)));
    }

    /** True when the session is a member-portal login. */
    public static boolean isMemberSession(HttpSession session) {
        return session != null && "Member".equals(session.getAttribute("role")) && session.getAttribute("memberId") != null;
    }

    public Optional<Resolved> resolve(HttpSession session) {
        if (session == null) return Optional.empty();
        String sessionTenant = session.getAttribute("appClientId") instanceof String s && !s.isBlank() ? s : null;

        // 1. Already linked — re-read within the session's church.
        Integer memberId = session.getAttribute("memberId") instanceof Number n ? n.intValue() : null;
        FamilyMember self = null;
        // Login always stores appClientId next to memberId (LoginController), so the
        // re-read is tenant-scoped; a memberId without a tenant is not trusted.
        if (memberId != null && sessionTenant != null) {
            self = familyMemberRepo.findByIdAndTenant(memberId, sessionTenant).orElse(null);
        }

        // 2. Member token: the MBR ref is the trusted member ↔ row binding.
        if (self == null) {
            String clientId = session.getAttribute("clientId") instanceof String s ? s : null;
            if (clientId != null && clientId.startsWith("MBR")) {
                self = familyMemberRepo.findByMemberRef(clientId).orElse(null);
            }
        }

        // 3. Staff login: match the staff e-mail to a member of the staff user's own church.
        if (self == null && appUserRepo != null && session.getAttribute("appUserId") instanceof Integer appUserId) {
            AppUser appUser = appUserRepo.findById(appUserId).orElse(null);
            if (appUser != null && appUser.getEmail() != null && !appUser.getEmail().isBlank() && appUser.getClientId() != null) {
                self = pickHousehold(familyMemberRepo.findActiveByEmailAndTenant(
                        appUser.getEmail().trim().toLowerCase(), appUser.getClientId()), appUser.getClientId());
            }
        }

        if (self == null) return Optional.empty();
        String tenant = tenantOf(self);
        if (tenant == null || tenant.isBlank()) tenant = sessionTenant;
        if (tenant == null) return Optional.empty();
        if (sessionTenant != null && !sessionTenant.equals(tenant)) {
            // A lookup must never move a session to another church.
            log.warn("member resolution ignored: session tenant {} but member {} belongs to {}", sessionTenant, self.getId(), tenant);
            return Optional.empty();
        }

        session.setAttribute("memberId", self.getId());
        if (self.getMemberRef() != null) session.setAttribute("memberRef", self.getMemberRef());
        session.setAttribute("appClientId", tenant);
        return Optional.of(new Resolved(self, self.getId(), tenant));
    }

    /**
     * The church a member belongs to: the family's appClientId when there is a family,
     * otherwise the member's own.
     *
     * <p>This runs outside any transaction (the resolver is a plain object created by
     * its controllers) with {@code spring.jpa.open-in-view=false}, so a family that a
     * lookup did not JOIN FETCH is an uninitialised proxy and reading it throws
     * {@code LazyInitializationException} — the blank member portal of 2026-10-15. The
     * repository lookups now fetch the family, and this guard covers any that don't:
     * an uninitialised proxy is never touched; the tenant is read with a scalar query.
     */
    String tenantOf(FamilyMember m) {
        Object family = m.getFamily();
        if (family != null && !org.hibernate.Hibernate.isInitialized(family)) {
            String viaQuery = m.getId() == null ? null : familyMemberRepo.findTenantOfMember(m.getId());
            if (viaQuery != null && !viaQuery.isBlank()) return viaQuery;
            return m.getAppClientId();
        }
        String fromFamily = m.getFamily() != null ? m.getFamily().getAppClientId() : null;
        return fromFamily != null ? fromFamily : m.getAppClientId();
    }

    /**
     * Deterministic choice among several member rows sharing one e-mail in one church:
     * the household with the most active members wins, then a Head role, then the
     * lowest member id. Logs the duplicate family ids (no personal data) when it has to choose.
     */
    FamilyMember pickHousehold(List<FamilyMember> candidates, String tenant) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        record Scored(FamilyMember m, int household, boolean head, int id) {}
        List<Scored> scored = new ArrayList<>();
        for (FamilyMember m : candidates) {
            Integer famId = m.getFamily() != null ? m.getFamily().getId() : null;
            int size = famId == null ? 1 : Math.max(1, familyMemberRepo.findActiveMembersByFamilyId(famId).size());
            boolean head = m.getRole() != null && m.getRole().trim().toLowerCase().startsWith("head");
            scored.add(new Scored(m, size, head, m.getId() == null ? Integer.MAX_VALUE : m.getId()));
        }
        scored.sort(Comparator.comparingInt(Scored::household).reversed()
                .thenComparing(Comparator.comparing(Scored::head).reversed())
                .thenComparingInt(Scored::id));
        List<Integer> familyIds = scored.stream().map(s -> s.m().getFamily() != null ? s.m().getFamily().getId() : null).toList();
        log.warn("staff e-mail matches {} member records in church {} (family ids {}); using family {} — merge the duplicates",
                candidates.size(), tenant, familyIds, familyIds.get(0));
        return scored.get(0).m();
    }
}
