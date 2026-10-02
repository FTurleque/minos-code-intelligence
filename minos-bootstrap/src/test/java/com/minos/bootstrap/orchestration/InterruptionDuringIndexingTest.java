package com.minos.bootstrap.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.ProjectIndexState;
import com.minos.orchestration.ResumableArtifactPolicy;
import com.minos.runtime.local.FileResumableRunMarkers;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q5 et R6 de bout en bout, sur le VRAI stockage (fichiers sous un {@code MINOS_HOME} de test) : une
 * interruption du thread d'indexation, où qu'elle tombe, termine le run en {@code INTERRUPTED}, le
 * drapeau d'interruption est rétabli pour l'appelant, et <b>les points de contrôle déjà acquis sont
 * conservés sur disque</b> : c'est leur comptage, relu depuis le stockage, qui fait la preuve, puis la
 * reprise qui les réutilise sans relancer leurs providers.
 *
 * <p>Aucune synchronisation par durée : le fournisseur de la troisième cible signale sa sortie par un
 * verrou, le thread d'interruption attend ce signal puis l'entrée du thread d'indexation dans
 * l'attente de lisibilité de l'artefact (état {@code TIMED_WAITING}, le seul endroit où ce thread
 * attend avec une durée), et seulement alors l'interrompt.</p>
 */
class InterruptionDuringIndexingTest {

    private static final long SAFETY_BOUND_SECONDS = 60L;
    private static final List<String> THREE_SCOPES = List.of("ui/app", "ui/lib", "ui/web");

    /** Comportement du fournisseur de la troisième cible ; les deux premières s'exécutent normalement. */
    @FunctionalInterface
    private interface ThirdTarget {
        IndexingArtifact execute(IndexingExecutionRequest request) throws Exception;
    }

    /** Issue observée sur le thread d'indexation : le run rendu et l'état du drapeau en fin de thread. */
    private record Observed(IndexingRun run, boolean interruptFlagRestored) { }

    @Test
    void interruptionWhileWaitingForTheArtifactKeepsTheRunResumableWithItsCheckpoints(@TempDir Path temp)
            throws Exception {
        Environment environment = new Environment(temp);
        CountDownLatch providerReturned = new CountDownLatch(1);
        Path neverWritten = environment.home.resolve("never-written.scip");
        ThirdTarget returnsAnUnreadableArtifact = request -> {
            providerReturned.countDown();
            return new IndexingArtifact(Language.TYPESCRIPT, ResumeCrashFixtureMain.PROVIDER, neverWritten,
                    request.projectRelativeRoot());
        };

        Observed observed = environment.indexInterruptedOnceTheProviderHasReturned(
                returnsAnUnreadableArtifact, providerReturned);

        environment.assertInterruptedAndResumable(observed);
        environment.assertResumeReusesTheTwoCheckpoints(observed.run().id());
    }

    @Test
    void interruptionRaisedByTheProviderKeepsTheRunResumableWithItsCheckpoints(@TempDir Path temp)
            throws Exception {
        Environment environment = new Environment(temp);
        ThirdTarget interrupted = request -> {
            throw new InterruptedException("service is stopping");
        };

        Observed observed = environment.indexOnOwnThread(interrupted);

        environment.assertInterruptedAndResumable(observed);
        environment.assertResumeReusesTheTwoCheckpoints(observed.run().id());
    }

    /** Un {@code MINOS_HOME} de test, le projet à trois cibles, et le cycle de vie câblé sur les vrais stockages. */
    private static final class Environment {
        private final Path home;
        private final Path project;
        private final UUID projectId = UUID.fromString(ResumeCrashFixtureMain.PROJECT_ID);
        private final FileIndexStateStore store;

        private Environment(Path temp) throws IOException {
            this.home = Files.createDirectories(temp.resolve("home"));
            this.project = Files.createDirectories(temp.resolve("project"));
            for (Path scope : ResumeCrashFixtureMain.SCOPES) {
                Files.createDirectories(project.resolve(scope).resolve("src"));
                Files.writeString(project.resolve(scope).resolve("src/index.ts"),
                        "export const v = '" + scope.toString().replace('\\', '/') + "';");
            }
            Files.writeString(project.resolve("package.json"), "{}");
            this.store = new FileIndexStateStore(home.resolve("index-state"));
        }

        private IndexingLifecycleService lifecycle(ThirdTarget thirdTarget) {
            ResumeCrashFixtureMain.CountingExecutor counting =
                    new ResumeCrashFixtureMain.CountingExecutor(home, Integer.MAX_VALUE);
            IndexerExecutor scripted = new IndexerExecutor() {
                @Override public String indexerId() { return ResumeCrashFixtureMain.PROVIDER; }

                @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                    boolean third = "ui/web".equals(request.projectRelativeRoot().toString().replace('\\', '/'));
                    return third && thirdTarget != null ? thirdTarget.execute(request) : counting.execute(request);
                }
            };
            return new IndexingLifecycleService(List.of(scripted), new ResumeCrashFixtureMain.FileStager(home),
                    new ResumeCrashFixtureMain.FilePromoter(home), store, ResumeCrashFixtureMain.markers(home),
                    ResumableArtifactPolicy.DEFAULT);
        }

        private IndexingRun index(ThirdTarget thirdTarget) {
            return lifecycle(thirdTarget).execute(projectId, project, ResumeCrashFixtureMain.discovery(project),
                    ResumeCrashFixtureMain.negotiation(), IndexingResumePolicy.RESUME);
        }

        /** Indexe sur un thread dédié (le drapeau d'interruption du thread de test n'est jamais touché). */
        private Observed indexOnOwnThread(ThirdTarget thirdTarget) throws InterruptedException {
            AtomicReference<Observed> result = new AtomicReference<>();
            Thread indexing = new Thread(() -> {
                IndexingRun run = index(thirdTarget);
                result.set(new Observed(run, Thread.currentThread().isInterrupted()));
            }, "indexing-under-test");
            indexing.start();
            join(indexing);
            return result.get();
        }

        /**
         * Interrompt le thread d'indexation pendant l'attente de lisibilité de l'artefact : le thread
         * d'interruption attend la sortie du provider (verrou), puis que le thread d'indexation soit
         * en attente chronométrée, sans jamais dormir lui-même.
         */
        private Observed indexInterruptedOnceTheProviderHasReturned(
                ThirdTarget thirdTarget, CountDownLatch providerReturned) throws InterruptedException {
            AtomicReference<Observed> result = new AtomicReference<>();
            Thread indexing = new Thread(() -> {
                IndexingRun run = index(thirdTarget);
                result.set(new Observed(run, Thread.currentThread().isInterrupted()));
            }, "indexing-under-test");
            Thread interrupter = new Thread(() -> {
                if (!awaitSignal(providerReturned)) return;
                while (indexing.getState() != Thread.State.TIMED_WAITING
                        && indexing.getState() != Thread.State.TERMINATED) {
                    Thread.onSpinWait();
                }
                indexing.interrupt();
            }, "interrupter-under-test");
            indexing.start();
            interrupter.start();
            join(indexing);
            join(interrupter);
            return result.get();
        }

        /** Attend le signal du provider ; la borne n'est qu'un garde-fou, jamais ce qui ordonne les événements. */
        private static boolean awaitSignal(CountDownLatch signal) {
            try {
                return signal.await(SAFETY_BOUND_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException unexpected) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private static void join(Thread thread) throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(SAFETY_BOUND_SECONDS));
            assertFalse(thread.isAlive(), "thread " + thread.getName() + " must end");
        }

        /** Le run rendu ET le run relu sur disque sont interrompus, offerts à la reprise, avec 2 points de contrôle. */
        private void assertInterruptedAndResumable(Observed observed) {
            IndexingRun returned = observed.run();
            assertEquals(IndexingRun.Status.INTERRUPTED, returned.status(), String.valueOf(returned.message()));
            assertTrue(observed.interruptFlagRestored(), "the interrupt flag is replayed to the caller");

            IndexingRun onDisk = store.findRun(returned.id()).orElseThrow();
            assertEquals(IndexingRun.Status.INTERRUPTED, onDisk.status(), String.valueOf(onDisk.message()));
            long survivingCheckpoints = onDisk.executions().stream()
                    .map(IndexerExecution::checkpoint).filter(Optional::isPresent).count();
            assertEquals(2L, survivingCheckpoints, "both completed targets keep their checkpoint on disk");

            ProjectIndexState state = store.findProjectState(projectId).orElseThrow();
            assertEquals(Optional.of(returned.id()), state.resumableRunId(), "the project offers the run for resume");
            assertTrue(Files.isRegularFile(home.resolve("runs").resolve(returned.id().toString())
                            .resolve(FileResumableRunMarkers.MARKER_FILE_NAME)),
                    "the run directory stays held against retention");
        }

        /** La reprise réutilise les deux points de contrôle : seul le dernier provider tourne, sous le même runId. */
        private void assertResumeReusesTheTwoCheckpoints(UUID interruptedRunId) throws IOException {
            IndexingRun resumed = index(null);

            assertEquals(IndexingRun.Status.SUCCEEDED, resumed.status(), String.valueOf(resumed.message()));
            assertEquals(interruptedRunId, resumed.id(), "the same run is reopened");
            assertEquals(2, resumed.resume().orElseThrow().reusedTargets());
            assertEquals(THREE_SCOPES, Files.readAllLines(home.resolve("executions.log")),
                    "each provider ran once in total: the two checkpointed targets were not re-executed");
        }
    }
}
