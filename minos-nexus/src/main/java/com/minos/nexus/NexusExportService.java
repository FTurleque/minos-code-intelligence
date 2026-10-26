package com.minos.nexus;

import com.minos.application.MinosApplication;
import com.minos.application.export.ExportedRelationship;
import com.minos.application.export.ExportedSymbol;
import com.minos.application.export.KnowledgeExport;
import com.minos.application.export.KnowledgeExportUseCase;
import com.minos.domain.Evidence;
import com.minos.domain.Origin;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.minos.nexus.NexusExportContract.CONTRACT_VERSION;
import static com.minos.nexus.NexusExportContract.PRODUCER;

/**
 * Read-only projection of the active MINOS knowledge snapshot into the NEXUS export contract v1. The selection is
 * made by {@link KnowledgeExportUseCase}; this adapter only translates its result into the contract.
 */
public final class NexusExportService {
    public static final int MAX_EXPORTED_SYMBOLS = KnowledgeExportUseCase.MAX_EXPORTED_SYMBOLS;
    public static final int MAX_EXPORTED_RELATIONS = KnowledgeExportUseCase.MAX_EXPORTED_RELATIONS;
    public static final int MAX_FILE_PATH_CANDIDATES = KnowledgeExportUseCase.MAX_FILE_PATH_CANDIDATES;
    public static final long MAX_SYMBOL_SELECTION_WEIGHT_BYTES = KnowledgeExportUseCase.MAX_SYMBOL_SELECTION_WEIGHT_BYTES;
    public static final long MAX_RELATION_SELECTION_WEIGHT_BYTES =
            KnowledgeExportUseCase.MAX_RELATION_SELECTION_WEIGHT_BYTES;

    private final KnowledgeExportUseCase exports;

    public NexusExportService(MinosApplication application) {
        this.exports = KnowledgeExportUseCase.of(Objects.requireNonNull(application, "application"));
    }

    public NexusExportContract.ExportSnapshot export(Path projectRoot) throws IOException {
        KnowledgeExport export = exports.export(projectRoot);
        Map<String, NexusExportContract.ExportSymbol> symbolsById = new HashMap<>();
        List<NexusExportContract.ExportSymbol> symbols = export.symbols().stream().map(exported -> {
            NexusExportContract.ExportSymbol symbol = symbol(exported);
            symbolsById.put(symbol.id(), symbol);
            return symbol;
        }).toList();
        List<NexusExportContract.ExportRelation> relations = export.relationships().stream()
                .map(exported -> relation(exported, symbolsById)).toList();
        return new NexusExportContract.ExportSnapshot(CONTRACT_VERSION, PRODUCER,
                new NexusExportContract.ExportProject(export.projectId(), export.displayName(),
                        export.rootPath().toString(), export.snapshotId()),
                symbols, relations, export.limitations());
    }

    private static NexusExportContract.ExportSymbol symbol(ExportedSymbol exported) {
        Symbol symbol = exported.symbol();
        return new NexusExportContract.ExportSymbol(
                symbol.id(), symbol.symbolKey(), exported.filePath(),
                symbol.moduleId(), symbol.kind().name(), symbol.name(), textOr(symbol.qualifiedName(), symbol.name()),
                textOr(symbol.signature(), ""), symbol.language(), symbol.location().startLine(),
                symbol.location().endLine(), symbol.resolutionStatus().name(), symbol.identityQuality().name(),
                symbol.generated(), origin(symbol.origin()));
    }

    private static NexusExportContract.ExportRelation relation(
            ExportedRelationship exported,
            Map<String, NexusExportContract.ExportSymbol> symbolsById
    ) {
        Relationship relationship = exported.relationship();
        NexusExportContract.ExportSymbol source = symbolsById.get(relationship.source().id());
        NexusExportContract.ExportSymbol target = symbolsById.get(relationship.target().id());
        return new NexusExportContract.ExportRelation(
                relationship.id(), exported.filePath(), relationship.kind().name(),
                source.id(), source.qualifiedName(), target.id(), target.qualifiedName(),
                relationship.resolutionStatus().name(), relationship.nature().name(), relationship.confidence(),
                origin(relationship.origin()), relationship.evidence().stream().map(NexusExportService::evidence).toList());
    }

    private static NexusExportContract.ExportOrigin origin(Origin origin) {
        return new NexusExportContract.ExportOrigin(origin.providerId(), origin.providerType(), origin.providerVersion(),
                origin.indexRunId(), origin.sourceType().name());
    }

    private static NexusExportContract.ExportEvidence evidence(Evidence evidence) {
        return new NexusExportContract.ExportEvidence(evidence.type().name(), evidence.description(), evidence.weight());
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
