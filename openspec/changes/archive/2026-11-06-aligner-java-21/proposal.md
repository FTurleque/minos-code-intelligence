# Proposal

## Why

MORPHEUS lance MINOS comme pair MCP en STDIO avec un runtime Java 21 embarqué. Le jar de MINOS, compilé pour Java 25 (classes version 69), n'y démarre pas : l'intégration ne peut pas s'appuyer sur MINOS tant que le produit exige Java 25.

Une mesure jetable du 2026-11-06 (`maven.compiler.release` à 21, aucune autre modification de la toolchain) a montré que la baseline Java 25 n'est pas une contrainte du code : le reactor compile sous Java 21 après le remplacement de 62 variables sans nom `_` (JEP 456, Java 22), ses 4127 tests passent, et le jar ombré répond sous un Java 21 réel à `initialize`, `tools/list` et `minos_index_status` comme sous Java 25. Le propriétaire a décidé d'aligner la baseline du produit sur Java 21.

## What Changes

- **Baseline** : `maven.compiler.release` passe à 21 et Maven Enforcer à `[21,22)` ; 62 variables sans nom `_` (32 fichiers Java, code et tests) deviennent `ignored`.
- **CI et livraison** : les 16 étapes `java-version: '25'` des workflows passent à `'21'` ; les deux qualifications de montée A → B installent Java 25 pour construire le candidat A (`main`, encore en Java 25) ; les images `eclipse-temurin` des deux Dockerfiles repartent de `21.0.12.1_1` (tag et digest relevés) ; les scripts Windows, de release et de qualification qui exigent un JDK 25 exigent un JDK 21.
- **Gates** : `check-toolchain-baseline.py` (version précédente `25`, liste fermée du plugin sans objet quand le produit est en Java 21), `check-audit-remediation-v2.py` et `check-current-docs.py` suivent ; l'auto-test du gate couvre un produit en Java 21.
- **Catalogue SCIP** : le prérequis de `scip-java` annonce « Java 21 JDK » (`ScipIndexerCatalog`, golden `cli-json.golden`).
- **ADR** : `GEN-0077` (Accepted) remplace `GEN-0067` (archivé) ; la règle Archgate compagnon change de nom et de référence.
- **Documentation** : `TOOLCHAIN_POLICY.md` (Java 21, support jusqu'en 2029-12), `AGENTS.md`, `openspec/config.yaml`, `STATUS.md`, `ROADMAP.md`, pages `docs/user/` et `docs/developer/`, arc42, skills `.claude/`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `socle-d-execution` : l'exigence « Le produit se construit et s'exécute sur un JDK LTS maintenu » passe de Java 25 à Java 21 ; un scénario ajoute l'exécution du jar sur le runtime Java 21 d'un pair MCP.

## Impact

- Modules du reactor : les 14 (propriété `maven.compiler.release` du POM racine) ; code modifié dans `minos-application`, `minos-cli`, `minos-domain`, `minos-engine`, `minos-integration-git`, `minos-provider-scip`, `minos-runtime-local`, `minos-storage`, `minos-nexus` (commentaire) ; aucune dépendance Maven ajoutée.
- Surfaces publiques : CLI, API Java et MCP inchangées en contrat ; la chaîne `runtimeRequirements` de `scip-java` dans `providers` (JSON de la CLI) change de « Java 25 JDK » à « Java 21 JDK ». IntelliJ et NEXUS : inchangés.
- **ADR** : nouvel ADR `GEN-0077` qui remplace `GEN-0067`.

## Hors périmètre

- **Qualification d'une indexation réelle sous Java 21** (providers, bac à sable Windows AppContainer et Linux bubblewrap) : la CI n'exécute aucun provider ; la capacité n'est pas requalifiée par ce changement et n'est pas annoncée comme telle.
- **Qualification des images Docker de release et du runtime de l'installateur Windows** sous Java 21 : jobs `docker-release-validation`, `windows-installer` et `release-promotion-gate`, déclenchés hors de ce changement.
- **Promotion `develop` → `main`** et création d'une release : décision du propriétaire.
- **Retour de la construction sur Java 25** : un changement futur de baseline suit `TOOLCHAIN_POLICY.md` ; ce changement n'en prépare aucun.
- **Fixtures Java 24** (`fixtures/java/java-24-smoke`) et relevés figés (`docs/project/history/`, archives) : conservés tels quels.
