package com.minos.program.analysis;

import com.minos.program.ProgramGraph;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Orchestrates source discovery, AST parsing and graph assembly without owning analysis details. */
final class JavaProgramGraphEngine {

    private final JavaAstParser parser;

    JavaProgramGraphEngine() {
        this(new JavaAstParser());
    }

    JavaProgramGraphEngine(JavaAstParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    String cacheKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(snapshot, "snapshot");
        JavaSourceWorkspace.Discovery discovery = JavaSourceWorkspace.discover(project, snapshot);
        return JavaSourceProgramGraphProvider.PROVIDER_ID + ":"
                + JavaSourceWorkspace.stateFingerprint(project, snapshot, discovery);
    }

    ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(snapshot, "snapshot");
        String projectId = project.id().toString();
        JavaSourceWorkspace.Discovery discovery = JavaSourceWorkspace.discover(project, snapshot);
        if (!discovery.usable()) {
            return empty(projectId, snapshot.snapshotId(), discovery.limitation());
        }

        JavaAstParser.ParseResult parsed = parser.parse(discovery);
        if (!parsed.successful()) {
            return empty(projectId, snapshot.snapshotId(), parsed.limitation());
        }

        String state = JavaSourceWorkspace.stateFingerprint(project, snapshot, discovery);
        String runId = snapshot.snapshotId() + ":" + state.substring(0, 16);
        JavaSecurityRules rules = JavaSecurityRules.load(project.rootPath());
        JavaProgramGraphContext context = new JavaProgramGraphContext(
                projectId,
                snapshot.snapshotId(),
                parsed.positions(),
                runId);
        return new JavaProgramGraphAssembler(context, parsed.units(), rules).analyze();
    }

    private static ProgramGraph empty(String projectId, String snapshotId, String limitation) {
        return new ProgramGraph(projectId, snapshotId, Set.of(), List.of(), List.of(), List.of(limitation));
    }
}
