package com.minos.architecture;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureTechnologyServiceTest {

    private final ArchitectureTechnologyService service = new ArchitectureTechnologyService();

    @Test
    void detectsOnlyFactualLanguagesAndBuildSystemsWithModuleAssignments() {
        ProjectDiscovery discovery = new ProjectDiscovery(
                Path.of("fixture"),
                "fixture",
                Set.of(Language.JAVA, Language.TYPESCRIPT),
                Set.of(BuildSystem.MAVEN, BuildSystem.NPM),
                List.of(
                        new DiscoveredModule(
                                Path.of(""),
                                "fixture",
                                Set.of(BuildSystem.MAVEN),
                                List.of(new SourceRoot(
                                        Path.of("src/main/java"),
                                        SourceRootKind.SOURCE,
                                        Language.JAVA
                                ))
                        ),
                        new DiscoveredModule(
                                Path.of("web"),
                                "web",
                                Set.of(BuildSystem.NPM),
                                List.of(
                                        new SourceRoot(Path.of("web/src"), SourceRootKind.SOURCE, Language.TYPESCRIPT),
                                        new SourceRoot(Path.of("web/test"), SourceRootKind.TEST, Language.TYPESCRIPT)
                                )
                        )
                )
        );
        ArchitectureOverview overview = overview(
                module("module:root", "fixture", ""),
                module("module:web", "web", "web")
        );

        ArchitectureTechnologyReport report = service.detect(discovery, overview);

        assertEquals(List.of("JAVA", "TYPESCRIPT", "MAVEN", "NPM"), report.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());
        assertEquals(List.of("module:root"), technology(report, "JAVA").moduleIds());
        assertEquals(List.of("module:web"), technology(report, "TYPESCRIPT").moduleIds());
        assertEquals(List.of("module:root"), technology(report, "MAVEN").moduleIds());
        assertEquals(List.of("module:web"), technology(report, "NPM").moduleIds());
        report.technologies().forEach(technology -> assertEquals(InformationNature.FACTUAL, technology.nature()));
        assertEquals(InformationNature.DERIVED, report.nature());
    }

    @Test
    void deduplicatesRepeatedObservationsWithoutInventingNewTechnology() {
        ProjectDiscovery discovery = new ProjectDiscovery(
                Path.of("fixture"),
                "fixture",
                Set.of(Language.JAVA),
                Set.of(),
                List.of(new DiscoveredModule(
                        Path.of(""),
                        "fixture",
                        Set.of(),
                        List.of(
                                new SourceRoot(Path.of("src/main/java"), SourceRootKind.SOURCE, Language.JAVA),
                                new SourceRoot(Path.of("src/test/java"), SourceRootKind.TEST, Language.JAVA)
                        )
                ))
        );

        ArchitectureTechnologyReport report = service.detect(
                discovery,
                overview(module("module:root", "fixture", ""))
        );

        assertEquals(1, report.technologyCount());
        assertEquals("technology:language:java", report.technologies().getFirst().id());
        assertEquals(List.of("module:root"), report.technologies().getFirst().moduleIds());
        assertEquals(2, report.technologies().getFirst().evidence().size());
    }

    @Test
    void rejectsDiscoveryModuleMissingFromArchitectureOverview() {
        ProjectDiscovery discovery = new ProjectDiscovery(
                Path.of("fixture"),
                "fixture",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                List.of(new DiscoveredModule(
                        Path.of("api"),
                        "api",
                        Set.of(BuildSystem.MAVEN),
                        List.of(new SourceRoot(
                                Path.of("api/src/main/java"),
                                SourceRootKind.SOURCE,
                                Language.JAVA
                        ))
                ))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.detect(discovery, overview(module("module:root", "fixture", "")))
        );
    }

    private static ArchitectureTechnology technology(ArchitectureTechnologyReport report, String name) {
        return report.technologies().stream()
                .filter(technology -> name.equals(technology.name()))
                .findFirst()
                .orElseThrow();
    }

    private static ArchitectureOverview overview(ArchitectureModule... modules) {
        return new ArchitectureOverview(
                "project-technology",
                "fixture",
                "snapshot-technology",
                List.of("JAVA", "TYPESCRIPT"),
                List.of("MAVEN", "NPM"),
                0,
                0,
                0,
                0,
                List.of(modules),
                InformationNature.DERIVED,
                List.of(evidence("technology overview"))
        );
    }

    private static ArchitectureModule module(String id, String name, String relativePath) {
        return new ArchitectureModule(
                id,
                name,
                relativePath,
                List.of(),
                List.of(),
                0,
                0,
                List.of(),
                InformationNature.FACTUAL,
                InformationNature.DERIVED,
                List.of(evidence("module " + name))
        );
    }

    private static Evidence evidence(String description) {
        return new Evidence(EvidenceType.DERIVATION_PATH, description, null, null, null, 1.0);
    }
}
