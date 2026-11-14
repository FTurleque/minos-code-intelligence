package com.minos.storage.local.store;

import com.minos.semantic.SemanticVectorStore;
import com.minos.testsupport.SemanticDocumentReadsContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

/** The paged document reads and the stored vector read, on the local file store behind its mutation lease. */
class FileSemanticDocumentReadsContractTest implements SemanticDocumentReadsContract {

    @TempDir
    Path temp;

    @Override
    public SemanticVectorStore store() throws Exception {
        return new ProjectMutationSemanticVectorStore(temp, new FileSemanticVectorStore(temp.resolve("semantic")));
    }

    @Override
    public String newProjectId() {
        return UUID.randomUUID().toString();
    }
}
