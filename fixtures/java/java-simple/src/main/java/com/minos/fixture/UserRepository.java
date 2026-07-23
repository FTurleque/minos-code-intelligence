package com.minos.fixture;

import java.util.Optional;

public interface UserRepository {

    Optional<User> findById(String id);
}
