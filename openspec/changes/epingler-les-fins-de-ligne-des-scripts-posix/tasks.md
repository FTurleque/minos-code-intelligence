# Tasks

## 1. Gate des fins de ligne (AUD-DEP-02)

- [ ] 1.1 AUD-DEP-02 — Rouge : écrire `scripts/quality/check-posix-script-eol.py` (lecture par `git ls-files --eol -z`, scripts `*.sh` et fichiers à `#!` d'un interpréteur POSIX, fichier de propriétés du wrapper, échec fermé) ; vérifier que `python scripts/quality/check-posix-script-eol.py` échoue au HEAD en nommant les treize `*.sh` et `mvnw` (`controles-de-fusion` › Un script POSIX est extrait en LF quel que soit le réglage du clone)
- [ ] 1.2 AUD-DEP-02 — `--self-test` sur dépôts Git temporaires : script sans attribut, script à `#!` sans extension, script indexé en CRLF, dépôt sans script, dossier hors dépôt, cas conforme ; vérifier par `python scripts/quality/check-posix-script-eol.py --self-test` ; l'option est déclarée sous la forme littérale `add_argument("--self-test"`, que `check-ci-wiring.py` reconnaît : dès cette tâche, `check-ci-wiring.py` et `run_gates.py` sont rouges jusqu'au câblage dans `invariants` (rouge attendu) (tâche 2.4) (`controles-de-fusion` › Le contrôle des fins de ligne échoue fermé)

## 2. Attributs et renormalisation (AUD-DEP-02)

- [ ] 2.1 AUD-DEP-02 — Correction : ajouter à `.gitattributes` `*.sh text eol=lf`, `mvnw text eol=lf`, `.mvn/wrapper/maven-wrapper.properties text eol=lf`, `*.cmd text eol=crlf`, avec leur commentaire ; vérifier que 1.1 passe
- [ ] 2.2 AUD-DEP-02 — `git add --renormalize -- mvnw.cmd` ; vérifier que `git status --porcelain` ne liste que `.gitattributes`, `mvnw.cmd` et les fichiers du gate, que `git ls-files --eol` ne compte plus aucun fichier visé en `i/crlf`, et que `./mvnw.cmd -v` répond ; rejouer `python scripts/quality/check-toolchain-baseline.py`
- [ ] 2.3 AUD-DEP-02 — Preuve : cloner le dépôt local avec `git -c core.autocrlf=true clone` dans le scratchpad de la session, constater `i/lf w/lf attr/text eol=lf` pour `*.sh` et `mvnw`, puis supprimer le clone
- [ ] 2.4 AUD-DEP-02 — Câblage : constater que `python scripts/quality/check-ci-wiring.py` échoue depuis la tâche 1.2 et tant que `check-posix-script-eol.py --self-test` n'est pas lancé par `invariants`, puis ajouter les deux étapes à `.github/workflows/pr-ci.yml` ; rejouer `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/test_check_ci_wiring.py`, `python scripts/quality/check-workflow-pins.py`

## 3. Poste et documentation (AUD-DEP-02)

- [ ] 3.1 AUD-DEP-02 — **(manuelle)** Remettre à niveau le clone du mainteneur et chaque worktree (suppression puis `git checkout --` des chemins visés, décision D4) ; vérifier `w/lf` par `git ls-files --eol '*.sh' mvnw`, puis `python scripts/dev/test_start_test_postgres.py`, `python scripts/deploy/test_cgroup_root_validation.py` et `python .claude/scripts/run_gates.py` verts
- [ ] 3.2 AUD-DEP-02 — **(manuelle)** Sous WSL2, depuis la copie Windows remise à niveau : `./mvnw -v` et `bash -n scripts/ci/qualify-linux-offline-maven-index.sh` sans erreur de fin de ligne
- [ ] 3.3 AUD-DEP-02 — Documentation : `docs/developer/testing.md` ou `CONTRIBUTING.md` (remise à niveau d'un clone existant, pourquoi), `docs/developer/quality-gates.md` (gate, ce qu'il ne prouve pas : la copie de travail d'un clone ancien), `docs/architecture/diagrams/capacites/controles-de-fusion.md` ; vérifier par `python scripts/docs/check-current-docs.py`

## 4. Clôture

- [ ] 4.1 Mettre à jour la matrice de traçabilité du design ; nommer les preuves manuelles (3.1, 3.2) ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 4.2 Vérifier le dossier durable `controles-de-fusion` par `python scripts/docs/check-capability-dossiers.py`
- [ ] 4.3 Valider les spécifications par `openspec validate --all --strict`
- [ ] 4.4 Lister les documents à mettre à jour avant archivage (`docs/developer/testing.md`, `quality-gates.md`, `SUIVI.md` de l'audit du 2026-11-15) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive epingler-les-fins-de-ligne-des-scripts-posix -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
