package com.minos.cli;

import com.minos.application.LocalProjectOperations;
import com.minos.application.MinosApplication;
import com.minos.bootstrap.CloseCountingStorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Q10 (V-L3-03) : les objets qui ouvrent eux-mêmes leur application à partir d'un {@code MINOS_HOME} la possèdent et
 * la ferment dans {@code close()} ; ceux qui la reçoivent ne la ferment pas. Observé sur un stockage qui compte ses
 * fermetures ({@link CloseCountingStorageProvider}), sans point d'injection en production.
 */
class OwnedApplicationLifecycleTest {

    @Test
    void localProjectOperationsOpenedOnAHomeClosesItsApplication(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            LocalProjectOperations operations = new LocalProjectOperations(home);
            assertEquals(1, CloseCountingStorageProvider.opens());
            assertEquals(0, CloseCountingStorageProvider.closes());

            operations.close();

            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }

    @Test
    void localAutonomousIndexOperationsOpenedOnAHomeClosesItsApplication(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            LocalAutonomousIndexOperations operations = new LocalAutonomousIndexOperations(home);
            assertEquals(1, CloseCountingStorageProvider.opens());
            assertEquals(0, CloseCountingStorageProvider.closes());

            operations.close();

            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }

    @Test
    void operationsBuiltOnAnExistingApplicationNeverCloseIt(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            MinosApplication application = MinosApplication.open(home);
            LocalProjectOperations projects = new LocalProjectOperations(application);
            LocalAutonomousIndexOperations indexing = new LocalAutonomousIndexOperations(application);

            projects.close();
            indexing.close();
            assertEquals(0, CloseCountingStorageProvider.closes(), "l'application appartient à son ouvreur");

            application.close();
            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }

    @Test
    void theRunnerClosesTheRealApplicationItOpensForACommand(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            int exit = MinosCliRunner.run(home, new String[]{"project", "list", "--format", "json"}, output, error);

            assertEquals(0, exit, error.toString());
            assertEquals(1, CloseCountingStorageProvider.opens());
            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }
}
