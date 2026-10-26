# Design

## Context

Constat **AUD-ARC-01**, lot 5 de l'inventaire du lot 1 (`openspec/changes/archive/2026-10-24-faire-consommer-l-indexation-aux-surfaces/design.md:101` : « cas d'usage applicatif produisant les données d'export, traduction JSON dans l'adaptateur (SH-09) », prérequis SH-04). ADR applicables : 0057 § 6 (les surfaces consomment des cas d'usage), 0058 § 3 (usages du moteur par les surfaces = dette), 0068 (direction des dépendances, un package par module), 0020 (contrat JSON NEXUS versionné). HEAD analysé : `50134955` (sommet du lot 6, `refactor/descendre-le-noyau-transverse`, PR #566 non fusionnée).

## État vérifié au HEAD

### Les tables A11

- `scripts/architecture/check-module-boundaries.py:515-567` : `ENGINE_TYPES_NAMED_BY_SURFACE`, **33 types** (API 15, CLI 11, MCP 2, NEXUS 5). NEXUS (`:560-566`) : `io.Sha256`, `registry.ProjectRegistry`, `registry.RegisteredProject`, `store.CodeKnowledgeSnapshot`, `store.CodeKnowledgeSnapshotStore`, chacun pour 1 fichier. CLI (`:546-551`) : `registry.ProjectRegistry` et `store.CodeKnowledgeSnapshotStore`, usages par accesseur, avec le commentaire « Removed by the NEXUS export lot of AUD-ARC-01 ».
- `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java:92-125` : `ENGINE_TYPES_USED_BY_SURFACE_BYTECODE`, **28 types** (API 13, CLI 8, MCP 2, NEXUS 5) ; NEXUS `:120-125`, mêmes 5 types. La CLI n'y a aucune entrée `registry`/`store` : le bytecode ne voit pas le type rendu par un accesseur passé en argument.
- Les accesseurs comptés par A11 sont lus dans `MinosApplication` (`check-module-boundaries.py:654-694`) : un appel `projectRegistry()` ou `snapshotStore()` par une surface compte comme un usage du type rendu.

### Usages, fichier par fichier

- `minos-nexus/src/main/java/com/minos/nexus/NexusExportService.java` (372 lignes) : imports `:9-14` (`Sha256`, `ProjectRegistry`, `RegisteredProject`, `UnreadableRegistryException` du domaine, `CodeKnowledgeSnapshot`, `CodeKnowledgeSnapshotStore`) ; constructeur `:47-50` ; lecture stricte du registre avec explication d'une entrée illisible `:56-67` ; `export` `:69-91` (racine canonique, projet par racine, snapshot actif par `loadActiveKnowledge`, sélection, chemins, projection) ; sélection bornée `:93-138` ; projection vers le contrat `:156-203` ; résolution des chemins par parcours de l'arbre `:205-258` ; identifiant stable `file:` + SHA-256 `:274-276` ; `BoundedById` `:329-371`.
- `minos-cli/src/main/java/com/minos/cli/MinosCliRunner.java:115-118` : `new NexusExportService(opened.projectRegistry(), opened.snapshotStore()).export(projectRoot)`, dans une poignée paresseuse (l'application n'est ouverte qu'à l'exécution).
- `minos-cli/src/main/java/com/minos/cli/command/NexusExportCommand.java:39-45` : la commande reçoit un `ExportOperation` (ou un `NexusExportService`) et rend le JSON (`:104-177`) ; le préfixe du refus « project root is not registered in MINOS » y est reconnu (`:21`).
- `minos-nexus/pom.xml:17-22` déclare `minos-engine` (« ADR 0058: used directly, so declared »). `NexusExportService` est la seule classe de production de `minos-nexus` qui nomme le moteur ; `NexusSemanticSignalService` n'utilise que `MinosApplication` et `HybridSearchService`.
- Tests : `NexusExportRefusalMessageTest.java:45-58` construit des doublures de `ProjectRegistry` et de `CodeKnowledgeSnapshotStore` ; `NexusExportBoundedSelectionTest` (45 lignes) teste `BoundedById` ; `minos-app/.../NexusExportIntegrationTest.java:33-35` construit le service sur `LocalProjectRegistry` et `FileSymbolSnapshotStore`.

### Le port de lecture des snapshots (SH-04)

- `minos-engine/src/main/java/com/minos/store/CodeKnowledgeReader.java:17-39` existe : lectures bornées par critères (`findSymbols`, `findFileSymbols`, `findUsages`, `findRelationships`), **aucune énumération** du snapshot. L'export parcourt tous les symboles et toutes les relations (`NexusExportService.java:101`, `:121`).
- `CodeKnowledgeSnapshotStore.java:27` (`loadActiveKnowledge`, le snapshot persisté) et `:45` (`loadActiveReadView`, vue de lecture qui porte l'index mémoire, `SnapshotReadView.java:41-47`). Les lectures applicatives d'un snapshot entier passent par `loadActiveKnowledge` (`HybridSearchService.java:57`, `ProgramGraphService.java:149`, `LocalProjectArchitectureQuery.java:90`). La règle A18 (`check-module-boundaries.py:938-994`) n'interdit que la vue dépréciée `SnapshotQueryView`, l'index mémoire et le port d'écriture.

### Arête `minos-nexus` → `minos-engine`

- `ALLOWED_DEPENDENCIES` (`check-module-boundaries.py:77`) et `ModuleArchitectureTest.ALLOWED` (`:71`) listent `minos-engine` pour `minos-nexus` ; A17 exige les deux tables identiques. La règle stricte `everyModuleOnlyUsesItsAllowedModules` (`ModuleArchitectureTest.java:233-254`) ne lit que les classes de **production**.
- Après la refonte, aucune classe de production de `minos-nexus` n'utilise le moteur : l'arête peut être retirée. Les tests de `minos-nexus` qui ouvrent une application réelle (`NexusSemanticSignalServiceTest.java:28-30`, appels d'accesseurs) atteignent le moteur par `minos-application`, en portée de test, comme aujourd'hui.
- Documentation qui cite l'arête : `docs/architecture/arc42/05-vue-blocs.md:98`, `docs/architecture/diagrams/module-dependencies.md:97` (généré), `docs/architecture/adr/ARCH-0058-declarer-les-usages-de-modules.md:20` (constat du 2026-10-09, historique de la décision) et `:70`.

### Gates et outillage qui lisent ces fichiers

- `scripts/remediation/check-mne.py:51`, `:119-122` : MNE-12 exige `MAX_SYMBOL_SELECTION_WEIGHT_BYTES`, `MAX_RELATION_SELECTION_WEIGHT_BYTES`, `ToLongFunction<T>` et `relationshipWeight` dans `NexusExportService.java`.
- `scripts/quality/check-jacoco.py:260` : portée `nexus-export`, préfixes `com/minos/nexus/` et `com/minos/integration/nexus/`, planchers ligne 0,30 et branche 0,12.
- `scripts/quality/comment-language.json:41` : `minos-nexus` a un maximum de 3 commentaires à jalon (dont « Read-only M13 projection », `NexusExportService.java:36`).
- `quality/spotbugs-baseline/minos-nexus.xml` : 4 entrées `EI_EXPOSE_REP`, toutes sur `NexusExportContract$ExportSnapshot` et `$ExportRelation`, que ce lot ne touche pas.
- Goldens : les 12 de `minos-app/src/test/resources/characterization/` ne couvrent pas `nexus-export` ; aucun golden d'export n'existe. `A2SurfaceCharacterizationTest.java:507-531` sait enregistrer, indexer et figer l'identité du projet (`pinProjectIdentity`, `:544-557`).
- Documentation : `docs/developer/public-surfaces.md:38`, `:50` (`NexusExportService --> MinosApplication`), `:266` ; `docs/developer/architecture.md:31-32` (`NexusExportService --> LocalProjectRegistry`, `--> FileSymbolSnapshotStore`) ; `docs/user/java-api.md:369` (classes de `com.minos.nexus`).

**Vérifié par le garde** : `types_named_by_module` rend exactement les 5 entrées NEXUS, toutes pour `NexusExportService.java`, et les deux entrées CLI pour `MinosCliRunner.java` seul (aucun autre appel de `projectRegistry()` ni `snapshotStore()` dans `minos-cli/src/main`).

**Non vérifié** : aucun build ni test Java exécuté pour cette spécification ; la couverture JaCoCo des classes déplacées n'est pas mesurée ; le passage de SpotBugs sur les nouveaux records n'est pas mesuré ; le contenu exact du futur golden (identifiants de run) n'est pas relevé.

## Écarts avec l'action proposée

- La consigne citait le port `CodeKnowledgeReader` comme appui. Il existe sous ce nom, mais il ne sait pas énumérer un snapshot (D1).
- L'inventaire du lot 1 comptait 6 entrées NEXUS ; le lot 6 a descendu `UnreadableRegistryException` dans le domaine : il en reste 5. La mesure élargie aux accesseurs (2026-10-25) a ajouté 2 entrées à la CLI qui partent avec ce lot : 7 entrées retirées au total.
- L'audit ne prévoyait pas le retrait de l'arête `minos-nexus` → `minos-engine` ; il devient possible et l'ADR 0058 § 3 en fait l'objectif (« Retirer une de ces arêtes, une fois les usages migrés »).

## Goals / Non-Goals

**Goals** : faire de l'export NEXUS un cas d'usage de `minos-application` ; ne laisser à `minos-nexus` que la traduction vers le contrat ; retirer les 7 entrées A11 et l'arête `minos-nexus` → `minos-engine` ; prouver la sortie inchangée par un golden.

**Non-Goals** : modifier le contrat, la commande, ses messages ou ses codes ; étendre un port du moteur ; toucher `minos-engine`, `minos-bootstrap`, un adaptateur ou l'API ; traiter les entrées de l'API (lot 4).

## Decisions

### D1 — Le cas d'usage lit le snapshot persisté par `loadActiveKnowledge`

- **A (retenue)** : `CodeKnowledgeSnapshotStore.loadActiveKnowledge`, comme les autres lectures d'un snapshot entier de l'application. Comportement et coût identiques à aujourd'hui.
- **B (écartée)** : `loadActiveReadView().snapshot()` : même donnée, mais le magasin de fichiers construit (et met en cache) l'index mémoire, inutile à un parcours complet.
- **C (écartée)** : ajouter une énumération à `CodeKnowledgeReader` : change un port du moteur et ses implémentations pour un seul appelant, hors du lot.

Décision technique, sans arbitrage.

### D2 — Le cas d'usage rend les données, l'adaptateur traduit

`KnowledgeExport(projectId, displayName, rootPath, snapshotId, List<ExportedSymbol>, List<ExportedRelationship>, List<String> limitations)`, avec `ExportedSymbol(Symbol symbol, String filePath)` et `ExportedRelationship(Relationship relationship, String filePath)` (types de domaine, listes copiées, ordre par identifiant). Le cas d'usage fait toute la sélection, y compris l'omission des symboles et des relations dont le chemin ne se résout pas, pour que les limitations restent produites dans le même ordre. `NexusExportService` traduit champ par champ (noms d'énumérations, repli `qualifiedName` → `name`, signature vide, origine, preuves) ; les noms qualifiés de source et de cible d'une relation viennent des symboles exportés. Alternative écartée : rendre des records calqués sur le contrat depuis l'application, qui dupliqueraient `NexusExportContract` et déplaceraient la traduction hors de l'adaptateur.

### D3 — `BoundedById` et les bornes suivent la sélection

`BoundedById` devient une classe imbriquée privée au package de `KnowledgeExportUseCase`, avec son test. Les constantes `MAX_EXPORTED_SYMBOLS`, `MAX_EXPORTED_RELATIONS`, `MAX_FILE_PATH_CANDIDATES`, `MAX_SYMBOL_SELECTION_WEIGHT_BYTES`, `MAX_RELATION_SELECTION_WEIGHT_BYTES` sont définies dans le cas d'usage ; `NexusExportService` garde ses constantes publiques de même nom et de même valeur, qui y renvoient. MNE-12 lit le cas d'usage.

### Q1 — Constructeur de `NexusExportService` (au propriétaire)

`NexusExportService` est une classe publique de `com.minos.nexus`, listée par `docs/developer/public-surfaces.md` ; ses seuls appelants au HEAD sont `MinosCliRunner` et `NexusExportIntegrationTest`. NEXUS consomme le JSON de `minos nexus-export` (ADR 0020), pas la classe.
- **A (recommandée)** : le constructeur devient `NexusExportService(MinosApplication)`, comme `NexusSemanticSignalService` et comme le dessine déjà `public-surfaces.md:50`. Rupture de source et binaire pour un appelant direct, consignée dans `docs/user/java-api.md` § Ruptures (version 1.3.0) ; `export(Path)`, le contrat et les constantes ne changent pas.
- **B** : garder l'ancien constructeur, déprécié, à côté du nouveau. `ProjectRegistry` et `CodeKnowledgeSnapshotStore` restent nommés par `minos-nexus` : 2 entrées A11 et l'arête vers le moteur restent, le lot n'est pas résorbé.

### Q2 — Retrait de l'arête `minos-nexus` → `minos-engine` (au propriétaire)

- **A (recommandée)** : retirer `minos-engine` du POM de `minos-nexus`, de `ALLOWED_DEPENDENCIES`, de `ModuleArchitectureTest.ALLOWED` ; régénérer `module-dependencies.md` ; mettre à jour l'arc42 § 5 et la mise en œuvre de l'ADR 0058. La règle stricte garde ensuite le retrait (un usage du moteur par une classe de production de `minos-nexus` fait échouer le build). Les tests de `minos-nexus` atteignent le moteur par `minos-application`, comme le test sémantique aujourd'hui.
- **B** : garder l'arête déclarée sans usage. La table A11 de NEXUS est vide, mais l'arête reste un droit ouvert que rien ne justifie.

### Q3 — Golden de l'export (au propriétaire)

Aucun golden n'existe pour l'export.
- **A (recommandée)** : ajouter `nexus-export.golden` (13ᵉ golden) par un test de `A2SurfaceCharacterizationTest` : enregistrement de la fixture TypeScript, identité figée, import SCIP, puis `minos nexus-export --root`, stdout et stderr normalisés. Il est écrit et commité **avant** la refonte (vert au HEAD), puis reste identique ; les 12 goldens existants ne changent pas.
- **B** : preuve locale seulement (sortie capturée avant et après sur un même `MINOS_HOME`, comparée par `diff`), sans fichier commité.

## Direction des dépendances (ADR 0068)

`minos-cli` → `minos-nexus` → `minos-application` → `minos-engine` → `minos-domain`. Le package `com.minos.application.export` est sous la racine `application` de `minos-application` (A14) ; il importe `com.minos.application` (`MinosApplication`), jamais l'inverse (A8) ; il n'importe aucun adaptateur (A2). `MinosApplication` ne gagne aucun accesseur : le cas d'usage s'obtient par `KnowledgeExportUseCase.of(MinosApplication)`, comme `RemoteIndexingUseCase.of`. Les quatre types publics du package ont un appelant de production dans `minos-nexus` (A15). Avec Q2 = A, `minos-nexus` ne dépend plus que de `minos-domain` et `minos-application` (et de `minos-bootstrap` en test).

## Capacités

| Capacité | Qualification |
|---|---|
| Export NEXUS v1 (`minos nexus-export`) | qualifiée, inchangée : mêmes bornes, mêmes limitations, mêmes refus |
| Signaux sémantiques NEXUS v2 | inchangés, hors du lot |

## Windows et Linux

Le parcours de l'arbre (`Files.walkFileTree`), le refus des liens symboliques et des entrées spéciales, la comparaison de chemins canoniques (`toRealPath`) et la normalisation des séparateurs (`\` → `/`) sont déplacés sans modification. Le golden est indépendant de l'hôte : chemins de la fixture relatifs, racine et répertoire temporaire normalisés par `CharacterizationNormalizer`, identité de projet figée. Les tests déplacés s'exécutent dans les deux jobs `Verify` ; aucun n'est restreint à une plateforme. Sur le poste Windows, la portée JaCoCo `m24-polyglot-provider-platform` est rouge hors régression.

## Coût

| Poste | Estimation au HEAD |
|---|---|
| Code de production monté dans `minos-application` | ≈ 300 lignes : sélection, chemins, `BoundedById`, lecture stricte du registre (de `NexusExportService.java:44-372`), plus 3 records (≈ 60 lignes). **Code neuf** pour SonarCloud : ≥ 80 % couverts par les tests de `minos-application` |
| Code restant dans `minos-nexus` | `NexusExportService` réduit à la traduction (≈ 90 lignes), compté comme modifié ; couvert par un test sur une application réelle (`minos-bootstrap` en test, déjà déclaré) |
| Tests | vers `minos-application` : `NexusExportBoundedSelectionTest` (45 lignes, devient `KnowledgeExportBoundedSelectionTest`) ; `NexusExportRefusalMessageTest` (59, doublures du registre et du magasin) devient `KnowledgeExportRefusalTest` ; nouveaux cas unitaires du cas d'usage (sélection, omissions, chemins directs et stables, troncature, registre illisible expliqué, snapshot absent). Restent dans `minos-nexus` : `NexusExportContractTest` ; nouveau `NexusExportServiceTest` (traduction, refus par nom de feuille). `minos-app` : `NexusExportIntegrationTest` construit le service sur `MinosApplication.open` |
| Golden | `nexus-export.golden` ajouté avant la refonte (Q3) ; les 12 goldens existants sont rejoués par `A2SurfaceCharacterizationTest`, aucun n'est régénéré |
| `check-jacoco.py` | portée `nexus-export` (`:260`) : ajouter le préfixe `com/minos/application/export/` ; planchers inchangés, mesurés sur le rapport agrégé |
| Gates littéraux | `check-mne.py:51`, `:119-122` : MNE-12 lit `minos-application/.../export/KnowledgeExportUseCase.java` ; `check-module-boundaries.py:77` (A2), `:515-567` (A11) ; `ModuleArchitectureTest.java:71`, `:92-125` ; `test_check_module_boundaries.py` (comptes lus dans la table, un cas qui refuse le retour d'un type NEXUS) |
| Langue des commentaires | `comment-language.json:41` : le maximum de jalons de `minos-nexus` baisse si « M13 » disparaît de la Javadoc ; aucun commentaire neuf en français ni à jalon |
| SpotBugs | `minos-nexus.xml` inchangée (entrées du contrat) ; une alerte sur un record neuf de l'application n'entre pas dans `minos-application.xml` : elle se corrige dans le code |
| Documentation courante | ADR 0058 (mise en œuvre) ; arc42 § 5 (`:97-98`) ; `module-dependencies.md` (généré) ; `docs/developer/architecture.md:19-32`, `:164-174` ; `docs/user/java-api.md` § Ruptures (Q1) ; `SUIVI.md` (ligne du lot 5) ; plan `storage-hexagonal-2026-10` (SH-09 partiellement fait) |

## Risks / Trade-offs

- **Ordre des limitations changé par la séparation** → le cas d'usage produit toutes les limitations, l'adaptateur n'en ajoute aucune ; le golden et un test unitaire de l'ordre le gardent.
- **Message de refus changé** (la CLI reconnaît son préfixe) → messages repris à l'identique ; `RegistryToleranceCliTest.nexusExportStaysStrictAndSaysWhy` et le test de refus par nom de feuille sont rejoués.
- **Faux retrait** (`var`, chaînage d'accesseur) → le garde compte les appels d'accesseurs, le bytecode les dépendances ; la CLI passe l'application, pas ses ports.
- **Ouverture anticipée de l'application par la CLI** → la construction du service reste dans la poignée paresseuse ; `LazyWiringGuardTest` est rejoué.
- **Conflits textuels** avec les lots 3 et 4 (tables A11, `SUIVI.md`, mise en œuvre de l'ADR 0058) → lignes disjointes ; le second fusionné rebase et rejoue le garde et son auto-test.

## Recouvrements avec les changements actifs

| Changement | Fichiers communs | Effet |
|---|---|---|
| `descendre-le-noyau-transverse` (lot 6, PR #566) | tables A11, `SUIVI.md`, ADR 0058 | base de cette branche ; rebase sur `develop` après sa fusion, décomptes inchangés |
| lots 3 et 4 d'AUD-ARC-01 (sessions parallèles) | tables A11 (lignes `hosted.*`, `git.*`, `query.*`, entrées API), `SUIVI.md`, ADR 0058 | lignes disjointes ; aucun fichier de code commun |

## Migration Plan

Une PR vers `develop`, branche `refactor/faire-consommer-l-export-nexus`, après la fusion de #566. Ordre : golden, cas d'usage, adaptateur et câblage, arête et cliquets, documentation. Retour arrière : revert de la PR ; aucun format ni donnée touchés.

## Open Questions

Q1, Q2 et Q3 ci-dessus, chacune avec une recommandation (A).
