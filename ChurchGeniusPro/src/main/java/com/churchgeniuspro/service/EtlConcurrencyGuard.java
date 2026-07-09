package com.churchgeniuspro.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * ETL Phase 7 — concurrency guard. A run moves through destructive, order-sensitive
 * stages (stage → validate → approve → load → rollback); two operators (or a
 * double-clicked button) acting on the same run at once could double-load or
 * corrupt the lifecycle. This guard serializes mutating operations <b>per run</b>
 * with a lightweight in-memory lock — concurrent attempts on the same run fail fast
 * with {@link BusyException}; different runs proceed in parallel.
 *
 * <p>In-memory is sufficient because the Service-Admin ETL console is operated from
 * a single application instance; if this ever runs multi-node, swap the set for a
 * DB row-lock or distributed lock without changing callers.
 */
@Component
public class EtlConcurrencyGuard {

    private final Set<Long> busyRuns = ConcurrentHashMap.newKeySet();

    public static class BusyException extends RuntimeException {
        public BusyException(Long runId) {
            super("Run " + runId + " is busy with another import operation. Please wait and retry.");
        }
    }

    /** Run {@code action} while holding the run's lock; throws {@link BusyException} if already held. */
    public <T> T guard(Long runId, Supplier<T> action) {
        if (runId == null) return action.get();
        if (!busyRuns.add(runId)) throw new BusyException(runId);
        try {
            return action.get();
        } finally {
            busyRuns.remove(runId);
        }
    }

    public boolean isBusy(Long runId) {
        return runId != null && busyRuns.contains(runId);
    }
}
