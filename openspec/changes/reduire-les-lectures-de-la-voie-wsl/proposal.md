# Proposal

## Why

Deux constats de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, sprint 6) portent sur les
mêmes fichiers de la voie WSL2 (ADR SEC-0079, capacité `indexation-java-via-wsl`) :

- **AUD-PERF-07** (Faible, Performance) : l'exécuteur de la voie hache tout l'arbre source à chaque cible
  (`WslIndexerExecutor.java:73`), avec un service d'empreintes qui lui est propre
  (`LocalWslIndexingRoute.java:138`), alors que l'orchestration tient déjà l'empreinte du projet
  (`IndexingRunExecutor.java:332-335`, AUD-PERF-04 de l'audit du 2026-10-10). Le gain d'AUD-PERF-04 est perdu sur
  cette voie et la perte croît avec le nombre de cibles.
- **AUD-PERF-08** (Faible, Performance) : entre l'appel de `accept` et le retour à l'orchestration, l'artefact
  SCIP (plafond de 512 Mio, `IndexArtifactLimits.java:7`) est lu cinq fois sur le chemin nominal, six quand l'entrée
  de cache existe déjà à l'identique (`DistributedArtifactBundleStore.java:184`, `:191`, `:206`, `:546` ;
  `WslIndexerExecutor.java:136-147`, `:152`). Une lecture de plus précède `accept` : celle du manifeste du paquet
  reçu (`WslIndexWorker.java:172`), qui parcourt toutes les entrées du zip
  (`DistributedArtifactBundleStore.java:513-523`) ; elle reste hors périmètre.

Les deux se corrigent ensemble : ils modifient `WslIndexerExecutor` et son magasin, et leur preuve vit dans la même
classe de test, sous le scope JaCoCo nominatif de la voie que crée le sprint 1 (AUD-TST-01).

## What Changes

- La requête d'exécution d'une cible (`IndexingExecutionRequest`) porte, quand l'orchestration la connaît, l'empreinte
  du projet enregistré ; la voie WSL la réutilise au lieu de hacher l'arbre. Sans empreinte fournie, la voie capture
  l'empreinte **une seule fois par run**, quel que soit le nombre de cibles. Une empreinte fournie mal formée est
  refusée avant tout lancement.
- L'acceptation d'un paquet calcule en **une seule lecture** l'empreinte du paquet, la taille et l'empreinte de
  l'artefact extrait ; la copie sous `runs/<runId>/` calcule l'empreinte **pendant** la copie. Chemin nominal : deux
  lectures de l'artefact entre `accept` et le retour, au lieu de cinq (trois au lieu de six en comptant la lecture
  du manifeste qui précède).
- Dans la distribution, la fabrication du paquet vérifie l'empreinte de l'artefact pendant son écriture dans le paquet
  (deux lectures au lieu de trois).
- **Aucune vérification d'intégrité n'est retirée** : taille et SHA-256 de l'artefact extrait contre le manifeste,
  SHA-256 de la copie contre le manifeste, vérification d'une entrée de cache réutilisée, provenance v3. Seul change
  le moment où l'empreinte est calculée.
- ADR SEC-0079 : section « Mise en œuvre » complétée (tâche), la décision est inchangée.

Aucune **RUPTURE** : ni option, ni sortie, ni format de manifeste, ni format de paquet ne changent.

## Capabilities

### New Capabilities

Aucune : le comportement relève de la capacité existante `indexation-java-via-wsl`.

### Modified Capabilities

- `indexation-java-via-wsl` : exigences **ajoutées** — réutilisation de l'empreinte du projet par la voie, nombre de
  lectures de l'artefact entre l'acceptation du paquet et son retour à l'orchestration, vérification du paquet pendant sa fabrication. L'exigence
  existante « L'artefact reçu de WSL est vérifié avant tout import » n'est ni modifiée ni affaiblie.

## Hors périmètre

- Les empreintes de l'orchestration elle-même (référence avant et après, points de contrôle) : AUD-PERF-04, clos.
- La lecture du manifeste du paquet reçu avant son acceptation (`WslIndexWorker.java:172`) : elle détecte une
  entrée de manifeste dupliquée avant toute acceptation et fournit le manifeste que l'exécuteur compare à celui du
  magasin (`WslIndexerExecutor.java:80`) ; la retirer changerait le contrat de `WorkerResponse`.
- La relecture de l'artefact par le point de contrôle (`ExecutionCheckpoints.java:49`) et par l'import : commune à
  tous les providers, hors de la voie WSL ; relevée comme écart dans `design.md`, non traitée.
- L'indexation distribuée distante (formats v1 et v2, `DistributedIndexerExecutor`) : elle bénéficie de l'acceptation
  en une lecture, son comportement observable (dont `bundleSha256` et `cacheHit`) est inchangé.
- La création du scope JaCoCo nominatif de la voie : sprint 1 (AUD-TST-01, autre changement).
- Tout autre constat du sprint 6 (changements distincts).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| L'exécuteur WSL hache l'arbre à chaque cible | [Existant vérifié] | `minos-runtime-local/src/main/java/com/minos/runtime/local/wsl/WslIndexerExecutor.java:73` ; appel par cible `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:307-308`, `:336` |
| Son service d'empreintes n'est pas celui de l'orchestration | [Existant vérifié] | `minos-bootstrap/src/main/java/com/minos/bootstrap/LocalWslIndexingRoute.java:137-138` |
| L'empreinte du projet n'est pas recalculée dans la distribution : elle est recopiée dans le manifeste | [Existant vérifié] | `minos-runtime-local/src/main/java/com/minos/runtime/local/wsl/WslLinuxWorker.java:167-171` |
| Entre `accept` et le retour : cinq lectures de l'artefact sur le chemin nominal, six sur entrée de cache identique | [Existant vérifié] | `DistributedArtifactBundleStore.java:184`, `:191`, `:206`, `:546` ; `WslIndexerExecutor.java:136-147`, `:152` |
| Une lecture du paquet précède `accept` | [Existant vérifié] | `minos-runtime-local/src/main/java/com/minos/runtime/local/wsl/WslIndexWorker.java:172` ; `DistributedArtifactBundleStore.java:507-529` |
| La vérification de l'entrée de cache porte sur un autre fichier que l'artefact extrait | [Existant vérifié] | `DistributedArtifactBundleStore.java:203-207`, `:531-551` |
| Comparer au manifeste l'empreinte des octets écrits (extraction, copie) au lieu de relire le fichier écrit | [Proposition] | `design.md`, D3 (décision du propriétaire attendue) |
| Durée gagnée | [Question] non bloquante | non mesurée par l'audit ni ici ; stratégie de mesure dans `design.md` |

## Impact

- **Modules du reactor** : `minos-engine` (`IndexingRuntimePorts.IndexingExecutionRequest`, `IndexingRunExecutor`),
  `minos-runtime-local` (`wsl/WslIndexerExecutor`, `wsl/WslLinuxWorker`, `distributed/DistributedArtifactBundleStore`),
  `minos-bootstrap` (`LocalWslIndexingRoute`, câblage seul).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune ; le port d'exécution du moteur reçoit un
  composant facultatif, additif.
- **ADR** : SEC-0079 complété dans sa section « Mise en œuvre » (pas d'ADR nouveau, pas d'amendement de la décision).
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py` (A10, A11),
  `scripts/architecture/check-private-io.py` (entrée `DistributedArtifactBundleStore.java` de
  `scripts/architecture/private-io-allowlist.json`), `scripts/quality/check-remote-distributed-consistency.py`,
  `scripts/remediation/check-post-mne.py`, `scripts/quality/check-comment-language.py` ; JaCoCo : scope nominatif de
  la voie WSL (créé par AUD-TST-01) et `m25-remote-distributed-indexing`.
- **Plateformes** : la voie n'est offerte que sous Windows ; le magasin et la fabrication du paquet s'exécutent aussi
  sous Linux (distribution WSL, CI Ubuntu). Les tests sont indépendants de la plateforme (worker factice).
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/indexation-java-via-wsl.md`,
  ADR SEC-0079 (« Mise en œuvre »).

## Questions ouvertes

Aucune bloquante. D3 (empreinte des octets écrits, à l'extraction et à la copie) attend la décision du propriétaire, avec recommandation dans
`design.md` ; elle ne change ni le découpage ni les autres exigences.
