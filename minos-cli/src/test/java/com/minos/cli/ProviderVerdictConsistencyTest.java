package com.minos.cli;

import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q11 : {@code doctor} et {@code tools verify} rendent UN verdict, pas deux. Pour chaque état de
 * {@link ProviderRuntimeStatus.State} (l'énumération, pas une liste recopiée) et pour un provider requis
 * ou optionnel, les deux commandes sortent le même code. Contrat documenté
 * ({@code docs/developer/remote-worker-sandbox-disposition.md}, « Invariant ») : une capacité
 * volontairement absente du backend sélectionné ({@code UNSUPPORTED_BY_BACKEND}) ne bloque pas, tout autre
 * état non prêt d'un provider requis bloque.
 */
class ProviderVerdictConsistencyTest {

    @TempDir Path home;

    private static AutonomousIndexOperations operations(ProviderRuntimeStatus.State state, boolean required) {
        return new AutonomousIndexOperations() {
            @Override public IndexPlanView plan(String project, String provider, boolean forceFull) {
                throw new UnsupportedOperationException();
            }
            @Override public IndexExecutionView execute(String project, String provider, boolean forceFull) {
                throw new UnsupportedOperationException();
            }
            @Override public List<ProviderView> providers() {
                return List.of(new ProviderView("scip-java", "1.0", state.name(), null, List.of(), required));
            }
            @Override public ProviderView installProvider(String providerId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private int doctor(ProviderRuntimeStatus.State state, boolean required, StringBuilder output) throws IOException {
        DoctorCommand doctor = new DoctorCommand(home, operations(state, required), ignored ->
                new DoctorCommand.WorkerSandboxReport("managed", true, "untrusted", false,
                        "REJECTED_BY_DECISION", java.util.Optional.empty(), List.of(), ""));
        return doctor.run(new String[]{"--format", "json"}, output, new StringBuilder());
    }

    private int toolsVerify(ProviderRuntimeStatus.State state, boolean required, boolean all) throws IOException {
        String[] arguments = all ? new String[]{"verify", "--all"} : new String[]{"verify"};
        return new ToolsCommand(operations(state, required)).run(arguments, new StringBuilder(), new StringBuilder());
    }

    @Test
    void doctorAndToolsVerifyAgreeForEveryStateOfARequiredProvider() throws IOException {
        for (ProviderRuntimeStatus.State state : ProviderRuntimeStatus.State.values()) {
            StringBuilder output = new StringBuilder();
            int doctor = doctor(state, true, output);
            int tools = toolsVerify(state, true, false);
            assertEquals(tools, doctor, state + " (required): doctor " + doctor + " but tools verify " + tools);
            assertTrue(output.toString().contains("\"ready\":" + (doctor == 0)), state + ": " + output);
        }
    }

    @Test
    void anOptionalProviderNeverChangesTheDefaultVerdictOfEitherCommand() throws IOException {
        for (ProviderRuntimeStatus.State state : ProviderRuntimeStatus.State.values()) {
            assertEquals(0, doctor(state, false, new StringBuilder()), state + " (optional) doctor");
            assertEquals(0, toolsVerify(state, false, false), state + " (optional) tools verify");
        }
    }

    @Test
    void theDocumentedVerdictPerState() throws IOException {
        for (ProviderRuntimeStatus.State state : ProviderRuntimeStatus.State.values()) {
            int expected = state == ProviderRuntimeStatus.State.READY
                    || state == ProviderRuntimeStatus.State.UNSUPPORTED_BY_BACKEND ? 0 : 1;
            assertEquals(expected, doctor(state, true, new StringBuilder()), state + " doctor");
            assertEquals(expected, toolsVerify(state, true, false), state + " tools verify");
            assertEquals(expected, toolsVerify(state, false, true), state + " tools verify --all");
        }
    }
}
