package com.minos.runtime.local.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ProcessIdentity} lit le SID du jeton par {@code whoami.exe} du répertoire système Windows. Là où
 * cet exécutable n'existe pas, l'identité est refusée (fail-closed) : jamais de repli sur {@code user.name}.
 */
class ProcessIdentityUnavailableTest {

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void withoutTheWindowsIdentityToolTheIdentityIsRefusedOpaquely() {
        IllegalStateException first = assertThrows(IllegalStateException.class, ProcessIdentity::sid);
        IllegalStateException second = assertThrows(IllegalStateException.class, ProcessIdentity::sid);

        assertEquals("cannot determine the identity of the current process", first.getMessage());
        assertNull(first.getCause(), "the cause may carry a path and must not be exposed");
        assertEquals(first.getMessage(), second.getMessage(), "a failed lookup is never cached as an identity");
    }
}
