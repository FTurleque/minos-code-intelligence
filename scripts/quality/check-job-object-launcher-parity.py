#!/usr/bin/env python3
"""Fail when the Job Object launcher of the IntelliJ plugin drifts from the one the engine writes (AUD-QUA-01).

The plugin is an external client of the CLI (no `com.minos:*` dependency): it carries its own copy of the Windows
launcher that owns a process in a Job Object, `windows-cli-job-owner-v1.ps1`. The engine assembles its launcher from
a template and shared fragments (`windows-job-object-owner-v1.ps1.template`, `windows-fragments/`), exactly as
`scripts/remediation/windows_launcher.py` does. Nothing used to compare the two: a fix on one side did not reach the
other, and nobody saw it.

Rules:

1. The template and every fragment it includes exist, and so does the plugin copy: a missing file fails, never
   "nothing to compare".
2. Each fragment of IDENTICAL_FRAGMENTS appears, line for line, in the plugin copy (line endings and trailing spaces
   aside).
3. Every other zone where the assembled engine launcher and the plugin copy differ belongs to one entry of the closed
   list ASSUMED_DIFFERENCES (a zone belongs to the first entry one of whose markers it contains), and an entry never
   holds more divergent lines (both sides counted) than its maximum. An entry that no zone uses is refused, so the
   list only shrinks; a maximum is raised only with a written reason.

Alignments of pure form are made in the plugin copy, never in a fragment: WindowsContainmentScriptTest freezes the
engine fragments against their qualified baseline. What this gate does not prove: that either launcher behaves as
written (the Windows tests of each side do), nor that an assumed difference is right (the reason says why it exists).

`--root` points the gate at another tree (default: this repository).
Self-test: scripts/quality/test_check_job_object_launcher_parity.py.
"""
from __future__ import annotations

import difflib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - après l'ajout du dossier du script au chemin

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
ENGINE_LAUNCHER = "minos-runtime-local/src/main/resources/com/minos/runtime/local/windows-job-object-owner-v1.ps1"
PLUGIN_LAUNCHER = "minos-intellij/src/main/resources/com/minos/intellij/protocol/windows-cli-job-owner-v1.ps1"
ASSEMBLER = "scripts/remediation/windows_launcher.py"

# Fragments the plugin copy carries word for word.
IDENTICAL_FRAGMENTS = (
    "command-line-quoting",
    "createprocess-call",
    "createprocess-import",
    "job-configuration-verify",
    "job-limit-structs",
    "kernel32-imports",
    "startupinfo-struct",
)

# (name, markers, maximum divergent lines both sides counted, reason). Order matters: a zone belongs to the first
# entry one of whose markers appears in its lines.
ASSUMED_DIFFERENCES = (
    ("raw-cmd-mode",
     ("rawCmd", "BuildRawCmdCommandLine", "isPreQuotedForCmdShell", "command.mode", "using System.IO"),
     45,
     "MINOS-AUD-C04: the plugin hands a batch launcher's command string to cmd.exe raw, in an explicit `cmd-c` mode "
     "whose shape is verified strictly; the engine still detects a pre-quoted /c argument. Aligning the engine is a "
     "change of the containment of untrusted code (decision D3), outside this gate."),
    ("owned-process-name",
     ("MINOS CLI", "CLI environment entry", "provider is not a member", "MINOS ownership Job Object",
      "provider environment entry"),
     6,
     "The messages name the process each launcher owns: the MINOS CLI for the plugin, a provider for the engine."),
    ("plan-deletion-point",
     ("Remove-Item -LiteralPath $Path",),
     4,
     "The engine deletes the plan while reading it; the plugin deletes it after reading the command mode, before the "
     "CLI starts (counted with raw-cmd-mode there). Both delete it before any process is resumed."),
)


def normalized(text: str) -> list[str]:
    lines = [line.rstrip() for line in text.replace("\r\n", "\n").split("\n")]
    while lines and not lines[-1]:
        lines.pop()
    return lines


def contains(haystack: list[str], needle: list[str]) -> bool:
    if not needle:
        return True
    first = needle[0]
    return any(haystack[index:index + len(needle)] == needle
               for index, line in enumerate(haystack) if line == first)


def load_assembler(root: Path):
    path = root / ASSEMBLER
    if not path.is_file():
        raise RuntimeError(f"{ASSEMBLER} is missing: the engine launcher cannot be assembled")
    sys.path.insert(0, str(path.parent))
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location("windows_launcher_for_parity", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        sys.path.pop(0)


def check(root: Path) -> tuple[list[str], int, int]:
    """(failures, number of identical fragments found, number of divergent zones accounted for)."""
    plugin_path = root / PLUGIN_LAUNCHER
    if not plugin_path.is_file():
        return [f"{PLUGIN_LAUNCHER} is missing: nothing can stand for the plugin launcher"], 0, 0
    try:
        engine = normalized(load_assembler(root).assemble(root, ENGINE_LAUNCHER))
    except RuntimeError as error:
        return [f"{ENGINE_LAUNCHER}: {error}"], 0, 0
    plugin = normalized(plugin_path.read_text(encoding="utf-8"))
    failures: list[str] = []

    fragments = (root / ENGINE_LAUNCHER).parent / "windows-fragments"
    identical = 0
    for name in IDENTICAL_FRAGMENTS:
        fragment = fragments / f"{name}.ps1frag"
        if not fragment.is_file():
            failures.append(f"{fragment.relative_to(root).as_posix()} is missing: the fragment listed as identical "
                            "cannot be compared")
            continue
        if contains(plugin, normalized(fragment.read_text(encoding="utf-8"))):
            identical += 1
        else:
            failures.append(f"{PLUGIN_LAUNCHER} no longer carries the engine fragment '{name}' word for word: align "
                            "the plugin copy, or move the difference to ASSUMED_DIFFERENCES with its reason")

    used: dict[str, int] = {}
    zones = 0
    matcher = difflib.SequenceMatcher(None, engine, plugin, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            continue
        zones += 1
        lines = engine[i1:i2] + plugin[j1:j2]
        owner = next((name for name, markers, _maximum, _reason in ASSUMED_DIFFERENCES
                      if any(marker in line for marker in markers for line in lines)), None)
        if owner is None:
            first = next((line.strip() for line in lines if line.strip()), "")
            failures.append(f"{PLUGIN_LAUNCHER}:{j1 + 1}: differs from the engine launcher (engine line {i1 + 1}, "
                            f"{i2 - i1} engine / {j2 - j1} plugin lines, starting '{first}') outside every "
                            "assumed difference")
            continue
        used[owner] = used.get(owner, 0) + len(lines)
    for name, _markers, maximum, _reason in ASSUMED_DIFFERENCES:
        if name not in used:
            failures.append(f"assumed difference '{name}' matches no difference any more: remove it")
        elif used[name] > maximum:
            failures.append(f"assumed difference '{name}' now spans {used[name]} divergent lines, more than its "
                            f"maximum {maximum}: align the plugin copy, or raise the maximum with a written reason")
    return failures, identical, zones


def main(argv: list[str] | None = None) -> int:
    root = gate_cli.parse_root(__doc__, DEFAULT_ROOT, argv)
    failures, identical, zones = check(root)
    if failures:
        print("JOB OBJECT LAUNCHER PARITY GATE FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1
    print(f"JOB OBJECT LAUNCHER PARITY GATE SUCCESS (identical fragments={identical}, assumed divergent zones={zones})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
