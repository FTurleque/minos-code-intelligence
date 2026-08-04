package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsCommandTest {

    @Test
    void verifyKeepsHistoricalRequiredOnlyBehavior() throws Exception {
        ToolsCommand command = new ToolsCommand(operations(List.of(
                provider("required", true, "READY"),
                provider("optional", false, "BLOCKED")
        )));

        int exit = command.run(new String[]{"verify", "--format", "json"}, new StringBuilder(), new StringBuilder());

        assertEquals(FindSymbolCommand.SUCCESS, exit);
    }

    @Test
    void verifyAllFailsWhenAnyAdvertisedProviderIsNotReady() throws Exception {
        ToolsCommand command = new ToolsCommand(operations(List.of(
                provider("required", true, "READY"),
                provider("optional", false, "BLOCKED")
        )));
        StringBuilder output = new StringBuilder();

        int exit = command.run(new String[]{"verify", "--all", "--format", "json"}, output, new StringBuilder());

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, exit);
        assertTrue(output.toString().contains("\"id\":\"optional\""));
        assertTrue(output.toString().contains("\"state\":\"BLOCKED\""));
    }

    @Test
    void verifyAllPassesOnlyWhenEveryAdvertisedProviderIsReady() throws Exception {
        ToolsCommand command = new ToolsCommand(operations(List.of(
                provider("required", true, "READY"),
                provider("optional", false, "READY")
        )));

        int exit = command.run(new String[]{"verify", "--all"}, new StringBuilder(), new StringBuilder());

        assertEquals(FindSymbolCommand.SUCCESS, exit);
    }

    @Test
    void allOptionIsRejectedOutsideVerify() throws Exception {
        ToolsCommand command = new ToolsCommand(operations(List.of(provider("required", true, "READY"))));
        StringBuilder error = new StringBuilder();

        int exit = command.run(new String[]{"list", "--all"}, new StringBuilder(), error);

        assertEquals(FindSymbolCommand.USAGE_ERROR, exit);
        assertTrue(error.toString().contains("--all is only valid with tools verify"));
    }

    private static AutonomousIndexOperations.ProviderView provider(String id, boolean required, String state) {
        return new AutonomousIndexOperations.ProviderView(id, "1.0", state, null, List.of(), required);
    }

    private static AutonomousIndexOperations operations(List<AutonomousIndexOperations.ProviderView> providers) {
        return new AutonomousIndexOperations() {
            @Override
            public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) {
                throw new AssertionError("plan must not be called");
            }

            @Override
            public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
                throw new AssertionError("execute must not be called");
            }

            @Override
            public List<ProviderView> providers() {
                return providers;
            }

            @Override
            public ProviderView installProvider(String providerId) {
                throw new AssertionError("installProvider must not be called");
            }
        };
    }
}
