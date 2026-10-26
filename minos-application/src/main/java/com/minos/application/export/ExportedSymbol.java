package com.minos.application.export;

import com.minos.domain.Symbol;

import java.util.Objects;

/** A symbol retained by the export, with the path of its file relative to the project root ('/' separators). */
public record ExportedSymbol(Symbol symbol, String filePath) {

    public ExportedSymbol {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(filePath, "filePath");
    }
}
