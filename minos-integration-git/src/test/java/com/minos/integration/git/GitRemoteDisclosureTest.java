package com.minos.integration.git;

import com.minos.domain.git.RepositoryView;
import com.minos.io.Sha256;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rendered origin remote carries neither credentials nor a local path (AUD-SEC-10-suite): only a recognised
 * network form is disclosed, anything else is withheld and declared, and the repository identity does not change.
 */
class GitRemoteDisclosureTest {

    @TempDir
    Path temp;

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            https://user:secret@example.com/org/repo.git?token=x#frag | https://example.com/org/repo
            ssh://git@example.com:2222/org/repo.git                   | ssh://example.com:2222/org/repo
            git@github.com:org/repo.git                               | github.com:org/repo
            example.com:org/repo                                      | example.com:org/repo
            """)
    void aNetworkRemoteIsDisclosedWithoutCredentials(String remote, String disclosed) throws Exception {
        RepositoryView view = inspectWithRemote(remote);

        assertEquals(disclosed, view.originRemote());
        assertFalse(view.limitations().contains("ORIGIN_REMOTE_NOT_DISCLOSED"), view.limitations().toString());
        assertFalse(view.limitations().contains("NO_ORIGIN_REMOTE"), view.limitations().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:p ss@example.com/org/repo.git",
            "/srv/depots/repo.git",
            "C:\\depots\\repo",
            "C:/depots/repo",
            "file:///srv/depots/repo.git",
            "\\\\server\\share\\repo",
            "../relative/repo"})
    void aLocalOrUnparsableRemoteIsWithheldAndDeclared(String remote) throws Exception {
        RepositoryView view = inspectWithRemote(remote);

        assertNull(view.originRemote(), remote);
        assertTrue(view.limitations().contains("ORIGIN_REMOTE_NOT_DISCLOSED"), view.limitations().toString());
        assertFalse(view.limitations().contains("NO_ORIGIN_REMOTE"), view.limitations().toString());
        assertFalse(view.toString().contains("p ss"), view.toString());
        assertFalse(view.toString().contains("depots"), view.toString());
    }

    @Test
    void theIdentityOfARepositoryWithALocalRemoteDoesNotChange() throws Exception {
        RepositoryView view = inspectWithRemote("/srv/depots/repo.git");

        // Identity basis computed before the rendered form was restricted: the remote without its .git suffix.
        assertEquals(Sha256.hex("remote:/srv/depots/repo"), view.repositoryId());
    }

    @Test
    void aRepositoryWithoutRemoteKeepsItsLimitation() throws Exception {
        RepositoryView view = new GitIntelligenceService().inspect(repository());

        assertNull(view.originRemote());
        assertTrue(view.limitations().contains("NO_ORIGIN_REMOTE"));
        assertFalse(view.limitations().contains("ORIGIN_REMOTE_NOT_DISCLOSED"));
    }

    private RepositoryView inspectWithRemote(String remote) throws Exception {
        Path root = repository();
        try (Git git = Git.open(root.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", remote);
            config.save();
        }
        return new GitIntelligenceService().inspect(root);
    }

    private Path repository() throws Exception {
        Path root = Files.createDirectories(temp.resolve("repo"));
        if (!Files.exists(root.resolve(".git"))) {
            try (Git git = Git.init().setDirectory(root.toFile()).call()) {
                Files.writeString(root.resolve("README.md"), "x\n");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").setAuthor("A", "a@example.invalid")
                        .setCommitter("A", "a@example.invalid").call();
            }
        }
        return root;
    }
}
