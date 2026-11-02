# Proposal

## Why

Trois constats du sprint 5 de l'audit du 10 octobre 2026 portent sur l'hygiène des **mêmes fichiers de workflow** et se gardent par le **même gate** (`scripts/quality/check-workflow-pins.py`) :

- **AUD-DEP-12** (faible) : aucun `actions/checkout` ne désactive la conservation du jeton (`persist-credentials`), et des jobs qui exécutent le code d'une PR n'ont pas de délai maximal. Décompte au HEAD : **25** checkouts (l'audit en comptait 22), 0 avec `persist-credentials` ; **7** jobs sur 28 sans `timeout-minutes` (l'audit n'en citait qu'un), dont `verify` et `invariants` de `pr-ci.yml` et un check exigé sur `main`.
- **AUD-DEP-05** (moyenne, **partiellement corrigé** par la PR #427) : la version de Gradle des workflows de release est désormais égale à celle du wrapper et gardée par `check-toolchain-baseline.py`. Il reste que la release construit le plugin par `gradle` installé (`gradle-version:` + `gradle --no-daemon`, 3 appels) au lieu du wrapper qualifié par la CI, et que `intellij-plugin-release.yml` tourne sur `ubuntu-latest` (2 occurrences, les seules du dépôt), image flottante.
- **AUD-DEP-13** (faible) : des identifiants Docker Hub sont posés par `docker/login-action` dans des jobs qui exécutent ensuite du code de la PR. Décompte au HEAD : **5** jobs (l'audit en citait 3).

Aucun de ces points n'est gardé : `check-workflow-pins.py` ne contrôle que l'épinglage des actions, et il n'a **ni auto-test ni `test_*.py`**.

## What Changes

- **Jeton du checkout** : `persist-credentials: false` sur tous les checkouts (aucun workflow ne fait de `git push` ni d'opération Git authentifiée après un checkout).
- **Délai maximal** sur chaque job qui s'exécute sur un runner.
- **Runners épinglés** : plus de `*-latest`.
- **Release du plugin par le wrapper** : `./gradlew` au lieu d'un Gradle installé à part.
- **Identifiants Docker Hub** : portée du jeton vérifiée et documentée ; déconnexion avant l'exécution du code de la PR là où la liste d'images à tirer est fermée.
- **Le gate apprend ces règles**, avec un auto-test créé et câblé dans `invariants`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `controles-de-fusion` : quatre exigences ajoutées (jeton du checkout non conservé ; délai maximal de chaque job ; runner et outil de build épinglés ; identifiants de registre non exposés au code d'une PR au-delà du nécessaire).

## Hors périmètre

- Les autres constats du sprint 5 : verrou Gradle du plugin (AUD-DEP-04), signature et attestation (AUD-DEP-06), déclenchement de la publication du plugin (AUD-DEP-07), reproductibilité Maven (AUD-DEP-11).
- Les permissions de chaque job (`permissions:`) : non relevées par l'audit ; à traiter si un constat le demande.
- Les workflows réutilisables externes (OSV-Scanner) : leur délai et leur checkout ne se règlent pas depuis l'appelant.
- Restreindre les secrets à un environnement GitHub : écarté, voir `design.md` (D4).

## Impact

- **Modules du reactor** : aucun.
- **Fichiers** : les 13 workflows de `.github/workflows/` ; `scripts/quality/check-workflow-pins.py` et un nouveau `scripts/quality/test_check_workflow_pins.py` ; `scripts/quality/check-toolchain-baseline.py` et son test (règle sur `gradle-version:`) ; `.github/workflows/pr-ci.yml` (câblage du nouvel auto-test) ; `docs/developer/supply-chain.md`.
- **Surfaces publiques** : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/quality/check-workflow-pins.py`, `python scripts/quality/check-ci-wiring.py` (tout `test_*.py` câblé dans `invariants`), `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-toolchain-baseline.py` et `test_check_toolchain_baseline.py` (`:36` traite aujourd'hui `gradle-version: '9.8.1'` comme valide), `python scripts/docs/check-current-docs.py` (`:207-213` : chaînes exigées dans `release-windows.yml`), `.github/required-checks.json` (noms de jobs inchangés).
- **Plateformes** : runners Ubuntu 24.04 et Windows Server 2022.
