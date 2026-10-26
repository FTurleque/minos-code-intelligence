# Design

## Context

Constat **AUD-ARC-01**, lot 4 de l'inventaire du design archivé `openspec/changes/archive/2026-10-24-faire-consommer-l-indexation-aux-surfaces/design.md` (« Lots suivants », ligne 100, et décision D4, lignes 127-129). Troisième lot de la phase 3, après le lot 6 (`descendre-le-noyau-transverse`, PR #566). ADR applicables :

- 0057 § 6 : « faire consommer [les] résultats [des cas d'usage] par API/CLI/MCP/NEXUS ; conserver les contrats publics » ;
- 0058 § 3 : un usage du moteur par une surface est une dette ;
- 0068 § 2 et § 3 : direction des dépendances ; `com.minos.query` et `com.minos.git` appartiennent au moteur (`ARCH-0068-…md:86`).

## État vérifié au HEAD

HEAD : `50134955` (branche `refactor/descendre-le-noyau-transverse`, lot 6 implémenté, non fusionné). Ce changement part de ce sommet et se rebase sur `develop` après la fusion de #566.

### Les entrées du lot dans les tables A11

| Entrée | Garde Python (`check-module-boundaries.py:515-563`) | Bytecode (`ModuleArchitectureTest.java:92-123`) | Fichiers de production |
|---|---|---|---|
| `git.GitIntelligence` (API) | 1 (`:517`) | 1 (`:94`) | `LocalMinosMultiRepositoryApi.java:4`, `:19`, `:36` (`app.gitIntelligence()`), `:205`, `:243-286` |
| `git.GitIntelligence` (CLI) | 3 (`:536`) | 3 (`:108`) | `GitActivityCommand.java:7`, `:48-62`, `:79-155` ; `MinosCli.java:32`, `:136`, `:155-156`, `:294`, `:345-346` ; `MinosCliRunner.java:24`, `:122-123` (`LazyApplication.lazy(GitIntelligence.class, …)`) |
| `query.SymbolResult`, `query.UsageResult`, `query.RelationshipResult` (API) | 1 chacun (`:527-529`) | 1 chacun (`:104-106`) | `LocalMinosApi.java:33-35`, `:303-330` |
| `query.SymbolResult`, `query.UsageResult`, `query.RelationshipResult` (CLI) | 1 chacun (`:540-542`) | absents : seulement en argument générique (`List<…>`), que le bytecode ne voit pas | `FindSymbolCommand.java:10`, `:56` ; `FindUsagesCommand.java:8`, `:40` ; `RelationshipCommand.java:12`, `:64` |
| `registry.ProjectRegistry`, `store.CodeKnowledgeSnapshotStore` (API) | 1 chacun (`:530-533`, accesseurs) | absents | `LocalMinosApi.java:96` : `new LocalProjectSymbolQuery(app.projectRegistry(), app.snapshotStore())` |

Soit 10 entrées du garde (API 6, CLI 4, dont 6 `query.*`) et 5 entrées bytecode (API 4, CLI 1). Les entrées `registry.ProjectRegistry` et `store.CodeKnowledgeSnapshotStore` de la **CLI** (`:548-552`, `MinosCliRunner.java:117` → `NexusExportService`) appartiennent au lot 5 et ne sont pas touchées.

### Faits relus

- `GitIntelligence` (`minos-engine/…/git/GitIntelligence.java`, 123 lignes) est un port : `inspect(Path)` et `analyze(Path, ActivityQuery)`, tous deux `throws Exception` pour que l'adaptateur propage tel quel l'échec de JGit (`:12-16`). Ses records imbriqués (`ActivityQuery`, `RepositoryView`, `CommitActivity`, `FileActivity`, `ZoneActivity`, `ActivityReport`, `:33-122`) n'utilisent que le JDK. `ActivityQuery` valide ses bornes dans son constructeur compact (`:45-57`).
- `MASKED_AUTHOR_EMAIL` (`:24`) n'est lu par aucune surface : le masquage est fait par l'adaptateur (`GitIntelligenceService.java:160`).
- `GitActivityCommand.failureMessage` (`:164-170`) déballe une seule `RuntimeException` à cause : la requête applicative ne doit ajouter aucune enveloppe.
- `LazyApplication.lazy` (`minos-cli/…/LazyApplication.java:139-142`) crée un `java.lang.reflect.Proxy` : le collaborateur passé à `MinosCli.Builder` doit être une **interface**.
- `LocalProjectSymbolQuery` a déjà un constructeur `(MinosApplication)` (`minos-application/…/LocalProjectSymbolQuery.java:29-32`) qui lit `application.projectQueryService()`. Ce service est construit une fois sur les mêmes magasins (`MinosApplication.java:182`, accesseur `:297`) et ne garde aucun état (`ProjectQueryService.java:31-42` : un résolveur et le magasin de snapshots). Le constructeur à deux magasins reste utilisé par des tests et des sondes (`ScipSymbolSnapshotImporterTest`, `LocalProjectSymbolQueryTest`, `scripts/m15/M15FinalQueryProbe.java:52`).
- `SymbolResult`, `UsageResult` et `RelationshipResult` (`minos-engine/…/query/`) ne dépendent que de `com.minos.domain` et du JDK, sans E/S. Ils sont produits par les services du moteur `SymbolQueryService` et `RelationshipQueryService` (même paquet), rendus par le port applicatif `ProjectSymbolQuery` (`minos-application/…/ProjectSymbolQuery.java:9-11`, `:21-37`) et rendus en sortie par `SymbolResultRenderer` (application). Ils apparaissent dans 20, 12 et 11 fichiers (application 13, CLI 9 avec les tests, API 1, `minos-app` 2).
- Aucune signature de `com.minos.api` ne nomme ces types : `LocalMinosApi` les convertit en `SymbolDto`, `UsageDto`, `RelationshipDto` (`:303-330`).
- La sortie Git de la CLI est gardée par `cli-git.golden` (`A2SurfaceCharacterizationTest.java:343-372`) ; l'API Git n'est pas dans `api.golden`, elle est couverte par `LocalMinosMultiRepositoryApiIntegrationTest`.
- Le MCP ne nomme ni `GitIntelligence` ni `query.*` (recherche dans `minos-mcp/src/main`).

**Vérifié par le garde** : `check_engine_types_named_by_surfaces()` est vert au HEAD avec les comptes ci-dessus ; `test_the_real_application_exposes_the_accessors_the_surfaces_call` (`test_check_module_boundaries.py:1213-1220`) lit `gitIntelligence`, `projectRegistry` et `snapshotStore` comme accesseurs à un type du moteur.

**Non vérifié** : aucun build Java exécuté pour cette spécification ; les tailles sont des `wc -l`, pas des lignes couvrables JaCoCo.

### Écarts avec l'action proposée

- L'inventaire du lot 1 comptait 8 entrées pour ce lot (API 4, CLI 4). La mesure élargie aux accesseurs (`rendre-les-cliquets-d-architecture-etanches`) y ajoute `registry.ProjectRegistry` et `store.CodeKnowledgeSnapshotStore` de l'API : 10 entrées, dont 4 retirées et 6 justifiées.
- D4 = a annonçait un « amendement de l'ADR 0057 § 6 ». Une décision acceptée ne se réécrit pas (`.claude/rules/documentation.md`, `docs/architecture/adr/README.md`, « Cycle de vie ») : la précision s'écrit dans la section « Mise en œuvre » de l'ADR 0057, au titre du point 6 (question Q2).

## Goals / Non-Goals

**Goals** : retirer `git.GitIntelligence` de la CLI et de l'API ; retirer les accesseurs `projectRegistry()` et `snapshotStore()` de l'API ; écrire la raison des 6 entrées `query.*` ; ne changer ni sortie ni signature publique.

**Non-Goals** : modifier le port Git ou son adaptateur ; retirer un accesseur de `MinosApplication` ; toucher aux entrées des lots 3, 5 et 6 ; déplacer les résultats de requête sans décision (Q1).

## Decisions

### D1 — Une requête applicative en interface, avec ses propres records

`ProjectGitQuery` (interface, `com.minos.application`) déclare `inspect(Path)` et `analyze(Path, ActivityRequest)`, `throws Exception` comme le port. Records imbriqués : `ActivityRequest`, `RepositoryView`, `CommitActivity`, `FileActivity`, `ZoneActivity`, `ActivityReport`, aux mêmes champs que ceux du port (`ActivityReport.request()` remplace `query()`). `LocalProjectGitQuery` l'implémente sur `GitIntelligence`, avec un constructeur `(MinosApplication)` (lit `gitIntelligence()` dans l'application) et un constructeur `(GitIntelligence)`.

- Interface : `LazyApplication.lazy` exige une interface ; c'est aussi la forme de `ProjectSymbolQuery`, `ProjectArchitectureQuery` et `ProjectImpactQuery`.
- Nom : `GitActivityQuery` est déjà le record de requête de `MinosMultiRepositoryApi`, que `LocalMinosMultiRepositoryApi` nomme sans qualification ; `ProjectGitQuery` évite la collision.
- Records applicatifs plutôt que records du port : une surface qui nommerait `GitIntelligence.ActivityReport` nommerait encore `git.GitIntelligence`. Ce sont des copies, ce que D4 (b) écartait pour les résultats de requête ; pour Git, D4 retient explicitement la requête applicative, et la copie se limite à 6 records sans logique.
- `ActivityRequest` valide ses bornes en construisant le record du port dans son constructeur compact : mêmes messages (`maxCommits must be between 1 and 10000`…), au même moment, sans dupliquer les constantes.
- Aucune enveloppe d'exception : `analyze` et `inspect` propagent l'exception du port telle quelle (même instance).

Alternative écartée : un service à classe finale (comme `WorkspaceIntelligenceService`). La CLI ne pourrait plus différer l'ouverture de `MINOS_HOME` par un proxy.

### D2 — L'API construit sa requête de symboles par l'application

`LocalMinosApi` appelle `new LocalProjectSymbolQuery(app)`. Le service rendu est équivalent : même résolveur sur le même registre, même magasin de snapshots, sans état propre. Aucun type ajouté.

### D3 — Les résultats de requête sont des modèles de lecture publiés (D4 = a)

`SymbolResult`, `UsageResult` et `RelationshipResult` restent dans `com.minos.query`. Leur raison s'écrit dans les deux tables A11 : ce sont les records que rend le port applicatif `ProjectSymbolQuery`, publiés aux surfaces comme modèles de lecture (ADR 0057, mise en œuvre du point 6). L'auto-test du garde vérifie que les seules entrées `query.*` des surfaces sont ces trois records. Un autre type de `com.minos.query` nommé par une surface reste refusé par A11.

### D4 — Tests dans le module de la classe

SonarCloud juge la couverture du nouveau code module par module. `LocalProjectGitQueryTest` (`minos-application`) couvre la conversion de chaque champ, la normalisation des listes nulles, les refus de `ActivityRequest` et la propagation de l'exception, avec un faux port. Les tests de la CLI (`GitActivityCommandTest`, `CliValidInvocationsTest`, `MinosCliBuilderTest`) passent aux types applicatifs sans changer leurs attentes. `GitActivityCommandTest` construit des records applicatifs au lieu de ceux de l'adaptateur JGit.

## Questions ouvertes

### Q1 — Résultats de requête : les reconnaître, ou les descendre dans le domaine ?

Le critère D5 du lot 6 (« un type sans E/S ni dépendance au moteur descend dans `minos-domain` ») s'applique aussi à ces trois records, ce que D4 = a (2026-10-22) n'avait pas examiné.

- **A (recommandée)** : les reconnaître comme modèles de lecture publiés, selon D4 = a. 6 entrées justifiées restent dans le garde, 3 dans le bytecode. Diff limité à ce lot.
- **B** : les descendre dans `com.minos.domain.query` (étape 3 de l'ordre de repli de l'ADR 0068 § 3, comme les types du registre au lot 6). A11 perdrait 6 entrées de plus (23 au lieu de 29) et 3 dans le bytecode, sans précision de l'ADR 0057. Coût : environ 35 fichiers d'imports dans 5 modules, 3 classes et leurs tests déplacés, commentaires passés en anglais, portée JaCoCo `domain-invariants`.

### Q2 — Forme de la précision de l'ADR 0057

- **A (recommandée)** : une entrée « Mise en œuvre » du point 6, au présent. La décision n'est pas réécrite, ce que demande la règle des ADR acceptés.
- **B** : réécrire le point 6 en place. Contraire à la règle des ADR acceptés.
- **C** : un ADR consolidé qui remplace l'ADR 0057. Disproportionné pour une précision.

Les compteurs et les tâches supposent Q1 = A et Q2 = A.

## Compteurs

| Table A11 | Avant | Après (Q1 = A) |
|---|---|---|
| Garde Python | 33 (API 15, CLI 11, MCP 2, NEXUS 5) | **29** (API 12, CLI 10, MCP 2, NEXUS 5) ; retirés : `git.GitIntelligence` ×2, `registry.ProjectRegistry` et `store.CodeKnowledgeSnapshotStore` de l'API |
| Bytecode | 28 (API 13, CLI 8, MCP 2, NEXUS 5) | **26** (API 12, CLI 7, MCP 2, NEXUS 5) ; retiré : `git.GitIntelligence` ×2 |

Les lots 3 et 5 retirent d'autres lignes des mêmes tables en parallèle : les totaux se recalculent au rebase, les deltas de ce lot restent les mêmes.

## Direction des dépendances (ADR 0068)

Inchangée : `minos-cli`, `minos-api` → `minos-application` → `minos-engine`. `ProjectGitQuery` et `LocalProjectGitQuery` vivent dans `com.minos.application`, qui importe déjà `com.minos.git` (`MinosApplication.java`) : aucune nouvelle arête de paquet, aucun cycle (A8). Aucun POM ne change. Les deux nouveaux types publics ont des appelants de production dans la CLI et l'API (A15).

## Capacités

Aucune capacité n'est ajoutée ni requalifiée. L'intelligence Git factuelle reste qualifiée et inchangée (ADR 0019).

## Windows et Linux

Aucun comportement ne dépend du système. Les tests touchés s'exécutent dans les deux jobs `Verify`. Le dépôt analysé passe toujours par l'adaptateur JGit et son contrôle du propriétaire (`GitIntelligenceTrustTest`), inchangé.

## Coût

| Poste | Estimation |
|---|---|
| Couverture du nouveau code (SonarCloud, 80 % par module) | `minos-application` : environ 150 lignes neuves (interface, 6 records, conversions), couvertes par `LocalProjectGitQueryTest`. `minos-cli` et `minos-api` : lignes modifiées couvertes par `GitActivityCommandTest`, `CliValidInvocationsTest`, `MinosCliBuilderTest` et `LocalMinosMultiRepositoryApiIntegrationTest` |
| Tests déplacés | aucun ; `GitActivityCommandTest` cesse d'utiliser les records de `GitIntelligenceService` |
| Portées de `scripts/quality/check-jacoco.py` | `global` ; `public-api` (`com/minos/api/`, 0,60 / 0,35) pour l'API ; aucune portée ne nomme les classes Git |
| Gates littéraux | `grep` dans `scripts/` : `check-mne.py:50`, `:116-117` lit `LocalMinosMultiRepositoryApi.java` (`ownsApplication`, `this(openApplication(home), true)`, `application.close()`, faits gardés) ; `check-m21-parity.py:55`, `:79` lit `MinosCliRunner.java` (`new IdeIntelligenceCommand(app)`, inchangé) ; `check-module-boundaries.py` (A11) et son auto-test ; `ModuleArchitectureTest` |
| Bases SpotBugs | `minos-api.xml` (entrées de `LocalMinosApi` à numéros de ligne) et `minos-cli.xml` : rejouer le profil `audit-spotbugs` et réenregistrer les lignes déplacées ; aucune entrée ajoutée à `minos-application.xml` (une alerte sur les records se corrige dans le code) |
| Langue des commentaires | nouveaux fichiers en anglais ; maximums de `scripts/quality/comment-language.json` inchangés ou abaissés |
| Documentation | ADR 0057 (mise en œuvre, point 6) ; ADR 0058 (mise en œuvre, « Point 3, lot 4 résorbé ») ; `SUIVI.md` (ligne du lot 4) ; arc42 relu (aucune mention de `GitIntelligence` par une surface attendue) |

## Risks / Trade-offs

- **Copie des records Git** → test de conversion champ par champ ; une évolution additive du port demande une ligne dans la conversion, ce que la compilation ne signale pas : le test de conversion compare tous les composants par réflexion sur les records.
- **Sortie modifiée** → goldens rejoués, aucun régénéré.
- **Faux retrait par `var` ou chaînage** → A11 compte les accesseurs ; aucune surface n'appelle `gitIntelligence()`.

## Recouvrements avec les changements actifs

| Changement | Fichiers communs | Effet |
|---|---|---|
| lot 3 (contrôle hébergé) | `MinosCli.java`, `MinosCliRunner.java`, `LocalMinosApi.java`, tables A11, `SUIVI.md`, ADR 0058 | lignes disjointes ; le second fusionné rebase |
| lot 5 (export NEXUS) | `MinosCliRunner.java:116-117`, tables A11, `SUIVI.md`, ADR 0058 | idem |
| `descendre-le-noyau-transverse` (lot 6) | base de cette branche | rebase sur `develop` après la fusion de #566 |

## Migration Plan

Une PR vers `develop`, branche `refactor/faire-consommer-git-et-les-resultats-de-requete`. Retour arrière : revert de la PR, sans effet sur les données.
