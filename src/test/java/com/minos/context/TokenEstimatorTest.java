package com.minos.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void estimatesUtf8AndTruncatesWithoutSplittingSurrogatePairs() {
        assertEquals(1, TokenEstimator.estimate("abcd"));
        assertEquals(1, TokenEstimator.estimate("é"));

        String truncated = TokenEstimator.truncate("ab😀cd", 1);

        assertTrue(TokenEstimator.estimate(truncated) <= 1);
        assertFalse(truncated.endsWith("\uD83D"));
        assertEquals("", TokenEstimator.truncate("anything", 0));
    }
}
