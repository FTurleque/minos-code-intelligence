package com.minos.semantic;

import java.io.IOException;
import java.util.List;

/** Optional M20 embedding provider SPI. Implementations may be local models or deterministic test providers. */
public interface EmbeddingProvider {

    String id();

    String modelId();

    int dimensions();

    SemanticVector embed(String stableKey, String text) throws IOException;

    /**
     * Limitations propres à ce fournisseur, ajoutées dans cet ordre, après celles de l'état de l'index,
     * aux statuts et rapports sémantiques. Aucune par défaut : un fournisseur n'en déclare que s'il en a.
     */
    default List<String> limitations() {
        return List.of();
    }
}
