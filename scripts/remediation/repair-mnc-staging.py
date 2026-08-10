from pathlib import Path

path = Path("scripts/remediation/apply-mnc-bounds.py")
text = path.read_text(encoding="utf-8")
updated = text.replace("= '''", "= r'''")
if updated == text:
    raise SystemExit("no non-raw triple-quoted staging literals found")

fragile = '''if text.count(old_walk) != 1:
    raise SystemExit("Nexus traversal anchor mismatch")
text = text.replace(old_walk, new_walk, 1)
'''
structural = '''if text.count(old_walk) == 1:
    text = text.replace(old_walk, new_walk, 1)
else:
    nexus_pattern = (r"        int scanned = 0;\\n"
                     r"        try \\(var paths = Files\\.walk\\(root\\)\\) \\{.*?"
                     r"        \\}\\n"
                     r"        if \\(!unresolvedStableIds\\.isEmpty\\(\\)\\)")
    replacement = new_walk + "        if (!unresolvedStableIds.isEmpty())"
    text, nexus_count = re.subn(nexus_pattern, lambda ignored: replacement, text, count=1, flags=re.S)
    if nexus_count != 1:
        raise SystemExit("Nexus traversal structural anchor mismatch")
'''
if fragile not in updated:
    raise SystemExit("NEXUS staging guard anchor missing")
updated = updated.replace(fragile, structural, 1)
path.write_text(updated, encoding="utf-8", newline="\n")
print("converted MNC bounds Java snippets to raw Python literals and hardened NEXUS replacement")
