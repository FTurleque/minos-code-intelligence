package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.application.ProviderPlatformService;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Local implementation of provider diagnostics over the shared M17 platform service. */
public final class LocalProviderPlatformApi implements ProviderPlatformApi {
    private final ProviderPlatformService service;

    public LocalProviderPlatformApi(Path home) throws MinosApi.MinosApiException {
        try {
            this.service = ProviderPlatformService.defaults(MinosApplication.open(home));
        } catch (Exception exception) {
            throw new MinosApi.MinosApiException(MinosApi.ErrorCode.IO_FAILURE,
                    "unable to open MINOS provider platform", exception);
        }
    }

    public LocalProviderPlatformApi(MinosApplication application) {
        this.service = ProviderPlatformService.defaults(Objects.requireNonNull(application, "application"));
    }

    @Override
    public List<ProviderDto> listProviders() throws MinosApi.MinosApiException {
        try {
            return service.listProviders().stream().map(LocalProviderPlatformApi::dto).toList();
        } catch (RuntimeException exception) {
            throw new MinosApi.MinosApiException(MinosApi.ErrorCode.EXECUTION_FAILURE,
                    exception.getMessage(), exception);
        }
    }

    @Override
    public ProviderDto getProvider(String providerId) throws MinosApi.MinosApiException {
        try {
            return dto(service.inspect(providerId));
        } catch (IllegalArgumentException exception) {
            throw new MinosApi.MinosApiException(MinosApi.ErrorCode.INVALID_REQUEST,
                    exception.getMessage(), exception);
        }
    }

    private static ProviderDto dto(ProviderPlatformService.ProviderView value) {
        return new ProviderDto(
                value.id(), value.version(), value.languages(), value.buildSystems(), value.capabilities(),
                value.conformanceScorePercent(), value.limitations(), value.runtimeState(), value.runtimeDiagnostics());
    }
}
