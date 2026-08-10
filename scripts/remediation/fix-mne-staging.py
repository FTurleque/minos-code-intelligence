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
path.write_text(text, encoding="utf-8")
print("MNE staging fixups applied")
