from minos_fixture.service import render_greeting


def test_render_greeting() -> None:
    assert render_greeting("MINOS") == "Hello, MINOS"
