package com.minos.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Class-level architecture rules over the compiled production classes of every module of the reactor.
 *
 * <p>The module policy itself is decided by ADR 0022, ADR 0042 (A2 roles) and ADR 0044 (one package, one
 * module), and enforced at POM and source level by {@code scripts/architecture/check-module-boundaries.py}.
 * Maven only checks declared edges; it does not see a class that uses a module it reaches transitively, and the
 * source check does not see compiled classes. These tests check the same decisions on the bytecode. ADR 0057
 * (point 7) names ArchUnit as an accepted way to complete the existing guards.
 *
 * <p>Every rule runs on an import that is first proven complete: each module's output directory must exist and
 * every {@code .class} file in it must be imported, so a stale or empty build cannot make a rule pass vacuously.
 * The working directory is the reactor root (Surefire {@code workingDirectory} of minos-app).
 */
class ModuleArchitectureTest {

    /** Output directory of each module's production classes; minos-app writes to the reactor root target/. */
    private static final Map<String, Path> OUTPUTS = new LinkedHashMap<>();

    static {
        for (String module : List.of(
                "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage",
                "minos-provider-scip", "minos-integration-git", "minos-application",
                "minos-bootstrap", "minos-nexus", "minos-cli", "minos-api", "minos-mcp")) {
            OUTPUTS.put(module, Path.of(module, "target", "classes"));
        }
        OUTPUTS.put("minos-app", Path.of("target", "classes"));
    }

    /** Same table as ALLOWED_DEPENDENCIES of check-module-boundaries.py (ADR 0022 / ADR 0042, A2). */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("minos-domain", Set.of()),
            Map.entry("minos-engine", Set.of("minos-domain")),
            Map.entry("minos-runtime-local", Set.of("minos-domain", "minos-engine")),
            Map.entry("minos-storage", Set.of("minos-domain", "minos-engine")),
            Map.entry("minos-provider-scip",
                    Set.of("minos-domain", "minos-engine", "minos-runtime-local", "minos-storage")),
            Map.entry("minos-integration-git", Set.of("minos-domain", "minos-engine")),
            Map.entry("minos-application", Set.of("minos-domain", "minos-engine")),
            Map.entry("minos-bootstrap", Set.of("minos-domain", "minos-engine", "minos-application",
                    "minos-runtime-local", "minos-storage", "minos-provider-scip", "minos-integration-git")),
            Map.entry("minos-nexus", Set.of("minos-domain", "minos-application", "minos-bootstrap")),
            Map.entry("minos-cli",
                    Set.of("minos-domain", "minos-engine", "minos-application", "minos-nexus", "minos-bootstrap")),
            Map.entry("minos-api", Set.of("minos-domain", "minos-engine", "minos-application", "minos-bootstrap")),
            Map.entry("minos-mcp", Set.of("minos-domain", "minos-engine", "minos-application", "minos-bootstrap")),
            Map.entry("minos-app", Set.of("minos-domain", "minos-engine", "minos-runtime-local",
                    "minos-storage", "minos-provider-scip",
                    "minos-integration-git", "minos-application", "minos-bootstrap", "minos-nexus", "minos-cli",
                    "minos-api", "minos-mcp")));

    private static final Set<String> ADAPTERS = Set.of("minos-runtime-local", "minos-storage",
            "minos-provider-scip", "minos-integration-git");
    private static final Set<String> SURFACES = Set.of("minos-cli", "minos-api", "minos-mcp", "minos-nexus");

    /**
     * A11 on the bytecode (AUD-ARC-01, ADR 0058 § 3): per surface, the top-level minos-engine types its compiled classes
     * depend on, with the number of top-level surface classes that depend on each. The source view of the same debt is
     * ENGINE_TYPES_NAMED_BY_SURFACE in scripts/architecture/check-module-boundaries.py; the bytecode also sees the calls
     * chained on an engine result, which name no type, and misses a type used only as a type argument. A change that
     * removes a use updates both tables; the tables only shrink.
     */
    private static final Map<String, Map<String, Integer>> ENGINE_TYPES_USED_BY_SURFACE_BYTECODE = Map.of(
            "minos-api", Map.ofEntries(
                    // Kept, as in ENGINE_TYPES_NAMED_BY_SURFACE of check-module-boundaries.py: published read models
                    // returned by ProjectSymbolQuery (ADR 0057, point 6).
                    Map.entry("query.RelationshipResult", 1),
                    Map.entry("query.SymbolResult", 1),
                    Map.entry("query.UsageResult", 1)),
            "minos-cli", Map.ofEntries(
                    // Kept, as in the source table: a persisted I/O port, and MinosVersion placed by ADR 0068 section 3.
                    Map.entry("registry.ProjectPathMappings", 1),
                    Map.entry("runtime.MinosVersion", 1)),
            "minos-mcp", Map.ofEntries(
                    Map.entry("runtime.MinosVersion", 1)),
            // The NEXUS export goes through KnowledgeExportUseCase; minos-nexus does not depend on the engine.
            "minos-nexus", Map.of());

    private static final String GROUP_PREFIX = "com.minos.";

    private static final Map<String, Set<String>> CLASSES_BY_MODULE = new LinkedHashMap<>();
    private static final Map<String, String> MODULE_BY_CLASS = new HashMap<>();
    private static JavaClasses all;

    @BeforeAll
    static void importEveryModule() {
        ClassFileImporter importer = new ClassFileImporter();
        for (Map.Entry<String, Path> output : OUTPUTS.entrySet()) {
            Path directory = output.getValue();
            assertTrue(Files.isDirectory(directory),
                    output.getKey() + ": no compiled classes at " + directory.toAbsolutePath()
                            + " (build the reactor before the architecture tests)");
            Set<String> names = importer.importPath(directory).stream()
                    .map(JavaClass::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            CLASSES_BY_MODULE.put(output.getKey(), names);
            for (String name : names) {
                String previous = MODULE_BY_CLASS.put(name, output.getKey());
                if (previous != null) {
                    throw new AssertionError(name + " is compiled by both " + previous + " and " + output.getKey());
                }
            }
        }
        all = importer.importPaths(OUTPUTS.values().toArray(Path[]::new));
    }

    @Test
    void everyModuleIsImportedCompletely() {
        for (Map.Entry<String, Path> output : OUTPUTS.entrySet()) {
            long classFiles = countClassFiles(output.getValue());
            int imported = CLASSES_BY_MODULE.get(output.getKey()).size();
            assertTrue(classFiles > 0, output.getKey() + ": empty output directory " + output.getValue());
            assertEquals(classFiles, imported,
                    output.getKey() + ": " + classFiles + " class files on disk, " + imported + " imported");
        }
        assertEquals(MODULE_BY_CLASS.size(), all.size(), "combined import differs from the per-module imports");
    }

    /** ADR 0044 §1 on the bytecode: a package is compiled by exactly one module (no split package). */
    @Test
    void everyPackageBelongsToOneModule() {
        Map<String, Set<String>> modulesByPackage = new TreeMap<>();
        for (JavaClass javaClass : all) {
            modulesByPackage.computeIfAbsent(javaClass.getPackageName(), ignored -> new TreeSet<>())
                    .add(MODULE_BY_CLASS.get(javaClass.getName()));
        }
        Map<String, Set<String>> split = modulesByPackage.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
        assertTrue(split.isEmpty(), "split packages: " + split);
    }

    /** ADR 0042 (A2): no adapter reaches the application layer, the composition root, a surface or the assembly. */
    @Test
    void adaptersDoNotReachBackIntoUpperLayers() {
        Set<String> upper = union(Set.of("minos-application", "minos-bootstrap", "minos-app"), SURFACES);
        for (String adapter : ADAPTERS) {
            noClasses().that(inModules(Set.of(adapter)))
                    .should().dependOnClassesThat(inModules(upper))
                    .because("ADR 0042 (A2): an adapter implements engine/domain ports and never reaches back")
                    .check(all);
        }
    }

    /** ADR 0042 (A2): the application layer only knows ports, never an adapter, the root or a surface. */
    @Test
    void applicationOnlyKnowsPorts() {
        noClasses().that(inModules(Set.of("minos-application")))
                .should().dependOnClassesThat(inModules(union(union(ADAPTERS, SURFACES),
                        Set.of("minos-bootstrap", "minos-app"))))
                .because("ADR 0042 (A2): minos-application only knows ports")
                .check(all);
    }

    /**
     * ADR 0042 (A2): surfaces reach the composition root at runtime only (Maven scope runtime/test), never at
     * compile time; on the bytecode, no surface class references a bootstrap class or an adapter class.
     */
    @Test
    void surfacesDoNotCompileAgainstTheCompositionRootOrAdapters() {
        noClasses().that(inModules(SURFACES))
                .should().dependOnClassesThat(inModules(union(ADAPTERS, Set.of("minos-bootstrap", "minos-app"))))
                .because("ADR 0042 (A2): surfaces open the application through MinosApplication.open")
                .check(all);
    }

    /** ADR 0022: the domain depends on no other module, and the engine only on the domain. */
    @Test
    void coreModulesStayAtTheBottom() {
        Set<String> modules = OUTPUTS.keySet();
        noClasses().that(inModules(Set.of("minos-domain")))
                .should().dependOnClassesThat(inModules(minus(modules, Set.of("minos-domain"))))
                .check(all);
        noClasses().that(inModules(Set.of("minos-engine")))
                .should().dependOnClassesThat(inModules(minus(modules, Set.of("minos-engine", "minos-domain"))))
                .check(all);
    }

    /**
     * ADR 0058: a module's classes only use classes of the modules it declares. The table bounds the edges declared
     * in the POMs; without this rule Maven would also let a class use a module reached transitively (for example an
     * adapter calling {@code com.minos.domain.Preconditions} through minos-engine). Every module is evaluated before
     * the test fails, so the report lists all undeclared module uses at once.
     */
    @Test
    void everyModuleOnlyUsesItsAllowedModules() {
        Map<String, Map<String, Integer>> undeclared = new TreeMap<>();
        List<String> details = new ArrayList<>();
        for (String module : OUTPUTS.keySet()) {
            Set<String> forbidden = minus(OUTPUTS.keySet(), union(ALLOWED.get(module), Set.of(module)));
            for (JavaClass javaClass : all) {
                if (!module.equals(MODULE_BY_CLASS.get(javaClass.getName()))) {
                    continue;
                }
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    String target = MODULE_BY_CLASS.get(dependency.getTargetClass().getName());
                    if (target != null && forbidden.contains(target)) {
                        undeclared.computeIfAbsent(module, ignored -> new TreeMap<>()).merge(target, 1, Integer::sum);
                        details.add(dependency.getDescription());
                    }
                }
            }
        }
        System.out.println("undeclared module uses (module -> used module = dependencies): " + undeclared);
        assertTrue(undeclared.isEmpty(), "undeclared module uses " + undeclared + "\n" + String.join("\n", details));
    }

    /**
     * A11 on the bytecode: each surface depends only on the minos-engine types of its
     * ENGINE_TYPES_USED_BY_SURFACE_BYTECODE list, each from the listed number of top-level classes.
     */
    @Test
    void surfacesUseOnlyListedEngineTypes() {
        Map<String, Map<String, Set<String>>> observed = new TreeMap<>();
        for (JavaClass javaClass : all) {
            String surface = MODULE_BY_CLASS.get(javaClass.getName());
            if (!SURFACES.contains(surface)) {
                continue;
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                if ("minos-engine".equals(MODULE_BY_CLASS.get(target))) {
                    observed.computeIfAbsent(surface, ignored -> new TreeMap<>())
                            .computeIfAbsent(topLevel(target).substring(GROUP_PREFIX.length()), ignored -> new TreeSet<>())
                            .add(topLevel(javaClass.getName()));
                }
            }
        }
        List<String> problems = ratchetProblems(ENGINE_TYPES_USED_BY_SURFACE_BYTECODE, observed);
        assertTrue(problems.isEmpty(), "A11 bytecode ratchet (ADR 0058 section 3: a surface reaches the engine through "
                + "application ports; ENGINE_TYPES_USED_BY_SURFACE_BYTECODE only shrinks): " + String.join("; ", problems));
    }

    /** The comparison of the A11 bytecode ratchet refuses a new type, a changed count and a dead entry. */
    @Test
    void theBytecodeRatchetRefusesANewTypeAChangedCountAndADeadEntry() {
        Map<String, Map<String, Integer>> listed = Map.of("minos-cli", Map.of("hosted.HostedRole", 1, "io.Gone", 1));
        Map<String, Map<String, Set<String>>> observed = Map.of("minos-cli", Map.of(
                "hosted.HostedRole", Set.of("com.minos.cli.A", "com.minos.cli.B"),
                "hosted.HostedTenantState", Set.of("com.minos.cli.A")));

        List<String> problems = ratchetProblems(listed, observed);

        assertEquals(3, problems.size(), problems::toString);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("hosted.HostedTenantState")
                && problem.contains("absent from")), problems::toString);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("hosted.HostedRole")
                && problem.contains("listed for 1") && problem.contains("used by 2")), problems::toString);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("io.Gone")
                && problem.contains("no longer used")), problems::toString);
        assertTrue(ratchetProblems(Map.of("minos-cli", Map.of("hosted.HostedRole", 2)),
                Map.of("minos-cli", Map.of("hosted.HostedRole", Set.of("com.minos.cli.A", "com.minos.cli.B"))))
                .isEmpty());
    }

    private static List<String> ratchetProblems(Map<String, Map<String, Integer>> listed,
                                                Map<String, Map<String, Set<String>>> observed) {
        List<String> problems = new ArrayList<>();
        for (String surface : new TreeSet<>(SURFACES)) {
            Map<String, Integer> expected = listed.getOrDefault(surface, Map.of());
            Map<String, Set<String>> used = observed.getOrDefault(surface, Map.of());
            for (Map.Entry<String, Set<String>> type : new TreeMap<>(used).entrySet()) {
                Integer count = expected.get(type.getKey());
                Set<String> classes = new TreeSet<>(type.getValue());
                if (count == null) {
                    problems.add(surface + ": " + classes + " use " + type.getKey()
                            + ", absent from ENGINE_TYPES_USED_BY_SURFACE_BYTECODE");
                } else if (count != classes.size()) {
                    problems.add(surface + ": entry " + type.getKey() + " is listed for " + count
                            + " class(es) and used by " + classes.size() + " " + classes
                            + (classes.size() > count ? "; a new use goes through an application port"
                                    : "; write " + classes.size() + " (the ratchet only shrinks)"));
                }
            }
            for (String type : new TreeSet<>(expected.keySet())) {
                if (!used.containsKey(type)) {
                    problems.add(surface + ": entry " + type + " is no longer used; remove it (the ratchet only shrinks)");
                }
            }
        }
        return problems;
    }

    private static String topLevel(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static DescribedPredicate<JavaClass> inModules(Set<String> modules) {
        // JDK and third-party classes belong to no module of the reactor (immutable sets reject contains(null)).
        return DescribedPredicate.describe("belong to " + new TreeSet<>(modules), javaClass -> {
            String module = MODULE_BY_CLASS.get(javaClass.getName());
            return module != null && modules.contains(module);
        });
    }

    private static long countClassFiles(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".class")).count();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.addAll(right);
        return result;
    }

    private static Set<String> minus(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }
}
