package com.minos.intellij;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-TST-05: the logic of the plugin is tested in plain JUnit, without a platform fixture, because it lives in
 * classes that use no IntelliJ type. If one of them starts importing the platform, its tests would need the IDE.
 */
class PlatformFreeLogicTest {

    private static final List<String> PLATFORM_FREE = List.of(
            "service/MinosSymbolRanking.java",
            "protocol/MinosJsonFields.java",
            "protocol/MinosRegistryNoticeText.java",
            "settings/MinosSettingsRules.java");

    @Test
    void theExtractedLogicUsesNoIntelliJType() throws IOException {
        Path sources = Path.of("src", "main", "java", "com", "minos", "intellij");
        for (String relative : PLATFORM_FREE) {
            Path source = sources.resolve(relative);
            assertTrue(Files.isRegularFile(source), "missing " + source.toAbsolutePath());
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("com.intellij."), relative + " uses an IntelliJ type");
        }
    }
}
