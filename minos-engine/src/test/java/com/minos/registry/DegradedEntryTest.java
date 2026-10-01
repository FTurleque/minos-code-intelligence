package com.minos.registry;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q8 (V-L5-01) : ce qu'une entrée dégradée dit est destiné à un terminal. Un fichier abîmé ou forgé ne doit pas
 * pouvoir y émettre de séquence de contrôle, et aucun chemin absolu ne doit y figurer.
 */
class DegradedEntryTest {

    private static final String WHAT = "registry entry is unreadable";

    /** ESC, BEL, NUL, CSI sur 8 bits (C1), inversion bidirectionnelle, séparateur de ligne Unicode. */
    private static final String HOSTILE = "bad \u001b[2J value \u0007 \u0000 \u009b \u202e \u2028 end";

    @Test
    void aFailureMessageCarryingTerminalControlsReachesTheReasonWithoutThem() {
        DegradedEntry entry = DegradedEntry.of("entry", WHAT, new IllegalArgumentException(HOSTILE));

        assertNoControlOrFormatCharacter(entry.reason());
        assertTrue(entry.reason().startsWith(WHAT + " (bad "), entry.reason());
        assertTrue(entry.reason().contains("value"), "the readable part of the message survives: " + entry.reason());
        assertTrue(entry.reason().endsWith("end)"), entry.reason());
    }

    @Test
    void aLineBreakInAMessageBecomesASpaceSoTheReasonStaysOnOneLine() {
        DegradedEntry entry = DegradedEntry.of("entry", WHAT, new IllegalStateException("first\nsecond\r\nthird"));

        assertEquals(WHAT + " (first second  third)", entry.reason());
    }

    @Test
    void aFailureMessageThatCarriesAnAbsolutePathFallsBackToTheClassName() {
        DegradedEntry entry = DegradedEntry.of("entry", WHAT, new IOException("cannot read C:\\Users\\someone\\x.properties"));

        assertEquals(WHAT + " (IOException)", entry.reason());
    }

    @Test
    void aFailureWithoutAMessageIsNamedByItsClass() {
        DegradedEntry entry = DegradedEntry.of("entry", WHAT, new IllegalStateException());

        assertEquals(WHAT + " (IllegalStateException)", entry.reason());
    }

    @Test
    void theEntryNameIsReducedToSafeCharactersAndBounded() {
        assertEquals("na_me_with_seps", DegradedEntry.of("na\u001bme/with\\seps", WHAT).entry());
        assertEquals("a".repeat(64), DegradedEntry.of("a".repeat(500), WHAT).entry());
        assertEquals("unnamed", DegradedEntry.of("  ", WHAT).entry());
        assertEquals("unnamed", DegradedEntry.of(null, WHAT).entry());
        assertEquals("caf_", DegradedEntry.of("caf\u00e9", WHAT).entry(), "non-ASCII characters are replaced");
    }

    @Test
    void anEntryAndAReasonAreNeverBlank() {
        assertThrows(IllegalArgumentException.class, () -> new DegradedEntry(" ", WHAT));
        assertThrows(IllegalArgumentException.class, () -> new DegradedEntry("entry", ""));
    }

    private static void assertNoControlOrFormatCharacter(String text) {
        text.chars().forEach(value -> {
            int type = Character.getType(value);
            assertFalse(Character.isISOControl(value) || type == Character.FORMAT
                            || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR,
                    "unexpected character U+" + Integer.toHexString(value) + " in: " + text);
        });
    }
}
