package com.minos.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hostile ignore file must never break loading nor make evaluation cost unbounded. */
class ProjectIgnoreRulesHostileInputTest {

    @Test
    void aReversedRangeDoesNotFailTheWholeLoadAndIsCounted(@TempDir Path home) throws Exception {
        ProjectIgnoreRules rules = load(home, "*.class\n[z-a]x\n*.log\n");

        assertTrue(rules.isIgnored(Path.of("Main.class"), false), "rules before the bad one still apply");
        assertTrue(rules.isIgnored(Path.of("debug.log"), false), "rules after the bad one still apply");
        assertFalse(rules.isIgnored(Path.of("zx"), false), "the unusable rule matches nothing");
        assertEquals(1, rules.discardedRuleCount());
    }

    @Test
    void characterClassesAreLiteralInputNotRegexSyntax(@TempDir Path home) throws Exception {
        ProjectIgnoreRules rules = load(home, "[[]open\n[a&&b]x\n[!]\n");

        assertTrue(rules.isIgnored(Path.of("[open"), false), "'[' inside a class is a literal");
        assertTrue(rules.isIgnored(Path.of("&x"), false), "'&&' is not a regex intersection");
        assertTrue(rules.isIgnored(Path.of("ax"), false));
        assertFalse(rules.isIgnored(Path.of("cx"), false));
        assertEquals(1, rules.discardedRuleCount(), "only the empty negated class is unusable");
    }

    @Test
    void repeatedDoubleStarSegmentsCollapseToOne(@TempDir Path home) throws Exception {
        ProjectIgnoreRules rules = load(home, "**/**/**/**/**/**/**/**/**/**/**/**/secret.txt\n");
        String deep = "a/".repeat(500) + "secret.txt";

        assertTrue(rules.isIgnored(Path.of(deep), false));
        assertFalse(rules.isIgnored(Path.of("a/".repeat(500) + "public.txt"), false));
        assertEquals(0, rules.discardedRuleCount());
        assertEquals(0, rules.exhaustedRuleCount());
    }

    @Test
    void aRuleThatExceedsItsEvaluationBudgetIsDisabledNotEvaluatedForever(@TempDir Path home) throws Exception {
        // Eight (the maximum allowed) unanchored star groups against a string of 'a' with no terminating 'b': exponential
        // backtracking in a plain regex. The bound is a step budget (deterministic), not a stopwatch.
        ProjectIgnoreRules rules = load(home, "*a*a*a*a*a*a*a*b\n*.class\n");
        Path hostile = Path.of("a".repeat(200));

        assertFalse(rules.isIgnored(hostile, false));
        assertEquals(1, rules.exhaustedRuleCount());
        assertFalse(rules.isIgnored(hostile, false), "a disabled rule is not evaluated again");
        assertEquals(1, rules.exhaustedRuleCount(), "the rule is counted once");
        assertTrue(rules.isIgnored(Path.of("Main.class"), false), "other rules are unaffected");
    }

    @Test
    void anOversizedOrOverComplexRuleIsRefusedAtCompilation(@TempDir Path home) throws Exception {
        String tooLong = "a".repeat(ProjectIgnoreRules.MAX_RULE_CHARS + 1);
        String tooManyStars = "*a".repeat(ProjectIgnoreRules.MAX_WILDCARDS_PER_RULE + 1);
        ProjectIgnoreRules rules = load(home, tooLong + "\n" + tooManyStars + "\n*.class\n");

        assertEquals(2, rules.discardedRuleCount());
        assertTrue(rules.isIgnored(Path.of("Main.class"), false));
    }

    private static ProjectIgnoreRules load(Path home, String gitignore) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Files.writeString(project.resolve(".gitignore"), gitignore);
        return ProjectIgnoreRules.load(project);
    }
}
