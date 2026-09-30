package com.minos.cli;

import com.minos.output.SymbolOutputFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Règles uniformes de l'analyse d'options (Q11), sans commande. */
class CliOptionsTest {

    private static final CliOptions.Spec SPEC = CliOptions.spec()
            .text("--module", "--format")
            .integer("--limit", 1, 1000)
            .flag("--dry-run");

    private static String failure(CliOptions.Spec spec, String... arguments) {
        return assertThrows(IllegalArgumentException.class, () -> spec.parse(arguments, 0)).getMessage();
    }

    @Test
    void readsValuesFlagsAndDefaults() {
        CliOptions options = SPEC.parse(new String[]{"--limit", "5", "--dry-run", "--module", "core"}, 0);

        assertEquals(5, options.integer("--limit", 20));
        assertEquals("core", options.text("--module"));
        assertTrue(options.has("--dry-run"));
        assertTrue(options.has("--module"));
        assertEquals(SymbolOutputFormat.TEXT, options.format());

        CliOptions empty = SPEC.parse(new String[0], 0);
        assertEquals(20, empty.integer("--limit", 20));
        assertNull(empty.text("--module"));
        assertEquals("fallback", empty.text("--module", "fallback"));
        assertFalse(empty.has("--dry-run"));
    }

    @Test
    void parsesFromTheGivenOffset() {
        CliOptions options = SPEC.parse(new String[]{"project", "symbol", "--limit", "3"}, 2);
        assertEquals(3, options.integer("--limit", 20));
    }

    @Test
    void aMissingValueIsReportedWhateverTheReason() {
        assertEquals("missing value for --module", failure(SPEC, "--module"));
        assertEquals("missing value for --module", failure(SPEC, "--module", ""));
        assertEquals("missing value for --module", failure(SPEC, "--module", "   "));
        assertEquals("missing value for --limit", failure(SPEC, "--limit"));
    }

    @Test
    void aValueThatLooksLikeAnOptionIsNeverSwallowed() {
        assertEquals("missing value for --module", failure(SPEC, "--module", "--limit", "5"));
        assertEquals("missing value for --format", failure(SPEC, "--format", "--x"));
        assertEquals("missing value for --limit", failure(SPEC, "--limit", "--dry-run"));
    }

    @Test
    void aValueWithASingleDashIsAValue() {
        assertEquals("-x", SPEC.parse(new String[]{"--module", "-x"}, 0).text("--module"));
        assertEquals("--limit must be between 1 and 1000", failure(SPEC, "--limit", "-5"));
        assertEquals("--limit must be an integer", failure(SPEC, "--limit", "-x"));
    }

    @Test
    void aRepeatedOptionIsRefusedForValuesAndFlagsAlike() {
        assertEquals("duplicate option: --module", failure(SPEC, "--module", "a", "--module", "a"));
        assertEquals("duplicate option: --limit", failure(SPEC, "--limit", "1", "--limit", "2"));
        assertEquals("duplicate option: --dry-run", failure(SPEC, "--dry-run", "--dry-run"));
    }

    @Test
    void anUnknownOptionOrAStrayArgumentIsRefused() {
        assertEquals("unknown option: --bogus", failure(SPEC, "--bogus", "x"));
        assertEquals("unknown option: --", failure(SPEC, "--"));
        assertEquals("unknown option: -x", failure(SPEC, "-x"));
        assertEquals("unexpected argument: stray", failure(SPEC, "stray"));
        assertEquals("unexpected argument: stray", failure(SPEC, "--limit", "1", "stray"));
    }

    @Test
    void optionNamesAreCaseSensitiveButValuesOfEnumeratedOptionsAreNot() {
        assertEquals("unknown option: --LIMIT", failure(SPEC, "--LIMIT", "5"));
        assertEquals("unknown option: --Format", failure(SPEC, "--Format", "json"));
        assertEquals(SymbolOutputFormat.JSON, SPEC.parse(new String[]{"--format", "JSON"}, 0).format());
        assertEquals(SymbolOutputFormat.JSON, SPEC.parse(new String[]{"--format", "Json"}, 0).format());
    }

    @Test
    void integerBoundsAreCheckedWhileAnalysing() {
        assertEquals(1, SPEC.parse(new String[]{"--limit", "1"}, 0).integer("--limit", 0));
        assertEquals(1000, SPEC.parse(new String[]{"--limit", "1000"}, 0).integer("--limit", 0));
        assertEquals("--limit must be between 1 and 1000", failure(SPEC, "--limit", "0"));
        assertEquals("--limit must be between 1 and 1000", failure(SPEC, "--limit", "1001"));
        assertEquals("--limit must be an integer", failure(SPEC, "--limit", "abc"));
        assertEquals("--limit must be an integer", failure(SPEC, "--limit", "99999999999"));
    }

    @Test
    void aNullArgumentIsRefused() {
        assertEquals("argument at index 1 must not be null", failure(SPEC, "--dry-run", null));
    }

    @Test
    void freeOperandsAreAcceptedOnlyUpToTheDeclaredCount() {
        CliOptions.Spec spec = CliOptions.spec().text("--format").operands(1);

        CliOptions options = spec.parse(new String[]{"--format", "json", "alpha"}, 0);
        assertEquals(List.of("alpha"), options.operands());
        assertEquals("unexpected argument: beta", failure(spec, "alpha", "beta"));
        assertEquals("unknown option: -x", failure(spec, "-x"));
    }

    @Test
    void aScopeNamesTheCommandFamilyInTheMessages() {
        CliOptions.Spec spec = CliOptions.spec().scope("team ").text("--name");
        assertEquals("unknown team option: --bogus", failure(spec, "--bogus", "x"));
        assertEquals("duplicate team option: --name", failure(spec, "--name", "a", "--name", "b"));
        assertEquals("unexpected team argument: stray", failure(spec, "stray"));
        assertEquals("missing value for --name", failure(spec, "--name"));
    }

    @Test
    void aForbiddenOptionIsRefusedWithItsOwnMessage() {
        CliOptions.Spec spec = CliOptions.spec().forbid("--token", "use the environment").text("--name");
        assertEquals("use the environment", failure(spec, "--token", "secret"));
        assertEquals("use the environment", failure(spec, "--name", "n", "--token"));
    }

    @Test
    void readingAnUndeclaredOptionIsAProgrammingError() {
        CliOptions options = SPEC.parse(new String[0], 0);
        assertThrows(IllegalStateException.class, () -> options.text("--undeclared"));
        assertThrows(IllegalStateException.class, () -> options.integer("--module", 1));
        assertThrows(IllegalStateException.class, () -> options.has("--undeclared"));
    }

    @Test
    void theDeclarationIsExposedInOrderForGuards() {
        List<CliOptions.Declared> declared = SPEC.declared();
        assertEquals(List.of("--module", "--format", "--limit", "--dry-run"),
                declared.stream().map(CliOptions.Declared::name).toList());
        assertEquals(CliOptions.Kind.INTEGER, declared.get(2).kind());
        assertEquals(1, declared.get(2).minimum());
        assertEquals(1000, declared.get(2).maximum());
    }

    @Test
    void aSpecRefusesMalformedDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.spec().text("-m"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.spec().text("--"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.spec().text("--a").flag("--a"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.spec().integer("--n", 2, 1));
    }
}
