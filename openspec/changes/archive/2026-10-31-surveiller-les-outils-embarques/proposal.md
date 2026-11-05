# Proposal

## Why

**AUD-DEP-03** (moyenne, sprint 3 de l'audit du 10 octobre 2026) : deux familles de composants **livrés** n'ont aucune veille de vulnérabilité automatisée.

- Les **dépendances npm** de scip-typescript et de scip-python : deux verrous, 66 paquets (5 et 61), rangés sous `minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/` avec un nom **préfixé** (`scip-typescript-package-lock.json`, `scip-python-package-lock.json`). OSV-Scanner (`pr-ci.yml:19-31`, `--recursive`) reconnaît un verrou npm par son nom `package-lock.json` ; Dependabot n'a pas d'écosystème `npm` (`.github/dependabot.yml` : maven, gradle, github-actions, docker, docker-compose).
- Les **11 composants** d'`embedded-tools.json` (9 `artifacts`, 2 `assembled` : Node, Maven, Coursier, rust-analyzer, les indexeurs scip-*…) : suivis par aucun outil, et exclus explicitement de Dependency-Check (`pom.xml:371-372`, « embedded SCIP tools »).

Ces composants sont installés chez l'utilisateur (zip Windows, image Docker) et exécutent du code de dépôts tiers. Une CVE qui les touche ne produit aujourd'hui ni PR ni échec de gate.

## What Changes

- **Verrous npm vus par le gate OSV** : les deux verrous sont passés explicitement à OSV-Scanner (`--lockfile=package-lock.json:<chemin>`), **sans renommage** (voir l'écart dans `design.md`).
- **Un gate de présence** : tout verrou npm versionné sous `src/main/resources` figure dans les arguments du scan ; un troisième verrou ajouté plus tard ne peut pas être oublié.
- **Une veille planifiée du catalogue** : un script interroge OSV pour chaque composant du catalogue qui a un identifiant d'écosystème connu, signale les lignes de runtime en fin de support, et tourne dans un workflow planifié qui échoue quand un composant est touché. Les composants sans identifiant interrogeable sont **listés comme non couverts**, pas tus.
- **Documentation** : `docs/developer/supply-chain.md` dit ce qui est couvert, par quoi, et ce qui ne l'est pas.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `audit-qualite-code` : trois exigences ajoutées (verrous npm livrés analysés par le gate de vulnérabilités ; aucun verrou livré hors du scan ; veille planifiée du catalogue avec couverture déclarée).

## Hors périmètre

- **Renommer les verrous** ou ajouter l'écosystème `npm` à Dependabot : écarté, voir `design.md` (D1).
- Monter une dépendance npm ou un outil : la veille signale, la montée reste un changement de catalogue qualifié (ADR 0040).
- `scip-java-classpath.lock.json` (verrou de 74 jars, format propre à MINOS) : son contenu est constitué de coordonnées Maven ; la veille planifiée les interroge si le coût le permet (tâche 3.2), sinon il est déclaré non couvert.
- Les dépendances Gradle du plugin (AUD-DEP-04, sprint 5) et le client HTTP de Testcontainers (AUD-DEP-14, changement `aligner-dependances-de-test-testcontainers`).
- Faire de la veille planifiée un check exigé par le ruleset : un workflow planifié ne rend pas de verdict sur une PR.

## Impact

- **Modules du reactor** : aucun code Java. Les verrous restent des ressources de `minos-provider-scip`, à leur emplacement.
- **Fichiers** : `.github/workflows/pr-ci.yml` (arguments du job OSV), un nouveau workflow planifié, un nouveau script sous `scripts/quality/` avec son auto-test, `scripts/quality/check-workflow-pins.py` ou `check-ci-wiring.py` pour le gate de présence, `docs/developer/supply-chain.md`.
- **Surfaces publiques** : aucune.
- **ADR** : aucun nouvel ADR ; l'ADR 0040 (catalogue des outils) reçoit une note de mise en œuvre sur la veille.
- **Gates à rejouer** : `python scripts/quality/check-workflow-pins.py` (actions épinglées par SHA, y compris dans le nouveau workflow), `python scripts/quality/check-ci-wiring.py` (auto-test du nouveau script câblé dans `invariants`), `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-tools-manifest.py`, `python scripts/docs/check-current-docs.py`.
- **Recouvrement** : le changement actif `etendre-audit-outille-a-tout-le-perimetre` (12/13) rend visibles les composants hors reactor pour SpotBugs et PIT ; il ne traite ni OSV ni le catalogue. Pas de conflit de fichiers.
- **Plateformes** : la CI (Ubuntu) ; aucun effet sur les livrables.
