package com.minos.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Politique d'exclusion locale utilisée pendant la découverte d'un projet.
 *
 * <p>Les exclusions techniques internes sont toujours prioritaires. Les règles
 * racine de {@code .gitignore} et {@code .minosignore} sont ensuite évaluées
 * séparément : un chemin ignoré par l'un des deux fichiers reste ignoré. Une
 * négation ne peut donc réinclure qu'une règle antérieure du même fichier.</p>
 *
 * <p>Le sous-ensemble volontairement supporté couvre les besoins usuels de M1 :
 * commentaires, négation {@code !}, ancrage racine {@code /}, répertoires
 * {@code pattern/}, {@code *}, {@code **}, {@code ?} et classes de caractères
 * simples. Les fichiers d'ignore imbriqués restent hors périmètre de M1.2.</p>
 */
public final class ProjectIgnorePolicy {

    private static final Set<String> HARD_IGNORED_DIRECTORY_NAMES = Set.of(
            ".git",
            ".idea",
            ".minos-m0",
            "node_modules",
            "target",
            "dist",
            "out"
    );

    private final List<IgnoreRule> gitRules;
    private final List<IgnoreRule> minosRules;

    private ProjectIgnorePolicy(List<IgnoreRule> gitRules, List<IgnoreRule> minosRules) {
        this.gitRules = List.copyOf(gitRules);
        this.minosRules = List.copyOf(minosRules);
    }

    public static ProjectIgnorePolicy load(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toAbsolutePath().normalize();
        return new ProjectIgnorePolicy(
                readRules(root.resolve(".gitignore")),
                readRules(root.resolve(".minosignore"))
        );
    }

    public boolean isIgnored(Path relativePath, boolean directory) {
        Path normalized = normalizeRelative(relativePath);
        if (isHardIgnored(normalized)) {
            return true;
        }
        String portablePath = portable(normalized);
        return evaluate(gitRules, portablePath, directory)
                || evaluate(minosRules, portablePath, directory);
    }

    public boolean isHardIgnored(Path relativePath) {
        Path normalized = normalizeRelative(relativePath);
        for (Path segment : normalized) {
            if (HARD_IGNORED_DIRECTORY_NAMES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean evaluate(List<IgnoreRule> rules, String portablePath, boolean directory) {
        boolean ignored = false;
        for (IgnoreRule rule : rules) {
            if (rule.matches(portablePath, directory)) {
                ignored = !rule.negated();
            }
        }
        return ignored;
    }

    private static List<IgnoreRule> readRules(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }

        List<IgnoreRule> rules = new ArrayList<>();
        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            IgnoreRule rule = parseRule(rawLine);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private static IgnoreRule parseRule(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.strip();
        if (line.isEmpty()) {
            return null;
        }

        boolean escapedLeadingMarker = line.startsWith("\\#") || line.startsWith("\\!");
        if (line.startsWith("#") && !escapedLeadingMarker) {
            return null;
        }
        if (escapedLeadingMarker) {
            line = line.substring(1);
        }

        boolean negated = false;
        if (line.startsWith("!")) {
            negated = true;
            line = line.substring(1);
        }
        if (line.isEmpty()) {
            return null;
        }

        boolean directoryOnly = line.endsWith("/");
        if (directoryOnly) {
            line = line.substring(0, line.length() - 1);
        }

        boolean anchored = line.startsWith("/");
        if (anchored) {
            line = line.substring(1);
        }
        if (line.isEmpty()) {
            return null;
        }

        String normalizedPattern = line.replace('\\', '/');
        boolean containsSlash = normalizedPattern.indexOf('/') >= 0;
        String regex = globToRegex(normalizedPattern);

        StringBuilder expression = new StringBuilder("^");
        if (!anchored && !containsSlash) {
            expression.append("(?:.*/)?");
        }
        expression.append(regex);
        if (directoryOnly) {
            expression.append("(?:/.*)?");
        }
        expression.append('$');

        return new IgnoreRule(Pattern.compile(expression.toString()), negated, directoryOnly);
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int index = 0;
        while (index < glob.length()) {
            char current = glob.charAt(index);
            if (current == '*') {
                if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                    index += 2;
                    while (index < glob.length() && glob.charAt(index) == '*') {
                        index++;
                    }
                    if (index < glob.length() && glob.charAt(index) == '/') {
                        regex.append("(?:.*/)?");
                        index++;
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                    index++;
                }
                continue;
            }
            if (current == '?') {
                regex.append("[^/]");
                index++;
                continue;
            }
            if (current == '[') {
                int closing = glob.indexOf(']', index + 1);
                if (closing > index + 1) {
                    String characterClass = glob.substring(index + 1, closing);
                    regex.append('[');
                    if (characterClass.startsWith("!")) {
                        regex.append('^');
                        characterClass = characterClass.substring(1);
                    }
                    regex.append(characterClass.replace("\\", "\\\\"));
                    regex.append(']');
                    index = closing + 1;
                    continue;
                }
            }

            if (".(){}+$^|\\".indexOf(current) >= 0) {
                regex.append('\\');
            }
            regex.append(current);
            index++;
        }
        return regex.toString();
    }

    private static Path normalizeRelative(Path path) {
        Objects.requireNonNull(path, "relativePath");
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("relativePath must be relative");
        }
        Path normalized = path.normalize();
        if (normalized.startsWith("..")) {
            throw new IllegalArgumentException("relativePath must stay inside the project root");
        }
        return normalized;
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record IgnoreRule(Pattern pattern, boolean negated, boolean directoryOnly) {
        private boolean matches(String portablePath, boolean directory) {
            if (directoryOnly && portablePath.isEmpty()) {
                return false;
            }
            return pattern.matcher(portablePath).matches();
        }
    }
}
