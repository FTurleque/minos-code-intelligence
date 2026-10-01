package com.minos.registry;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Une réponse que le registre ne peut pas donner avec certitude parce que des entrées sont illisibles (Q24).
 *
 * <p>Elle porte les {@link DegradedEntry} que le registre a déjà produites : il n'y a pas de seconde notion d'entrée
 * dégradée. Son message ne dit que le nombre d'entrées et la conséquence, jamais un chemin ni un texte lu dans un
 * fichier abîmé. C'est le signal ; la commande qui la reçoit décide du verdict : une résolution par nom en fait un
 * résultat partiel, une mutation un échec.</p>
 */
public final class UnreadableRegistryException extends IOException {

    /** Transient: an exception is serializable, a {@link DegradedEntry} is not; the count stays in the message. */
    private final transient List<DegradedEntry> unreadable;

    private UnreadableRegistryException(List<DegradedEntry> unreadable, String consequence) {
        super(describe(unreadable.size()) + ", so " + consequence);
        this.unreadable = unreadable;
    }

    /** {@code consequence} dit ce que ce dénombrement empêche de garantir ; {@code unreadable} n'est pas vide. */
    public static UnreadableRegistryException of(List<DegradedEntry> unreadable, String consequence) {
        List<DegradedEntry> copy = List.copyOf(Objects.requireNonNull(unreadable, "unreadable"));
        if (copy.isEmpty()) throw new IllegalArgumentException("unreadable must not be empty");
        return new UnreadableRegistryException(copy, Objects.requireNonNull(consequence, "consequence"));
    }

    /**
     * Ce qui explique l'échec d'une lecture stricte du registre : l'exception véridique si des entrées sont illisibles,
     * vide sinon (l'échec est alors autre chose et l'appelant le relève tel quel). Une panne du registre lui-même,
     * qui ne peut pas être inventoriée, n'est jamais présentée comme des entrées illisibles.
     */
    public static Optional<UnreadableRegistryException> explaining(ProjectRegistry registry, String consequence) {
        try {
            List<DegradedEntry> unreadable = Objects.requireNonNull(registry, "registry").inventory().unreadable();
            return unreadable.isEmpty() ? Optional.empty() : Optional.of(of(unreadable, consequence));
        } catch (IOException | RuntimeException inventoryFailure) {
            return Optional.empty();
        }
    }

    /** Les entrées illisibles, telles que le registre les a décrites. */
    public List<DegradedEntry> unreadable() {
        return unreadable;
    }

    /** « 1 registry entry is unreadable » ou « N registry entries are unreadable », la forme du message et de l'avertissement. */
    public static String describe(int count) {
        return count == 1 ? "1 registry entry is unreadable" : count + " registry entries are unreadable";
    }
}
