#!/usr/bin/env python3
"""Self-test of gate_runner.py, the command-line runner shared by check-module-boundaries.py and check-private-io.py.

run_gate prints the report of a passing check on stdout and returns 0 with nothing on stderr; it reports an exception
on stderr as ``<label> FAILED: <message>`` and returns 1 with nothing on stdout; an interruption that is not an
Exception (SystemExit, KeyboardInterrupt) propagates and is never returned as a success.
"""

from __future__ import annotations

import contextlib
import io
import sys
import unittest

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from gate_runner import run_gate

LABEL = "DEMO GATE"


def run(check):
    """Runs run_gate on ``check``; returns the exit code, stdout and stderr."""
    with contextlib.redirect_stdout(io.StringIO()) as output, contextlib.redirect_stderr(io.StringIO()) as error:
        code = run_gate(LABEL, check)
    return code, output.getvalue(), error.getvalue()


class RunGateTest(unittest.TestCase):

    def test_a_report_is_printed_on_stdout_with_code_0(self):
        code, output, error = run(lambda: "DEMO GATE SUCCESS (checks=3)")
        self.assertEqual(0, code)
        self.assertEqual("DEMO GATE SUCCESS (checks=3)\n", output)
        self.assertEqual("", error)

    def test_an_exception_is_reported_on_stderr_with_code_1(self):
        for exception in (RuntimeError("rule A2 violated"), ValueError("rule A2 violated")):
            with self.subTest(exception=type(exception).__name__):
                def check():
                    raise exception
                code, output, error = run(check)
                self.assertEqual(1, code)
                self.assertEqual("", output)
                self.assertEqual(f"{LABEL} FAILED: rule A2 violated\n", error)

    def test_an_interruption_propagates_and_is_never_a_success(self):
        for interruption in (SystemExit(0), SystemExit(2), KeyboardInterrupt()):
            with self.subTest(interruption=repr(interruption)):
                def check():
                    raise interruption
                with self.assertRaises(type(interruption)) as raised:
                    run(check)
                self.assertIs(interruption, raised.exception)


if __name__ == "__main__":
    unittest.main()
