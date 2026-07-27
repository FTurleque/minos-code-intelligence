package com.minos.semantic;

import java.io.IOException;

/** Optional M20 embedding provider SPI. Implementations may be local models or deterministic test providers. */
public interface EmbeddingProvider {

    String id();

    String modelId();

    int dimensions();

    SemanticVector embed(String stableKey, String text) throws IOException;
}
