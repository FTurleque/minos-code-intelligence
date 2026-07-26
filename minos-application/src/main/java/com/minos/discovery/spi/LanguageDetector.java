package com.minos.discovery.spi;

import com.minos.discovery.ProjectDiscovery.Language;

import java.nio.file.Path;
import java.util.Optional;

/** Classifies one source file by language; no filesystem traversal policy is embedded here. */
@FunctionalInterface
public interface LanguageDetector {
    Optional<Language> detect(Path file);
}
