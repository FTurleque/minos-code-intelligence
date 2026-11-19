package com.minos.program.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * The completion the composition root applies to a list of program graph providers, for the tests of this module,
 * which do not see {@code minos-bootstrap}: the Java source analyser beside the relationship provider, the sidecar
 * reader always.
 */
public final class TestProgramGraphProviders {

    private TestProgramGraphProviders() {
    }

    /** The relationship provider and the Java source analyser, completed. */
    public static List<ProgramGraphProvider> production() {
        return completed(List.of(new RelationshipProgramGraphProvider(), new JavaSourceProgramGraphProvider()));
    }

    /** The given providers completed as the composition root does. */
    public static List<ProgramGraphProvider> completed(List<ProgramGraphProvider> requested) {
        List<ProgramGraphProvider> completed = new ArrayList<>(requested);
        boolean relationships = has(completed, RelationshipProgramGraphProvider.PROVIDER_ID);
        if (relationships && !has(completed, JavaSourceProgramGraphProvider.PROVIDER_ID)) {
            completed.add(new JavaSourceProgramGraphProvider());
        }
        if (!has(completed, FileProgramGraphProvider.PROVIDER_ID)) {
            completed.add(new FileProgramGraphProvider());
        }
        return List.copyOf(completed);
    }

    /** The completion of a list received as an untyped argument (a proxy of the composition root port). */
    public static List<ProgramGraphProvider> completedArgument(Object requested) {
        List<ProgramGraphProvider> providers = new ArrayList<>();
        for (Object provider : (List<?>) requested) {
            providers.add((ProgramGraphProvider) provider);
        }
        return completed(providers);
    }

    private static boolean has(List<ProgramGraphProvider> providers, String id) {
        return providers.stream().anyMatch(provider -> id.equals(provider.id()));
    }
}
