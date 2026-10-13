# Design

## Context

Sprint 9 de l'audit du 10 octobre 2026 : **AUD-PERF-04**. HEAD analysé : `949e1569` (`develop`). `ENG` = `minos-engine/src/main/java/com/minos` ; `CLI` = `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java`. Ce changement se fait **après** `unifier-l-orchestration-d-indexation` : l'empreinte « avant » et l'exécuteur de run seront alors reliés par un seul cas d'usage.

## État vérifié au HEAD

### AUD-PERF-04 — confirmé à la lecture, durée **non mesurée**

- Avant : `CLI:316` `fingerprintService.capture(project.rootPath())`. Après : `CLI:200`.
- Scope : `ENG/orchestration/IndexingRunExecutor.java:320-321` `context.scopeFingerprints.computeIfAbsent(...)` (une capture par scope et par run), vers `ENG/orchestration/ExecutionCheckpoints.java:39` `FINGERPRINTS.captureScope(projectRoot, projectRelativeRoot)`.
- `ENG/incremental/ProjectFingerprintService.java:59-60` : `capture(root)` rend `captureScope(root, Path.of(""))` ; `:324` `hashFile` rouvre et hache chaque fichier.
- Soit 3 hachages complets pour une cible racine. **Manques du constat** : en reprise, `ENG/orchestration/IndexingResumePlanner.java:268` recalcule l'empreinte de scope de chaque cible réutilisée ; chaque scope non racine ajoute son propre hachage.
- Valeur persistée : `minos-storage-local/src/main/java/com/minos/storage/local/orchestration/FileIndexStateStore.java:343` écrit `scopeFingerprint` ; `IndexingResumePlanner.java:272` la compare à l'empreinte recalculée.
- Baseline SpotBugs : 3 entrées sur `ProjectFingerprintService` dans `quality/spotbugs-baseline/minos-engine.xml`, avec numéros de ligne.

**Non mesuré** : la durée d'un hachage complet sur ce dépôt ; la mesure « 12,7 à 36 s » citée par l'audit porte sur la découverte et n'a pas été refaite. **Non vérifié** : aucun build ni test exécuté ; l'ADR 0039 n'a pas été relu en entier.

### Écarts avec l'action proposée par l'audit

1. **« Même politique d'ignore, même racine » n'est vrai que par défaut.** `ExecutionCheckpoints.java:31` utilise un `new ProjectFingerprintService()` statique ; l'orchestration utilise `application.fingerprintService()` (`CLI:96`), surchargeable (`minos-application/src/main/java/com/minos/application/MinosApplicationAssembler.java:80-81`). Réutiliser l'empreinte « avant » sans précaution persisterait une valeur calculée avec une autre politique que celle que la reprise recalculera.
2. **La valeur persistée doit rester la même.** Si l'empreinte réutilisée diffère de celle que `ExecutionCheckpoints.scopeFingerprint` aurait produite, toute reprise est refusée (`IndexingResumePlanner.java:272`) : c'est fail-closed, mais c'est une régression de la reprise.
3. **Le raccourci taille / date pour l'empreinte « après » affaiblit la preuve de stabilité.** L'exigence « L'empreinte de référence est capturée avant la découverte » de `decouverte-et-negociation` repose sur l'égalité de deux hachages complets ; une heuristique de même nature est rejetée dans `minos-application/src/main/java/com/minos/context/LocalSourceReader.java:133-135` (résolution grossière des dates).
4. **Réutiliser ne vaut que pour le scope racine.** L'empreinte d'un sous-scope ajoute les fichiers de contrôle et descripteurs de ses ancêtres (`ProjectFingerprintService.java:63-74`) : la dériver par filtrage n'est pas une simple sélection de chemins.

## Goals / Non-Goals

**Goals** : au plus deux hachages complets par indexation à cible racine ; aucune valeur persistée ne change ; la preuve de stabilité reste un hachage complet ; la réutilisation est refusée quand elle n'est pas prouvée sûre.

**Non-Goals** : accélérer l'empreinte « après » ; toucher à la vérification de reprise ; changer le format des points de reprise.

## Decisions

### D1. L'orchestration transmet l'empreinte « avant » à l'exécuteur de run

L'empreinte déjà calculée est passée au contexte du run ; pour le scope racine (chemin relatif vide), elle remplace la capture. Les scopes non racine et l'absence d'empreinte transmise gardent le chemin actuel.

### D2. L'égalité des politiques est prouvée, sinon l'empreinte est recalculée

Recommandation : l'exécuteur de run et la vérification de reprise utilisent **le même** service d'empreinte que l'orchestration (injecté, plus de champ statique dans `ExecutionCheckpoints`). Alors l'empreinte transmise est, par construction, celle que la reprise recalculera. Tant que ce n'est pas le cas, la réutilisation n'a lieu que si le service de l'orchestration est celui par défaut ; sinon l'empreinte de scope est recalculée. Aucune reprise n'est rendue possible par une valeur non prouvée.

Alternative écartée : comparer les deux politiques champ par champ ; fragile dès qu'une politique gagne un paramètre.

### D3. L'empreinte « après » reste un hachage complet — **décision en attente du propriétaire**

Recommandation : **ne pas adopter** le raccourci taille / date / clé de fichier. La promotion de la baseline atteste que l'arbre indexé est celui de l'empreinte ; une heuristique peut manquer une modification qui conserve taille et date observable, et la baseline promue ferait ensuite conclure à tort « rien n'a changé ». Le gain de ce changement (un hachage sur trois) est obtenu sans ce risque.

Alternative : adopter le raccourci avec relecture complète en cas de doute, par un changement séparé, un amendement de l'ADR 0039 et une preuve par système de fichiers (NTFS, ext4, résolution des dates).

### D4. Les sous-scopes ne sont pas dérivés

Le filtrage de l'empreinte du projet pour un scope non racine est reporté : il faut d'abord prouver l'égalité octet pour octet avec `captureScope` sur les ancêtres (écart 4).

## Direction des dépendances

`domain → engine → runtime/storage → adapters → application → surfaces`. L'empreinte descend de l'orchestration (`minos-application` après AUD-ARC-02) vers `minos-engine` par un paramètre : aucune arête nouvelle, aucun adaptateur nommé.

## Qualification des capacités

- Indexation et reprise : qualifiées, inchangées ; la reprise d'un run commencé avant le changement reste acceptée (test).
- Réduction du nombre de hachages : qualifiée pour une cible racine avec le service d'empreinte commun ; **non supportée** pour les scopes non racine (D4) et pour l'empreinte « après » (D3).

## Windows et Linux

Aucune différence de comportement. La résolution des dates de modification diffère entre systèmes de fichiers : une raison de plus pour D3.

## Risks / Trade-offs

- Une empreinte réutilisée mais inexacte ferait refuser les reprises : D2 et un test d'égalité avec `captureScope(root, "")` le couvrent.
- Toute modification de `ProjectFingerprintService` décale les 3 entrées de la baseline SpotBugs : la régénérer dans la même tâche, sans y ajouter de constat.
- Si l'unification (AUD-ARC-02) n'est pas faite, le paramètre traverse `minos-cli` : possible, mais à refaire ensuite ; d'où l'ordre recommandé.
- Le gain n'est pas mesuré au HEAD : la tâche 1.1 le relève, et un gain négligeable sur ce dépôt est une issue à dire.
