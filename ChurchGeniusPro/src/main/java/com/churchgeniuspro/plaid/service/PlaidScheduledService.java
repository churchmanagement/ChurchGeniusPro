package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.config.PlaidProperties;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Scheduled maintenance for the Plaid feature:
 * <ul>
 *   <li>Safety-net sync — re-syncs active items periodically so transactions are
 *       not missed if a webhook was dropped. Respects each church's sync flag.</li>
 *   <li>Retention purge — removes old rejected/bank-removed staging rows per the
 *       configured retention window (approved rows are kept; they link to the
 *       ledger and are covered by the ledger's own retention).</li>
 * </ul>
 * The app already enables scheduling ({@code @EnableScheduling}).
 */
@Service
public class PlaidScheduledService {

    private static final Logger log = LoggerFactory.getLogger(PlaidScheduledService.class);

    private final PlaidProperties props;
    private final PlaidItemRepository itemRepo;
    private final PlaidTransactionStagingRepository stagingRepo;
    private final PlaidSyncService syncService;
    private final PlaidGuard guard;

    public PlaidScheduledService(PlaidProperties props,
                                 PlaidItemRepository itemRepo,
                                 PlaidTransactionStagingRepository stagingRepo,
                                 PlaidSyncService syncService,
                                 PlaidGuard guard) {
        this.props = props;
        this.itemRepo = itemRepo;
        this.stagingRepo = stagingRepo;
        this.syncService = syncService;
        this.guard = guard;
    }

    /** Hourly (at :15) safety-net sync for active items in sync-enabled churches. */
    @Scheduled(cron = "${plaid.safety-net-sync-cron:0 15 * * * *}")
    public void safetyNetSync() {
        if (!props.isConfigured()) return;
        int synced = 0;
        for (PlaidItem item : itemRepo.findByDeleteFlagFalse()) {
            if ("DISCONNECTED".equals(item.getStatus())) continue;
            if (!guard.isSyncEnabled(item.getClientId())) continue;
            try {
                syncService.sync(item, "SCHEDULED");
                synced++;
            } catch (Exception e) {
                log.warn("Scheduled sync failed for item {}: {}", item.getItemId(), e.getMessage());
            }
        }
        if (synced > 0) log.info("Plaid safety-net sync ran for {} item(s)", synced);
    }

    /** Daily (03:30) purge of old rejected/removed staging rows. */
    @Scheduled(cron = "${plaid.retention-purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeOldStaging() {
        int days = props.getStagingRetentionDays() > 0 ? props.getStagingRetentionDays() : 90;
        Date cutoff = Date.from(Instant.now().minus(days, ChronoUnit.DAYS));
        long rejected = stagingRepo.deleteByStatusAndReviewedDateBefore("REJECTED", cutoff);
        long removed = stagingRepo.deleteByRemovedTrueAndUpdatedDateBefore(cutoff);
        if (rejected + removed > 0) {
            log.info("Plaid staging purge removed {} rejected and {} removed rows older than {} days",
                    rejected, removed, days);
        }
    }
}
