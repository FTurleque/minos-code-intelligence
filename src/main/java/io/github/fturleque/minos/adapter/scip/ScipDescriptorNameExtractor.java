package io.github.fturleque.minos.adapter.scip;

import java.util.Optional;

/**
 * Extrait uniquement le nom du dernier descripteur d'un identifiant SCIP global.
 *
 * <p>Ce repli volontairement minimal ne parse pas la grammaire SCIP complète. Il
 * permet de qualifier les indexeurs qui omettent {@code display_name}, tout en
 * gardant l'identifiant fournisseur brut dans {@code ProviderReference}.</p>
 */
final class ScipDescriptorNameExtractor {

    private static final String LOCAL_SYMBOL_PREFIX = "local ";

    private ScipDescriptorNameExtractor() {
    }

    static Optional<String> extract(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()
                || rawSymbol.startsWith(LOCAL_SYMBOL_PREFIX)
                || rawSymbol.endsWith("/")) {
            return Optional.empty();
        }

        String withoutSuffix = rawSymbol;
        if (withoutSuffix.endsWith("().")) {
            withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - 3);
        } else if (withoutSuffix.endsWith("#") || withoutSuffix.endsWith(".")) {
            withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - 1);
        }

        int separator = lastUnescapedSeparator(withoutSuffix);
        if (separator < 0) {
            return Optional.empty();
        }
        String descriptor = withoutSuffix.substring(separator + 1);
        if (descriptor.startsWith("(") && descriptor.endsWith(")")) {
            descriptor = descriptor.substring(1, descriptor.length() - 1);
        }
        if (descriptor.startsWith("`") && descriptor.endsWith("`") && descriptor.length() >= 2) {
            descriptor = descriptor.substring(1, descriptor.length() - 1).replace("``", "`");
        }

        return descriptor.isBlank() ? Optional.empty() : Optional.of(descriptor);
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
