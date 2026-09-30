package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.bootstrap.CloseCountingStorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Q10 (V-L3-03) : une façade ouverte sur un {@code MINOS_HOME} possède son application et la ferme dans
 * {@code close()} ; construite sur une application reçue, elle ne la ferme pas. Observé sur un stockage qui compte
 * ses fermetures ({@link CloseCountingStorageProvider}), sans point d'injection en production.
 */
class OwnedApplicationLifecycleTest {

    @Test
    void localMinosApiOpenedOnAHomeClosesItsApplication(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            LocalMinosApi api = new LocalMinosApi(home);
            assertEquals(1, CloseCountingStorageProvider.opens());
            assertEquals(0, CloseCountingStorageProvider.closes());

            api.close();

            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }

    @Test
    void localMinosMultiRepositoryApiOpenedOnAHomeClosesItsApplication(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            LocalMinosMultiRepositoryApi api = new LocalMinosMultiRepositoryApi(home);
            assertEquals(1, CloseCountingStorageProvider.opens());
            assertEquals(0, CloseCountingStorageProvider.closes());

            api.close();

            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }

    @Test
    void localProviderPlatformApiOpenedOnAHomeClosesItsApplication(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            LocalProviderPlatformApi api = new LocalProviderPlatformApi(home);
            assertEquals(1, CloseCountingStorageProvider.opens());
            assertEquals(0, CloseCountingStorageProvider.closes());

            api.close();

            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }

    @Test
    void facadesBuiltOnAnExistingApplicationNeverCloseIt(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            MinosApplication application = MinosApplication.open(home);
            LocalMinosApi api = new LocalMinosApi(application);
            LocalMinosMultiRepositoryApi multi = new LocalMinosMultiRepositoryApi(application);
            LocalProviderPlatformApi providers = new LocalProviderPlatformApi(application);

            api.close();
            multi.close();
            providers.close();
            assertEquals(0, CloseCountingStorageProvider.closes(), "l'application appartient à son ouvreur");

            application.close();
            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }
}
