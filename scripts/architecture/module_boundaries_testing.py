"""Shared fixtures of the self-tests of check-module-boundaries.py (the test_check_module_boundaries*.py files).

The gate is loaded once from its file (``_MODULE``); the modules that hold its rules are the very modules the gate
imports. A test reads or patches a name where the code that uses it reads it: a rule of the gate script itself through
``_MODULE`` (the A2 dependency policy reads ``_MODULE.ALLOWED_DEPENDENCIES``), any other table or helper through the
module that defines it (``BOUNDARY_POLICY``, ``ADAPTERS``, ``RATCHETS``, ``PACKAGES``, ``DOCUMENTS``).

This module is not a test file and not a gate: it holds no test, and the test files that import it are run by the
``invariants`` job.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from importlib import util as importlib_util
from pathlib import Path

_MODULE_PATH = Path(__file__).resolve().parent / "check-module-boundaries.py"
_SPEC = importlib_util.spec_from_file_location("check_module_boundaries", _MODULE_PATH)
_MODULE = importlib_util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_MODULE)

# The gate has put its directory on sys.path: these are the modules it imports, not copies.
BOUNDARY_POLICY = sys.modules["module_boundary_policy"]
ADAPTERS = sys.modules["module_boundary_adapters"]
RATCHETS = sys.modules["module_boundary_ratchets"]
PACKAGES = sys.modules["module_boundary_packages"]
DOCUMENTS = sys.modules["module_boundary_documents"]

POM_NAMESPACE = "http://maven.apache.org/POM/4.0.0"
MODULES = BOUNDARY_POLICY.MODULES


def pom_text(artifact: str, dependencies=(), build: str = "", profiles: str = "", management: str = "") -> str:
    """A module POM. `dependencies` are (artifactId, scope, groupId) triples; groupId defaults to com.minos."""
    declared = "".join(
        f"<dependency><groupId>{group or 'com.minos'}</groupId><artifactId>{name}</artifactId>"
        + (f"<scope>{scope}</scope>" if scope else "") + "</dependency>"
        for name, scope, group in dependencies)
    return (f'<project xmlns="{POM_NAMESPACE}"><modelVersion>4.0.0</modelVersion>'
            f"<parent><groupId>com.minos</groupId><artifactId>minos-parent</artifactId><version>1</version></parent>"
            f"<artifactId>{artifact}</artifactId>{build}{management}<dependencies>{declared}</dependencies>"
            f"{profiles}</project>")


def write_root_pom(root: Path, group: str = "com.minos") -> None:
    (root / "pom.xml").write_text(f'<project xmlns="{POM_NAMESPACE}"><modelVersion>4.0.0</modelVersion>'
                                  f"<groupId>{group}</groupId></project>", encoding="utf-8")


def element(text: str) -> ET.Element:
    return ET.fromstring(text)


class RatchetTreeTestCase(unittest.TestCase):
    """A temporary reactor tree (root pom.xml with groupId com.minos) for the A11-A14 ratchets."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        write_root_pom(self.root)

    def write(self, module, package, name, body, imports=(), kind="main"):
        directory = self.root / module / "src" / kind / "java" / Path(*package.split("."))
        directory.mkdir(parents=True, exist_ok=True)
        lines = "".join(f"import {line};\n" for line in imports)
        (directory / f"{name}.java").write_text(f"package {package};\n\n{lines}\n{body}\n", encoding="utf-8")

    def refused(self, action, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            action()
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))
        return str(raised.exception)
