"""PreToolUse (Bash) : garde-fous de gouvernance Git et GitHub de MINOS.

Refuse : push forcé, push direct vers `main` ou `develop`, contournement des hooks (`--no-verify`,
signature désactivée), `gh pr merge --admin`, suppression ou déplacement d'un tag de release
(les tags `vX.Y.Z` sont immuables, jamais retaguées), suppression d'une release.
Demande confirmation : `git reset --hard`, `git clean`, `git push --force-with-lease`,
`git checkout -- .` / `git restore .` (travail local perdu).

Tout le reste passe. Voir docs/developer/ai-configuration.md.
"""
from __future__ import annotations

import re
import shlex

from _common import PROTECTED_BRANCHES, current_branch, permission, read_event

SEPARATORS = re.compile(r"\s*(?:&&|\|\||;|\||\n)\s*")
RELEASE_TAG = re.compile(r"^(?:refs/tags/)?v\d+\.\d+\.\d+")


def segments(command: str) -> list[str]:
    return [part.strip() for part in SEPARATORS.split(command) if part.strip()]


def tokens(segment: str) -> list[str]:
    try:
        return shlex.split(segment, posix=True)
    except ValueError:
        return segment.split()


def strip_wrappers(words: list[str]) -> list[str]:
    """Retire `rtk`, `rtk proxy`, `env VAR=x`, `sudo`, `command` qui précèdent la commande réelle."""
    index = 0
    while index < len(words):
        word = words[index]
        if word in ("rtk", "proxy", "sudo", "command", "env") or re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", word):
            index += 1
            continue
        break
    return words[index:]


def check_git_push(words: list[str]) -> None:
    args = words[2:]
    if any(a == "--force" or a.startswith("--force=") or (re.fullmatch(r"-[a-zA-Z]+", a) and "f" in a) for a in args):
        permission("deny", "Push forcé refusé : les branches partagées ne se réécrivent pas. "
                           "Corriger par un nouveau commit, ou demander explicitement à l'utilisateur.")
    if any(a.startswith("--force-with-lease") for a in args):
        permission("ask", "Push avec --force-with-lease : confirmer que la réécriture de l'historique est voulue.")
    if "--no-verify" in args:
        permission("deny", "--no-verify refusé : les hooks et gates ne se contournent pas.")
    if any(a in ("--delete", "-d") for a in args) or any(a.startswith(":") for a in args):
        for a in args:
            target = a.lstrip(":")
            if RELEASE_TAG.match(target):
                permission("deny", "Suppression d'un tag de release refusée : les tags vX.Y.Z sont immuables.")
    positional = [a for a in args if not a.startswith("-")]
    refspecs = positional[1:] if len(positional) > 1 else []
    destinations = []
    for spec in refspecs:
        spec = spec.lstrip("+")
        destinations.append(spec.split(":", 1)[1] if ":" in spec else spec)
    for destination in destinations:
        short = destination.removeprefix("refs/heads/")
        if short in PROTECTED_BRANCHES:
            permission("deny", f"Push direct vers {short} refusé : passer par une branche thématique et une PR "
                               "(ruleset « Protect main & develop »).")
    if not refspecs and "--tags" not in args and current_branch() in PROTECTED_BRANCHES:
        permission("deny", f"La branche courante est {current_branch()} : pousser depuis une branche thématique "
                           "(docs/, ci/, fix/, feat/, refactor/, build/, sec/, chore/) puis ouvrir une PR.")


def check_git(words: list[str]) -> None:
    if len(words) < 2 or words[0] != "git":
        return
    sub = words[1]
    rest = words[2:]
    if sub == "push":
        check_git_push(words)
    elif sub in ("commit", "merge", "rebase", "cherry-pick", "revert", "pull"):
        if "--no-verify" in rest or (sub == "commit" and "-n" in rest):
            permission("deny", "--no-verify refusé : les hooks ne se contournent pas.")
        if any("commit.gpgsign=false" in a for a in words) or "--no-gpg-sign" in rest:
            permission("deny", "Désactiver la signature des commits est refusé.")
    elif sub == "tag":
        if any(a in ("-d", "-f", "--delete", "--force") for a in rest) and any(RELEASE_TAG.match(a) for a in rest):
            permission("deny", "Suppression ou déplacement d'un tag de release refusé : les tags sont immuables.")
    elif sub == "reset" and "--hard" in rest:
        permission("ask", "git reset --hard perd le travail local non commité. Confirmer.")
    elif sub == "clean" and any(re.match(r"^-[a-zA-Z]*f", a) or a == "--force" for a in rest):
        permission("ask", "git clean supprime des fichiers non suivis (dont des audits ou scratchpads locaux). Confirmer.")
    elif sub in ("checkout", "restore") and (rest[-1:] == ["."] or rest[-2:] == ["--", "."]):
        permission("ask", "Restaurer tout l'arbre de travail perd les modifications locales. Confirmer.")


def check_gh(words: list[str]) -> None:
    if len(words) < 3 or words[0] != "gh":
        return
    if words[1] == "pr" and words[2] == "merge" and "--admin" in words:
        permission("deny", "gh pr merge --admin refusé : le ruleset (checks exigés, branche à jour) ne se contourne pas.")
    if words[1] == "release" and words[2] in ("delete", "delete-asset", "edit", "upload"):
        permission("deny", "Les releases sont immuables : ne pas modifier ni supprimer une release publiée.")


def main() -> None:
    event = read_event()
    command = (event.get("tool_input") or {}).get("command") or ""
    for segment in segments(command):
        words = strip_wrappers(tokens(segment))
        if not words:
            continue
        check_git(words)
        check_gh(words)


if __name__ == "__main__":
    main()
