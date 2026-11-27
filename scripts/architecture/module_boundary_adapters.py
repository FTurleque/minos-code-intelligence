"""Adapter isolation rules of check-module-boundaries.py: A2 sources (no adapter class outside
the composition root), R1/R2 PostgreSQL backend isolation, A10 runtime-local exports, A20 assembly classes,
A19 embedded adapters and the storage package rules (SH-03).

This module is not a gate: check-module-boundaries.py imports it and runs its rules; its self-tests are
the test_check_module_boundaries*.py files.
"""

from __future__ import annotations

import re
from pathlib import Path

from module_boundary_policy import (
    ADAPTER_MODULES, APPLICATION_MODULE, ASSEMBLY_MODULE, BOOTSTRAP_MODULE, MODULES, POLICY, ROOT, fail,
    repository_group_id)
from module_boundary_sources import (
    production_sources,
    PACKAGE, SOURCE_IMPORT, SOURCE_WORD, name_reading_code, qualified_name_pattern, read_java, resolve_adapter_class)


def forbidden_names(source: Path, prefixes: tuple[str, ...], qualified: re.Pattern[str]) -> set[str]:
    """The names below one of the prefixes that a source imports (single, static, wildcard) or writes qualified."""
    code = name_reading_code(read_java(source), source)
    found: set[str] = set()
    for _static, name, wildcard in SOURCE_IMPORT.findall(code):
        if any(name == prefix or name.startswith(prefix + ".") for prefix in prefixes):
            found.add(name + (".*" if wildcard else ""))
    found.update(qualified.findall(SOURCE_IMPORT.sub(" ", code)))
    return found


def adapter_classes(root: Path = ROOT) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    """Top-level production classes of every adapter module: FQN -> module, package -> name -> module."""
    by_fqn: dict[str, str] = {}
    by_package: dict[str, dict[str, str]] = {}
    for module in sorted(ADAPTER_MODULES):
        source_root = root / module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            relative = source.relative_to(source_root).with_suffix("")
            package = ".".join(relative.parts[:-1])
            by_fqn[".".join(relative.parts)] = module
            by_package.setdefault(package, {})[relative.parts[-1]] = module
    return by_fqn, by_package


def check_source_boundaries(root: Path = ROOT, group_id: str | None = None) -> None:
    """A2 / ADR 0042 — the POM rules do not see sources.

    No production class outside the adapters and the composition root (minos-bootstrap) may name a
    concrete adapter class: not by import (single, static, wildcard of a package holding adapter
    classes or of the members of such a class), not by fully qualified name, not by simple name from a
    package it shares with an adapter.
    minos-app keeps its POM dependencies (it assembles the shaded JAR) but, like the application and the
    surfaces, reaches adapters only through ports wired by minos-bootstrap. Comments and string literals
    are ignored.
    """
    by_fqn, by_package = adapter_classes(root)
    qualified_name = qualified_name_pattern(group_id or repository_group_id(root))
    guarded = [module for module in MODULES if module not in ADAPTER_MODULES and module != BOOTSTRAP_MODULE]
    violations: list[str] = []
    for module, source in production_sources(root, guarded):
        text = read_java(source)
        code = name_reading_code(text, source)
        package_match = PACKAGE.search(text)
        package = package_match.group(1) if package_match else ""
        found: set[str] = set()
        for _static, name, wildcard in SOURCE_IMPORT.findall(code):
            if wildcard and name in by_package:
                found.add(name + ".*")
                continue
            # A wildcard on a class brings in its members: the class is named like by a single import.
            resolved = resolve_adapter_class(name, by_fqn)
            if resolved:
                found.add(resolved)
        body = SOURCE_IMPORT.sub(" ", code)
        for qualified in qualified_name.findall(body):
            resolved = resolve_adapter_class(qualified, by_fqn)
            if resolved:
                found.add(resolved)
        words = set(SOURCE_WORD.findall(body))
        for simple in by_package.get(package, {}):
            if simple in words:
                found.add(package + "." + simple)
        relative = source.relative_to(root).as_posix()
        for adapter_class in sorted(found):
            owner = by_fqn.get(adapter_class) or sorted(set(by_package[adapter_class[:-2]].values()))[0]
            violations.append(f"{relative} -> {adapter_class} [{owner}]")
    if violations:
        fail("A2 source boundary violated (a non-adapter module other than minos-bootstrap names a concrete "
             "adapter class; reach it through a port wired by minos-bootstrap): " + "; ".join(violations))


# R1 / R2 (E04, ADR 0068 section 4) — the PostgreSQL backend is optional in production: the composition root discovers
# it by ServiceLoader and the SCIP provider never needs it. Neither names a type of its package nor of its driver, so
# that sharing one storage module with the local backend gives them no compile-time path to PostgreSQL.
POSTGRESQL_BLIND_MODULES = {BOOTSTRAP_MODULE: "R1", "minos-provider-scip": "R2"}
POSTGRESQL_BACKEND_PACKAGE = "storage.postgresql"
POSTGRESQL_DRIVER_PACKAGE = "org.postgresql"


def check_postgresql_backend_isolation(root: Path = ROOT, group_id: str | None = None) -> int:
    """R1 / R2 — no production source of minos-bootstrap nor of minos-provider-scip names the PostgreSQL backend
    package or the PostgreSQL driver, by single, static or wildcard import or by qualified name; comments and string
    literals are ignored."""
    group = group_id or repository_group_id(root)
    prefixes = (f"{group}.{POSTGRESQL_BACKEND_PACKAGE}", POSTGRESQL_DRIVER_PACKAGE)
    qualified = re.compile(r"\b(?:" + "|".join(re.escape(prefix) for prefix in prefixes) + r")(?:\.\w+)+")
    violations: list[str] = []
    for module, rule in sorted(POSTGRESQL_BLIND_MODULES.items()):
        source_root = root / module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            found = forbidden_names(source, prefixes, qualified)
            relative = source.relative_to(root).as_posix()
            violations += [f"{rule} {relative} -> {name}" for name in sorted(found)]
    if violations:
        fail("R1/R2 PostgreSQL backend isolation violated (minos-bootstrap discovers the backend by ServiceLoader and "
             "minos-provider-scip never needs it): " + "; ".join(violations))
    return len(POSTGRESQL_BLIND_MODULES)


RUNTIME_LOCAL_MODULE = "minos-runtime-local"
RUNTIME_LOCAL_PACKAGE = "runtime.local"
RUNTIME_LOCAL_GUARDED_PACKAGES = ("acl", "process", "sandbox", "workspace")
# The only types of the guarded packages another module may name. The list only grows by a visible edit:
# every other public type there (icacls launcher and platform access control, process environment, sandbox plan
# transformer, workspace deletion, cgroup and Job Object ownership) is public for the sub-packages of the module or
# for ServiceLoader, not for its callers.
RUNTIME_LOCAL_EXPORTED_TYPES = frozenset({
    "process.BoundedProcessOutput",
    "process.CommandLocator",
    "process.IndexerProcessPlan",
    "process.IndexerProcessPlanFactory",
    "process.InstallationProcessEnvironment",
    "process.ProcessIndexerExecutor",
    "sandbox.WorkerResourceContainment",
    "sandbox.WorkerSandboxBackend",
    "sandbox.WorkerSandboxBackends",
    "sandbox.WorkerSandboxQualification",
    "sandbox.WorkerSandboxSelection",
    "workspace.FileResumableRunMarkers",
})
# Members of an exported type that replace the ownership boundary of a run: reserved to the module.
RUNTIME_LOCAL_RESERVED_MEMBERS = frozenset({"ProcessPlanTransformer", "executeSandboxed"})
ANY_WORD = re.compile(r"\b[A-Za-z_]\w*\b")


def guarded_runtime_local_type(name: str, prefix: str) -> tuple[str, str] | None:
    """(guarded package, 'package.Type') named by a qualified name under the guarded packages, if any."""
    if not name.startswith(prefix + "."):
        return None
    parts = name[len(prefix) + 1:].split(".")
    if parts[0] not in RUNTIME_LOCAL_GUARDED_PACKAGES:
        return None
    if len(parts) < 2 or not parts[1][:1].isupper():
        return parts[0], parts[0]
    return parts[0], parts[0] + "." + parts[1]


def check_runtime_local_exports(root: Path = ROOT, group_id: str | None = None,
                                exported: frozenset[str] = RUNTIME_LOCAL_EXPORTED_TYPES) -> int:
    """A10 — the sandbox, process and workspace primitives of minos-runtime-local are not a public API.

    Java has no module visibility without JPMS: a type shared by two sub-packages of the module is public for
    every module that depends on it. Outside minos-runtime-local, production and test sources may name only
    the exported types of its guarded packages (single or static import, fully qualified name); a wildcard
    import of a guarded package is refused, and so is any use of a reserved member. The list is a ratchet:
    every exported type must exist and must still be named by another module. Comments and string literals
    are ignored.
    """
    group = group_id or repository_group_id(root)
    prefix = group + "." + RUNTIME_LOCAL_PACKAGE
    qualified_name = qualified_name_pattern(group)
    base = root / RUNTIME_LOCAL_MODULE / "src" / "main" / "java" / Path(*prefix.split("."))
    missing = sorted(name for name in exported if not (base / Path(*name.split("."))).with_suffix(".java").is_file())
    if missing:
        fail("A10 runtime-local export list names a type that does not exist: " + ", ".join(missing))
    violations: list[str] = []
    used: set[str] = set()
    for module in MODULES:
        if module == RUNTIME_LOCAL_MODULE:
            continue
        for kind in ("main", "test"):
            source_root = root / module / "src" / kind / "java"
            if not source_root.is_dir():
                continue
            for source in sorted(source_root.rglob("*.java")):
                code = name_reading_code(read_java(source), source)
                found: set[str] = set()
                uses_runtime_local = False
                for _static, name, wildcard in SOURCE_IMPORT.findall(code):
                    uses_runtime_local = uses_runtime_local or name.startswith(prefix)
                    guarded = guarded_runtime_local_type(name, prefix)
                    if guarded is None:
                        continue
                    package, type_name = guarded
                    if type_name == package:
                        found.add(f"{prefix}.{package}.*" if wildcard else f"{prefix}.{package}")
                    elif type_name not in exported:
                        found.add(f"{prefix}.{type_name}")
                    else:
                        used.add(type_name)
                body = SOURCE_IMPORT.sub(" ", code)
                for qualified in qualified_name.findall(body):
                    uses_runtime_local = uses_runtime_local or qualified.startswith(prefix)
                    guarded = guarded_runtime_local_type(qualified, prefix)
                    if guarded is None:
                        continue
                    if guarded[1] in exported:
                        used.add(guarded[1])
                    else:
                        found.add(f"{prefix}.{guarded[1]}")
                if uses_runtime_local:
                    for member in sorted(RUNTIME_LOCAL_RESERVED_MEMBERS & set(ANY_WORD.findall(body))):
                        found.add(f"reserved member {member}")
                relative = source.relative_to(root).as_posix()
                violations.extend(f"{relative} -> {name}" for name in sorted(found))
    if violations:
        fail("A10 runtime-local export boundary violated (a module names a sandbox, process or workspace type of "
             "minos-runtime-local that is not in RUNTIME_LOCAL_EXPORTED_TYPES, or a reserved member; these types "
             "are public for the sub-packages of the module only): " + "; ".join(violations))
    obsolete = sorted(exported - used)
    if obsolete:
        fail("A10 runtime-local export list is a ratchet: no other module names " + ", ".join(obsolete)
             + " any more; remove the entry")
    return len(exported)


STORAGE_MODULE = "minos-storage"
LOCAL_BACKEND_PACKAGE = "storage.local"


# A20 (AUD-ARC-05) — the production classes of the final assembly minos-app, by name below com.minos, each with its
# role. A policy belongs to an application module or a surface; a new class enters this list by a visible edit.
ASSEMBLY_CLASSES: dict[str, str] = POLICY.assembly_classes


def check_assembly_classes(root: Path = ROOT, group_id: str | None = None,
                           listed: dict[str, str] | None = None) -> int:
    """A20 — every production class of minos-app is listed in ASSEMBLY_CLASSES with a written role, and every entry
    names a class of minos-app: the final assembly holds wiring, launch routes, transport and the home backend
    configuration, no application policy."""
    listed = ASSEMBLY_CLASSES if listed is None else listed
    prefix = (group_id or repository_group_id(root)) + "."
    source_root = root / ASSEMBLY_MODULE / "src" / "main" / "java"
    found: dict[str, str] = {}
    for source in sorted(source_root.rglob("*.java")) if source_root.is_dir() else []:
        if source.name in ("package-info.java", "module-info.java"):
            continue
        name = ".".join(source.relative_to(source_root).with_suffix("").parts).removeprefix(prefix)
        found[name] = source.relative_to(root).as_posix()
    problems = [f"{path} is a production class of {ASSEMBLY_MODULE} absent from ASSEMBLY_CLASSES ({name}); place a "
                f"policy in an application module or a surface, or list the class with its role"
                for name, path in sorted(found.items()) if name not in listed]
    problems += [f"ASSEMBLY_CLASSES entry {name} has no written role"
                 for name, role in sorted(listed.items()) if not str(role).strip()]
    problems += [f"ASSEMBLY_CLASSES entry {name} is no class of {ASSEMBLY_MODULE} any more; remove it"
                 for name in sorted(set(listed) - set(found))]
    if problems:
        fail("A20 classes of the final assembly (AUD-ARC-05: minos-app holds wiring, launch routes, transport and "
             "the home backend configuration): " + "; ".join(problems))
    return len(listed)


# A wildcard on one of these packages names it too: the types it brings in are written by their simple name only.
INFRASTRUCTURE_API = re.compile(
    r"\bjava\s*\.\s*net\s*\.\s*HttpURLConnection\b|\bjava\s*\.\s*net\s*\.\s*http\s*\.\s*(?:\w+|\*)"
    r"|\bjavax\s*\.\s*tools\s*\.\s*(?:\w+|\*)|\bcom\s*\.\s*sun\s*\.\s*source\s*\.\s*[\w.*]+")
# java.net also holds URI: its wildcard names the API only in a source that names the HTTP connection.
JAVA_NET_WILDCARD = re.compile(r"\bimport\s+java\.net\.\*")
HTTP_CONNECTION = "HttpURLConnection"
# A19 (ADR 0087) — the minos-application files that name a JDK infrastructure API, by name below com.minos. The ratchet
# only shrinks: a new HTTP client, compiler or source tree reader belongs to an adapter wired by minos-bootstrap.
INFRASTRUCTURE_API_USERS: frozenset[str] = POLICY.infrastructure_api_users
# A19 (ADR 0087) — the adapter classes minos-application embeds, each with its justification. No other production
# source of minos-application names them: the composition root instantiates them. The list only shrinks, and it holds
# the simple name of every INFRASTRUCTURE_API_USERS entry: an adapter entry leaves only once its class names no API.
EMBEDDED_ADAPTER_CLASSES: dict[str, str] = POLICY.embedded_adapter_classes


def check_embedded_adapters(root: Path = ROOT, group_id: str | None = None,
                            api_users: frozenset[str] | None = None,
                            adapters: dict[str, str] | None = None) -> tuple[int, int]:
    """A19 — in minos-application, only the INFRASTRUCTURE_API_USERS files name java.net.HttpURLConnection or a type of
    java.net.http, javax.tools or com.sun.source, and only the EMBEDDED_ADAPTER_CLASSES name one of those classes (by
    import, qualified name or simple name). Every INFRASTRUCTURE_API_USERS entry is listed, by its simple name, in
    EMBEDDED_ADAPTER_CLASSES. Comments and string literals are ignored; both tables only shrink."""
    api_users = INFRASTRUCTURE_API_USERS if api_users is None else api_users
    adapters = EMBEDDED_ADAPTER_CLASSES if adapters is None else adapters
    prefix = (group_id or repository_group_id(root)) + "."
    source_root = root / APPLICATION_MODULE / "src" / "main" / "java"
    sources: dict[str, tuple[str, str, str]] = {}
    for source in sorted(source_root.rglob("*.java")) if source_root.is_dir() else []:
        name = ".".join(source.relative_to(source_root).with_suffix("").parts).removeprefix(prefix)
        code = name_reading_code(read_java(source), source)
        sources[name] = (source.relative_to(root).as_posix(), source.stem, code)
    problems: list[str] = []
    using = {}
    for name, (path, _, code) in sorted(sources.items()):
        found = {re.sub(r"\s+", "", match.group(0)) for match in INFRASTRUCTURE_API.finditer(code)}
        if JAVA_NET_WILDCARD.search(code) and HTTP_CONNECTION in SOURCE_WORD.findall(code):
            found.add("java.net." + HTTP_CONNECTION)
        if found:
            using[name] = (path, sorted(found))
    problems += [f"{path} names {', '.join(found)} and is not in INFRASTRUCTURE_API_USERS ({name}); an "
                 f"infrastructure client belongs to an adapter wired by minos-bootstrap"
                 for name, (path, found) in sorted(using.items()) if name not in api_users]
    problems += [f"INFRASTRUCTURE_API_USERS entry {name} names no infrastructure API any more; remove it "
                 f"(the ratchet only shrinks)" for name in sorted(set(api_users) - set(using))]
    # The first table is included in the second: an infrastructure client stays an embedded adapter, so removing its
    # adapter entry while it still names the API cannot lift the protection. A stale entry is refused above.
    for name in sorted(set(api_users) & set(using)):
        simple = name.rsplit(".", 1)[-1]
        if simple not in adapters:
            problems.append(f"{simple} names {', '.join(using[name][1])} and is missing from EMBEDDED_ADAPTER_CLASSES; "
                            f"an infrastructure client is an embedded adapter")
    classes = {stem for _, stem, _ in sources.values()}
    problems += [f"EMBEDDED_ADAPTER_CLASSES entry {name} has no written justification"
                 for name, reason in sorted(adapters.items()) if not str(reason).strip()]
    problems += [f"EMBEDDED_ADAPTER_CLASSES entry {name} is no class of {APPLICATION_MODULE}; remove it "
                 f"(the list only shrinks)" for name in sorted(set(adapters) - classes)]
    for name, (path, stem, code) in sorted(sources.items()):
        if stem in adapters:
            continue
        named = sorted(set(adapters) & set(SOURCE_WORD.findall(code)))
        if named:
            problems.append(f"{path} names the embedded adapter class(es) {', '.join(named)}; ask the composition "
                            f"root (MinosApplicationComposer) for them")
    if problems:
        fail("A19 embedded adapters of minos-application (ADR 0087: minos-bootstrap instantiates them; the "
             "application names no infrastructure API outside the closed list): " + "; ".join(problems))
    return len(api_users), len(adapters)


# SH-03 (E04), ADR 0068 section 4 — inside minos-storage, production and test sources alike: the local and PostgreSQL
# backends do not name each other, and the shared packages (the snapshot codec and the MINOS_HOME path mapping) name
# neither backend, nor the PostgreSQL driver, nor JDBC. Each rule: (label, source package, forbidden prefixes); a prefix
# under the groupId is written without it.
STORAGE_PACKAGE_RULES: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("local -> postgresql", LOCAL_BACKEND_PACKAGE, (POSTGRESQL_BACKEND_PACKAGE, POSTGRESQL_DRIVER_PACKAGE)),
    ("postgresql -> local", POSTGRESQL_BACKEND_PACKAGE, (LOCAL_BACKEND_PACKAGE,)),
    ("codec -> backends", "storage.codec",
     (LOCAL_BACKEND_PACKAGE, POSTGRESQL_BACKEND_PACKAGE, POSTGRESQL_DRIVER_PACKAGE, "java.sql", "javax.sql")),
    ("home -> backends", "storage.home",
     (LOCAL_BACKEND_PACKAGE, POSTGRESQL_BACKEND_PACKAGE, POSTGRESQL_DRIVER_PACKAGE, "java.sql", "javax.sql")),
)
EXTERNAL_PACKAGE_PREFIXES = (POSTGRESQL_DRIVER_PACKAGE, "java.", "javax.")
# The closed list of the packages directly under com.minos.storage that minos-storage holds: a new sibling package would
# escape the rules above, so it is a decision written here by a visible edit.
STORAGE_PACKAGES = frozenset({"codec", "home", "local", "postgresql"})


def check_storage_package_isolation(root: Path = ROOT, group_id: str | None = None) -> int:
    """SH-03 — no production nor test source of a STORAGE_PACKAGE_RULES package of minos-storage names a forbidden
    package, by single, static or wildcard import or by qualified name; comments and string literals are ignored.
    AUD-ARC-12 — minos-storage holds only the STORAGE_PACKAGES below com.minos.storage, and no other module declares a
    package below it."""
    group = group_id or repository_group_id(root)
    violations: list[str] = []
    for kind in ("main", "test"):
        storage_root = root / STORAGE_MODULE / "src" / kind / "java" / Path(*f"{group}.storage".split("."))
        if storage_root.is_dir():
            for source in sorted(storage_root.rglob("*.java")):
                parts = source.relative_to(storage_root).parts
                if len(parts) == 1 or parts[0] not in STORAGE_PACKAGES:
                    violations.append(f"storage package outside {', '.join(sorted(STORAGE_PACKAGES))} "
                                      f"{source.relative_to(root).as_posix()}")
        # AUD-ARC-12, ADR 0068 section 5: a package below com.minos.storage always names minos-storage; the root package
        # itself holds the engine ports.
        for module in MODULES:
            other_root = root / module / "src" / kind / "java" / Path(*f"{group}.storage".split("."))
            if module != STORAGE_MODULE and other_root.is_dir():
                violations += [f"package below {group}.storage outside {STORAGE_MODULE} "
                               f"{source.relative_to(root).as_posix()}" for source in sorted(other_root.rglob("*.java"))
                               if len(source.relative_to(other_root).parts) > 1]
    for label, package, forbidden in STORAGE_PACKAGE_RULES:
        prefixes = tuple(prefix if prefix.startswith(EXTERNAL_PACKAGE_PREFIXES) else f"{group}.{prefix}"
                         for prefix in forbidden)
        qualified = re.compile(r"\b(?:" + "|".join(re.escape(prefix) for prefix in prefixes) + r")(?:\.\w+)+")
        for kind in ("main", "test"):
            package_root = root / STORAGE_MODULE / "src" / kind / "java" / Path(*f"{group}.{package}".split("."))
            if not package_root.is_dir():
                continue
            for source in sorted(package_root.rglob("*.java")):
                found = forbidden_names(source, prefixes, qualified)
                relative = source.relative_to(root).as_posix()
                violations += [f"{label} {relative} -> {name}" for name in sorted(found)]
    if violations:
        fail("Storage package isolation violated (ADR 0068 section 4: the backends meet only through the shared codec "
             "and path-mapping packages, which name neither backend nor JDBC): " + "; ".join(violations))
    return len(STORAGE_PACKAGE_RULES)
