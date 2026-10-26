package com.minos.registry;

import com.minos.domain.registry.DegradedEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A strict read of the registry that failed is explained by its unreadable entries only when there are some: a
 * registry that cannot be inventoried at all is never presented as a partial result.
 */
class ProjectRegistryExplainUnreadableTest {

    private static final DegradedEntry FIRST =
            DegradedEntry.of("11111111-1111-1111-1111-111111111111", "registry entry is unreadable");

    @Test
    void aRegistryThatCannotBeInventoriedIsNeverPresentedAsUnreadableEntries() {
        ProjectRegistry broken = registry(() -> {
            throw new IOException("storage down");
        });

        assertEquals(Optional.empty(), broken.explainUnreadable("x"), "a failure is not a partial result");
    }

    @Test
    void aHealthyRegistryExplainsNothing() {
        ProjectRegistry healthy = registry(() -> new ProjectRegistry.Inventory(List.of(), List.of()));

        assertEquals(Optional.empty(), healthy.explainUnreadable("x"));
    }

    @Test
    void aDamagedRegistryIsExplainedByItsUnreadableEntriesAndTheConsequence() {
        ProjectRegistry damaged = registry(() -> new ProjectRegistry.Inventory(List.of(), List.of(FIRST)));

        var explained = damaged.explainUnreadable("the project name cannot be resolved").orElseThrow();

        assertEquals(List.of(FIRST), explained.unreadable());
        assertEquals("1 registry entry is unreadable, so the project name cannot be resolved", explained.getMessage());
    }

    @FunctionalInterface
    private interface InventorySource {
        ProjectRegistry.Inventory read() throws IOException;
    }

    /** A registry whose only abstract behaviour is its inventory; default methods run as written. */
    private static ProjectRegistry registry(InventorySource source) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("inventory")) return source.read();
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
            throw new UnsupportedOperationException(method.getName());
        };
        return (ProjectRegistry) Proxy.newProxyInstance(ProjectRegistry.class.getClassLoader(),
                new Class<?>[]{ProjectRegistry.class}, handler);
    }
}
