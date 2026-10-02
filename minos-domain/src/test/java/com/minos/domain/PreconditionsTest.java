package com.minos.domain;

import org.junit.jupiter.api.Test;

import static com.minos.domain.Preconditions.requireText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreconditionsTest {

    @Test
    void returnsTheSameTextUntouched() {
        String value = "  keeps its blanks  ";

        assertSame(value, requireText(value, "field"));
    }

    @Test
    void refusesNullWithTheNameOfTheField() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> requireText(null, "projectId"));

        assertEquals("projectId must not be blank", failure.getMessage());
    }

    @Test
    void refusesEveryBlankText() {
        for (String blank : new String[] {"", " ", "\t", "\n", " \r\n\t ", " "}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> requireText(blank, "label"), "blank: [" + blank + "]");
            assertEquals("label must not be blank", failure.getMessage());
        }
    }

    @Test
    void theNameIsPartOfTheMessageAsGiven() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> requireText("", "lease key"));

        assertEquals("lease key must not be blank", failure.getMessage());
    }
}
