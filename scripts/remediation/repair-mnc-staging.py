from pathlib import Path

path = Path("scripts/remediation/apply-mnc-bounds.py")
text = path.read_text(encoding="utf-8")
updated = text.replace("= '''", "= r'''")
if updated == text:
    raise SystemExit("no non-raw triple-quoted staging literals found")
# Raw Python snippets must contain the Java backslash char literal exactly as '\\'.
updated = updated.replace("replace('\\\\\\\\', '/')", "replace('\\\\', '/')")

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

bad_wrapper = '''body = text[start:end]
wrapped = "        try {\\n" + "".join("    " + line if line.strip() else line for line in body.splitlines(True)) + "        } catch (UncheckedIOException exception) {\\n            throw exception.getCause();\\n        }\\n"
text = text[:start] + wrapped + text[end:]
'''
good_wrapper = '''body = text[start:end]
method_close = "    }\\n"
if not body.endswith(method_close):
    raise SystemExit("ProjectDiscoveryService discover closing brace mismatch")
body = body[:-len(method_close)]
wrapped = ("        try {\\n"
           + "".join("    " + line if line.strip() else line for line in body.splitlines(True))
           + "        } catch (UncheckedIOException exception) {\\n"
           + "            throw exception.getCause();\\n"
           + "        }\\n"
           + method_close)
text = text[:start] + wrapped + text[end:]
'''
if bad_wrapper not in updated:
    raise SystemExit("ProjectDiscoveryService staging wrapper anchor missing")
updated = updated.replace(bad_wrapper, good_wrapper, 1)

path.write_text(updated, encoding="utf-8", newline="\n")

# Narrow MinosApplication.close from AutoCloseable's broad Exception contract to IOException.
app = Path("minos-application/src/main/java/com/minos/application/MinosApplication.java")
app_text = app.read_text(encoding="utf-8")
old_close = '''    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            storageBackend.close();
        }
    }
'''
new_close = '''    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try {
            storageBackend.close();
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("unable to close MINOS storage backend", exception);
        }
    }
'''
if app_text.count(old_close) != 1:
    raise SystemExit("MinosApplication close contract anchor mismatch")
app.write_text(app_text.replace(old_close, new_close, 1), encoding="utf-8", newline="\n")

print("converted Java snippets to raw literals; fixed NEXUS escaping; hardened discovery and close lifecycle staging")
