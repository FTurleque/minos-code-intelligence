package com.minos.storage.local.registry;

import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q26 : les espaces de travail comme inventaire. Un fichier de projet abîmé borne son dégât à son entrée (le
 * rattachement des autres reste établi et le compte le dit), un fichier d'espace abîmé est un espace qui manque à la
 * liste et qu'on compte, et un registre qu'on ne peut pas lister du tout reste un échec. La liste stricte, elle, ne change pas.
 */
class LocalProjectRegistryWorkspaceInventoryTest {

    private static final String PROPERTIES = ".properties";

    @TempDir
    Path temp;

    private Path storage;
    private LocalProjectRegistry registry;
    private RegisteredWorkspace platform;
    private RegisteredWorkspace tooling;
    private RegisteredProject alpha;
    private RegisteredProject beta;
    private RegisteredProject gamma;

    /** Deux espaces ; alpha et beta dans {@code platform}, gamma dans {@code tooling}. */
    private void registry() throws IOException {
        storage = Files.createDirectories(temp.resolve("registry"));
        registry = new LocalProjectRegistry(storage);
        alpha = registry.registerProject(Files.createDirectories(temp.resolve("alpha")), "alpha");
        beta = registry.registerProject(Files.createDirectories(temp.resolve("beta")), "beta");
        gamma = registry.registerProject(Files.createDirectories(temp.resolve("gamma")), "gamma");
        platform = registry.createWorkspace("platform");
        tooling = registry.createWorkspace("tooling");
        registry.assignProjectToWorkspace(alpha.id(), platform.id());
        registry.assignProjectToWorkspace(beta.id(), platform.id());
        registry.assignProjectToWorkspace(gamma.id(), tooling.id());
    }

    private Path projectEntry(RegisteredProject project) {
        return storage.resolve("projects").resolve(project.id() + PROPERTIES);
    }

    private Path workspaceEntry(RegisteredWorkspace workspace) {
        return storage.resolve("workspaces").resolve(workspace.id() + PROPERTIES);
    }

    private static void damage(Path file) throws IOException {
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("createdAt=.*", "createdAt=\u001b[2Jnot-a-date"), StandardCharsets.UTF_8);
    }

    @Test
    void aHealthyRegistryHasTheSameWorkspacesAsTheStrictListAndNothingUnreadable() throws Exception {
        registry();

        ProjectRegistry.WorkspaceInventory inventory = registry.workspaceInventory();

        assertEquals(registry.listWorkspaces(), inventory.workspaces());
        assertEquals(List.of(), inventory.unreadableWorkspaces());
        assertEquals(List.of(), inventory.unreadableProjects());
    }

    @Test
    void aDamagedProjectEntryIsCountedAndTheMembershipOfTheOthersIsStillEstablished() throws Exception {
        registry();
        damage(projectEntry(beta));

        ProjectRegistry.WorkspaceInventory inventory = registry.workspaceInventory();

        assertEquals(2, inventory.workspaces().size(), "both workspaces are established: a project cannot remove one");
        assertEquals(List.of(alpha.id()), membership(inventory, platform),
                "beta is unreadable, so platform's membership is what could be read");
        assertEquals(List.of(gamma.id()), membership(inventory, tooling));
        assertEquals(List.of(), inventory.unreadableWorkspaces());
        assertEquals(1, inventory.unreadableProjects().size());
        assertEquals(beta.id().toString(), inventory.unreadableProjects().getFirst().entry());
        assertCleanReason(inventory.unreadableProjects().getFirst().reason());
    }

    @Test
    void theStrictListStillFailsOnTheSameDamage() throws Exception {
        registry();
        damage(projectEntry(beta));

        assertThrows(RuntimeException.class, registry::listWorkspaces);
        assertThrows(RuntimeException.class, () -> registry.findWorkspace(platform.id()));
    }

    @Test
    void aDamagedWorkspaceEntryIsAWorkspaceThatMayExistAndIsCounted() throws Exception {
        registry();
        damage(workspaceEntry(tooling));

        ProjectRegistry.WorkspaceInventory inventory = registry.workspaceInventory();

        assertEquals(List.of(platform.id()), inventory.workspaces().stream().map(RegisteredWorkspace::id).toList());
        assertEquals(1, inventory.unreadableWorkspaces().size());
        assertEquals(tooling.id().toString(), inventory.unreadableWorkspaces().getFirst().entry());
        assertEquals(List.of(), inventory.unreadableProjects());
        assertCleanReason(inventory.unreadableWorkspaces().getFirst().reason());
    }

    @Test
    void anEntryThatIsNotARegularFileIsReportedNotSkipped() throws Exception {
        registry();
        Files.createDirectory(storage.resolve("workspaces").resolve(UUID.randomUUID() + PROPERTIES));

        ProjectRegistry.WorkspaceInventory inventory = registry.workspaceInventory();

        assertEquals(2, inventory.workspaces().size());
        assertEquals(1, inventory.unreadableWorkspaces().size());
        assertTrue(inventory.unreadableWorkspaces().getFirst().reason().contains("not a regular file"),
                inventory.unreadableWorkspaces().getFirst().reason());
    }

    @Test
    void everyProjectEntryDamagedStillListsEveryWorkspaceWithAnEmptyMembershipAndTheCount() throws Exception {
        registry();
        for (RegisteredProject project : List.of(alpha, beta, gamma)) damage(projectEntry(project));

        ProjectRegistry.WorkspaceInventory inventory = registry.workspaceInventory();

        assertEquals(2, inventory.workspaces().size());
        assertTrue(inventory.workspaces().stream().allMatch(workspace -> workspace.projectIds().isEmpty()));
        assertEquals(3, inventory.unreadableProjects().size());
    }

    @Test
    void everyWorkspaceEntryDamagedIsAnEmptyListWithTheCountNotASilentEmptyRegistry() throws Exception {
        registry();
        damage(workspaceEntry(platform));
        damage(workspaceEntry(tooling));

        ProjectRegistry.WorkspaceInventory inventory = registry.workspaceInventory();

        assertEquals(List.of(), inventory.workspaces());
        assertEquals(2, inventory.unreadableWorkspaces().size());
    }

    @Test
    void aRegistryThatCannotBeListedAtAllIsAFailureNotAnInventory() throws Exception {
        registry();
        Path workspaces = storage.resolve("workspaces");
        for (Path file : Files.list(workspaces).toList()) Files.delete(file);
        Files.delete(workspaces);
        Files.writeString(workspaces, "not a directory", StandardCharsets.UTF_8);

        assertThrows(IOException.class, registry::workspaceInventory);
    }

    @Test
    void theInterProcessDecoratorKeepsTheTolerantViewInsteadOfFallingBackToTheStrictDefault() throws Exception {
        registry();
        damage(projectEntry(beta));
        ProjectRegistry decorated = new InterProcessLocalProjectRegistry(storage);

        ProjectRegistry.WorkspaceInventory inventory = decorated.workspaceInventory();

        assertEquals(2, inventory.workspaces().size());
        assertEquals(1, inventory.unreadableProjects().size());
    }

    private static List<UUID> membership(ProjectRegistry.WorkspaceInventory inventory, RegisteredWorkspace workspace) {
        return inventory.workspaces().stream().filter(candidate -> candidate.id().equals(workspace.id()))
                .findFirst().orElseThrow().projectIds();
    }

    /** Comme pour les projets (Q8) : ni chemin absolu ni caractère de contrôle ; le fragment lu reste connu (CLI-SUIVI § 7). */
    private void assertCleanReason(String reason) {
        assertFalse(reason.contains(temp.toString()), "absolute path in: " + reason);
        assertTrue(reason.chars().noneMatch(Character::isISOControl), "control character in: " + reason);
    }
}
