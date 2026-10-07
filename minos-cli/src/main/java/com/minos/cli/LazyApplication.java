package com.minos.cli;

import com.minos.application.MinosApplication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Le seul point de construction différée du câblage de la ligne de commande (Q21, Q22).
 *
 * <p>Une commande analyse ses arguments sans service ({@link CliOptions}) ; ce qui lui sert à s'exécuter n'est donc
 * ouvert ni construit avant que son analyse ne soit passée, et seulement si elle l'appelle :</p>
 * <ul>
 *   <li>{@link #get()} ouvre {@code MINOS_HOME} à la première utilisation, une seule fois, même appelée par
 *       plusieurs fils ; {@link #close()} ne ferme que ce qu'il a ouvert lui-même ;</li>
 *   <li>{@link #deferred} construit un service à son premier appel, une seule fois ;</li>
 *   <li>{@link #lazy} en tire une poignée qui ne l'appelle qu'à la première méthode invoquée.</li>
 * </ul>
 * <p>Un échec d'ouverture traverse le corps des commandes sous la forme d'une {@link OpenFailure} que le lanceur
 * rapporte comme avant (« MINOS bootstrap failed »), au lieu d'être pris pour un échec de la commande.</p>
 */
final class LazyApplication implements AutoCloseable {

    /** Ouvre l'application ; en production {@link MinosApplication#open}. */
    @FunctionalInterface
    interface Opener {
        MinosApplication open() throws IOException;
    }

    /** Construit un service à partir de l'application ouverte. */
    @FunctionalInterface
    interface ServiceFactory<T> {
        T create(MinosApplication application) throws IOException;
    }

    /** L'ouverture de {@code MINOS_HOME} a échoué ; non vérifiée pour traverser les corps de commande jusqu'au lanceur. */
    static final class OpenFailure extends RuntimeException {
        private final Exception failure;

        private OpenFailure(Exception failure) {
            super(failure.getMessage(), failure);
            this.failure = failure;
        }

        /** L'échec d'origine, tel que {@link MinosApplication#open} l'a levé : {@link IOException} ou exception non vérifiée. */
        Exception failure() {
            return failure;
        }

        /** Lève l'échec d'origine à la place de ce relais, avec son type : le contrat de {@code run(Path, …)} est inchangé. */
        IOException rethrow() throws IOException {
            if (failure instanceof IOException io) throw io;
            throw (RuntimeException) failure;
        }
    }

    private final Path home;
    private final Opener opener;
    private final boolean owned;
    private MinosApplication application;
    private boolean closed;

    private LazyApplication(Path home, Opener opener, boolean owned) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.opener = Objects.requireNonNull(opener, "opener");
        this.owned = owned;
    }

    /** Une application que cet objet ouvrira à la demande et fermera. */
    static LazyApplication opening(Path home, Opener opener) {
        return new LazyApplication(home, opener, true);
    }

    /** Une application déjà ouverte par l'appelant, à qui elle appartient : jamais fermée ici. */
    static LazyApplication of(MinosApplication application) {
        MinosApplication opened = Objects.requireNonNull(application, "application");
        LazyApplication lazy = new LazyApplication(opened.home(), () -> opened, false);
        lazy.application = opened;
        return lazy;
    }

    /** Le {@code MINOS_HOME} visé, connu sans l'ouvrir. */
    Path home() {
        return home;
    }

    /** L'application, ouverte à la première demande. */
    synchronized MinosApplication get() {
        if (closed) throw new IllegalStateException("MINOS application already released");
        if (application == null) {
            try {
                application = opener.open();
            } catch (IOException | RuntimeException failure) {
                throw new OpenFailure(failure);
            }
        }
        return application;
    }

    /** Un service construit à son premier appel, une seule fois. */
    <T> Deferred<T> deferred(ServiceFactory<T> factory) {
        return new Deferred<>(this, Objects.requireNonNull(factory, "factory"));
    }

    /** Un service construit à la demande, une seule fois ; {@link Supplier} pour les commandes qui reçoivent un fournisseur. */
    static final class Deferred<T> implements Supplier<T> {
        private final LazyApplication owner;
        private final ServiceFactory<T> factory;
        private T service;

        private Deferred(LazyApplication owner, ServiceFactory<T> factory) {
            this.owner = owner;
            this.factory = factory;
        }

        @Override
        public synchronized T get() {
            if (service == null) {
                MinosApplication opened = owner.get();
                try {
                    service = Objects.requireNonNull(factory.create(opened), "service");
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
            return service;
        }
    }

    /**
     * Une poignée du contrat {@code contract} : chaque appel est transmis à {@code target}, qui n'est appelé (donc
     * construit) qu'à la première méthode invoquée. Les exceptions du service traversent la poignée telles quelles.
     */
    static <T> T lazy(Class<T> contract, Supplier<? extends T> target) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(target, "target");
        Object handle = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> contract.getSimpleName() + " (deferred)";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    try {
                        return method.invoke(target.get(), arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    } catch (UncheckedIOException failure) {
                        if (declares(method, IOException.class)) throw failure.getCause();
                        throw failure;
                    }
                });
        return contract.cast(handle);
    }

    private static boolean declares(java.lang.reflect.Method method, Class<? extends Exception> exception) {
        for (Class<?> declared : method.getExceptionTypes()) {
            if (declared.isAssignableFrom(exception)) return true;
        }
        return false;
    }

    /** Ferme l'application si, et seulement si, elle a été ouverte ici ; sans effet sinon, et la seconde fois. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (owned && application != null) application.close();
    }
}
