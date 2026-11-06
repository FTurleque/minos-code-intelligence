package com.minos.adapter.scip.runtime.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decisions of {@link ScipJavaMavenOffline} that rest on resolving a path, on any operating system: a
 * designation that is not a path and a Maven launcher that does not resolve are refused with a path-free reason,
 * and nothing is mounted for them.
 */
class ScipJavaMavenOfflineResolutionTest {

    @TempDir
    Path temp;

    @Test
    void aDesignationThatIsNotAPathIsRefusedWithoutEchoingIt() {
        // A NUL character is not valid in a path on any system.
        String designation = "repository-with-nul-\u0000-secret-name";
        ScipJavaMavenOffline offline = ScipJavaMavenOffline.of(designation, temp.resolve("minos-home"), null, null,
                List.of());

        ScipJavaMavenOffline.Repository repository = offline.repository();

        assertEquals(ScipJavaMavenOffline.RepositoryState.REFUSED, repository.state());
        assertTrue(repository.directory().isEmpty(), "a designation that is not a path is never mounted");
        String problem = repository.problem().orElseThrow();
        assertTrue(problem.contains("it is not a path"), problem);
        assertFalse(problem.contains("secret-name"), "the designation is echoed: " + problem);
    }

    @Test
    void aMavenLauncherThatDoesNotResolveIsRefusedWithoutNamingItsPath() {
        Path absent = temp.resolve("absent").resolve("bin").resolve("mvn");
        ScipJavaMavenOffline offline = ScipJavaMavenOffline.of(null, temp.resolve("minos-home"), null, absent,
                List.of(temp));

        ScipJavaMavenOffline.Maven maven = offline.maven();

        assertTrue(maven.home().isEmpty(), "an unresolved launcher has no installation");
        assertTrue(maven.version().isEmpty());
        String problem = maven.problem().orElseThrow();
        assertTrue(problem.contains("does not resolve to a Maven installation"), problem);
        assertFalse(problem.contains(temp.toString()), problem);
        assertTrue(offline.problems().contains(problem), "the diagnostic reaches the operator");
    }
}
