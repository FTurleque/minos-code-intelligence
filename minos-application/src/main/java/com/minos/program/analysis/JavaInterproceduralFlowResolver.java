package com.minos.program.analysis;

import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramNodeKind;

import java.util.List;
import java.util.Map;

/** Resolves only unique project-local simple-name and arity matches. */
final class JavaInterproceduralFlowResolver {

    private final JavaProgramGraphContext context;

    JavaInterproceduralFlowResolver(JavaProgramGraphContext context) {
        this.context = context;
    }

    void resolve(
            Map<JavaProgramModel.MethodKey, List<JavaProgramModel.MethodInfo>> methodIndex,
            List<JavaProgramModel.InvocationInfo> invocations
    ) {
        boolean unresolved = false;
        for (JavaProgramModel.InvocationInfo invocation : invocations) {
            List<JavaProgramModel.MethodInfo> candidates = methodIndex.getOrDefault(
                    new JavaProgramModel.MethodKey(
                            invocation.name(),
                            invocation.argumentNodeIds().size()),
                    List.of());
            if (candidates.size() != 1) {
                unresolved = true;
                continue;
            }
            JavaProgramModel.MethodInfo target = candidates.getFirst();
            for (int index = 0; index < invocation.argumentNodeIds().size(); index++) {
                if (index >= target.parameterNodeIds().size()) {
                    break;
                }
                context.addDerivedEdge(
                        "argument-flow",
                        invocation.argumentNodeIds().get(index),
                        target.parameterNodeIds().get(index),
                        ProgramEdgeKind.ARGUMENT_FLOW,
                        JavaProgramGraphContext.INTERPROCEDURAL_CONFIDENCE,
                        "argument mapped to the unique project method with matching simple name and arity",
                        invocation.location());
            }
            if (!target.returnNodeIds().isEmpty()) {
                String resultId = context.id(
                        "call-result",
                        invocation.unit(),
                        invocation.tree(),
                        invocation.name());
                context.addNode(context.factualNode(
                        resultId,
                        ProgramNodeKind.RETURN_VALUE,
                        context.label(
                                "call-result " + invocation.name(),
                                invocation.unit(),
                                invocation.tree()),
                        invocation.location()));
                for (String returnNode : target.returnNodeIds()) {
                    context.addDerivedEdge(
                            "return-flow",
                            returnNode,
                            resultId,
                            ProgramEdgeKind.RETURN_FLOW,
                            JavaProgramGraphContext.INTERPROCEDURAL_CONFIDENCE,
                            "return mapped from the unique project method with matching simple name and arity",
                            invocation.location());
                }
            }
        }
        if (unresolved) {
            context.addLimitation("JAVA_INTERPROCEDURAL_EXTERNAL_OR_AMBIGUOUS_CALLS_SKIPPED");
        }
    }
}
