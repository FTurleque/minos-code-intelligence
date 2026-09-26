package com.minos.incremental;

import com.minos.discovery.ProjectIgnorePolicy;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.FileTreeOperations;
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Captures and compares the observable visible fingerprint of a project. */
public final class ProjectFingerprintService {

    private static final Set<String> ROOT_CONTROL_FILES = Set.of(
            ".gitignore",
            ".minosignore"
    );
    /** Descripteurs d'outillage d'un ancêtre pris en compte par l'empreinte de scope (V8). */
    private static final Set<String> TOOLING_DESCRIPTOR_FILES = Set.of(".npmrc");
    private static final Set<String> TOOLING_DESCRIPTOR_DIRECTORIES = Set.of(".mvn");

    private final BuildDescriptorPolicy buildDescriptorPolicy;
    private final SourceBudgetPolicy sourceBudgetPolicy;

    public ProjectFingerprintService() {
        this(BuildDescriptorPolicy.m24Defaults(), SourceBudgetPolicy.DEFAULT);
    }

    public ProjectFingerprintService(BuildDescriptorPolicy buildDescriptorPolicy) {
        this(buildDescriptorPolicy, SourceBudgetPolicy.DEFAULT);
    }

    public ProjectFingerprintService(
            BuildDescriptorPolicy buildDescriptorPolicy,
            SourceBudgetPolicy sourceBudgetPolicy
    ) {
        this.buildDescriptorPolicy = Objects.requireNonNull(buildDescriptorPolicy, "buildDescriptorPolicy");
        this.sourceBudgetPolicy = Objects.requireNonNull(sourceBudgetPolicy, "sourceBudgetPolicy");
    }

    public ProjectFingerprint capture(Path projectRoot) throws IOException {
        return captureScope(projectRoot, Path.of(""));
    }

    /**
     * Empreinte d'un scope d'exécution (ADR 0039 §1) : le sous-arbre {@code projectRelativeScope}
     * plus, pour chaque répertoire ancêtre jusqu'à la racine, ses fichiers de contrôle racine et ses
     * descripteurs de build. La politique d'ignore est celle de la racine projet, les chemins
     * restent relatifs à cette racine : un scope vide est exactement {@link #capture(Path)}.
     *
     * <p>Limite assumée : comme l'empreinte projet, celle-ci ne couvre ni les répertoires
     * hard-ignorés ({@code target/}, {@code node_modules/}...), ni les dépendances externes, ni les
     * fichiers pointés par un lien symbolique (non suivis). Un artefact dont le contenu dépendrait
     * d'un tel état ne doit pas être déclaré {@code RESUMABLE_ARTIFACT} par son provider.</p>
     */
    public ProjectFingerprint captureScope(Path projectRoot, Path projectRelativeScope) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(projectRelativeScope, "projectRelativeScope");
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        }
        Path scope = projectRelativeScope.normalize();
        if (scope.isAbsolute() || scope.startsWith("..")) {
            throw new IllegalArgumentException("projectRelativeScope must stay inside the project");
        }
        Path scopeRoot = root.resolve(scope).normalize();
        if (!scopeRoot.startsWith(root) || !Files.isDirectory(scopeRoot)) {
            throw new IllegalArgumentException("projectRelativeScope must be an existing directory of the project");
        }
        // V7: a scope reached through a symbolic link is never walked (links are not recursable), so
        // its fingerprint would be blind to the real sources. Every component of the scope is checked.
        Path cursor = root;
        for (Path component : scope) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("projectRelativeScope must not traverse a symbolic link");
            }
        }

        ProjectIgnorePolicy ignorePolicy = ProjectIgnorePolicy.load(root);
        SourceBudgetPolicy.Tracker budget = sourceBudgetPolicy.tracker("project fingerprint");
        List<FileFingerprint> files = new ArrayList<>();

        Files.walkFileTree(scopeRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                if (!directory.equals(scopeRoot) && !FileTreeOperations.isRecursableDirectory(attributes)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(scopeRoot)
                        && ignorePolicy.isHardIgnored(root.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }

                Path relative = root.relativize(file);
                if (!isRootControlFile(relative) && ignorePolicy.isIgnored(relative, false)) {
                    return FileVisitResult.CONTINUE;
                }

                budget.accountFile();
                files.add(fingerprint(root, relative, budget));
                return FileVisitResult.CONTINUE;
            }
        });

        if (!scopeRoot.equals(root)) {
            files.addAll(ancestorDescriptors(root, scope, ignorePolicy, budget));
        }

        files.sort(Comparator.comparing(FileFingerprint::relativePath));
        List<FileFingerprint> immutableFiles = List.copyOf(files);
        String projectHash = aggregateHash(immutableFiles);
        String buildHash = aggregateHash(immutableFiles.stream()
                .filter(file -> buildDescriptorPolicy.isBuildDescriptor(Path.of(file.relativePath())))
                .toList());

        return new ProjectFingerprint(projectHash, buildHash, immutableFiles);
    }

    public ProjectChangeSet compare(ProjectFingerprint previous, ProjectFingerprint current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");

        Map<String, FileFingerprint> before = index(previous.files());
        Map<String, FileFingerprint> after = index(current.files());
        TreeSet<String> paths = new TreeSet<>();
        paths.addAll(before.keySet());
        paths.addAll(after.keySet());

        List<String> added = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();

        for (String path : paths) {
            FileFingerprint oldFile = before.get(path);
            FileFingerprint newFile = after.get(path);
            if (oldFile == null) {
                added.add(path);
            } else if (newFile == null) {
                deleted.add(path);
            } else if (!oldFile.sha256().equals(newFile.sha256())
                    || oldFile.sizeBytes() != newFile.sizeBytes()) {
                modified.add(path);
            } else {
                unchanged.add(path);
            }
        }

        boolean projectChanged = !added.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
        boolean buildChanged = !previous.buildSha256().equals(current.buildSha256());
        return new ProjectChangeSet(
                previous.projectSha256(),
                current.projectSha256(),
                previous.buildSha256(),
                current.buildSha256(),
                projectChanged,
                buildChanged,
                added,
                modified,
                deleted,
                unchanged
        );
    }

    private static Map<String, FileFingerprint> index(List<FileFingerprint> files) {
        Map<String, FileFingerprint> result = new LinkedHashMap<>();
        for (FileFingerprint file : files) {
            if (result.put(file.relativePath(), file) != null) {
                throw new IllegalArgumentException("duplicate file fingerprint: " + file.relativePath());
            }
        }
        return result;
    }

    /**
     * Fichiers de contrôle racine, descripteurs de build et descripteurs d'outillage situés
     * directement dans chaque ancêtre du scope, de la racine (exclue du sous-arbre) jusqu'au parent
     * du scope. Ils conditionnent ce que le provider a vu (réacteur Maven, workspace npm, lockfiles,
     * {@code tsconfig*.json}, {@code .npmrc}, {@code .mvn/**}) sans appartenir au sous-arbre.
     *
     * <p>Les descripteurs d'outillage (V8) ne sont pris en compte que par l'empreinte de scope : les
     * ajouter à {@link BuildDescriptorPolicy} changerait l'empreinte de build de tous les projets et
     * forcerait une réindexation complète unique, hors du périmètre de la reprise.</p>
     */
    private List<FileFingerprint> ancestorDescriptors(
            Path root,
            Path scope,
            ProjectIgnorePolicy ignorePolicy,
            SourceBudgetPolicy.Tracker budget
    ) throws IOException {
        List<FileFingerprint> descriptors = new ArrayList<>();
        Path ancestor = Path.of("");
        for (int depth = 0; depth < scope.getNameCount(); depth++) {
            Path directory = root.resolve(ancestor);
            try (var entries = Files.list(directory)) {
                for (Path candidate : entries.sorted().toList()) {
                    budget.accountTraversalEntry();
                    Path relative = root.relativize(candidate);
                    if (Files.isSymbolicLink(candidate)) continue;
                    if (TOOLING_DESCRIPTOR_DIRECTORIES.contains(candidate.getFileName().toString())
                            && Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        descriptors.addAll(toolingDirectory(root, candidate, ignorePolicy, budget));
                        continue;
                    }
                    if (!isAncestorDescriptor(relative)) continue;
                    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!isRootControlFile(relative) && ignorePolicy.isIgnored(relative, false)) continue;
                    budget.accountFile();
                    descriptors.add(fingerprint(root, relative, budget));
                }
            }
            ancestor = ancestor.resolve(scope.getName(depth));
        }
        return descriptors;
    }

    private boolean isAncestorDescriptor(Path relative) {
        if (isRootControlFile(relative) || buildDescriptorPolicy.isBuildDescriptor(relative)) return true;
        String name = relative.getFileName().toString();
        return TOOLING_DESCRIPTOR_FILES.contains(name)
                || (name.startsWith("tsconfig") && name.endsWith(".json"));
    }

    /** Every regular file below a tooling directory such as {@code .mvn/}, links never followed. */
    private static List<FileFingerprint> toolingDirectory(
            Path root,
            Path directory,
            ProjectIgnorePolicy ignorePolicy,
            SourceBudgetPolicy.Tracker budget
    ) throws IOException {
        List<FileFingerprint> files = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path child, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                return child.equals(directory) || FileTreeOperations.isRecursableDirectory(attributes)
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                Path relative = root.relativize(file);
                if (ignorePolicy.isIgnored(relative, false)) return FileVisitResult.CONTINUE;
                budget.accountFile();
                files.add(fingerprint(root, relative, budget));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static FileFingerprint fingerprint(
            Path root,
            Path relative,
            SourceBudgetPolicy.Tracker budget
    ) throws IOException {
        HashedFile hashed = hashFile(root, relative, budget);
        return new FileFingerprint(portable(relative), hashed.sizeBytes(), hashed.sha256());
    }

    private static boolean isRootControlFile(Path relativePath) {
        return relativePath.getNameCount() == 1
                && ROOT_CONTROL_FILES.contains(relativePath.getFileName().toString());
    }

    private static String aggregateHash(List<FileFingerprint> files) {
        MessageDigest digest = sha256();
        for (FileFingerprint file : files) {
            update(digest, file.relativePath());
            digest.update((byte) 0);
            update(digest, Long.toString(file.sizeBytes()));
            digest.update((byte) 0);
            update(digest, file.sha256());
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static HashedFile hashFile(
            Path root,
            Path relative,
            SourceBudgetPolicy.Tracker budget
    ) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        long bytes = 0L;
        // The tree walk is only discovery. Re-open the relative path through the confinement
        // primitive so neither a junction nor an ancestor replacement can redirect the bytes read.
        try (InputStream input = Channels.newInputStream(
                ConfinedFileOpener.openConfinedRegularFile(root, relative))) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    budget.accountBytes(read);
                    try {
                        bytes = Math.addExact(bytes, read);
                    } catch (ArithmeticException exception) {
                        throw new IOException("project fingerprint file byte counter overflow", exception);
                    }
                    digest.update(buffer, 0, read);
                }
            }
        }
        return new HashedFile(HexFormat.of().formatHex(digest.digest()), bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record HashedFile(String sha256, long sizeBytes) { }
}
