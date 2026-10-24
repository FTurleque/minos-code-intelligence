package com.minos.application.semantic;

import com.minos.semantic.SemanticVector;
import java.io.IOException;
import java.util.List;

/** Optional embedding provider SPI. Implementations may be local models or deterministic test providers. */
public interface EmbeddingProvider {

    String id();

    String modelId();

    int dimensions();

    SemanticVector embed(String stableKey, String text) throws IOException;

    /**
     * Limitations specific to this provider, added in this order, after those of the index state, to the
     * semantic statuses and reports. None by default: a provider declares some only if it has any.
     */
    default List<String> limitations() {
        return List.of();
    }
}
