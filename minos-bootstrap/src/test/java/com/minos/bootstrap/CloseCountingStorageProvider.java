package com.minos.bootstrap;

import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackendProvider;
import com.minos.storage.local.LocalStorageBackend;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixture de test : un fournisseur de stockage « postgresql » qui enveloppe le stockage local et compte les
 * fermetures. Une {@code MinosApplication} ouverte par {@code MinosApplication.open(home)} le sélectionne quand
 * {@link #select()} est actif : la fermeture de l'application, que {@code MinosApplication.close()} propage au
 * stockage, devient observable sans point d'injection en production.
 *
 * <p>Le fournisseur n'est découvert que dans les modules dont les ressources de test déclarent, dans
 * {@code META-INF/services/com.minos.storage.StorageBackendProvider}, la classe de ce fichier. Jamais dans
 * {@code minos-bootstrap} même, où le vrai fournisseur PostgreSQL est sur le chemin de classes de test.</p>
 */
public final class CloseCountingStorageProvider implements StorageBackendProvider {

    private static final AtomicInteger CLOSES = new AtomicInteger();
    private static final AtomicInteger OPENS = new AtomicInteger();

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public StorageBackend open(StorageBackendConfiguration configuration) throws IOException {
        OPENS.incrementAndGet();
        StorageBackend delegate = new LocalStorageBackend(configuration.home());
        InvocationHandler handler = (proxy, method, arguments) -> {
            if ("close".equals(method.getName())) CLOSES.incrementAndGet();
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        };
        return (StorageBackend) Proxy.newProxyInstance(
                StorageBackend.class.getClassLoader(), new Class<?>[]{StorageBackend.class}, handler);
    }

    /** Applications ouvertes (fournisseur sélectionné) depuis le début de la sélection. */
    public static int opens() {
        return OPENS.get();
    }

    /** Fermetures du stockage depuis le début de la sélection. */
    public static int closes() {
        return CLOSES.get();
    }

    /**
     * Sélectionne le fournisseur pour {@code MinosApplication.open} jusqu'à la fermeture de la portée retournée,
     * et remet les compteurs à zéro. Les tests qui l'utilisent ne tournent pas en parallèle.
     */
    public static Selection select() {
        String previous = System.getProperty(StorageBackendConfiguration.BACKEND_PROPERTY);
        CLOSES.set(0);
        OPENS.set(0);
        System.setProperty(StorageBackendConfiguration.BACKEND_PROPERTY, "postgresql");
        return () -> {
            if (previous == null) System.clearProperty(StorageBackendConfiguration.BACKEND_PROPERTY);
            else System.setProperty(StorageBackendConfiguration.BACKEND_PROPERTY, previous);
        };
    }

    /** La portée d'une sélection : se ferme sans exception. */
    @FunctionalInterface
    public interface Selection extends AutoCloseable {
        @Override
        void close();
    }
}
