package io.github.fturleque.minos.fixture;

import java.util.Objects;

public final class UserResource {

    private final UserService service;

    public UserResource(UserService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public String getUserName(String id) {
        return service.findUser(id)
                .map(User::name)
                .orElse("unknown");
    }
}
