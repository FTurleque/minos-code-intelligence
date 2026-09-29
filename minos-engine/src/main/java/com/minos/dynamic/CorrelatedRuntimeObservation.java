package com.minos.dynamic;

import java.util.Objects;

/** Runtime observation plus explicit static-correlation results. */
public record CorrelatedRuntimeObservation(
        RuntimeObservation observation,
        RuntimeSymbolResolution source,
        RuntimeSymbolResolution target
) {
    public CorrelatedRuntimeObservation {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(source, "source");
        if (!observation.source().equals(source.reference())) {
            throw new IllegalArgumentException("source resolution must describe the observation source");
        }
        if ((observation.target() == null) != (target == null)) {
            throw new IllegalArgumentException("target resolution must match observation target presence");
        }
        if (target != null && !observation.target().equals(target.reference())) {
            throw new IllegalArgumentException("target resolution must describe the observation target");
        }
    }
}
