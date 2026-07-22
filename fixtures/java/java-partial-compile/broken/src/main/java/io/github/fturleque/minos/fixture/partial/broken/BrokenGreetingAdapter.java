package io.github.fturleque.minos.fixture.partial.broken;

import io.github.fturleque.minos.fixture.partial.stable.StableGreeting;
import third.party.missing.MissingClient;

public final class BrokenGreetingAdapter {

    private final StableGreeting stableGreeting;
    private final MissingClient missingClient;

    public BrokenGreetingAdapter(StableGreeting stableGreeting, MissingClient missingClient) {
        this.stableGreeting = stableGreeting;
        this.missingClient = missingClient;
    }

    public String greet(String name) {
        return missingClient.decorate(stableGreeting.greet(name));
    }
}
