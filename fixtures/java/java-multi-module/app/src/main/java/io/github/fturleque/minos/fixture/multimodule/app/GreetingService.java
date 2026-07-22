package io.github.fturleque.minos.fixture.multimodule.app;

import io.github.fturleque.minos.fixture.multimodule.api.GreetingPort;
import io.github.fturleque.minos.fixture.multimodule.api.UserProfile;

import java.util.Objects;

public final class GreetingService {

    private final GreetingPort greetingPort;

    public GreetingService(GreetingPort greetingPort) {
        this.greetingPort = Objects.requireNonNull(greetingPort, "greetingPort");
    }

    public String greet(String name) {
        return greetingPort.greet(new UserProfile(name));
    }
}
