package io.github.fturleque.minos.fixture24;

public final class GreetingApp {

    private final GreetingService service;

    public GreetingApp(GreetingService service) {
        this.service = service;
    }

    public String run(String name) {
        return service.greet(name);
    }
}
