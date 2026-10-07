package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.bootstrap.CloseCountingStorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Q10 (V-L3-03) : {@code MinosMcpTools} ouvert sur un {@code MINOS_HOME} possède son application et la ferme dans
 * {@code close()} ; construit sur une application reçue, il ne la ferme pas. Observé sur un stockage qui compte ses
 * fermetures ({@link CloseCountingStorageProvider}), sans point d'injection en production.
 */
class OwnedApplicationLifecycleTest {

    @Test
    void toolsOpenedOnAHomeCloseTheirApplication(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            MinosMcpTools tools = new MinosMcpTools(home);
            assertEquals(1, CloseCountingStorageProvider.opens());
            assertEquals(0, CloseCountingStorageProvider.closes());

            tools.close();

            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }

    @Test
    void theServerOpenedOnAHomeClosesItsApplicationWhenTheSessionEnds(@TempDir Path home) throws Exception {
        java.io.InputStream previous = System.in;
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            System.setIn(new java.io.ByteArrayInputStream(new byte[0]));

            MinosMcpServer.run(home);

            assertEquals(1, CloseCountingStorageProvider.opens());
            assertEquals(1, CloseCountingStorageProvider.closes());
        } finally {
            System.setIn(previous);
        }
    }

    @Test
    void theServerOnAnExistingApplicationNeverClosesIt(@TempDir Path home) throws Exception {
        java.io.InputStream previous = System.in;
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            MinosApplication application = MinosApplication.open(home);
            System.setIn(new java.io.ByteArrayInputStream(new byte[0]));

            MinosMcpServer.run(application);

            assertEquals(0, CloseCountingStorageProvider.closes(), "l'application appartient à son ouvreur");
            application.close();
            assertEquals(1, CloseCountingStorageProvider.closes());
        } finally {
            System.setIn(previous);
        }
    }
}
