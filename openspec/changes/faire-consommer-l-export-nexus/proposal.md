# Proposal

## Why

Le constat **AUD-ARC-01** (audit du 10 octobre 2026) relève que les surfaces utilisent des types de `minos-engine` au lieu de passer par `minos-application` (ADR 0057 § 6, ADR 0058 § 3). La règle A11 tient cette dette : 33 types dans le garde Python et 28 dans la table bytecode, au HEAD `50134955` (sommet du lot 6, branche `refactor/descendre-le-noyau-transverse`).

Le lot 5, l'**export NEXUS**, porte sur `NexusExportService` (`minos-nexus`). Ce service lit lui-même le registre et le magasin de snapshots du moteur, sélectionne les symboles et les relations, et résout les chemins de fichiers. La CLI lui passe `opened.projectRegistry()` et `opened.snapshotStore()`. Le plan du stockage hexagonal (SH-09) demande que les données d'export soient produites par un cas d'usage et que la traduction vers le contrat reste dans l'adaptateur. Son prérequis, le port de lecture des snapshots (SH-04), est livré.

## What Changes

- **Cas d'usage applicatif** `KnowledgeExportUseCase` (`com.minos.application.export`) : il résout la racine enregistrée, lit le snapshot actif, applique les bornes de sélection, résout les chemins et rend un `KnowledgeExport` (projet, symboles et relations retenus avec leur chemin relatif, limitations), en types JDK, domaine et application. Il porte la logique aujourd'hui dans `NexusExportService`, à comportement identique (mêmes bornes, mêmes limitations dans le même ordre, mêmes messages de refus).
- **`NexusExportService` devient l'adaptateur du contrat** : il obtient le cas d'usage par `KnowledgeExportUseCase.of(MinosApplication)` et ne fait que traduire `KnowledgeExport` en `NexusExportContract` (noms d'énumérations, repli `qualifiedName`, origine, preuves). Son constructeur prend `MinosApplication` (décision Q1). Le rendu JSON reste dans `NexusExportCommand` (CLI).
- **CLI** : `MinosCliRunner` passe l'application ouverte à `NexusExportService` et n'appelle plus `projectRegistry()` ni `snapshotStore()`.
- **Arête retirée** : `minos-nexus` n'utilise plus `minos-engine` ; la dépendance disparaît du POM, de `ALLOWED_DEPENDENCIES`, de `ModuleArchitectureTest.ALLOWED` et du document généré (décision Q2).
- **Cliquets A11** :
  - garde Python : 33 → **26** types (NEXUS : les 5 entrées ; CLI : `registry.ProjectRegistry` et `store.CodeKnowledgeSnapshotStore`, usages par accesseur) ;
  - table bytecode : 28 → **23** types (NEXUS : les 5 entrées).
- **Caractérisation** : un golden `nexus-export.golden` fige la sortie JSON de `minos nexus-export` avant la refonte et la garde ensuite (décision Q3).
- **Tests déplacés avec leurs classes** : la sélection bornée et les refus passent dans `minos-application` ; `minos-nexus` garde les tests du contrat et gagne un test de traduction sur une application réelle.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : deux exigences ajoutées (l'export NEXUS passe par un cas d'usage applicatif ; `minos-nexus` ne dépend plus du moteur).

## Hors périmètre

- Les entrées équivalentes de l'API (`LocalMinosApi` passe `app.projectRegistry()` et `app.snapshotStore()` à `LocalProjectSymbolQuery`) : lot 4.
- Les lots 3 (contrôle hébergé) et 4 (Git et résultats de requête) d'AUD-ARC-01.
- Toute modification du contrat NEXUS (version, champs, ordre, limitations), de la commande `nexus-export`, de ses codes de sortie ou de ses messages ; `NexusSemanticSignalService`.
- Le port `CodeKnowledgeReader` : il ne sait pas énumérer un snapshot et n'est pas étendu (voir le design, D1).
- Le reste de SH-09 (façades des autres capacités, artefact de contrats).

## Impact

- **Modules** : `minos-application` reçoit le package `com.minos.application.export` (4 types) et ses tests ; `minos-nexus` perd la logique de sélection et sa dépendance au moteur ; `minos-cli` change une ligne de câblage ; `minos-app` adapte `NexusExportIntegrationTest` et gagne un golden.
- **Surfaces publiques** :
  - format d'export NEXUS, commande `nexus-export`, API `com.minos.api`, MCP : inchangés ;
  - classe `NexusExportService` : constructeur `(ProjectRegistry, CodeKnowledgeSnapshotStore)` remplacé par `(MinosApplication)` ; rupture de source et binaire pour un appelant direct, documentée dans `docs/user/java-api.md` § Ruptures (Q1). Les constantes publiques `MAX_*` restent.
- **ADR** : aucun nouvel ADR. Mise en œuvre de l'ADR 0058 § 3 (« Point 3, lot 5 résorbé », arête retirée).
- **Gates** :
  - `check-module-boundaries.py` (A2 `ALLOWED_DEPENDENCIES`, A11, A17, `--write-doc`) et `test_check_module_boundaries.py` ;
  - `ModuleArchitectureTest` (`ALLOWED`, table bytecode, règle stricte) ;
  - `scripts/remediation/check-mne.py` (MNE-12 lit la sélection pondérée dans le cas d'usage) ;
  - `check-jacoco.py` (portée `nexus-export`) ; `check-comment-language.py` (maximum de jalons de `minos-nexus`) ;
  - base SpotBugs (`minos-nexus.xml` inchangée ; aucune entrée ajoutée à `minos-application.xml`).
- **Plateformes** : aucun comportement propre au système ; le parcours de l'arbre et la résolution canonique des chemins (liens symboliques) sont déplacés sans changement.
- **Constat** : AUD-ARC-01, lot 5.
