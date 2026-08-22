package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.application.ProviderPlatformService;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Local implementation of provider diagnostics over the shared M17 platform service. */
public final class LocalProviderPlatformApi implements ProviderPlatformApi, AutoCloseable {
    private final MinosApplication application;
    private final boolean ownsApplication;
    private final ProviderPlatformService service;

    public LocalProviderPlatformApi(Path home) throws MinosApi.MinosApiException {
        this(openApplication(home), true);
    }

    public LocalProviderPlatformApi(MinosApplication application) {
        this(application, false);
    }

    private LocalProviderPlatformApi(MinosApplication application, boolean ownsApplication) {
        this.application = Objects.requireNonNull(application, "application");
        this.ownsApplication = ownsApplication;
        this.service = ProviderPlatformService.defaults(this.application);
    }

    @Override
    public List<ProviderDto> listProviders() throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(() -> service.listProviders().stream().map(LocalProviderPlatformApi::dto).toList());
    }

    @Override
    public ProviderDto getProvider(String providerId) throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(() -> dto(service.inspect(providerId)));
    }

    @Override
    public void close() throws MinosApi.MinosApiException {
        if (!ownsApplication) return;
        MinosApiSupport.execute(() -> {
            application.close();
            return null;
        });
    }

    private static MinosApplication openApplication(Path home) throws MinosApi.MinosApiException {
        return MinosApiSupport.openApplication(home, "unable to open MINOS provider platform");
    }

    private static ProviderDto dto(ProviderPlatformService.ProviderView value) {
        return new ProviderDto(
                value.id(), value.version(), value.languages(), value.buildSystems(), value.capabilities(),
                value.conformanceScorePercent(), value.limitations(), value.runtimeState(), value.runtimeDiagnostics());
    }
}
