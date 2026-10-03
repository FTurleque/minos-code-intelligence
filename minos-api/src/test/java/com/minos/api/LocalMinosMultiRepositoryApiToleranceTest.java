package com.minos.api;

import com.minos.api.MinosApi.ErrorCode;
import com.minos.api.MinosApi.MinosApiException;
import com.minos.api.MinosMultiRepositoryApi.WorkspaceDto;
import com.minos.api.MinosMultiRepositoryApi.WorkspaceInventoryDto;
import com.minos.api.MinosMultiRepositoryApi.WorkspaceLookupDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q26 : ce qu'un client voit de {@code listWorkspaces} et {@code getWorkspace} avant et après, et ce que disent les deux
 * opérations neuves devant un registre abîmé. La règle numéro un : un client qui ignore l'ajout fonctionne à l'identique.
 */
class LocalMinosMultiRepositoryApiToleranceTest {

    @TempDir
    Path temp;

    private Path home;
    private LocalMinosMultiRepositoryApi api;
    private WorkspaceDto platform;
    private WorkspaceDto tooling;
    private String alphaId;
    private String betaId;

    @AfterEach
    void close() throws Exception {
        if (api != null) api.close();
    }

    /** alpha et beta dans {@code platform}, gamma dans {@code tooling}. */
    private void registry() throws Exception {
        home = Files.createDirectories(temp.resolve("home"));
        api = new LocalMinosMultiRepositoryApi(home);
        alphaId = api.addProject(Files.createDirectories(temp.resolve("alpha")), "alpha").id();
        betaId = api.addProject(Files.createDirectories(temp.resolve("beta")), "beta").id();
        String gammaId = api.addProject(Files.createDirectories(temp.resolve("gamma")), "gamma").id();
        platform = api.createWorkspace("platform");
        tooling = api.createWorkspace("tooling");
        api.assignProjectToWorkspace(alphaId, platform.id());
        api.assignProjectToWorkspace(betaId, platform.id());
        api.assignProjectToWorkspace(gammaId, tooling.id());
    }

    private Path projectEntry(String id) {
        return home.resolve("registry").resolve("projects").resolve(id + ".properties");
    }

    private Path workspaceEntry(String id) {
        return home.resolve("registry").resolve("workspaces").resolve(id + ".properties");
    }

    private static void damage(Path file) throws Exception {
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("createdAt=.*", "createdAt=not-a-date"), StandardCharsets.UTF_8);
    }

    // ---- ce qu'un client existant voit : rien ne change ---------------------------------------------------------

    @Test
    void theExistingContractIsUnchangedAndTheAdditionIsPurelyAdditive() {
        Set<String> abstractMethods = Arrays.stream(MinosMultiRepositoryApi.class.getDeclaredMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals(Set.of("createWorkspace", "listWorkspaces", "getWorkspace", "assignProjectToWorkspace", "inspectGit",
                "analyzeGitActivity", "analyzeWorkspace"), abstractMethods,
                "no abstract method was added: a third-party implementation still compiles");
        for (String added : List.of("listWorkspaceInventory", "lookupWorkspace")) {
            Method method = Arrays.stream(MinosMultiRepositoryApi.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(added)).findFirst().orElseThrow();
            assertTrue(method.isDefault(), added + " must be a default method");
        }
        assertEquals(List.of("id", "name", "projectIds", "createdAt", "updatedAt"),
                Arrays.stream(WorkspaceDto.class.getRecordComponents()).map(RecordComponent::getName).toList(),
                "WorkspaceDto, the element of the existing list, did not gain a component");
        assertEquals("1", MinosMultiRepositoryApi.MULTI_REPOSITORY_CONTRACT_VERSION);
    }

    @Test
    void aClientThatIgnoresTheNewOperationsSeesTheSameAnswersOnAHealthyRegistry() throws Exception {
        registry();

        WorkspaceInventoryDto inventory = api.listWorkspaceInventory();
        WorkspaceLookupDto lookup = api.lookupWorkspace("platform");

        assertEquals(api.listWorkspaces(), inventory.workspaces());
        assertEquals(0, inventory.unreadableProjectEntries());
        assertEquals(0, inventory.unreadableWorkspaceEntries());
        assertEquals(api.getWorkspace("platform"), lookup.workspace());
        assertEquals(0, lookup.unreadableProjectEntries());
        assertEquals(0, lookup.unreadableWorkspaceEntries());
    }

    @Test
    void theExistingOperationsStayStrictOnTheSameDamage() throws Exception {
        registry();
        damage(projectEntry(betaId));

        assertThrows(MinosApiException.class, api::listWorkspaces);
        assertThrows(MinosApiException.class, () -> api.getWorkspace("platform"));
    }

    @Test
    void aThirdPartyImplementationWithoutTheNewMethodsAnswersUnavailableNotACompleteInventory() {
        InvocationHandler handler = (proxy, method, arguments) -> InvocationHandler.invokeDefault(proxy, method, arguments);
        MinosMultiRepositoryApi bare = (MinosMultiRepositoryApi) Proxy.newProxyInstance(
                MinosMultiRepositoryApi.class.getClassLoader(), new Class<?>[]{MinosMultiRepositoryApi.class}, handler);

        assertEquals(ErrorCode.UNAVAILABLE, assertThrows(MinosApiException.class, bare::listWorkspaceInventory).code());
        assertEquals(ErrorCode.UNAVAILABLE, assertThrows(MinosApiException.class, () -> bare.lookupWorkspace("x")).code());
    }

    // ---- l'inventaire ---------------------------------------------------------------------------------------------

    @Test
    void aDamagedProjectEntryIsCountedAndTheOtherMembershipIsStillListed() throws Exception {
        registry();
        damage(projectEntry(betaId));

        WorkspaceInventoryDto inventory = api.listWorkspaceInventory();

        assertEquals(2, inventory.workspaces().size());
        assertEquals(List.of(alphaId), byName(inventory, "platform").projectIds());
        assertEquals(1, inventory.unreadableProjectEntries());
        assertEquals(0, inventory.unreadableWorkspaceEntries());
    }

    @Test
    void aDamagedWorkspaceEntryIsCountedAsAWorkspaceThatMayBeMissing() throws Exception {
        registry();
        damage(workspaceEntry(tooling.id()));

        WorkspaceInventoryDto inventory = api.listWorkspaceInventory();

        assertEquals(List.of("platform"), inventory.workspaces().stream().map(WorkspaceDto::name).toList());
        assertEquals(1, inventory.unreadableWorkspaceEntries());
        assertEquals(0, inventory.unreadableProjectEntries());
    }

    @Test
    void aRegistryWhoseEveryEntryIsDamagedIsListedWithTheCountsNeverAsASilentEmptyList() throws Exception {
        registry();
        damage(workspaceEntry(platform.id()));
        damage(workspaceEntry(tooling.id()));
        for (String id : List.of(alphaId, betaId)) damage(projectEntry(id));

        WorkspaceInventoryDto inventory = api.listWorkspaceInventory();

        assertEquals(List.of(), inventory.workspaces());
        assertEquals(2, inventory.unreadableWorkspaceEntries());
        assertEquals(2, inventory.unreadableProjectEntries());
    }

    @Test
    void aRegistryThatCannotBeListedIsAFailureNotAnInventory() throws Exception {
        registry();
        Path workspaces = home.resolve("registry").resolve("workspaces");
        for (Path file : Files.list(workspaces).toList()) Files.delete(file);
        Files.delete(workspaces);
        Files.writeString(workspaces, "not a directory", StandardCharsets.UTF_8);

        MinosApiException failure = assertThrows(MinosApiException.class, api::listWorkspaceInventory);

        assertEquals(ErrorCode.IO_FAILURE, failure.code());
        assertFalse(failure.getMessage().contains("unreadable"), "an outage is not described as unreadable entries: " + failure.getMessage());
        assertNoPath(failure.getMessage());
    }

    // ---- la résolution : absent n'est pas indéterminable -----------------------------------------------------------

    @Test
    void aWorkspaceFoundBesideADamagedProjectIsAnsweredWithTheCount() throws Exception {
        registry();
        damage(projectEntry(betaId));

        for (String reference : List.of("platform", platform.id())) {
            WorkspaceLookupDto lookup = api.lookupWorkspace(reference);
            assertEquals(platform.id(), lookup.workspace().id(), reference);
            assertEquals(List.of(alphaId), lookup.workspace().projectIds(), "the membership that could be read");
            assertEquals(1, lookup.unreadableProjectEntries(), reference);
            assertEquals(0, lookup.unreadableWorkspaceEntries(), reference);
        }
    }

    @Test
    void aWorkspaceAbsentBesideADamagedProjectIsAbsentBecauseAProjectCannotHoldAWorkspace() throws Exception {
        registry();
        damage(projectEntry(betaId));

        MinosApiException failure = assertThrows(MinosApiException.class, () -> api.lookupWorkspace("ghost"));

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(failure.getMessage().contains("Unknown workspace: ghost"), failure.getMessage());
    }

    @Test
    void aWorkspaceNotFoundByNameBesideADamagedWorkspaceEntryIsNotReportedAbsent() throws Exception {
        registry();
        damage(workspaceEntry(tooling.id()));

        MinosApiException failure = assertThrows(MinosApiException.class, () -> api.lookupWorkspace("ghost"));

        assertEquals(ErrorCode.IO_FAILURE, failure.code());
        assertTrue(failure.getMessage().contains("1 registry entry is unreadable, so it cannot be told whether this workspace exists"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("Unknown workspace"), "unreadable is not absent: " + failure.getMessage());
        assertNoPath(failure.getMessage());
        assertFalse(failure.getMessage().contains("not-a-date"), "text of the damaged file leaked: " + failure.getMessage());
    }

    @Test
    void aNameFoundBesideADamagedWorkspaceEntryIsAnsweredButCannotBeProvenUnique() throws Exception {
        registry();
        damage(workspaceEntry(tooling.id()));

        WorkspaceLookupDto lookup = api.lookupWorkspace("platform");

        assertEquals(platform.id(), lookup.workspace().id());
        assertEquals(1, lookup.unreadableWorkspaceEntries());
    }

    @Test
    void byIdentifierOnlyThatEntryMatters() throws Exception {
        registry();
        damage(workspaceEntry(tooling.id()));

        // A damaged neighbour does not concern a lookup by identifier ...
        WorkspaceLookupDto found = api.lookupWorkspace(platform.id());
        assertEquals(0, found.unreadableWorkspaceEntries());
        // ... an unknown identifier is absent ...
        MinosApiException absent = assertThrows(MinosApiException.class,
                () -> api.lookupWorkspace("00000000-0000-0000-0000-000000000000"));
        assertEquals(ErrorCode.INVALID_REQUEST, absent.code());
        // ... and the identifier of the damaged entry itself is unreadable, not absent.
        MinosApiException unreadable = assertThrows(MinosApiException.class, () -> api.lookupWorkspace(tooling.id()));
        assertEquals(ErrorCode.IO_FAILURE, unreadable.code());
        assertTrue(unreadable.getMessage().contains("unreadable"), unreadable.getMessage());
        assertFalse(unreadable.getMessage().contains("Unknown workspace"), unreadable.getMessage());
    }

    @Test
    void anAmbiguousNameAmongTheReadableEntriesStaysAmbiguous() throws Exception {
        registry();
        // Two workspaces cannot share a name through the API, so duplicate one by hand.
        String copyId = "11111111-1111-1111-1111-111111111111";
        String content = Files.readString(workspaceEntry(platform.id()), StandardCharsets.UTF_8)
                .replace(platform.id(), copyId);
        Files.writeString(workspaceEntry(copyId), content, StandardCharsets.UTF_8);

        MinosApiException failure = assertThrows(MinosApiException.class, () -> api.lookupWorkspace("platform"));

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(failure.getMessage().contains("Ambiguous workspace name"), failure.getMessage());
    }

    @Test
    void aNullIdentifierIsAnInvalidRequestLikeEveryOtherOperation() throws Exception {
        registry();

        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(MinosApiException.class, () -> api.lookupWorkspace(null)).code());
    }

    private static WorkspaceDto byName(WorkspaceInventoryDto inventory, String name) {
        return inventory.workspaces().stream().filter(workspace -> workspace.name().equals(name)).findFirst().orElseThrow();
    }

    private void assertNoPath(String message) {
        assertFalse(message.contains(temp.toString()), "absolute path in: " + message);
    }
}
