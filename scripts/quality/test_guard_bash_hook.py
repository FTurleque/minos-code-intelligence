#!/usr/bin/env python3
"""Auto-test du garde `.claude/hooks/guard_bash.py` (outils Bash et PowerShell, branche du dossier d'exécution).

Le garde est lancé comme le fait Claude Code : événement JSON sur l'entrée standard, `CLAUDE_PROJECT_DIR`
désignant le dossier principal. Les dépôts sont temporaires : un dossier principal et un arbre de travail sous
`.claude/worktrees/`, l'un sur `main` et l'autre sur une branche thématique, dans les deux sens.
"""
from __future__ import annotations

import json
import os
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HOOK = Path(__file__).resolve().parents[2] / ".claude" / "hooks" / "guard_bash.py"


def environment(**extra: str) -> dict:
    """Environnement sans configuration Git du poste ni redirection de dépôt héritée."""
    clean = {name: value for name, value in os.environ.items() if not name.startswith("GIT_")}
    clean.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1", PYTHONUTF8="1", **extra)
    return clean


def run_git(directory: Path, *arguments: str) -> None:
    subprocess.run(["git", "-c", "user.name=test", "-c", "user.email=test@example.invalid",
                    "-c", "commit.gpgsign=false", *arguments],
                   cwd=directory, env=environment(), check=True, capture_output=True)


def quoted(path: Path) -> str:
    return shlex.quote(path.as_posix())


class Layout:
    """Un dossier principal sur `root_branch` et un arbre de travail sur `tree_branch`."""

    def __init__(self, base: Path, root_branch: str, tree_branch: str) -> None:
        self.root = base / "depot"
        self.root.mkdir(parents=True)
        run_git(self.root, "init", "-b", "main")
        run_git(self.root, "commit", "--allow-empty", "-m", "init")
        if root_branch != "main":
            run_git(self.root, "switch", "-c", root_branch)
        self.tree = self.root / ".claude" / "worktrees" / "wt"
        if tree_branch == "main":
            run_git(self.root, "worktree", "add", str(self.tree), "main")
        else:
            run_git(self.root, "worktree", "add", "-b", tree_branch, str(self.tree))


class GuardCase(unittest.TestCase):
    tool = "Bash"
    root_branch = "main"
    tree_branch = "fix/theme"

    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        base = Path(cls.temporary.name).resolve()
        cls.layout = Layout(base, cls.root_branch, cls.tree_branch)
        cls.plain = base / "hors-depot"
        cls.plain.mkdir()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def decision(self, command: str, cwd: Path | None) -> str:
        """`deny`, `ask`, ou `passe` quand le garde ne dit rien."""
        event = {"tool_name": self.tool, "tool_input": {"command": command}}
        if cwd is not None:
            event["cwd"] = str(cwd)
        done = subprocess.run(
            [sys.executable, str(HOOK)], input=json.dumps(event), capture_output=True, text=True, encoding="utf-8",
            env=environment(CLAUDE_PROJECT_DIR=str(self.layout.root)), cwd=self.layout.root, check=False)
        self.assertEqual(0, done.returncode, done.stderr)
        if not done.stdout.strip():
            return "passe"
        return json.loads(done.stdout)["hookSpecificOutput"]["permissionDecision"]

    def refused(self, command: str, cwd: Path | None) -> None:
        with self.subTest(command=command):
            self.assertEqual("deny", self.decision(command, cwd))

    def accepted(self, command: str, cwd: Path | None) -> None:
        with self.subTest(command=command):
            self.assertEqual("passe", self.decision(command, cwd))


class MainFolderOnMainTest(GuardCase):
    """Dossier principal sur `main`, arbre de travail sur une branche thématique."""

    def test_push_from_the_thematic_tree_is_accepted(self) -> None:
        tree = self.layout.tree
        for command in ("git push", "git push -u origin", "git push -u origin HEAD", "git push 2>&1 | tail -5",
                        "git push origin 2>&1", "rtk git push"):
            self.accepted(command, tree)

    def test_push_from_the_main_folder_is_refused(self) -> None:
        root = self.layout.root
        for command in ("git push", "git push origin", "git push -u origin HEAD", "git push origin @",
                        "git push origin 2>&1", "git push origin > journal.txt", "git push origin # envoi",
                        "git push -o ci.skip origin", "git push --follow-tags", "(git push)"):
            self.refused(command, root)

    def test_named_thematic_branch_and_tags_stay_accepted_from_main(self) -> None:
        self.accepted("git push origin fix/theme", self.layout.root)
        self.accepted("git push --tags", self.layout.root)

    def test_push_towards_main_is_refused_from_the_thematic_tree(self) -> None:
        for command in ("git push origin main", "git push origin HEAD:main", "git push origin HEAD:refs/heads/main",
                        "git push --all origin", "git push --mirror", "git push origin :", "(git push origin main)",
                        f"git -C {quoted(self.layout.root)} push origin main"):
            self.refused(command, self.layout.tree)

    def test_git_dash_c_designates_the_directory(self) -> None:
        self.accepted(f"git -C {quoted(self.layout.tree)} push", self.layout.root)
        self.accepted("git -C .claude/worktrees/wt push", self.layout.root)
        self.refused(f"git -C {quoted(self.layout.root)} push", self.layout.tree)
        self.refused("git -C ../../.. push", self.layout.tree)
        self.refused("git -c core.quotepath=false -C ../../.. push", self.layout.tree)

    def test_leading_cd_designates_the_directory(self) -> None:
        root, tree = self.layout.root, self.layout.tree
        self.accepted(f"cd {quoted(tree)} && git push", root)
        self.accepted("cd .claude/worktrees/wt && git status && git push", root)
        self.accepted(f"(cd {quoted(tree)} && git push)", root)
        self.accepted(f"# envoi\ncd {quoted(tree)}\ngit push", root)
        self.refused(f"cd {quoted(root)} && git push", tree)
        self.refused(f"cd {quoted(root)}; git push", tree)
        self.refused(f"(cd {quoted(root)} && git push)", tree)

    @unittest.skipUnless(os.name == "nt", "chemins MSYS de Git Bash")
    def test_git_bash_drive_paths_are_understood(self) -> None:
        posix = self.layout.tree.as_posix()
        msys = f"/{posix[0].lower()}{posix[2:]}"
        self.accepted(f"cd {shlex.quote(msys)} && git push", self.layout.root)

    def test_unreadable_branch_is_refused(self) -> None:
        root, tree = self.layout.root, self.layout.tree
        self.refused("git push", self.plain)
        self.refused("git push", None)
        self.refused("git push origin HEAD", self.plain)
        self.refused("git -C absent push", tree)
        self.refused("cd absent; git push", tree)
        self.refused('cd "$AILLEURS" && git push', tree)
        self.refused("cd - && git push", tree)
        self.refused("git --git-dir=autre/.git push", tree)
        self.refused("GIT_DIR=autre/.git git push", tree)
        self.refused(f"cd {quoted(tree)} | git push", root)
        self.refused(f"cd {quoted(tree)} || git push", root)
        self.refused(f"true && cd {quoted(tree)} && git push", root)
        self.refused(f"(cd {quoted(tree)} && git status) && git push", root)
        self.refused(f"pushd {quoted(root)} && git push", tree)

    def test_branch_change_inside_the_command_is_refused(self) -> None:
        self.refused("git switch main && git push", self.layout.tree)
        self.refused("git checkout main; git push origin HEAD", self.layout.tree)
        self.refused("gh pr checkout 12 && git push", self.layout.tree)
        self.accepted("git checkout -- fichier.txt && git push", self.layout.tree)

    def test_global_options_do_not_hide_the_subcommand(self) -> None:
        self.refused("git -c commit.gpgsign=false commit -m x", self.layout.tree)
        self.refused("git --no-pager push origin main", self.layout.tree)


class CommandShapeTest(GuardCase):
    """Formes de commande : groupes, commentaires, messages cités, heredocs, branche créée en route."""

    def test_directory_of_a_group_or_a_comment_is_not_followed(self) -> None:
        root, tree = self.layout.root, self.layout.tree
        self.refused("(\ncd .claude/worktrees/wt\ngit status\n)\ngit push", root)
        self.refused("# aller; cd .claude/worktrees/wt\ngit push", root)
        self.refused("cd .claude/worktrees/wt & git push", root)
        self.refused(f"if [ -d {quoted(root)} ]; then cd {quoted(root)}; fi; git push", tree)
        self.accepted('(cd .claude/worktrees/wt && git commit -m "fix(x): y" && git push)', root)

    def test_quoted_text_and_message_heredocs_are_not_commands(self) -> None:
        for command in ('git commit -m "fix: a && cd b" && git push',
                        'git commit -m "docs: voir; git checkout la branche" && git push',
                        'git commit -m "refuser GIT_DIR= et GIT_WORK_TREE=" && git push',
                        "git commit -m \"$(cat <<'EOF'\ncd ailleurs\ngit checkout main\ngit push origin main\n"
                        "l'arbre dit \"non\"\nEOF\n)\" && git push",
                        "git commit -F - <<'EOF' && git push\nl'arbre\nEOF"):
            self.accepted(command, self.layout.tree)

    def test_heredoc_that_may_be_executed_is_still_read(self) -> None:
        self.refused("cat <<'EOF' | bash\ngit push origin main\nEOF", self.layout.tree)
        self.refused("python - <<EOF\nl'a\nEOF\ngit push origin main\necho \"l'b\"", self.layout.tree)

    def test_branch_created_in_the_command_is_followed_along_and_chains(self) -> None:
        tree = self.layout.tree
        self.accepted("git switch -c fix/neuve && git push -u origin HEAD", tree)
        self.accepted("git checkout -b fix/neuve && git commit -m x && git push", tree)
        self.refused("git switch -C main && git push", tree)
        self.refused("git switch -c fix/neuve; git push", tree)
        self.refused("git switch -c fix/neuve && git status; git push", tree)
        self.refused("git checkout main -- && git push", tree)
        self.refused("gh pr merge 12 --merge --delete-branch && git push", tree)

    def test_other_writings_of_a_push_of_main_are_refused(self) -> None:
        root = self.layout.root
        for command in ("git push -u origin $(git branch --show-current)",
                        'git push -u origin "$(git rev-parse --abbrev-ref HEAD)"', "git push>push.log 2>&1",
                        "git push origin main>push.log", "timeout 60 git push", "git push origin heads/main",
                        "if git push; then echo fait; fi"):
            self.refused(command, root)
        self.accepted("git push -u origin $(git branch --show-current)", self.layout.tree)


class DetachedHeadTest(GuardCase):
    def test_detached_head_is_refused(self) -> None:
        run_git(self.layout.tree, "switch", "--detach")
        self.refused("git push", self.layout.tree)


class MainFolderOnThematicBranchTest(GuardCase):
    """Sens inverse : dossier principal sur une branche thématique, arbre de travail sur `main`."""

    root_branch = "fix/dossier-principal"
    tree_branch = "main"

    def test_push_from_a_tree_on_main_is_refused(self) -> None:
        root, tree = self.layout.root, self.layout.tree
        self.refused("git push", tree)
        self.refused("git push origin HEAD", tree)
        self.refused(f"git -C {quoted(tree)} push", root)
        self.refused(f"cd {quoted(tree)} && git push", root)

    def test_push_from_the_thematic_main_folder_is_accepted(self) -> None:
        self.accepted("git push", self.layout.root)
        self.accepted(f"git -C {quoted(self.layout.root)} push", self.layout.tree)


def literal(path: Path) -> str:
    """Le chemin en chaîne PowerShell littérale, avec les séparateurs du système."""
    return "'" + str(path).replace("'", "''") + "'"


class PowerShellMainFolderOnMainTest(GuardCase):
    """Outil PowerShell : dossier principal sur `main`, arbre de travail sur une branche thématique."""

    tool = "PowerShell"

    def test_pushes_of_the_thematic_tree_are_accepted(self) -> None:
        for command in ("git push", "git push -u origin HEAD", "git push 2>&1 | Select-Object -Last 5",
                        "git push *> journal.txt", "git push 2>$null", "& git push", "rtk git push",
                        "git push -u origin $(git branch --show-current)", "git status; if ($?) { git push }",
                        "git push $distant fix/theme"):
            self.accepted(command, self.layout.tree)

    def test_git_dash_c_reads_a_native_path(self) -> None:
        root, tree = self.layout.root, self.layout.tree
        self.accepted(f"git -C {literal(tree)} push", root)
        if " " not in str(tree):
            self.accepted(f"git -C {tree} push", root)  # `\` est un caractère ordinaire
        self.accepted("git -C .claude/worktrees/wt push", root)
        self.refused(f"git -C {literal(root)} push", tree)

    def test_leading_location_change_designates_the_directory(self) -> None:
        root, tree = self.layout.root, self.layout.tree
        for command in (f"Set-Location {literal(tree)}; git push", f"Push-Location {literal(tree)}\ngit push",
                        f"cd {literal(tree)} && git push", f"sl -LiteralPath {literal(tree)}; git push",
                        f"SET-LOCATION -Path {literal(tree)}; git status; git push",
                        f"$ErrorActionPreference = 'Stop'\nchdir {literal(tree)}\ngit push",
                        f"$ErrorActionPreference='Stop'; $n=3; $env:X =\"y\"; cd {literal(tree)}; git push",
                        f"# envoi\npushd {literal(tree)}; git push"):
            self.accepted(command, root)
        for command in (f"Set-Location {literal(root)}; git push", f"cd {literal(root)} && git push",
                        f"Push-Location -Path {literal(root)}; git push", f"chdir {literal(root)}\ngit push"):
            self.refused(command, tree)

    def test_unfollowed_location_is_refused(self) -> None:
        root, tree = self.layout.root, self.layout.tree
        for command in ("Set-Location $env:AILLEURS; git push", "Set-Location absent; git push", "Pop-Location; git push",
                        "Set-Location; git push", "Set-Location -; git push", "cd..; git push", "N:; git push",
                        "$env:GIT_DIR = 'autre'; git push", "$env:GIT_WORK_TREE='autre'; git push",
                        "Set-Item env:GIT_DIR autre; git push", "git --git-dir=autre/.git push"):
            self.refused(command, tree)
        for command in (f"if ($true) {{ Set-Location {literal(tree)} }}; git push",
                        f"Get-Date; Set-Location {literal(tree)}; git push",
                        f"$a = Get-Date; Set-Location {literal(tree)}; git push",
                        f"Set-Location {literal(tree)} | git push", f"Set-Location {literal(tree)} || git push"):
            self.refused(command, root)

    def test_pushes_towards_main_are_refused_in_every_shape(self) -> None:
        for command in ("git push origin main", "& git push origin main", "GIT push origin main",
                        "git.exe push origin main", "& 'C:\\Program Files\\Git\\cmd\\git.exe' push origin main",
                        ". git push origin main", "if ($?) { git push origin main }",
                        "if ($a) { 'x' } else { git push origin main }", "try { git push origin main } finally { }",
                        "1..2 | ForEach-Object { git push origin main }", "$r = git push origin main",
                        "$r=git push origin main", "$a, $b = git push origin main",
                        "[string]$r += git push origin main", "[void](git push origin main)", "$(git push origin main)",
                        'Write-Host "envoi : $(git push origin main)"', '@"\n$(git push origin main)\n"@',
                        "git status\ngit push origin main", "git push `\n  origin main", "git push origin ma`in",
                        "git push origin main>journal.txt", "git push origin main 2>$null",
                        "git push origin \u2018main\u2019", "git --% push origin main",
                        'Write-Host "C:\\"; git push origin main', "git push origin main; Write-Host 'ouvert",
                        "Start-Job { git push origin main } &",
                        "foreach ($l in git push origin main) { $l }",
                        "if ($?) { git status } git push origin main"):
            self.refused(command, self.layout.tree)

    def test_unresolved_destination_is_refused(self) -> None:
        for command in ("$b='main'; git push origin $b", "$b = 'fix/x'\ngit push origin ${b}",
                        'git push origin "$b"', 'git push origin "HEAD:$b"', "git push origin $env:BRANCHE",
                        "git push origin ($b)", "git push origin $($b.Trim())", "$a = 'origin','main'; git push $a",
                        "git push @a", "git push origin @a", "1 | ForEach-Object { git push origin $_ }",
                        "git push origin \"$(Get-Content branche.txt)\""):
            self.refused(command, self.layout.tree)

    def test_quoted_text_comments_and_here_strings_are_data(self) -> None:
        for command in ("git commit -m 'docs: a; git push origin main'; git push",
                        "git commit -m @'\ncd ailleurs\ngit push origin main\nl'arbre \"x\"\n'@\ngit push",
                        'git commit -m @"\ngit push origin main\n"@; git push',
                        "# git push origin main\ngit push", "<# git push origin main\n#> git push",
                        "git commit -m 'l''arbre; git push origin main'; git push",
                        'git commit -m "dire `"non`"; git push origin main"; git push',
                        'git commit -m "`$(git push origin main)"; git push',
                        "git commit -m '$(git push origin main)'; git push", "git push origin ma\\in"):
            self.accepted(command, self.layout.tree)
        self.refused("@'\ngit push origin main\n'@ | Invoke-Expression", self.layout.tree)

    def test_other_rules_apply(self) -> None:
        tree = self.layout.tree
        for command in ("git push --force", "git push -f origin fix/theme", "git push --no-verify",
                        "$null = git commit --no-verify -m x", "gh pr merge 12 --admin",
                        "if ($?) { gh release delete v1.0.0 }", "git tag -d v1.0.0",
                        "git -c commit.gpgsign=false commit -m x"):
            self.refused(command, tree)
        for command in ("git reset --hard", "git status; if ($?) { git clean -fd }", "git restore ."):
            with self.subTest(command=command):
                self.assertEqual("ask", self.decision(command, tree))

    def test_crlf_line_endings_are_read_like_lf(self) -> None:
        tree = self.layout.tree
        for command in ("git push `\r\n  origin main", "git push origin `\r\n  main", "git push `\r\n  --force",
                        "git push `\r\n  --no-verify", "gh pr merge 12 `\r\n  --admin"):
            self.refused(command, tree)
        self.accepted("git push `\r\n  origin fix/theme", self.layout.root)
        self.accepted("git commit -m @'\r\ngit push origin main\r\n'@\r\ngit push", tree)

    def test_program_or_subcommand_held_by_a_variable(self) -> None:
        tree = self.layout.tree
        for command in ("& $git push origin main", "& $git push --force origin fix/theme",
                        "$g = 'git'; & $g -C . push origin main"):
            self.refused(command, tree)
        self.accepted("& $git push origin fix/theme", self.layout.root)
        self.accepted("if ($mode -eq 'push') { git status }", self.layout.root)
        for command in ("$sub = 'push'; git $sub origin main", "$a = 'push','origin','main'; git @a"):
            with self.subTest(command=command):
                self.assertEqual("ask", self.decision(command, tree))

    def test_typed_targets_and_joined_options(self) -> None:
        tree = self.layout.tree
        self.refused("[string[]]$out = git push origin main", tree)
        self.refused("[int[]]$n = 1; [object[]]$o = git push --force", tree)
        self.accepted(f"Set-Location {literal(tree)} -ErrorAction:Stop; git push", self.layout.root)

    def test_too_deep_nesting_is_read_literally(self) -> None:
        self.refused("(" * 5000 + "\ngit push origin main\n" + ")" * 5000, self.layout.tree)

    def test_branch_change_inside_the_command(self) -> None:
        tree = self.layout.tree
        self.accepted("git switch -c fix/neuve && git push -u origin HEAD", tree)
        self.refused("git switch -c fix/neuve; git push", tree)
        self.refused("git switch main; git push origin $(git branch --show-current)", tree)
        self.refused("if ($a) { git switch -c fix/neuve }; git push", tree)


class PowerShellMainFolderOnThematicBranchTest(GuardCase):
    """Sens inverse pour l'outil PowerShell."""

    tool = "PowerShell"
    root_branch = "fix/dossier-principal"
    tree_branch = "main"

    def test_the_directory_decides(self) -> None:
        root, tree = self.layout.root, self.layout.tree
        self.accepted("git push", root)
        self.accepted(f"Set-Location {literal(root)}; git push", tree)
        self.refused("git push", tree)
        self.refused(f"Set-Location {literal(tree)}; git push", root)
        self.refused(f"git -C {literal(tree)} push", root)
        self.refused("git push -u origin $(git branch --show-current)", tree)


class SettingsWiringTest(unittest.TestCase):
    def test_the_guard_is_wired_to_both_shells(self) -> None:
        settings = json.loads((HOOK.parents[1] / "settings.json").read_text(encoding="utf-8"))
        wired = {tool for entry in settings["hooks"]["PreToolUse"]
                 if any(HOOK.name in hook["command"] for hook in entry["hooks"])
                 for tool in entry["matcher"].split("|")}
        self.assertEqual({"Bash", "PowerShell"}, wired)


if __name__ == "__main__":
    unittest.main()
