package com.minos.cli.operations;

import com.minos.application.MinosApplication;
import com.minos.application.MinosApplicationTestAccess;
import com.minos.application.indexing.IndexingResumeChoice;
import com.minos.application.remote.RemoteIndexingUseCase;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteRepositoryMaterializer;

/** The remote indexing of an opened application with a substituted materializer and runtime, for these tests. */
final class RemoteIndexingUseCases {

    private RemoteIndexingUseCases() {
    }

    static RemoteIndexingUseCase wired(MinosApplication application, RemoteRepositoryMaterializer materializer,
                                       RemoteIndexingRuntime runtime) {
        return new RemoteIndexingUseCase(new RemoteIndexingUseCase.Dependencies(application.home(),
                MinosApplicationTestAccess.projectRegistry(application), materializer, runtime,
                        MinosApplicationTestAccess.indexerDescriptors(application),
                (projectId, providerOverride, decorator) ->
                        MinosApplicationTestAccess.decoratedProjectIndexing(application, decorator)
                        .executeSummary(projectId, providerOverride, true, IndexingResumeChoice.RESUME)));
    }
}
