package com.minos.bootstrap.semantic;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.LocalHashEmbeddingProvider;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Dans une même application, le classement hybride suit chaque nouveau snapshot comme le ferait une application
 * neuve sur le même état (lot A6, V-A6-09). Les snapshots successifs ont le même nombre de documents et des
 * contenus différents : un texte normalisé resservi d'un corpus précédent ne passerait pas inaperçu.
 */
class HybridCorpusRefreshTest {

    @Test
    void rankingFollowsEachNewSnapshotLikeAFreshApplication(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path root = Files.createDirectories(temp.resolve("project"));
        MinosApplication application = MinosApplication.builder(home).build();
        RegisteredProject project = HybridRankingCorpus.install(application, root);

        String first = HybridRankingCorpus.rankings(application, "structured");
        assertEquals(fresh(home, false, "structured"), first);

        HybridRankingCorpus.publish(application, root, project, "hybrid-ranking-snapshot-b", 1);
        String second = HybridRankingCorpus.rankings(application, "structured");
        assertNotEquals(first, second, "the second snapshot must rank differently");
        assertEquals(fresh(home, false, "structured"), second);

        MinosApplication semantic = MinosApplication.builder(home)
                .embeddingProvider(new LocalHashEmbeddingProvider()).build();
        semantic.semanticIndexService().synchronize(HybridRankingCorpus.PROJECT);
        String third = HybridRankingCorpus.rankings(semantic, "semantic");
        assertEquals(fresh(home, true, "semantic"), third);

        HybridRankingCorpus.publish(semantic, root, project, "hybrid-ranking-snapshot-c", 2);
        semantic.semanticIndexService().synchronize(HybridRankingCorpus.PROJECT);
        String fourth = HybridRankingCorpus.rankings(semantic, "semantic");
        assertNotEquals(third, fourth, "the third snapshot must rank differently");
        assertEquals(fresh(home, true, "semantic"), fourth);
    }

    /** Classement d'une application neuve, donc sans corpus en cache, sur l'état courant du home. */
    private static String fresh(Path home, boolean semantic, String mode) throws Exception {
        MinosApplication.Builder builder = MinosApplication.builder(home);
        if (semantic) builder.embeddingProvider(new LocalHashEmbeddingProvider());
        return HybridRankingCorpus.rankings(builder.build(), mode);
    }
}
