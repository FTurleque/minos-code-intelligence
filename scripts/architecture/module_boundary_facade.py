"""A11 reading of the MinosApplication facade for check-module-boundaries.py: its public accessors, signatures,
fields, nested types and wildcard imports, read from its source, and the surfaces reaching its composition root.

This module is not a gate: check-module-boundaries.py imports it and runs its rules; its self-tests are
the test_check_module_boundaries*.py files.
"""

from __future__ import annotations

import re
from functools import partial
from pathlib import Path
from typing import NamedTuple

from module_boundary_policy import (
    APPLICATION_MODULE, ENGINE_MODULE, ENGINE_TYPE_RATCHETED_MODULES, ROOT, fail, repository_group_id)
from module_boundary_sources import (
    ANNOTATION_NAME, COMMENTS_AND_LITERALS, QUALIFIER_GAP, SOURCE_IMPORT, annotation_end, call_argument_count,
    literals_as_tokens, production_types, qualified_name_pattern, read_java, resolve_adapter_class)


# A11 / AUD-ARC-01, AUD-ARC-02, ADR 0057 point 6, ADR 0087 section 2 — the public instance accessors of MinosApplication
# whose return type names a minos-engine type, with that type (as minos_application_accessors reads them). Empty, and a
# ratchet at zero: the ports and the composition root are package-private, read by the classes of com.minos.application;
# the surfaces go through the services and use cases of the facade, and the tests of the other modules through
# MinosApplicationTestAccess (test-jar of minos-application, test scope only). An entry, or a public accessor returning
# an engine type, fails. The parameters are held at zero without a table (minos_application_engine_parameters): no
# public method or constructor of the class body takes an engine type; the nested Builder, which customises the
# composition, is out of the rule. The static returns (minos_application_static_engine_returns) and the wildcard imports
# reaching the engine (minos_application_engine_wildcard_imports) are held at zero without a table too. Generic
# members are read, bounds included, and neither annotations, the case of a name nor line breaks hide a member. No
# public field is typed with an engine type or the composition root (minos_application_engine_fields), and the nested
# Builder is the only public nested type (minos_application_public_nested_types). The services and use cases the facade
# hands out are held on the surface side.
MINOS_APPLICATION_ENGINE_ACCESSORS: dict[str, str] = {}


# No space is required after a modifier: `public<T>` is legal Java.
PUBLIC_MODIFIERS = re.compile(r"\bpublic\b\s*((?:(?:static|final|synchronized|abstract|default)\b\s*)*)")
JAVA_IDENTIFIER = r"(?:[^\W\d]|\$)[\w$]*"
# A member header runs from the modifiers (and the type parameter section, if any) to its first such character: '(' for
# a method or a constructor, ';' or '=' for a field. The header itself is not matched against a list of the characters
# a type may hold.
HEADER_END = re.compile(r"[(;={}]")
MEMBER_NAME = re.compile(r"(?<![\w$])" + JAVA_IDENTIFIER + r"(?=\s*$)")
FIELD_NAME = re.compile(r"(?<![\w$])" + JAVA_IDENTIFIER + r"(?=(?:\s*\[\s*\])*\s*$)")
# `class`, `interface`, `enum` are keywords and `record` cannot name a type, so one of them followed by a name declares
# a nested type, whatever ends the header.
TYPE_DECLARATION = re.compile(r"(?<![\w$.])(?:class|interface|enum|record)\s+(" + JAVA_IDENTIFIER + r")")
# The public nested types MinosApplication may declare: the Builder customises the composition (A11).
FACADE_PUBLIC_NESTED_TYPES = frozenset({"Builder"})
RETURN_TYPE_WORD = re.compile(r"\b[A-Z]\w*(?:\.[A-Z]\w*)*")


class PublicMember(NamedTuple):
    """A public method or constructor: its type parameter section ('' without one, bounds included), its return type
    text (None for a constructor) and the index of the opening parenthesis of its parameters."""

    static: bool
    type_parameters: str
    returned: str | None
    name: str
    open_parenthesis: int

    def returned_types_text(self) -> str:
        """The text naming the types the method hands out: a bound counts as returned."""
        return f"{self.type_parameters} {self.returned}"


def type_parameters_end(code: str, start: int) -> int:
    """The index after the '>' closing the type parameter section that opens at start, nested bounds included; an
    unbalanced section fails the guard."""
    depth = 0
    for index in range(start, len(code)):
        character = code[index]
        if character == "<":
            depth += 1
        elif character == ">":
            depth -= 1
            if depth == 0:
                return index + 1
        elif character in "{};":
            break
    fail("A11 unbalanced MinosApplication type parameters")
    return start


class PublicDeclaration(NamedTuple):
    """A `public` declaration: its modifiers, type parameter section ('' without one), header text and the index of the
    character that ends the header."""

    static: bool
    type_parameters: str
    header: str
    end: int


def public_declarations(code: str):
    """The public declarations at the top level of the class body of code (as minos_application_code reads it): methods,
    constructors, fields and nested types. What nested types declare is excluded."""
    depth = 0
    position = 0
    for match in PUBLIC_MODIFIERS.finditer(code):
        depth += code.count("{", position, match.start()) - code.count("}", position, match.start())
        position = match.start()
        if depth != 1:
            continue
        start = match.end()
        end = type_parameters_end(code, start) if code.startswith("<", start) else start
        static = "static" in match.group(1).split()
        stopper = HEADER_END.search(code, end)
        if stopper is not None:
            yield PublicDeclaration(static, code[start:end], code[end:stopper.start()], stopper.start())


def unreadable_member(header: str) -> None:
    fail(f"A11 unreadable MinosApplication member: {' '.join(header.split())!r}")


def public_members(code: str, class_name: str):
    """The public methods (instance and static) and public constructors among public_declarations, generic or not. A
    header followed by '(' whose name cannot be read fails the guard."""
    for declared in public_declarations(code):
        if code[declared.end] != "(" or TYPE_DECLARATION.search(declared.header):
            continue
        name = MEMBER_NAME.search(declared.header)
        returned = declared.header[:name.start()].strip() if name else ""
        if returned:
            yield PublicMember(declared.static, declared.type_parameters, returned, name.group(), declared.end)
        elif name and name.group() == class_name:
            yield PublicMember(declared.static, declared.type_parameters, None, class_name, declared.end)
        else:
            unreadable_member(declared.header)


def first_declarator(header: str) -> str:
    """header up to its first comma outside type arguments: `Type name` of a field declaring several names."""
    depth = 0
    for index, character in enumerate(header):
        depth += {"<": 1, ">": -1}.get(character, 0)
        if character == "," and depth == 0:
            return header[:index]
    return header


def public_fields(code: str):
    """The public fields among public_declarations, as (first declared name, type text). A header that is neither a
    method, a nested type nor a readable field fails the guard."""
    for declared in public_declarations(code):
        if code[declared.end] == "(" or TYPE_DECLARATION.search(declared.header):
            continue
        declarator = first_declarator(declared.header)
        name = FIELD_NAME.search(declarator)
        type_text = declarator[:name.start()].strip() if name else ""
        if code[declared.end] not in ";=" or not type_text:
            unreadable_member(declared.header)
        yield name.group(), type_text


def parameter_declarations(code: str, open_parenthesis: int) -> list[str]:
    """The parameter declarations of the signature whose opening parenthesis is at the given index, split at the
    top-level commas (type arguments and annotation arguments kept together)."""
    depth = 0
    declarations = []
    current = ""
    for character in code[open_parenthesis + 1:]:
        if character in "(<[":
            depth += 1
        elif character in ")>]":
            if depth == 0 and character == ")":
                break
            depth -= 1
        elif character == "," and depth == 0:
            declarations.append(current)
            current = ""
            continue
        current += character
    else:
        fail("A11 unbalanced MinosApplication signature")
    declarations.append(current)
    return [declaration.strip() for declaration in declarations if declaration.strip()]


IDENTIFIER = re.compile(JAVA_IDENTIFIER)


def engine_types_named(text: str, imported: dict[str, str], engine: dict[str, str], qualified_name: re.Pattern[str],
                       group: str) -> list[str]:
    """The minos-engine types (relative to the groupId) a type text names: imported simple name or fully qualified
    name, type arguments included, a nested type counted as its top-level type."""
    candidates = qualified_name.findall(text)
    for word in RETURN_TYPE_WORD.findall(text):
        head, _, tail = word.partition(".")
        if head in imported:
            candidates.append(imported[head] + ("." + tail if tail else ""))
    return sorted({resolved.removeprefix(group + ".") for resolved in
                   (resolve_adapter_class(candidate, engine) for candidate in candidates) if resolved})


def minos_application_source(root: Path, group: str) -> Path:
    """The source of MinosApplication under root, for the groupId group."""
    return (root / APPLICATION_MODULE / "src" / "main" / "java" / Path(*group.split(".")) / "application"
            / "MinosApplication.java")


COMPOSITION_ROOT_TYPE = re.compile(r"\bMinosApplicationComposer\b")


def without_annotations(code: str) -> str:
    """code with each annotation, its arguments included, replaced by one space (an `@interface` declaration is kept);
    code has its literals as tokens, so every parenthesis counts. Unbalanced arguments fail the guard."""
    kept = []
    position = 0
    for match in ANNOTATION_NAME.finditer(code):
        if match.start() < position:  # an annotation inside the arguments of the previous one
            continue
        kept += [code[position:match.start()], " "]
        position = annotation_end(code, match, "A11 unbalanced MinosApplication annotation")
    return "".join(kept) + code[position:]


def minos_application_code(root: Path, group: str) -> str | None:
    """The MinosApplication source of root as the A11 readings take it: comments blanked, literals as tokens,
    annotations removed and the parts of a qualified name joined; None when it is missing."""
    source = minos_application_source(root, group)
    if not source.is_file():
        return None
    return QUALIFIER_GAP.sub(".", without_annotations(literals_as_tokens(read_java(source))))


def engine_type_reader(root: Path, group: str, code: str):
    """engine_types_named bound to the single imports of code: type text -> the minos-engine types it names. A static
    import brings a member type in scope too; a single-type import of the same simple name wins."""
    imported = {name.rsplit(".", 1)[-1]: name
                for static, name, wildcard in sorted(SOURCE_IMPORT.findall(code), key=lambda found: not found[0])
                if not wildcard}
    return partial(engine_types_named, imported=imported, engine=production_types(root, ENGINE_MODULE),
                   qualified_name=qualified_name_pattern(group), group=group)


def public_composition_root_methods(root: Path = ROOT, group_id: str | None = None) -> list[str]:
    """A11 / AUD-ARC-02 — the public methods (instance and static) of MinosApplication whose return type or type
    parameter bounds name MinosApplicationComposer, the composition root that hands out engine ports under an
    application type. Read from the source; empty when the source is missing (minos_application_accessors fails closed
    on it)."""
    code = minos_application_code(root, group_id or repository_group_id(root))
    if code is None:
        return []
    return sorted(member.name for member in public_members(code, "MinosApplication")
                  if member.returned is not None and COMPOSITION_ROOT_TYPE.search(member.returned_types_text()))


def minos_application_static_engine_returns(root: Path = ROOT,
                                            group_id: str | None = None) -> list[tuple[str, str]]:
    """A11 — the public static methods of MinosApplication whose return type or type parameter bounds name a
    minos-engine type: (method, type relative to the groupId). They are no accessor of the table, so none is allowed.
    Read from the source; empty when the source is missing (minos_application_accessors fails closed on it)."""
    group = group_id or repository_group_id(root)
    code = minos_application_code(root, group)
    if code is None:
        return []
    engine_types_in = engine_type_reader(root, group, code)
    return [(member.name, engine_type) for member in public_members(code, "MinosApplication")
            if member.static and member.returned is not None
            for engine_type in engine_types_in(member.returned_types_text())]


def minos_application_engine_fields(root: Path = ROOT, group_id: str | None = None) -> list[tuple[str, str]]:
    """A11 — the public fields of MinosApplication, static or not, whose declared type names a minos-engine type or
    MinosApplicationComposer: (field, type relative to the groupId). The initialiser is not read. Read from the source;
    empty when the source is missing (minos_application_accessors fails closed on it)."""
    group = group_id or repository_group_id(root)
    code = minos_application_code(root, group)
    if code is None:
        return []
    engine_types_in = engine_type_reader(root, group, code)
    found = []
    for name, type_text in public_fields(code):
        types = engine_types_in(type_text)
        if COMPOSITION_ROOT_TYPE.search(type_text):
            types.append("application.MinosApplicationComposer")
        found += [(name, field_type) for field_type in types]
    return found


def minos_application_public_nested_types(root: Path = ROOT, group_id: str | None = None) -> list[str]:
    """A11 — the public nested types of MinosApplication outside FACADE_PUBLIC_NESTED_TYPES, whatever they name: their
    members may be public without saying so. Read from the source; empty when the source is missing
    (minos_application_accessors fails closed on it)."""
    code = minos_application_code(root, group_id or repository_group_id(root))
    if code is None:
        return []
    nested = (TYPE_DECLARATION.search(declared.header) for declared in public_declarations(code))
    return sorted({found.group(1) for found in nested if found} - FACADE_PUBLIC_NESTED_TYPES)


def minos_application_engine_wildcard_imports(root: Path = ROOT, group_id: str | None = None) -> list[str]:
    """A11 — the targets MinosApplication imports by wildcard, static or not, that are a minos-engine package or a
    minos-engine type (nested types included): a simple name imported that way resolves to no type, so the other A11
    readings would not see it. Read from the source; empty when the source is missing (minos_application_accessors
    fails closed on it)."""
    code = minos_application_code(root, group_id or repository_group_id(root))
    if code is None:
        return []
    types = production_types(root, ENGINE_MODULE)
    packages = set(types.values())
    return sorted(name for _, name, wildcard in SOURCE_IMPORT.findall(code)
                  if wildcard and (name in packages or resolve_adapter_class(name, types)))


def minos_application_accessors(root: Path = ROOT, group_id: str | None = None) -> dict[str, tuple[int, str]]:
    """A11 — the public instance methods of MinosApplication, generic or not, whose return type or type parameter
    bounds name a minos-engine type: method name -> (parameter count, engine type relative to the groupId). Read from
    the source, never written in this guard. A type argument (Optional<T>, List<T>) counts; a nested type counts as its
    top-level type. The application source is required as soon as minos-application has production sources
    (fail-closed)."""
    group = group_id or repository_group_id(root)
    source_root = root / APPLICATION_MODULE / "src" / "main" / "java"
    code = minos_application_code(root, group)
    if code is None:
        if source_root.is_dir():
            source = minos_application_source(root, group)
            fail(f"A11 cannot read the accessors of MinosApplication: {source.relative_to(root).as_posix()} is "
                 "missing (the ratchet would stop counting the uses without a name)")
        return {}
    engine_types_in = engine_type_reader(root, group, code)
    accessors: dict[str, tuple[int, str]] = {}
    for member in public_members(code, "MinosApplication"):
        if member.static or member.returned is None:
            continue
        types = engine_types_in(member.returned_types_text())
        if len(types) > 1:
            fail(f"A11 accessor MinosApplication.{member.name} returns several minos-engine types "
                 f"({', '.join(types)}); the ratchet counts one type per accessor")
        if types:
            accessors[member.name] = (
                call_argument_count(code, member.open_parenthesis, "A11 unbalanced MinosApplication method"), types[0])
    return accessors


def minos_application_engine_parameters(root: Path = ROOT, group_id: str | None = None) -> list[tuple[str, str, str]]:
    """A11 / AUD-ARC-13 — the parameters of the public methods (instance and static) and public constructors, generic or
    not, declared at the top level of the MinosApplication class body whose type names a minos-engine type or
    MinosApplicationComposer: (method, parameter, type relative to the groupId). A type named in a type parameter bound
    counts for every parameter; a generic constructor without parameter reports it as '<type parameters>'. The nested
    Builder is out of the rule. Read from the source; empty when the source is missing (minos_application_accessors
    fails closed on it)."""
    group = group_id or repository_group_id(root)
    code = minos_application_code(root, group)
    if code is None:
        return []
    engine_types_in = engine_type_reader(root, group, code)
    found = []
    for member in public_members(code, "MinosApplication"):
        typed_parameters = []
        for declaration in parameter_declarations(code, member.open_parenthesis):
            words = IDENTIFIER.findall(declaration)
            if len(words) < 2:
                continue
            parameter = words[-1]
            typed_parameters.append((parameter, declaration.rsplit(parameter, 1)[0]))
        # A constructor hands out nothing: without a parameter, only this entry reads its bounds.
        if member.returned is None and member.type_parameters and not typed_parameters:
            typed_parameters.append(("<type parameters>", ""))
        for parameter, declared_type in typed_parameters:
            type_text = f"{member.type_parameters} {declared_type}"
            types = engine_types_in(type_text)
            if COMPOSITION_ROOT_TYPE.search(type_text):
                types.append("application.MinosApplicationComposer")
            found += [(member.name, parameter, engine_type) for engine_type in types]
    return found


COMPOSITION_ROOT_USE = re.compile(r"\bcompositionRoot\s*\(|::\s*compositionRoot\b|\bMinosApplicationComposers?\b")


def check_minos_application_accessors(root: Path = ROOT, group_id: str | None = None,
                                      listed: dict[str, str] | None = None) -> int:
    """A11 — the public MinosApplication accessors returning a minos-engine type are exactly those of
    MINOS_APPLICATION_ENGINE_ACCESSORS, each with its listed type; the table only shrinks. No public static method of
    MinosApplication returns a minos-engine type, no public method returns the composition root, and no surface
    production source reaches it, since its methods return engine ports. No public method or constructor of the class
    body of MinosApplication takes a minos-engine type or the composition root as a parameter (the nested Builder is
    out of the rule). Generic methods and constructors are read too, and a type named in a type parameter bound counts
    as returned and taken; a member is read whatever its annotations, the case of its name and its line breaks, and an
    unreadable header fails. A type brought in by a static import counts like an imported one. MinosApplication has no
    wildcard import, static or not, of a minos-engine package or type. No public field is typed with a minos-engine type
    or the composition root, and no nested type other than the Builder is public. Comments and literals are ignored."""
    listed = MINOS_APPLICATION_ENGINE_ACCESSORS if listed is None else listed
    accessors = minos_application_accessors(root, group_id)
    problems = []
    for name, (_, engine_type) in sorted(accessors.items()):
        if name not in listed:
            problems.append(f"MinosApplication.{name} is a new public accessor returning {engine_type}; expose an "
                            "application facade, or keep it package-private if only com.minos.application calls it")
        elif listed[name] != engine_type:
            problems.append(f"MinosApplication.{name} returns {engine_type}, listed as {listed[name]}")
    problems += [f"MINOS_APPLICATION_ENGINE_ACCESSORS entry {name} is no longer a public accessor returning an "
                 "engine type; remove it (the ratchet only shrinks)" for name in sorted(set(listed) - set(accessors))]
    problems += [f"MinosApplication.{name} is a public static method returning {engine_type}; keep it package-private "
                 "(tests reach it through MinosApplicationTestAccess)"
                 for name, engine_type in minos_application_static_engine_returns(root, group_id)]
    source = minos_application_source(root, group_id or repository_group_id(root))
    problems += [f"{source.relative_to(root).as_posix()} imports {target}.*, a minos-engine package or type; import "
                 "each type by name so that A11 reads it"
                 for target in minos_application_engine_wildcard_imports(root, group_id)]
    problems += [f"MinosApplication.{name} is a public method returning MinosApplicationComposer, the composition root; "
                 "keep it package-private (tests read it through MinosApplicationTestAccess)"
                 for name in public_composition_root_methods(root, group_id)]
    problems += [f"MinosApplication.{name} is public and takes parameter {parameter} of type {engine_type}; keep it "
                 "package-private (tests reach it through MinosApplicationTestAccess)"
                 for name, parameter, engine_type in minos_application_engine_parameters(root, group_id)]
    problems += [f"MinosApplication.{name} is a public field of type {field_type}; keep it package-private (tests reach "
                 "it through MinosApplicationTestAccess)"
                 for name, field_type in minos_application_engine_fields(root, group_id)]
    problems += [f"MinosApplication.{name} is a public nested type; only {', '.join(sorted(FACADE_PUBLIC_NESTED_TYPES))}"
                 " may be one, keep it package-private or declare it in its own file"
                 for name in minos_application_public_nested_types(root, group_id)]
    # The composition root hands out engine ports under an application type: a surface or a contract module would
    # bypass the table.
    for surface in sorted(ENGINE_TYPE_RATCHETED_MODULES):
        source_root = root / surface / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            if COMPOSITION_ROOT_USE.search(COMMENTS_AND_LITERALS.sub(" ", read_java(source))):
                problems.append(f"{source.relative_to(root).as_posix()} reaches the composition root "
                                "(compositionRoot() or MinosApplicationComposer); go through an application facade")
    if problems:
        fail("A11 MinosApplication engine accessors ratchet (ADR 0057 point 6): " + "; ".join(problems))
    return len(accessors)
