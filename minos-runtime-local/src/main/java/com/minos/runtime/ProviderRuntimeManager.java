package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;

import java.util.List;

/**
 * Frontière de gestion des runtimes de providers externes.
 */
public interface ProviderRuntimeManager {

    List<ProviderRuntimeStatus> list();

    ProviderRuntimeStatus inspect(String providerId);

    ProviderRuntimeStatus install(String providerId) throws Exception;

    IndexerExecutor executor(String providerId);
}
