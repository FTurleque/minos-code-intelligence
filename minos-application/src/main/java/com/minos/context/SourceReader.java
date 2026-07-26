package com.minos.context;

import com.minos.domain.SymbolLocation;

import java.io.IOException;
import java.util.Optional;

/**
 * Port local de lecture des sources, indépendant du store de connaissance.
 */
public interface SourceReader {

    Optional<SourceExcerpt> readExcerpt(
            SymbolLocation location,
            int contextLines,
            int maxTokens
    ) throws IOException;

    SourceExcerpt readFull(String fileId) throws IOException;
}
