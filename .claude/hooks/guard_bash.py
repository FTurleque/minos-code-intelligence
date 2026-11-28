"""PreToolUse (Bash, PowerShell) : garde-fous de gouvernance Git et GitHub de MINOS.

Refuse : push forcé, push qui met à jour `main` (destination `main`, ou push sans destination nommée ou vers
`HEAD` depuis `main`), contournement des hooks (`--no-verify`, signature désactivée), `gh pr merge --admin`,
suppression ou déplacement d'un tag de release (les tags `vX.Y.Z` sont immuables, jamais retaguées),
suppression d'une release.
Demande confirmation : `git reset --hard`, `git clean`, `git push --force-with-lease`,
`git checkout -- .` / `git restore .` (travail local perdu).

La branche d'un push est celle du dossier où la commande s'exécute : le `cwd` de l'événement, modifié par les
`cd` en tête de commande et par `git -C`, ou la branche que la commande crée avant lui dans une chaîne `&&`.
Quand un push dépend de cette branche et qu'elle ne peut pas être établie, il est refusé : dossier ou branche
illisible, HEAD détachée, `cd` ailleurs qu'en tête, `--git-dir`, `--work-tree`, `GIT_DIR`, autre changement de
branche dans la même commande.

L'outil de l'événement (`tool_name`) choisit la lecture de la commande (`_shell.py`). En PowerShell, un push
dont la destination est une variable, un splat ou une expression est refusé.

Tout le reste passe. Voir docs/developer/ai-configuration.md.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

from _common import PROTECTED_BRANCHES, current_branch, permission, read_event
from _shell import UNKNOWN, bash_commands, powershell_commands

RELEASE_TAG = re.compile(r"^(?:refs/tags/)?v\d+\.\d+\.\d+")
REDIRECTION = re.compile(r"^(?:\d*|&)(?:>>|>|<<<|<<|<)(.*)$")
GIT_VALUE_OPTIONS = ("-c", "--namespace", "--config-env", "--attr-source")
PUSH_VALUE_OPTIONS = ("-o", "--push-option", "--receive-pack", "--exec", "--repo")
NEW_BRANCH_OPTIONS = {"switch": ("-c", "-C", "--create", "--force-create"), "checkout": ("-b", "-B")}


def without_redirections(words: list[str]) -> list[str]:
    """Arguments transmis au programme : ni redirections, ni `&` final, ni commentaire."""
    kept: list[str] = []
    target_expected = False
    for word in words:
        if target_expected:
            target_expected = False
            continue
        if word.startswith("#"):
            break
        redirection = REDIRECTION.match(word)
        if redirection:
            target_expected = not redirection.group(1)
        elif word != "&":
            kept.append(word)
    return kept


def resolve_directory(base: Path | None, target: str, msys: bool) -> Path | None:
    """Le dossier désigné par `target` depuis `base`, ou None quand il ne peut pas être établi."""
    if target == "-" or any(character in target for character in ("$", "`", "*", "?", UNKNOWN)):
        return None
    if msys and os.name == "nt":
        target = re.sub(r"^/([A-Za-z])(?=/|$)", r"\1:/", target)  # Git Bash : /n/depot désigne n:/depot
        if target.startswith("/"):  # montage MSYS (/tmp, /usr) inconnu de Windows
            return None
    try:
        path = Path(os.path.expanduser(target))
        if not path.is_absolute():
            if base is None:
                return None
            path = base / path
        path = Path(os.path.normpath(path))
        return path if path.is_dir() else None
    except (OSError, ValueError):
        return None


def changed_directory(base: Path | None, arguments: list[str], msys: bool) -> Path | None:
    targets = [a for a in without_redirections(arguments) if a not in ("-L", "-P", "--")]
    if len(targets) > 1:
        return None
    return resolve_directory(base, targets[0] if targets else "~", msys)


def parse_git(words: list[str], directory: Path | None, msys: bool) -> tuple[str, list[str], Path | None]:
    """Sous-commande, arguments et dossier effectif, options globales de git (`-C`, `-c`…) retirées."""
    redirected = False
    index = 1
    while index < len(words) and words[index].startswith("-"):
        option = words[index]
        value = words[index + 1] if index + 1 < len(words) else ""
        if option == "-C":
            if value:
                directory = resolve_directory(directory, value, msys)
            index += 2
        elif option in ("--git-dir", "--work-tree"):
            redirected = True
            index += 2
        elif option.startswith(("--git-dir=", "--work-tree=")):
            redirected = True
            index += 1
        else:
            index += 2 if option in GIT_VALUE_OPTIONS else 1
    sub = words[index] if index < len(words) else ""
    return sub, words[index + 1:], None if redirected else directory


def positional_arguments(args: list[str]) -> list[str]:
    found: list[str] = []
    value_expected = False
    for argument in args:
        if value_expected:
            value_expected = False
        elif argument.startswith("-"):
            value_expected = argument in PUSH_VALUE_OPTIONS
        else:
            found.append(argument)
    return found


def source_branch(directory: Path | None, known: str | None) -> str:
    """La branche dont dépend le push ; refuse le push quand elle ne peut pas être établie.

    `known` est la branche que la commande elle-même fixe avant le push ("" si elle devient inconnue),
    None quand le dossier fait foi.
    """
    branch = known if known is not None else current_branch(directory) if directory else ""
    if not branch:
        permission("deny", "Push refusé : la branche du dossier où il s'exécute ne peut pas être établie. "
                           "Nommer la branche de destination (`git push origin <branche>`).")
    return branch


def check_destinations_are_read(remote: list[str], refspecs: list[str]) -> None:
    named = [spec.split(":", 1)[-1] for spec in refspecs] or remote  # seule, la variable porte tout
    if any(UNKNOWN in name for name in named):
        permission("deny", "Push refusé : sa destination dépend d'une variable ou d'une expression PowerShell. "
                           "Écrire la branche (`git push origin <branche>`) ou `git push origin HEAD`.")


def check_git_push(args: list[str], directory: Path | None, known: str | None) -> None:
    args = without_redirections(args)
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
    positional = positional_arguments(args)
    refspecs = [spec.lstrip("+") for spec in positional[1:]]
    check_destinations_are_read(positional[:1], refspecs)
    if any(a in ("--all", "--mirror", "--branches") for a in args) or ":" in refspecs:
        permission("deny", "Push de toutes les branches refusé : il inclut main. Nommer la branche thématique.")
    for spec in refspecs:
        destination = spec.split(":", 1)[1] if ":" in spec else spec
        if destination in ("HEAD", "@") or "$" in destination or "`" in destination:
            destination = source_branch(directory, known)
        short = destination.removeprefix("refs/").removeprefix("heads/")
        if short in PROTECTED_BRANCHES:
            permission("deny", f"Push direct vers {short} refusé : passer par une branche thématique et une PR "
                               "(ruleset « Protect main »).")
    if not refspecs and "--tags" not in args:
        branch = source_branch(directory, known)
        if branch in PROTECTED_BRANCHES:
            permission("deny", f"La branche courante est {branch} : pousser depuis une branche thématique "
                               "(docs/, ci/, fix/, feat/, refactor/, build/, sec/, chore/) puis ouvrir une PR.")


def check_git(sub: str, rest: list[str], words: list[str], directory: Path | None, known: str | None) -> None:
    if UNKNOWN in sub:
        permission("ask", "La sous-commande de git est une variable ou un splat que le garde ne lit pas. Confirmer.")
    elif sub == "push":
        check_git_push(rest, directory, known)
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


def new_branch(words: list[str], sub: str, rest: list[str]) -> str | None:
    """None si la commande garde sa branche, le nom de la branche qu'elle crée, "" si la suite est inconnue."""
    if words[:3] == ["gh", "pr", "checkout"]:
        return ""
    if words[:3] == ["gh", "pr", "merge"]:
        return "" if "--delete-branch" in words or "-d" in words else None
    if sub not in NEW_BRANCH_OPTIONS or "--" in rest[:-1]:  # `checkout -- fichier` ne change pas de branche
        return None
    for option, name in zip(rest, rest[1:]):
        if option in NEW_BRANCH_OPTIONS[sub] and not any(c in name for c in ("$", "`", UNKNOWN)):
            return name
    return ""


def check_command(command: str, cwd: str, powershell: bool) -> None:
    """Contrôle chaque commande simple en suivant le dossier et la branche dans lesquels elle s'exécutera."""
    directory = Path(cwd) if cwd and os.path.isdir(cwd) else None  # chemin littéral, sans expansion de shell
    redirected = False
    created: str | None = None  # branche fixée par la commande ("" : inconnue)
    created_in: Path | None = None
    at_head = True
    msys = not powershell  # `/n/depot` désigne n:/depot en Git Bash seulement
    commands = powershell_commands(command) if powershell else bash_commands(command)
    for words, separator, grouped, redirects_git in commands:
        at_head = at_head and not grouped
        redirected = redirected or redirects_git
        if words[:1] in (["cd"], ["pushd"], ["popd"]):
            followed = at_head and words[0] == "cd" and separator in ("&&", ";", "\n", "")
            directory = changed_directory(directory, words[1:], msys) if followed else None
        elif words and not words[0].startswith("#"):
            at_head = False
            sub, rest, target = "", [], directory
            if words[0] == "git":
                sub, rest, target = parse_git(words, directory, msys)
                target = None if redirected else target
                known = created if created == "" or (target and target == created_in) else None
                check_git(sub, rest, words, target, known)
            check_gh(words)
            branch = new_branch(words, sub, rest)
            if branch is not None:
                created, created_in = ("" if created == "" else branch), target
        if created and separator != "&&":  # sans `&&`, la suite s'exécute même si la création a échoué
            created = ""


def main() -> None:
    event = read_event()
    command = (event.get("tool_input") or {}).get("command") or ""
    check_command(command, event.get("cwd") or "", event.get("tool_name") == "PowerShell")


if __name__ == "__main__":
    main()
