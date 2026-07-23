package com.minos.cli;

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
