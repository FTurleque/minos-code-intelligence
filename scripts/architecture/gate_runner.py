"""Shared command-line runner of the architecture gates: one report on success, one failure line on stderr.

This module is not a gate: it has no ``check-`` in its name and no self-test of its own. The self-tests of the gates
that use it exercise it (``test_check_module_boundaries.py``, ``test_check_private_io.py``). A gate imports it as
follows, whether it runs as a script or is loaded by its self-test:

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
