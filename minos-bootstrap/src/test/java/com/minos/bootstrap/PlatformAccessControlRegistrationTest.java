package com.minos.bootstrap;

import com.minos.io.PlatformAccessControl;
import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A module that sees both the test-jar of minos-engine and minos-runtime-local finds exactly one platform access
 * control, the production one: the registration of the engine's test double never travels with its test-jar
 * (SH-08), and two implementations would make every hardening of private storage fail on Windows.
 */
class PlatformAccessControlRegistrationTest {

    @Test
    void theLocalRuntimeImplementationIsTheOnlyOneRegistered() {
        List<String> found = ServiceLoader.load(PlatformAccessControl.class, PrivateLocalStorage.class.getClassLoader())
                .stream().map(provider -> provider.type().getName()).toList();

        assertEquals(List.of("com.minos.runtime.local.acl.WindowsIcaclsAccessControl"), found);
    }
}
