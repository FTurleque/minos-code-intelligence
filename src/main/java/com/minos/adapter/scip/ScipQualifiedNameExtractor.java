package com.minos.adapter.scip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Extrait un nom qualifié MINOS depuis les descripteurs d'un symbole SCIP global.
 *
 * <p>La classe porte uniquement la grammaire de descripteurs standardisée par
 * SCIP. Elle ne publie ni l'identifiant fournisseur brut, ni ses coordonnées de
 * package dans le domaine MINOS.</p>
 */
final class ScipQualifiedNameExtractor {

    private static final String LOCAL_SYMBOL_PREFIX = "local ";
    private static final List<String> ECMASCRIPT_MODULE_EXTENSIONS = List.of(
            ".d.ts", ".d.mts", ".d.cts", ".ts", ".tsx", ".mts", ".cts",
            ".js", ".jsx", ".mjs", ".cjs");

    private ScipQualifiedNameExtractor() {
    }

    static Optional<String> extract(String rawSymbol, String language) {
        if (rawSymbol == null || rawSymbol.isBlank() || rawSymbol.startsWith(LOCAL_SYMBOL_PREFIX)) {
            return Optional.empty();
        }

        String descriptorSection = descriptorSection(rawSymbol);
        if (descriptorSection == null) {
            return Optional.empty();
        }

        List<Descriptor> descriptors = parseDescriptors(descriptorSection);
        if (descriptors.isEmpty()) {
            return Optional.empty();
        }

        boolean ecmaScript = isEcmaScript(language, rawSymbol);
        int firstSemanticDescriptor = ecmaScript
                ? firstDescriptorAfterModuleFile(descriptors)
                : 0;
        if (firstSemanticDescriptor >= descriptors.size()) {
            return Optional.empty();
        }

        String qualifiedName = descriptors.subList(firstSemanticDescriptor, descriptors.size()).stream()
                .map(Descriptor::name)
                .map(name -> normalizeConstructorName(name, ecmaScript))
                .reduce((parent, child) -> parent + "." + child)
                .orElse("");
        return qualifiedName.isBlank() ? Optional.empty() : Optional.of(qualifiedName);
    }

    private static String descriptorSection(String rawSymbol) {
        int separators = 0;
        for (int index = 0; index < rawSymbol.length(); index++) {
            if (rawSymbol.charAt(index) != ' ') {
                continue;
            }
            if (index + 1 < rawSymbol.length() && rawSymbol.charAt(index + 1) == ' ') {
                index++;
                continue;
            }
            separators++;
            if (separators == 4) {
                return index + 1 < rawSymbol.length()
                        ? rawSymbol.substring(index + 1)
                        : null;
            }
        }
        return null;
    }

    private static List<Descriptor> parseDescriptors(String value) {
        List<Descriptor> descriptors = new ArrayList<>();
        int index = 0;

        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '[') {
                ParsedName name = readName(value, index + 1);
                if (name == null || name.nextIndex() >= value.length()
                        || value.charAt(name.nextIndex()) != ']') {
                    return List.of();
                }
                descriptors.add(new Descriptor(name.value(), DescriptorSuffix.TYPE_PARAMETER));
                index = name.nextIndex() + 1;
                continue;
            }
            if (current == '(') {
                ParsedName name = readName(value, index + 1);
                if (name == null || name.nextIndex() >= value.length()
                        || value.charAt(name.nextIndex()) != ')') {
                    return List.of();
                }
                descriptors.add(new Descriptor(name.value(), DescriptorSuffix.PARAMETER));
                index = name.nextIndex() + 1;
                continue;
            }

            ParsedName name = readName(value, index);
            if (name == null) {
                return List.of();
            }
            if (name.nextIndex() == value.length()) {
                descriptors.add(new Descriptor(name.value(), DescriptorSuffix.LOCAL));
                break;
            }

            char suffix = value.charAt(name.nextIndex());
            DescriptorSuffix descriptorSuffix = switch (suffix) {
                case '/' -> DescriptorSuffix.NAMESPACE;
                case '#' -> DescriptorSuffix.TYPE;
                case '.' -> DescriptorSuffix.TERM;
                case ':' -> DescriptorSuffix.META;
                case '!' -> DescriptorSuffix.MACRO;
                default -> null;
            };
            if (descriptorSuffix != null) {
                descriptors.add(new Descriptor(name.value(), descriptorSuffix));
                index = name.nextIndex() + 1;
                continue;
            }
            if (suffix != '(') {
                return List.of();
            }

            int closingParenthesis = findUnescapedClosingParenthesis(value, name.nextIndex() + 1);
            if (closingParenthesis < 0 || closingParenthesis + 1 >= value.length()
                    || value.charAt(closingParenthesis + 1) != '.') {
                return List.of();
            }
            descriptors.add(new Descriptor(name.value(), DescriptorSuffix.METHOD));
            index = closingParenthesis + 2;
        }

        return List.copyOf(descriptors);
    }

    private static ParsedName readName(String value, int startIndex) {
        if (startIndex >= value.length()) {
            return null;
        }
        if (value.charAt(startIndex) != '`') {
            int index = startIndex;
            while (index < value.length() && isSimpleIdentifierCharacter(value.charAt(index))) {
                index++;
            }
            return index == startIndex
                    ? null
                    : new ParsedName(value.substring(startIndex, index), index);
        }

        StringBuilder name = new StringBuilder();
        int index = startIndex + 1;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current != '`') {
                name.append(current);
                index++;
                continue;
            }
            if (index + 1 < value.length() && value.charAt(index + 1) == '`') {
                name.append('`');
                index += 2;
                continue;
            }
            return name.isEmpty() ? null : new ParsedName(name.toString(), index + 1);
        }
        return null;
    }

    private static int findUnescapedClosingParenthesis(String value, int startIndex) {
        boolean escapedName = false;
        for (int index = startIndex; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '`') {
                if (escapedName && index + 1 < value.length() && value.charAt(index + 1) == '`') {
                    index++;
                } else {
                    escapedName = !escapedName;
                }
            } else if (current == ')' && !escapedName) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isSimpleIdentifierCharacter(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '_'
                || value == '+'
                || value == '-'
                || value == '$';
    }

    private static boolean isEcmaScript(String language, String rawSymbol) {
        String normalizedLanguage = language == null ? "" : language.toLowerCase(Locale.ROOT);
        return normalizedLanguage.equals("typescript")
                || normalizedLanguage.equals("javascript")
                || rawSymbol.startsWith("scip-typescript ");
    }

    private static int firstDescriptorAfterModuleFile(List<Descriptor> descriptors) {
        for (int index = 0; index < descriptors.size(); index++) {
            Descriptor descriptor = descriptors.get(index);
            if (descriptor.suffix() == DescriptorSuffix.NAMESPACE
                    && isEcmaScriptModuleFile(descriptor.name())) {
                return index + 1;
            }
        }
        return 0;
    }

    private static boolean isEcmaScriptModuleFile(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return ECMASCRIPT_MODULE_EXTENSIONS.stream().anyMatch(normalized::endsWith);
    }

    private static String normalizeConstructorName(String name, boolean ecmaScript) {
        return ecmaScript && name.equals("<constructor>") ? "constructor" : name;
    }

    private enum DescriptorSuffix {
        NAMESPACE,
        TYPE,
        TERM,
        METHOD,
        TYPE_PARAMETER,
        PARAMETER,
        META,
        LOCAL,
        MACRO
    }

    private record Descriptor(String name, DescriptorSuffix suffix) {
    }

    private record ParsedName(String value, int nextIndex) {
    }
}
