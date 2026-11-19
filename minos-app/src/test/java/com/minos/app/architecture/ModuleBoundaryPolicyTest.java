package com.minos.app.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The strict read of the shared boundary policy (AUD-ARC-04): every refusal of the guard, on temporary copies. */
class ModuleBoundaryPolicyTest {

    @TempDir
    Path root;

    private String real;

    @BeforeEach
    void readTheRealPolicy() throws IOException {
        real = Files.readString(ModuleBoundaryPolicy.FILE);
        Files.createDirectories(root.resolve(ModuleBoundaryPolicy.FILE).getParent());
    }

    private void write(String text) throws IOException {
        Files.writeString(root.resolve(ModuleBoundaryPolicy.FILE), text);
    }

    private void mutated(UnaryOperator<String> change) throws IOException {
        String changed = change.apply(real);
        assertNotEquals(real, changed, "the mutation changes the policy");
        write(changed);
    }

    private void refused(String... fragments) {
        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> ModuleBoundaryPolicy.load(root));
        String message = refusal.getMessage();
        assertTrue(message.contains("scripts/architecture/module-boundaries.json"), message);
        assertFalse(message.contains(root.toString()), "the file is named relatively to the repository: " + message);
        for (String fragment : fragments) {
            assertTrue(message.contains(fragment), message + " does not name " + fragment);
        }
    }

    @Test
    void theRealPolicyIsReadWithItsFamiliesEdgesAndBytecodeDebt() {
        ModuleBoundaryPolicy policy = ModuleBoundaryPolicy.load(Path.of(""));

        assertEquals(14, policy.modules().size());
        assertEquals("minos-domain", policy.modules().get(0));
        assertEquals(Set.of("minos-nexus"), policy.family("contract"));
        assertTrue(policy.allowed().get("minos-cli").contains("minos-nexus"));
        assertEquals(Map.of("runtime.MinosVersion", 1), policy.engineTypesUsedBySurfaceBytecode().get("minos-mcp"));
    }

    @Test
    void aMissingFileIsRefused() {
        refused("missing");
    }

    @Test
    void anUnparsableFileIsRefused() throws IOException {
        write("{ not json");
        refused("not valid JSON");
    }

    @Test
    void anUnknownKeyIsRefused() throws IOException {
        mutated(text -> text.replace("\"allowedDependencies\"", "\"alowedDependencies\""));
        refused("unknown key", "alowedDependencies");
    }

    @Test
    void aDuplicatedKeyIsRefused() throws IOException {
        mutated(text -> text.replaceFirst("\"version\": 1,", "\"version\": 1,\n  \"version\": 1,"));
        refused("duplicated key", "version");
    }

    @Test
    void aMissingSectionIsRefused() throws IOException {
        mutated(text -> text.replaceFirst("\"version\": 1,", ""));
        refused("missing section", "version");
    }

    @Test
    void anUnknownFamilyIsRefused() throws IOException {
        mutated(text -> text.replaceFirst("\"family\": \"core\"", "\"family\": \"kernel\""));
        refused("unknown family", "kernel");
    }

    @Test
    void anUnknownModuleInAnEdgeIsRefused() throws IOException {
        mutated(text -> text.replace("\"minos-cli\": [", "\"minos-cli\": [\n      \"minos-inconnu\","));
        refused("minos-inconnu", "allowedDependencies.minos-cli");
    }

    @Test
    void aDebtEntryWithoutReasonIsRefused() throws IOException {
        mutated(text -> text.replaceAll("(\"classes\": 1,\\s*\"reason\": )\"[^\"]*\"", "$1\" \""));
        refused("reason", "engineTypesUsedBySurfaceBytecode");
    }

    @Test
    void anEngineDebtKeyedByAnAdapterIsRefused() throws IOException {
        mutated(text -> text.replace("\"engineTypesUsedBySurfaceBytecode\": {",
                "\"engineTypesUsedBySurfaceBytecode\": {\n    \"minos-storage\": {},"));
        refused("minos-storage", "neither a surface nor a contract module");
    }
}
