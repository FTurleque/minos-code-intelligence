# État courant — MINOS

Dernière mise à jour : **24 juillet 2026**

Ce document est le tableau de bord opérationnel compact de MINOS. Les preuves détaillées restent dans les documents de jalon, décisions, issues et PR.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET LIVRÉ
M1 — Découverte et orchestration    TERMINÉ ET LIVRÉ
M2 — Intelligence des symboles      TERMINÉ ET LIVRÉ
M3 — Intelligence des relations     TERMINÉ ET LIVRÉ
M4 — Recherche et contexte compact  TERMINÉ ET LIVRÉ
M5 — Tests liés et dérivations      TERMINÉ ET LIVRÉ
M6 — Intelligence d’architecture    TERMINÉ, VALIDÉ ET LIVRÉ
M7 — Indexation incrémentale        TERMINÉ, VALIDÉ ET LIVRÉ
M8 — Analyse d’impact               TERMINÉ, VALIDÉ ET LIVRÉ
M9 — CLI stabilisée                 TERMINÉ, VALIDÉ ET LIVRÉ
M10 — Serveur MCP                   TERMINÉ, VALIDÉ ET LIVRÉ
M11 — API publique                  TERMINÉ, VALIDÉ ET LIVRÉ
M12 — Multi-dépôts + Git            TERMINÉ, VALIDÉ ET LIVRÉ
M13 — Intégration NEXUS             IMPLÉMENTÉ — VALIDATIONS FINALES EN ATTENTE
```

GitHub Actions reste hors de la porte locale MINOS ; l’anomalie historique est suivie séparément dans #5.

## Portes acquises

```text
M2    86 tests   BUILD SUCCESS
M3   115 tests   BUILD SUCCESS
M4   131 tests   BUILD SUCCESS
M5   140 tests   BUILD SUCCESS
M6   162 tests   BUILD SUCCESS
M7   196 tests   BUILD SUCCESS
M8   203 tests   BUILD SUCCESS
M9   207 tests   BUILD SUCCESS
M10  210 tests   BUILD SUCCESS
M11  214 tests   BUILD SUCCESS
M12  221 tests   BUILD SUCCESS
```

## M11 — API publique — LIVRÉ

Issue #33 clôturée. PR #34 fusionnée.

```text
head validé   fae552e8e6f2aa66c327fb80485f5bad448d7520
merge         3780785f167cf373dfe0e9cf34f3c3862e87b868
sources       154 main / 79 test
tests         214/214 PASS
```

Contrat :

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

Replay :

```text
M11 public API: version=1, project=<uuid>, snapshot=scip-7f41649a3cdad442a3235c0a, modules=3, impact=2, tests=1
```

## M12 — Multi-dépôts et intelligence Git — LIVRÉ

Issue #35 clôturée. PR #36 fusionnée.

```text
head validé   6c771909e0b97b49fbd8e49090522d8a6c0b53aa
merge         3bc6cc364b6d7d651c1c9ab3a93ecac28ce02e86
sources       158 main / 83 test
tests         221/221 PASS
```

Acquis : workspaces publics, intelligence Git via JGit, activité bornée, zones, résolution cross-repository exacte et contrat public M12 additif.

Replay :

```text
M12 multi-repo Git: workspace=<uuid>, projects=1, git-commits=1, files=1, exact-cross-repo=0
```

## M13 — Intégration NEXUS — IMPLÉMENTÉ

Suivi MINOS : issue #37 / PR Draft #38.

Compagnon NEXUS : `FTurleque/nexus-context-engine` issue #11 / PR Draft #12.

### Porte produit

> NEXUS peut-il consommer la Code Intelligence normalisée de MINOS par un contrat local versionné, sans dépendance Maven croisée, sans déplacer le ranking/sélection de contexte dans MINOS et sans rendre l’un des deux moteurs obligatoire pour l’autre ?

### Architecture

```text
MINOS Java 24
  NexusExportService
  nexus-export --root <project>
        |
        | JSON contract v1 / stdout
        v
NEXUS Java 21
  MinosCodeIndexImporter opt-in
        |
        v
  IndexRepository -> SearchService -> ranking -> ContextBuilder
```

Le transport inter-processus évite un couplage Java 21/24 et tout lien Maven entre les deux dépôts.

### MINOS livré dans M13

```text
NexusExportContract.CONTRACT_VERSION = 1
NexusExportContract.PRODUCER = MINOS
NexusExportService
NexusExportCommand
minos nexus-export --root <project-root>
```

L’export :

- ne lit que le snapshot actif ;
- est strictement read-only ;
- conserve origine, nature, confiance et preuves ;
- n’exporte que les symboles locaux rattachables à un fichier réel ;
- reconstruit les `fileId` SCIP stables via la même identité SHA-256 ;
- n’exporte que les relations symbol → symbol locales représentables dans le snapshot ;
- expose toutes les omissions/troncatures comme limitations.

### NEXUS compagnon

`MinosCodeIndexImporter` :

- désactivé par défaut ;
- exige `NEXUS_MINOS_JAR` + `NEXUS_MINOS_JAVA` pour activation ;
- lance MINOS avec Java 24 dans un processus local borné ;
- valide version, producteur et racine projet ;
- mappe uniquement les kinds/relations NEXUS ayant une équivalence explicite ;
- conserve `sourceProvider=minos` ;
- est appliqué avant SCIP direct ;
- purge les anciennes données MINOS lorsqu’il est désactivé ;
- ne modifie ni `SearchService`, ni le ranking, ni `DefaultContextBuilder`.

### Qualification ajoutée

MINOS :

```text
NexusExportContractTest
NexusExportIntegrationTest
```

NEXUS :

```text
MinosCodeIndexImporterTest
FakeMinosExportMain
MinosRealIntegrationTest   opt-in, vrai JAR MINOS
```

Replay MINOS attendu :

```text
M13 MINOS export: contract=1, project=<uuid>, snapshot=<snapshot>, symbols=<n>, relations=<n>
```

Replay réel inter-dépôt attendu :

```text
M13 MINOS->NEXUS: symbols=<n>, relations=<n>, nexus-symbols=<n>, search=<n>
```

Le replay doit prouver que `GreetingPort` entre dans l’index NEXUS avec `sourceProvider=minos` puis est retourné par une recherche NEXUS.

## Porte active M13

Trois preuves sont obligatoires :

1. `m13/nexus-integration` : `./mvnw clean verify` sous Java 24 ;
2. `integration/minos-code-intelligence` : validation NEXUS complète sous Java 21 ;
3. `MinosRealIntegrationTest` avec le shaded JAR issu du **head MINOS exact qualifié**.

Tout nouveau commit sur l’un des heads après sa validation invalide la preuve correspondante.

Verdict préparé :

> **OUI, via un contrat JSON local versionné et un importer NEXUS optionnel : MINOS reste la source de faits de Code Intelligence, tandis que NEXUS reste seul responsable du classement, de la sélection et du budget du contexte.**

Documents : `docs/m13/NEXUS_INTEGRATION.md`, `docs/m13/DECISION_M13.md`.

## Sources de vérité

- roadmap : `docs/ROADMAP.md` ;
- suivi M13 : issue #37 / PR #38 ;
- contrat M13 : `docs/m13/NEXUS_INTEGRATION.md` ;
- décision : `docs/m13/DECISION_M13.md` ;
- compagnon NEXUS : issue #11 / PR #12 / ADR-0044.
