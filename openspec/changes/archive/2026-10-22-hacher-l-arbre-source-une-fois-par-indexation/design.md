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

### D3. L'empreinte « après » reste un hachage complet — **décidé : pas de raccourci taille / date (propriétaire, 2026-10-21)**

Recommandation : **ne pas adopter** le raccourci taille / date / clé de fichier. La promotion de la baseline atteste que l'arbre indexé est celui de l'empreinte ; une heuristique peut manquer une modification qui conserve taille et date observable, et la baseline promue ferait ensuite conclure à tort « rien n'a changé ». Le gain de ce changement (un hachage sur trois) est obtenu sans ce risque.

Alternative : adopter le raccourci avec relecture complète en cas de doute, par un changement séparé, un amendement de l'ADR 0039 et une preuve par système de fichiers (NTFS, ext4, résolution des dates).

### D4. Les sous-scopes ne sont pas dérivés

Le filtrage de l'empreinte du projet pour un scope non racine est reporté : il faut d'abord prouver l'égalité octet pour octet avec `captureScope` sur les ancêtres (écart 4).

## Décision du propriétaire, mesure et relevé d'implémentation (2026-10-21)

- **D3** : pas de raccourci taille / date / clé de fichier ; l'empreinte de fin de run reste un hachage complet (test `aContentChangeThatKeepsSizeAndDateIsStillAChangeOfTheWorkspace`).
- **Mesure (1.1)** : une capture complète de l'empreinte de ce dépôt (2 625 fichiers retenus, disque `N:` sous Windows, JDK 25) prend 7,8 à 9,4 s (5 captures successives, programme jetable hors dépôt). Avant : 3 captures complètes par indexation à cible racine (avant, scope racine, après) ; après : 2. Gain attendu : une capture, soit environ 8 s sur ce dépôt pour une cible racine ; **non mesuré** sur un `minos index` réel (aucun provider exécutable dans la session), ni en reprise ni en incrémental.
- **Tests de reprise existants qui comparent `scopeFingerprint`** : `minos-engine` `IndexingResumeTest`, `InterruptedRunRecoveryTest`, `minos-bootstrap` `orchestration/*` (reprise après arrêt brutal) ; verts.
- **D1 réalisé** : `ReferenceFingerprint` (nouveau, `com.minos.orchestration`) porte l'empreinte « avant » et le service qui l'a capturée ; `IndexingLifecycleService.execute/executePlanned` (variantes à découverte) l'acceptent ; `IndexingRunExecutor` la place dans ses `Ports` et l'emploie pour le scope racine uniquement.
- **D2, première étape (2026-10-21)** : garde `ProjectFingerprintService.capturesLike` (règles identiques) devant un service statique de `ExecutionCheckpoints`. Remplacée par la forme ci-dessous.
- **D2 réalisé sous la forme « service injecté » (2026-10-22, décision de forme demandée par le propriétaire)** : forme la plus étroite retenue, **un constructeur public de plus** sur `IndexingLifecycleService` — `(executors, stager, promoter, stateStore, markers, ProjectFingerprintService)` — et un constructeur de paquet à 8 paramètres (même liste que l'existant à 7, plus le service) pour les tests. Tous les constructeurs existants sont conservés et délèguent avec `new ProjectFingerprintService()` : aucune signature publique ne disparaît, aucun appelant n'est modifié hors `ProjectIndexingUseCase`, qui passe son propre `fingerprintService`. Le service descend ensuite par paramètre : `IndexingRunExecutor.execute(…, fingerprints, rootScopeFingerprint)` le range dans ses `Ports` (capture du point de contrôle), `IndexingResumePlanner.Request` le porte (recalcul à la vérification de reprise), `ExecutionCheckpoints.scopeFingerprint(fingerprints, root, scope)` le reçoit. Le champ statique `FINGERPRINTS` et `capturesLikeCheckpoints` disparaissent. Écarté : un paramètre de plus sur `execute/executePlanned` (surface publique plus large, à chaque appel) et un `Builder` (hors de proportion).
- **Garde `capturesLike` supprimée, preuve par construction** : le point de contrôle et la reprise d'un même cycle de vie utilisent la même instance, et l'empreinte « avant » n'est réutilisée que si `ReferenceFingerprint.capturedBy` **est** cette instance (`reusableRootScopeFingerprint(runService)`, comparaison d'identité). La valeur persistée est alors exactement ce que la reprise recalcule avec ce service. Une autre instance, même de règles égales, fait recapturer le scope racine (filet fail-closed d'une ligne, sans comparaison de politiques, alternative déjà écartée ci-dessus). `ProjectFingerprintService.capturesLike` n'a plus d'appelant et est retirée (ajoutée par ce changement, PR #527, absente de toute release). Une reprise entre deux processus aux services de règles différentes est refusée (empreinte différente) : fail-closed, sans valeur non prouvée.
- **Valeur persistée inchangée à l'octet** : `scopeFingerprint` reste `captureScope(root, scope).projectSha256()` ; seul l'appelant change d'instance. Avec le service par défaut, les valeurs sont identiques à celles du HEAD (tests de reprise et de crash existants verts sans modification de leurs attentes).
- **Fenêtre assumée** : l'empreinte réutilisée est capturée avant la découverte, alors que l'ancienne l'était juste avant le provider. Une source modifiée entre les deux donne un point de reprise qui ne correspond pas à l'arbre courant : la reprise est alors refusée (fail-closed) et la baseline n'est pas promue (empreinte de fin différente).
- **Reprise d'un run antérieur** : pas de fixture écrite par le code du HEAD ; la preuve est l'égalité `capture(root) == captureScope(root, "")` et l'égalité de la valeur persistée avec la capture du scope sur un arbre inchangé (`theReusedValueIsExactlyTheCaptureOfTheRootScopeSoAResumeAcceptsIt`).
- **SpotBugs** : `capturesLike` avait été ajoutée en fin de `ProjectFingerprintService` (borne de fin de classe 357 → 369) ; son retrait ramène la borne des 3 entrées de la baseline à 359 (les autres lignes de la classe ont bougé depuis). Aucune entrée ajoutée. Non revérifié par une exécution de SpotBugs (le `verify` local ne le lance pas).

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
