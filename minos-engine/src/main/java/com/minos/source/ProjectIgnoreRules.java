package com.minos.source;

import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedLineReader;
import com.minos.io.ConfinedFileOpener;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared, bounded project ignore rules used by discovery and provider-visible workspaces.
 *
 * <p>The semantics intentionally preserve the historical MINOS contract: hard-ignored runtime/build
 * directories are always hidden, while {@code .gitignore} and {@code .minosignore} are evaluated
 * independently and then combined. Keeping this parser in {@code minos-engine} prevents discovery
 * and provider staging from drifting apart.</p>
 */
public final class ProjectIgnoreRules {

    private static final long MAX_IGNORE_BYTES = 1024L * 1024L;
    private static final int MAX_IGNORE_LINES = 20_000;
    private static final int MAX_IGNORE_RULES = 10_000;
    private static final int MAX_IGNORE_LINE_CHARS = 8_192;

    private static final Set<String> HARD_IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", ".idea", ".minos", ".minos-m0", "node_modules", "target", "dist", "out");

    private final List<IgnoreRule> gitRules;
    private final List<IgnoreRule> minosRules;

    private ProjectIgnoreRules(List<IgnoreRule> gitRules, List<IgnoreRule> minosRules) {
        this.gitRules = List.copyOf(gitRules);
        this.minosRules = List.copyOf(minosRules);
    }

    public static ProjectIgnoreRules load(Path projectRoot) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        return new ProjectIgnoreRules(
                readRules(root, Path.of(".gitignore")),
                readRules(root, Path.of(".minosignore"))
        );
    }

    public boolean isIgnored(Path relativePath, boolean directory) {
        Path normalized = normalizeRelative(relativePath);
        if (isHardIgnoredNormalized(normalized)) return true;
        String portablePath = portable(normalized);
        return evaluate(gitRules, portablePath, directory)
                || evaluate(minosRules, portablePath, directory);
    }

    public boolean isHardIgnored(Path relativePath) {
        return isHardIgnoredNormalized(normalizeRelative(relativePath));
    }

    private static boolean isHardIgnoredNormalized(Path normalized) {
        for (Path segment : normalized) {
            if (HARD_IGNORED_DIRECTORY_NAMES.contains(segment.toString())) return true;
        }
        return false;
    }

    private static boolean evaluate(List<IgnoreRule> rules, String portablePath, boolean directory) {
        boolean ignored = false;
        for (IgnoreRule rule : rules) {
            if (rule.matches(portablePath, directory)) ignored = !rule.negated();
        }
        return ignored;
    }

    private static List<IgnoreRule> readRules(Path root, Path relative) throws IOException {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<IgnoreRule> rules = new ArrayList<>();
        int lines = 0;
        try (BoundedInputStream input = new BoundedInputStream(
                     Channels.newInputStream(ConfinedFileOpener.openConfinedRegularFile(root, relative)),
                     MAX_IGNORE_BYTES,
                     "project ignore file");
             BoundedLineReader reader = new BoundedLineReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), MAX_IGNORE_LINE_CHARS)) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                lines++;
                if (lines > MAX_IGNORE_LINES) {
                    throw new IOException("project ignore file exceeds line limit");
                }
                IgnoreRule rule = parseRule(rawLine);
                if (rule != null) {
                    if (rules.size() >= MAX_IGNORE_RULES) {
                        throw new IOException("project ignore file exceeds rule limit");
                    }
                    rules.add(rule);
                }
            }
        }
        return List.copyOf(rules);
    }

    private static IgnoreRule parseRule(String rawLine) {
        if (rawLine == null) return null;
        String line = rawLine.strip();
        if (line.isEmpty()) return null;
        boolean escapedLeadingMarker = line.startsWith("\\#") || line.startsWith("\\!");
        if (line.startsWith("#") && !escapedLeadingMarker) return null;
        if (escapedLeadingMarker) line = line.substring(1);

        boolean negated = false;
        if (!escapedLeadingMarker && line.startsWith("!")) {
            negated = true;
            line = line.substring(1);
        }
        if (line.isEmpty()) return null;

        boolean directoryOnly = line.endsWith("/");
        if (directoryOnly) line = line.substring(0, line.length() - 1);
        boolean anchored = line.startsWith("/");
        if (anchored) line = line.substring(1);
        if (line.isEmpty()) return null;

        boolean containsSlash = line.indexOf('/') >= 0;
        String regex = globToRegex(line);
        StringBuilder baseExpression = new StringBuilder("^");
        if (!anchored && !containsSlash) baseExpression.append("(?:.*/)?");
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
            index = appendGlobToken(regex, glob, index);
        }
        return regex.toString();
    }

    private static int appendGlobToken(StringBuilder regex, String glob, int index) {
        char current = glob.charAt(index);
        return switch (current) {
            case '\\' -> appendEscapedLiteral(regex, glob, index);
            case '*' -> appendWildcard(regex, glob, index);
            case '?' -> {
                regex.append("[^/]");
                yield index + 1;
            }
            case '[' -> appendCharacterClassOrLiteral(regex, glob, index);
            default -> {
                appendRegexLiteral(regex, current);
                yield index + 1;
            }
        };
    }

    private static int appendEscapedLiteral(StringBuilder regex, String glob, int index) {
        if (index + 1 >= glob.length()) {
            appendRegexLiteral(regex, '\\');
            return index + 1;
        }
        appendRegexLiteral(regex, glob.charAt(index + 1));
        return index + 2;
    }

    private static int appendWildcard(StringBuilder regex, String glob, int index) {
        if (index + 1 >= glob.length() || glob.charAt(index + 1) != '*') {
            regex.append("[^/]*");
            return index + 1;
        }
        int cursor = index + 2;
        while (cursor < glob.length() && glob.charAt(cursor) == '*') cursor++;
        if (cursor < glob.length() && glob.charAt(cursor) == '/') {
            regex.append("(?:.*/)?");
            return cursor + 1;
        }
        regex.append(".*");
        return cursor;
    }

    private static int appendCharacterClassOrLiteral(StringBuilder regex, String glob, int index) {
        int closing = glob.indexOf(']', index + 1);
        if (closing <= index + 1) {
            appendRegexLiteral(regex, '[');
            return index + 1;
        }
        String characterClass = glob.substring(index + 1, closing);
        regex.append('[');
        if (characterClass.startsWith("!")) {
            regex.append('^');
            characterClass = characterClass.substring(1);
        }
        regex.append(characterClass.replace("\\", "\\\\"));
        regex.append(']');
        return closing + 1;
    }

    private static void appendRegexLiteral(StringBuilder regex, char value) {
        if (".[](){}*+?$^|\\".indexOf(value) >= 0) regex.append('\\');
        regex.append(value);
    }

    private static Path normalizeRelative(Path path) {
        Objects.requireNonNull(path, "relativePath");
        if (path.isAbsolute()) throw new IllegalArgumentException("relativePath must be relative");
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
            if (directoryOnly && !directory && directPattern.matcher(portablePath).matches()) return false;
            return effectivePattern.matcher(portablePath, directory).matches();
        }
    }
}
