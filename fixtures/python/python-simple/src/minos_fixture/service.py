class GreetingService:
    def greet(self, name: str) -> str:
        return f"Hello, {name}"


def render_greeting(name: str) -> str:
    return GreetingService().greet(name)
