package com.minos.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Q8 sur l'API Java : une entrée de registre abîmée ne fait plus échouer {@code listProjects} ; le projet concerné
 * est une ligne à l'état explicite {@code UNREADABLE}, sans run reprenable, et les autres sont rendus comme avant.
 */
class LocalMinosApiDamagedRegistryTest {

    @Test
    void aDamagedRegistryEntryIsAnUnreadableRowInsteadOfAFailureOfTheWholeListing(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        MinosApi api = new LocalMinosApi(home);
        Path first = Files.createDirectories(temp.resolve("first"));
        Path second = Files.createDirectories(temp.resolve("second"));
        MinosApi.ProjectDto damaged = api.addProject(first, "first");
        MinosApi.ProjectDto healthy = api.addProject(second, "second");
        Path file = home.resolve("registry").resolve("projects").resolve(damaged.id() + ".properties");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("createdAt=.*", "createdAt=not-an-instant"), StandardCharsets.UTF_8);

        List<MinosApi.ProjectDto> projects = api.listProjects();

        assertEquals(2, projects.size());
        MinosApi.ProjectDto row = projects.stream().filter(project -> project.id().equals(damaged.id())).findFirst().orElseThrow();
        assertEquals("UNREADABLE", row.indexState());
        assertNull(row.resumableRunId());
        MinosApi.ProjectDto ok = projects.stream().filter(project -> project.id().equals(healthy.id())).findFirst().orElseThrow();
        assertEquals("NEVER_INDEXED", ok.indexState());
    }
}
