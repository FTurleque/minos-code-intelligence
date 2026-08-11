package com.minos.program.analysis;

import com.sun.source.tree.MethodInvocationTree;
import com.minos.io.BoundedProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Loads and evaluates the explicit M22 source/sink/sanitizer rule set. */
record JavaSecurityRules(
        boolean configured,
        Set<String> sources,
        Set<String> sinks,
        Set<String> sanitizers
) {
    static JavaSecurityRules load(Path root) throws IOException {
        Optional<Path> config = JavaSourceWorkspace.securityConfig(root);
        if (config.isEmpty()) {
            return new JavaSecurityRules(false, Set.of(), Set.of(), Set.of());
        }
        Properties properties = BoundedProperties.load(
                config.orElseThrow(), 1024L * 1024L, 16, 64, 256 * 1024,
                "Java security analysis rules");
        return new JavaSecurityRules(
                true,
                tokens(properties.getProperty("sources", "")),
                tokens(properties.getProperty("sinks", "")),
                tokens(properties.getProperty("sanitizers", "")));
    }

    boolean source(MethodInvocationTree tree) {
        return matches(sources, tree);
    }

    boolean sink(MethodInvocationTree tree) {
        return matches(sinks, tree);
    }

    boolean sanitizer(MethodInvocationTree tree) {
        return matches(sanitizers, tree);
    }

    private static Set<String> tokens(String raw) {
        Set<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String value = token.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return Set.copyOf(values);
    }

    private static boolean matches(Set<String> rules, MethodInvocationTree tree) {
        return rules.contains(tree.getMethodSelect().toString())
                || rules.contains(JavaAstSupport.invocationName(tree));
    }
}
