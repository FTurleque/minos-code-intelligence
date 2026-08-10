package com.minos.program.analysis;

import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.Symbol;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reuses normalized relationship facts already present in the active snapshot.
 * CALLS stays at its original nature; READS/WRITES are mapped to explicitly derived
 * potential data-flow edges because execution order is not proven by those facts.
 */
public final class RelationshipProgramGraphProvider implements ProgramGraphProvider {

    public static final String PROVIDER_ID = "minos-relationships";
    private static final ProgramGraphBudget DIRECT_CALL_BUDGET = new ProgramGraphBudget(100_000, 500_000);

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) {
        return analyze(project, snapshot, DIRECT_CALL_BUDGET);
    }

    @Override
    public ProgramGraph analyze(
            RegisteredProject project,
            CodeKnowledgeSnapshot snapshot,
            ProgramGraphBudget budget
    ) {
        String projectId = project.id().toString();
        List<Relationship> selected = new ArrayList<>(Math.min(snapshot.relationships().size(), budget.maxEdges()));
        Set<String> requiredSymbolIds = new LinkedHashSet<>();
        boolean edgeBudgetReached = false;

        for (Relationship relationship : snapshot.relationships()) {
            if (!resolvedSymbolToSymbol(relationship) || !supported(relationship.kind())) continue;
            if (selected.size() >= budget.maxEdges()) {
                edgeBudgetReached = true;
                break;
            }
            selected.add(relationship);
            requiredSymbolIds.add(relationship.source().id());
            requiredSymbolIds.add(relationship.target().id());
        }

        Map<String, Symbol> symbols = new LinkedHashMap<>();
        for (Symbol symbol : snapshot.symbols()) {
            if (requiredSymbolIds.contains(symbol.id())) {
                symbols.put(symbol.id(), symbol);
                if (symbols.size() == requiredSymbolIds.size()) break;
            }
        }

        Map<String, ProgramGraphNode> nodes = new LinkedHashMap<>();
        List<ProgramGraphEdge> edges = new ArrayList<>(selected.size());
        Set<ProgramGraphCapability> capabilities = new LinkedHashSet<>();
        Set<String> limitations = new LinkedHashSet<>();
        if (edgeBudgetReached) limitations.add("PROGRAM_GRAPH_EDGE_LIMIT_REACHED");

        boolean callObserved = false;
        boolean localFlowObserved = false;
        for (Relationship relationship : selected) {
            Symbol source = symbols.get(relationship.source().id());
            Symbol target = symbols.get(relationship.target().id());
            if (source == null || target == null) continue;
            if (!ensureNode(nodes, source, budget) || !ensureNode(nodes, target, budget)) {
                limitations.add("PROGRAM_GRAPH_NODE_LIMIT_REACHED");
                continue;
            }
            if (relationship.kind() == RelationshipKind.CALLS) {
                edges.add(new ProgramGraphEdge(
                        "call:" + relationship.id(), projectId, nodeId(source.id()), nodeId(target.id()),
                        ProgramEdgeKind.CALL, relationship.nature(), relationship.confidence(),
                        relationship.origin(), relationship.evidence()));
                callObserved = true;
            } else if (relationship.kind() == RelationshipKind.WRITES) {
                edges.add(derivedFlowEdge(snapshot.snapshotId(), relationship, source, target, true));
                localFlowObserved = true;
                limitations.add("EXECUTION_ORDER_NOT_PROVEN");
            } else if (relationship.kind() == RelationshipKind.READS) {
                edges.add(derivedFlowEdge(snapshot.snapshotId(), relationship, target, source, false));
                localFlowObserved = true;
                limitations.add("EXECUTION_ORDER_NOT_PROVEN");
            }
        }

        if (callObserved) capabilities.add(ProgramGraphCapability.CALL_GRAPH);
        if (localFlowObserved) capabilities.add(ProgramGraphCapability.LOCAL_DATA_FLOW);
        return new ProgramGraph(
                projectId,
                snapshot.snapshotId(),
                capabilities,
                nodes.values().stream().sorted(java.util.Comparator.comparing(ProgramGraphNode::id)).toList(),
                edges.stream().sorted(java.util.Comparator.comparing(ProgramGraphEdge::id)).toList(),
                limitations.stream().sorted().toList());
    }

    private static boolean supported(RelationshipKind kind) {
        return kind == RelationshipKind.CALLS || kind == RelationshipKind.WRITES || kind == RelationshipKind.READS;
    }

    private static ProgramGraphEdge derivedFlowEdge(
            String snapshotId,
            Relationship relationship,
            Symbol source,
            Symbol target,
            boolean write
    ) {
        double base = relationship.confidence() == null ? 1.0 : relationship.confidence();
        double confidence = Math.min(0.90, base * 0.90);
        Evidence derivation = new Evidence(
                EvidenceType.DERIVATION_PATH,
                (write ? "Potential data flow derived from WRITES relation " : "Potential data flow derived from READS relation ")
                        + relationship.id() + "; execution order is not proven",
                relationship.source(), relationship.target(), relationship.location(), confidence);
        List<Evidence> evidence = new ArrayList<>(relationship.evidence());
        evidence.add(derivation);
        Origin origin = new Origin(
                "minos-program-graph", "PROGRAM_GRAPH", "1", snapshotId, OriginType.DERIVED_BY_MINOS);
        return new ProgramGraphEdge(
                (write ? "write-flow:" : "read-flow:") + relationship.id(),
                relationship.projectId(),
                nodeId(source.id()),
                nodeId(target.id()),
                ProgramEdgeKind.DATA_FLOW,
                InformationNature.DERIVED,
                confidence,
                origin,
                evidence);
    }

    private static boolean resolvedSymbolToSymbol(Relationship relationship) {
        return relationship.target() != null
                && relationship.source().type() == CodeEntityType.SYMBOL
                && relationship.target().type() == CodeEntityType.SYMBOL;
    }

    private static boolean ensureNode(Map<String, ProgramGraphNode> nodes, Symbol symbol, ProgramGraphBudget budget) {
        String id = nodeId(symbol.id());
        if (nodes.containsKey(id)) return true;
        if (nodes.size() >= budget.maxNodes()) return false;
        nodes.put(id, new ProgramGraphNode(
                id, symbol.projectId(), symbol.id(), ProgramNodeKind.SYMBOL,
                symbol.qualifiedName() == null ? symbol.name() : symbol.qualifiedName(),
                symbol.location(), InformationNature.FACTUAL, null, symbol.origin(), List.of()));
        return true;
    }

    private static String nodeId(String symbolId) {
        return "symbol:" + symbolId;
    }
}
