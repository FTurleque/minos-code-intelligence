package com.minos.registry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Le signal « N entrées sont illisibles » : un dénombrement et une conséquence, jamais un chemin ni un texte de fichier. */
class UnreadableRegistryExceptionTest {

    private static final DegradedEntry FIRST = DegradedEntry.of("11111111-1111-1111-1111-111111111111", "registry entry is unreadable");
    private static final DegradedEntry SECOND = DegradedEntry.of("22222222-2222-2222-2222-222222222222", "registry entry is unreadable");

    @Test
    void theMessageCountsTheEntriesAndStatesTheConsequence() {
        assertEquals("1 registry entry is unreadable, so it cannot be told whether this project exists",
                UnreadableRegistryException.of(List.of(FIRST), "it cannot be told whether this project exists").getMessage());
        assertEquals("2 registry entries are unreadable, so the project name cannot be resolved",
                UnreadableRegistryException.of(List.of(FIRST, SECOND), "the project name cannot be resolved").getMessage());
    }

    @Test
    void itCarriesTheDegradedEntriesTheRegistryProducedAndNothingElse() {
        UnreadableRegistryException exception = UnreadableRegistryException.of(List.of(FIRST, SECOND), "x");

        assertEquals(List.of(FIRST, SECOND), exception.unreadable());
        assertTrue(!exception.getMessage().contains(FIRST.entry()), "the message carries a count, not entry names");
    }

    @Test
    void anEmptyListIsNotUnreadable() {
        assertThrows(IllegalArgumentException.class, () -> UnreadableRegistryException.of(List.of(), "x"));
    }

    @Test
    void aRegistryThatCannotBeInventoriedIsNeverPresentedAsUnreadableEntries() {
        ProjectRegistry broken = registry(() -> {
            throw new IOException("storage down");
        });
        ProjectRegistry healthy = registry(() -> new ProjectRegistry.Inventory(List.of(), List.of()));
        ProjectRegistry damaged = registry(() -> new ProjectRegistry.Inventory(List.of(), List.of(FIRST)));

        assertEquals(Optional.empty(), UnreadableRegistryException.explaining(broken, "x"), "a panne is not a partial result");
        assertEquals(Optional.empty(), UnreadableRegistryException.explaining(healthy, "x"));
        assertEquals(List.of(FIRST), UnreadableRegistryException.explaining(damaged, "x").orElseThrow().unreadable());
    }

    @FunctionalInterface
    private interface InventorySource {
        ProjectRegistry.Inventory read() throws IOException;
    }

    private static ProjectRegistry registry(InventorySource source) {
        return (ProjectRegistry) Proxy.newProxyInstance(ProjectRegistry.class.getClassLoader(),
                new Class<?>[]{ProjectRegistry.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("inventory")) return source.read();
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
