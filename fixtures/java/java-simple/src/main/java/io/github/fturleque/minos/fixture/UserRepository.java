package io.github.fturleque.minos.fixture;

import java.util.Optional;

public interface UserRepository {

    Optional<User> findById(String id);
}
