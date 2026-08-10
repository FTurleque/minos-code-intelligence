package com.minos.adapter.scip.runtime;

import com.minos.source.SourceBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedProviderSourceProbeTest {

    @Test
    void probeRespectsMinosIgnoreAndNegation(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("ignored"));
        Files.writeString(root.resolve(".minosignore"), "*.py\n!visible.py\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("hidden.py"), "print('hidden')", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("visible.py"), "print('visible')", StandardCharsets.UTF_8);

        assertTrue(BoundedProviderSourceProbe.contains(
                root, Integer.MAX_VALUE, "test", name -> name.endsWith(".py")));

        Files.delete(root.resolve("visible.py"));
        assertFalse(BoundedProviderSourceProbe.contains(
                root, Integer.MAX_VALUE, "test", name -> name.endsWith(".py")));
    }

    @Test
    void probeFailsClosedWhenVisibleSourceBudgetIsExceeded(@TempDir Path root) throws Exception {
        Files.write(root.resolve("large.txt"), new byte[64]);
        Files.writeString(root.resolve("project.csproj"), "<Project/>", StandardCharsets.UTF_8);

        SourceBudgetPolicy tiny = new SourceBudgetPolicy(100, 32);
        assertThrows(IOException.class, () -> BoundedProviderSourceProbe.contains(
                root, 3, "test", name -> name.endsWith(".csproj"), tiny));
    }
}
