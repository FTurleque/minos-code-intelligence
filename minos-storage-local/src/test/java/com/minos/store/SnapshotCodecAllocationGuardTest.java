package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotCodecAllocationGuardTest {

    @TempDir
    Path temporary;

    @Test
    void truncatedSnapshotWithMaximumSymbolCountFailsWithoutProtocolScalePreallocation() throws Exception {
        Path file = temporary.resolve("corrupt.knowledge");
        UUID project = UUID.randomUUID();
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeInt(0x4D4E5359);
            output.writeInt(2);
            output.writeLong(project.getMostSignificantBits());
            output.writeLong(project.getLeastSignificantBits());
            output.writeInt(1);
            output.writeChar('s');
            output.writeInt(10_000_000);
        }
        assertThrows(IOException.class, () -> new SnapshotCodecV2().read(file));
    }
}
