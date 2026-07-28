package com.minos.semantic;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Zero-network reference embedding provider based on signed feature hashing.
 *
 * <p>This provider is intentionally opt-in and is not presented as a language model.
 * It proves the provider/store/search plumbing without creating a cloud dependency.</p>
 */
public final class LocalHashEmbeddingProvider implements EmbeddingProvider {

    public static final int DEFAULT_DIMENSIONS = 384;
    private final int dimensions;

    public LocalHashEmbeddingProvider() {
        this(DEFAULT_DIMENSIONS);
    }

    public LocalHashEmbeddingProvider(int dimensions) {
        if (dimensions < 32 || dimensions > 4096) {
            throw new IllegalArgumentException("dimensions must be between 32 and 4096");
        }
        this.dimensions = dimensions;
    }

    @Override
    public String id() {
        return "minos-local-hash";
    }

    @Override
    public String modelId() {
        return "signed-token-chargram-v1-" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public SemanticVector embed(String stableKey, String text) {
        if (stableKey == null || stableKey.isBlank()) throw new IllegalArgumentException("stableKey must not be blank");
        if (text == null) throw new IllegalArgumentException("text must not be null");
        double[] vector = new double[dimensions];
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_]+", " ").trim();
        if (!normalized.isEmpty()) {
            for (String token : normalized.split("\\s+")) {
                addFeature(vector, "t:" + token, 1.0);
                String padded = "^" + token + "$";
                for (int n = 3; n <= 5; n++) {
                    for (int i = 0; i + n <= padded.length(); i++) {
                        addFeature(vector, "g:" + padded.substring(i, i + n), 0.35);
                    }
                }
            }
        }
        double norm = 0.0;
        for (double value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm != 0.0) {
            for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        }
        return SemanticVector.fromArray(stableKey, vector);
    }

    private void addFeature(double[] vector, String feature, double weight) {
        int hash = fnv1a(feature.getBytes(StandardCharsets.UTF_8));
        int index = Math.floorMod(hash, dimensions);
        int sign = (hash & 0x40000000) == 0 ? 1 : -1;
        vector[index] += sign * weight;
    }

    private static int fnv1a(byte[] bytes) {
        int hash = 0x811c9dc5;
        for (byte value : bytes) {
            hash ^= value & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }
}
