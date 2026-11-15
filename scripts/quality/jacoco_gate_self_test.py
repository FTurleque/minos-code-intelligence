#!/usr/bin/env python3
"""Self-test of ``check-jacoco.py``: the gate's own decision logic on synthetic reports.

The gate is the only thing standing between a renamed class and a silently shrinking coverage surface, so its
behaviour is verified here rather than assumed. ``check-jacoco.py --self-test`` loads this module and passes itself
as ``gate``: each scenario swaps the gate's scope table, runs its ``main()`` on a temporary report and reads the
JSON result. Runs without pytest so it can be invoked from any CI step. This module is not a gate.
"""
from __future__ import annotations

import importlib
import json
import sys
import tempfile
from pathlib import Path
from types import ModuleType

sys.path.insert(0, str(Path(__file__).resolve().parent))
gate_cli = importlib.import_module("gate_cli")


def run_self_test(gate: ModuleType) -> int:
    """Runs the scenarios against ``gate`` (the loaded ``check-jacoco.py``); 0 when all of them hold."""
    report_xml = """<?xml version="1.0" encoding="UTF-8"?>
<report name="self-test">
  <class name="com/minos/selftest/Alpha">
    <counter type="LINE" covered="90" missed="10"/>
    <counter type="BRANCH" covered="80" missed="20"/>
  </class>
  <class name="com/minos/selftest/Weak">
    <counter type="LINE" covered="10" missed="90"/>
    <counter type="BRANCH" covered="10" missed="90"/>
  </class>
  <class name="com/minos/selftest/pkg/Beta">
    <counter type="LINE" covered="90" missed="10"/>
    <counter type="BRANCH" covered="80" missed="20"/>
  </class>
</report>
"""
    failures: list[str] = []

    def run(scopes: dict, *, skip: list[str] | None = None, xml: str | None = None) -> tuple[int, list[str]]:
        original = dict(gate.SCOPES)
        gate.SCOPES.clear()
        gate.SCOPES.update(scopes)
        try:
            with tempfile.TemporaryDirectory() as directory:
                report = Path(directory) / "jacoco.xml"
                report.write_text(xml if xml is not None else report_xml, encoding="utf-8")
                output = Path(directory) / "gate.json"
                argv = [sys.argv[0], str(report), "--output", str(output)]
                for name in skip or []:
                    argv += ["--skip-scope", name]
                saved_argv = sys.argv
                sys.argv = argv
                try:
                    code = gate.main()
                finally:
                    sys.argv = saved_argv
                payload = json.loads(output.read_text(encoding="utf-8"))
                return code, [n for n, r in payload["scopes"].items() if r.get("status") == "FAIL"]
        finally:
            gate.SCOPES.clear()
            gate.SCOPES.update(original)

    def expect(label: str, actual, wanted) -> None:
        if actual != wanted:
            failures.append(f"{label}: expected {wanted!r}, got {actual!r}")

    live_class = {"prefixes": ("com/minos/selftest/Alpha",), "line": 0.5, "branch": 0.5}
    live_package = {"prefixes": ("com/minos/selftest/pkg/",), "line": 0.5, "branch": 0.5}

    # 1. every declared prefix valid (class prefix and package prefix) -> PASS
    code, failed = run({"class-prefix": live_class, "package-prefix": live_package})
    expect("all prefixes valid: exit", code, 0)
    expect("all prefixes valid: failures", failed, [])

    # 2. one dead prefix beside a live one -> FAIL (the regression this gate previously missed)
    code, failed = run({"mixed": {
        "prefixes": ("com/minos/selftest/Alpha", "com/minos/selftest/Removed"),
        "line": 0.5, "branch": 0.5,
    }})
    expect("dead prefix beside live: exit", code, 1)
    expect("dead prefix beside live: failures", failed, ["mixed"])

    # 3. a dead package prefix is caught the same way
    code, failed = run({"dead-package": {
        "prefixes": ("com/minos/selftest/Alpha", "com/minos/selftest/gone/"),
        "line": 0.5, "branch": 0.5,
    }})
    expect("dead package prefix: exit", code, 1)
    expect("dead package prefix: failures", failed, ["dead-package"])

    # 4. no prefix matches anything -> FAIL
    code, failed = run({"empty": {"prefixes": ("com/minos/absent/",), "line": 0.5, "branch": 0.5}})
    expect("no prefix matches: exit", code, 1)
    expect("no prefix matches: failures", failed, ["empty"])

    # 5. an unmet threshold still fails, independently of prefix validity
    code, failed = run({"threshold": {"prefixes": ("com/minos/selftest/Alpha",), "line": 0.99, "branch": 0.5}})
    expect("threshold breach: exit", code, 1)
    expect("threshold breach: failures", failed, ["threshold"])

    # 6. platform exclusion keeps SKIPPED semantics: a dead prefix in an inapplicable scope is not
    #    evaluated at all, exactly as before.
    other_platform = "windows" if gate.current_platform() != "windows" else "linux"
    code, failed = run({"platform-excluded": {
        "prefixes": ("com/minos/selftest/Removed",), "platform": other_platform, "line": 0.5, "branch": 0.5,
    }})
    expect("platform exclusion: exit", code, 0)
    expect("platform exclusion: failures", failed, [])

    # 7. explicit --skip-scope keeps SKIPPED semantics for the same case
    code, failed = run(
        {"skippable": {"prefixes": ("com/minos/selftest/Removed",), "line": 0.5, "branch": 0.5}},
        skip=["skippable"],
    )
    expect("explicit skip: exit", code, 0)
    expect("explicit skip: failures", failed, [])

    # 8. a weak live prefix cannot hide behind a strongly-covered sibling when the aggregate passes.
    code, failed = run({"prefix-floor": {
        "prefixes": ("com/minos/selftest/Alpha", "com/minos/selftest/Weak"),
        "line": 0.50,
        "branch": 0.45,
        "prefixMinimums": {
            "com/minos/selftest/Weak": {"line": 0.50, "branch": 0.50},
        },
    }})
    expect("weak live prefix: exit", code, 1)
    expect("weak live prefix: failures", failed, ["prefix-floor"])

    # 9-11. a scope that names a module reads the report where that module's POM writes it (AUD-TST-08).
    pom_template = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                    '<artifactId>{name}</artifactId>{build}</project>')

    def resolve(build: str) -> Path | str:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "minos-x").mkdir()
            (root / "minos-x" / "pom.xml").write_text(pom_template.format(name="minos-x", build=build), encoding="utf-8")
            try:
                return gate.module_report("minos-x", root)
            except ValueError as error:
                return str(error)

    redirected = ("<build><directory>${maven.multiModuleProjectDirectory}/target</directory></build>")
    expect("module redirected to the root target", resolve(redirected), Path("target/site/jacoco/jacoco.xml"))
    expect("module with its own target", resolve("<build><plugins/></build>"),
           Path("minos-x/target/site/jacoco/jacoco.xml"))
    unknown = resolve("<build><directory>/somewhere/else</directory></build>")
    expect("unrecognised build directory is refused", isinstance(unknown, str) and "minos-x" in unknown
           and "/somewhere/else" in unknown, True)

    # 12-16. floors per platform and named exclusions (AUD-TST-01).
    here = gate.current_platform()
    elsewhere = "windows" if here != "windows" else "linux"
    package = ("com/minos/selftest/",)
    code, failed = run({"per-platform": {"prefixes": package, "line": {here: 0.60, elsewhere: 0.99},
                                         "branch": {here: 0.55, elsewhere: 0.99}}})
    expect("floor of the current platform met: exit", code, 0)
    expect("floor of the current platform met: failures", failed, [])
    code, failed = run({"per-platform": {"prefixes": package, "line": {here: 0.70, elsewhere: 0.10},
                                         "branch": {here: 0.10, elsewhere: 0.10}}})
    expect("measure under the floor of the current platform: exit", code, 1)
    expect("measure under the floor of the current platform: failures", failed, ["per-platform"])
    code, failed = run({"per-platform": {"prefixes": package, "line": {elsewhere: 0.10}, "branch": {elsewhere: 0.10}}})
    expect("no floor for the current platform fails, never SKIPPED: exit", code, 1)
    expect("no floor for the current platform fails, never SKIPPED: failures", failed, ["per-platform"])
    code, failed = run({"excluding": {"prefixes": package, "exclusions": ("com/minos/selftest/Weak",),
                                      "line": 0.85, "branch": 0.75}})
    expect("named exclusion applied: exit", code, 0)
    expect("named exclusion applied: failures", failed, [])
    code, failed = run({"excluding": {"prefixes": package, "exclusions": ("com/minos/selftest/Gone",),
                                      "line": 0.10, "branch": 0.10}})
    expect("exclusion that matches no class: exit", code, 1)
    expect("exclusion that matches no class: failures", failed, ["excluding"])

    # 17-18. the zones created after the reference measure keep a named scope and their class floors (AUD-TST-01):
    #     the real table declares them, and a copy deprived of one scope is refused by name.
    expect("real table declares the new zones and their class floors", gate.missing_zone_declarations(gate.SCOPES), [])
    deprived = {name: config for name, config in gate.SCOPES.items() if name != "semantic-projection"}
    expect("table deprived of a zone scope is refused", gate.missing_zone_declarations(deprived),
           ["semantic-projection: scope not declared"])

    # 19-20. a guard class of the local HTTP surface under its own floor fails although the scope passes, and a
    #     guard class gone from the report fails as a dead prefix; the floor is the one of the real table.
    guard = "com/minos/explorer/RequestGuard"
    guard_floor = gate.SCOPES.get("explorer-surface", {}).get("prefixMinimums", {}).get(guard, {"line": 0.0, "branch": 0.0})
    explorer_xml = """<?xml version="1.0" encoding="UTF-8"?>
<report name="self-test-explorer">
  <class name="com/minos/explorer/Strong">
    <counter type="LINE" covered="990" missed="10"/>
    <counter type="BRANCH" covered="990" missed="10"/>
  </class>
  <class name="com/minos/explorer/RequestGuard">
    <counter type="LINE" covered="80" missed="20"/>
    <counter type="BRANCH" covered="80" missed="20"/>
  </class>
</report>
"""
    code, failed = run({"guard-floor": {
        "prefixes": ("com/minos/explorer/", guard), "line": 0.90, "branch": 0.90,
        "prefixMinimums": {guard: guard_floor},
    }}, xml=explorer_xml)
    expect("guard class under its floor in a passing scope: exit", code, 1)
    expect("guard class under its floor in a passing scope: failures", failed, ["guard-floor"])
    code, failed = run({"guard-absent": {
        "prefixes": ("com/minos/explorer/", "com/minos/explorer/SessionToken"), "line": 0.10, "branch": 0.10,
    }}, xml=explorer_xml)
    expect("guard class absent from the report: exit", code, 1)
    expect("guard class absent from the report: failures", failed, ["guard-absent"])

    if gate_cli.report_failure_list("MINOS JACOCO GATE SELF-TEST", failures):
        return 1
    print("MINOS JACOCO GATE SELF-TEST SUCCESS (20 scenarios)")
    return 0
