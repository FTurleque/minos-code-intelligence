package com.minos.integration.nexus;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NexusExportContractTest {

    @Test
    void contractVersionAndProducerArePinned() {
        assertEquals("1", NexusExportContract.CONTRACT_VERSION);
        assertEquals("MINOS", NexusExportContract.PRODUCER);
    }

    @Test
    void exportedRecordComponentsDoNotDependOnNexusTypes() {
        Arrays.stream(NexusExportContract.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getGenericType)
                .forEach(type -> assertFalse(
                        type.getTypeName().contains("com.nexus"),
                        () -> "M13 transport leaks a NEXUS type through " + type.getTypeName()));
    }
}
