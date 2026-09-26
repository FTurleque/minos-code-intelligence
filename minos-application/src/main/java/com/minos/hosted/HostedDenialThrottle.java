package com.minos.hosted;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, thread-safe sliding-window budget of chained (persisted) refusals per tenant principal.
 *
 * <p>Only refusals that are actually chained consume the budget. The budget is process-local, so it
 * limits the audit churn and version increments a single principal can cause from one process; the
 * cross-process guarantee comes from {@link HostedRetentionPolicy#deniedAuditCapacity()}. Memory is
 * bounded by {@code maxTrackedPrincipals} keys of at most {@code maxPerWindow} timestamps each; the
 * least recently active principal is evicted first.</p>
 */
final class HostedDenialThrottle {
    static final int DEFAULT_MAX_PER_WINDOW = 10;
    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
    static final int DEFAULT_MAX_TRACKED_PRINCIPALS = 1_024;

    private final int maxPerWindow;
    private final Duration window;
    private final int maxTrackedPrincipals;
    private final LinkedHashMap<String, ArrayDeque<Instant>> budgets = new LinkedHashMap<>(16, 0.75f, true);

    HostedDenialThrottle() {
        this(DEFAULT_MAX_PER_WINDOW, DEFAULT_WINDOW, DEFAULT_MAX_TRACKED_PRINCIPALS);
    }

    HostedDenialThrottle(int maxPerWindow, Duration window, int maxTrackedPrincipals) {
        if (maxPerWindow < 1) throw new IllegalArgumentException("maxPerWindow must be positive");
        if (maxTrackedPrincipals < 1) throw new IllegalArgumentException("maxTrackedPrincipals must be positive");
        Objects.requireNonNull(window, "window");
        if (window.isNegative() || window.isZero()) throw new IllegalArgumentException("window must be positive");
        this.maxPerWindow = maxPerWindow;
        this.window = window;
        this.maxTrackedPrincipals = maxTrackedPrincipals;
    }

    /** Consumes one chained-refusal slot for the principal if its window budget allows it. */
    synchronized boolean tryAcquire(UUID tenantId, String principalId, Instant now) {
        String key = Objects.requireNonNull(tenantId, "tenantId") + "\0" + Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(now, "now");
        ArrayDeque<Instant> stamps = budgets.get(key);
        if (stamps == null) {
            stamps = new ArrayDeque<>(maxPerWindow);
            budgets.put(key, stamps);
            Iterator<String> eldest = budgets.keySet().iterator();
            while (budgets.size() > maxTrackedPrincipals && eldest.hasNext()) {
                eldest.next();
                eldest.remove();
            }
        }
        Instant cutoff = now.minus(window);
        while (!stamps.isEmpty() && !stamps.peekFirst().isAfter(cutoff)) {
            stamps.pollFirst();
        }
        if (stamps.size() >= maxPerWindow) {
            return false;
        }
        stamps.addLast(now);
        return true;
    }

    synchronized int trackedPrincipals() {
        return budgets.size();
    }
}
