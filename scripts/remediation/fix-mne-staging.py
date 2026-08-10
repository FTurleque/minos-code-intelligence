#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / "scripts/remediation/apply-mne-semantic.py"
text = path.read_text(encoding="utf-8")
old = '''    if count != 1:\n        raise RuntimeError(f"{path}: expected one anchor, found {count}: {old[:120]!r}")\n    save(path, value.replace(old, new, 1))\n'''
new = '''    if count < 1:\n        raise RuntimeError(f"{path}: anchor missing: {old[:120]!r}")\n    save(path, value.replace(old, new, 1))\n'''
if text.count(old) != 1:
    raise RuntimeError("semantic staging replace_once guard anchor mismatch")
text = text.replace(old, new, 1)
old_end = '''replace_between(nexus,\n                "    static final class BoundedById<T> {\\n",\n                "}\\n",\n'''
new_end = '''replace_between(nexus,\n                "    static final class BoundedById<T> {\\n",\n                "    }\\n}\\n",\n'''
if text.count(old_end) != 1:
    raise RuntimeError("semantic staging NEXUS class-tail anchor mismatch")
text = text.replace(old_end, new_end, 1)
text += '''\n# Staging-only guard: replace_between preserves its end anchor, so collapse the duplicated\n# BoundedById + outer-class closing braces introduced by the replacement payload.\nnexus_value = load(nexus)\nduplicated_closing = "    }\\n}\\n    }\\n}\\n"\nif nexus_value.count(duplicated_closing) != 1:\n    raise RuntimeError("NexusExportService: expected one duplicated staging class tail")\nsave(nexus, nexus_value.replace(duplicated_closing, "    }\\n}\\n", 1))\n'''
path.write_text(text, encoding="utf-8")
print("MNE staging fixups applied")
