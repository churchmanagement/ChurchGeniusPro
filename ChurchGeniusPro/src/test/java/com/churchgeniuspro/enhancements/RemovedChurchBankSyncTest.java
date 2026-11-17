package com.churchgeniuspro.enhancements;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import com.churchgeniuspro.plaid.service.PlaidAuditService;
import com.churchgeniuspro.plaid.service.PlaidClient;
import com.churchgeniuspro.plaid.service.PlaidEnvironmentService;
import com.churchgeniuspro.plaid.service.PlaidLinkService;
import com.churchgeniuspro.plaid.service.PlaidSyncService;
import com.churchgeniuspro.plaid.service.PlaidTokenCipher;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.AccountHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-16 (item 12) — "Bank Sync Connections Needing Attention" kept listing TRIAL-1788992984878
 * (LOGIN_REQUIRED, "church removed"): the church was gone but its bank connection was never closed.
 */
@DisplayName("Bank Sync — connections of removed churches")
class RemovedChurchBankSyncTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    ServiceClientRepository clients = mock(ServiceClientRepository.class);
    PlaidItemRepository items = mock(PlaidItemRepository.class);
    PlaidGuard guard = mock(PlaidGuard.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    static PlaidItem item(int id, String cid, String status) {
        PlaidItem i = new PlaidItem();
        i.setId(id); i.setClientId(cid); i.setStatus(status); i.setInstitutionName("Bank " + id);
        i.setItemId("item-" + id); i.setAccessTokenEnc("enc-" + id);
        i.setLastSyncedDate(java.sql.Date.valueOf(TODAY.minusDays(1)));
        return i;
    }

    @BeforeEach
    void setUp() {
        ServiceClient live = new ServiceClient();
        live.setId(1); live.setClientId("CHR-live"); live.setChurchName("Live Church"); live.setStatus("Active");
        live.setEndDate(TODAY.plusDays(90)); live.setDeleteFlag(false);
        when(clients.findAllByDeleteFlagFalseOrderByIdDesc()).thenReturn(List.of(live));
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(guard.isSyncEnabled(anyString())).thenReturn(true);
    }

    @Test @SuppressWarnings("unchecked")
    @DisplayName("a removed church's connection is not in the attention list; a live church's problem still is")
    void notListedAsActiveIssue() {
        when(items.findByDeleteFlagFalse()).thenReturn(List.of(
                item(1, "TRIAL-1788992984878", "LOGIN_REQUIRED"),
                item(2, "CHR-live", "LOGIN_REQUIRED"),
                item(3, "CHR-gone", "ACTIVE")));
        AccountHealthService svc = new AccountHealthService(clients, items, guard, jdbc);

        Map<String, Object> r = svc.report();
        List<Map<String, Object>> banks = (List<Map<String, Object>>) r.get("bankSyncs");
        assertThat(banks).extracting(b -> b.get("clientId")).containsExactly("CHR-live");
        List<Map<String, Object>> gone = (List<Map<String, Object>>) r.get("removedChurchBankSyncs");
        assertThat(gone).extracting(b -> b.get("clientId")).containsExactly("TRIAL-1788992984878", "CHR-gone");
        Map<String, Object> counts = (Map<String, Object>) r.get("counts");
        assertThat(counts).containsEntry("bankIssues", 1).containsEntry("removedChurchBankSyncs", 2);
        assertThat(svc.removedChurchesWithBankSync()).containsExactly("TRIAL-1788992984878", "CHR-gone");
        verify(items, never()).save(any());                      // the report never writes
    }

    @Test @DisplayName("closing them: revoked at Plaid, staged rows removed, kept as a DISCONNECTED stub with an audit row; a live church is never touched")
    void disconnect() throws Exception {
        PlaidClient client = mock(PlaidClient.class);
        PlaidEnvironmentService env = mock(PlaidEnvironmentService.class);
        PlaidTokenCipher cipher = mock(PlaidTokenCipher.class);
        PlaidAccountRepository accounts = mock(PlaidAccountRepository.class);
        PlaidTransactionStagingRepository staging = mock(PlaidTransactionStagingRepository.class);
        PlaidAuditService audit = mock(PlaidAuditService.class);
        PlaidLinkService link = new PlaidLinkService(client, env, cipher, items, accounts, staging, mock(PlaidSyncService.class), audit);

        PlaidItem a = item(1, "TRIAL-1788992984878", "LOGIN_REQUIRED"), b = item(2, "TRIAL-1788992984878", "ACTIVE");
        when(items.findByClientIdAndDeleteFlagFalse("TRIAL-1788992984878")).thenReturn(List.of(a, b));
        when(cipher.decrypt(anyString())).thenReturn("access-token");
        when(staging.deleteByPlaidItemId(anyInt())).thenReturn(3L);
        when(accounts.deleteByPlaidItemId(anyInt())).thenReturn(1L);
        doThrow(new RuntimeException("ITEM_NOT_FOUND")).when(client).itemRemove(any(), eq("access-token"));   // Plaid already forgot it

        int closed = link.disconnectForRemovedChurch("TRIAL-1788992984878", "serviceadmin");

        assertThat(closed).isEqualTo(2);
        for (PlaidItem i : List.of(a, b)) {
            assertThat(i.getStatus()).isEqualTo("DISCONNECTED");
            assertThat(i.isDeleteFlag()).isTrue();
            assertThat(i.getAccessTokenEnc()).isEmpty();
            assertThat(i.getSyncCursor()).isNull();
        }
        verify(client, times(2)).itemRemove(any(), eq("access-token"));
        verify(audit, times(2)).record(eq("TRIAL-1788992984878"), eq("serviceadmin"), eq("ITEM_DELETED"), anyString(),
                contains("church was removed"));
        verify(items, never()).findByClientIdAndDeleteFlagFalse("CHR-live");
        assertThat(link.disconnectForRemovedChurch(null, "x")).isZero();
        assertThat(link.disconnectForRemovedChurch(" ", "x")).isZero();
    }

    @Test @DisplayName("wiring: deleting a client closes its connections; the clean-up endpoint works out the list itself")
    void wiring() throws Exception {
        String ctl = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/churchgeniuspro/controller/ServiceAdminController.java"));
        assertThat(ctl).contains("plaidLinks.disconnectForRemovedChurch(cid");
        String h = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/churchgeniuspro/controller/ServiceAdminAccountHealthController.java"));
        assertThat(h).contains("health.removedChurchesWithBankSync()").contains("\"ServiceAdmin\".equals(s.getAttribute(\"role\"))");
        assertThat(h).doesNotContain("@RequestBody");            // no ids accepted from the page
        String page = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/serviceadminhome.html"));
        assertThat(page).contains("removedChurchBankSyncs").contains("/api/serviceadmin/account-health/removed-church-bank-syncs/disconnect");
    }
}
