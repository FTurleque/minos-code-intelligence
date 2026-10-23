package com.minos.application.indexing;

import com.minos.orchestration.IndexingResumePolicy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AUD-ARC-01 (D2): the resume choice a surface passes has one value per value of the engine policy, with the same
 * name. A value present on one side only fails here, named.
 */
class IndexingResumeChoiceTest {

    @Test
    void everyEnginePolicyHasAChoiceOfTheSameNameAndBack() {
        Set<String> choices = names(IndexingResumeChoice.values());
        Set<String> policies = names(IndexingResumePolicy.values());

        assertEquals(Set.of(), difference(policies, choices), "engine policies without an application choice");
        assertEquals(Set.of(), difference(choices, policies), "application choices without an engine policy");
        for (IndexingResumeChoice choice : IndexingResumeChoice.values()) {
            assertEquals(choice.name(), choice.policy().name());
        }
        assertEquals(List.of(IndexingResumeChoice.values()).size(), IndexingResumePolicy.values().length);
    }

    private static Set<String> names(Enum<?>[] values) {
        return new TreeSet<>(Arrays.stream(values).map(Enum::name).toList());
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> missing = new TreeSet<>(left);
        missing.removeAll(right);
        return missing;
    }
}
