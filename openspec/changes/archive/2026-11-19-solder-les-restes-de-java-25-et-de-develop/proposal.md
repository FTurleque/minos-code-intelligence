# Proposal

## Why

Deux constats de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, Faibles, confirmés au HEAD `89be3fb4`) décrivent les restes de deux transitions closes :

- **AUD-DEP-04** : l'exception « Java 25 pour le candidat A » n'a plus d'objet. `main` compile pour Java 21 (`pom.xml:207`, borne `[21,22)`), le candidat A d'une PR est `main`, et pourtant deux workflows installent encore Temurin 25 à chaque exécution ; `check-toolchain-baseline.py` garde l'exception ouverte, donc tolère qu'un workflow réinstalle Java 25.
- **AUD-DEP-06** : la branche `develop` est retirée (2026-11-13) et plus aucun déclencheur, gate ni Dependabot ne la cite, mais la documentation courante nomme encore le ruleset « Protect main & develop », des contrôles « vers `main` et `develop` », et le registre des risques garde ouvert R-25, qui bloque une promotion `develop → main` faute d'un runner auto-hébergé que le dépôt interdit désormais.

## What Changes

- Les deux étapes « previous Java baseline » sont retirées de `release-promotion-gate.yml` et de `docker-upgrade-qualification.yml`.
- `check-toolchain-baseline.py` n'a plus d'exception pour une baseline précédente : tout `java-version:` autre que la baseline, hors liste fermée du plugin, échoue.
- La réserve « Exception Java 25 des workflows de qualification A → B » du suivi de l'audit du 2026-10-10 est close.
- `STATUS.md`, `ROADMAP.md` et `docs/developer/quality/code-audit.md` nomment le ruleset « Protect main » et les seules PR vers `main` ; R-25 est clos ou réécrit au registre des risques.
- `check-current-docs.py` refuse, dans les documents courants (contrôles ajoutés à son auto-test, que lui donne le changement `doter-chaque-gate-d-un-auto-test`), un nom de ruleset absent de `.github/required-checks.json` et un risque ouvert qui cite `develop`.

Aucune rupture de contrat produit.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `socle-d-execution` : une exigence ajoutée (aucune baseline précédente installée par un workflow). L'exigence « La version de Java est énoncée une seule fois et contrôlée partout » n'est pas modifiée : elle ne mentionne pas l'exception.
- `controles-de-fusion` : une exigence ajoutée (les documents courants nomment les rulesets déclarés et ne laissent ouvert aucun risque sur une branche retirée).

## Hors périmètre

- Les relevés datés qui citent `develop` comme un fait de leur époque (sections de sprint de `STATUS.md` « fusionné dans `develop` », mesures de `docs/developer/indexing-and-storage.md` et de `docs/developer/quality/code-audit-couverture.md`, `docs/architecture/SYNTHESE.md`, texte des ADR, `docs/project/history/`, `docs/project/roadmap/M*_EXECUTION.md`, `docs/project/research/`, audits) : règle « liens oui, prose non ».
- Les fixtures des auto-tests de gates (`scripts/quality/test_check_ci_wiring.py`, `test_check_workflow_pins.py`, `test_check_single_execution.py`, `test_check_image_pins.py`) et le commentaire de mesure de `check-jacoco.py:16`.
- Le périmètre de la qualification Docker par PR (AUD-DEP-03, changement `borner-la-qualification-docker-aux-pr-concernees`).
- Toute modification du ruleset GitHub.

## Découpage

Les deux constats sont des nettoyages mécaniques sans décision d'architecture, de taille S, qui touchent la même zone (workflows de qualification, gates de baseline et de documentation, documents courants) et se vérifient par le même jeu de gates statiques : une seule PR `chore/`. AUD-DEP-04 touche `release-promotion-gate.yml`, comme AUD-DEP-03 ; ce changement-ci, sans décision en attente, se fusionne avant lui. Il se fusionne après `doter-chaque-gate-d-un-auto-test` (AUD-TST-09, sprint 1), qui porte `scripts/docs/check-current-docs.py` vers un auto-test `--self-test`.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `main` impose Java 21 | [Existant vérifié] | `pom.xml:207` |
| Deux workflows installent Java 25 | [Existant vérifié] | `.github/workflows/release-promotion-gate.yml:71-79`, `.github/workflows/docker-upgrade-qualification.yml:55-63` |
| Le gate garde l'exception | [Existant vérifié] | `scripts/quality/check-toolchain-baseline.py:106-113`, `:190-203` |
| Aucune release n'impose Java 25 : `v1.3.0` impose `[21,22)`, `v1.2.0` impose `[24,25)` | [Existant vérifié] | `git show v1.3.0:pom.xml` (ligne 214), `git show v1.2.0:pom.xml` (ligne 123) |
| Documents courants qui nomment « Protect main & develop » | [Existant vérifié] | `docs/project/governance/STATUS.md:15`, `docs/project/governance/ROADMAP.md:19` |
| R-25 ouvert sur `develop → main` et un runner auto-hébergé | [Existant vérifié] | `docs/architecture/risks/register.md:22` |
| Le ruleset GitHub se nomme « Protect main » et `develop` est supprimée à distance | [Existant vérifié le 2026-11-13, non revérifié] | tâches 2.1 et 2.2 du changement archivé `2026-11-13-retirer-la-branche-develop` ; à rejouer (tâche manuelle 2.4) |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé. GEN-0077 est **clarifié, sens inchangé** : sa section de conformité nomme la liste `PREVIOUS_JAVA_STEPS` (`docs/architecture/adr/GEN-0077-socle-d-execution-java-21-et-maven.md:104`) et sa règle Archgate compagnon lit `PREVIOUS_JAVA` et `PREVIOUS_JAVA_STEPS` dans le gate (`GEN-0077-socle-d-execution-java-21-et-maven.rules.ts:162`, `:173-176`, `:213`) et échoue (`missingConstant`) si elles manquent. Les deux sont alignés en tâche 1.2. Le flux de branches reste une convention de gouvernance, sans ADR.
- **Gates à rejouer** : `scripts/quality/check-toolchain-baseline.py` et `scripts/quality/test_check_toolchain_baseline.py` (commentaire `:179` et cas `:284-299` à réécrire), `scripts/quality/check-workflow-pins.py`, `scripts/quality/check-ci-wiring.py`, `scripts/remediation/check-audit-remediation-v2.py` (littéraux des deux workflows), `scripts/docs/check-current-docs.py`, `scripts/docs/product-facts.py --check`, `archgate check` (règle compagnon de GEN-0077, modifiée par ce changement), `python .claude/scripts/run_gates.py`.
- **Plateformes** : CI Ubuntu 24.04 pour les workflows ; gates sur Windows et Linux.
- **Documentation durable** : `docs/project/governance/STATUS.md`, `docs/project/governance/ROADMAP.md`, `docs/developer/quality/code-audit.md`, `docs/developer/quality-gates.md`, `docs/architecture/risks/register.md`, `docs/architecture/arc42/11-risques-dette.md` si R-25 y figure, `docs/project/audits/2026-10-10/SUIVI.md`, dossiers `socle-d-execution` et `controles-de-fusion`.
