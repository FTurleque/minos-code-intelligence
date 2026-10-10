#!/usr/bin/env python3
"""Rejoue en local les gates statiques du job `invariants` de .github/workflows/pr-ci.yml.

La liste n'est pas recopiée : elle est lue dans le workflow, donc toujours celle de la CI.

    python .claude/scripts/run_gates.py                # tous les gates et auto-tests python
    python .claude/scripts/run_gates.py --fast         # seulement les gates (sans auto-tests)
    python .claude/scripts/run_gates.py --only docs    # commandes dont le texte contient « docs »
    python .claude/scripts/run_gates.py --list         # affiche sans exécuter
    python .claude/scripts/run_gates.py --with-pwsh    # ajoute les scripts .ps1 du job (pwsh requis)

Code de sortie : 0 si tout passe, 1 si au moins un gate échoue, 2 si le workflow est illisible.
Ne lance ni Maven ni Docker (les jobs `verify` restent à la CI ou à /minos:verify).
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "pr-ci.yml"
COMMAND = re.compile(r"^\s*(?:run:\s*)?((?:python\s+scripts/|\./scripts/)\S.*)$")


def invariants_commands() -> list[str]:
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if re.match(r"^  invariants:\s*$", line))
    except StopIteration:
        raise SystemExit("job `invariants` introuvable dans pr-ci.yml") from None
    commands: list[str] = []
    for line in lines[start + 1:]:
        if re.match(r"^  [A-Za-z0-9_-]+:\s*$", line):  # job suivant
            break
        match = COMMAND.match(line)
        if match and "${{" not in line:
            commands.append(match.group(1).strip())
    return commands


def is_selftest(command: str) -> bool:
    return "--self-test" in command or re.search(r"/test_[^ ]+\.py", command) is not None or "test-" in command


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fast", action="store_true", help="exclut les auto-tests")
    parser.add_argument("--only", default="", help="ne garde que les commandes contenant ce texte")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--with-pwsh", action="store_true")
    parser.add_argument("--timeout", type=int, default=300, help="secondes par gate")
    args = parser.parse_args()

    try:
        commands = invariants_commands()
    except OSError as error:
        print(f"pr-ci.yml illisible : {error}", file=sys.stderr)
        return 2
    selected = []
    for command in commands:
        if command.startswith("./scripts/") and not args.with_pwsh:
            continue
        if args.fast and is_selftest(command):
            continue
        if args.only and args.only not in command:
            continue
        selected.append(command)

    if args.list:
        print("\n".join(selected))
        return 0

    environment = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    failures: list[tuple[str, str]] = []
    for command in selected:
        if command.startswith("python "):
            argv = [sys.executable, *command.split()[1:]]
        else:
            pwsh = shutil.which("pwsh") or shutil.which("powershell")
            if not pwsh:
                print(f"SAUTÉ  {command} (pwsh absent)")
                continue
            argv = [pwsh, "-NoProfile", "-File", *command.split()]
        started = time.time()
        try:
            done = subprocess.run(argv, cwd=ROOT, env=environment, capture_output=True, text=True,
                                  encoding="utf-8", errors="replace", timeout=args.timeout, check=False)
            ok, output = done.returncode == 0, (done.stdout + done.stderr)
        except subprocess.TimeoutExpired:
            ok, output = False, f"délai de {args.timeout}s dépassé"
        seconds = time.time() - started
        print(f"{'OK    ' if ok else 'ÉCHEC '} {seconds:5.1f}s  {command}")
        if not ok:
            failures.append((command, "\n".join(output.strip().splitlines()[-25:])))

    print(f"\n{len(selected) - len(failures)}/{len(selected)} gates passent.")
    for command, tail in failures:
        print(f"\n--- {command}\n{tail}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
