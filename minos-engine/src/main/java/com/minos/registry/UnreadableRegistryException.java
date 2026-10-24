package com.minos.registry;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An answer that the registry cannot give with certainty because some entries are unreadable (Q24).
 *
 * <p>It carries the {@link DegradedEntry} values that the registry has already produced: there is no second notion
 * of degraded entry. Its message only states the number of entries and the consequence, never a path nor a text read
 * from a damaged file. It is the signal; the command that receives it decides the verdict: a resolution by name makes
 * it a partial result, a mutation a failure.</p>
 */
public final class UnreadableRegistryException extends IOException {

    /** Transient: an exception is serializable, a {@link DegradedEntry} is not; the count stays in the message. */
    private final transient List<DegradedEntry> unreadable;

    private UnreadableRegistryException(List<DegradedEntry> unreadable, String consequence) {
        super(describe(unreadable.size()) + ", so " + consequence);
        this.unreadable = unreadable;
    }

    /** {@code consequence} says what this count prevents from guaranteeing; {@code unreadable} is not empty. */
    public static UnreadableRegistryException of(List<DegradedEntry> unreadable, String consequence) {
        List<DegradedEntry> copy = List.copyOf(Objects.requireNonNull(unreadable, "unreadable"));
        if (copy.isEmpty()) throw new IllegalArgumentException("unreadable must not be empty");
        return new UnreadableRegistryException(copy, Objects.requireNonNull(consequence, "consequence"));
    }

    /**
     * What explains the failure of a strict read of the registry: the truthful exception if some entries are
     * unreadable, empty otherwise (the failure is then something else and the caller reports it as is). A failure of
     * the registry itself, which cannot be inventoried, is never presented as unreadable entries.
     */
    public static Optional<UnreadableRegistryException> explaining(ProjectRegistry registry, String consequence) {
        try {
            List<DegradedEntry> unreadable = Objects.requireNonNull(registry, "registry").inventory().unreadable();
            return unreadable.isEmpty() ? Optional.empty() : Optional.of(of(unreadable, consequence));
        } catch (IOException | RuntimeException inventoryFailure) {
            return Optional.empty();
        }
    }

    /** The unreadable entries, as the registry described them. */
    public List<DegradedEntry> unreadable() {
        return unreadable;
    }

    /** "1 registry entry is unreadable" or "N registry entries are unreadable": the form of the message and warning. */
    public static String describe(int count) {
        return count == 1 ? "1 registry entry is unreadable" : count + " registry entries are unreadable";
    }
}
