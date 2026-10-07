package com.minos.cli;

import com.minos.application.ProjectSymbolQuery;
import com.minos.domain.RelationshipDirection;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.query.RelationshipResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationshipCommandTest {

    @Test
    void mapsEveryRelationshipCommandToItsDirectionAndKind() throws IOException {
        Map<RelationshipCommand.Operation, Expected> expected = Map.of(
                RelationshipCommand.Operation.IMPLEMENTATIONS,
                new Expected(RelationshipDirection.INCOMING, RelationshipKind.IMPLEMENTS),
                RelationshipCommand.Operation.CALLERS,
                new Expected(RelationshipDirection.INCOMING, RelationshipKind.CALLS),
                RelationshipCommand.Operation.CALLEES,
                new Expected(RelationshipDirection.OUTGOING, RelationshipKind.CALLS),
                RelationshipCommand.Operation.DEPENDENCIES,
                new Expected(RelationshipDirection.OUTGOING, RelationshipKind.DEPENDS_ON),
                RelationshipCommand.Operation.DEPENDENTS,
                new Expected(RelationshipDirection.INCOMING, RelationshipKind.DEPENDS_ON),
                RelationshipCommand.Operation.RELATED_TESTS,
                new Expected(RelationshipDirection.INCOMING, RelationshipKind.RELATED_TEST)
        );

        for (var entry : expected.entrySet()) {
            AtomicReference<RelationshipSearchCriteria> captured = new AtomicReference<>();
            ProjectSymbolQuery query = new EmptyProjectQuery() {
                @Override
                public List<RelationshipResult> findRelationships(
                        String projectId,
                        RelationshipSearchCriteria criteria
                ) {
                    assertEquals("project-1", projectId);
                    captured.set(criteria);
                    return List.of();
                }
            };
            StringBuilder output = new StringBuilder();

            int code = new RelationshipCommand(entry.getKey(), query).run(
                    new String[]{"project-1", "symbol-1", "--limit", "9", "--format", "json"},
                    output,
                    new StringBuilder()
            );

            assertEquals(0, code);
            assertEquals(entry.getValue().direction(), captured.get().direction());
            assertEquals(java.util.Set.of(entry.getValue().kind()), captured.get().kinds());
            assertEquals(9, captured.get().limit());
            assertEquals("symbol-1", captured.get().anchor().id());
            assertEquals("{\"count\":0,\"relationships\":[]}\n", output.toString());
        }
    }

    /** MINOS-AUD-F01 : les appelants, appelés et dépendances déclarent la limite ; les implémentations restent inchangées. */
    @Test
    void declaresTheLimitationsOfTheKindAskedForAndNothingForImplementations() throws IOException {
        ProjectSymbolQuery query = new EmptyProjectQuery() {
            @Override
            public List<RelationshipResult> findRelationships(String projectId, RelationshipSearchCriteria criteria) {
                return List.of();
            }

            @Override
            public List<String> relationshipLimitations(String projectId, java.util.Set<RelationshipKind> kinds) {
                return kinds.contains(RelationshipKind.CALLS) ? List.of("CALL_RELATIONS_NOT_PRODUCED")
                        : kinds.contains(RelationshipKind.DEPENDS_ON) ? List.of("OCCURRENCE_REFERENCES_NOT_PROJECTED")
                        : List.of();
            }
        };

        for (var operation : List.of(RelationshipCommand.Operation.CALLERS, RelationshipCommand.Operation.CALLEES)) {
            StringBuilder json = new StringBuilder();
            new RelationshipCommand(operation, query).run(
                    new String[]{"project-1", "symbol-1", "--format", "json"}, json, new StringBuilder());
            assertEquals("{\"count\":0,\"relationships\":[],\"limitations\":[\"CALL_RELATIONS_NOT_PRODUCED\"]}\n",
                    json.toString(), operation.name());

            StringBuilder text = new StringBuilder();
            new RelationshipCommand(operation, query).run(
                    new String[]{"project-1", "symbol-1", "--format", "text"}, text, new StringBuilder());
            assertEquals("relationships: 0\nlimitations: [CALL_RELATIONS_NOT_PRODUCED]\n", text.toString(), operation.name());
        }

        StringBuilder dependents = new StringBuilder();
        new RelationshipCommand(RelationshipCommand.Operation.DEPENDENTS, query).run(
                new String[]{"project-1", "symbol-1", "--format", "json"}, dependents, new StringBuilder());
        assertTrue(dependents.toString().contains("\"limitations\":[\"OCCURRENCE_REFERENCES_NOT_PROJECTED\"]"));

        for (var operation : List.of(RelationshipCommand.Operation.IMPLEMENTATIONS, RelationshipCommand.Operation.RELATED_TESTS)) {
            StringBuilder output = new StringBuilder();
            new RelationshipCommand(operation, query).run(
                    new String[]{"project-1", "symbol-1", "--format", "json"}, output, new StringBuilder());
            assertEquals("{\"count\":0,\"relationships\":[]}\n", output.toString(), operation.name());
        }
    }

    @Test
    void exposesOperationSpecificHelpAndFailure() throws IOException {
        RelationshipCommand command = new RelationshipCommand(
                RelationshipCommand.Operation.CALLERS,
                new EmptyProjectQuery() {
                    @Override
                    public List<RelationshipResult> findRelationships(
                            String projectId,
                            RelationshipSearchCriteria criteria
                    ) {
                        throw new IllegalStateException("missing\nsnapshot");
                    }
                }
        );
        StringBuilder help = new StringBuilder();
        assertEquals(0, command.run(new String[]{"--help"}, help, new StringBuilder()));
        assertTrue(help.toString().startsWith("Usage: minos find-callers"));

        StringBuilder error = new StringBuilder();
        assertEquals(1, command.run(
                new String[]{"project-1", "symbol-1"},
                new StringBuilder(),
                error
        ));
        assertEquals("error: find-callers failed: missing snapshot\n", error.toString());
    }

    private record Expected(RelationshipDirection direction, RelationshipKind kind) {
    }

    private abstract static class EmptyProjectQuery implements ProjectSymbolQuery {
        @Override
        public List<com.minos.query.SymbolResult> findSymbols(
                String projectId,
                com.minos.domain.SymbolSearchCriteria criteria
        ) {
            return List.of();
        }
    }
}
