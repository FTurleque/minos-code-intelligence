package example

class App(private val core: Core = Core()) { fun message(): String = core.greeting() }
