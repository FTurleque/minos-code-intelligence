package com.minos.fixture.multimodule.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingServiceTest {

    @Test
    void greetsAcrossModules() {
        GreetingService service = new GreetingService(new DefaultGreetingPort());

        assertEquals("Hello, Ada", service.greet("Ada"));
    }
}
