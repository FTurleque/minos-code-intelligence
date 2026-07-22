package io.github.fturleque.minos.adapter.scip;

import org.scip_code.scip.Index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Lit un fichier binaire SCIP à la frontière infrastructure de MINOS.
 *
 * <p>Le type {@link Index} ne doit pas franchir le package d'adaptation SCIP.
 * L'étape suivante de M0 le normalisera vers les types du domaine MINOS.</p>
 */
public final class ScipIndexReader {

    public Index read(Path indexFile) throws IOException {
        Objects.requireNonNull(indexFile, "indexFile");

        if (!Files.isRegularFile(indexFile)) {
            throw new IOException("SCIP index does not exist or is not a regular file: " + indexFile);
        }

        try (InputStream input = Files.newInputStream(indexFile)) {
            return Index.parseFrom(input);
        }
    }
}
