package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.io.Sha256;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.minos.domain.Preconditions.requireText;

/**
 * Règle pure et déterministe qui rattache un chemin de fichier du projet au module découvert qui le contient :
 * la racine de source la plus spécifique l'emporte, à défaut le module le plus profond (MINOS-AUD-F04).
 *
 * <p>Cette règle est la seule : la vue d'architecture ({@code ArchitectureModuleResolver}) la délègue, et
 * l'indexation autonome l'applique aux symboles locaux avant publication, pour que l'identifiant saisi dans un
 * filtre par module soit celui que l'architecture affiche. Elle ne lit aucun fichier et ne dépend d'aucun
 * séparateur de chemin : {@code \} et {@code /} désignent le même fichier.</p>
 */
public final class ModuleAssignmentRule {

    private static final ModuleAssignmentRule NONE = new ModuleAssignmentRule("none", List.of());

    private final String projectId;
    private final List<DiscoveredModule> modules;

    private ModuleAssignmentRule(String projectId, List<DiscoveredModule> modules) {
        this.projectId = projectId;
        this.modules = modules;
    }

    /** Règle vide : aucun fichier n'est rattaché (indexation sans découverte). */
    public static ModuleAssignmentRule none() {
        return NONE;
    }

    public static ModuleAssignmentRule of(String projectId, ProjectDiscovery discovery) {
        requireText(projectId, "projectId");
        Objects.requireNonNull(discovery, "discovery");
        List<DiscoveredModule> ordered = discovery.modules().stream()
                .sorted(Comparator.comparing(module -> portable(module.relativePath())))
                .toList();
        return new ModuleAssignmentRule(projectId, ordered);
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /** Identifiant de module d'un fichier désigné par son identifiant de symbole ; vide si hors projet ou hors module. */
    public Optional<String> moduleIdOf(String fileId) {
        Path filePath = safeRelativePath(fileId);
        if (filePath == null) {
            return Optional.empty();
        }
        return resolve(filePath).map(Assignment::moduleId);
    }

    public Optional<Assignment> resolve(Path filePath) {
        Objects.requireNonNull(filePath, "filePath");

        DiscoveredModule selected = null;
        SourceRoot selectedRoot = null;
        int bestRootScore = -1;
        for (DiscoveredModule module : modules) {
            SourceRoot candidateRoot = module.sourceRoots().stream()
                    .filter(root -> startsWith(filePath, root.relativePath()))
                    .max(Comparator.comparingInt(root -> portable(root.relativePath()).length()))
                    .orElse(null);
            if (candidateRoot == null) {
                continue;
            }
            int score = portable(candidateRoot.relativePath()).length();
            if (score > bestRootScore) {
                selected = module;
                selectedRoot = candidateRoot;
                bestRootScore = score;
            }
        }

        if (selected == null) {
            int bestModuleScore = -1;
            for (DiscoveredModule module : modules) {
                if (!startsWith(filePath, module.relativePath())) {
                    continue;
                }
                int score = portable(module.relativePath()).length();
                if (score > bestModuleScore) {
                    selected = module;
                    selectedRoot = null;
                    bestModuleScore = score;
                }
            }
        }

        if (selected == null) {
            return Optional.empty();
        }
        return Optional.of(new Assignment(
                selected,
                filePath,
                selectedRoot,
                moduleId(projectId, selected.relativePath())
        ));
    }

    public static String moduleId(String projectId, Path modulePath) {
        return "module:" + Sha256.hex(requireText(projectId, "projectId")
                + "\u001F" + portable(Objects.requireNonNull(modulePath, "modulePath")));
    }

    public static Path safeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String portable = value.replace('\\', '/');
        if (portable.startsWith("/")
                || portable.startsWith("file:")
                || portable.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        try {
            Path path = Path.of(portable).normalize();
            if (path.isAbsolute() || path.startsWith("..")) {
                return null;
            }
            return path;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    public static boolean startsWith(Path path, Path prefix) {
        return portable(prefix).isEmpty() || path.startsWith(prefix);
    }

    public static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    public record Assignment(
            DiscoveredModule module,
            Path filePath,
            SourceRoot sourceRoot,
            String moduleId
    ) {
        public Assignment {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(filePath, "filePath");
            if (moduleId == null || moduleId.isBlank()) {
                throw new IllegalArgumentException("moduleId must not be blank");
            }
        }
    }
}
