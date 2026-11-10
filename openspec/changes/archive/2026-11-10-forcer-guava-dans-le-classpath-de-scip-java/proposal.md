# Proposal

## Why

Le verrou du classpath de scip-java 0.13.2 (`scip-java-classpath.lock.json`, 70 jars) épingle `guava-33.6.0-jre.jar`, touché par l'avis `GHSA-xxph-c9ww-hj94`, corrigé en guava 33.7.2. Le risque est accepté jusqu'au 2027-01-28 (registre des risques, **R-29**, constat d'audit AUD-DEP-03) et dérogé dans `scripts/quality/embedded-tools-advisory-waivers.json`. Le registre dit qu'un jar du verrou « ne se remplace pas seul » et que l'avis se corrige en montant scip-java (`docs/developer/supply-chain.md`, « Traiter une alerte », point 3). Aucune version de scip-java n'embarque guava ≥ 33.7.2. Cette interdiction est une règle du dépôt, pas une limite de l'outil : Coursier sait forcer la version d'un module (`--force-version organisation:nom:version`).

Le propriétaire veut corriger l'avis maintenant, avant l'échéance de la dérogation, par un forçage nominatif, justifié, vérifié par somme et prouvé par une indexation Java réelle. Il veut aussi préparer la fermeture de **R-30** (minimatch 3.1.5, `GHSA-g3hv-75wg-m249`) par une demande de correction de l'avis auprès de GitHub. Cette demande se rédige ici et le propriétaire la soumet lui-même.

## What Changes

- Une décision d'architecture (nouvel ADR, voir design D1) autorise le forçage nominatif d'une dépendance transitive du verrou de scip-java pour corriger un avis, à trois conditions : il est listé avec sa raison, il est vérifié par somme SHA-256, et une indexation Java réelle le prouve.
- Le verrou porte une liste `forcedVersions` (module, version, avis, raison). Il est régénéré avec `com.google.guava:guava:33.7.2-jre` forcé et toutes ses sommes recalculées.
- Le script de construction des outils embarqués (`scripts/release/build-embedded-tools.py`) applique les versions forcées à sa résolution Coursier. Il gagne l'option `--update-classpath-lock` : son message d'erreur la cite déjà, mais elle n'existe pas au HEAD.
- L'installation de scip-java sur le poste de l'utilisateur (`coursier fetch --classpath`) applique les mêmes versions forcées. Sans cela, elle résoudrait guava 33.6.0 et le verrou la refuserait.
- `scripts/quality/check-tools-manifest.py` refuse une entrée forcée sans raison, en double, ou dont le jar n'est pas dans le verrou.
- La dérogation `GHSA-xxph-c9ww-hj94` est retirée de `embedded-tools-advisory-waivers.json`.
- R-30 : le design contient le texte d'une demande de correction de l'avis `GHSA-g3hv-75wg-m249` pour `github/advisory-database`. Une tâche manuelle la confie au propriétaire.
- Aucune **RUPTURE** : ni CLI, ni API Java publique, ni MCP, ni sortie JSON ne changent.

## Capabilities

### New Capabilities

Aucune : le comportement relève du verrou du classpath de scip-java, déjà spécifié dans `installation-des-providers`.

### Modified Capabilities

- `installation-des-providers` : trois exigences ajoutées. Le forçage est nominatif et justifié dans le verrou, et contrôlé par le gate du catalogue. L'installation applique les versions forcées à la résolution. Le script de construction régénère le verrou avec ces versions.

Cette capacité est retenue plutôt que `chaine-de-livraison` : celle-ci couvre les notices, la reproductibilité du jar ombré, la provenance et la publication des releases, pas le verrou. `installation-des-providers` porte déjà « Le classpath de scip-java est vérifié jar par jar avant tout usage » et « Le verrou est tenu par le catalogue et contrôlé en CI ». La veille (`audit-qualite-code` › « Le catalogue des outils embarqués a une veille planifiée à couverture déclarée ») est inchangée : retirer une dérogation est une donnée, pas une exigence.

## Hors périmètre

- Monter scip-java, ou forcer tout autre jar que guava. Des jars voisins pourraient changer par la résolution elle-même : le design le traite (D4).
- R-28 (signature) et tout autre constat. `ETAT-2026-10-13.md` et `findings.json` ne sont pas modifiés.
- Soumettre la demande de correction de R-30 : seul le propriétaire le fait.
- Modifier les fichiers partagés `docs/architecture/risks/register.md`, `docs/project/audits/2026-10-10/SUIVI.md` et `docs/developer/supply-chain.md`. Leur texte est proposé dans le design.
- Rendre scip-java exécutable sous Windows natif : il reste `BLOCKED` (SEC-0074).
- Le repli du lanceur Windows (`scip-java-windows-runner.ps1`) et le lanceur autonome de l'image Docker (`cs bootstrap`, `docker/Dockerfile.mcp.release:268`) restent sans forçage : décision du propriétaire (design D6), dite dans l'ADR.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le verrou compte 70 jars et épingle guava 33.6.0-jre | [Existant vérifié] | `scip-java-classpath.lock.json:5-74`, `:16` |
| L'installation résout par `coursier fetch --classpath <coordonnée>` sans forçage | [Existant vérifié] | `ScipJavaClasspathInstallation.java:41-43` |
| Aucun script ne génère le verrou : l'option `--update-classpath-lock` est citée mais absente | [Existant vérifié] | `build-embedded-tools.py:195` contre `:343-347` |
| Coursier accepte `--force-version organisation:nom:version` (alias `-V`) | [Existant vérifié] | source Coursier, `ResolutionOptions.scala` (branche `main`) ; à prouver avec le lanceur épinglé (tâche 2.1) |
| guava 33.7.2-jre existe sur Maven Central (SHA-256 `b530942257fb…606c`, 3 057 659 octets) | [Existant vérifié] | téléchargement et calcul local, 2026-11-10 |
| Le code décrit par GHSA-g3hv-75wg-m249 est absent de minimatch 3.1.5 | [Existant vérifié] | archive du registre, sha512 égal au verrou (design, R-30) |
| Le forçage se déclare dans le verrou, pas dans `embedded-tools.json` | [Décision] | design D2 (propriétaire, 2026-11-10) |
| Nouvel ADR plutôt qu'amendement de BE-0065 | [Décision] | design D1 |
| scip-java 0.13.2 fonctionne avec guava 33.7.2 | [Question] | bloquante pour la clôture ; point d'arrêt en tâche 4 |

## Impact

- **Modules du reactor** : `minos-provider-scip` (verrou, installation de scip-java, tests). Aucun autre module.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune. Le format du verrou, ressource interne du jar, gagne un champ facultatif (additif).
- **ADR** : à écrire, un nouvel ADR `SEC-0080` (numéro réservé par l'orchestrateur). Il complète BE-0065 § 12, qui reçoit un renvoi.
- **Gates à rejouer** : `check-tools-manifest.py` et son test, `scripts/release/test_build_embedded_tools.py`, `check-embedded-tools-advisories.py --self-test`, `check-ci-wiring.py`, `check-comment-language.py`, `python .claude/scripts/run_gates.py --fast`. Pas de renommage, donc aucun gate littéral cassé (vérifié : aucun `check-*.py` ne cite la commande de fetch).
- **Plateformes** : Linux (installation réelle et indexation sous bubblewrap) et Windows (construction du paquet, avec le lanceur Coursier `windows-x64` embarqué). Sous Windows natif, scip-java reste `BLOCKED`.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/installation-des-providers.md`, l'ADR, `docs/developer/supply-chain.md` (texte proposé), le registre des risques (texte proposé).

## Questions ouvertes

- Non bloquante pour la spécification, bloquante pour la clôture : scip-java 0.13.2 indexe-t-il avec guava 33.7.2 ? Le point d'arrêt est prévu (tâche 4.3).
- Les décisions du propriétaire sont listées dans le design (« Questions ouvertes »).
