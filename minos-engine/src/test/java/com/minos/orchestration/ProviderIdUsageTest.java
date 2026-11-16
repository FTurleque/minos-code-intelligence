package com.minos.orchestration;

import com.minos.diagnostics.UsageException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderIdUsageTest {

    @Test
    void anIdentifierThatIsNotSafeIsAUsageError() {
        assertThrows(UsageException.class, () -> ProviderId.require(null));
        assertThrows(UsageException.class, () -> ProviderId.require("../escape"));
    }
}
