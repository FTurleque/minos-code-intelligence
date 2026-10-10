# Proposal

## Why

L'audit du 10 octobre 2026 (`docs/audit/2026-10-10/`, sprint 1 « Remettre les dispositifs de mesure en marche ») constate que les contrôles de MINOS existent mais ne s'imposent pas :

- **AUD-DEP-09** : les gates statiques (`Static invariants (single run)`), Gitleaks et la CI du plugin IntelliJ ne sont pas des checks exigés par le ruleset. La lecture du ruleset réel (10/10/2026, `gh api`, lecture seule) corrige l'hypothèse de l'audit : `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan` et `SonarCloud Code Analysis` sont **déjà** exigés ; il manque trois contextes.
- **AUD-TST-07** : l'auto-test de `check-jacoco.py` (8 scénarios) n'est exécuté par aucun workflow. L'analyse en a trouvé un second : l'auto-test de `check-milestone-artifact-references.py`, que l'ADR 0043 § 4 exige, n'est pas non plus rejoué.
- **AUD-TST-08** : le scope `m29-backend-routing` lit `target/site/jacoco/jacoco.xml` parce que `minos-app` redirige son `<directory>` vers le `target/` racine ; ce couplage n'est ni déclaré ni testé.
- **AUD-TST-13** : la CI du plugin ne se déclenche pas quand change le code qui produit le JSON qu'il consomme, et, étant filtrée par chemins, elle ne peut pas devenir un check exigé sans bloquer les PR qui ne la touchent pas.

Sans ce changement, un gate rouge, un auto-test absent ou une CVE remontée par OSV peut rester consultatif, et les sprints suivants (TST-01, TST-02, QUA-01) s'appuieraient sur un dispositif qu'aucune règle ne garde allumé.

## What Changes

- **Câblage vérifié** : nouveau gate `scripts/quality/check-ci-wiring.py` (job `invariants`, avec auto-test) qui échoue si (a) un contexte de la liste versionnée `.github/required-checks.json` ne correspond à aucun job réel, ou à un job d'un workflow que ses filtres de chemins empêchent de rendre un verdict ; (b) un auto-test de gate (`scripts/**/test_*.py`, ou script exposant `--self-test`) n'est exécuté par aucune étape du job `invariants`.
- **Écart avec le ruleset mesurable à la demande** : `scripts/quality/verify-ruleset.py`, lancé à la main (`gh api`), compare le ruleset réel à `.github/required-checks.json`. Il n'est pas exécuté en CI. Son moteur de comparaison est testé hors ligne sur des charges JSON.
- **Auto-tests rejoués** : étapes `check-jacoco.py --self-test` et `check-milestone-artifact-references.py --self-test` dans `invariants`. `check-single-execution.py` (qui compterait une seconde exécution JaCoCo) et `check-audit-remediation-v2.py` (qui interdit le jeton `check-jacoco.py` dans le job) sont corrigés pour distinguer une exécution de gate d'un auto-test, avec leurs auto-tests.
- **Plugin IntelliJ rendu exigible** : `intellij-plugin.yml` n'est plus filtré par chemins au niveau du workflow. Un job `changes` calcule la portée (script `scripts/ci/plugin-gate.py`, testé), les deux jobs existants ne s'exécutent que si le plugin est touché, et un job `IntelliJ plugin (gate)` s'exécute toujours et ne réussit que si rien de ce qui devait tourner n'a échoué. La liste de portée ajoute `minos-application/.../output/**`, `minos-domain/**` et les goldens de caractérisation (AUD-TST-13).
- **Rapport JaCoCo dérivé du POM** : `check-jacoco.py` détermine le rapport d'un scope depuis le `<directory>` du module qui le produit, au lieu d'un chemin écrit en dur (AUD-TST-08). La redirection de `minos-app` est conservée.
- **Documentation** : `docs/developer/quality-gates.md` (liste des contrôles exigés, procédure du ruleset) ; mention « non exigé, audit G6 » retirée du nom d'étape de `pr-ci.yml` et de la docstring de `check-partial-result-consumers.py`.

## Capabilities

### New Capabilities

- `controles-de-fusion` : ce qui rend un contrôle de qualité opposable à une fusion (liste versionnée des checks exigés, câblage vérifié, auto-tests rejoués, plugin toujours évalué, rapport de couverture du bon module). Capacité nouvelle plutôt qu'un amendement d'`audit-qualite-code` : cette dernière a pour objet l'audit **à la demande** et « sans effet sur le build habituel » (SpotBugs, PIT), c'est-à-dire l'inverse de ce qui est spécifié ici. Les exigences de l'autre changement actif (`etendre-audit-outille-a-tout-le-perimetre`) restent intactes.

### Modified Capabilities

(aucune)

## Hors périmètre

- La **configuration du ruleset GitHub**, faite hors du dépôt : tâche manuelle (section 6 de `tasks.md`), précédée d'un premier run des nouveaux checks.
- Rendre bloquants SpotBugs, PIT, Dependency-Check : décision réservée par `etendre-audit-outille-a-tout-le-perimetre`.
- Les autres workflows filtrés par chemins (`windows-installer`, `windows-in-place-upgrade`, `docker-release-validation`) : non rendus exigibles ici.
- AUD-TST-01 et AUD-TST-02 (plancher global et classes du cœur hors scope du gate JaCoCo), AUD-QUA-01 (copie du lanceur dans le plugin) : bénéficiaires du sprint, traités plus tard.
- Aucun code de production, aucun test Java.

## Impact

- **Modules du reactor touchés** : aucun (scripts, workflows, documentation).
- **Surfaces publiques impactées** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés). Le comportement du plugin n'est pas modifié ; seule sa CI change de déclencheur.
- **ADR** : aucun nouvel ADR ni amendement. S'appuie sur l'ADR 0043 (§ 3 : un gate s'ancre sur une structure, pas sur une phrase ; § 4 : un garde-fou a son auto-test) et l'ADR 0022 (direction des dépendances inchangée).
- **CI** : `pr-ci.yml` (deux étapes ajoutées, un nom d'étape modifié), `intellij-plugin.yml` (restructuré), nouveau `.github/required-checks.json`.
- **Gates à rejouer** : `check-single-execution.py` et son auto-test, `check-workflow-pins.py`, `check-current-docs.py` (jetons du workflow plugin), `check-audit-remediation-v2.py`, `check-p0-p2.py`, `check-milestone-artifact-references.py`, `check-jacoco.py --self-test`.
- **Plateformes** : scripts Python indépendants de l'OS ; la CI Windows (`Verify (windows-2022)`) n'est pas modifiée.
