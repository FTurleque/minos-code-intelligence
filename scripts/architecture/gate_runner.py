"""Shared command-line runner of the architecture gates: one report on success, one failure line on stderr.

This module is not a gate: it has no ``check-`` in its name. Its own self-test, ``test_gate_runner.py``, run by the
``invariants`` job, proves the report and code 0, the ``<label> FAILED: <message>`` line and code 1, and that an
interruption which is not an Exception is never returned as a success. Its users are check-module-boundaries.py and
check-private-io.py. A gate imports it as follows, whether it runs as a script or is loaded by its self-test:

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from gate_runner import run_gate
"""

from __future__ import annotations

import sys
from collections.abc import Callable


def run_gate(label: str, check: Callable[[], str]) -> int:
    """Runs ``check`` and returns the exit code of the gate.

    The text ``check`` returns is printed on stdout and the code is 0. Any exception it raises is reported on stderr
    as ``<label> FAILED: <message>`` and the code is 1.
    """
    try:
        report = check()
    except Exception as exception:
        print(f"{label} FAILED: {exception}", file=sys.stderr)
        return 1
    print(report)
    return 0
