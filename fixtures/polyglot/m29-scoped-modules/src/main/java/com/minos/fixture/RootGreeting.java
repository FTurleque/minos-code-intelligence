package com.minos.fixture;

public final class RootGreeting {
    private RootGreeting() {
    }

    public static String greet(String name) {
        return "hello " + name;
    }
}
