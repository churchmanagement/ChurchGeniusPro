package com.churchgeniuspro.subscription;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.plaid.config.PlaidProperties;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import com.churchgeniuspro.plaid.service.PlaidScheduledService;
import com.churchgeniuspro.plaid.service.PlaidSyncService;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MemberMessageRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.PushNotificationScheduler;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.WebPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Push notifications and the scheduled Bank Sync run only for churches whose account
 * is active ({@code status = 'Active'} and {@code end_date > today}) — the same rule
 * as sign-in — and a skipped Bank Sync leaves the connection untouched so it resumes
 * on renewal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Active-account gate — push notifications and Bank Sync")
class ActiveAccountGateTest {

    static final String ACTIVE  = "CHR-active";
    static final String EXPIRED = "CHR-expired";

    @Mock SubscriptionService subscriptions;
    final Set<String> activeIds = new HashSet<>(Set.of(ACTIVE));

    @BeforeEach
    void gate() {
        when(subscriptions.activeAccountClientIds()).thenAnswer(i -> new HashSet<>(activeIds));
    }

    /* ── the rule itself ───────────────────────────────────────────────── */

    @Nested
    @DisplayName("the rule: Active status AND end_date > today")
    class Rule {
        final LocalDate today = LocalDate.of(2026, 9, 28);

        ServiceClient sc(String status, LocalDate end, boolean deleted) {
            ServiceClient c = new ServiceClient();
            c.setStatus(status); c.setEndDate(end); c.setDeleteFlag(deleted);
            return c;
        }

        @Test void activeFutureEnd()   { assertThat(SubscriptionService.isAccountActive(sc("Active", today.plusDays(1), false), today)).isTrue(); }
        @Test void endsToday()         { assertThat(SubscriptionService.isAccountActive(sc("Active", today, false), today)).isFalse(); }
        @Test void pastEnd()           { assertThat(SubscriptionService.isAccountActive(sc("Active", today.minusDays(5), false), today)).isFalse(); }
        @Test void hold()              { assertThat(SubscriptionService.isAccountActive(sc("Hold", today.plusDays(30), false), today)).isFalse(); }
        @Test void inactive()          { assertThat(SubscriptionService.isAccountActive(sc("Inactive", today.plusDays(30), false), today)).isFalse(); }
        @Test void deleted()           { assertThat(SubscriptionService.isAccountActive(sc("Active", today.plusDays(30), true), today)).isFalse(); }
        @Test void noEndDate()         { assertThat(SubscriptionService.isAccountActive(sc("Active", null, false), today)).isFalse(); }
        @Test void nullRow()           { assertThat(SubscriptionService.isAccountActive(null, today)).isFalse(); }

        @Test
        @DisplayName("bulk lookup drops null ids")
        void bulkDropsNulls() {
            ServiceClientRepository repo = mock(ServiceClientRepository.class);
            when(repo.findActiveAccountClientIds()).thenReturn(Arrays.asList("A", null, "B"));
            SubscriptionService real = new SubscriptionService(repo, null, null);
            assertThat(real.activeAccountClientIds()).containsExactlyInAnyOrder("A", "B");
        }
    }

    /* ── push notifications ────────────────────────────────────────────── */

    @Nested
    @DisplayName("push notifications")
    class Push {
        @Mock WebPushService pushService;
        @Mock PushSubscriptionRepository subRepo;
        @Mock MemberMessageRepository messages;
        @Mock FamilyMemberRepository members;
        @Mock ChurchEventRepository events;
        @Mock MeetingRepository meetings;
        PushNotificationScheduler scheduler;

        PushSubscription sub(String userKey, String clientId) {
            PushSubscription s = new PushSubscription();
            s.setUserKey(userKey); s.setAppClientId(clientId); s.setUserType("member"); s.setActive(true);
            return s;
        }
        FamilyMember member(int id, String clientId) {
            FamilyMember fm = new FamilyMember();
            fm.setId(id); fm.setAppClientId(clientId); fm.setFirstName("M" + id);
            LocalDate t = LocalDate.now();
            fm.setBirthdayMonth(t.getMonthValue()); fm.setBirthdayDay(t.getDayOfMonth());
            return fm;
        }

        @BeforeEach
        void setUp() {
            scheduler = new PushNotificationScheduler(pushService, subRepo, messages, members, events, meetings, subscriptions);
            when(pushService.isEnabled()).thenReturn(true);
            List<PushSubscription> subs = List.of(sub("m-active", ACTIVE), sub("m-expired", EXPIRED));
            when(subRepo.findByUserTypeAndActiveTrue("member")).thenReturn(subs);
            when(subRepo.findAll()).thenReturn(subs);
            FamilyMember a = member(1, ACTIVE), e = member(2, EXPIRED);
            when(members.findByMemberRef("m-active")).thenReturn(Optional.of(a));
            when(members.findByMemberRef("m-expired")).thenReturn(Optional.of(e));
            when(messages.countUnread(anyInt())).thenReturn(3L);
            when(members.findAllWithFamilyByAppUser(ACTIVE)).thenReturn(new ArrayList<>(List.of(a)));
            when(members.findAllWithFamilyByAppUser(EXPIRED)).thenReturn(new ArrayList<>(List.of(e)));
        }

        @Test
        @DisplayName("unread-message push: active church notified, expired church skipped")
        void unread() {
            scheduler.notifyUnreadMessages();
            verify(pushService).logAndSendToUser(eq("m-active"), eq(ACTIVE), anyString(), anyString(), anyString(), anyString(), anyString());
            verify(pushService, never()).logAndSendToUser(eq("m-expired"), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("daily digest: active church notified, expired church skipped")
        void digest() {
            scheduler.notifyDailyDigest();
            verify(pushService, org.mockito.Mockito.atLeastOnce()).logAndSendToOrg(eq(ACTIVE), anyString(), anyString(), anyString(), anyString());
            verify(pushService, never()).logAndSendToOrg(eq(EXPIRED), any(), any(), any(), any());
        }

        @Test
        @DisplayName("after renewal the church is notified again")
        void renewal() {
            activeIds.add(EXPIRED);
            scheduler.notifyUnreadMessages();
            verify(pushService).logAndSendToUser(eq("m-expired"), eq(EXPIRED), anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a subscription without a church id is resolved from the member, not sent blindly")
        void nullClientIdResolvedFromMember() {
            when(subRepo.findByUserTypeAndActiveTrue("member")).thenReturn(List.of(sub("m-expired", null)));
            scheduler.notifyUnreadMessages();
            verify(pushService, never()).logAndSendToUser(any(), any(), any(), any(), any(), any(), any());
        }
    }

    /* ── Bank Sync ─────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("scheduled Bank Sync")
    class BankSync {
        @Mock PlaidProperties props;
        @Mock PlaidItemRepository items;
        @Mock PlaidTransactionStagingRepository staging;
        @Mock PlaidSyncService sync;
        @Mock PlaidGuard guard;
        PlaidScheduledService service;
        PlaidItem activeItem, expiredItem;

        PlaidItem item(int id, String clientId) {
            PlaidItem i = new PlaidItem();
            i.setId(id); i.setClientId(clientId); i.setStatus("ACTIVE");
            i.setAccessTokenEnc("enc-" + id); i.setSyncCursor("cursor-" + id);
            return i;
        }

        @BeforeEach
        void setUp() {
            service = new PlaidScheduledService(props, items, staging, sync, guard, subscriptions);
            when(props.isConfigured()).thenReturn(true);
            when(guard.isSyncEnabled(anyString())).thenReturn(true);
            when(subscriptions.isFeatureEnabled(anyString(), eq("bankSync"))).thenReturn(true);
            activeItem = item(1, ACTIVE);
            expiredItem = item(2, EXPIRED);
            when(items.findByDeleteFlagFalse()).thenReturn(List.of(activeItem, expiredItem));
        }

        @Test
        @DisplayName("active church syncs; expired church is skipped")
        void gated() {
            service.safetyNetSync();
            verify(sync).sync(activeItem, "SCHEDULED");
            verify(sync, never()).sync(eq(expiredItem), anyString());
        }

        @Test
        @DisplayName("skipping never disconnects, deletes or alters the connection")
        void connectionUntouched() {
            service.safetyNetSync();
            verify(items, never()).save(any());
            verify(items, never()).delete(any());
            verify(staging, never()).deleteAll();
            assertThat(expiredItem.getStatus()).isEqualTo("ACTIVE");
            assertThat(expiredItem.getAccessTokenEnc()).isEqualTo("enc-2");
            assertThat(expiredItem.getSyncCursor()).isEqualTo("cursor-2");
            assertThat(expiredItem.isDeleteFlag()).isFalse();
        }

        @Test
        @DisplayName("after renewal the same connection resumes from its stored cursor")
        void resumesAfterRenewal() {
            service.safetyNetSync();
            verify(sync, never()).sync(eq(expiredItem), anyString());
            activeIds.add(EXPIRED);                       // church renewed
            service.safetyNetSync();
            verify(sync).sync(expiredItem, "SCHEDULED");
            assertThat(expiredItem.getSyncCursor()).isEqualTo("cursor-2");
        }

        @Test
        @DisplayName("a plan without Bank Sync (e.g. Standard) is skipped the same way — connection untouched")
        void planWithoutBankSync() {
            when(subscriptions.isFeatureEnabled(ACTIVE, "bankSync")).thenReturn(false);
            service.safetyNetSync();
            verify(sync, never()).sync(eq(activeItem), anyString());
            verify(items, never()).save(any());
            assertThat(activeItem.getSyncCursor()).isEqualTo("cursor-1");
        }

        @Test
        @DisplayName("if active accounts cannot be read, the run is skipped rather than syncing everyone")
        void failsClosed() {
            when(subscriptions.activeAccountClientIds()).thenThrow(new RuntimeException("db down"));
            service.safetyNetSync();
            verify(sync, never()).sync(any(), anyString());
        }
    }
}
