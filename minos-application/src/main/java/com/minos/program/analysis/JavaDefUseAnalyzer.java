package com.minos.program.analysis;

import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramNodeKind;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Name-based intraprocedural def-use analysis and invocation collection. */
final class JavaDefUseAnalyzer {

    private final JavaProgramGraphContext context;
    private final List<JavaProgramModel.InvocationInfo> invocations;

    JavaDefUseAnalyzer(
            JavaProgramGraphContext context,
            List<JavaProgramModel.InvocationInfo> invocations
    ) {
        this.context = context;
        this.invocations = invocations;
    }

    void analyze(JavaProgramModel.MethodInfo method) {
        Map<String, Set<String>> definitions = new LinkedHashMap<>();
        int index = 0;
        for (VariableTree parameter : method.tree().getParameters()) {
            String name = parameter.getName().toString();
            String id = context.id(
                    "param",
                    method.unit(),
                    parameter,
                    method.tree().getName() + ":" + index + ":" + name);
            context.addNode(context.factualNode(
                    id,
                    ProgramNodeKind.PARAMETER,
                    context.label(
                            "parameter " + method.tree().getName() + "[" + index + "] " + name,
                            method.unit(),
                            parameter),
                    context.location(method.unit(), parameter)));
            method.parameterNodeIds().add(id);
            definitions.put(name, Set.of(id));
            index++;
        }
        new Scanner(method, definitions).scan(method.tree().getBody(), null);
    }

    private final class Scanner extends TreeScanner<Void, Void> {
        private final JavaProgramModel.MethodInfo method;
        private final Map<String, Set<String>> definitions;

        private Scanner(
                JavaProgramModel.MethodInfo method,
                Map<String, Set<String>> definitions
        ) {
            this.method = method;
            this.definitions = definitions;
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            if (tree.getInitializer() != null) {
                scan(tree.getInitializer(), null);
            }
            String name = tree.getName().toString();
            definitions.put(name, Set.of(context.definitionNode(method.unit(), tree, name)));
            return null;
        }

        @Override
        public Void visitAssignment(AssignmentTree tree, Void unused) {
            scan(tree.getExpression(), null);
            if (tree.getVariable() instanceof IdentifierTree identifier) {
                String name = identifier.getName().toString();
                definitions.put(name, Set.of(context.definitionNode(method.unit(), tree.getVariable(), name)));
            } else {
                context.addLimitation("JAVA_LOCAL_DATA_FLOW_FIELDS_NOT_MODELED");
                scan(tree.getVariable(), null);
            }
            return null;
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree tree, Void unused) {
            scan(tree.getVariable(), null);
            scan(tree.getExpression(), null);
            if (tree.getVariable() instanceof IdentifierTree identifier) {
                String name = identifier.getName().toString();
                definitions.put(name, Set.of(context.definitionNode(method.unit(), tree.getVariable(), name)));
            } else {
                context.addLimitation("JAVA_LOCAL_DATA_FLOW_FIELDS_NOT_MODELED");
            }
            return null;
        }

        @Override
        public Void visitUnary(UnaryTree tree, Void unused) {
            scan(tree.getExpression(), null);
            switch (tree.getKind()) {
                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> {
                    if (tree.getExpression() instanceof IdentifierTree identifier) {
                        String name = identifier.getName().toString();
                        definitions.put(name, Set.of(context.definitionNode(
                                method.unit(), tree.getExpression(), name)));
                    }
                }
                default -> {
                }
            }
            return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            String name = tree.getName().toString();
            Set<String> sources = definitions.get(name);
            if (sources == null || sources.isEmpty()) {
                return null;
            }
            String useId = context.id("use", method.unit(), tree, name);
            context.addNode(context.factualNode(
                    useId,
                    ProgramNodeKind.VARIABLE,
                    context.label("use " + name, method.unit(), tree),
                    context.location(method.unit(), tree)));
            for (String source : sources) {
                context.addDerivedEdge(
                        "def-use",
                        source,
                        useId,
                        ProgramEdgeKind.DEF_USE,
                        JavaProgramGraphContext.DEF_USE_CONFIDENCE,
                        "lexical Java definition reaches identifier use inside the same method",
                        context.location(method.unit(), tree));
            }
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            String name = JavaAstSupport.invocationName(tree);
            List<String> argumentIds = new ArrayList<>();
            int index = 0;
            for (ExpressionTree argument : tree.getArguments()) {
                String argumentId = context.id("arg", method.unit(), argument, name + ":" + index);
                context.addNode(context.factualNode(
                        argumentId,
                        ProgramNodeKind.PARAMETER,
                        context.label(
                                "argument " + name + "[" + index + "]",
                                method.unit(),
                                argument),
                        context.location(method.unit(), argument)));
                argumentIds.add(argumentId);
                scan(argument, null);
                index++;
            }
            if (tree.getMethodSelect() instanceof MemberSelectTree memberSelect) {
                scan(memberSelect.getExpression(), null);
            }
            invocations.add(new JavaProgramModel.InvocationInfo(
                    method.unit(),
                    tree,
                    name,
                    List.copyOf(argumentIds),
                    context.location(method.unit(), tree)));
            return null;
        }

        @Override
        public Void visitReturn(ReturnTree tree, Void unused) {
            if (tree.getExpression() != null) {
                scan(tree.getExpression(), null);
            }
            String id = context.id(
                    "return",
                    method.unit(),
                    tree,
                    method.tree().getName().toString());
            context.addNode(context.factualNode(
                    id,
                    ProgramNodeKind.RETURN_VALUE,
                    context.label("return " + method.tree().getName(), method.unit(), tree),
                    context.location(method.unit(), tree)));
            method.returnNodeIds().add(id);
            return null;
        }

        @Override
        public Void visitIf(IfTree tree, Void unused) {
            scan(tree.getCondition(), null);
            Map<String, Set<String>> before = JavaAstSupport.copyState(definitions);
            definitions.clear();
            definitions.putAll(JavaAstSupport.copyState(before));
            scan(tree.getThenStatement(), null);
            Map<String, Set<String>> thenState = JavaAstSupport.copyState(definitions);
            definitions.clear();
            definitions.putAll(JavaAstSupport.copyState(before));
            if (tree.getElseStatement() != null) {
                scan(tree.getElseStatement(), null);
            }
            Map<String, Set<String>> elseState = JavaAstSupport.copyState(definitions);
            definitions.clear();
            definitions.putAll(JavaAstSupport.mergeStates(thenState, elseState));
            return null;
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
            scan(tree.getCondition(), null);
            Map<String, Set<String>> before = JavaAstSupport.copyState(definitions);
            scan(tree.getStatement(), null);
            Map<String, Set<String>> afterBody = JavaAstSupport.copyState(definitions);
            definitions.clear();
            definitions.putAll(JavaAstSupport.mergeStates(before, afterBody));
            context.addLimitation("JAVA_LOCAL_DATA_FLOW_LOOP_FIXPOINT_CONSERVATIVE");
            return null;
        }

        @Override
        public Void visitForLoop(ForLoopTree tree, Void unused) {
            for (StatementTree initializer : tree.getInitializer()) {
                scan(initializer, null);
            }
            if (tree.getCondition() != null) {
                scan(tree.getCondition(), null);
            }
            Map<String, Set<String>> before = JavaAstSupport.copyState(definitions);
            scan(tree.getStatement(), null);
            for (ExpressionStatementTree update : tree.getUpdate()) {
                scan(update, null);
            }
            Map<String, Set<String>> afterBody = JavaAstSupport.copyState(definitions);
            definitions.clear();
            definitions.putAll(JavaAstSupport.mergeStates(before, afterBody));
            context.addLimitation("JAVA_LOCAL_DATA_FLOW_LOOP_FIXPOINT_CONSERVATIVE");
            return null;
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
            scan(tree.getExpression(), null);
            Map<String, Set<String>> before = JavaAstSupport.copyState(definitions);
            String name = tree.getVariable().getName().toString();
            definitions.put(name, Set.of(context.definitionNode(method.unit(), tree.getVariable(), name)));
            scan(tree.getStatement(), null);
            Map<String, Set<String>> afterBody = JavaAstSupport.copyState(definitions);
            definitions.clear();
            definitions.putAll(JavaAstSupport.mergeStates(before, afterBody));
            context.addLimitation("JAVA_LOCAL_DATA_FLOW_ENHANCED_FOR_ELEMENT_BINDING_NOT_PROVEN");
            return null;
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
            Map<String, Set<String>> before = JavaAstSupport.copyState(definitions);
            scan(tree.getStatement(), null);
            scan(tree.getCondition(), null);
            Map<String, Set<String>> afterBody = JavaAstSupport.copyState(definitions);
            definitions.clear();
            definitions.putAll(JavaAstSupport.mergeStates(before, afterBody));
            context.addLimitation("JAVA_LOCAL_DATA_FLOW_LOOP_FIXPOINT_CONSERVATIVE");
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
            context.addLimitation("JAVA_LOCAL_DATA_FLOW_NESTED_LAMBDA_NOT_MODELED");
            return null;
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            context.addLimitation("JAVA_LOCAL_DATA_FLOW_LOCAL_CLASS_NOT_MODELED");
            return null;
        }
    }
}
