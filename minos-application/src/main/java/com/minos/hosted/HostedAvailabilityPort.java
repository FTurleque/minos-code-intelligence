package com.minos.hosted;

/** Operator-owned backup, restore and availability boundary for an operated hosted deployment. */
public interface HostedAvailabilityPort {

    Readiness readiness();

    enum Readiness {
        QUALIFIED,
        NOT_QUALIFIED
    }
}
