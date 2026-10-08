package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.repository.ServiceClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Account Health report — expired / inactive / unused accounts and bank syncs")
class AccountHealthServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    @Mock ServiceClientRepository clients;
    @Mock PlaidItemRepository items;
    @Mock PlaidGuard guard;
    @Mock JdbcTemplate jdbc;
    AccountHealthService svc;

    static ServiceClient sc(int id, String cid, String status, LocalDate end) {
        ServiceClient c = new ServiceClient();
        c.setId(id); c.setClientId(cid); c.setChurchName("Church " + id);
        c.setStatus(status); c.setEndDate(end); c.setDeleteFlag(false); c.setSubscriptionType("PRO");
        return c;
    }
    static PlaidItem item(int id, String cid, String status, LocalDate lastSync) {
        PlaidItem i = new PlaidItem();
        i.setId(id); i.setClientId(cid); i.setStatus(status); i.setInstitutionName("Bank " + id);
        if (lastSync != null) i.setLastSyncedDate(java.sql.Date.valueOf(lastSync));
        return i;
    }

    @BeforeEach
    void setUp() {
        svc = new AccountHealthService(clients, items, guard, jdbc);
        when(clients.findAllByDeleteFlagFalseOrderByIdDesc()).thenReturn(List.of(
                sc(1, "CHR-used",     "Active",   TODAY.plusDays(100)),
                sc(2, "CHR-idle",     "Active",   TODAY.plusDays(100)),
                sc(3, "TRIAL-9",      "Active",   TODAY.minusDays(4)),
                sc(4, "CHR-hold",     "Hold",     TODAY.plusDays(100)),
                sc(5, "CHR-endtoday", "Active",   TODAY)));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("church", "CHR-used", "last_at", Timestamp.valueOf(TODAY.minusDays(2).atStartOfDay())),
                Map.of("church", "CHR-idle", "last_at", Timestamp.valueOf(TODAY.minusDays(45).atStartOfDay()))));
        when(guard.isSyncEnabled(anyString())).thenReturn(true);
        when(items.findByDeleteFlagFalse()).thenReturn(List.of(
                item(10, "CHR-used", "ACTIVE", TODAY.minusDays(1)),          // healthy → not listed
                item(11, "TRIAL-9",  "ACTIVE", TODAY.minusDays(1)),          // church expired → PAUSED
                item(12, "CHR-used", "LOGIN_REQUIRED", TODAY.minusDays(3)),  // needs reauth
                item(13, "CHR-idle", "ACTIVE", TODAY.minusDays(60))));       // stale
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> accountsById(Map<String, Object> r) {
        Map<String, Map<String, Object>> m = new java.util.HashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) r.get("accounts")) m.put((String) a.get("clientId"), a);
        return m;
    }

    @Test
    @DisplayName("accounts: expired, inactive and unused are listed; an active, used account is not")
    void accounts() {
        Map<String, Map<String, Object>> a = accountsById(svc.report(TODAY));
        assertThat(a).doesNotContainKey("CHR-used");
        assertThat(a.get("CHR-idle").get("state")).isEqualTo("UNUSED");
        assertThat(a.get("TRIAL-9").get("state")).isEqualTo("EXPIRED");
        assertThat(a.get("TRIAL-9").get("daysSinceExpiry")).isEqualTo(4L);
        assertThat(a.get("TRIAL-9").get("kind")).isEqualTo("TRIAL");
        assertThat(a.get("CHR-hold").get("state")).isEqualTo("INACTIVE");
        assertThat(a.get("CHR-endtoday").get("state")).isEqualTo("EXPIRED");   // same strict rule as sign-in
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("bank syncs: paused, needs-attention and stale connections are listed; a healthy one is not")
    void banks() {
        List<Map<String, Object>> b = (List<Map<String, Object>>) svc.report(TODAY).get("bankSyncs");
        Map<Integer, List<String>> byId = new java.util.HashMap<>();
        for (Map<String, Object> x : b) byId.put((Integer) x.get("itemDbId"), (List<String>) x.get("reasons"));
        assertThat(byId).doesNotContainKey(10);
        assertThat(byId.get(11)).containsExactly("PAUSED");
        assertThat(byId.get(12)).containsExactly("NEEDS_ATTENTION");
        assertThat(byId.get(13)).containsExactly("STALE");
    }

    @Test
    @DisplayName("the report never writes")
    void readOnly() {
        svc.report(TODAY);
        verify(items, never()).save(org.mockito.ArgumentMatchers.any());
        verify(clients, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
