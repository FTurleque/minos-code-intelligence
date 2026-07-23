package com.minos.fixture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceTest {

    @Test
    void findsExistingUser() {
        UserRepository repository = new InMemoryUserRepository();
        UserService service = new UserService(repository);

        var user = service.findUser("42");

        assertTrue(user.isPresent());
        assertEquals("Ada", user.orElseThrow().name());
    }
}
