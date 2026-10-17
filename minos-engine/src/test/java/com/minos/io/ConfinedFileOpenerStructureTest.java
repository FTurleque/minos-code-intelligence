package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the shape of the confinement primitive: it keeps no mutable static state. A test that needs to act
 * between the last validation and the open passes its own step to the package-private overloads, so one test
 * can never leak its step into another or into production code running in the same JVM.
 */
class ConfinedFileOpenerStructureTest {

    @Test
    void hasNoStaticFieldThatCanBeReassigned() {
        List<String> mutable = Arrays.stream(ConfinedFileOpener.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> !Modifier.isFinal(field.getModifiers()))
                .map(Field::getName)
                .toList();

        assertEquals(List.of(), mutable, "ConfinedFileOpener must hold no mutable static state");
    }

    @Test
    void aStepPassedToOneCallIsNotRunByTheNextCall(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("data.txt"), "payload", StandardCharsets.UTF_8);
        AtomicInteger runs = new AtomicInteger();

        try (InputStream ignored = ConfinedFileOpener.openRegularFileNoFollow(file, runs::incrementAndGet)) {
            assertEquals(1, runs.get());
        }
        try (InputStream ignored = ConfinedFileOpener.openRegularFileNoFollow(file)) {
            assertEquals(1, runs.get(), "the public overload must not inherit the step of an earlier call");
        }
        try (SeekableByteChannel ignored = ConfinedFileOpener.openConfinedRegularFile(
                root, Path.of("data.txt"))) {
            assertEquals(1, runs.get(), "neither must the confined open");
        }
    }
}
