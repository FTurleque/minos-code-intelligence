#!/usr/bin/env python3
"""Auto-test des hooks `.claude/hooks/` quand la session travaille dans un arbre de travail.

Chaque hook est lancé comme le fait Claude Code : événement JSON sur l'entrée standard, `CLAUDE_PROJECT_DIR`
désignant le dossier principal. Les dépôts sont temporaires : un dossier principal sur `main` et un arbre de
travail sous `.claude/worktrees/` sur une branche thématique. `guard_bash.py` a son propre auto-test
(`test_guard_bash_hook.py`), dont celui-ci reprend la fabrique de dépôts.
"""
from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_guard_bash_hook import Layout, environment, run_git

HOOKS = Path(__file__).resolve().parents[2] / ".claude" / "hooks"

PROTECTED = (
    "openspec/changes/archive/2026-01-01-x/tasks.md",
    "openspec/specs/capacite/spec.md",
    "docs/project/audits/archive/2026-01-01/README.md",
    "docs/project/history/releve.md",
    "minos-app/src/test/resources/characterization/golden.json",
    ".github/workflows/pr-ci.yml",
    ".github/required-checks.json",
    "scripts/architecture/private-io-allowlist.json",
    "docs/architecture/adr/archive/ARCH-0001-ancien.md",
    "LICENSE",
    "SECURITY.md",
    "CONTRIBUTING.md",
    ".gitleaks.toml",
)
GENERATED = (
    "target/classes/A.class",
    "minos-domain/target/surefire-reports/x.xml",
    "minos-intellij/build/libs/x.jar",
    ".intellijPlatform/cache.txt",
    "docs/developer/generated/product-facts.md",
)
ADR = "docs/architecture/adr/ARCH-9999-exemple.md"


def write(base: Path, relative: str, text: str = "contenu\n") -> Path:
    path = base / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))
    return path


class HookCase(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        layout = Layout(self.base, "main", "fix/theme")
        self.root, self.tree = layout.root, layout.tree

    def run_hook(self, name: str, event: dict, **extra: str) -> dict:
        """La sortie `hookSpecificOutput` du hook, ou {} quand il ne dit rien."""
        done = subprocess.run(
            [sys.executable, str(HOOKS / name)], input=json.dumps(event), capture_output=True, text=True,
            encoding="utf-8", env=environment(CLAUDE_PROJECT_DIR=str(self.root), **extra), cwd=self.root, check=False)
        self.assertEqual(0, done.returncode, done.stderr)
        return json.loads(done.stdout)["hookSpecificOutput"] if done.stdout.strip() else {}


class GuardPathsTest(HookCase):
    def judge(self, path: Path | str, cwd: Path | None = None, content: str = "x",
              field: str = "file_path") -> tuple[str, str]:
        """(`deny`, `ask` ou `passe`, motif)."""
        event = {"tool_name": "Write", "tool_input": {field: str(path), "content": content},
                 "cwd": str(cwd or self.tree)}
        out = self.run_hook("guard_paths.py", event)
        return out.get("permissionDecision", "passe"), out.get("permissionDecisionReason", "")

    def expect(self, decision: str, relative: str, base: Path) -> None:
        with self.subTest(base=base.name, relative=relative):
            self.assertEqual(decision, self.judge(base / relative)[0])

    def test_protected_files_ask_in_the_main_folder_and_in_a_tree(self) -> None:
        for base in (self.root, self.tree):
            for relative in PROTECTED:
                write(base, relative)
                self.expect("ask", relative, base)

    def test_generated_files_are_denied_in_the_main_folder_and_in_a_tree(self) -> None:
        for base in (self.root, self.tree):
            for relative in GENERATED:
                self.expect("deny", relative, base)

    def test_ordinary_files_pass(self) -> None:
        for base in (self.root, self.tree):
            self.expect("passe", "minos-domain/src/main/java/A.java", base)
        self.expect("passe", "LICENSE", self.base / "hors-depot")

    def test_reasons_name_the_path_inside_the_tree(self) -> None:
        for relative, content in (("openspec/specs/c/spec.md", "x"), ("target/x", "x"),
                                  ("notes.txt", "AKIA" + "A" * 16)):
            with self.subTest(relative=relative):
                reason = self.judge(self.tree / relative, content=content)[1]
                self.assertIn(relative, reason)
                self.assertNotIn(".claude/worktrees", reason)

    def test_an_existing_adr_is_the_one_of_the_edited_tree(self) -> None:
        write(self.tree, ADR)
        self.expect("ask", ADR, self.tree)
        self.expect("passe", ADR, self.root)  # absent du dossier principal : création libre

    def test_an_adr_only_in_the_main_folder_is_new_in_a_tree(self) -> None:
        write(self.root, ADR)
        self.expect("passe", ADR, self.tree)
        self.expect("ask", ADR, self.root)

    def test_the_tree_of_the_file_counts_not_the_one_of_the_session(self) -> None:
        self.assertEqual("ask", self.judge(self.tree / "LICENSE", cwd=self.root)[0])
        self.assertEqual("ask", self.judge(self.root / "LICENSE", cwd=self.tree)[0])

    def test_a_relative_path_starts_from_the_directory_of_the_event(self) -> None:
        write(self.tree, ADR)
        self.assertEqual("ask", self.judge(ADR, cwd=self.tree)[0])
        self.assertEqual("ask", self.judge("workflows/ci.yml", cwd=self.tree / ".github")[0])

    def test_an_independent_clone_inside_the_folder_is_not_a_working_tree(self) -> None:
        for base in (self.root, self.tree):
            clone = base / "target" / "depot-de-test"
            (clone / ".git").mkdir(parents=True)
            self.expect("deny", "target/depot-de-test/src/A.java", base)

    def test_notebook_paths_are_judged_too(self) -> None:
        self.assertEqual("ask", self.judge(self.tree / "openspec/specs/a.ipynb", field="notebook_path")[0])


class PostEditTest(HookCase):
    def notes(self, base: Path, relative: str) -> str:
        event = {"tool_name": "Edit", "tool_input": {"file_path": str(base / relative)}, "cwd": str(base)}
        return self.run_hook("post_edit.py", event).get("additionalContext", "")

    def test_a_new_gate_is_named_by_its_path_inside_the_tree(self) -> None:
        write(self.tree, "scripts/quality/check-exemple.py")
        notes = self.notes(self.tree, "scripts/quality/check-exemple.py")
        self.assertIn("scripts/quality/check-exemple.py est un gate", notes)
        self.assertNotIn(".claude/worktrees", notes)

    def test_gates_that_read_the_file_are_those_of_the_edited_tree(self) -> None:
        write(self.tree, "scripts/quality/check-arbre.py", 'ATTENDU = "FichierCible.java"\n')
        write(self.root, "scripts/quality/check-principal.py", 'ATTENDU = "FichierCible.java"\n')
        for base, seen, unseen in ((self.tree, "check-arbre.py", "check-principal.py"),
                                   (self.root, "check-principal.py", "check-arbre.py")):
            with self.subTest(base=base.name):
                notes = self.notes(base, "src/FichierCible.java")
                self.assertIn("Des gates citent src/FichierCible.java", notes)
                self.assertIn(seen, notes)
                self.assertNotIn(unseen, notes)

    @unittest.skipUnless(importlib.util.find_spec("yaml"), "PyYAML absent : le hook ne charge pas le YAML")
    def test_a_broken_workflow_of_a_tree_is_reported(self) -> None:
        write(self.tree, ".github/workflows/casse.yml", "jobs: [\n")
        write(self.root, ".github/workflows/casse.yml", "jobs: {}\n")
        self.assertIn(".github/workflows/casse.yml n'est plus un YAML valide",
                      self.notes(self.tree, ".github/workflows/casse.yml"))
        self.assertNotIn("YAML valide", self.notes(self.root, ".github/workflows/casse.yml"))

    def test_a_powershell_script_in_lf_is_read_in_its_tree(self) -> None:
        write(self.tree, "scripts/a.ps1", "Write-Output 1\n")
        write(self.root, "scripts/a.ps1", "Write-Output 1\r\n")
        notes = self.notes(self.tree, "scripts/a.ps1")
        self.assertIn("scripts/a.ps1 est en LF", notes)
        self.assertNotIn(".claude/worktrees", notes)
        self.assertNotIn("en LF", self.notes(self.root, "scripts/a.ps1"))


class SessionStartTest(HookCase):
    SUIVI = "docs/project/audits/2026-10-10/SUIVI.md"
    FAKE = "import pathlib\nprint(pathlib.Path('changements.txt').read_text())\n"

    def setUp(self) -> None:
        super().setUp()
        for base, name, sprint in ((self.root, "principal", 1), (self.tree, "arbre", 2)):
            write(base, "changements.txt", f"changement-{name}  1/2 tasks\n")
            write(base, self.SUIVI, f"## Sprint {sprint} {name}\n")
        run_git(self.tree, "commit", "--allow-empty", "-m", "propre à l'arbre")
        self.shims = self.base / "outils"
        script = write(self.shims, "faux_openspec.py", self.FAKE)
        if os.name == "nt":
            write(self.shims, "openspec.cmd", f'@"{sys.executable}" "{script}" %*\r\n')
        else:
            write(self.shims, "openspec", f'#!/bin/sh\nexec "{sys.executable}" "{script}" "$@"\n').chmod(0o755)

    def context(self, cwd: Path | None) -> str:
        event = {"hook_event_name": "SessionStart"}
        if cwd is not None:
            event["cwd"] = str(cwd)
        path = str(self.shims) + os.pathsep + os.environ.get("PATH", "")
        return self.run_hook("session_start.py", event, PATH=path)["additionalContext"]

    def head(self, directory: Path) -> str:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=directory, env=environment(),
                              capture_output=True, text=True, check=True).stdout.strip()

    def test_a_session_in_a_tree_is_situated_in_that_tree(self) -> None:
        write(self.tree, "src/dossier/neuf.txt")
        for cwd in (self.tree, self.tree / "src" / "dossier"):
            with self.subTest(cwd=cwd.name):
                text = self.context(cwd)
                self.assertIn(f"branche fix/theme @ {self.head(self.tree)}, 3 fichier(s)", text)
                self.assertIn("changement-arbre", text)
                self.assertIn("Sprint 2 arbre", text)
                self.assertNotIn("principal", text)
                self.assertNotIn("Branche protégée", text)

    def test_otherwise_the_session_is_situated_in_the_main_folder(self) -> None:
        for cwd in (self.root, None, self.base, self.root / "absent"):
            with self.subTest(cwd=cwd):
                text = self.context(cwd)
                self.assertIn(f"branche main @ {self.head(self.root)}", text)
                self.assertIn("changement-principal", text)
                self.assertIn("Sprint 1 principal", text)
                self.assertIn("Branche protégée", text)


if __name__ == "__main__":
    unittest.main()
