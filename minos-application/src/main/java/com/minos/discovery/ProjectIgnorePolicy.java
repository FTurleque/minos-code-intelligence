package com.minos.discovery;

import com.minos.io.BoundedInputStream;
import com.minos.source.SourceBudgetPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
 * {@code pattern/}, {@code *}, {@code **}, {@code ?}, classes de caractères
 * simples et échappement d'un caractère par antislash. Les fichiers d'ignore
 * imbriqués restent hors périmètre de M1.2.</p>
 */
public final class ProjectIgnorePolicy {

    private static final long MAX_IGNORE_BYTES = 1024L * 1024L;
    private static final int MAX_IGNORE_LINES = 20_000;
    private static final int MAX_IGNORE_RULES = 10_000;
    private static final int MAX_IGNORE_LINE_CHARS = 8_192;

    private static final Set<String> HARD_IGNORED_DIRECTORY_NAMES = Set.of(
            ".git",
            ".idea",
            ".minos",
            ".minos-m0",
            "node_modules",
            "target",
            "dist",
            "out"
    );

    private final Path root;
    private final SourceBudgetPolicy.Tracker budget;
    private final Set<Path> accountedRegularFiles = new HashSet<>();
    private final List<IgnoreRule> gitRules;
    private final List<IgnoreRule> minosRules;

    private ProjectIgnorePolicy(
            Path root,
            SourceBudgetPolicy.Tracker budget,
            List<IgnoreRule> gitRules,
            List<IgnoreRule> minosRules
    ) {
        this.root = root;
        this.budget = budget;
        this.gitRules = List.copyOf(gitRules);
        this.minosRules = List.copyOf(minosRules);
    }

    public static ProjectIgnorePolicy load(Path projectRoot) throws IOException {
        return load(projectRoot, null);
    }

    static ProjectIgnorePolicy load(Path projectRoot, SourceBudgetPolicy.Tracker budget) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toAbsolutePath().normalize();
        return new ProjectIgnorePolicy(
                root,
                budget,
                readRules(root.resolve(".gitignore")),
                readRules(root.resolve(".minosignore"))
        );
    }

    public boolean isIgnored(Path relativePath, boolean directory) {
        Path normalized = normalizeRelative(relativePath);
        accountTraversal();
        if (hardIgnored(normalized)) return true;
        String portablePath = portable(normalized);
        boolean ignored = evaluate(gitRules, portablePath, directory)
                || evaluate(minosRules, portablePath, directory);
        if (!directory && !ignored) accountRegularFile(normalized);
        return ignored;
    }

    public boolean isHardIgnored(Path relativePath) {
        Path normalized = normalizeRelative(relativePath);
        accountTraversal();
        return hardIgnored(normalized);
    }

    private static boolean hardIgnored(Path normalized) {
        for (Path segment : normalized) {
            if (HARD_IGNORED_DIRECTORY_NAMES.contains(segment.toString())) return true;
        }
        return false;
    }

    private void accountTraversal() {
        if (budget == null) return;
        try {
            budget.accountTraversalEntry();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void accountRegularFile(Path relative) {
        if (budget == null || !accountedRegularFiles.add(relative)) return;
        try {
            Path file = root.resolve(relative).normalize();
            if (file.startsWith(root) && Files.isRegularFile(file)) budget.accountRegularFile(Files.size(file));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
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
        if (!Files.isRegularFile(file)) return List.of();
        List<IgnoreRule> rules = new ArrayList<>();
        int lines = 0;
        try (BoundedInputStream input = new BoundedInputStream(
                     Files.newInputStream(file), MAX_IGNORE_BYTES, "project ignore file");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                lines++;
                if (lines > MAX_IGNORE_LINES) throw new IOException("project ignore file exceeds line limit");
                if (rawLine.length() > MAX_IGNORE_LINE_CHARS) {
                    throw new IOException("project ignore rule exceeds character limit");
                }
                IgnoreRule rule = parseRule(rawLine);
                if (rule != null) {
                    if (rules.size() >= MAX_IGNORE_RULES) throw new IOException("project ignore file exceeds rule limit");
                    rules.add(rule);
                }
            }
        }
        return List.copyOf(rules);
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
        if (!escapedLeadingMarker && line.startsWith("!")) {
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

        boolean containsSlash = line.indexOf('/') >= 0;
        String regex = globToRegex(line);

        StringBuilder baseExpression = new StringBuilder("^");
        if (!anchored && !containsSlash) {
            baseExpression.append("(?:.*/)?");
        }
        baseExpression.append(regex);

        Pattern directPattern = Pattern.compile(baseExpression + "$");
        Pattern effectivePattern = directoryOnly
                ? Pattern.compile(baseExpression + "(?:/.*)?$")
                : directPattern;

        return new IgnoreRule(effectivePattern, directPattern, negated, directoryOnly);
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int index = 0;
        while (index < glob.length()) {
            char current = glob.charAt(index);
            if (current == '\\' && index + 1 < glob.length()) {
                appendRegexLiteral(regex, glob.charAt(index + 1));
                index += 2;
                continue;
            }
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

            appendRegexLiteral(regex, current);
            index++;
        }
        return regex.toString();
    }

    private static void appendRegexLiteral(StringBuilder regex, char value) {
        if (".[](){}*+?$^|\\".indexOf(value) >= 0) {
            regex.append('\\');
        }
        regex.append(value);
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

    private record IgnoreRule(
            Pattern effectivePattern,
            Pattern directPattern,
            boolean negated,
            boolean directoryOnly
    ) {
        private boolean matches(String portablePath, boolean directory) {
            if (directoryOnly && !directory && directPattern.matcher(portablePath).matches()) {
                return false;
            }
            return effectivePattern.matcher(portablePath).matches();
        }
    }
}
