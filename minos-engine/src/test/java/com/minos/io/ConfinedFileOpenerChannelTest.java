package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ConfinedFileOpener#openRegularFileNoFollowChannel}: the read of a file by channel, for the callers that need
 * {@code size()} and {@code position(0)} on the one descriptor (AUD-SEC-03). Same checks as the stream, same refusals.
 */
class ConfinedFileOpenerChannelTest {

    private static String readAll(SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // keep reading until the buffer is full
        }
        return new String(buffer.array(), StandardCharsets.UTF_8);
    }

    @Test
    void aRegularFileIsReadThroughOneDescriptorWithItsSizeAndARewind(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("data.bin"), "payload", StandardCharsets.UTF_8);

        try (SeekableByteChannel channel = ConfinedFileOpener.openRegularFileNoFollowChannel(file)) {
            assertEquals(7L, channel.size());
            assertEquals("payload", readAll(channel));
            channel.position(0L);
            assertEquals("payload", readAll(channel));
        }
    }

    @Test
    void theChannelCannotWrite(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("data.bin"), "payload", StandardCharsets.UTF_8);

        try (SeekableByteChannel channel = ConfinedFileOpener.openRegularFileNoFollowChannel(file)) {
            assertThrows(java.nio.channels.NonWritableChannelException.class,
                    () -> channel.write(ByteBuffer.wrap(new byte[]{1})));
        }
        assertEquals("payload", Files.readString(file));
    }

    @Test
    void anAbsentFileIsReportedAsAbsent(@TempDir Path root) {
        assertThrows(NoSuchFileException.class,
                () -> ConfinedFileOpener.openRegularFileNoFollowChannel(root.resolve("absent.bin")));
    }

    @Test
    void aDirectoryIsNotAFileAndTheRefusalNamesNoPath(@TempDir Path root) {
        ConfinedFileOpener.ConfinementException refusal = assertThrows(ConfinedFileOpener.ConfinementException.class,
                () -> ConfinedFileOpener.openRegularFileNoFollowChannel(root));

        assertFalse(String.valueOf(refusal.getMessage()).contains(root.toString()));
    }

    @Test
    void aLinkLeafIsRefusedWithoutBeingFollowed(@TempDir Path root) throws Exception {
        Path secret = Files.writeString(root.resolve("secret.txt"), "SECRET", StandardCharsets.UTF_8);
        Path link = root.resolve("link.bin");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException _) {
            assumeTrue(false, "this platform/account cannot create symbolic links");
        }

        ConfinedFileOpener.ConfinementException refusal = assertThrows(ConfinedFileOpener.ConfinementException.class,
                () -> ConfinedFileOpener.openRegularFileNoFollowChannel(link));

        assertFalse(String.valueOf(refusal.getMessage()).contains(root.toString()), "the refusal carries no path");
    }

    @Test
    void aLeafSwappedForALinkAfterTheLookIsRefusedNotFollowed(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("envelope.bin"), "legitimate", StandardCharsets.UTF_8);
        Path secret = Files.writeString(root.resolve("secret.txt"), "SECRET", StandardCharsets.UTF_8);
        Path probe = root.resolve("probe.lnk");
        try {
            Files.createSymbolicLink(probe, secret);
            Files.delete(probe);
        } catch (UnsupportedOperationException | IOException _) {
            assumeTrue(false, "this platform/account cannot create symbolic links");
        }
        AtomicBoolean swapped = new AtomicBoolean();
        Runnable beforeOpen = () -> {
            try {
                Files.delete(file);
                Files.createSymbolicLink(file, secret);
                swapped.set(true);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        };

        assertThrows(IOException.class, () -> readThrough(file, beforeOpen));
        assertTrue(swapped.get(), "the step between the look and the open ran");
    }

    @Test
    void aFileDeletedBetweenTheLookAndTheOpenIsReportedAsAbsent(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("data.bin"), "payload", StandardCharsets.UTF_8);
        Runnable beforeOpen = () -> {
            try {
                Files.delete(file);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        };

        assertThrows(NoSuchFileException.class, () -> readThrough(file, beforeOpen));
    }

    /** Opens through the seam and reads: one call can throw, so a refusal is attributed to the right step. */
    private static String readThrough(Path file, Runnable beforeOpen) throws IOException {
        try (SeekableByteChannel channel = ConfinedFileOpener.openRegularFileNoFollowChannel(file, beforeOpen)) {
            return readAll(channel);
        }
    }
}
