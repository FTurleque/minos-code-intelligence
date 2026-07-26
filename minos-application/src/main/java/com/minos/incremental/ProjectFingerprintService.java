package com.minos.incremental;

import com.minos.discovery.ProjectIgnorePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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

    private final BuildDescriptorPolicy buildDescriptorPolicy;

    public ProjectFingerprintService() {
        this(BuildDescriptorPolicy.m17Defaults());
    }

    public ProjectFingerprintService(BuildDescriptorPolicy buildDescriptorPolicy) {
        this.buildDescriptorPolicy = Objects.requireNonNull(buildDescriptorPolicy, "buildDescriptorPolicy");
    }

    public ProjectFingerprint capture(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        }

        ProjectIgnorePolicy ignorePolicy = ProjectIgnorePolicy.load(root);
        List<FileFingerprint> files = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root)
                        && ignorePolicy.isHardIgnored(root.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }

                Path relative = root.relativize(file);
                if (!isRootControlFile(relative) && ignorePolicy.isIgnored(relative, false)) {
                    return FileVisitResult.CONTINUE;
                }

                files.add(new FileFingerprint(
                        portable(relative),
                        attributes.size(),
                        hashFile(file)
                ));
                return FileVisitResult.CONTINUE;
            }
        });

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

    private static String hashFile(Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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
}
