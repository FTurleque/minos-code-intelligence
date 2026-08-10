from pathlib import Path

path = Path("scripts/remediation/apply-mnc-bounds.py")
text = path.read_text(encoding="utf-8")
updated = text.replace("= '''", "= r'''")
if updated == text:
    raise SystemExit("no non-raw triple-quoted staging literals found")
path.write_text(updated, encoding="utf-8", newline="\n")
print("converted MNC bounds Java snippets to raw Python literals")
