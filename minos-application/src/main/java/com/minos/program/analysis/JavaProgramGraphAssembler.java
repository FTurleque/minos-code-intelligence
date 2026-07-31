package com.minos.program.analysis;

import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramNodeKind;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates the decomposed Java analyses and derives the capability-honest graph envelope. */
final class JavaProgramGraphAssembler {

    private final JavaProgramGraphContext context;
    private final List<JavaParsedUnit> units;
    private final JavaSecurityRules securityRules;
    private final List<JavaProgramModel.MethodInfo> methods = new ArrayList<>();
    private final Map<JavaProgramModel.MethodKey, List<JavaProgramModel.MethodInfo>> methodIndex =
            new LinkedHashMap<>();
    private final List<JavaProgramModel.InvocationInfo> invocations = new ArrayList<>();

    JavaProgramGraphAssembler(
            JavaProgramGraphContext context,
            List<JavaParsedUnit> units,
            JavaSecurityRules securityRules
    ) {
        this.context = context;
        this.units = units;
        this.securityRules = securityRules;
    }

    ProgramGraph analyze() {
        context.addLimitation("JAVA_ADVANCED_PROVIDER_V1");
        context.addLimitation("JAVA_AST_PARSE_ONLY_TYPE_ATTRIBUTION_NOT_PROVEN");
        collectMethods();

        JavaDefUseAnalyzer defUse = new JavaDefUseAnalyzer(context, invocations);
        JavaControlFlowAnalyzer controlFlow = new JavaControlFlowAnalyzer(context);
        for (JavaProgramModel.MethodInfo method : methods) {
            if (method.tree().getBody() != null) {
                defUse.analyze(method);
                controlFlow.analyze(method.unit(), method.tree().getBody());
            }
        }

        new JavaInterproceduralFlowResolver(context).resolve(methodIndex, invocations);
        new JavaTaintAnalyzer(context, securityRules).analyze(methods);

        Set<ProgramGraphCapability> capabilities = EnumSet.noneOf(ProgramGraphCapability.class);
        Set<ProgramEdgeKind> kinds = EnumSet.noneOf(ProgramEdgeKind.class);
        context.edges().values().forEach(edge -> kinds.add(edge.kind()));
        if (kinds.contains(ProgramEdgeKind.CONTROL_FLOW)) {
            capabilities.add(ProgramGraphCapability.CONTROL_FLOW);
        }
        if (kinds.contains(ProgramEdgeKind.DEF_USE) || kinds.contains(ProgramEdgeKind.DATA_FLOW)) {
            capabilities.add(ProgramGraphCapability.LOCAL_DATA_FLOW);
            context.addLimitation("JAVA_LOCAL_DATA_FLOW_NAME_BASED_WITHIN_METHOD");
        }
        if (kinds.contains(ProgramEdgeKind.ARGUMENT_FLOW) || kinds.contains(ProgramEdgeKind.RETURN_FLOW)) {
            capabilities.add(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW);
            context.addLimitation("JAVA_INTERPROCEDURAL_UNIQUE_NAME_ARITY_ONLY");
        }
        boolean source = context.nodes().values().stream()
                .anyMatch(node -> node.kind() == ProgramNodeKind.SOURCE);
        boolean sink = context.nodes().values().stream()
                .anyMatch(node -> node.kind() == ProgramNodeKind.SINK);
        if (kinds.contains(ProgramEdgeKind.TAINT_FLOW) && source && sink) {
            capabilities.add(ProgramGraphCapability.SECURITY_TAINT);
            context.addLimitation("JAVA_SECURITY_FLOW_INTRAPROCEDURAL_CONFIGURED_RULES_ONLY");
        }
        if (!securityRules.configured()) {
            context.addLimitation("JAVA_SECURITY_RULES_NOT_CONFIGURED");
        }
        return context.toGraph(capabilities);
    }

    private void collectMethods() {
        for (JavaParsedUnit unit : units) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree tree, Void unused) {
                    JavaProgramModel.MethodInfo method = new JavaProgramModel.MethodInfo(
                            unit,
                            tree,
                            new ArrayList<>(),
                            new ArrayList<>());
                    methods.add(method);
                    String name = tree.getName().toString();
                    if (!"<init>".equals(name)) {
                        methodIndex.computeIfAbsent(
                                new JavaProgramModel.MethodKey(name, tree.getParameters().size()),
                                ignored -> new ArrayList<>()).add(method);
                    }
                    return super.visitMethod(tree, unused);
                }
            }.scan(unit.tree(), null);
        }
    }
}
