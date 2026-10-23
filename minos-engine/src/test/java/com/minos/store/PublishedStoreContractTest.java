package com.minos.store;

import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolSearchCriteria;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AUD-ARC-07 (ADR 0056) : les méthodes publiques du contrat de lecture publié en 1.2.0 se résolvent par leur nom et
 * leur descripteur exacts, comme le ferait un appelant compilé contre cette version et jamais recompilé. Une seule
 * absente fait échouer le test : la compatibilité binaire du contrat est gardée.
 */
@SuppressWarnings("deprecation")
class PublishedStoreContractTest {

    private static final MethodHandles.Lookup PUBLIC = MethodHandles.publicLookup();

    @Test
    void readsCalledThroughTheWritePortResolveWithTheirPublishedDescriptors() throws Exception {
        resolve(CodeKnowledgeStore.class, "findSymbolById", MethodType.methodType(Optional.class, String.class, String.class));
        resolve(CodeKnowledgeStore.class, "findSymbols",
                MethodType.methodType(List.class, String.class, SymbolSearchCriteria.class));
        resolve(CodeKnowledgeStore.class, "findSymbols",
                MethodType.methodType(List.class, String.class, String.class, int.class));
        resolve(CodeKnowledgeStore.class, "lacksModuleAttribution", MethodType.methodType(boolean.class, String.class));
        resolve(CodeKnowledgeStore.class, "findFileSymbols",
                MethodType.methodType(List.class, String.class, String.class, int.class));
        resolve(CodeKnowledgeStore.class, "findUsages",
                MethodType.methodType(List.class, String.class, String.class, int.class));
        resolve(CodeKnowledgeStore.class, "findRelationships",
                MethodType.methodType(List.class, String.class, RelationshipSearchCriteria.class));
        resolve(CodeKnowledgeStore.class, "putSymbols", MethodType.methodType(void.class, java.util.Collection.class));
        resolve(CodeKnowledgeStore.class, "putOccurrences", MethodType.methodType(void.class, java.util.Collection.class));
        resolve(CodeKnowledgeStore.class, "putRelationships",
                MethodType.methodType(void.class, java.util.Collection.class));
    }

    @Test
    void theLegacyQueryViewKeepsItsPublishedAccessorsAndConstructor() throws Exception {
        resolve(SnapshotQueryView.class, "queryStore", MethodType.methodType(InMemoryCodeKnowledgeStore.class));
        resolve(SnapshotQueryView.class, "indexMetrics",
                MethodType.methodType(InMemoryCodeKnowledgeStore.IndexMetrics.class));
        resolve(SnapshotQueryView.class, "descriptor", MethodType.methodType(SnapshotDescriptor.class));
        resolve(SnapshotQueryView.class, "snapshot", MethodType.methodType(CodeKnowledgeSnapshot.class));
        resolve(SnapshotQueryView.class, "buildNanos", MethodType.methodType(long.class));
        assertNotNull(PUBLIC.findConstructor(SnapshotQueryView.class, MethodType.methodType(void.class,
                SnapshotDescriptor.class, CodeKnowledgeSnapshot.class, InMemoryCodeKnowledgeStore.class, long.class)));
    }

    @Test
    void theLegacySnapshotPortReadResolvesWithItsPublishedDescriptor() throws Exception {
        resolve(CodeKnowledgeSnapshotStore.class, "loadActiveQueryView",
                MethodType.methodType(Optional.class, UUID.class));
        resolve(CodeKnowledgeSnapshotStore.class, "loadActiveKnowledge",
                MethodType.methodType(Optional.class, UUID.class));
    }

    @Test
    void theMemoryIndexKeepsItsPublishedReadsAndEdgeIndex() throws Exception {
        resolve(InMemoryCodeKnowledgeStore.class, "symbolEdgeIndex",
                MethodType.methodType(InMemoryCodeKnowledgeStore.SymbolEdgeIndex.class, String.class));
        resolve(InMemoryCodeKnowledgeStore.SymbolEdgeIndex.class, "incoming",
                MethodType.methodType(List.class, String.class));
        assertNotNull(PUBLIC.findStatic(InMemoryCodeKnowledgeStore.SymbolEdgeIndex.class, "of",
                MethodType.methodType(InMemoryCodeKnowledgeStore.SymbolEdgeIndex.class, CodeKnowledgeSnapshot.class)));
        assertNotNull(PUBLIC.findStatic(InMemoryCodeKnowledgeStore.class, "symbolEdgeIndexWeightBytes",
                MethodType.methodType(long.class, long.class)));
    }

    private static void resolve(Class<?> owner, String name, MethodType type) throws ReflectiveOperationException {
        assertNotNull(PUBLIC.findVirtual(owner, name, type), owner.getSimpleName() + "." + name + type);
    }
}
