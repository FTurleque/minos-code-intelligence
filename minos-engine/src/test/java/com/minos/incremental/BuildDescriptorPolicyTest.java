package com.minos.incremental;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildDescriptorPolicyTest {

    private static final Set<String> LEGACY = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "package.json", "package-lock.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", "yarn.lock",
            "pyproject.toml", "setup.py");
    private static final Set<String> POLYGLOT_ADDITIONS = Set.of(
            "CMakeLists.txt", "compile_commands.json", "go.mod", "go.sum", "go.work", "Cargo.toml", "Cargo.lock");

    @Test
    void legacyDefaultsKeepTheJvmJavaScriptAndPythonDescriptors() {
        BuildDescriptorPolicy legacy = BuildDescriptorPolicy.legacyDefaults();

        assertEquals(LEGACY, legacy.fileNames());
        assertTrue(legacy.isBuildDescriptor(Path.of("module", "pom.xml")));
        assertFalse(legacy.isBuildDescriptor(Path.of("go.mod")));
    }

    @Test
    void currentDefaultsAddThePolyglotDescriptorsToTheLegacyOnes() {
        BuildDescriptorPolicy current = BuildDescriptorPolicy.currentDefaults();

        Set<String> expected = new HashSet<>(LEGACY);
        expected.addAll(POLYGLOT_ADDITIONS);
        assertEquals(expected, current.fileNames());
        assertTrue(current.isBuildDescriptor(Path.of("native", "CMakeLists.txt")));
        assertTrue(current.isBuildDescriptor(Path.of("app", "App.csproj")));
    }
}
