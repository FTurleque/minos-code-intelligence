# Proposal

## Why

Constat **AUD-TST-01** (audit du 2026-11-15, sprint 1, sévérité moyenne). Quatre zones créées depuis le 10/10 ne sont mesurées que par le scope JaCoCo `global` : le module `minos-explorer` (9 classes, 1 291 lignes), la voie WSL2 (27 fichiers, 2 367 lignes), la projection 2D et `QueryViewMemoryBudget` (327 lignes). La marge du scope global (2 points sur l'ensemble du produit) absorbe la perte complète des tests de l'une de ces zones sans échec. Les sprints 3, 4, 5 et 6 du même audit modifient ces zones : sans plancher nominatif, leurs corrections peuvent faire reculer la couverture de la garde HTTP locale (`RequestGuard`, `SessionToken`, `ListeningPolicy`) ou du budget mémoire des vues sans signal.

## What Changes

- `scripts/quality/check-jacoco.py` reçoit trois scopes nominatifs : `explorer-surface`, `wsl-lane` et `semantic-projection`, chacun avec un plancher de lignes et de branches.
- `RequestGuard`, `SessionToken` et `ListeningPolicy` portent chacun un plancher de classe dans `explorer-surface`.
- `QueryViewMemoryBudget` entre dans le scope `storage-codec-and-state` avec un plancher de classe.
- Un plancher de scope est la mesure du rapport agrégé de la CI moins 2 points, par système quand les deux mesures diffèrent ; un plancher de classe est un nombre unique, la plus basse des deux mesures moins environ 5 points (règle de `docs/developer/quality-gates.md`) ; la mesure, le run et le commit sont écrits dans le commentaire du scope, comme pour les scopes existants.
- L'auto-test du gate (`--self-test`) reçoit un cas qui prouve que les quatre zones restent déclarées.
- L'artefact de validation de `pr-ci.yml` publie aussi `target/m21-quality/`, où le gate écrit `jacoco-gate.json`.
- `docs/developer/quality-gates.md` nomme les nouveaux scopes.

Aucun code de production, aucun test Java, aucune surface publique ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `controles-de-fusion` : trois exigences ajoutées (zones nouvelles sous scope nominatif, garde de la surface HTTP locale sous plancher de classe, budget mémoire des vues sous plancher de classe). Aucune exigence existante n'est modifiée.

## Hors périmètre

- Relever les planchers de classe déjà déclarés et jugés bas (`ProviderProcessEnvironment`, `ScipJavaProcessPlanFactory`) : AUD-TST-03, sprint 6.
- Écrire de nouveaux tests Java pour atteindre un plancher : les planchers suivent la mesure existante ; un test manquant est un constat à part.
- La couverture du front `app.js` (non mesuré par JaCoCo) : changement `executer-le-front-de-l-explorateur-en-test` (AUD-TST-02).
- La couverture du plugin IntelliJ (AUD-TST-08) et la mutation de `minos-explorer` (AUD-TST-10), sprint 6.
- Un test bout en bout de la voie WSL2 en CI (AUD-TST-11, non affecté à un sprint).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Aucun scope ne nomme l'explorateur, la voie WSL2, la projection ni le budget des vues | [Existant vérifié] | `scripts/quality/check-jacoco.py:13-300` (recherche de `explorer`, `wsl`, `Budget`, `projection` : 0 ligne de scope) |
| `minos-explorer` est dans le rapport agrégé | [Existant vérifié] | `pom.xml:36`, `minos-app/pom.xml:29` et `:72` (`report-aggregate`, `includeCurrentProject`) |
| Plancher de scope = mesure de la CI moins 2 points ; plancher de classe = mesure moins environ 5 points, nombre unique | [Décision] | `scripts/quality/check-jacoco.py:17-18`, `:649-650` ; `docs/developer/quality-gates.md:225` |
| Valeur de chaque plancher | [Question] | mesure à lire dans le rapport agrégé de la CI (tâche 1.1) ; aucune valeur n'est fixée ici |
| `SemanticCorpusQueryService` et `WslWorkerLaunch` entrent dans les scopes | [Proposition] | écarts E2 et E3 du design |

## Impact

- **Modules du reactor** : aucun code ; `scripts/quality/check-jacoco.py` lit le rapport agrégé de `minos-app`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun (un plancher de couverture est un réglage de gate, pas une décision d'architecture).
- **Gates à rejouer** : `python scripts/quality/check-jacoco.py --self-test`, `python scripts/quality/check-jacoco.py` sur un rapport agrégé de chaque système (CI), `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-vertical-decomposition-consistency.py` et les autres gates de cohérence qui lisent `check-jacoco.py`, `python scripts/docs/check-current-docs.py` et `python scripts/remediation/check-p0-p2.py` (ils y affirment des littéraux), `python scripts/quality/check-workflow-pins.py` (chemin d'artefact ajouté à `pr-ci.yml`), `python .claude/scripts/run_gates.py`.
- **Plateformes** : Windows et Linux. Les tests de la voie WSL2 sont en partie restreints à un système : planchers par système pour `wsl-lane`.
- **Fusion** : quatre changements du sprint 1 ajoutent des étapes au job `invariants` de `.github/workflows/pr-ci.yml` (`executer-le-front-de-l-explorateur-en-test`, `compter-comme-sautes-les-tests-propres-a-une-plateforme`, `doter-chaque-gate-d-un-auto-test`, `epingler-les-fins-de-ligne-des-scripts-posix`), et `borner-la-couverture-des-zones-nouvelles` y modifie l'artefact de validation : les fusionner en série, chaque PR remise à jour sur `main` avant la suivante.
- **Documentation durable et diagrammes** : `docs/developer/quality-gates.md` (section JaCoCo), `docs/architecture/diagrams/capacites/controles-de-fusion.md`.

## Questions ouvertes

Non bloquantes : valeur des planchers (tâche 1.1, mesure de la CI) ; périmètre exact du scope de l'explorateur (décision D3 du design, recommandation donnée).
