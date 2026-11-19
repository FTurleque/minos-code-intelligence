# Tasks

## 1. Séparer la compilation de l'analyse et garder la séparation (AUD-DEP-05)

- [x] 1.1 AUD-DEP-05 — Rouge : ajouter à `scripts/quality/test_check_workflow_pins.py` les cas « étape d'un workflow `pull_request` dont l'`env:` d'étape référence `secrets.` et qui lance `./mvnw … test-compile` », « étape hors liste avec secret et script de `scripts/` », « entrée de liste périmée », « `SONAR_TOKEN` dans un `env:` de job ou de workflow », « `DOCKERHUB_USERNAME` dans un `env:` de job admis », « workflow sans `pull_request` non signalé » (`controles-de-fusion › Un secret n'accompagne pas la compilation du code d'une pull request`) ; vérifier qu'ils échouent tant que les deux contrôles n'existent pas, puis ajouter les contrôles et la table `SECRET_WITH_REPOSITORY_CODE_KEPT` (vide) à `check-workflow-pins.py` et vérifier que le gate échoue sur le dépôt réel en nommant `pr-ci.yml`, job `sonar`, et n'échoue pas sur les `env:` de job de `pr-ci.yml:289-290` et `release-promotion-gate.yml:41-42`
- [ ] 1.2 AUD-DEP-05 — Mesure **(manuelle, non faite : la forme `install -DskipTests` du design est appliquée sans mesure : exige une PR d'essai et la CI, à la demande du propriétaire)**, avant de figer la forme : sur une branche d'essai, lancer le job `sonar` avec l'analyse dans une invocation séparée, d'abord après `test-compile`, puis après `install -DskipTests -Djacoco.skip=true` ; relever laquelle des deux résout les 14 modules et importe autant de rapports que de modules Linux ; essayer `-o` sur l'étape d'analyse ; consigner les trois résultats dans le design (D1) et retenir la forme la plus étroite qui passe — **Reste au 2026-11-19** : archivé sans cette tâche, voir `docs/project/audits/2026-11-15/SUIVI.md`.
- [x] 1.3 AUD-DEP-05 — Correction : scinder l'étape du job `sonar` de `.github/workflows/pr-ci.yml` selon la forme mesurée en 1.2 (build et résolution du plugin sans secret ; analyse seule avec `SONAR_TOKEN`, sans phase) ; si `install` est retenu, retirer les artefacts `com.minos` du dépôt Maven local avant la sauvegarde du cache ; inscrire l'étape d'analyse dans la table avec sa raison (but `sonar` lancé par `mvnw`, risque résiduel E3). Vérifier : `python scripts/quality/check-workflow-pins.py` rend 0 et affiche la liste ; rejouer `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-toolchain-baseline.py`, `python .claude/scripts/run_gates.py`
- [ ] 1.4 AUD-DEP-05 — Preuve **(manuelle : exige la PR du changement et la CI, à la demande du propriétaire)** : le job `SonarCloud analysis (Linux and Windows coverage)` est vert, le journal montre autant de modules importés que de rapports Linux, et le check `SonarCloud Code Analysis` rend son verdict — **Reste au 2026-11-19** : archivé sans cette tâche, voir `docs/project/audits/2026-11-15/SUIVI.md`.
- [x] 1.5 AUD-DEP-05 — Documentation : `docs/developer/supply-chain.md` (portée et type du jeton SonarCloud, liste fermée, règle de l'`env:` de job, risque résiduel), `docs/developer/quality-gates.md` (nouveaux contrôles du gate), `.claude/rules/workflows-ci.md` ; vérifier par `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`

## 2. Nature du jeton

- [ ] 2.1 AUD-DEP-05 — **(manuelle)** Vérifier dans SonarCloud que `SONAR_TOKEN` (secret du dépôt et secret Dependabot) est un jeton d'analyse limité au projet ; s'il s'agit d'un jeton d'utilisateur, le remplacer et le révoquer ; consigner le type (jamais la valeur) dans `docs/developer/supply-chain.md` — **Reste au 2026-11-19** : archivé sans cette tâche, voir `docs/project/audits/2026-11-15/SUIVI.md`.

## 3. Clôture

- [x] 3.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [x] 3.2 **(à faire à l'archivage : le gate refuse une ligne dont l'exigence n'est pas encore dans `openspec/specs/`)** Mettre à jour le dossier durable `docs/architecture/diagrams/capacites/controles-de-fusion.md` ; vérifier par `python scripts/docs/check-capability-dossiers.py` — lignes de traçabilité ajoutées à l'archivage, `check-capability-dossiers.py` vert.
- [x] 3.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [x] 3.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, `docs/developer/supply-chain.md`, `SUIVI.md` de l'audit du 2026-11-15) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive separer-le-jeton-sonarcloud-de-la-compilation-de-la-pr -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
