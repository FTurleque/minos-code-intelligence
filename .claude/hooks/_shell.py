"""Lecture d'une ligne de commande Bash ou PowerShell pour les gardes : commandes simples et séparateurs.

En Bash, les guillemets, les commentaires et le corps des heredocs de message (`cat`, `git commit`) ne sont pas
des commandes. Quand la lecture n'est pas sûre (guillemet non fermé, autre heredoc), le découpage est littéral :
chaque séparateur coupe, quitte à voir une commande là où il n'y a que du texte.

En PowerShell, `\\` est un caractère ordinaire et l'accent grave échappe. Le contenu des `( )`, `{ }`, `$( )`,
`@( )` et `@{ }` est lu comme des commandes, avant celle qui les porte ; les chaînes, les here-strings et les
commentaires n'en sont pas, sauf les `$( )` d'une chaîne entre guillemets doubles et le corps d'une here-string
envoyé à un interpréteur. Une variable ou une expression vaut `UNKNOWN` : le garde ne l'évalue pas. Une lecture
impossible (chaîne ou groupe non fermé, imbrication trop profonde) retombe sur le découpage littéral.
"""
from __future__ import annotations

import re
import shlex
from typing import NamedTuple

LITERAL_SEPARATORS = re.compile(r"[ \t]*(&&|\|\||;|\||\n)\s*")
HEREDOC = re.compile(r"""(?<!<)<<-?[ \t]*\\?(['"]?)([A-Za-z_]\w*)\1""")
MESSAGE_HEREDOC = re.compile(r"\b(?:cat|git\s+commit)\b[^;&|]*<<")
QUOTED = re.compile(r"""'[^']*'|"(?:\\.|[^"\\])*\"""")
KEYWORDS = ("if", "then", "elif", "else", "while", "until", "do", "!", "time")
WRAPPERS = ("rtk", "proxy", "sudo", "command", "env")
DURATION = re.compile(r"^\d+(?:\.\d+)?[smhd]?$")
GIT_REDIRECTED = re.compile(r"^GIT_(?:DIR|WORK_TREE)=")

UNKNOWN = "\x00"  # variable ou expression PowerShell, connue à l'exécution seulement
SINGLE_QUOTES = "'\u2018\u2019\u201a\u201b"  # PowerShell tient les guillemets typographiques pour des guillemets
DOUBLE_QUOTES = '"\u201c\u201d\u201e'
HERE_STRING = re.compile(f"@([{SINGLE_QUOTES}{DOUBLE_QUOTES}])[ \t]*\r?\n")
VARIABLE = re.compile(r"\$(\{[^}]*\}|(?:\w+:)?\w+|[?^$])")
SPLAT = re.compile(r"@[A-Za-z_]\w*")
REDIRECTION_OPERATOR = re.compile(r">>?(&\d)?|<")
INTERPRETER_PIPE = re.compile(r"[ \t]*\|\s*(?:iex|invoke-expression|bash|sh|pwsh|powershell|cmd|ssh)\b", re.IGNORECASE)
ASSIGNMENT_OPERATOR = re.compile(r"(?:[-+*/%]|\?\?)?=")
ASSIGNMENT_TARGET = re.compile(rf"^(?:\[(?:[^\[\]]|\[[^\[\]]*\])*\])*{UNKNOWN}")  # `$x`, `[int]$x`, `$h['k']`
GIT_VARIABLE = re.compile(r"^env(?::\\?|\.)GIT_(?:DIR|WORK_TREE)$", re.IGNORECASE)
ITEM_WRITERS = ("set-item", "si", "new-item", "ni", "set-content", "sc")
BLOCK_KEYWORDS = ("if", "elseif", "else", "foreach", "for", "while", "switch", "try", "catch", "finally", "do",
                  "function", "filter", "trap")
POWERSHELL_PREFIXES = (".", "return", "rtk", "proxy", "!", "-not")
LOCATION_CHANGES = ("set-location", "sl", "cd", "chdir", "push-location", "pushd")
UNFOLLOWED_CHANGES = ("pop-location", "popd", "cd..", "cd\\", "cd~")
PATH_PARAMETERS = ("-path", "-literalpath", "-lp", "-pspath")
CURRENT_BRANCH_QUERIES = ([["git", "branch", "--show-current"]], [["git", "rev-parse", "--abbrev-ref", "HEAD"]])


class SimpleCommand(NamedTuple):
    words: list[str]  # sans mots-clés ni enveloppes
    separator: str  # séparateur qui suit, "" pour la dernière
    grouped: bool  # dans un groupe ou un bloc : un changement de dossier n'y est pas suivi
    redirects_git: bool  # pose GIT_DIR ou GIT_WORK_TREE


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


def literal_split(command: str) -> list[tuple[str, str]]:
    parts = LITERAL_SEPARATORS.split(command) + [""]
    return list(zip(parts[::2], parts[1::2]))


def segments(command: str) -> list[tuple[str, str]]:
    """Commandes simples, chacune avec le séparateur qui la suit ("" pour la dernière)."""
    command = command.replace("\\\n", " ").strip()
    command, other_heredoc = without_message_heredocs(command)
    unquoted = QUOTED.sub("", command)
    if command.startswith("(") and command.endswith(")") and unquoted.count("(") == unquoted.count(")") == 1:
        command = command[1:-1]  # toute la commande dans un seul sous-shell
    parts = None if other_heredoc else quoted_split(command)
    if parts is None:
        parts = literal_split(command)
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


def bash_commands(command: str) -> list[SimpleCommand]:
    found: list[SimpleCommand] = []
    for segment, separator in segments(command):
        raw = tokens(segment)
        words = strip_wrappers(raw)
        grouped = segment.startswith(("(", "{")) or (raw[:1] != [] and raw[0] in KEYWORDS)
        prefix = raw if words[:1] == ["export"] else raw[:len(raw) - len(words)]
        redirects = any(GIT_REDIRECTED.match(word) for word in prefix)
        found.append(SimpleCommand(words, separator, grouped, redirects))
    return found


class Quoted(str):
    """Mot PowerShell qui commence par un guillemet : une valeur, pas le nom d'une commande."""


class Unreadable(Exception):
    """Chaîne ou groupe PowerShell non fermé."""


class Statement:
    """Les mots de l'instruction PowerShell en cours de lecture."""

    def __init__(self) -> None:
        self.words: list[str] = []
        self.word: str | None = None
        self.quoted = False
        self.redirection_target = False

    def add(self, text: str, quoted: bool = False) -> None:
        if self.word is None:
            self.word, self.quoted = "", quoted
        self.word += text

    def end_word(self) -> None:
        if self.word is None:
            return
        if not self.redirection_target:
            self.words.append(Quoted(self.word) if self.quoted else self.word)
        self.word, self.redirection_target = None, False

    def first_word(self) -> str:
        return self.words[0] if self.words else self.word or ""

    def holds_targets_only(self) -> bool:
        """Vrai quand l'instruction ne porte encore que des variables : un `=` les affecte."""
        read = self.words + ([self.word] if self.word is not None else [])
        return bool(read) and all(ASSIGNMENT_TARGET.match(word) for word in read)


class PowerShellReader:
    """Découpe un script PowerShell en instructions `(mots, séparateur, dans un groupe)`."""

    def __init__(self, text: str) -> None:
        self.text = text
        self.index = 0
        self.statements: list[tuple[list[str], str, bool]] = []

    def read(self) -> list[tuple[list[str], str, bool]]:
        self.block("", False)
        return self.statements

    def block(self, closer: str, grouped: bool) -> None:
        statement = Statement()
        while self.index < len(self.text):
            character = self.text[self.index]
            if character == closer:
                self.index += 1
                self.end(statement, "", grouped)
                return
            if character in ")}":
                raise Unreadable
            if not (self.separator(statement, grouped) or self.comment(statement) or self.redirection(statement)):
                self.part(statement, grouped)
        if closer:
            raise Unreadable
        self.end(statement, "", grouped)

    def end(self, statement: Statement, separator: str, grouped: bool) -> None:
        statement.end_word()
        if statement.words:
            self.statements.append((statement.words, separator, grouped))
        statement.words, statement.redirection_target = [], False

    def separator(self, statement: Statement, grouped: bool) -> bool:
        character = self.text[self.index]
        pair = self.text[self.index:self.index + 2]
        separator = pair if pair in ("&&", "||") else character if character in ";\n|&" else ""
        if not separator:
            return False
        if separator != "&" or statement.words or statement.word is not None:  # `&` en tête appelle
            self.end(statement, separator, grouped)
        self.index += len(separator)
        return True

    def comment(self, statement: Statement) -> bool:
        if statement.word is not None:
            return False
        if self.text.startswith("<#", self.index):
            end = self.text.find("#>", self.index + 2)
            self.index = len(self.text) if end < 0 else end + 2
        elif self.text[self.index] == "#":
            end = self.text.find("\n", self.index)
            self.index = len(self.text) if end < 0 else end
        else:
            return False
        return True

    def redirection(self, statement: Statement) -> bool:
        found = REDIRECTION_OPERATOR.match(self.text, self.index)
        if not found:
            return False
        if statement.word is not None and (statement.word.isdigit() or statement.word == "*"):
            statement.word = None  # numéro du flux redirigé
        statement.end_word()
        statement.redirection_target = not found.group(1)
        self.index = found.end()
        return True

    def part(self, statement: Statement, grouped: bool) -> None:
        """Un espace, ou le prochain morceau du mot en cours."""
        character = self.text[self.index]
        following = self.text[self.index + 1:self.index + 2]
        here_string = HERE_STRING.match(self.text, self.index)
        splat = SPLAT.match(self.text, self.index) if statement.word is None else None
        operator = ASSIGNMENT_OPERATOR.match(self.text, self.index)
        if character in " \t\r":
            statement.end_word()
            self.index += 1
        elif operator and statement.holds_targets_only():
            statement.end_word()
            statement.words.append(operator.group())
            self.index = operator.end()
        elif character == "`":
            self.escape(statement)
        elif here_string:
            statement.add(self.here_string(here_string), quoted=True)
        elif character in SINGLE_QUOTES + DOUBLE_QUOTES:
            statement.add(self.string(), quoted=True)
        elif character == "$":
            statement.add(self.dollar())
        elif character in "({" or (character == "@" and following in ("(", "{")):
            self.nested(statement, grouped)
        elif splat:
            statement.words += [UNKNOWN, UNKNOWN]  # un splat déploie un nombre inconnu d'arguments
            self.index = splat.end()
        else:
            statement.add(character)
            self.index += 1

    def escape(self, statement: Statement) -> None:
        following = self.text[self.index + 1:self.index + 2]
        if following in ("\r", "\n"):  # l'instruction continue à la ligne suivante
            statement.end_word()
        else:
            statement.add(escaped(following))
        self.index += 2

    def nested(self, statement: Statement, grouped: bool) -> None:
        self.index += 2 if self.text[self.index] == "@" else 1
        closer = ")" if self.text[self.index - 1] == "(" else "}"
        statement.add(self.group(closer))
        keyword = re.match(r"[A-Za-z]*", statement.first_word()).group().lower()
        if closer == "}" and keyword in BLOCK_KEYWORDS:  # une autre instruction peut suivre sur la même ligne
            self.end(statement, "\n", grouped)

    def group(self, closer: str) -> str:
        """Lit un groupe jusqu'à `closer` et rend ce qu'il vaut comme argument."""
        before = len(self.statements)
        self.block(closer, True)
        inner = self.statements[before:]
        if closer == "}" or not inner:
            return UNKNOWN
        words, _, grouped = inner[-1]
        self.statements[-1] = (words, "&&", grouped)  # une expression ne rompt pas la chaîne qui la porte
        return "HEAD" if [item[0] for item in inner] in CURRENT_BRANCH_QUERIES else UNKNOWN

    def dollar(self) -> str:
        if self.text.startswith("$(", self.index):
            self.index += 2
            return self.group(")")
        found = VARIABLE.match(self.text, self.index)
        if not found:
            self.index += 1
            return "$"
        self.index = found.end()
        return UNKNOWN + found.group(1).strip("{}").replace(":", ".")  # `:` sépare les refspecs

    def string(self) -> str:
        expands = self.text[self.index] in DOUBLE_QUOTES
        quotes = DOUBLE_QUOTES if expands else SINGLE_QUOTES
        self.index += 1
        value = ""
        while self.index < len(self.text):
            character = self.text[self.index]
            following = self.text[self.index + 1:self.index + 2]
            if character in quotes:
                if not following or following not in quotes:
                    self.index += 1
                    return value
                value += character
                self.index += 2
            elif expands:
                value += self.expanded()
            else:
                value += character
                self.index += 1
        raise Unreadable

    def expanded(self) -> str:
        """Le prochain morceau d'un texte qui développe variables, `$( )` et échappements."""
        character = self.text[self.index]
        if character == "$":
            return self.dollar()
        if character == "`":
            self.index += 2
            return escaped(self.text[self.index - 1:self.index])
        self.index += 1
        return character

    def here_string(self, opening: re.Match) -> str:
        expands = opening.group(1) in DOUBLE_QUOTES
        quotes = DOUBLE_QUOTES if expands else SINGLE_QUOTES
        closing = re.compile(f"\r?\n[{quotes}]@").search(self.text, opening.end() - 1)
        if not closing:
            raise Unreadable
        body = self.text[opening.end():closing.start()]
        value = ""
        self.index = opening.end()
        while expands and self.index < closing.start():
            value += self.expanded()
        self.index = closing.end()
        if INTERPRETER_PIPE.match(self.text, self.index):  # le texte sera exécuté
            self.statements += [(words, separator, True) for words, separator, _ in PowerShellReader(body).read()]
        return value if expands else body


def escaped(character: str) -> str:
    return {"n": "\n", "r": "\r", "t": "\t"}.get(character, character)


def location_target(arguments: list[str]) -> str | None:
    """Le dossier nommé par les arguments de `Set-Location`, ou None quand il n'est pas lisible."""
    kept: list[str] = []
    index = 0
    while index < len(arguments):
        option = arguments[index].lower()
        if option in ("-erroraction", "-ea"):
            index += 2
            continue
        if option != "-passthru" and not option.startswith(("-erroraction:", "-ea:")):
            kept.append(arguments[index])
        index += 1
    if len(kept) == 2 and kept[0].lower() in PATH_PARAMETERS:
        kept = kept[1:]
    return kept[0] if len(kept) == 1 and not kept[0].startswith("-") else None


def without_assignment(words: list[str]) -> tuple[list[str], bool]:
    """La commande qui suit `$x =` ou `$x in` ([] pour une simple valeur), et si `$x` redirige git."""
    count = 0
    while count < len(words) and ASSIGNMENT_TARGET.match(words[count]):
        count += 1
    if not count or count == len(words) or not (words[count] == "in" or ASSIGNMENT_OPERATOR.fullmatch(words[count])):
        return words, False
    value = words[count + 1:]
    literal = len(value) == 1 and (isinstance(value[0], Quoted) or value[0].startswith(UNKNOWN) or value[0].isdigit())
    redirects = any(GIT_VARIABLE.match(word.split(UNKNOWN)[-1]) for word in words[:count])
    return [] if literal else value, redirects


def powershell_command(words: list[str], separator: str, grouped: bool) -> SimpleCommand:
    """L'instruction ramenée au vocabulaire du garde : `cd`, `popd`, `git`, `gh`."""
    words, redirects = without_assignment([word for word in words if word != "--%"])
    while words and words[0].lower() in POWERSHELL_PREFIXES:
        words = words[1:]
    if not words:
        return SimpleCommand([], separator, grouped, redirects)
    name = words[0].lower()
    if name in ITEM_WRITERS:
        redirects = redirects or any(GIT_VARIABLE.match(word) for word in words[1:])
    if name in LOCATION_CHANGES:
        target = location_target(words[1:])
        words = ["popd"] if target is None else ["cd", target]
    elif name in UNFOLLOWED_CHANGES or re.fullmatch(r"[a-z]:", name):
        words = ["popd"]
    elif UNKNOWN in words[0] and ["push"] in (words[1:2], words[3:4] if words[1:2] == ["-C"] else []):
        words = ["git", *words[1:]]  # `& $git push` : le programme est jugé comme git
    else:
        program = re.split(r"[\\/]", name)[-1].removesuffix(".exe")
        if program in ("git", "gh"):
            words = [program, *words[1:]]
    return SimpleCommand(words, separator, grouped, redirects)


def powershell_commands(command: str) -> list[SimpleCommand]:
    command = command.replace("\r\n", "\n")  # `` en fin de ligne continue aussi en CRLF
    try:
        statements = PowerShellReader(command).read()
    except (Unreadable, RecursionError):
        statements = [(part.split(), separator, True) for part, separator in literal_split(command) if part.strip()]
    return [powershell_command(*statement) for statement in statements]
