package com.minos.program.analysis;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Shared deterministic AST helpers used by the analysis components. */
final class JavaAstSupport {

    private JavaAstSupport() {
    }

    static String invocationName(MethodInvocationTree tree) {
        ExpressionTree select = tree.getMethodSelect();
        if (select instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        if (select instanceof MemberSelectTree memberSelect) {
            return memberSelect.getIdentifier().toString();
        }
        return select.toString();
    }

    static Map<String, Set<String>> copyState(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        return copy;
    }

    static Map<String, Set<String>> mergeStates(
            Map<String, Set<String>> left,
            Map<String, Set<String>> right
    ) {
        Map<String, Set<String>> result = copyState(left);
        right.forEach((name, values) -> {
            Set<String> merged = new LinkedHashSet<>(result.getOrDefault(name, Set.of()));
            merged.addAll(values);
            result.put(name, Set.copyOf(merged));
        });
        return result;
    }

    static Set<String> safe(Set<String> values) {
        return values == null ? Set.of() : values;
    }

    static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(safe(left));
        result.addAll(safe(right));
        return Set.copyOf(result);
    }
}
