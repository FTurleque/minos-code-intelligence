#!/usr/bin/env python3
"""Self-test of check-capability-dossiers.py: one accepted tree, one refused mutation per rule."""
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-capability-dossiers.py"
_SPEC = importlib.util.spec_from_file_location("check_capability_dossiers", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = gate
_SPEC.loader.exec_module(gate)

SPEC = """# demo Specification

## Purpose
Capacité de démonstration pour l'auto-test du contrôle des dossiers.

## Requirements

### Requirement: Alpha refuse une entrée vide
Le système SHALL refuser une entrée vide.

#### Scenario: Entrée vide
- **GIVEN** une entrée vide
- **WHEN** la commande est lancée
- **THEN** elle échoue

### Requirement: Beta journalise
Le système SHALL journaliser.

#### Scenario: Journal
- **WHEN** la commande est lancée
- **THEN** une ligne est écrite

## Documentation liée

- Dossier durable : [demo](../../../docs/architecture/diagrams/capacites/demo.md)
"""

DOSSIER = """# Capacité `demo`

Spec de référence : [spec](../../../../openspec/specs/demo/spec.md)

## Vues

### Que se passe-t-il pour une entrée vide ?

Type : sequenceDiagram · Portée : existant vérifié

```mermaid
sequenceDiagram
  A->>B: x
```

## Traçabilité

| Exigence | Scénarios | Vue | Preuve | Statut |
|---|---|---|---|---|
| Alpha refuse une entrée vide | Entrée vide | V1 | `scripts/demo/test_demo.py#test_empty` | couverte |
| Beta journalise | Journal | — | aucune trouvée | manquante |
"""


class CheckCapabilityDossiersTest(unittest.TestCase):

    def run_gate(self, spec: str = SPEC, dossier: str | None = DOSSIER, extra: dict[str, str] | None = None) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {
                "openspec/specs/demo/spec.md": spec,
                "scripts/demo/test_demo.py": "def test_empty():\n    pass\n",
                "docs/architecture/diagrams/capacites/README.md": "# index\n",
                **(extra or {}),
            }
            if dossier is not None:
                files["docs/architecture/diagrams/capacites/demo.md"] = dossier
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            failures, _, _ = gate.check(root)
            return failures

    def assert_refused(self, fragment: str, **kwargs) -> None:
        failures = self.run_gate(**kwargs)
        self.assertTrue(any(fragment in failure for failure in failures), failures)

    def test_accepts_consistent_tree(self) -> None:
        self.assertEqual([], self.run_gate())

    def test_accepts_capability_without_dossier(self) -> None:
        self.assertEqual([], self.run_gate(spec=SPEC.split("## Documentation liée")[0], dossier=None))

    def test_rule8_refuses_missing_dossier_index(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "openspec/specs/demo/spec.md"
            path.parent.mkdir(parents=True)
            path.write_text(SPEC.split("## Documentation liée")[0], encoding="utf-8")
            failures, _, _ = gate.check(root)
            self.assertTrue(any("the dossier index is missing" in failure for failure in failures), failures)

    def test_rule8_refuses_proof_path_with_parent_segment(self) -> None:
        self.assert_refused("with a `..` segment",
                            dossier=DOSSIER.replace("scripts/demo/test_demo.py", "scripts/../scripts/demo/test_demo.py"))

    def test_rule1_refuses_dossier_of_unknown_capability(self) -> None:
        self.assert_refused("no capability `ghost`",
                            extra={"docs/architecture/diagrams/capacites/ghost.md": DOSSIER})

    def test_rule2_refuses_spec_without_linked_section(self) -> None:
        self.assert_refused("no `## Documentation liée`", spec=SPEC.split("## Documentation liée")[0])

    def test_rule2_refuses_linked_section_before_requirements(self) -> None:
        head, tail = SPEC.split("## Requirements")
        linked = "## Documentation liée" + tail.split("## Documentation liée")[1]
        moved = head + linked + "\n## Requirements" + tail.split("## Documentation liée")[0]
        self.assert_refused("must follow `## Requirements`", spec=moved)

    def test_rule2_refuses_heading_in_linked_section(self) -> None:
        self.assert_refused("must hold no `###` heading", spec=SPEC + "\n### Vue\n")

    def test_rule2_refuses_linked_section_without_dossier_link(self) -> None:
        self.assert_refused("does not link to demo.md",
                            spec=SPEC.replace("capacites/demo.md", "capacites/other.md"))

    def test_rule3_refuses_dossier_without_back_link(self) -> None:
        self.assert_refused("does not link back", dossier=DOSSIER.replace("openspec/specs/demo/spec.md", "openspec/specs/x.md"))

    def test_rule4_refuses_view_without_type(self) -> None:
        self.assert_refused("has no `Type :` line", dossier=DOSSIER.replace("Type : sequenceDiagram", "Genre :"))

    def test_rule4_refuses_unknown_diagram_keyword(self) -> None:
        self.assert_refused("not a Mermaid diagram keyword", dossier=DOSSIER.replace("sequenceDiagram\n  A", "seqDiagram\n  A"))

    def test_rule4_accepts_front_matter_before_the_diagram_keyword(self) -> None:
        self.assertEqual([], self.run_gate(dossier=DOSSIER.replace(
            "```mermaid\nsequenceDiagram", "```mermaid\n---\ntitle: Entrée vide\n---\nsequenceDiagram")))

    def test_rule4_refuses_unclosed_front_matter(self) -> None:
        self.assert_refused("not a Mermaid diagram keyword", dossier=DOSSIER.replace(
            "```mermaid\nsequenceDiagram\n  A->>B: x\n", "```mermaid\n---\ntitle: x\n"))

    def test_rule4_refuses_missing_views_section(self) -> None:
        self.assert_refused("no `## Vues` section", dossier=DOSSIER.replace("## Vues", "## Autres"))

    def test_rule5_refuses_missing_requirement(self) -> None:
        self.assert_refused("`Beta journalise` is missing", dossier=DOSSIER.replace("| Beta journalise |", "| |"))

    def test_rule5_refuses_unknown_requirement(self) -> None:
        self.assert_refused("unknown requirement `Gamma`", dossier=DOSSIER + "| Gamma | x | — | — | manquante |\n")

    def test_rule6_refuses_unknown_status(self) -> None:
        self.assert_refused("has status `faite`", dossier=DOSSIER.replace("| manquante |", "| faite |"))

    def test_rule6_refuses_covered_without_proof(self) -> None:
        self.assert_refused("without an existing proof path",
                            dossier=DOSSIER.replace("`scripts/demo/test_demo.py#test_empty`", "un test"))

    def test_rule6_refuses_missing_proof_path(self) -> None:
        self.assert_refused("cites missing proof `scripts/demo/absent.py`",
                            dossier=DOSSIER.replace("scripts/demo/test_demo.py", "scripts/demo/absent.py"))

    def test_rule6_accepts_a_file_of_the_repository_root_as_proof(self) -> None:
        self.assertEqual([], self.run_gate(
            dossier=DOSSIER.replace("`scripts/demo/test_demo.py#test_empty`", "`pom.xml:12`"),
            extra={"pom.xml": "<project/>\n"}))

    def test_rule6_refuses_a_missing_file_of_the_repository_root(self) -> None:
        self.assert_refused("cites missing proof `pom.xml`",
                            dossier=DOSSIER.replace("`scripts/demo/test_demo.py#test_empty`", "`pom.xml:12`"))

    def test_rule9_refuses_a_member_absent_from_its_file(self) -> None:
        self.assert_refused("cites `test_gone`, absent from `scripts/demo/test_demo.py`",
                            dossier=DOSSIER.replace("#test_empty", "#test_gone"),
                            extra={"scripts/demo/test_demo.py": "def test_empty():\n    pass\n"})

    def test_rule9_accepts_a_member_present_in_its_file(self) -> None:
        self.assertEqual([], self.run_gate(extra={"scripts/demo/test_demo.py": "def test_empty():\n    pass\n"}))

    def test_rule5_accepts_a_title_that_starts_and_ends_with_code(self) -> None:
        spec = SPEC.replace("Beta journalise", "`beta` journalise via `log`")
        dossier = DOSSIER.replace("| Beta journalise |", "| `beta` journalise via `log` |")
        self.assertEqual([], self.run_gate(spec=spec, dossier=dossier))

    def test_rule5_accepts_a_title_written_as_one_code_span(self) -> None:
        self.assertEqual([], self.run_gate(dossier=DOSSIER.replace("| Beta journalise |", "| `Beta journalise` |")))

    def test_rule7_refuses_broken_link(self) -> None:
        self.assert_refused("broken link `nowhere.md`", dossier=DOSSIER + "\nVoir [x](nowhere.md).\n")

    def test_report_lists_every_capability(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "openspec/specs/demo/spec.md"
            path.parent.mkdir(parents=True)
            path.write_text(SPEC, encoding="utf-8")
            self.assertIn("| `demo` | 2 | 2 | 2 | non |", gate.report(root))


if __name__ == "__main__":
    unittest.main()
