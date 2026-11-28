"""Ratchet rules of check-module-boundaries.py: A11 minos-engine types named by surfaces, the
MinosApplication accessor calls included, A12 process launchers, A13 adapter types named by adapters, A14 package roots,
A16 ServiceLoader class loaders, A18 snapshot read port, and the measured share of concrete engine code.

This module is not a gate: check-module-boundaries.py imports it and runs its rules; its self-tests are
the test_check_module_boundaries*.py files. The A11 reading of the MinosApplication facade is module_boundary_facade.
"""

from __future__ import annotations

import re
from pathlib import Path

from module_boundary_facade import minos_application_accessors
from module_boundary_policy import (
    ADAPTER_MODULES, APPLICATION_MODULE, ENGINE_MODULE, ENGINE_TYPE_RATCHETED_MODULES, MODULES, POLICY, ROOT, fail,
    repository_group_id)
from module_boundary_sources import (
    COMMENTS_AND_LITERALS, SOURCE_IMPORT, blank_comments_and_literals, call_argument_count, declared_package,
    literals_as_tokens, name_reading_code, production_sources, production_types, qualified_name_pattern, read_java,
    resolve_adapter_class)


# A11 to A14 — debt held by ratchets (AUD-ARC-01, -05, -08, -12). Each table lists, from the production sources, what
# exists today and nothing more: a name outside the table fails, and so does an entry nobody uses any more. A11 and A13
# count, per type, the production files that use it, and A12 counts, per class, its launch sites: one more use fails,
# and one less fails until the count is lowered. The tables only shrink, by a visible edit, when a structural change
# removes a use; a `var` or a chained call is not a removal. Names are written relative to the reactor groupId (read
# from the root POM). Test sources are not concerned.

# A11 / AUD-ARC-01, ADR 0058 § 3 — the minos-engine types each surface uses, with the number of its production files
# that use each: by import, by fully qualified name, or by calling a MinosApplication accessor that returns the type
# (minos_application_accessors of module_boundary_facade). Both views of the debt live in module-boundaries.json: this
# one under engineTypesNamedBySurface, the bytecode view of ModuleArchitectureTest under
# engineTypesUsedBySurfaceBytecode; a lot that removes a use updates both entries of the file.
ENGINE_TYPES_NAMED_BY_SURFACE: dict[str, dict[str, int]] = POLICY.engine_types_named_by_surface

# A12 / AUD-ARC-05, ADR 0068 — classes of the inner layers that launch a process (module/name.Type), with their number
# of launch sites. Empty: the Windows ACL mechanism of private storage is the PlatformAccessControl port, provided by
# minos-runtime-local (com.minos.runtime.local.acl).
PROCESS_LAUNCHER_MODULES = ("minos-domain", ENGINE_MODULE, APPLICATION_MODULE)
PROCESS_LAUNCHERS: dict[str, int] = {}
PROCESS_LAUNCH = re.compile(r"\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*(?:\.|::)\s*exec\b")
PROCESS_LAUNCH_SITE = re.compile(r"\bnew\s+ProcessBuilder\b|\bProcessBuilder\s*::\s*new\b"
                                 r"|\bProcessBuilder\s*\.\s*startPipeline\b"
                                 r"|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*(?:\.|::)\s*exec\b")
# A Runtime held by a name (local, field or parameter typed Runtime, or any name assigned Runtime.getRuntime(), `var`
# included) launches a process through `name.exec` or `name::exec`; other Runtime calls (availableProcessors, maxMemory)
# do not launch anything.
RUNTIME_HOLDER = re.compile(r"\b(?:java\s*\.\s*lang\s*\.\s*)?Runtime\s+([A-Za-z_]\w*)\b"
                            r"|\b([A-Za-z_]\w*)\s*=\s*(?:java\s*\.\s*lang\s*\.\s*)?Runtime\s*\.\s*getRuntime\s*\(\s*\)")


def process_launch_sites(code: str) -> tuple[bool, int]:
    """A12 — whether code (comments and literals blanked) launches a process, and its number of launch sites: the
    PROCESS_LAUNCH_SITE forms plus every `exec` called on a name that holds a Runtime."""
    holders = {name for pair in RUNTIME_HOLDER.findall(code) for name in pair if name}
    held_exec = (re.compile(r"\b(?:" + "|".join(sorted(map(re.escape, holders))) + r")\s*(?:\.|::)\s*exec\b")
                 if holders else None)
    held_sites = len(held_exec.findall(code)) if held_exec else 0
    return bool(PROCESS_LAUNCH.search(code)) or held_sites > 0, len(PROCESS_LAUNCH_SITE.findall(code)) + held_sites


# A13 / AUD-ARC-08, ADR 0068 — the types of one adapter another adapter names, per (naming, named) couple, with the
# number of files of the naming adapter that name each.
ADAPTER_TYPES_NAMED_BY_ADAPTER: dict[tuple[str, str], dict[str, int]] = {
    ("minos-provider-scip", "minos-runtime-local"): {
        "runtime.local.StrongProcessOwnershipIndexerExecutor": 1,
        "runtime.local.process.BoundedProcessOutput": 2,
        "runtime.local.process.CommandLocator": 10,
        "runtime.local.process.IndexerProcessPlan": 4,
        "runtime.local.process.IndexerProcessPlanFactory": 4,
        "runtime.local.process.InstallationProcessEnvironment": 2,
        "runtime.local.process.ProcessIndexerExecutor": 1,
        "runtime.local.sandbox.WorkerSandboxBackend": 1,
        "runtime.local.sandbox.WorkerSandboxBackends": 1,
    },
}

# A13 / AUD-ARC-08, ADR 0068 — the written reason of each couple of ADAPTER_TYPES_NAMED_BY_ADAPTER. A couple without a
# reason, or a reason without a couple, fails: every coupling between adapters is a reviewed, explained decision.
ADAPTER_COUPLING_REASONS: dict[tuple[str, str], str] = {
    ("minos-provider-scip", "minos-runtime-local"): (
        "the SCIP provider builds the process plans of its indexers, installs its tools with a filtered process "
        "environment and bounded outputs, and runs the indexers under the strong process ownership and the worker "
        "sandbox of the local runtime (ADR 0068, ADR 0070); it names only types exported by A10, plus the strong "
        "ownership launcher"),
}

# A14 / AUD-ARC-12, ADR 0068 — the package roots (first segment under the groupId) each module owns. A new package
# goes under one of its module's roots; a new root is a decision, written here by a visible edit (ADR 0068). A root may
# be owned by several modules (runtime, storage, program, integration): A3 keeps each package in one module, and
# ADR 0068 gives the adapters a module namespace below such a root (com.minos.runtime.local, com.minos.storage.local).
PACKAGE_ROOTS_BY_MODULE: dict[str, frozenset[str]] = {
    # diagnostics: the whole package (PublicErrorMessages, without I/O nor engine dependency) moved down from
    # minos-engine with its qualified name unchanged (ADR 0068 section 3, step 1; AUD-ARC-01, lot 6).
    "minos-domain": frozenset({"diagnostics", "domain", "program", "semantic"}),
    "minos-engine": frozenset({
        "discovery", "dynamic", "git", "hosted", "incremental", "io", "orchestration", "query",
        "registry", "remote", "runtime", "source", "storage", "store",
    }),
    "minos-runtime-local": frozenset({"runtime"}),
    "minos-storage": frozenset({"storage"}),
    "minos-provider-scip": frozenset({"adapter"}),
    "minos-integration-git": frozenset({"integration"}),
    "minos-application": frozenset({
        "application", "architecture", "context", "impact", "output", "program", "workspace",
    }),
    "minos-bootstrap": frozenset({"bootstrap"}),
    "minos-nexus": frozenset({"nexus"}),
    "minos-cli": frozenset({"cli"}),
    "minos-api": frozenset({"api"}),
    "minos-mcp": frozenset({"mcp"}),
    "minos-explorer": frozenset({"explorer"}),
    "minos-app": frozenset({"app", "integration"}),
}


ACCESSOR_USE = re.compile(r"(::|\.)\s*([A-Za-z_]\w*)\b(\s*\()?")


def accessor_types_used(code: str, accessors: dict[str, tuple[int, str]], group: str) -> set[str]:
    """Engine types (qualified) a source reaches through a MinosApplication accessor: a call with the accessor's arity,
    or a method reference. The code is the output of literals_as_tokens, imports removed."""
    used: set[str] = set()
    for match in ACCESSOR_USE.finditer(code):
        accessor = accessors.get(match.group(2))
        if accessor is None:
            continue
        if match.group(1) == "::" or (match.group(3) and accessor[0] == call_argument_count(
                code, match.end() - 1, "A11 unbalanced accessor call")):
            used.add(f"{group}.{accessor[1]}")
    return used


def types_named_by_module(root: Path, module: str, targets: dict[str, str], qualified_name: re.Pattern[str],
                          accessors: dict[str, tuple[int, str]] | None = None, group: str = "",
                          naming_package: str = "") -> tuple[dict[str, set[str]], list[str]]:
    """Target types the production sources of one module use (type -> files): by single or static import, by fully
    qualified name and, when accessors are given, by a call of a MinosApplication accessor that returns the type. Also
    every non-static wildcard import of a target package or type, nested types included ('file -> target.*'); one on a
    type is a use of that type too. Comments and string literals are ignored; a nested type counts as its top-level
    type. A naming package (dotted, under the source root) restricts the naming files to that package and its
    subpackages."""
    named: dict[str, set[str]] = {}
    wildcards: list[str] = []
    packages = set(targets.values())
    source_root = root / module / "src" / "main" / "java"
    naming_root = source_root.joinpath(*naming_package.split(".")) if naming_package else source_root
    if not naming_root.is_dir():
        return named, wildcards
    for source in sorted(naming_root.rglob("*.java")):
        text = read_java(source)
        code = name_reading_code(text, source)
        relative = source.relative_to(root).as_posix()
        names = []
        for static, name, wildcard in SOURCE_IMPORT.findall(code):
            if wildcard and not static:
                # The simple names such an import brings in are written nowhere else: refused. On a type, they all
                # belong to it (a nested type counts as its top-level type), so the file also uses that type.
                if name in packages or resolve_adapter_class(name, targets):
                    wildcards.append(f"{relative} -> {name}.*")
                if name in packages:
                    continue
            names.append(name)
        names += qualified_name.findall(SOURCE_IMPORT.sub(" ", code))
        if accessors:
            names += sorted(accessor_types_used(SOURCE_IMPORT.sub(" ", literals_as_tokens(text)), accessors, group))
        for name in names:
            resolved = resolve_adapter_class(name, targets)
            if resolved:
                named.setdefault(resolved, set()).add(relative)
    return named, wildcards


def named_type_ratchet_problems(rule: str, label: str, table: str, root: Path, naming: str, target: str,
                                listed: dict[str, int], group: str, qualified_name: re.Pattern[str],
                                accessors: dict[str, tuple[int, str]] | None = None, naming_package: str = "",
                                target_package: str = "") -> list[str]:
    """Problems of one (naming module, target module) list: unknown entry, invalid count, type outside the list, count
    different from the number of files that use the type, dead entry. Naming and target packages (dotted, under the
    source root) narrow the list to the types of one package named by the files of another, inside one module."""
    targets = {name: package for name, package in production_types(root, target).items()
               if not target_package or name.startswith(target_package + ".")}
    prefix = group + "."
    problems = [f"{label}: {table} entry {name} names no production type of {target}"
                for name in sorted(listed) if prefix + name not in targets]
    problems += [f"{label}: {table} entry {name} has the count {count!r}; write the positive number of files that "
                 "use it" for name, count in sorted(listed.items())
                 if isinstance(count, bool) or not isinstance(count, int) or count < 1]
    named, wildcards = types_named_by_module(root, naming, targets, qualified_name, accessors, group, naming_package)
    problems += [f"{label}: wildcard import of a {target} package or type {wildcard}; import each type by name"
                 for wildcard in wildcards]
    for qualified in sorted(named):
        name = qualified.removeprefix(prefix)
        files = sorted(named[qualified])
        if name not in listed:
            problems.append(f"{label}: {', '.join(files)} uses {qualified}, absent from {table}")
        elif listed[name] != len(files):
            advice = ("a new use goes through a port, not into the table" if len(files) > listed[name]
                      else f"write {len(files)} (the ratchet only shrinks)")
            problems.append(f"{label}: {table} entry {name} is listed for {listed[name]} file(s) and used by "
                            f"{len(files)} ({', '.join(files)}); {advice}")
    used = {qualified.removeprefix(prefix) for qualified in named}
    problems += [f"{label}: {table} entry {name} is no longer named; remove it (the ratchet only shrinks)"
                 for name in sorted(listed) if prefix + name in targets and name not in used]
    return problems


def check_engine_types_named_by_surfaces(root: Path = ROOT, group_id: str | None = None,
                                         listed: dict[str, dict[str, int]] | None = None) -> dict[str, int]:
    """A11 — each surface and each contract module uses only the minos-engine types of its
    ENGINE_TYPES_NAMED_BY_SURFACE list, each from the listed number of files; a call of a MinosApplication accessor
    counts as a use of the engine type it returns. A non-static wildcard import of a minos-engine package or type
    fails."""
    listed = ENGINE_TYPES_NAMED_BY_SURFACE if listed is None else listed
    group = group_id or repository_group_id(root)
    qualified_name = qualified_name_pattern(group)
    accessors = minos_application_accessors(root, group)
    unknown = sorted(set(listed) - ENGINE_TYPE_RATCHETED_MODULES)
    problems = [f"ENGINE_TYPES_NAMED_BY_SURFACE names {module}, which is neither a surface nor a contract module"
                for module in unknown]
    for surface in sorted(ENGINE_TYPE_RATCHETED_MODULES):
        problems += named_type_ratchet_problems("A11", surface, "ENGINE_TYPES_NAMED_BY_SURFACE", root, surface,
                                                ENGINE_MODULE, listed.get(surface, {}), group, qualified_name,
                                                accessors)
    if problems:
        fail("A11 surface -> minos-engine ratchet (ADR 0058 section 3: a new engine type used by a surface needs a "
             "justification; go through an application port, or add the type to ENGINE_TYPES_NAMED_BY_SURFACE in "
             "a reviewed change): " + "; ".join(problems))
    return {surface: len(listed.get(surface, {})) for surface in sorted(ENGINE_TYPE_RATCHETED_MODULES)}


# AUD-ARC-05, ADR 0068 (Mise en œuvre) — the share of concrete code of minos-engine is measured, never ratcheted: a
# ratchet would refuse splitting a large class and accept turning a class into a record. A production file counts for
# the kind of its first top-level declaration, with all its lines (comments and blank lines included).
TOP_LEVEL_KIND = re.compile(r"^(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|abstract|final|static|sealed"
                            r"|non-sealed|strictfp)\s+)*(class|interface|record|enum|@interface)\s+\w+", re.MULTILINE)


def engine_concrete_code(root: Path = ROOT, module: str = ENGINE_MODULE) -> dict[str, tuple[int, int]]:
    """AUD-ARC-05 — kind of first top-level declaration -> (production files, lines) of one module; package-info and
    module-info count as "info", a file without a recognised declaration as "unclassified"."""
    measured: dict[str, tuple[int, int]] = {}
    source_root = root / module / "src" / "main" / "java"
    for source in sorted(source_root.rglob("*.java")) if source_root.is_dir() else []:
        text = source.read_text(encoding="utf-8")
        declaration = TOP_LEVEL_KIND.search(text)
        kind = ("info" if source.name in ("package-info.java", "module-info.java")
                else declaration.group(1) if declaration else "unclassified")
        files, lines = measured.get(kind, (0, 0))
        measured[kind] = (files + 1, lines + len(text.splitlines()))
    return measured


def check_process_launchers(root: Path = ROOT, group_id: str | None = None,
                            listed: dict[str, int] | None = None,
                            modules: tuple[str, ...] = PROCESS_LAUNCHER_MODULES) -> int:
    """A12 — in the domain, the engine and the application, only the PROCESS_LAUNCHERS classes launch a process (a
    class that mentions ProcessBuilder or calls exec on a Runtime), each with its listed number of launch sites (new
    ProcessBuilder, ProcessBuilder::new, ProcessBuilder.startPipeline, Runtime.getRuntime().exec or ::exec, and exec
    on a name that holds a Runtime, see process_launch_sites). Comments and string literals are ignored."""
    listed = PROCESS_LAUNCHERS if listed is None else listed
    prefix = (group_id or repository_group_id(root)) + "."
    found: dict[str, tuple[str, int]] = {}
    for module, source in production_sources(root, modules):
        launches, sites = process_launch_sites(COMMENTS_AND_LITERALS.sub(" ", read_java(source)))
        if launches:
            java_root = root / module / "src" / "main" / "java"
            name = ".".join(source.relative_to(java_root).with_suffix("").parts).removeprefix(prefix)
            found[f"{module}/{name}"] = (source.relative_to(root).as_posix(), sites)
    problems = [f"{path} launches a process and is not in PROCESS_LAUNCHERS ({entry})"
                for entry, (path, _) in sorted(found.items()) if entry not in listed]
    problems += [f"PROCESS_LAUNCHERS entry {entry} has the count {count!r}, which is not a count of launch sites"
                 for entry, count in sorted(listed.items())
                 if isinstance(count, bool) or not isinstance(count, int) or count < 0]
    for entry, (path, sites) in sorted(found.items()):
        if entry in listed and listed[entry] != sites:
            advice = ("a new launch goes through a port provided by minos-runtime-local" if sites > listed[entry]
                      else f"write {sites} (the ratchet only shrinks)")
            problems.append(f"{path}: PROCESS_LAUNCHERS entry {entry} is listed with {listed[entry]} launch site(s) "
                            f"and has {sites}; {advice}")
    problems += [f"PROCESS_LAUNCHERS entry {entry} launches no process any more; remove it (the ratchet only shrinks)"
                 for entry in sorted(set(listed) - set(found))]
    if problems:
        fail("A12 process launch outside minos-runtime-local (ADR 0068: the process runtime belongs to the local "
             "runtime adapter; inject a port it provides): " + "; ".join(problems))
    return len(listed)


def check_adapter_types_named_by_adapters(root: Path = ROOT, group_id: str | None = None,
                                          listed: dict[tuple[str, str], dict[str, int]] | None = None,
                                          reasons: dict[tuple[str, str], str] | None = None) -> int:
    """A13 — an adapter names the types of another adapter only through ADAPTER_TYPES_NAMED_BY_ADAPTER, each from the
    listed number of files, and each couple carries its written reason in ADAPTER_COUPLING_REASONS. A non-static
    wildcard import of a package or type of another adapter fails."""
    listed = ADAPTER_TYPES_NAMED_BY_ADAPTER if listed is None else listed
    reasons = ADAPTER_COUPLING_REASONS if reasons is None else reasons
    group = group_id or repository_group_id(root)
    qualified_name = qualified_name_pattern(group)
    problems = [f"ADAPTER_TYPES_NAMED_BY_ADAPTER names {naming} -> {named}, which is not a couple of adapters"
                for naming, named in sorted(listed) if {naming, named} - ADAPTER_MODULES or naming == named]
    problems += [f"ADAPTER_COUPLING_REASONS has no written reason for {naming} -> {named}; write why the couple is "
                 "needed" for naming, named in sorted(listed) if not reasons.get((naming, named), "").strip()]
    problems += [f"ADAPTER_COUPLING_REASONS gives a reason for {naming} -> {named}, absent from "
                 "ADAPTER_TYPES_NAMED_BY_ADAPTER; remove the reason" for naming, named in sorted(set(reasons) - set(listed))]
    for naming in sorted(ADAPTER_MODULES):
        for named in sorted(ADAPTER_MODULES - {naming}):
            problems += named_type_ratchet_problems("A13", f"{naming} -> {named}", "ADAPTER_TYPES_NAMED_BY_ADAPTER",
                                                    root, naming, named, listed.get((naming, named), {}),
                                                    group, qualified_name)
    if problems:
        fail("A13 adapter -> adapter ratchet (ADR 0068: adapters meet through engine ports; a new coupling is "
             "added to ADAPTER_TYPES_NAMED_BY_ADAPTER in a reviewed change): " + "; ".join(problems))
    return sum(len(types) for types in listed.values())


def check_package_roots(root: Path = ROOT, group_id: str | None = None,
                        listed: dict[str, frozenset[str]] | None = None,
                        modules: tuple[str, ...] = MODULES) -> int:
    """A14 — every production package of a module sits under one of its PACKAGE_ROOTS_BY_MODULE roots."""
    listed = PACKAGE_ROOTS_BY_MODULE if listed is None else listed
    group = group_id or repository_group_id(root)
    problems = [f"PACKAGE_ROOTS_BY_MODULE names {module}, which is not a governed module"
                for module in sorted(set(listed) - set(modules))]
    for module in modules:
        source_root = root / module / "src" / "main" / "java"
        roots: dict[str, str] = {}
        if source_root.is_dir():
            for source in sorted(source_root.rglob("*.java")):
                package = declared_package(source)
                segment = package[len(group) + 1:].split(".")[0] if package.startswith(group + ".") else ""
                roots.setdefault(segment, source.relative_to(root).as_posix())
        allowed = listed.get(module, frozenset())
        problems += [f"{module}: new package root {group}.{segment or '<none>'} ({path}); put the package under "
                     f"one of {', '.join(sorted(allowed)) or 'no root'}" for segment, path in sorted(roots.items())
                     if segment not in allowed]
        problems += [f"{module}: root {group}.{segment} holds no production source any more; remove it (the ratchet "
                     "only shrinks)" for segment in sorted(allowed - set(roots))]
    if problems:
        fail("A14 package roots (ADR 0068: no rename of existing packages, no new root without a decision): "
             + "; ".join(problems))
    return sum(len(roots) for roots in listed.values())


SERVICE_LOADER_LOAD = re.compile(r"\bServiceLoader\s*\.\s*load\s*\(")


def check_service_loader_class_loaders(root: Path = ROOT, modules: tuple[str, ...] = MODULES) -> int:
    """A16 / ADR 0042 — every production ServiceLoader.load call passes an explicit class loader.

    The one-argument form uses the thread context class loader: in a Java host that embeds MINOS with another
    context loader, an optional provider silently disappears (AUD-ARC-04). Production sources of the reactor
    must call ServiceLoader.load(Service.class, loader). Comments and string literals are ignored. Returns the
    number of production discovery sites.
    """
    violations: list[str] = []
    sites = 0
    for module, source in production_sources(root, modules):
        code = blank_comments_and_literals(read_java(source))
        for match in SERVICE_LOADER_LOAD.finditer(code):
            sites += 1
            if call_argument_count(code, match.end() - 1) < 2:
                line = code.count("\n", 0, match.start()) + 1
                violations.append(f"{source.relative_to(root).as_posix()}:{line}")
    if violations:
        fail("A16 ServiceLoader.load without an explicit class loader (the thread context class loader must not "
             "drive production discovery; pass the loader of the service port): " + "; ".join(violations))
    return sites


# A18 / AUD-ARC-07, ADR 0056 — read use cases read the active snapshot through SnapshotReadView and the
# CodeKnowledgeReader port. Only the com.minos.store package of minos-engine names the in-memory index; the deprecated
# SnapshotQueryView, which hands that index out, is named or reached (loadActiveQueryView(, queryStore()) only by the
# closed list below; minos-application names no write port (CodeKnowledgeStore). The words are searched in the code
# with comments and literals blanked: imports (single, static, wildcard), qualified names, nested types and chained
# calls all show the word. The list only shrinks.
LEGACY_SNAPSHOT_VIEW_USERS = frozenset({
    # Both snapshot stores implement the deprecated loadActiveQueryView by SnapshotQueryView.of(read view).
    "minos-storage/storage.local.store.FileSymbolSnapshotStore",
    "minos-storage/storage.postgresql.snapshot.PostgresCodeKnowledgeSnapshotStore",
    # Deprecated public overload analyze(SnapshotQueryView, ...), delegating to analyze(SnapshotReadView, ...).
    "minos-application/impact.ImpactAnalysisService",
})
CORE_STORE_PACKAGE = "store"
MEMORY_INDEX = re.compile(r"\bInMemoryCodeKnowledgeStore\b")
LEGACY_SNAPSHOT_VIEW = re.compile(r"\bSnapshotQueryView\b|\bloadActiveQueryView\s*\(|\bqueryStore\s*\(\s*\)")
WRITE_PORT = re.compile(r"\bCodeKnowledgeStore\b")


def check_snapshot_read_port(root: Path = ROOT, group_id: str | None = None,
                             listed: frozenset[str] = LEGACY_SNAPSHOT_VIEW_USERS,
                             modules: tuple[str, ...] = MODULES) -> int:
    """A18 — the in-memory index stays in the core, the deprecated view in its closed list, the write port out of
    minos-application (production sources; comments and string literals ignored)."""
    group = group_id or repository_group_id(root)
    core = f"{group}.{CORE_STORE_PACKAGE}"
    problems: list[str] = []
    users: set[str] = set()
    for module, source in production_sources(root, modules):
        if module == ENGINE_MODULE and declared_package(source) == core:
            continue
        relative = source.relative_to(root).as_posix()
        java_root = root / module / "src" / "main" / "java"
        name = ".".join(source.relative_to(java_root).with_suffix("").parts).removeprefix(group + ".")
        entry = f"{module}/{name}"
        code = blank_comments_and_literals(read_java(source))
        for number, line in enumerate(code.splitlines(), start=1):
            if MEMORY_INDEX.search(line):
                problems.append(f"{relative}:{number} names InMemoryCodeKnowledgeStore; read through "
                                f"SnapshotReadView and CodeKnowledgeReader")
            legacy = ", ".join(re.sub(r"\s+", "", match.group(0)).replace("(", "(...)").replace("(...))", "()")
                               for match in LEGACY_SNAPSHOT_VIEW.finditer(line))
            if legacy:
                users.add(entry)
                if entry not in listed:
                    problems.append(f"{relative}:{number} uses the deprecated snapshot view ({legacy}); use "
                                    f"loadActiveReadView and SnapshotReadView")
            if module == APPLICATION_MODULE and WRITE_PORT.search(line):
                problems.append(f"{relative}:{number} names the write port CodeKnowledgeStore; a read use case "
                                f"receives CodeKnowledgeReader")
    problems += [f"LEGACY_SNAPSHOT_VIEW_USERS entry {entry} does not exist or no longer uses the deprecated view; "
                 f"remove it (the ratchet only shrinks)" for entry in sorted(listed - users)]
    if problems:
        fail("A18 snapshot read port (ADR 0056: only minos-engine com.minos.store names the in-memory index; the "
             "deprecated SnapshotQueryView stays in LEGACY_SNAPSHOT_VIEW_USERS): " + "; ".join(problems))
    return len(listed)
