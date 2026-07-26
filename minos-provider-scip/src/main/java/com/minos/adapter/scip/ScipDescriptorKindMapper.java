package com.minos.adapter.scip;

import com.minos.domain.SymbolKind;

/**
 * Infers only the broad kinds encoded unambiguously by SCIP descriptors.
 *
 * <p>This fallback is intentionally narrower than a language-specific parser:
 * a type descriptor does not distinguish a class from an interface, and a term
 * descriptor does not distinguish a field from a property or variable.</p>
 */
final class ScipDescriptorKindMapper {

    private static final String LOCAL_SYMBOL_PREFIX = "local ";

    SymbolKind map(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()
                || rawSymbol.startsWith(LOCAL_SYMBOL_PREFIX)) {
            return SymbolKind.OTHER;
        }
        if (rawSymbol.endsWith("#")) {
            return SymbolKind.TYPE;
        }
        if (!rawSymbol.endsWith("().")) {
            return SymbolKind.OTHER;
        }

        String name = ScipDescriptorNameExtractor.extract(rawSymbol).orElse("");
        if ("<constructor>".equals(name)) {
            return SymbolKind.CONSTRUCTOR;
        }

        String withoutMethodSuffix = rawSymbol.substring(0, rawSymbol.length() - 3);
        int parentSeparator = lastUnescapedSeparator(withoutMethodSuffix);
        if (parentSeparator < 0) {
            return SymbolKind.OTHER;
        }
        return switch (withoutMethodSuffix.charAt(parentSeparator)) {
            case '#' -> SymbolKind.METHOD;
            case '/' -> SymbolKind.FUNCTION;
            default -> SymbolKind.OTHER;
        };
    }

    private static int lastUnescapedSeparator(String value) {
        boolean escaped = false;
        int lastSeparator = -1;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '`') {
                if (escaped && index + 1 < value.length() && value.charAt(index + 1) == '`') {
                    index++;
                    continue;
                }
                escaped = !escaped;
            } else if (!escaped && (current == '/' || current == '#' || current == '.')) {
                lastSeparator = index;
            }
        }
        return lastSeparator;
    }
}
