package com.minos.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded write budget granted to an untrusted provider across every root it can write to.
 *
 * <p>Neither Linux nor Windows exposes an unprivileged per-job disk quota, so this budget is
 * enforced by {@link ProviderWriteQuotaSupervisor} during execution: the supervisor destroys the
 * whole OS job boundary as soon as the provider crosses either bound. The disposition reported by
 * a backend is therefore {@code SUPERVISED_HARD_KILL}, never {@code OS_ENFORCED}: MINOS states the
 * guarantee it actually provides.</p>
 *
 * @param maxBytes     aggregate bytes the provider may materialize across all writable roots
 * @param maxEntries   aggregate files and directories the provider may materialize (inode proxy)
 * @param samplePeriod minimum delay between two samples; the supervisor also backs off
 *                     proportionally to its own (budget-bounded) walk cost so that supervising a
 *                     large workspace never burns more than a bounded share of a core
 */
public record ProviderWriteQuota(long maxBytes, long maxEntries, Duration samplePeriod) {

    public static final long DEFAULT_MAX_BYTES = 8L * 1024L * 1024L * 1024L;
    public static final long DEFAULT_MAX_ENTRIES = 400_000L;
    public static final Duration DEFAULT_SAMPLE_PERIOD = Duration.ofMillis(250L);

    public static final ProviderWriteQuota DEFAULT =
            new ProviderWriteQuota(DEFAULT_MAX_BYTES, DEFAULT_MAX_ENTRIES, DEFAULT_SAMPLE_PERIOD);

    public ProviderWriteQuota {
        if (maxBytes < 1L) {
            throw new IllegalArgumentException("provider write byte quota must be positive");
        }
        if (maxEntries < 1L) {
            throw new IllegalArgumentException("provider write entry quota must be positive");
        }
        Objects.requireNonNull(samplePeriod, "samplePeriod");
        if (samplePeriod.isZero() || samplePeriod.isNegative()) {
            throw new IllegalArgumentException("provider write quota sample period must be positive");
        }
    }
}
