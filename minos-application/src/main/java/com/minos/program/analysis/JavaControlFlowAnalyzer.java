package com.minos.program.analysis;

import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramNodeKind;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.WhileLoopTree;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Conservative statement-level control-flow graph construction. */
final class JavaControlFlowAnalyzer {

    private final JavaProgramGraphContext context;

    JavaControlFlowAnalyzer(JavaProgramGraphContext context) {
        this.context = context;
    }

    void analyze(JavaParsedUnit unit, StatementTree statement) {
        new Builder(unit).build(statement);
    }

    private final class Builder {
        private final JavaParsedUnit unit;

        private Builder(JavaParsedUnit unit) {
            this.unit = unit;
        }

        JavaProgramModel.FlowFragment build(StatementTree statement) {
            if (statement == null) {
                return JavaProgramModel.FlowFragment.empty();
            }
            if (statement instanceof BlockTree block) {
                return sequence(block.getStatements());
            }
            if (statement instanceof IfTree tree) {
                return ifFlow(tree);
            }
            if (statement instanceof WhileLoopTree tree) {
                return whileFlow(tree);
            }
            if (statement instanceof ForLoopTree tree) {
                return forFlow(tree);
            }
            if (statement instanceof EnhancedForLoopTree tree) {
                return enhancedForFlow(tree);
            }
            if (statement instanceof DoWhileLoopTree tree) {
                return doWhileFlow(tree);
            }
            if (statement instanceof TryTree tree) {
                return tryFlow(tree);
            }
            if (statement instanceof SynchronizedTree tree) {
                return synchronizedFlow(tree);
            }
            if (statement instanceof LabeledStatementTree tree) {
                return build(tree.getStatement());
            }
            String node = basicBlock(statement);
            if (statement instanceof ReturnTree || statement instanceof ThrowTree) {
                return new JavaProgramModel.FlowFragment(node, Set.of());
            }
            switch (statement.getKind()) {
                case BREAK, CONTINUE, SWITCH, YIELD ->
                        context.addLimitation("JAVA_CFG_UNMODELED_CONTROL_TRANSFER_PRESENT");
                default -> {
                }
            }
            return new JavaProgramModel.FlowFragment(node, Set.of(node));
        }

        private JavaProgramModel.FlowFragment sequence(List<? extends StatementTree> statements) {
            String entry = null;
            Set<String> exits = Set.of();
            for (StatementTree statement : statements) {
                JavaProgramModel.FlowFragment next = build(statement);
                if (next.entry() == null) {
                    continue;
                }
                if (entry == null) {
                    entry = next.entry();
                } else {
                    for (String exit : exits) {
                        cfgEdge(exit, next.entry(), statement);
                    }
                }
                exits = next.exits();
                if (exits.isEmpty()) {
                    break;
                }
            }
            return new JavaProgramModel.FlowFragment(entry, exits);
        }

        private JavaProgramModel.FlowFragment ifFlow(IfTree tree) {
            String decision = basicBlock(tree);
            JavaProgramModel.FlowFragment thenFlow = build(tree.getThenStatement());
            if (thenFlow.entry() != null) {
                cfgEdge(decision, thenFlow.entry(), tree.getThenStatement());
            }
            JavaProgramModel.FlowFragment elseFlow = tree.getElseStatement() == null
                    ? JavaProgramModel.FlowFragment.empty()
                    : build(tree.getElseStatement());
            if (elseFlow.entry() != null) {
                cfgEdge(decision, elseFlow.entry(), tree.getElseStatement());
            }
            Set<String> exits = new LinkedHashSet<>(thenFlow.exits());
            if (tree.getElseStatement() == null) {
                exits.add(decision);
            } else {
                exits.addAll(elseFlow.exits());
            }
            return new JavaProgramModel.FlowFragment(decision, Set.copyOf(exits));
        }

        private JavaProgramModel.FlowFragment whileFlow(WhileLoopTree tree) {
            String condition = basicBlock(tree);
            JavaProgramModel.FlowFragment body = build(tree.getStatement());
            if (body.entry() != null) {
                cfgEdge(condition, body.entry(), tree.getStatement());
            }
            for (String exit : body.exits()) {
                cfgEdge(exit, condition, tree);
            }
            return new JavaProgramModel.FlowFragment(condition, Set.of(condition));
        }

        private JavaProgramModel.FlowFragment forFlow(ForLoopTree tree) {
            JavaProgramModel.FlowFragment initializer = sequence(tree.getInitializer());
            String condition = basicBlock(tree);
            if (initializer.entry() != null) {
                for (String exit : initializer.exits()) {
                    cfgEdge(exit, condition, tree);
                }
            }
            JavaProgramModel.FlowFragment body = build(tree.getStatement());
            JavaProgramModel.FlowFragment update = sequence(tree.getUpdate());
            if (body.entry() != null) {
                cfgEdge(condition, body.entry(), tree.getStatement());
            }
            if (update.entry() != null) {
                for (String exit : body.exits()) {
                    cfgEdge(exit, update.entry(), tree);
                }
                for (String exit : update.exits()) {
                    cfgEdge(exit, condition, tree);
                }
            } else {
                for (String exit : body.exits()) {
                    cfgEdge(exit, condition, tree);
                }
            }
            return new JavaProgramModel.FlowFragment(
                    initializer.entry() == null ? condition : initializer.entry(),
                    Set.of(condition));
        }

        private JavaProgramModel.FlowFragment enhancedForFlow(EnhancedForLoopTree tree) {
            String condition = basicBlock(tree);
            JavaProgramModel.FlowFragment body = build(tree.getStatement());
            if (body.entry() != null) {
                cfgEdge(condition, body.entry(), tree.getStatement());
            }
            for (String exit : body.exits()) {
                cfgEdge(exit, condition, tree);
            }
            return new JavaProgramModel.FlowFragment(condition, Set.of(condition));
        }

        private JavaProgramModel.FlowFragment doWhileFlow(DoWhileLoopTree tree) {
            JavaProgramModel.FlowFragment body = build(tree.getStatement());
            String condition = basicBlock(tree);
            for (String exit : body.exits()) {
                cfgEdge(exit, condition, tree);
            }
            if (body.entry() != null) {
                cfgEdge(condition, body.entry(), tree.getStatement());
            }
            return new JavaProgramModel.FlowFragment(
                    body.entry() == null ? condition : body.entry(),
                    Set.of(condition));
        }

        private JavaProgramModel.FlowFragment tryFlow(TryTree tree) {
            String header = basicBlock(tree);
            JavaProgramModel.FlowFragment body = build(tree.getBlock());
            if (body.entry() != null) {
                cfgEdge(header, body.entry(), tree.getBlock());
            }
            Set<String> exits = new LinkedHashSet<>(body.exits());
            for (CatchTree catchTree : tree.getCatches()) {
                JavaProgramModel.FlowFragment catchFlow = build(catchTree.getBlock());
                if (catchFlow.entry() != null) {
                    cfgEdge(header, catchFlow.entry(), catchTree.getBlock());
                }
                exits.addAll(catchFlow.exits());
            }
            context.addLimitation("JAVA_CFG_EXCEPTION_EDGES_CONSERVATIVE");
            if (tree.getFinallyBlock() != null) {
                JavaProgramModel.FlowFragment finallyFlow = build(tree.getFinallyBlock());
                if (finallyFlow.entry() != null) {
                    for (String exit : exits) {
                        cfgEdge(exit, finallyFlow.entry(), tree.getFinallyBlock());
                    }
                    exits = new LinkedHashSet<>(finallyFlow.exits());
                }
            }
            if (exits.isEmpty()) {
                exits.add(header);
            }
            return new JavaProgramModel.FlowFragment(header, Set.copyOf(exits));
        }

        private JavaProgramModel.FlowFragment synchronizedFlow(SynchronizedTree tree) {
            String header = basicBlock(tree);
            JavaProgramModel.FlowFragment body = build(tree.getBlock());
            if (body.entry() != null) {
                cfgEdge(header, body.entry(), tree.getBlock());
            }
            return new JavaProgramModel.FlowFragment(
                    header,
                    body.exits().isEmpty() ? Set.of(header) : body.exits());
        }

        private String basicBlock(Tree tree) {
            String id = context.id("bb", unit, tree, tree.getKind().name());
            context.addNode(context.factualNode(
                    id,
                    ProgramNodeKind.BASIC_BLOCK,
                    context.label("cfg " + tree.getKind().name(), unit, tree),
                    context.location(unit, tree)));
            return id;
        }

        private void cfgEdge(String source, String target, Tree tree) {
            context.addDerivedEdge(
                    "cfg",
                    source,
                    target,
                    ProgramEdgeKind.CONTROL_FLOW,
                    JavaProgramGraphContext.CFG_CONFIDENCE,
                    "control-flow edge derived from Java statement semantics",
                    context.location(unit, tree));
        }
    }
}
