"""The boundary policy of check-module-boundaries.py: module-boundaries.json read strictly, the module
families and the allowed internal dependencies derived from it, and the root POM groupId.

An unreadable or invalid policy file fails the guard as soon as this module is imported.

This module is not a gate: check-module-boundaries.py imports it and runs its rules; its self-tests are
the test_check_module_boundaries*.py files.
"""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

# AUD-ARC-04, ADR 0058 — the boundary policy shared with ModuleArchitectureTest (modules in reactor order and their
# family, allowed internal dependencies, couples allowed in test scope only, both views of the A11 debt) and the A19 and
# A20 tables have one source, read strictly: no table of the guard repeats it.
POLICY_FILE = ROOT / "scripts" / "architecture" / "module-boundaries.json"
POLICY_SECTIONS = ("version", "modules", "allowedDependencies", "testOnlyDependencies", "engineTypesNamedBySurface",
                   "engineTypesUsedBySurfaceBytecode", "embeddedAdapterClasses", "infrastructureApiUsers",
                   "assemblyClasses")
FAMILIES = ("core", "adapter", "application", "composition", "contract", "surface", "assembly")
SINGLE_MODULE_FAMILIES = ("application", "composition", "assembly")


class BoundaryPolicy:
    """The shared boundary policy as module-boundaries.json declares it, checked on load."""

    def __init__(self, **sections: object) -> None:
        self.modules: tuple[str, ...] = sections["modules"]
        self.families: dict[str, str] = sections["families"]
        self.allowed_dependencies: dict[str, frozenset[str]] = sections["allowed_dependencies"]
        self.test_only_dependencies: frozenset[tuple[str, str]] = sections["test_only_dependencies"]
        self.engine_types_named_by_surface: dict[str, dict[str, int]] = sections["engine_types_named_by_surface"]
        self.engine_types_used_by_surface_bytecode: dict[str, dict[str, int]] =             sections["engine_types_used_by_surface_bytecode"]
        self.embedded_adapter_classes: dict[str, str] = sections["embedded_adapter_classes"]
        self.infrastructure_api_users: frozenset[str] = sections["infrastructure_api_users"]
        self.assembly_classes: dict[str, str] = sections["assembly_classes"]

    def family(self, family: str) -> frozenset[str]:
        return frozenset(module for module in self.modules if self.families[module] == family)

    def single(self, family: str) -> str:
        return next(iter(self.family(family)))


def load_policy(path: Path = POLICY_FILE, root: Path = ROOT) -> BoundaryPolicy:
    """Reads the boundary policy without any default: a missing or unparsable file, an unknown, duplicated or missing
    key, an unknown family, an unknown module or a debt entry without reason fails, naming the file relatively."""
    try:
        shown = path.relative_to(root).as_posix()
    except ValueError:
        shown = path.name

    def refuse(cause: str) -> None:
        raise RuntimeError(f"boundary policy {shown}: {cause}")

    def unique_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
        seen: dict[str, object] = {}
        for key, value in pairs:
            if key in seen:
                refuse(f"duplicated key {key!r}")
            seen[key] = value
        return seen

    if not path.is_file():
        refuse("missing; the guard and ModuleArchitectureTest read the shared policy from this file")
    try:
        policy_document = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_keys)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        refuse(f"not valid JSON ({type(error).__name__})")
    if not isinstance(policy_document, dict):
        refuse("the top level is not an object")

    def exact_keys(value: object, expected: tuple[str, ...], where: str) -> dict:
        if not isinstance(value, dict):
            refuse(f"{where} is not an object")
        for key in value:
            if key not in expected:
                refuse(f"unknown key {key!r} in {where}")
        for key in expected:
            if key not in value:
                refuse(f"missing section {key!r} in {where}")
        return value

    def text(value: object, where: str) -> str:
        if not isinstance(value, str) or not value.strip():
            refuse(f"{where} needs a non-blank text (reason)")
        return value

    exact_keys(policy_document, POLICY_SECTIONS, "the policy")
    if policy_document["version"] != 1:
        refuse(f"unsupported version {policy_document['version']!r}")
    if not isinstance(policy_document["modules"], list) or not policy_document["modules"]:
        refuse("modules is not a non-empty list")
    modules: list[str] = []
    families: dict[str, str] = {}
    for index, entry in enumerate(policy_document["modules"]):
        exact_keys(entry, ("name", "family"), f"modules[{index}]")
        name = text(entry["name"], f"modules[{index}].name")
        if name in families:
            refuse(f"module {name} is listed twice")
        if entry["family"] not in FAMILIES:
            refuse(f"unknown family {entry['family']!r} for {name}")
        modules.append(name)
        families[name] = entry["family"]
    for family in SINGLE_MODULE_FAMILIES:
        if sum(1 for value in families.values() if value == family) != 1:
            refuse(f"exactly one module of family {family!r} is expected")

    def known(module: object, where: str) -> str:
        if module not in families:
            refuse(f"{where} names the unknown module {module!r}")
        return module

    allowed = exact_keys(policy_document["allowedDependencies"], tuple(modules), "allowedDependencies")
    allowed_dependencies: dict[str, frozenset[str]] = {}
    for module in modules:
        targets = allowed[module]
        if not isinstance(targets, list):
            refuse(f"allowedDependencies.{module} is not a list")
        if len(set(targets)) != len(targets):
            refuse(f"allowedDependencies.{module} lists a module twice")
        allowed_dependencies[module] = frozenset(known(target, f"allowedDependencies.{module}") for target in targets)
    if not isinstance(policy_document["testOnlyDependencies"], list):
        refuse("testOnlyDependencies is not a list")
    test_only: set[tuple[str, str]] = set()
    for index, entry in enumerate(policy_document["testOnlyDependencies"]):
        where = f"testOnlyDependencies[{index}]"
        exact_keys(entry, ("module", "dependency", "reason"), where)
        text(entry["reason"], f"{where}.reason")
        test_only.add((known(entry["module"], where), known(entry["dependency"], where)))

    def engine_debt(section: str, counter: str) -> dict[str, dict[str, int]]:
        value = policy_document[section]
        if not isinstance(value, dict):
            refuse(f"{section} is not an object")
        debt: dict[str, dict[str, int]] = {}
        for module, types in value.items():
            known(module, section)
            if families[module] not in ("surface", "contract"):
                refuse(f"{section}.{module}: {module} is neither a surface nor a contract module")
            if not isinstance(types, dict):
                refuse(f"{section}.{module} is not an object")
            debt[module] = {}
            for type_name, entry in types.items():
                where = f"{section}.{module}.{type_name}"
                exact_keys(entry, (counter, "reason"), where)
                text(entry["reason"], f"{where}.reason")
                if not isinstance(entry[counter], int) or isinstance(entry[counter], bool) or entry[counter] < 1:
                    refuse(f"{where}.{counter} is not a positive count")
                debt[module][type_name] = entry[counter]
        return debt

    def reasons(section: str) -> dict[str, str]:
        value = policy_document[section]
        if not isinstance(value, dict):
            refuse(f"{section} is not an object")
        return {name: text(reason, f"{section}.{name}") for name, reason in value.items()}

    users = policy_document["infrastructureApiUsers"]
    if not isinstance(users, list) or any(not isinstance(user, str) or not user.strip() for user in users):
        refuse("infrastructureApiUsers is not a list of class names")
    if len(set(users)) != len(users):
        refuse("infrastructureApiUsers lists a class twice")
    return BoundaryPolicy(
        modules=tuple(modules),
        families=families,
        allowed_dependencies=allowed_dependencies,
        test_only_dependencies=frozenset(test_only),
        engine_types_named_by_surface=engine_debt("engineTypesNamedBySurface", "files"),
        engine_types_used_by_surface_bytecode=engine_debt("engineTypesUsedBySurfaceBytecode", "classes"),
        embedded_adapter_classes=reasons("embeddedAdapterClasses"),
        infrastructure_api_users=frozenset(users),
        assembly_classes=reasons("assemblyClasses"),
    )


try:
    POLICY = load_policy()
except RuntimeError as policy_error:
    print(f"M21 MODULE BOUNDARY CONSISTENCY FAILED: {policy_error}", file=sys.stderr)
    raise SystemExit(1) from policy_error
MODULES = POLICY.modules

# A2 / ADR 0042 — module roles.
# Adapter modules implement engine/domain ports against a concrete technology (process runtime,
# file system, PostgreSQL, SCIP, Git). The application layer only knows ports; no adapter reaches
# back into it. minos-bootstrap is the composition root: the ONLY non-adapter module that knows
# concrete adapter classes, apart from minos-app, the final distributable assembly. Surfaces open the
# application through MinosApplication.open and reach the composition root at runtime only. A contract module
# translates an application result into a versioned external contract (ADR 0020): it sits between the application
# and the surfaces, which may depend on it; no surface depends on another surface.
ADAPTER_MODULES = POLICY.family("adapter")
APPLICATION_MODULE = POLICY.single("application")
BOOTSTRAP_MODULE = POLICY.single("composition")
ASSEMBLY_MODULE = POLICY.single("assembly")
SURFACE_MODULES = POLICY.family("surface")
CONTRACT_MODULES = POLICY.family("contract")
# Surfaces may see the composition root at runtime or in tests, never at compile time.
SURFACE_TO_BOOTSTRAP_SCOPES = frozenset({"runtime", "test"})
# A contract module depends on these modules only; it sees the composition root in test scope only.
CONTRACT_MODULE_DEPENDENCIES = frozenset({"minos-domain", "minos-application"})
CONTRACT_TO_BOOTSTRAP_SCOPES = frozenset({"test"})
# The modules held by the A11 ratchet on minos-engine types: every surface and every contract module.
ENGINE_TYPE_RATCHETED_MODULES = SURFACE_MODULES | CONTRACT_MODULES
# Couples (module, dependency) allowed in test scope only: the SCIP provider receives its snapshot stores through
# engine ports (SH-06), and its tests keep the real file store of minos-storage.
TEST_ONLY_DEPENDENCIES = POLICY.test_only_dependencies

# Explicitly allowed direct MINOS dependencies (module-boundaries.json). This is a maximum set, not a requirement:
# removing a dependency is allowed, adding a dependency outside this policy is blocked. A dict, so that the self-test
# can patch an entry.
ALLOWED_DEPENDENCIES: dict[str, frozenset[str]] = dict(POLICY.allowed_dependencies)

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# Each module's artifact carries the module name, except the final assembly, published under the product name.
ARTIFACT_TO_MODULE = {module: module for module in MODULES if module != "minos-app"} | {
    "minos-code-intelligence": "minos-app"}


def fail(message: str) -> None:
    raise RuntimeError(message)


def repository_group_id(root: Path = ROOT) -> str:
    """The groupId of the reactor, read from its root POM: the only group an internal dependency may use."""
    pom = root / "pom.xml"
    if not pom.is_file():
        fail("root pom.xml is missing: the internal groupId cannot be read")
    group = ET.parse(pom).getroot().findtext("m:groupId", default="", namespaces=NS).strip()
    if not group or "${" in group:
        fail("root pom.xml declares no literal <groupId>: the internal groupId cannot be read")
    return group
