#!/usr/bin/env python3
"""Fail when a production Java file mixes French and English comments, or a module's comment counters move up.

Policy: CONTRIBUTING.md ("Comment language") and AGENTS.md; change `fixer-la-langue-des-commentaires`
(AUD-QUA-11). The language of each module is declared in `scripts/quality/comment-language.json`.

Read: every `.java` file under `minos-*/src/main` (the IntelliJ plugin included), as UTF-8, after decoding the
`\\uXXXX` escapes. Only comments are read (`//`, blocks and Javadoc): string, character and text-block literals are
skipped by one lexical pass. In each comment, `{@code ...}`, `{@link ...}` and every other inline tag, the content
of `<code>` and `<pre>`, the remaining HTML tags, back-quoted and double-quoted segments, the block tag words and
the names after `@param`, `@throws` and `@exception`, and the whole lines of the `@see`, `@author`, `@since`,
`@serial` and `@version` tags are removed before classification. Words are split at apostrophes (`n'est`).

Criterion (deliberately coarse: it decides on mixing and counts files, it does not decide the language of a
sentence). A comment is split into paragraphs (blank comment line, `<p>`, a block tag; consecutive `//` lines
form one paragraph). In a paragraph, the French score is the number of words of FRENCH_WORDS plus the number of
words carrying a French accented letter; the English score is the number of words of ENGLISH_WORDS. A paragraph
is French when its French score is higher and is at least 2, or at least 1 with an accented word; English when
its English score is higher and is at least 2; otherwise it is not classified. A `§` or any other non-letter
sign never counts. A file is:

- *mixed* when it has at least one French and one English paragraph;
- *out of language* when it has at least one paragraph in the language its module does not declare;
- *milestone-citing* when one of its comments cites a milestone (`M` followed by digits as a whole word, such
  as `M17` or `M21-S6`).

The gate fails when: a module has no declared language, or a declared language is neither `fr` nor `en`, or a
declared module does not exist; a mixed file is not listed in `mixed` (the failure names the file and one line of
each language); a `mixed` entry matches no production file, several files (ambiguous name) or a file that is no
longer mixed; an entry has no written reason; per module, the number of out-of-language files or of
milestone-citing files differs from its maximum (above: a file was added; below: lower the maximum, the message
gives the value). Entries of `mixed` are file names, not paths: moving a file to another package or module leaves
the list untouched; moving it between modules moves one unit from a module's counters to the other's.

What it does not prove: that a comment without accent and without listed words is in the declared language, nor
anything about test sources, scripts and documentation, which follow AGENTS.md.

`--root` points the gate at another tree (default: this repository); `--config` at another declaration (default:
`scripts/quality/comment-language.json` under the root). `--inventory` prints, per module, the mixed,
out-of-language and milestone-citing files instead of checking. `--self-test` runs the gate on temporary trees.
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - after the script's directory is added to the path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
CONFIG = "scripts/quality/comment-language.json"
LABEL = "COMMENT LANGUAGE GATE"
# Nothing under src/main is build output: no directory is skipped.
SKIPPED_DIRECTORIES: frozenset[str] = frozenset()
LANGUAGES = ("en", "fr")

# Closed lists. A word that is common in both languages (`a`, `an`, `on`, `plus`, `via`, `type`, `plan`, `index`)
# is in neither.
FRENCH_WORDS = frozenset("""
    le la les des du un une est sont pour dans avec sans qui que quand lorsque ne pas sur par au aux ce cet cette
    ces son sa ses leur leurs mais donc si ou et chaque aucun aucune tout toute tous toutes fichier fichiers
    dossier dossiers sinon puis elle il ils elles nous vous être avoir doit peut fait déjà rend renvoie lit écrit
    selon entre comme même de encore jamais toujours ici où dont lui y
    """.split())
ENGLISH_WORDS = frozenset("""
    the is are was were be been of to and or in into for with without when while which that this these those
    it its if not no never only each every from by at must should will would can cannot could does do has have
    returns return reads writes than then there their they so but also any all one may might
    """.split())
FRENCH_ACCENTS = frozenset("àâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ")
WORD = re.compile(r"[^\W\d_]+")
MILESTONE = re.compile(r"(?<![A-Za-z0-9_])M[0-9]+(?![A-Za-z0-9_])")
UNICODE_ESCAPE = re.compile(r"(?<!\\)((?:\\\\)*)\\u+([0-9a-fA-F]{4})")
INLINE_TAG = re.compile(r"\{@[^{}]*\}")
CODE_ELEMENT = re.compile(r"<(code|pre)\b[^>]*>.*?</\1\s*>", re.DOTALL | re.IGNORECASE)
HTML_TAG = re.compile(r"</?[A-Za-z][^>]*>")
QUOTED = re.compile(r"`[^`\n]*`|\"[^\"\n]*\"")
DROPPED_TAG_LINE = re.compile(r"^@(see|author|since|serial|serialData|serialField|version)\b")
# A block tag word, and the parameter or exception name that follows `@param`, `@throws` and `@exception`.
NAMED_TAG = re.compile(r"^@(?:param|throws|exception)\s+\S+|^@[a-zA-Z]+")
PARAGRAPH_TAG = re.compile(r"^@[a-zA-Z]+")
PARAGRAPH_BREAK = re.compile(r"<p\b[^>]*>", re.IGNORECASE)


@dataclass
class Paragraph:
    line: int
    text: str


@dataclass
class FileReport:
    path: str
    module: str
    french: list[int] = field(default_factory=list)
    english: list[int] = field(default_factory=list)
    milestones: list[int] = field(default_factory=list)

    @property
    def mixed(self) -> bool:
        return bool(self.french) and bool(self.english)


def decode_unicode_escapes(text: str) -> str:
    """Decodes `\\uXXXX` as javac does (an escape preceded by an even number of backslashes)."""
    return UNICODE_ESCAPE.sub(lambda match: match.group(1) + chr(int(match.group(2), 16)), text)


def comments(source: str) -> list[tuple[int, str, bool]]:
    """(first line, raw text, is a line comment) of every comment, outside string, character and text-block literals."""
    found: list[tuple[int, str, bool]] = []
    index, line, length = 0, 1, len(source)
    while index < length:
        char = source[index]
        if char == "\n":
            line += 1
            index += 1
        elif source.startswith("//", index):
            end = source.find("\n", index)
            end = length if end < 0 else end
            found.append((line, source[index + 2:end], True))
            index = end
        elif source.startswith("/*", index):
            end = source.find("*/", index + 2)
            end = length if end < 0 else end
            found.append((line, source[index + 2:end], False))
            line += source.count("\n", index, end)
            index = end + 2
        elif source.startswith('"""', index):
            end = index + 3
            while end < length and not source.startswith('"""', end):
                end += 2 if source[end] == "\\" else 1
            line += source.count("\n", index, end)
            index = end + 3
        elif char in "\"'":
            end = index + 1
            while end < length and source[end] not in (char, "\n"):
                end += 2 if source[end] == "\\" else 1
            index = end + 1
        else:
            index += 1
    return found


def paragraphs(source: str) -> list[Paragraph]:
    """Paragraphs of the comments of a Java source, each with the line where it starts."""
    result: list[Paragraph] = []
    previous_line_comment = -2
    for first_line, raw, is_line_comment in comments(source):
        if is_line_comment:
            text = raw.lstrip("/").strip()
            if text and previous_line_comment == first_line - 1 and result:
                result[-1].text += " " + text
            elif text:
                result.append(Paragraph(first_line, text))
            previous_line_comment = first_line if text else -2
            continue
        previous_line_comment = -2
        current: Paragraph | None = None
        for offset, row in enumerate(raw.split("\n")):
            text = row.strip().lstrip("*").strip()
            pieces = PARAGRAPH_BREAK.split(text)
            for position, piece in enumerate(pieces):
                piece = piece.strip()
                starts = position > 0 or not piece or PARAGRAPH_TAG.match(piece) is not None
                if starts and current is not None:
                    result.append(current)
                    current = None
                if not piece or DROPPED_TAG_LINE.match(piece):
                    continue
                piece = NAMED_TAG.sub("", piece, count=1).strip()
                if current is None:
                    current = Paragraph(first_line + offset, piece)
                else:
                    current.text += " " + piece
        if current is not None:
            result.append(current)
    return result


def cleaned(text: str) -> str:
    """The prose of a paragraph: inline tags, code, HTML tags and quoted segments removed, entities decoded."""
    previous = None
    while previous != text:
        previous, text = text, INLINE_TAG.sub(" ", text)
    text = CODE_ELEMENT.sub(" ", text)
    text = HTML_TAG.sub(" ", text)
    text = html.unescape(text)
    return QUOTED.sub(" ", text)


def classify(text: str) -> str | None:
    """`fr`, `en` or None for one paragraph (see the module docstring)."""
    words = [word.lower() for word in WORD.findall(cleaned(text))]
    accented = sum(1 for word in words if any(char in FRENCH_ACCENTS for char in word))
    french = accented + sum(1 for word in words if word in FRENCH_WORDS and not any(c in FRENCH_ACCENTS for c in word))
    english = sum(1 for word in words if word in ENGLISH_WORDS)
    if french > english and (french >= 2 or accented >= 1):
        return "fr"
    if english > french and english >= 2:
        return "en"
    return None


def report_of(relative: str, module: str, source: str) -> FileReport:
    report = FileReport(relative, module)
    for paragraph in paragraphs(decode_unicode_escapes(source)):
        language = classify(paragraph.text)
        if language == "fr":
            report.french.append(paragraph.line)
        elif language == "en":
            report.english.append(paragraph.line)
        if MILESTONE.search(paragraph.text):
            report.milestones.append(paragraph.line)
    return report


def modules(root: Path) -> list[str]:
    """Every `minos-*` directory under `root` that has a `src/main/java` tree."""
    return sorted(path.name for path in root.glob("minos-*") if (path / "src" / "main" / "java").is_dir())


def scan(root: Path) -> tuple[list[FileReport], list[str]]:
    """(one report per production Java file, read failures)."""
    reports: list[FileReport] = []
    failures: list[str] = []
    for module in modules(root):
        main = root / module / "src" / "main"
        for path in gate_cli.repository_files(main, SKIPPED_DIRECTORIES):
            if path.suffix != ".java":
                continue
            relative = path.relative_to(root).as_posix()
            try:
                source = path.read_text(encoding="utf-8")
            except (OSError, UnicodeError) as error:
                failures.append(f"{relative} cannot be read as UTF-8 ({type(error).__name__}); the gate does not skip it")
                continue
            reports.append(report_of(relative, module, source))
    return reports, failures


def load_config(path: Path) -> tuple[dict, list[str]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as error:
        return {}, [f"{path.name} cannot be read as a JSON object ({type(error).__name__}); the gate cannot run"]
    if not isinstance(data, dict):
        return {}, [f"{path.name} is not a JSON object"]
    return data, []


def out_of_language(report: FileReport, language: str) -> bool:
    return bool(report.english) if language == "fr" else bool(report.french)


def check(root: Path, config_path: Path | None = None) -> tuple[list[str], list[str]]:
    """(failures, one summary line per module)."""
    config, failures = load_config(config_path or root / CONFIG)
    if failures:
        return failures, []
    languages = config.get("languages", {})
    maxima = {"outOfLanguageMaximum": config.get("outOfLanguageMaximum", {}),
              "milestoneCommentMaximum": config.get("milestoneCommentMaximum", {})}
    mixed_entries = config.get("mixed", {})
    present = modules(root)
    for module in present:
        if module not in languages:
            failures.append(f"module {module} has no declared comment language in {CONFIG} (`languages`)")
    for module, language in sorted(languages.items()):
        if module not in present:
            failures.append(f"`languages` declares {module}, which is not a module with src/main/java; remove it")
        if language not in LANGUAGES:
            failures.append(f"module {module} declares the language {language!r}; use one of {', '.join(LANGUAGES)}")
    for key, values in maxima.items():
        for module, value in sorted(values.items()):
            if module not in present:
                failures.append(f"`{key}` names {module}, which is not a module with src/main/java; remove it")
            elif not isinstance(value, int) or isinstance(value, bool) or value < 0:
                failures.append(f"`{key}` of {module} is {value!r}; a maximum is a non-negative integer")
    for name, reason in sorted(mixed_entries.items()):
        if not isinstance(reason, str) or not reason.strip():
            failures.append(f"`mixed` entry {name} needs a written reason")

    reports, read_failures = scan(root)
    failures.extend(read_failures)
    by_name: dict[str, list[FileReport]] = {}
    for report in reports:
        by_name.setdefault(report.path.rsplit("/", 1)[-1], []).append(report)
    for report in reports:
        name = report.path.rsplit("/", 1)[-1]
        if report.mixed and name not in mixed_entries:
            failures.append(f"{report.path} mixes French (line {report.french[0]}) and English (line "
                            f"{report.english[0]}) comments; write them in the module's language")
    for name in sorted(mixed_entries):
        matches = by_name.get(name, [])
        if not matches:
            failures.append(f"`mixed` entry {name} matches no production file; remove it (the list only shrinks)")
        elif len(matches) > 1:
            failures.append(f"`mixed` entry {name} matches {len(matches)} files ("
                            + ", ".join(report.path for report in matches) + "); the name is ambiguous")
        elif not matches[0].mixed:
            failures.append(f"`mixed` entry {name} ({matches[0].path}) is no longer mixed; remove the entry "
                            f"(the list only shrinks)")

    summary: list[str] = []
    for module in present:
        language = languages.get(module)
        if language not in LANGUAGES:
            continue
        mine = [report for report in reports if report.module == module]
        counts = {"outOfLanguageMaximum": sum(1 for report in mine if out_of_language(report, language)),
                  "milestoneCommentMaximum": sum(1 for report in mine if report.milestones)}
        for key, count in counts.items():
            maximum = maxima[key].get(module, 0)
            if key == "outOfLanguageMaximum":
                what, hint = "out-of-language file(s)", f"write new or changed comments in `{language}`"
            else:
                what, hint = "file(s) whose comments cite a milestone", "say what the code does, not which milestone"
            if count > maximum:
                failures.append(f"module {module} has {count} {what}, above its maximum {maximum} (`{key}`); {hint}")
            elif count < maximum:
                failures.append(f"module {module} has {count} {what}, below its maximum {maximum}; set `{key}` "
                                f"of {module} to {count} (the counter only goes down)")
        summary.append(f"{module}={language} files={len(mine)} out-of-language={counts['outOfLanguageMaximum']} "
                       f"milestone-citing={counts['milestoneCommentMaximum']} "
                       f"mixed={sum(1 for report in mine if report.mixed)}")
    return failures, summary


def inventory(root: Path) -> int:
    reports, failures = scan(root)
    for failure in failures:
        print(failure, file=sys.stderr)
    for module in modules(root):
        mine = [report for report in reports if report.module == module]
        french = [report for report in mine if report.french]
        print(f"{module}: files={len(mine)} with-french={len(french)} "
              f"english-only={sum(1 for r in mine if r.english and not r.french)} "
              f"mixed={sum(1 for r in mine if r.mixed)} milestone-citing={sum(1 for r in mine if r.milestones)}")
        for report in mine:
            if report.mixed:
                print(f"  mixed {report.path} fr={report.french} en={report.english}")
        for report in mine:
            if report.milestones:
                print(f"  milestone {report.path} lines={report.milestones}")
    return 1 if failures else 0


# --- self-test -------------------------------------------------------------------------------------------------

JAVA_MAIN = "minos-sample/src/main/java/com/example"
OTHER_MAIN = "minos-other/src/main/java/com/example"

ENGLISH = """package com.example;

/** Reads the index of a project and returns its symbols. */
final class Reader {
    // The cache is never shared with another project.
    int size() {
        return 0;
    }
}
"""
FRENCH = """package com.example;

/** Lit l'index du projet et rend ses symboles. */
final class Lecteur {
    // Le cache n'est jamais partagé entre deux projets.
    int taille() {
        return 0;
    }
}
"""
MIXED = """package com.example;

/** Reads the index of a project and returns its symbols. */
final class Mixed {
    // Rouvre le run interrompu à partir de son marqueur.
    int size() {
        return 0;
    }
}
"""
LITERALS = """package com.example;

/**
 * Bounds the arguments: a value such as {@code "é"} or {@literal ç} is counted in code points, and the
 * section mark § of ADR 0039 is not a word.
 * <pre>
 * Déjà vu
 * </pre>
 *
 * @param café the input, which is never empty
 * @author Gérard Ménard
 */
final class Bounds {
    static final String MESSAGE = "Projet introuvable : vérifiez le chemin";
    static final char E = 'é';
    static final String BLOCK = \"\"\"
            Fichier déjà présent
            \"\"\";
    // The message "Projet introuvable" is a public string, quoted in `détail`; it is never translated.
    int size() {
        return 0;
    }
}
"""
ESCAPED = """package com.example;

/** Reads the index of a project and returns its symbols. */
final class Escaped {
    // R\\u00e9cup\\u00e8re le marqueur du run interrompu.
    int size() {
        return 0;
    }
}
"""
TECHNICAL = """package com.example;

/**
 * Plugin index plan: type, via, plus, on, an, a.
 */
final class Technical {
    // SCIP index plan via Gradle, plus a type on an API.
    int size() {
        return 0;
    }
}
"""
MILESTONE_COMMENT = """package com.example;

/** Historical M17 policy: reads the index of the project and returns its symbols. */
final class Policy {
    // The arm64 build and the System32 directory are not milestones; neither is qualifiedM24Providers.
    int size() {
        return 0;
    }
}
"""


def write(root: Path, relative: str, content: str | bytes) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content if isinstance(content, bytes) else content.encode("utf-8"))


def declaration(languages: dict | None = None, out: dict | None = None, milestones: dict | None = None,
                mixed: dict | None = None) -> str:
    return json.dumps({"languages": {"minos-sample": "en"} if languages is None else languages,
                       "outOfLanguageMaximum": out or {}, "milestoneCommentMaximum": milestones or {},
                       "mixed": mixed or {}})


def run_case(files: dict[str, str | bytes], config: str) -> list[str]:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        for relative, content in files.items():
            write(root, relative, content)
        write(root, CONFIG, config)
        failures, _ = check(root)
        return failures


def expect(label: str, condition: bool, detail: object) -> list[str]:
    return [] if condition else [f"{label}: {detail}"]


def single(failures: list[str], *fragments: str) -> bool:
    return len(failures) == 1 and all(fragment in failures[0] for fragment in fragments)


def self_test() -> int:
    problems: list[str] = []
    english = {f"{JAVA_MAIN}/Reader.java": ENGLISH}
    mixed = {f"{JAVA_MAIN}/Mixed.java": MIXED}
    listed = {"Mixed.java": "kept until the run executor is split"}

    failures = run_case(english, declaration())
    problems += expect("an English file in an English module is accepted", failures == [], failures)

    failures = run_case(mixed, declaration(out={"minos-sample": 1}))
    problems += expect("an unlisted mixed file is refused with one line of each language",
                       single(failures, f"{JAVA_MAIN}/Mixed.java", "French (line 5)", "English (line 3)"), failures)

    failures = run_case(mixed, declaration(out={"minos-sample": 1}, mixed=listed))
    problems += expect("a listed mixed file is accepted", failures == [], failures)

    failures = run_case({f"{JAVA_MAIN}/deeper/Mixed.java": MIXED}, declaration(out={"minos-sample": 1}, mixed=listed))
    problems += expect("a listed file moved to another package is accepted", failures == [], failures)

    failures = run_case(english, declaration(mixed=listed))
    problems += expect("a dead entry is refused", single(failures, "Mixed.java", "matches no production file"),
                       failures)

    failures = run_case({**mixed, f"{OTHER_MAIN}/Mixed.java": MIXED},
                        declaration({"minos-sample": "en", "minos-other": "en"},
                                    {"minos-sample": 1, "minos-other": 1}, mixed=listed))
    problems += expect("an ambiguous entry is refused", single(failures, "Mixed.java", "matches 2 files"), failures)

    failures = run_case({f"{JAVA_MAIN}/Mixed.java": ENGLISH}, declaration(mixed=listed))
    problems += expect("a listed file that is no longer mixed is refused",
                       single(failures, "Mixed.java", "no longer mixed"), failures)

    failures = run_case(mixed, declaration(out={"minos-sample": 1}, mixed={"Mixed.java": " "}))
    problems += expect("an entry without a reason is refused", single(failures, "needs a written reason"), failures)

    failures = run_case({**english, f"{OTHER_MAIN}/Reader.java": ENGLISH}, declaration())
    problems += expect("an undeclared module is refused", single(failures, "module minos-other has no declared"),
                       failures)

    failures = run_case(english, declaration({"minos-sample": "en", "minos-gone": "en"}))
    problems += expect("a declared module that does not exist is refused",
                       single(failures, "declares minos-gone", "not a module"), failures)

    failures = run_case(english, declaration({"minos-sample": "de"}))
    problems += expect("an unknown language is refused", single(failures, "'de'"), failures)

    failures = run_case({f"{JAVA_MAIN}/Lecteur.java": FRENCH}, declaration())
    problems += expect("an out-of-language file above the maximum is refused with count and maximum",
                       single(failures, "minos-sample has 1 out-of-language", "above its maximum 0"), failures)

    failures = run_case({f"{JAVA_MAIN}/Lecteur.java": FRENCH}, declaration(out={"minos-sample": 1}))
    problems += expect("an out-of-language file within the maximum is accepted", failures == [], failures)

    failures = run_case({f"{JAVA_MAIN}/Lecteur.java": FRENCH}, declaration({"minos-sample": "fr"}))
    problems += expect("a French file in a French module is accepted", failures == [], failures)

    failures = run_case(english, declaration(out={"minos-sample": 2}))
    problems += expect("a maximum above the count is refused with the value to write",
                       single(failures, "below its maximum 2", "to 0"), failures)

    failures = run_case(english, declaration(out={"minos-sample": -1}))
    problems += expect("a negative maximum is refused", any("non-negative" in f for f in failures), failures)

    failures = run_case({f"{JAVA_MAIN}/Bounds.java": LITERALS}, declaration())
    problems += expect("accents in literals, text blocks, {@code}, <pre>, quotes, @param and @author, and the "
                       "§ sign, are ignored", failures == [], failures)

    failures = run_case({f"{JAVA_MAIN}/Escaped.java": ESCAPED}, declaration(out={"minos-sample": 1}))
    problems += expect("a French comment written with \\u escapes is decoded and refused as mixed",
                       single(failures, "Escaped.java mixes"), failures)

    failures = run_case({f"{JAVA_MAIN}/Technical.java": TECHNICAL}, declaration()) + run_case(
        {f"{JAVA_MAIN}/Technical.java": TECHNICAL}, declaration({"minos-sample": "fr"}))
    problems += expect("words common to both languages are classified neither French nor English", failures == [],
                       failures)

    failures = run_case({f"{JAVA_MAIN}/Policy.java": MILESTONE_COMMENT}, declaration())
    problems += expect("a comment citing a milestone above the maximum is refused",
                       single(failures, "1 file(s) whose comments cite a milestone", "above its maximum 0"), failures)

    failures = run_case({f"{JAVA_MAIN}/Policy.java": MILESTONE_COMMENT}, declaration(milestones={"minos-sample": 1}))
    problems += expect("a milestone-citing file within the maximum is accepted (arm64, System32 and "
                       "qualifiedM24Providers are not milestones)", failures == [], failures)

    failures = run_case(english, declaration(milestones={"minos-sample": 1}))
    problems += expect("a milestone maximum above the count is refused", single(failures, "set `milestoneCommentMaximum`"),
                       failures)

    failures = run_case({f"{JAVA_MAIN}/Broken.java": b"package com.example;\n// \xff\xfe\n"}, declaration())
    problems += expect("a file that cannot be read as UTF-8 is refused", single(failures, "cannot be read as UTF-8"),
                       failures)

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        write(root, f"{JAVA_MAIN}/Reader.java", ENGLISH)
        write(root, CONFIG, "{ not json")
        failures, _ = check(root)
    problems += expect("an unreadable declaration fails closed", single(failures, "cannot be read as a JSON object"),
                       failures)

    failures = run_case({"minos-sample/src/test/java/com/example/Mixed.java": MIXED,
                         "tools/src/main/java/com/example/Mixed.java": MIXED,
                         **english}, declaration())
    problems += expect("only src/main of minos-* modules is read", failures == [], failures)

    if gate_cli.report_failures(LABEL + " SELF-TEST", problems):
        return 1
    print(f"{LABEL} SELF-TEST SUCCESS (mixed, listed, moved, dead, ambiguous, no longer mixed, unreasoned, undeclared "
          f"and unknown modules, unknown language, counters above and below, literals and tags ignored, \\u escapes, "
          f"shared words, milestones, unreadable source and declaration, scope)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--inventory", action="store_true")
    arguments = parser.parse_args(argv)
    if arguments.self_test:
        return self_test()
    if arguments.inventory:
        return inventory(arguments.root)
    failures, summary = check(arguments.root, arguments.config)
    if gate_cli.report_failures(LABEL, failures):
        return 1
    print(f"{LABEL} SUCCESS (" + "; ".join(summary) + ")")
    return 0


if __name__ == "__main__":
    sys.exit(main())
