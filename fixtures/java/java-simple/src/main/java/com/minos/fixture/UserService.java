package com.minos.fixture;

import java.util.Objects;
import java.util.Optional;

public final class UserService {

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<User> findUser(String id) {
        return repository.findById(id);
    }
}
