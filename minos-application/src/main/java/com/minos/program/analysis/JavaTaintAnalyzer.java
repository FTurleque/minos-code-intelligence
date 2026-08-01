package com.minos.program.analysis;

import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.SymbolLocation;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Intraprocedural configured-rule taint analysis with conservative unknown-call handling. */
final class JavaTaintAnalyzer {

    private final JavaProgramGraphContext context;
    private final JavaSecurityRules securityRules;

    JavaTaintAnalyzer(
            JavaProgramGraphContext context,
            JavaSecurityRules securityRules
    ) {
        this.context = context;
        this.securityRules = securityRules;
    }

    void analyze(List<JavaProgramModel.MethodInfo> methods) {
        if (!securityRules.configured()) {
            return;
        }
        for (JavaProgramModel.MethodInfo method : methods) {
            if (method.tree().getBody() != null) {
                new Scanner(method.unit()).scan(method.tree().getBody(), null);
            }
        }
    }

    private final class Scanner extends TreeScanner<Set<String>, Void> {
        private final JavaParsedUnit unit;
        private final Map<String, Set<String>> taint = new LinkedHashMap<>();

        private Scanner(JavaParsedUnit unit) {
            this.unit = unit;
        }

        @Override
        public Set<String> visitVariable(VariableTree tree, Void unused) {
            Set<String> value = tree.getInitializer() == null
                    ? Set.of()
                    : JavaAstSupport.safe(scan(tree.getInitializer(), null));
            taint.put(tree.getName().toString(), value);
            return Set.of();
        }

        @Override
        public Set<String> visitAssignment(AssignmentTree tree, Void unused) {
            Set<String> value = JavaAstSupport.safe(scan(tree.getExpression(), null));
            if (tree.getVariable() instanceof IdentifierTree identifier) {
                taint.put(identifier.getName().toString(), value);
            }
            return value;
        }

        @Override
        public Set<String> visitIdentifier(IdentifierTree tree, Void unused) {
            return taint.getOrDefault(tree.getName().toString(), Set.of());
        }

        @Override
        public Set<String> visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            Set<String> incoming = new LinkedHashSet<>();
            for (ExpressionTree argument : tree.getArguments()) {
                incoming.addAll(JavaAstSupport.safe(scan(argument, null)));
            }
            String name = JavaAstSupport.invocationName(tree);
            if (securityRules.source(tree)) {
                return Set.of(securityNode("source", ProgramNodeKind.SOURCE, name, tree));
            }
            if (securityRules.sanitizer(tree)) {
                String sanitizer = securityNode("sanitizer", ProgramNodeKind.SANITIZER, name, tree);
                for (String source : incoming) {
                    securityEdge(
                            source,
                            sanitizer,
                            tree,
                            "configured sanitizer receives an observed tainted value");
                }
                return incoming.isEmpty() ? Set.of() : Set.of(sanitizer);
            }
            if (securityRules.sink(tree)) {
                String sink = securityNode("sink", ProgramNodeKind.SINK, name, tree);
                for (String source : incoming) {
                    securityEdge(
                            source,
                            sink,
                            tree,
                            "configured sink receives an observed tainted value");
                }
                return Set.of();
            }
            if (!incoming.isEmpty()) {
                context.addLimitation("JAVA_SECURITY_UNKNOWN_CALL_STOPS_FLOW");
            }
            return Set.of();
        }

        @Override
        public Set<String> visitParenthesized(ParenthesizedTree tree, Void unused) {
            return JavaAstSupport.safe(scan(tree.getExpression(), null));
        }

        @Override
        public Set<String> visitTypeCast(TypeCastTree tree, Void unused) {
            return JavaAstSupport.safe(scan(tree.getExpression(), null));
        }

        @Override
        public Set<String> visitConditionalExpression(ConditionalExpressionTree tree, Void unused) {
            return JavaAstSupport.union(
                    scan(tree.getTrueExpression(), null),
                    scan(tree.getFalseExpression(), null));
        }

        @Override
        public Set<String> visitBinary(BinaryTree tree, Void unused) {
            return JavaAstSupport.union(
                    scan(tree.getLeftOperand(), null),
                    scan(tree.getRightOperand(), null));
        }

        @Override
        public Set<String> visitIf(IfTree tree, Void unused) {
            scan(tree.getCondition(), null);
            Map<String, Set<String>> before = JavaAstSupport.copyState(taint);
            taint.clear();
            taint.putAll(JavaAstSupport.copyState(before));
            scan(tree.getThenStatement(), null);
            Map<String, Set<String>> thenState = JavaAstSupport.copyState(taint);
            taint.clear();
            taint.putAll(JavaAstSupport.copyState(before));
            if (tree.getElseStatement() != null) {
                scan(tree.getElseStatement(), null);
            }
            Map<String, Set<String>> elseState = JavaAstSupport.copyState(taint);
            taint.clear();
            taint.putAll(JavaAstSupport.mergeStates(thenState, elseState));
            return Set.of();
        }

        @Override
        public Set<String> visitWhileLoop(WhileLoopTree tree, Void unused) {
            scan(tree.getCondition(), null);
            Map<String, Set<String>> before = JavaAstSupport.copyState(taint);
            scan(tree.getStatement(), null);
            Map<String, Set<String>> after = JavaAstSupport.copyState(taint);
            taint.clear();
            taint.putAll(JavaAstSupport.mergeStates(before, after));
            context.addLimitation("JAVA_SECURITY_LOOP_FLOW_CONSERVATIVE");
            return Set.of();
        }

        @Override
        public Set<String> visitForLoop(ForLoopTree tree, Void unused) {
            for (StatementTree initializer : tree.getInitializer()) {
                scan(initializer, null);
            }
            if (tree.getCondition() != null) {
                scan(tree.getCondition(), null);
            }
            Map<String, Set<String>> before = JavaAstSupport.copyState(taint);
            scan(tree.getStatement(), null);
            for (ExpressionStatementTree update : tree.getUpdate()) {
                scan(update, null);
            }
            Map<String, Set<String>> after = JavaAstSupport.copyState(taint);
            taint.clear();
            taint.putAll(JavaAstSupport.mergeStates(before, after));
            context.addLimitation("JAVA_SECURITY_LOOP_FLOW_CONSERVATIVE");
            return Set.of();
        }

        @Override
        public Set<String> visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
            scan(tree.getExpression(), null);
            Map<String, Set<String>> before = JavaAstSupport.copyState(taint);
            scan(tree.getStatement(), null);
            Map<String, Set<String>> after = JavaAstSupport.copyState(taint);
            taint.clear();
            taint.putAll(JavaAstSupport.mergeStates(before, after));
            context.addLimitation("JAVA_SECURITY_LOOP_FLOW_CONSERVATIVE");
            return Set.of();
        }

        @Override
        public Set<String> visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
            Map<String, Set<String>> before = JavaAstSupport.copyState(taint);
            scan(tree.getStatement(), null);
            scan(tree.getCondition(), null);
            Map<String, Set<String>> after = JavaAstSupport.copyState(taint);
            taint.clear();
            taint.putAll(JavaAstSupport.mergeStates(before, after));
            context.addLimitation("JAVA_SECURITY_LOOP_FLOW_CONSERVATIVE");
            return Set.of();
        }

        @Override
        public Set<String> visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
            context.addLimitation("JAVA_SECURITY_LAMBDA_FLOW_NOT_MODELED");
            return Set.of();
        }

        @Override
        public Set<String> visitClass(ClassTree tree, Void unused) {
            context.addLimitation("JAVA_SECURITY_LOCAL_CLASS_FLOW_NOT_MODELED");
            return Set.of();
        }

        @Override
        public Set<String> reduce(Set<String> left, Set<String> right) {
            return JavaAstSupport.union(left, right);
        }

        private String securityNode(
                String prefix,
                ProgramNodeKind kind,
                String name,
                Tree tree
        ) {
            String id = context.id(prefix, unit, tree, name);
            SymbolLocation location = context.location(unit, tree);
            Evidence evidence = new Evidence(
                    EvidenceType.PROVIDER_FACT,
                    "Java invocation matched explicit M22 security rule: " + name,
                    null,
                    null,
                    location,
                    1.0);
            context.addNode(new ProgramGraphNode(
                    id,
                    context.projectId(),
                    null,
                    kind,
                    context.label(prefix + " " + name, unit, tree),
                    location,
                    InformationNature.DERIVED,
                    1.0,
                    context.derivedOrigin(),
                    List.of(evidence)));
            return id;
        }

        private void securityEdge(
                String source,
                String target,
                Tree tree,
                String description
        ) {
            context.addDerivedEdge(
                    "taint",
                    source,
                    target,
                    ProgramEdgeKind.TAINT_FLOW,
                    JavaProgramGraphContext.SECURITY_CONFIDENCE,
                    description,
                    context.location(unit, tree));
        }
    }
}
