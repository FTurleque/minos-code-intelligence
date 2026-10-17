# Design

## Context

Sprint 5 de l'audit du 10 octobre 2026 : **AUD-DEP-12**, le reste d'**AUD-DEP-05** et **AUD-DEP-13**. HEAD analysé : `2d1cc214` (`develop`). Dépôt **public** (`gh repo view --json visibility`).

## État vérifié au HEAD

### AUD-DEP-12 — confirmé, décomptes périmés

- **25** `actions/checkout`, 0 `persist-credentials`. Par workflow : `code-audit.yml` 4, `intellij-plugin.yml` 4, `pr-ci.yml` 3, `historical-qualification.yml` 3, `release-windows.yml` 2, `release-promotion-gate.yml` 2, 1 dans chacun des sept autres. `release-windows.yml:237-241` (job `publish`, `contents: write`) : exact.
- **7 jobs sur 28 sans `timeout-minutes`** : `pr-ci.yml` `verify` et `invariants` ; `code-audit.yml` `spotbugs`, `mutation`, `dependency-check` ; `m0-java-ci.yml` `verify` ; `release-promotion-gate.yml` `docker-upgrade-evidence` (check exigé sur `main`). Le job `vulnerability-scan` de `pr-ci.yml` est un appel de workflow réutilisable : `timeout-minutes` y est interdit par GitHub.
- Opérations Git après checkout : `git fetch origin main` (`pr-ci.yml:235`, l'audit citait `:208`), `git fetch --tags` et `ls-remote` (`release-windows.yml:57-58`), `ls-remote` (`publish-windows-release.ps1:341`). Toutes en lecture sur un dépôt public. **Aucun `git push`** dans les workflows, ni dans `scripts/ci` ou `scripts/release`. Tous les appels `gh` passent par `GH_TOKEN` en variable d'environnement.

### AUD-DEP-05 — partiellement corrigé par la PR #427

- **Corrigé** : `gradle-version: '9.8.1'` aux deux sites (`intellij-plugin-release.yml:92`, `release-windows.yml:101`), égal au wrapper ; `docs/developer/TOOLCHAIN_POLICY.md:19` à jour ; `check-toolchain-baseline.py` (règle 3) impose l'égalité.
- **Reste** : 2 `gradle-version:` et 3 appels `gradle --no-daemon` (`intellij-plugin-release.yml:113`, `release-windows.yml:107,112`) : la release n'utilise pas `./gradlew`. 2 `runs-on: ubuntu-latest` (`intellij-plugin-release.yml:24,144`), seules occurrences de `*-latest` du dépôt.

### AUD-DEP-13 — confirmé, 5 jobs et non 3

| Job | Connexion | Code exécuté ensuite | Événements |
|---|---|---|---|
| `pr-ci.yml` `verify` (Linux seul) | `:204-207` | `mvnw clean verify`, scripts `ci/` | PR, push |
| `code-audit.yml` `mutation` | `:128-131` | `mvnw` PIT | dispatch |
| `docker-release-validation.yml` `provider-complete-image` | `:47-50` | `qualify-docker-release.sh` | PR, push |
| `docker-upgrade-qualification.yml` `real-docker-upgrade` | `:49-52` | `qualify-docker-upgrade.ps1` | dispatch |
| `release-promotion-gate.yml` `docker-upgrade-qualification` | `:57-60` | `qualify-docker-upgrade.ps1` | PR |

Les deux derniers ne sont pas cités par l'audit. Le commentaire `pr-ci.yml:198-201` justifie la connexion par les tirages de Testcontainers pendant les tests.

**Non vérifié** : la portée réelle du jeton Docker Hub (« lecture seule » n'est écrit que dans un commentaire ; l'API ne permet pas de la lire). Aucun workflow n'a été exécuté pendant l'analyse.

### Gate concerné

`scripts/quality/check-workflow-pins.py` contrôle l'épinglage par SHA des actions et ignore les `uses: ./…`. Il n'a **ni `--self-test` ni `test_check_workflow_pins.py`** : toute règle ajoutée exige de créer l'auto-test et de le câbler dans `invariants` (`check-ci-wiring.py`).

### Écarts avec l'action proposée par l'audit

1. **AUD-DEP-12 — périmètre.** « `timeout-minutes: 90` sur `verify` » couvre un job sur sept. La règle porte sur **tout job à `runs-on`**, avec l'exception codée des appels de workflow réutilisable.
2. **AUD-DEP-12 — l'hypothèse « dépôt public » doit être écrite.** `persist-credentials: false` ne casse rien parce que les `fetch` sont anonymes sur un dépôt public ; si le dépôt devenait privé, `pr-ci.yml:235` et `release-windows.yml:57-58` devraient s'authentifier explicitement.
3. **AUD-DEP-05 — « refuser `gradle-version:` » contredit un gate adopté la veille.** La règle 3 de `check-toolchain-baseline.py` et `test_check_toolchain_baseline.py:36` traitent `gradle-version: '9.8.1'` comme valide. Les deux se modifient ensemble (D2).
4. **AUD-DEP-13 — « `docker logout` avant `mvnw` » casse l'objet de la connexion.** Dans `pr-ci.yml`, la connexion sert justement aux tirages faits **pendant** `mvnw verify` (Testcontainers). Se déconnecter avant exige de pré-tirer toutes les images, dont la liste doit alors être calculée (D4).
5. **AUD-DEP-13 — un environnement GitHub** sur des jobs de PR ajoute des règles de déploiement et des approbations : inadapté à un check exigé.

## Goals / Non-Goals

**Goals** : le jeton du dépôt ne reste pas sur le disque d'un runner ; aucun job ne peut immobiliser un runner six heures ; la release du plugin sort de la même chaîne que la CI ; les identifiants de registre sont de portée minimale et ne restent pas disponibles sans nécessité ; chaque règle est gardée et le gate est lui-même testé.

**Non-Goals** : revoir les permissions de chaque job ; changer les checks exigés.

## Decisions

### D1. `persist-credentials: false` partout, sans exception

Les 25 checkouts. Si un futur job a besoin d'une opération Git authentifiée, il s'authentifie explicitement (jeton en variable d'environnement, portée du job). Le gate refuse tout `actions/checkout` sans cette option.

### D2. La release du plugin passe par le wrapper

Les étapes `gradle-version:` + `gradle …` deviennent `./gradlew …` dans `minos-intellij` (l'action de configuration de Gradle peut rester pour le cache, sans `gradle-version`). `check-toolchain-baseline.py` : la règle 3 devient « aucun `gradle-version:` dans un workflow » et son test est inversé dans la même tâche. Le wrapper vérifie déjà l'empreinte de sa distribution.

### D3. Délais et runners

`timeout-minutes` sur chaque job à `runs-on` ; valeurs fixées d'après les durées observées des derniers runs (tâche 1.1), avec marge ; l'appel réutilisable est l'exception nommée dans le gate. `ubuntu-latest` devient `ubuntu-24.04` ; le gate refuse tout `runs-on` terminé par `-latest`.

**Relevé de la tâche 1.1 (2026-10-15)**, durées des jobs réussis lues par `gh run view` (6 derniers runs réussis de chaque workflow, sauf mention) :

| Job | Durée maximale observée | `timeout-minutes` retenu |
|---|---|---|
| `pr-ci.yml` `invariants` | 1,3 min | 15 |
| `pr-ci.yml` `verify` | 8,6 min (Linux), 26,6 min (Windows) | 90 |
| `code-audit.yml` `spotbugs` | 4,1 min (2 runs) | 30 |
| `code-audit.yml` `mutation` | 20,2 min (2 runs, module choisi) ; `minos-app`, le plus long, **jamais mesuré** | 120 |
| `code-audit.yml` `dependency-check` | **aucun run réussi** (clé NVD absente) | 90 |
| `m0-java-ci.yml` `verify` | **aucun run** (`workflow_dispatch` seul) ; équivalent de `Verify (ubuntu-24.04)` | 60 |
| `release-promotion-gate.yml` `docker-upgrade-evidence` | 0,3 min | 10 |

Les trois valeurs sans mesure sont des bornes larges, à resserrer au premier run réussi.

### D4. Identifiants Docker Hub — **option A retenue par le propriétaire le 2026-10-15, avec un écart**

**Écart constaté à l'implémentation** : la liste d'images n'est fermée que pour **un** des trois jobs de qualification. `docker-release-validation.yml` ne construit que depuis les `FROM` épinglés de `docker/Dockerfile.mcp.release` : il les tire connecté, puis `docker logout`. Les deux jobs de montée de version (`docker-upgrade-qualification.yml` `real-docker-upgrade`, `release-promotion-gate.yml` `docker-upgrade-qualification`) construisent aussi le **candidat A** depuis un autre commit (le précédent, ou `main`), dont le Dockerfile peut nommer d'autres empreintes : un pré-tirage depuis l'arbre courant ne les couvrirait pas, et un tirage anonyme pendant la qualification retomberait sous la limite du 9 octobre. Ils rejoignent la liste fermée avec cette raison (quatre entrées au lieu de deux). Les fermer exigerait de lire les `FROM` des deux arbres (`git show <ref>:docker/…`) avant la connexion : changement possible, non fait ici.

Recommandation (option A) : (1) **vérifier et documenter** que le jeton est en lecture seule sur les dépôts publics (tâche manuelle : seul le propriétaire du compte peut le lire), l'écrire dans `supply-chain.md` ; (2) dans les jobs dont la liste d'images est **fermée** (les trois jobs de qualification Docker), tirer les images épinglées puis `docker logout` avant d'exécuter les scripts ; (3) dans `pr-ci.yml` `verify` et `code-audit.yml` `mutation`, où Testcontainers tire pendant les tests, garder la connexion et l'**inscrire dans une liste fermée** du gate avec sa raison.

Option B : pré-tirer aussi les images de Testcontainers (liste calculée depuis les références épinglées que lit `check-image-pins.py`) et se déconnecter partout. Plus strict ; ajoute un couplage entre le workflow et la liste d'images, et un test qui tirerait une image non listée échouerait sur quota.

Écartée : environnement GitHub (écart 5).

### D5. Le gate est étendu et enfin testé

`check-workflow-pins.py` gagne les règles : checkout sans `persist-credentials: false` ; job à `runs-on` sans `timeout-minutes` ; `runs-on` en `-latest` ; étape `run:` après `docker/login-action` sans `docker logout`, hors liste fermée. `test_check_workflow_pins.py` (arbres temporaires, un cas refusé et un accepté par règle, y compris les règles d'épinglage existantes) est câblé dans `invariants`.

## Direction des dépendances

Sans objet (aucun module).

## Qualification des capacités

Sans objet ; aucune capacité produit. Les checks exigés gardent leurs noms (`.github/required-checks.json`).

## Windows et Linux

Les deux familles de runners sont concernées par les délais et le checkout. `./gradlew` s'exécute sous Linux dans les deux workflows de release concernés (à confirmer en tâche 1.1 pour le job de `release-windows.yml`, qui tourne sous Windows : `gradlew.bat`).

## Risks / Trade-offs

- Un délai trop court rend un check exigé instable : valeurs fondées sur les durées observées, avec marge large.
- Les workflows de release ne tournent pas sur une PR : la bascule vers le wrapper n'est prouvée qu'au prochain candidat (tâche manuelle), d'où l'intérêt de la faire maintenant et non le jour d'une release.
- YAML invalide : chaque workflow modifié est relu par `yaml.safe_load` avant commit.
