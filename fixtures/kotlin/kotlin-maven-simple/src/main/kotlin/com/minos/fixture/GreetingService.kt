package com.minos.fixture

interface GreetingPort { fun greet(name: String): String }

class GreetingService : GreetingPort {
    override fun greet(name: String): String = "Hello, $name"
}

class GreetingController(private val service: GreetingPort = GreetingService()) {
    fun message(name: String): String = service.greet(name)
}
