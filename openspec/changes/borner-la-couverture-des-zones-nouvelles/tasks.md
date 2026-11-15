# Tasks

## 1. Scopes JaCoCo des zones nouvelles (AUD-TST-01)

- [ ] 1.1 AUD-TST-01 — Mesure : télécharger le rapport agrégé (`target/site/jacoco-aggregate/jacoco.xml`) des jobs `Verify (ubuntu-24.04)` et `Verify (windows-2022)` d'un même run `pr-ci.yml` de `main` ; relever lignes et branches de chaque préfixe de la décision D3, de `RequestGuard`, `SessionToken`, `ListeningPolicy` et `QueryViewMemoryBudget`, et du scope `storage-codec-and-state` avec cette dernière ; vérifier que chaque préfixe désigne au moins une classe mesurée (écart E5) ; noter run, commit et mesures dans le design
- [ ] 1.2 AUD-TST-01 — Rouge : ajouter à `check-jacoco.py --self-test` les cas « table réelle : `explorer-surface`, `wsl-lane`, `semantic-projection` et les quatre planchers de classe sont déclarés », « table privée d'un scope : refus » et « classe de garde sous son plancher dans un scope conforme : échec » ; vérifier que `python scripts/quality/check-jacoco.py --self-test` échoue sur le premier (`controles-de-fusion` › Les zones créées après la mesure de référence sont sous un scope nominatif ; La garde de la surface HTTP locale a un plancher de classe)
- [ ] 1.3 AUD-TST-01 — Correction : déclarer les trois scopes dans `SCOPES` (plancher de scope = mesure moins 2 points, dictionnaire `linux` / `windows` pour `wsl-lane` et pour tout écart de plus de 2 points entre systèmes) ; inscrire `RequestGuard`, `SessionToken` et `ListeningPolicy` **dans `prefixes` et dans `prefixMinimums`** (plancher de classe = nombre unique, la plus basse des deux mesures moins environ 5 points) ; écrire en commentaire le run, le commit et les mesures ; vérifier que `--self-test` passe
- [ ] 1.4 AUD-TST-01 — Correction : ajouter `com/minos/storage/codec/QueryViewMemoryBudget` aux préfixes et aux `prefixMinimums` de `storage-codec-and-state` ; écrire dans le commentaire la mesure recalculée du scope ; ne baisser son plancher que si la mesure l'impose, raison écrite (`controles-de-fusion` › Le budget mémoire des vues a un plancher de classe)
- [ ] 1.5 AUD-TST-01 — Preuve : ajouter `target/m21-quality/**` aux chemins de l'artefact `pr-validation` de `.github/workflows/pr-ci.yml` et rejouer `python scripts/quality/check-workflow-pins.py`, `python scripts/quality/check-ci-wiring.py` ; `python .claude/scripts/run_gates.py` vert (dont `check-single-execution.py`, les gates `check-*-consistency.py`, `scripts/docs/check-current-docs.py` et `scripts/remediation/check-p0-p2.py`, qui lisent `check-jacoco.py`) ; sur la PR, le journal des deux jobs `Verify` porte `JaCoCo explorer-surface: PASS`, `JaCoCo wsl-lane: PASS`, `JaCoCo semantic-projection: PASS`, et `jacoco-gate.json` de l'artefact porte la mesure et le plancher de chaque classe
- [ ] 1.6 AUD-TST-01 — Documentation : section « JaCoCo » de `docs/developer/quality-gates.md` (nouveaux scopes et classes plancherisées ; les règles « moins 2 points » et « moins environ 5 points » sont inchangées) et `docs/architecture/diagrams/capacites/controles-de-fusion.md` (trois exigences, écarts) ; vérifier par `python scripts/docs/check-current-docs.py` et `python scripts/docs/check-capability-dossiers.py`

## 2. Clôture

- [ ] 2.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → décision → tâche → preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 2.2 Vérifier le dossier durable `controles-de-fusion` par `python scripts/docs/check-capability-dossiers.py`
- [ ] 2.3 Valider les spécifications par `openspec validate --all --strict`
- [ ] 2.4 Lister les documents à mettre à jour avant archivage (`docs/developer/quality-gates.md`, `docs/project/audits/2026-11-15/SUIVI.md`, STATUS si la liste des scopes y figure) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive borner-la-couverture-des-zones-nouvelles -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
- Bénéficiaires : sprints 3, 4, 5 et 6 de l'audit du 2026-11-15, dont les corrections sont jugées par ces scopes.
