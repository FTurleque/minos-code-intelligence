from pathlib import Path


def patch(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one finalization anchor, found {count}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


# SnapshotIntegrityService exposes an instance checksum(Path) API.
patch(
    "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresCodeKnowledgeSnapshotStore.java",
    "String actualSha = com.minos.store.SnapshotIntegrityService.sha256(row.payload());",
    "String actualSha = new com.minos.store.SnapshotIntegrityService().checksum(row.payload());",
)

# WorkerSandboxQualification's machine-readable evidence list is named limitations().
patch(
    "minos-runtime-local/src/test/java/com/minos/runtime/LinuxBubblewrapWorkerSandboxIsolationTest.java",
    'backend.qualification().evidence().contains("LINUX_MINIMAL_RUNTIME_READ_ONLY_ALLOWLIST")',
    'backend.qualification().limitations().contains("LINUX_MINIMAL_RUNTIME_READ_ONLY_ALLOWLIST")',
)

# Bubblewrap uses an empty namespace root; create destination parent directories without exposing host content.
path = Path("minos-runtime-local/src/main/java/com/minos/runtime/LinuxBubblewrapWorkerSandboxBackend.java")
text = path.read_text(encoding="utf-8")
old = '''    private static void addReadOnlyIfPresent(List<String> command, Set<Path> mounted, Path candidate)
            throws IOException {
        if (candidate == null || !Files.exists(candidate)) return;
        Path real = candidate.toRealPath();
        if (!mounted.add(real)) return;
        command.add("--ro-bind");
        command.add(real.toString());
        command.add(real.toString());
    }

    private static void addWritableBind(List<String> command, Path directory) {
        command.add("--bind");
        command.add(directory.toString());
        command.add(directory.toString());
    }
'''
new = '''    private static void addReadOnlyIfPresent(List<String> command, Set<Path> mounted, Path candidate)
            throws IOException {
        if (candidate == null || !Files.exists(candidate)) return;
        Path real = candidate.toRealPath();
        if (!mounted.add(real)) return;
        addDestinationParents(command, real);
        command.add("--ro-bind");
        command.add(real.toString());
        command.add(real.toString());
    }

    private static void addWritableBind(List<String> command, Path directory) {
        addDestinationParents(command, directory);
        command.add("--bind");
        command.add(directory.toString());
        command.add(directory.toString());
    }

    private static void addDestinationParents(List<String> command, Path destination) {
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) return;
        List<Path> parents = new ArrayList<>();
        while (parent != null && parent.getParent() != null) {
            parents.add(parent);
            parent = parent.getParent();
        }
        for (int index = parents.size() - 1; index >= 0; index--) {
            command.add("--dir");
            command.add(parents.get(index).toString());
        }
    }
'''
if text.count(old) != 1:
    raise SystemExit("Linux bubblewrap destination-parent helper anchor mismatch")
path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")

print("MNC staging finalized")
