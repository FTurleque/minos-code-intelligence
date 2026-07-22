package io.github.fturleque.minos.fixture.multimodule.app;

import io.github.fturleque.minos.fixture.multimodule.api.GreetingPort;
import io.github.fturleque.minos.fixture.multimodule.api.UserProfile;

public final class DefaultGreetingPort implements GreetingPort {

    @Override
    public String greet(UserProfile user) {
        return "Hello, " + user.name();
    }
}
