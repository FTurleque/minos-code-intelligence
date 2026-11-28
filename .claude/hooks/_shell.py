"""Lecture d'une ligne de commande Bash pour les gardes : commandes simples et séparateurs.

Les guillemets, les commentaires et le corps des heredocs de message (`cat`, `git commit`) ne sont pas des
commandes. Quand la lecture n'est pas sûre (guillemet non fermé, autre heredoc), le découpage est littéral :
chaque séparateur coupe, quitte à voir une commande là où il n'y a que du texte.
"""
from __future__ import annotations

import re
import shlex

LITERAL_SEPARATORS = re.compile(r"[ \t]*(&&|\|\||;|\||\n)\s*")
HEREDOC = re.compile(r"""(?<!<)<<-?[ \t]*\\?(['"]?)([A-Za-z_]\w*)\1""")
MESSAGE_HEREDOC = re.compile(r"\b(?:cat|git\s+commit)\b[^;&|]*<<")
QUOTED = re.compile(r"""'[^']*'|"(?:\\.|[^"\\])*\"""")
KEYWORDS = ("if", "then", "elif", "else", "while", "until", "do", "!", "time")
WRAPPERS = ("rtk", "proxy", "sudo", "command", "env")
DURATION = re.compile(r"^\d+(?:\.\d+)?[smhd]?$")


def without_message_heredocs(command: str) -> tuple[str, bool]:
    """La commande sans le corps des heredocs de message, et True s'il reste un heredoc d'une autre nature."""
    lines = command.split("\n")
    kept: list[str] = []
    other = False
    index = 0
    while index < len(lines):
        line = lines[index]
        kept.append(line)
        index += 1
        found = HEREDOC.search(line)
        if not found:
            continue
        end = next((i for i in range(index, len(lines)) if lines[i].strip() == found.group(2)), None)
        if end is None or not MESSAGE_HEREDOC.search(line) or "|" in line[found.end():]:
            other = True
        else:
            index = end + 1
    return "\n".join(kept), other


def quoted_split(command: str) -> list[tuple[str, str]] | None:
    """Découpage qui respecte guillemets et commentaires, ou None si un guillemet reste ouvert."""
    parts: list[tuple[str, str]] = []
    current = ""
    quote = ""
    index = 0
    while index < len(command):
        character = command[index]
        pair = command[index:index + 2]
        if character == "\\" and quote != "'":
            current += pair
            index += 2
            continue
        separator = ""
        if quote:
            quote = "" if character == quote else quote
        elif character in "'\"":
            quote = character
        elif character == "#" and current[-1:] in ("", " ", "\t"):
            end = command.find("\n", index)
            index = len(command) if end < 0 else end
            continue
        elif pair in ("&&", "||"):
            separator = pair
        elif character in ";\n|":
            separator = character
        elif character == "&" and current[-1:] not in ("<", ">") and pair != "&>":
            separator = "&"
        elif character in "<>" and current and not (current[-1] in " \t<>&" or current[-1].isdigit()):
            current += " "  # `main>journal` redirige : la destination est `main`
        if separator:
            parts.append((current, separator))
            current = ""
            index += len(separator)
        else:
            current += character
            index += 1
    parts.append((current, ""))
    return None if quote else parts


def segments(command: str) -> list[tuple[str, str]]:
    """Commandes simples, chacune avec le séparateur qui la suit ("" pour la dernière)."""
    command = command.replace("\\\n", " ").strip()
    command, other_heredoc = without_message_heredocs(command)
    unquoted = QUOTED.sub("", command)
    if command.startswith("(") and command.endswith(")") and unquoted.count("(") == unquoted.count(")") == 1:
        command = command[1:-1]  # toute la commande dans un seul sous-shell
    parts = None if other_heredoc else quoted_split(command)
    if parts is None:
        literal = LITERAL_SEPARATORS.split(command) + [""]
        parts = list(zip(literal[::2], literal[1::2]))
    return [(part.strip(), separator) for part, separator in parts if part.strip()]


def tokens(segment: str) -> list[str]:
    """Les mots d'une commande simple, sans les parenthèses ni accolades qui la groupent."""
    segment = re.sub(r"^[({\s]+|[)\s]+$", "", segment)
    try:
        return shlex.split(segment, posix=True)
    except ValueError:
        return segment.split()


def strip_wrappers(words: list[str]) -> list[str]:
    """Retire les mots-clés du shell et les enveloppes (`rtk`, `env VAR=x`, `timeout 60`…) avant la commande."""
    index = 0
    while index < len(words):
        word = words[index]
        if word in KEYWORDS or word in WRAPPERS or re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", word):
            index += 1
        elif word == "timeout":
            index += 1
            while index < len(words) and (words[index].startswith("-") or DURATION.match(words[index])):
                index += 1
        else:
            break
    return words[index:]
