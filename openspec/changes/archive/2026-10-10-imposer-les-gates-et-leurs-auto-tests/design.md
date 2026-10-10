# Design

## Context

Sprint 1 de l'audit du 10 octobre 2026 : AUD-DEP-09, AUD-TST-07, AUD-TST-08, AUD-TST-13 (`docs/audit/2026-10-10/findings.md`). Tous touchent la même question : un contrôle qui existe est-il **opposable** à une fusion ? Le HEAD analysé est `816cdd0c` (`develop`, aucun commit depuis).

## État vérifié au HEAD

Preuves relues le 2026-10-10 sur `816cdd0c`. Le ruleset a été lu en lecture seule (`gh api repos/FTurleque/minos-code-intelligence/rulesets/{id}`), les journaux du run `pr-ci` 38008850294 (push sur `develop`, vert) aussi.

### AUD-DEP-09 — preuve **corrigée** (confirmée en partie)

| Élément | Constat au HEAD |
|---|---|
| Ruleset « Protect main & develop » (id 20809312, cible `~DEFAULT_BRANCH` + `refs/heads/develop`, aucun contournement) | Checks exigés : `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `SonarCloud Code Analysis` ; `strict_required_status_checks_policy: true` ; PR obligatoire, 0 approbation, résolution des fils exigée. |
| Ruleset « Release promotion gate (main only) » (id 21831016) | `Docker upgrade evidence gate`. |
| `Static invariants (single run)` (`pr-ci.yml:39-40`) | **Non exigé.** Le nom d'étape `pr-ci.yml:113` le dit encore (« this job is not a required check, audit G6 »). |
| `Gitleaks` (`secret-scan.yml:24-25`, déclenché sur `pull_request` sans filtre de chemins) | **Non exigé.** |
| Plugin IntelliJ (`intellij-plugin.yml:33-34` et `:86-87`, workflow filtré par chemins `:6-12`) | **Non exigé** ; ne peut pas l'être tel quel (un check exigé d'un workflow filtré reste « en attente » sur toute PR qui ne touche pas ces chemins). |
| Les 5 checks que l'audit proposait d'ajouter | 3 sur 5 déjà exigés (Verify ×2, OSV). Restent à ajouter : `Static invariants (single run)`, `Gitleaks`, un agrégateur du plugin. |

Le statut « plausible » de l'audit devient **confirmé** pour trois checks et **infirmé** pour trois autres. Effet de bord à connaître : `check-partial-result-consumers.py` (`pr-ci.yml:113-114`) est une heuristique « consultative » qui sort en code 1 sur échec ; exiger le job la rend bloquante (voir D7).

### AUD-TST-07 — confirmé, périmètre étendu

- `python scripts/quality/check-jacoco.py --self-test` → `MINOS JACOCO GATE SELF-TEST SUCCESS (8 scenarios)`, code 0. Aucune étape de workflow ne le lance (`grep -rn "self-test" .github/workflows` : `pr-ci.yml:69,75,81,87,95,101,116,118`, aucune sur `check-jacoco`).
- **Ajout de l'analyse** : `python scripts/quality/check-milestone-artifact-references.py --self-test` → `SELF-TEST SUCCESS`, et `pr-ci.yml:110-111` ne lance que le gate. L'ADR 0043 § 4 exige que ce garde-fou ait son auto-test.
- **Piège non vu par l'audit : l'action proposée casserait deux gates.**
  1. `scripts/quality/check-single-execution.py` compte toute ligne contenant `scripts/quality/check-jacoco.py` comme une exécution du gate (`JACOCO`, ligne 46 ; `controls_of`, à partir de la ligne 82) et exige exactement une exécution `jacoco-linux` dans `pr-ci.yml`. Ajouter `check-jacoco.py --self-test` en fait deux : « `jacoco-linux` must run exactly once, found 2 ».
  2. `scripts/remediation/check-audit-remediation-v2.py:105-107` interdit le jeton `check-jacoco.py` dans le bloc du job `invariants` (« duplicated the Maven verify job's responsibility »).
- Inventaire des auto-tests (`scripts/**/test_*.py` : 9 fichiers ; scripts à `--self-test` : 3) : tous câblés dans `invariants` **sauf** les deux ci-dessus.

### AUD-TST-08 — confirmé, remède de l'audit **écarté**

- `scripts/quality/check-jacoco.py:155` : `"report": "target/site/jacoco/jacoco.xml"` ; `minos-app/pom.xml:37` : `<directory>${maven.multiModuleProjectDirectory}/target</directory>`. Preuve exacte.
- L'audit propose (a) « rendre à `minos-app` son répertoire » ou (b) « passer ces classes dans le rapport agrégé ». Les mesures locales (rapports du `verify` du 10/10) écartent les deux :
  - (a) : **14 consommateurs** du `target/*-all.jar` racine (`docker/Dockerfile.mcp`, `minos.cmd`, `scripts/release/build-windows-distribution.ps1`, `scripts/ci/qualify-docker-release.sh`, `qualify-docker-upgrade.ps1`, `scripts/intellij/run-minos.ps1`, `scripts/m14/validate-local.ps1`, `scripts/m24..m27/*-e2e.py`, `scripts/m29/run-s3.ps1`, `run-s4.ps1`, `M29DockerReleaseCiGateContractTest`). La redirection est une convention de livraison, pas un accident (première occurrence dans le commit `2de847bd`, jalon M30).
  - (b) : dans `target/site/jacoco-aggregate/jacoco.xml`, `com/minos/app/McpBackend` est à **0/10 lignes** (l'exécution des tests de `minos-app` n'y figure pas), alors que dans `target/site/jacoco/jacoco.xml` il est à 9/10. Le scope tomberait à 0 %.
- Le vrai défaut est donc un couplage **implicite** : le chemin du rapport suppose une propriété du POM d'un autre fichier, sans contrôle.

### AUD-TST-13 — confirmé

`intellij-plugin.yml:6-12` et `:14-20` : filtres `minos-intellij/**`, `minos-cli/**`, `minos-integration-git/**`, deux documents et le workflow. Les rendus JSON sont dans `minos-application/src/main/java/com/minos/output/` (13 classes dont `ProjectJson`, `SymbolResultRenderer`, `ArchitectureResultRenderer`). Atténuation confirmée : `minos-app/src/test/resources/characterization/cli-json.golden` (58,5 Ko) fige les sorties dans le job `Verify`. Ordre de grandeur de la charge ajoutée : 14 commits touchant `output/` et 26 touchant `minos-domain/` depuis le 10/07, contre 197 touchant déjà `minos-intellij`/`minos-cli`.

## Goals / Non-Goals

**Goals** : qu'un renommage de job, un workflow filtré par chemins ou un auto-test non câblé ne puissent plus **désarmer** un contrôle sans qu'un gate le dise ; que l'écart entre le ruleset et la liste attendue se mesure par une commande ; que le plugin ait un verdict sur toute PR ; que le chemin d'un rapport JaCoCo soit dérivé de sa source.

**Non-Goals** : modifier le ruleset depuis le dépôt (aucun secret d'administration en CI) ; changer le contenu d'un gate existant au-delà de ce qui est dit en D5 ; rendre exigibles les autres workflows filtrés.

## Decisions

### D1. Liste versionnée des checks exigés : `.github/required-checks.json`

```json
{
  "version": 1,
  "rulesets": {
    "Protect main & develop": {
      "branches": ["main", "develop"],
      "checks": [
        { "context": "Verify (ubuntu-24.04)",                    "source": "workflow", "workflow": "pr-ci.yml" },
        { "context": "Verify (windows-2022)",                    "source": "workflow", "workflow": "pr-ci.yml" },
        { "context": "Dependency vulnerability gate / osv-scan", "source": "reusable", "workflow": "pr-ci.yml" },
        { "context": "Static invariants (single run)",           "source": "workflow", "workflow": "pr-ci.yml" },
        { "context": "Gitleaks",                                 "source": "workflow", "workflow": "secret-scan.yml" },
        { "context": "IntelliJ plugin (gate)",                   "source": "workflow", "workflow": "intellij-plugin.yml" },
        { "context": "SonarCloud Code Analysis",                 "source": "application" }
      ]
    },
    "Release promotion gate (main only)": {
      "branches": ["main"],
      "checks": [
        { "context": "Docker upgrade evidence gate", "source": "workflow", "workflow": "release-promotion-gate.yml" }
      ]
    }
  }
}
```

Chaque ruleset porte les branches qu'il protège : c'est d'elles que `check-ci-wiring.py` déduit où le workflow doit se déclencher. Le fichier **déclare l'attendu** ; il ne configure rien. Il porte le même rôle que `ALLOWED_DEPENDENCIES` pour les modules : une table que le gate relit et que la revue voit changer. Les contextes `application` (SonarCloud, `integration_id` 12526) ne sont pas résolubles hors ligne ; ils sont déclarés pour que `verify-ruleset.py` les compare.

### D2. `scripts/quality/check-ci-wiring.py` : le câblage se vérifie sur la structure

Lecture ligne à ligne en bibliothèque standard (comme `check-single-execution.py` ; PyYAML n'est pas garanti sur le runner). Trois règles, ancrées sur la structure (ADR 0043 § 3), jamais sur une phrase :

1. **Résolution.** Chaque entrée `workflow` correspond à exactement un job dont le `name:` (matrice développée : `Verify (${{ matrix.os }})` → deux contextes) est ce contexte, dans le workflow déclaré, et ce workflow est atteignable : déclenché par `pull_request` vers les branches cibles du ruleset, **sans `paths:` ni `paths-ignore:` sous ce déclencheur**. Un `if:` de niveau job est admis (un job sauté compte comme réussi pour GitHub). Une entrée `reusable` est résolue par le job appelant (`name:` égal au préfixe avant ` / `). Une cible introuvable est un **échec**, jamais « rien à vérifier ».
2. **Auto-tests.** Tout `scripts/**/test_*.py` (hors `history/`) et tout script qui déclare `--self-test` (`add_argument("--self-test"…)`) est exécuté par une ligne `run:` du job `invariants`. Le job est découpé comme `read_job_block` de `check-audit-remediation-v2.py` (clé de job à deux espaces).

3. **Pas d'appel réseau en CI.** Aucune ligne de workflow ne lance `verify-ruleset.py` sans `--from-json` (le jeton nécessaire pour lire un ruleset ne doit pas être donné à une PR).

Ce que le gate **ne prouve pas** : que le ruleset réel contient ces contextes (c'est `verify-ruleset.py`), ni que l'étape trouvée s'exécute sur chaque événement.

Alternative écartée : dériver la liste du ruleset en CI. Elle exige un jeton `administration: read`, qu'un workflow de PR ne doit pas recevoir.

### D3. `scripts/quality/verify-ruleset.py` : mesure manuelle de l'écart

`python scripts/quality/verify-ruleset.py [--repo FTurleque/minos-code-intelligence]` appelle `gh api repos/{repo}/rulesets` puis chaque ruleset par nom, et compare ses `required_status_checks` à D1.

- Code 0 : égalité. Code 1 : contextes manquants **ou en trop**, nommés. Code 2 : `gh` absent, non authentifié ou réponse illisible. **Jamais 0 sur une erreur** (cohérent avec « une erreur d'outil n'est pas un résultat propre »).
- `--from-json <fichier>` remplace l'appel `gh` par une charge enregistrée : c'est ainsi que `test_verify_ruleset.py` (exécuté en CI, donc câblé par D2) teste la comparaison sans réseau. L'appel réel reste manuel.
- Le script n'écrit jamais dans le ruleset ; il n'imprime que des noms de contextes.

### D4. Le plugin IntelliJ : portée calculée, verdict toujours rendu

Options considérées :

| Option | Verdict |
|---|---|
| Exiger `IntelliJ 2026.1 / Java 21` tel quel | Écartée : attente éternelle sur toute PR hors portée. |
| Exécuter le plugin sur **toutes** les PR | Écartée : deux jobs (Ubuntu, Windows) à chaque PR documentaire ; la charge n'apporte rien hors portée. |
| Action tierce de filtre de chemins, épinglée par SHA | Écartée : nouvelle chaîne d'approvisionnement pour 40 lignes de logique testable. |
| **Script `scripts/ci/plugin-gate.py`** + job agrégateur | Retenue. |

Structure de `intellij-plugin.yml` : plus de `paths:` sous `on:` (les déclencheurs `pull_request`/`push` sur `main` et `develop` restent, ce que `check-current-docs.py` exige) ; trois jobs en plus des deux existants, dont les `name:` et les étapes ne changent pas :

- `changes` (nom : `Plugin scope`) : checkout `fetch-depth: 0`, Python, `plugin-gate.py scope` → sortie `plugin=true|false`. Pour `pull_request` : diff `base.sha...head.sha` ; pour `push` : `event.before..sha` ; `workflow_dispatch`, base introuvable ou toute erreur : `true` (**fail-closed** : dans le doute, le plugin tourne).
- `plugin` et `windows-ownership` : `needs: changes`, `if: needs.changes.outputs.plugin == 'true'`.
- `gate` (nom : `IntelliJ plugin (gate)`) : `if: always()`, `needs: [changes, plugin, windows-ownership]`, appelle `plugin-gate.py verdict` avec les résultats des trois jobs. Réussit si `changes` a réussi et que chaque autre job a réussi ou a été sauté **parce que `plugin=false`** ; échoue sur `failure`, `cancelled`, ou si `changes` a échoué.

Liste de portée (`PLUGIN_PATHS`, une constante unique) : `minos-intellij/`, `minos-cli/`, `minos-integration-git/`, `minos-application/src/main/java/com/minos/output/`, `minos-domain/`, `minos-app/src/test/resources/characterization/`, `docs/user/intellij-plugin.md`, `docs/roadmap/M18_EXECUTION.md`, `.github/workflows/intellij-plugin.yml`, `scripts/ci/plugin-gate.py`. Le test `test_plugin_gate.py` vérifie (1) qu'un fichier de `com/minos/output/` déclenche, qu'un `README.md` racine ne déclenche pas ; (2) que **chaque préfixe existe** dans le dépôt, de sorte qu'un répertoire renommé ne vide pas le filtre en silence.

`minos-domain/**` va au-delà du seul `output/` : les rendus lisent le domaine. C'est le compromis de l'audit ; si la charge s'avère trop forte, le retirer est une ligne et un test.

### D5. Auto-tests rejoués sans casser les gates qui comptent

- Deux étapes ajoutées à `invariants` : `python scripts/quality/check-jacoco.py --self-test` et `python scripts/quality/check-milestone-artifact-references.py --self-test`.
- `check-single-execution.py` : une ligne contenant `--self-test` n'est **pas** une exécution de contrôle (aucune des quatre commandes qu'il possède n'a de `--self-test` légitime en exécution). Cas ajouté à `test_check_single_execution.py` : un auto-test JaCoCo + une exécution = succès ; deux exécutions = échec ; zéro exécution = échec.
- `check-audit-remediation-v2.py:105-107` : le jeton `check-jacoco.py` quitte la liste des jetons interdits ; il est remplacé par la règle de fond que le jeton approximait : aucune ligne du job `invariants` ne **lance le gate sur un rapport** (`check-jacoco.py` sans `--self-test`). `mvnw`, `windows-2022` et `matrix:` restent interdits.

### D6. `check-jacoco.py` : le rapport d'un scope se dérive de son module

Le scope porte `"report_of": "minos-app"` au lieu de `"report": "target/site/jacoco/jacoco.xml"`. Le script lit `<module>/pom.xml` :

| `<build><directory>` du module | Rapport |
|---|---|
| `${maven.multiModuleProjectDirectory}/target` | `target/site/jacoco/jacoco.xml` (racine) |
| absent | `<module>/target/site/jacoco/jacoco.xml` |
| toute autre valeur | échec explicite (« répertoire de build non reconnu ») |

Scénarios ajoutés à l'auto-test (9, 10 et 11) : les deux formes, plus la valeur inconnue. Le message final de l'auto-test change (« 8 scenarios » → « 11 scenarios »), sans autre dépendance (`grep` : un seul fichier).

### D7. Conséquence assumée : l'heuristique consultative devient bloquante

`check-partial-result-consumers.py` est un jeu de règles heuristique, documenté comme consultatif parce que `invariants` n'était pas exigé. Le rendre exigible le rend bloquant. Décision : l'accepter. Ses faux positifs documentés (« none exists today »), son plafond `GAP_CEILING` et son auto-test (déjà câblé) limitent le risque ; la docstring (`STATUS: advisory`) et le nom d'étape sont mis à jour. Si l'heuristique devait se révéler trop bruyante, l'étape peut sortir du job sans toucher au ruleset.

### D8. Ordre de mise en service

Un check exigé **avant** d'avoir tourné bloque toutes les PR. Séquence : (1) fusionner ce changement ; (2) laisser `Static invariants`, `Gitleaks` et `IntelliJ plugin (gate)` s'exécuter sur au moins une PR ; (3) ajouter les trois contextes au ruleset (tâche manuelle) ; (4) lancer `verify-ruleset.py`. Le changement n'est « fermé » qu'à l'étape 4.

## Qualification des capacités nouvelles

| Capacité | Statut | Preuve ou condition |
|---|---|---|
| `check-ci-wiring.py` (résolution + auto-tests câblés) | **Qualifiée localement** | Commande Python et son auto-test, sans réseau ni build. |
| `verify-ruleset.py` | **Partielle** | Comparaison testée hors ligne ; lecture réelle du ruleset exécutée le 10/10 à la main ; l'égalité n'est atteinte qu'après l'action manuelle. |
| `plugin-gate.py scope` / `verdict` | **Partielle** | Logique testée localement ; le comportement de GitHub (job sauté = réussi, `needs` d'un job `if: always()`) n'a pas été exécuté : à vérifier sur une PR jetable, avec votre autorisation. |
| Rapport JaCoCo dérivé du POM | **Qualifiée localement** | Auto-test + exécution du gate sur le rapport local du `verify` du 10/10. |

## Windows et Linux

Les scripts sont en Python pur et valent sur les deux OS ; aucun job de `verify` n'est modifié. `check-ci-wiring.py` lit les workflows en UTF-8 et normalise les fins de ligne à la lecture (poste Windows et runner Linux). `plugin-gate.py scope` s'exécute sur Ubuntu uniquement (job `changes`). Aucun comportement propre à une plateforme n'est introduit.

## Gates littéraux concernés

| Script | Raison | Action |
|---|---|---|
| `check-single-execution.py` + `test_check_single_execution.py` | compte les lignes `check-jacoco.py` | D5, cas ajoutés |
| `check-audit-remediation-v2.py:105-107` | interdit `check-jacoco.py` dans `invariants` | D5 |
| `check-audit-remediation-v2.py:113-` | exige les noms d'étape `Maven clean verify (Unix, PostgreSQL required)`, `(Windows)` | inchangés |
| `check-current-docs.py:175-180` | exige `push:`, `Checkout exact candidate`, `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`, `verifyPlugin` dans `intellij-plugin.yml` | la restructuration les conserve ; rejouer |
| `check-p0-p2.py:118-` | exige `delegate-linux-cgroup.sh --attach-pid $$` dans `pr-ci.yml` | inchangé |
| `check-workflow-pins.py` | épinglage des `uses` | aucun `uses` nouveau hors `actions/*` et `setup-python` déjà épinglés au même SHA |
| `check-milestone-artifact-references.py` | tout script doit être référencé | `check-ci-wiring.py`, `plugin-gate.py` (workflow), `verify-ruleset.py` (documentation) |

## Risks / Trade-offs

- [`Static invariants` exigé : un faux positif bloque toute fusion] → le job est vert sur les cinq derniers runs de `develop` (un seul échec de PR le 09/10 à 20:59, corrigé depuis) ; le retrait d'une étape reste un commit.
- [Un check exigé dont le nom change devient « en attente » éternel] → R1 de D2 échoue dès la PR qui renomme le job.
- [`minos-domain/**` dans la portée du plugin augmente la charge] → mesuré à 26 commits en trois mois ; voir D4.
- [Le calcul de portée se trompe] → fail-closed : erreur = `plugin=true`.
- [Le job `gate` masque un `plugin` sauté à tort] → le verdict exige `plugin=false` pour accepter un saut.

## Direction des dépendances (ADR 0022)

Aucune dépendance Maven ajoutée, aucun module touché. Les scripts ne lisent les POM que via les fonctions existantes de `check-module-boundaries.py` si besoin (D6 lit un POM, mais pour un chemin de build, pas pour une dépendance).

## Décisions qui vous attendent

1. **Capacité nouvelle `controles-de-fusion`** plutôt qu'un amendement d'`audit-qualite-code` (recommandé, voir la proposition). À confirmer.
2. **Exiger `Static invariants (single run)`** et accepter que l'heuristique `check-partial-result-consumers.py` devienne bloquante (D7).
3. **Portée du plugin** : garder `minos-domain/**` (recommandé, suit l'audit) ou la réduire à `output/**` + goldens.
4. **Action manuelle sur le ruleset** (section 6 de `tasks.md`), après un premier run.
