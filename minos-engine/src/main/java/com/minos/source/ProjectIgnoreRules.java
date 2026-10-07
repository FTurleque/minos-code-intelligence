package com.minos.source;

import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedLineReader;
import com.minos.io.ConfinedFileOpener;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static final char BYTE_ORDER_MARK = '\uFEFF';
    private static final long MAX_IGNORE_BYTES = 1024L * 1024L;
    private static final int MAX_IGNORE_LINES = 20_000;
    private static final int MAX_IGNORE_RULES = 10_000;
    private static final int MAX_IGNORE_LINE_CHARS = 8_192;
    /** A rule longer than this is refused at compilation: no legitimate glob needs it. */
    static final int MAX_RULE_CHARS = 1_024;
    /** Star groups per rule; each one multiplies the backtracking a match can need. */
    static final int MAX_WILDCARDS_PER_RULE = 8;
    /** Characters one rule may read while matching one path; beyond it the rule is disabled, not retried. */
    static final int MATCH_STEP_BUDGET = 100_000;

    private static final String ANY_DIRECTORIES = "(?:.*/)?";
    private static final System.Logger LOGGER = System.getLogger(ProjectIgnoreRules.class.getName());

    private static final Set<String> HARD_IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", ".idea", ".minos", ".minos-m0", "node_modules", "target", "dist", "out");

    private final List<IgnoreRule> gitRules;
    private final List<IgnoreRule> minosRules;
    private final int discardedRules;
    private final AtomicInteger exhaustedEvaluations = new AtomicInteger();

    private ProjectIgnoreRules(List<IgnoreRule> gitRules, List<IgnoreRule> minosRules, int discardedRules) {
        this.gitRules = List.copyOf(gitRules);
        this.minosRules = List.copyOf(minosRules);
        this.discardedRules = discardedRules;
    }

    /**
     * Loads {@code .gitignore} and {@code .minosignore} of the project root only. Nested
     * {@code .gitignore} files and {@code .git/info/exclude} are deliberately not read: honouring
     * them needs Git's full precedence, negation and directory-scope semantics, and a partial
     * version would be worse than a documented gap. A rule that cannot be compiled, or is too
     * large or complex to evaluate safely, is discarded and counted ({@link #discardedRuleCount()}),
     * never allowed to fail the load of the others.
     */
    public static ProjectIgnoreRules load(Path projectRoot) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        int[] discarded = new int[1];
        List<IgnoreRule> git = readRules(root, root.resolve(".gitignore"), discarded);
        List<IgnoreRule> minos = readRules(root, root.resolve(".minosignore"), discarded);
        if (discarded[0] > 0) {
            LOGGER.log(System.Logger.Level.WARNING, "MINOS discarded " + discarded[0]
                    + " unusable project ignore rule(s); the other rules still apply");
        }
        return new ProjectIgnoreRules(git, minos, discarded[0]);
    }

    /** Rules refused at load (invalid syntax, too long, too many star groups). Counts only, never their text. */
    public int discardedRuleCount() {
        return discardedRules;
    }

    /**
     * Evaluations (one path against the rules) that exceeded the step budget so far. Such a path is
     * treated as ignored, never indexed on a guess: the verdict belongs to that path alone and depends
     * on neither the order of evaluation nor on any earlier path.
     */
    public int exhaustedEvaluationCount() {
        return exhaustedEvaluations.get();
    }

    public boolean isIgnored(Path relativePath, boolean directory) {
        Path normalized = normalizeRelative(relativePath);
        if (isHardIgnoredNormalized(normalized)) return true;
        String portablePath = portable(normalized);
        try {
            return evaluate(gitRules, portablePath, directory)
                    || evaluate(minosRules, portablePath, directory);
        } catch (MatchBudgetExceeded exceeded) {
            // Fail closed for this path only: a rule that cannot be evaluated within its budget makes the
            // path "not indexable", never "not excluded". One warning per instance, counts only.
            if (exhaustedEvaluations.incrementAndGet() == 1) {
                LOGGER.log(System.Logger.Level.WARNING, "MINOS could not evaluate a project ignore rule within its "
                        + "budget and treats the affected paths as ignored");
            }
            return true;
        }
    }

    public boolean isHardIgnored(Path relativePath) {
        return isHardIgnoredNormalized(normalizeRelative(relativePath));
    }

    /**
     * MINOS-AUD-D01: whether a path a tree walk could not open may be skipped. The JDK opens a directory before
     * {@code preVisitDirectory}, so an unreadable directory reaches {@code visitFileFailed} even when it is hardened or
     * ignored, and its type is not known there: the path is skippable when it is hardened, or ignored under either
     * interpretation of its type (a directory-only rule such as {@code pgdata/} counts). Anything else is not
     * skippable: a source directory MINOS cannot read must not become a silently incomplete index.
     */
    public boolean isSkippableWhenUnreadable(Path relativePath) {
        Path normalized = normalizeRelative(relativePath);
        return isHardIgnoredNormalized(normalized) || isIgnored(normalized, true) || isIgnored(normalized, false);
    }

    /**
     * The failure for an unreadable path that is NOT skippable: the same exception type as the original, because the
     * public error taxonomy classifies an {@code AccessDeniedException} apart from any other {@code IOException}, and
     * a message that names the path relative to the project (never the absolute path) and the way out. The original
     * exception is kept as the cause.
     */
    public static IOException unreadableFailure(Path relativePath, IOException cause) {
        String name = portable(normalizeRelative(relativePath));
        String reason = "cannot be read and is not ignored; add it to .minosignore (or fix its permissions) "
                + "so that MINOS skips it";
        IOException failure;
        if (cause instanceof AccessDeniedException) {
            failure = new AccessDeniedException(name, null, reason);
        } else if (cause instanceof NoSuchFileException) {
            failure = new NoSuchFileException(name, null, reason);
        } else if (cause instanceof FileSystemException) {
            failure = new FileSystemException(name, null, reason);
        } else {
            failure = new IOException(name + " " + reason);
        }
        failure.initCause(cause);
        return failure;
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

    private static List<IgnoreRule> readRules(Path root, Path file, int[] discarded) throws IOException {
        Path candidate = file.toAbsolutePath().normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Path relative = root.relativize(candidate);
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
                // MINOS-AUD-D10: a UTF-8 byte order mark (Windows PowerShell 5.1, older editors) starts the file, never a
                // later line; String.strip() does not remove it, so it would stay glued to the first pattern. Git removes
                // it on every platform, so does this.
                if (lines == 1 && !rawLine.isEmpty() && rawLine.charAt(0) == BYTE_ORDER_MARK) {
                    rawLine = rawLine.substring(1);
                }
                IgnoreRule rule = parseRuleOrDiscard(rawLine, discarded);
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

    private static IgnoreRule parseRuleOrDiscard(String rawLine, int[] discarded) {
        try {
            return parseRule(rawLine);
        } catch (IllegalArgumentException unusable) {
            // PatternSyntaxException is an IllegalArgumentException: one bad rule never fails the load.
            discarded[0]++;
            return null;
        }
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

        if (line.length() > MAX_RULE_CHARS) throw new IllegalArgumentException("ignore rule is too long");
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
        int wildcards = 0;
        while (index < glob.length()) {
            boolean star = glob.charAt(index) == '*';
            int before = regex.length();
            index = appendGlobToken(regex, glob, index);
            // A "**/" folded into the previous one adds nothing and is not counted.
            if (star && regex.length() > before && ++wildcards > MAX_WILDCARDS_PER_RULE) {
                throw new IllegalArgumentException("ignore rule has too many wildcards");
            }
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
            // Consecutive "**/" segments match exactly what one does; repeating the group only
            // multiplies backtracking.
            if (!regex.toString().endsWith(ANY_DIRECTORIES)) regex.append(ANY_DIRECTORIES);
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
        boolean negated = characterClass.startsWith("!") || characterClass.startsWith("^");
        if (negated) characterClass = characterClass.substring(1);
        if (characterClass.isEmpty()) throw new IllegalArgumentException("empty character class");
        // POSIX classes ("[[:alpha:]]") are not supported: refuse the rule rather than compile a wrong one.
        if (characterClass.startsWith("[:")) throw new IllegalArgumentException("unsupported POSIX character class");
        // Every class member is emitted escaped: the content is untrusted input, never regex syntax
        // ("[[]", "[a&&b]"), and a reversed range ("[z-a]") makes the rule unusable. Members are code
        // points, so a range between characters outside the Basic Multilingual Plane stays valid.
        regex.append(negated ? "[^" : "[");
        int[] members = characterClass.codePoints().toArray();
        int position = 0;
        while (position < members.length) {
            int first = members[position];
            if (position + 2 < members.length && members[position + 1] == '-') {
                int last = members[position + 2];
                if (first > last) throw new IllegalArgumentException("reversed character range");
                appendClassMember(regex, first);
                regex.append('-');
                appendClassMember(regex, last);
                position += 3;
            } else {
                appendClassMember(regex, first);
                position++;
            }
        }
        regex.append(']');
        return closing + 1;
    }

    private static void appendClassMember(StringBuilder regex, int value) {
        if ("[]&^-\\".indexOf(value) >= 0) regex.append('\\');
        regex.appendCodePoint(value);
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
        /** @throws MatchBudgetExceeded when matching this path needs more than the step budget */
        private boolean matches(String portablePath, boolean directory) {
            if (directoryOnly && !directory && directPattern.matcher(new BudgetedPath(portablePath)).matches()) {
                return false;
            }
            return effectivePattern.matcher(new BudgetedPath(portablePath)).matches();
        }
    }

    private static final class MatchBudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private MatchBudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** The only way a rule reads a path: each character read spends a fixed budget, so cost is bounded by count, not time. */
    private static final class BudgetedPath implements CharSequence {
        private final String value;
        private int remaining = MATCH_STEP_BUDGET;

        private BudgetedPath(String value) {
            this.value = value;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            if (--remaining < 0) throw new MatchBudgetExceeded();
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return value.subSequence(start, end);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
