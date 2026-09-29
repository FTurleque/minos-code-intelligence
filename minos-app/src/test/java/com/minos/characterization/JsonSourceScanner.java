package com.minos.characterization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lecture des sources de production pour les garde-fous de sortie JSON ({@link JsonEscapeGuardTest},
 * {@link JsonOrderGuardTest}). Jouée depuis {@code minos-app}, dont le répertoire de travail est la
 * racine du dépôt.
 */
final class JsonSourceScanner {

    static final Path ROOT = Path.of("").toAbsolutePath();

    private JsonSourceScanner() {
    }

    static List<Path> productionSources(List<String> roots) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String root : roots) {
            Path start = ROOT.resolve(root.contains("/src/main/java") ? root : root + "/src/main/java");
            if (Files.isRegularFile(start)) {
                files.add(start);
            } else if (Files.isDirectory(start)) {
                try (Stream<Path> walk = Files.walk(start)) {
                    walk.filter(path -> path.toString().endsWith(".java")).sorted().forEach(files::add);
                }
            } else {
                throw new AssertionError("racine de garde introuvable : " + start);
            }
        }
        assertTrue(!files.isEmpty(), "aucune source de production trouvée depuis " + ROOT);
        return files;
    }

    static String relative(Path file) {
        return ROOT.relativize(file).toString().replace('\\', '/');
    }

    /** Le code Java sans commentaires ; les littéraux gardent leur contenu. */
    static String withoutComments(String source) {
        return scan(source, false);
    }

    /**
     * Le code Java sans commentaires ni contenu de littéraux : les guillemets sont gardés, leur
     * contenu est remplacé par des espaces, de sorte que virgules et parenthèses des chaînes ne
     * faussent pas le comptage des arguments.
     */
    static String codeOnly(String source) {
        return scan(source, true);
    }

    private static String scan(String source, boolean blankLiterals) {
        StringBuilder out = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : ' ';
            if (current == '/' && next == '/') {
                while (index < source.length() && source.charAt(index) != '\n') index++;
            } else if (current == '/' && next == '*') {
                index += 2;
                while (index + 1 < source.length() && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
                    if (source.charAt(index) == '\n') out.append('\n');
                    index++;
                }
                index += 2;
            } else if (source.startsWith("\"\"\"", index)) {
                out.append("\"\"\"");
                index += 3;
                while (index < source.length() && !source.startsWith("\"\"\"", index)) {
                    if (source.charAt(index) == '\\') {
                        out.append(blankLiterals ? ' ' : '\\');
                        index++;
                    }
                    char literal = source.charAt(index);
                    out.append(literal == '\n' || !blankLiterals ? literal : ' ');
                    index++;
                }
                out.append("\"\"\"");
                index += 3;
            } else if (current == '"' || current == '\'') {
                out.append(current);
                index++;
                while (index < source.length() && source.charAt(index) != current) {
                    if (source.charAt(index) == '\\') {
                        out.append(blankLiterals ? ' ' : '\\');
                        index++;
                    }
                    out.append(blankLiterals ? ' ' : source.charAt(index));
                    index++;
                }
                out.append(current);
                index++;
            } else {
                out.append(current);
                index++;
            }
        }
        return out.toString();
    }

    /** Nombre d'arguments de chaque appel de {@code callee(...)} dans {@code code}, dans l'ordre du source. */
    static List<Integer> argumentCounts(String code, String callee) {
        List<Integer> counts = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?<!\\w)" + Pattern.quote(callee) + "\\s*\\(").matcher(code);
        while (matcher.find()) {
            int index = matcher.end();
            int depth = 1;
            int commas = 0;
            boolean anyToken = false;
            while (index < code.length() && depth > 0) {
                char current = code.charAt(index);
                if (current == '(' || current == '[' || current == '{') depth++;
                else if (current == ')' || current == ']' || current == '}') depth--;
                else if (current == ',' && depth == 1) commas++;
                if (depth > 0 && !Character.isWhitespace(current)) anyToken = true;
                index++;
            }
            counts.add(anyToken ? commas + 1 : 0);
        }
        return counts;
    }
}
