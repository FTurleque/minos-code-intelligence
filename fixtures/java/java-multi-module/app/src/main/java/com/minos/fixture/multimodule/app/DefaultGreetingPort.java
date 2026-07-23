package com.minos.fixture.multimodule.app;

import com.minos.fixture.multimodule.api.GreetingPort;
import com.minos.fixture.multimodule.api.UserProfile;

public final class DefaultGreetingPort implements GreetingPort {

    @Override
    public String greet(UserProfile user) {
        return "Hello, " + user.name();
    }
}
