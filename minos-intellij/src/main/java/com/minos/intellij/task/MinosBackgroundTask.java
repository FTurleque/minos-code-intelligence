package com.minos.intellij.task;

import com.intellij.openapi.diagnostic.Logger;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

/**
 * Exécute l'opération d'une tâche d'arrière-plan du plugin sans jamais avaler une exception.
 *
 * <p>L'annulation ({@link CancellationException}, dont hérite {@code ProcessCanceledException}) et les {@link Error} sont relancées : la plateforme
 * les traite. Toute autre exception est journalisée avec sa pile (fichier {@code idea.log} de
 * l'utilisateur) puis rendue à l'appelant, qui l'affiche. Le package est une feuille : il ne dépend ni
 * de {@code ui} ni de {@code actions}.</p>
 */
public final class MinosBackgroundTask {

    /** Destination du journal d'un échec ; un test y met une liste, la production le journal de l'IDE. */
    @FunctionalInterface
    public interface FailureLog {
        void warn(String message, Throwable failure);
    }

    /** Résultat d'une opération : l'un des deux champs est {@code null}. */
    public record Outcome<T>(T result, Throwable failure) {
    }

    private final FailureLog log;

    public MinosBackgroundTask(FailureLog log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Une tâche qui journalise dans {@code idea.log}. */
    public static MinosBackgroundTask withIdeLog() {
        Logger logger = Logger.getInstance(MinosBackgroundTask.class);
        return new MinosBackgroundTask(logger::warn);
    }

    public <T> Outcome<T> run(String title, Callable<T> operation) {
        try {
            return new Outcome<>(operation.call(), null);
        } catch (CancellationException canceled) {
            throw canceled;
        } catch (Exception failure) {
            log.warn("MINOS task failed: " + title + " (" + failure.getClass().getSimpleName() + ")", failure);
            return new Outcome<>(null, failure);
        }
    }

    /** Texte montré à l'utilisateur : le message, ou le type quand il manque. */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "Unknown failure";
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "Unknown failure (" + failure.getClass().getSimpleName() + ")";
        }
        return message;
    }
}
