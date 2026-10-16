package com.minos.intellij.protocol;

/** The text of {@link MinosRegistryNotice}, composed without the platform so it is tested in plain JUnit (AUD-TST-05). */
public final class MinosRegistryNoticeText {

    private MinosRegistryNoticeText() {
    }

    /** {@code unreadable} is the CLI's own wording, for instance « 1 registry entry is unreadable ». */
    public static String message(String unreadable) {
        String what = unreadable == null || unreadable.isBlank() ? "some registry entries are unreadable" : unreadable.trim();
        return "MINOS: " + what + ". The project works; run `minos project list` to see which.";
    }
}
