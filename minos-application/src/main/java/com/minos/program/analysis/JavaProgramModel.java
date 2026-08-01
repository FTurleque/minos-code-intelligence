package com.minos.program.analysis;

import com.minos.domain.SymbolLocation;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;

import java.util.List;
import java.util.Set;

/** Internal immutable identities and explicitly mutable per-method collection slots. */
final class JavaProgramModel {

    private JavaProgramModel() {
    }

    record MethodKey(String name, int arity) {
    }

    record MethodInfo(
            JavaParsedUnit unit,
            MethodTree tree,
            List<String> parameterNodeIds,
            List<String> returnNodeIds
    ) {
    }

    record InvocationInfo(
            JavaParsedUnit unit,
            MethodInvocationTree tree,
            String name,
            List<String> argumentNodeIds,
            SymbolLocation location
    ) {
    }

    record FlowFragment(String entry, Set<String> exits) {
        static FlowFragment empty() {
            return new FlowFragment(null, Set.of());
        }
    }
}
