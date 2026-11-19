package com.minos.cli.operations;

import com.minos.application.indexing.IndexExecutionSummary;
import com.minos.application.indexing.IndexPlanSummary;
import com.minos.application.indexing.ProviderRuntimeSummary;
import com.minos.application.indexing.ResumeTraceSummary;
import com.minos.application.remote.RemoteIndexSummary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The CLI keeps a view of an application summary only where the view sanitizes text or changes the shape of a
 * component. Each kept view must still carry every component of its summary, by name, by a declared renaming, or
 * through an exclusion with a written reason; a component added to the summary alone fails this test.
 */
class CliViewParityTest {

    /** Summary component renamed in the view, per view. */
    private static final Map<Class<?>, Map<String, String>> RENAMINGS = Map.of(
            AutonomousIndexOperations.ProviderView.class, Map.of("providerId", "id"));

    /** Summary component deliberately absent from the view, with its reason, per view. */
    private static final Map<Class<?>, Map<String, String>> EXCLUSIONS = Map.of();

    private static final List<Pair> KEPT_VIEWS = List.of(
            new Pair(ProviderRuntimeSummary.class, AutonomousIndexOperations.ProviderView.class),
            new Pair(IndexPlanSummary.class, AutonomousIndexOperations.IndexPlanView.class),
            new Pair(ResumeTraceSummary.class, AutonomousIndexOperations.ResumeView.class),
            new Pair(IndexExecutionSummary.class, AutonomousIndexOperations.IndexExecutionView.class),
            new Pair(RemoteIndexSummary.class, RemoteIndexOperations.RemoteIndexView.class));

    @Test
    void everyKeptViewCoversEveryComponentOfItsSummary() {
        List<String> failures = new ArrayList<>();
        for (Pair pair : KEPT_VIEWS) {
            failures.addAll(compare(pair.summary(), pair.view(),
                    RENAMINGS.getOrDefault(pair.view(), Map.of()),
                    EXCLUSIONS.getOrDefault(pair.view(), Map.of())));
        }

        assertEquals(List.of(), failures);
    }

    @Test
    void everyDeclaredRenamingOrExclusionNamesAKeptView() {
        Set<Class<?>> views = new LinkedHashSet<>();
        KEPT_VIEWS.forEach(pair -> views.add(pair.view()));

        assertEquals(Set.of(), difference(RENAMINGS.keySet(), views));
        assertEquals(Set.of(), difference(EXCLUSIONS.keySet(), views));
    }

    @Test
    void aComponentAddedToTheSummaryAloneIsRefused() {
        List<String> failures = compare(SampleSummary.class, SampleView.class, Map.of("id", "identifier"), Map.of());

        assertEquals(List.of("SampleSummary.extra has no component in SampleView"), failures);
    }

    @Test
    void anExclusionWithoutAReasonIsRefused() {
        List<String> failures = compare(SampleSummary.class, SampleView.class, Map.of("id", "identifier"),
                Map.of("extra", " "));

        assertEquals(List.of("exclusion SampleSummary.extra has no written reason"), failures);
    }

    @Test
    void aStaleExclusionIsRefused() {
        List<String> failures = compare(SampleSummary.class, SampleView.class, Map.of("id", "identifier"),
                Map.of("extra", "kept out of the CLI output", "removed", "no longer declared"));

        assertEquals(List.of("exclusion SampleSummary.removed names no component of the summary: remove it"),
                failures);
    }

    @Test
    void aStaleRenamingIsRefused() {
        List<String> failures = compare(SampleSummary.class, SampleView.class,
                Map.of("id", "identifier", "gone", "elsewhere"), Map.of("extra", "kept out of the CLI output"));

        assertEquals(List.of("renaming SampleSummary.gone names no component of the summary: remove it"),
                failures);
    }

    @Test
    void aRenamingToAMissingViewComponentIsRefused() {
        List<String> failures = compare(SampleSummary.class, SampleView.class, Map.of("id", "missing"),
                Map.of("extra", "kept out of the CLI output"));

        assertEquals(List.of("SampleSummary.id has no component in SampleView"), failures);
    }

    static List<String> compare(Class<? extends Record> summary, Class<? extends Record> view,
                                Map<String, String> renamings, Map<String, String> exclusions) {
        Set<String> summaryComponents = names(summary);
        Set<String> viewComponents = names(view);
        List<String> failures = new ArrayList<>();
        renamings.keySet().stream().filter(name -> !summaryComponents.contains(name)).sorted()
                .forEach(name -> failures.add("renaming " + summary.getSimpleName() + "." + name
                        + " names no component of the summary: remove it"));
        exclusions.keySet().stream().sorted().forEach(name -> {
            if (!summaryComponents.contains(name)) {
                failures.add("exclusion " + summary.getSimpleName() + "." + name
                        + " names no component of the summary: remove it");
            } else if (exclusions.get(name).isBlank()) {
                failures.add("exclusion " + summary.getSimpleName() + "." + name + " has no written reason");
            }
        });
        for (String component : summaryComponents) {
            if (exclusions.containsKey(component)) {
                continue;
            }
            if (!viewComponents.contains(renamings.getOrDefault(component, component))) {
                failures.add(summary.getSimpleName() + "." + component + " has no component in "
                        + view.getSimpleName());
            }
        }
        return List.copyOf(failures);
    }

    private static Set<String> names(Class<? extends Record> type) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).forEach(names::add);
        return names;
    }

    private static Set<Class<?>> difference(Set<Class<?>> left, Set<Class<?>> right) {
        Set<Class<?>> difference = new LinkedHashSet<>(left);
        difference.removeAll(right);
        return difference;
    }

    private record Pair(Class<? extends Record> summary, Class<? extends Record> view) {
    }

    private record SampleSummary(String id, String name, String extra) {
    }

    private record SampleView(String identifier, String name) {
    }
}
