# Design

Changement d'outillage, de configuration CI et de documentation : seules les sections Contexte, État vérifié, Écarts, Décisions, Vérification et Traçabilité sont développées. Les autres sections du modèle sont « Non applicable : aucun code du produit, aucune surface publique, aucune donnée persistée n'est touché ».

## Contexte

Motivation : `proposal.md` (Why). Deux transitions sont closes — Java 25 → Java 21 (GEN-0077, borne `[21,22)` sur `main` depuis le 2026-11-07) et `develop` → `main` seule (2026-11-13) — et chacune a laissé un reste : une exception de gate et deux étapes de workflow pour la première, des phrases de documents courants et un risque ouvert pour la seconde.

## État vérifié au HEAD

HEAD `89be3fb4`. Lecture et commandes Git locales en lecture seule ; aucun gate exécuté, aucun appel à GitHub.

| Fait | Preuve |
|---|---|
| [Existant vérifié] Borne de l'enforcer : `[21,22)` | `pom.xml:207` |
| [Existant vérifié] Étape « Set up the previous Java baseline for candidate A », `java-version: '25'`, avec la consigne « Remove this step, and its entry in check-toolchain-baseline.py, once no candidate A is built for Java 25 » | `.github/workflows/release-promotion-gate.yml:71-79` ; `.github/workflows/docker-upgrade-qualification.yml:55-63` |
| [Existant vérifié] Dans le gate de promotion, A = `github.event.pull_request.base.sha` (donc `main`) | `release-promotion-gate.yml:99-103` |
| [Existant vérifié] `PREVIOUS_JAVA = "25"`, `PREVIOUS_JAVA_STEPS` à deux entrées, consigne « Empty this table (and remove the steps) when `main` is built for the current baseline » | `scripts/quality/check-toolchain-baseline.py:106-113` ; usage `:190-203` |
| [Existant vérifié] L'auto-test exerce la table par ses entrées réelles ; son commentaire d'en-tête des fixtures cite `PREVIOUS_JAVA` | `scripts/quality/test_check_toolchain_baseline.py:178-179`, `:284-299` |
| [Existant vérifié] Des refus existent déjà : workflow resté sur une autre version (`pr-ci.yml`, job `verify`), autre job du workflow de qualification (`another-job`) | `test_check_toolchain_baseline.py:215`, `:266`, `:298-299` |
| [Existant vérifié] La règle Archgate compagnon de GEN-0077 lit `PREVIOUS_JAVA` et `PREVIOUS_JAVA_STEPS` dans le gate et rend `missingConstant` si l'une manque | `docs/architecture/adr/GEN-0077-socle-d-execution-java-21-et-maven.rules.ts:162` (description), `:173-176`, `:213` |
| [Existant vérifié] L'ADR nomme la liste dans sa section de conformité | `docs/architecture/adr/GEN-0077-socle-d-execution-java-21-et-maven.md:104` |
| [Existant vérifié] `scripts/docs/check-current-docs.py` n'a ni `--self-test` ni `test_*.py` ; le changement `doter-chaque-gate-d-un-auto-test` le porte vers un auto-test | `scripts/docs/` (aucun `test_check_current_docs.py`) ; `openspec/changes/doter-chaque-gate-d-un-auto-test/tasks.md` (tâche 2.4) |
| [Existant vérifié] La qualification résout le JDK de chaque candidat d'après son POM (`JAVA_HOME_<majeure>_X64`) et échoue en nommant la version requise si elle manque | `scripts/ci/qualify-docker-upgrade.ps1:54-71` |
| [Existant vérifié] Bornes des releases : `v1.3.0` `[21,22)` ; `v1.2.0` `[24,25)` | `git show v1.3.0:pom.xml` ligne 214 ; `git show v1.2.0:pom.xml` ligne 123 |
| [Existant vérifié] Réserve ouverte au suivi de l'audit précédent | `docs/project/audits/2026-10-10/SUIVI.md:407` |
| [Existant vérifié] « (ruleset « Protect main & develop ») » dans deux documents d'état | `docs/project/governance/STATUS.md:15` ; `docs/project/governance/ROADMAP.md:19` |
| [Existant vérifié] « sur chaque PR vers `main` et `develop` » | `docs/developer/quality/code-audit.md:179` |
| [Existant vérifié] Phrases au présent qui citent `develop` : « ni promotion de develop vers main » ; « Le plan est basé sur `develop` » | `STATUS.md:9` ; `ROADMAP.md:9` |
| [Existant vérifié] R-25 « OPEN — bloque la promotion » : promotion `develop → main`, runner auto-hébergé `minos-docker` | `docs/architecture/risks/register.md:22` (rappel `:7`) |
| [Existant vérifié] Le gate de promotion tourne sur un runner hébergé, et un gate interdit tout runner auto-hébergé | `release-promotion-gate.yml:13`, `:36` ; `scripts/remediation/check-audit-remediation-v2.py:165-174` |
| [Existant vérifié] Rulesets déclarés : « Protect main », « Release promotion gate (main only) » | `.github/required-checks.json` |
| [Existant vérifié] `verify-ruleset.py` apparie par nom exact | `scripts/quality/verify-ruleset.py:51-55` (cité par l'audit, non relu ligne à ligne) |
| [Existant vérifié] `quality-gates.md:55` décrit des workflows supprimés (« sur `pull_request` vers `main` ou `develop` ») : relevé au passé | `docs/developer/quality-gates.md:55` |

Non vérifié : l'état réel du ruleset GitHub et l'absence de la branche distante `develop` à la date du jour (vérifiés le 2026-11-13 par le changement archivé ; tâche manuelle 2.4) ; la présence de R-25 dans `docs/architecture/arc42/11-risques-dette.md` (les deux occurrences de `develop` relevées aux lignes 27 et 36 concernent R-07 et DT-01).

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1. L'audit envisage de garder Java 25 dans `docker-upgrade-qualification.yml` « si [il] doit encore partir d'une release 1.2.0 en Java 25 » | action d'AUD-DEP-04 | `v1.2.0` impose Java 24, pas 25 ; `v1.3.0` impose 21 : aucun candidat A publié n'est construit pour Java 25 | Retirer l'étape des **deux** workflows. Une qualification manuelle depuis `v1.2.0` échoue déjà aujourd'hui (JDK 24 absent) avec un message actionnable ; ce n'est pas une régression (D2) |
| E2. L'audit cite `quality-gates.md:55` parmi la dérive | preuve d'AUD-DEP-06 | la phrase décrit au passé des workflows supprimés | Ne pas la modifier (relevé) |
| E3. L'audit liste trois lignes à corriger ; deux autres phrases au présent citent `develop` | action d'AUD-DEP-06 | `STATUS.md:9`, `ROADMAP.md:9` | Les relire et corriger celles qui décrivent le flux en vigueur (tâche 2.2) |
| E4. Aucune garde n'empêche le retour de l'ancien nom de ruleset | critère de sortie du sprint 2 | `check-current-docs.py` ne lit pas `.github/required-checks.json` | Deux contrôles ajoutés au gate des documents courants (D3) |
| E5. R-25 : « clore ou réécrire » | action d'AUD-DEP-06 | sa cause (aucun runner auto-hébergé) est devenue une règle du dépôt, et la preuve A → B est produite sur runner hébergé à chaque PR | Clore R-25 avec sa raison datée (D4) ; si AUD-DEP-03 restreint la qualification, le risque résiduel est porté par ce changement-là |

## Décisions

### D1 — Retirer l'exception entière : étapes, table et constante

[Décision] Les deux étapes disparaissent ; `PREVIOUS_JAVA` et `PREVIOUS_JAVA_STEPS` sont supprimés de `check-toolchain-baseline.py`, avec la branche qui les lit ; les cas de l'auto-test (`:284-299`) deviennent un cas de refus visant les deux jobs de l'ancienne table (`real-docker-upgrade` de `docker-upgrade-qualification.yml`, `docker-upgrade-qualification` de `release-promotion-gate.yml`), que les refus existants (`:215`, `:266`, `:298-299`) ne couvrent pas ; le commentaire `:178-179` est réécrit. La règle Archgate compagnon de GEN-0077 perd la lecture des deux constantes et la ligne `PREVIOUS_JAVA_STEPS` de sa boucle (`rules.ts:162`, `:173-176`, `:213`), et la ligne 104 de l'ADR ne nomme plus que `PLUGIN_JAVA_STEPS` : clarification, la décision (Java 21, une seule version énoncée) ne change pas. Sans cela, `archgate check` échoue sur `missingConstant`. Écarté : garder la table vide « pour la prochaine fois » — une exception sans objet qui subsiste dans le code est ce que le constat reproche ; un changement de baseline est une décision (politique de toolchain) qui réintroduira le mécanisme s'il le faut.

### D2 — Aucun JDK supplémentaire pour le déclenchement manuel

[Décision] `docker-upgrade-qualification.yml` n'installe que la baseline. Un candidat A construit pour une autre version fait échouer la qualification avant tout build (comportement existant de `Resolve-CandidateJavaHome`), fail-closed. La description de l'entrée `previous_ref` le dit.

### D3 — Garder la documentation courante par deux contrôles littéraux

[Proposition] `check-current-docs.py` : (1) tout « Protect … » cité entre guillemets français dans `STATUS.md`, `ROADMAP.md` et `docs/developer/**/*.md` doit être une clé de `rulesets` de `.github/required-checks.json` ; (2) une ligne du registre des risques dont la colonne de statut contient `OPEN` ne contient pas `develop`. Les relevés datés restent hors contrôle. Écarté : interdire le mot `develop` dans les documents courants (les sections de sprint le citent légitimement comme un fait de leur date).

### D4 — Clore R-25

[Proposition] R-25 passe à un statut clos, avec la raison : preuve A → B produite sur runner hébergé par `release-promotion-gate.yml`, runner auto-hébergé interdit par gate. Le rappel de la ligne 7 du registre est un relevé daté du 30 août 2026 : inchangé. Écarté : supprimer la ligne (un risque ne se supprime pas, il se clôt).

## Vérification

- Auto-tests : `python scripts/quality/test_check_toolchain_baseline.py` ; `python scripts/docs/check-current-docs.py --self-test` (cas ancien nom de ruleset, risque ouvert, relevé daté, ajoutés à la table de mutations que crée `doter-chaque-gate-d-un-auto-test`).
- Gates : `python scripts/quality/check-toolchain-baseline.py`, `python scripts/quality/check-workflow-pins.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/remediation/check-audit-remediation-v2.py`, `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, `archgate check` (règle compagnon de GEN-0077), `python .claude/scripts/run_gates.py`.
- Preuve en CI (à la demande du propriétaire) : la qualification A → B de la PR du changement réussit sans JDK 25.
- Manuel : `python scripts/quality/verify-ruleset.py` contre GitHub ; `git ls-remote --heads origin`.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| socle-d-execution › Aucun workflow n'installe une baseline Java précédente | Étape Java 25 dans un workflow de qualification | D1 | 1.1, 1.2 | `test_check_toolchain_baseline.py` — cas à réécrire |
| idem | Dépôt réel | D1 | 1.2 | `check-toolchain-baseline.py` |
| idem | Candidat A construit pour une autre version | D2 | 1.3 | lecture de `qualify-docker-upgrade.ps1:54-71` ; preuve exécutée manquante (aucun test du script) |
| controles-de-fusion › Les documents courants nomment les rulesets déclarés et aucun risque ouvert ne vise une branche retirée | Ancien nom de ruleset dans l'état courant | D3 | 2.1, 2.2 | cas de `check-current-docs.py --self-test` — à écrire |
| idem | Risque ouvert sur la branche retirée | D3, D4 | 2.1, 2.3 | idem — à écrire |
| idem | Dépôt réel | D3 | 2.2, 2.3 | `check-current-docs.py`, `product-facts.py --check` |
| idem | Relevé daté | D3 | 2.1 | idem — à écrire |

## Ordre de fusion

1. `doter-chaque-gate-d-un-auto-test` (AUD-TST-09, sprint 1) : il donne `--self-test` à `scripts/docs/check-current-docs.py`, que le groupe 2 étend.
2. Ce changement.
3. `borner-la-qualification-docker-aux-pr-concernees` (même `release-promotion-gate.yml`).

Le groupe 1 (Java 25) ne dépend pas du point 1 et peut être livré seul si le changement du sprint 1 tarde.

## Questions ouvertes

Aucune.
