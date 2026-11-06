package com.minos.integration.git;

import com.minos.domain.git.ActivityQuery;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A project root that cannot be opened is refused by its name, never by its absolute path (AUD-SEC-04). */
class GitRefusalMessagePathTest {

    private static final String INVALID_CONFIGURATION = "The Git repository configuration is invalid";

    @TempDir
    Path temp;

    @Test
    void aProjectRootThatIsAFileIsRefusedByItsName() throws Exception {
        Path file = Files.writeString(temp.resolve("not-a-repository.txt"), "x");

        GitIntelligenceService service = new GitIntelligenceService();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> service.inspect(file));

        assertEquals("projectRoot must be an existing directory: not-a-repository.txt", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void aMalformedRepositoryConfigurationIsRefusedOpaquelyOnInspect() throws Exception {
        Path root = repositoryWithMalformedConfiguration();

        IOException refusal = assertThrows(IOException.class, () -> new GitIntelligenceService().inspect(root));

        assertOpaqueConfigurationRefusal(refusal, root);
    }

    @Test
    void aMalformedRepositoryConfigurationIsRefusedOpaquelyOnAnalyze() throws Exception {
        Path root = repositoryWithMalformedConfiguration();
        ActivityQuery query = new ActivityQuery(Instant.EPOCH, 100, 100, 1);

        IOException refusal = assertThrows(IOException.class,
                () -> new GitIntelligenceService().analyze(root, query));

        assertOpaqueConfigurationRefusal(refusal, root);
    }

    private Path repositoryWithMalformedConfiguration() throws Exception {
        Path root = Files.createDirectories(temp.resolve("analysed-repository"));
        Git.init().setDirectory(root.toFile()).call().close();
        // An unterminated section header: the repository cannot be parsed at all.
        Files.writeString(root.resolve(".git").resolve("config"), "[core\n\tbare = false\n");
        return root;
    }

    private void assertOpaqueConfigurationRefusal(Throwable refusal, Path root) {
        String message = refusal.getMessage();
        assertEquals(INVALID_CONFIGURATION, message);
        assertNull(refusal.getCause(), "the parser failure must not travel with the refusal");
        assertFalse(message.contains(root.toString()), message);
        assertFalse(message.contains(temp.toString()), message);
        assertFalse(message.contains(temp.getFileName().toString()), message);
        assertFalse(message.contains(root.getFileName().toString()), message);
    }
}
