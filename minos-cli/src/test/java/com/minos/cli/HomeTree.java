package com.minos.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** L'arbre d'un {@code MINOS_HOME} comparé octet par octet : chemins, répertoires vides compris, et empreinte de chaque fichier. */
final class HomeTree {

    private HomeTree() { }

    /** Chaque entrée sous {@code root} : chemin relatif -> « dir » ou SHA-256 du fichier ; vide si {@code root} n'existe pas. */
    static Map<String, String> of(Path root) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        if (Files.notExists(root)) return entries;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                if (Files.isDirectory(path)) {
                    entries.put(relative + "/", "dir");
                } else {
                    entries.put(relative, sha256(Files.readAllBytes(path)));
                }
            }
        }
        return entries;
    }

    /** Ce qui diffère entre deux arbres, une ligne par entrée ({@code +} créée, {@code -} disparue, {@code ~} modifiée). */
    static String difference(Map<String, String> before, Map<String, String> after) {
        StringBuilder difference = new StringBuilder();
        after.forEach((path, digest) -> {
            if (!before.containsKey(path)) difference.append("\n  + ").append(path);
            else if (!before.get(path).equals(digest)) difference.append("\n  ~ ").append(path);
        });
        before.keySet().stream().filter(path -> !after.containsKey(path))
                .forEach(path -> difference.append("\n  - ").append(path));
        return difference.toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
