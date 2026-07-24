package com.minos.runtime;

/** Version observable de l'artefact MINOS courant. */
public final class MinosVersion {

    private static final String DEVELOPMENT_VERSION = "0.2.0-SNAPSHOT";

    private MinosVersion() {
    }

    public static String current() {
        String packaged = MinosVersion.class.getPackage().getImplementationVersion();
        return packaged == null || packaged.isBlank() ? DEVELOPMENT_VERSION : packaged;
    }
}
