# Proposal

## Why

Le constat **AUD-ARC-01** (audit du 10 octobre 2026) relève que les surfaces utilisent des types de `minos-engine` au lieu de passer par `minos-application` (ADR 0058 § 3). La règle A11 tient cette dette : 33 types dans le garde Python et 28 dans la table bytecode, au HEAD `50134955` (lot 6 implémenté, PR #566).

Le lot 4, **Git et résultats de requête**, porte sur trois usages :

- la CLI et l'API Java appellent directement le port du moteur `com.minos.git.GitIntelligence` ;
- l'API Java construit sa requête de symboles à partir de deux accesseurs de `MinosApplication`, `projectRegistry()` et `snapshotStore()`, qui comptent comme des usages de `ProjectRegistry` et de `CodeKnowledgeSnapshotStore` ;
- la CLI et l'API nomment `SymbolResult`, `UsageResult` et `RelationshipResult`, que rend le port applicatif `ProjectSymbolQuery`. La décision D4 = a du lot 1 (propriétaire, 2026-10-22) les reconnaît comme **modèles de lecture publiés** et prévoit l'amendement de l'ADR 0057 § 6 dans ce changement.

## What Changes

- **Requête applicative d'activité Git** : `ProjectGitQuery` (interface) et `LocalProjectGitQuery` (implémentation sur le port du moteur), dans `com.minos.application`. Elle rend l'inspection et l'activité d'un dépôt en records applicatifs, avec les mêmes champs, les mêmes valeurs, les mêmes refus et les mêmes exceptions que le port.
- **La CLI et l'API la consomment** : `GitActivityCommand`, `MinosCli`, `MinosCliRunner` et `LocalMinosMultiRepositoryApi` ne nomment plus `GitIntelligence` et n'appellent plus `gitIntelligence()`.
- **La requête de symboles de l'API passe par l'application** : `LocalMinosApi` construit `new LocalProjectSymbolQuery(app)`, qui lit `projectQueryService()` ; elle n'appelle plus `projectRegistry()` ni `snapshotStore()`.
- **Résultats de requête reconnus** : l'ADR 0057 § 6 dit que les records de lecture rendus par un port applicatif sont des modèles de lecture publiés aux surfaces. Les entrées `query.*` des deux tables A11 portent cette raison écrite.
- **Cliquets A11** :
  - garde Python : 33 → **29** types (API 15 → 12, CLI 11 → 10), en retirant `git.GitIntelligence` (API, CLI), `registry.ProjectRegistry` et `store.CodeKnowledgeSnapshotStore` (API) ;
  - table bytecode : 28 → **26** types (API 13 → 12, CLI 8 → 7), en retirant `git.GitIntelligence` (API, CLI).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : trois exigences sont ajoutées (activité Git par requête applicative, requête de symboles de l'API par l'application, résultats de requête reconnus comme modèles de lecture publiés).

## Hors périmètre

- Les lots 3 (contrôle hébergé, entrées `hosted.*`) et 5 (export NEXUS, entrées de `minos-nexus`, et `registry.ProjectRegistry` et `store.CodeKnowledgeSnapshotStore` de la CLI, passés par `MinosCliRunner` à `NexusExportService`).
- Les entrées gardées par le lot 6 : `registry.ProjectPathMappings` et `runtime.MinosVersion`.
- Déplacer `SymbolResult`, `UsageResult` ou `RelationshipResult` hors du moteur (question Q1 du design).
- Retirer un accesseur de `MinosApplication`, modifier le port `GitIntelligence` ou son adaptateur JGit.
- Tout changement de sortie, de message, de code de sortie ou de signature publique.

## Impact

- **Modules** : `minos-application` reçoit 2 types et leurs tests ; `minos-cli` et `minos-api` changent de collaborateur. Aucun POM ni arête de module ne change. `minos-engine` et `minos-integration-git` ne changent pas.
- **Surfaces publiques** : aucune rupture. La sortie de `minos git-activity` est inchangée (golden `cli-git.golden`). Les valeurs de `MinosMultiRepositoryApi.inspectGit` et `analyzeGitActivity` sont inchangées (`LocalMinosMultiRepositoryApiIntegrationTest`). Le MCP n'expose pas Git et n'est pas touché.
- **ADR** : amendement de l'ADR 0057 § 6 (modèles de lecture publiés) ; mise en œuvre de l'ADR 0058 § 3 (lot 4). Aucun nouvel ADR.
- **Gates** :
  - `check-module-boundaries.py` (A11, A8, A15) et son auto-test ;
  - `ModuleArchitectureTest` (table bytecode) ;
  - `check-comment-language.py` (nouveaux fichiers en anglais) ;
  - `check-mne.py` (lit `LocalMinosMultiRepositoryApi.java`, faits gardés) ;
  - `check-jacoco.py` (portées `global` et `public-api`) ;
  - bases SpotBugs `minos-api.xml` et `minos-cli.xml` (numéros de ligne), `minos-application.xml` (aucune entrée ajoutée).
- **Plateformes** : aucun comportement propre au système. Le dépôt Git analysé passe toujours par `GitIntelligenceService` et son contrôle du propriétaire.
- **Constat** : AUD-ARC-01, lot 4.
