package com.minos.bootstrap.semantic;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.LocalHashEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le classement hybride est identique, à l'octet près, à celui du code de {@code d9ae1005} (lot A6, étape 4) :
 * mêmes documents, même ordre, mêmes bits de score, même départage par {@code stableKey}, en mode structuré et
 * en mode sémantique. La référence {@code hybrid-ranking-d9ae1005.tsv} a été produite par ce même test avec le
 * {@code minos-application} de {@code d9ae1005}, avant la mise en cache de la normalisation.
 */
class HybridRankingEquivalenceTest {

    static final String REFERENCE = "/hybrid-ranking/hybrid-ranking-d9ae1005.tsv";
    /** {@code -Dminos.hybridRanking.write=<fichier>} écrit la référence au lieu de la comparer (production initiale). */
    static final String WRITE_PROPERTY = "minos.hybridRanking.write";

    @Test
    void rankingIsByteIdenticalToTheBaseInStructuredAndSemanticModes(@TempDir Path temp) throws Exception {
        MinosApplication structured = MinosApplication.builder(temp.resolve("home")).build();
        HybridRankingCorpus.install(structured, Files.createDirectories(temp.resolve("project")));
        String actual = HybridRankingCorpus.rankings(structured, "structured");

        MinosApplication semantic = MinosApplication.builder(temp.resolve("home"))
                .embeddingProvider(new LocalHashEmbeddingProvider()).build();
        semantic.semanticIndexService().synchronize(HybridRankingCorpus.PROJECT);
        actual += HybridRankingCorpus.rankings(semantic, "semantic");

        String target = System.getProperty(WRITE_PROPERTY);
        if (target != null) {
            Files.writeString(Path.of(target), actual, StandardCharsets.UTF_8);
            return;
        }
        assertTrue(HybridRankingCorpus.exactTies(actual) > 500,
                "the corpus must exercise exact score ties: " + HybridRankingCorpus.exactTies(actual));
        assertEquals(reference(), actual);
    }

    private static String reference() throws IOException {
        try (InputStream input = HybridRankingEquivalenceTest.class.getResourceAsStream(REFERENCE)) {
            if (input == null) throw new IOException("missing reference " + REFERENCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
