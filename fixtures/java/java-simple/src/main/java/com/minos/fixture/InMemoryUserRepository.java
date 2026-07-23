package com.minos.fixture;

import java.util.Map;
import java.util.Optional;

public final class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> users = Map.of(
            "42", new User("42", "Ada")
    );

    @Override
    public Optional<User> findById(String id) {
        return Optional.ofNullable(users.get(id));
    }
}
