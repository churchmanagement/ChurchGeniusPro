package com.churchgeniuspro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans for children who are overdue for pickup and fires the
 * configured email/SMS alerts. Scheduling is already enabled app-wide
 * ({@code @EnableScheduling} on the application class). The scan interval is
 * configurable via {@code km.pickup.scan-ms} (default 2 minutes).
 */
@Component
public class KmPickupAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(KmPickupAlertScheduler.class);

    private final KmPickupAlertService alertService;

    public KmPickupAlertScheduler(KmPickupAlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "${km.pickup.scan-ms:120000}", initialDelay = 60000)
    public void scan() {
        try {
            alertService.sendOverdueAlerts();
        } catch (Exception e) {
            log.warn("KM pickup alert scan error: {}", e.getMessage());
        }
    }
}
