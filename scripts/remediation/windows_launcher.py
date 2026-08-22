#!/usr/bin/env python3
"""Assembles a Windows containment launcher the same way the runtime does.

The Job Object and AppContainer launchers are stored as a template plus shared Win32 fragments so
the interop surface exists once. The remediation gates must still assert against the script that is
actually written to disk, not against a template with holes in it, so they resolve the includes
exactly as WindowsContainmentScript does before matching their invariants.
"""

from __future__ import annotations

import re
from pathlib import Path

INCLUDE_PREFIX = "#minos-include:"
FRAGMENT_DIRECTORY = "windows-fragments"
SAFE_FRAGMENT = re.compile(r"^[a-z0-9-]+$")


def is_assembled_launcher(root: Path, relative: str) -> bool:
    """True when the path names a launcher that exists only as a template."""
    return not (root / relative).is_file() and (root / (relative + ".template")).is_file()


def assemble(root: Path, relative: str) -> str:
    """Resolves every include directive in the launcher template against its fragments."""
    template_path = root / (relative + ".template")
    if not template_path.is_file():
        raise RuntimeError(f"missing Windows launcher template: {relative}.template")
    fragments = template_path.parent / FRAGMENT_DIRECTORY

    out: list[str] = []
    seen: set[str] = set()
    for line in template_path.read_text(encoding="utf-8").split("\n"):
        stripped = line.rstrip("\r")
        if not stripped.startswith(INCLUDE_PREFIX):
            out.append(line)
            continue
        name = stripped[len(INCLUDE_PREFIX):].strip()
        if not SAFE_FRAGMENT.match(name):
            raise RuntimeError(f"invalid Windows launcher fragment name: {name}")
        if name in seen:
            raise RuntimeError(f"Windows launcher template includes a fragment twice: {name}")
        seen.add(name)
        fragment = fragments / f"{name}.ps1frag"
        if not fragment.is_file():
            raise RuntimeError(f"missing Windows launcher fragment: {name}")
        out.append(fragment.read_text(encoding="utf-8"))
    return "\n".join(out)
