package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinosRegistryNoticeTextTest {

    @Test
    void theNoticeQuotesTheCliAndSaysHowToSeeTheDamagedEntries() {
        assertEquals("MINOS: 1 registry entry is unreadable. The project works; run `minos project list` to see which.",
                MinosRegistryNoticeText.message("1 registry entry is unreadable"));
    }

    @Test
    void theCliWordingIsTrimmedAndAnEmptyOneStillGivesAUsefulNotice() {
        assertEquals("MINOS: 2 registry entries are unreadable. The project works; run `minos project list` to see which.",
                MinosRegistryNoticeText.message("  2 registry entries are unreadable \n"));
        assertEquals("MINOS: some registry entries are unreadable. The project works; run `minos project list` to see "
                + "which.", MinosRegistryNoticeText.message(null));
        assertEquals("MINOS: some registry entries are unreadable. The project works; run `minos project list` to see "
                + "which.", MinosRegistryNoticeText.message(" "));
    }
}
