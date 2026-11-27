# Tasks

Changement indépendant des quatre autres du sprint 12. Les trois sections touchent des fichiers disjoints (`check-toolchain-baseline.py`, `check-tools-manifest.py`, `check-posix-script-eol.py` et leurs auto-tests) ; chacune peut être sortie dans un changement à part. Ajout de lignes aux dossiers `socle-d-execution.md` et `controles-de-fusion.md` à l'archivage ; `aligner-la-documentation-courante-apres-le-sprint-11` ajoute aussi une ligne à `controles-de-fusion.md` : le second archivé rebase.

Mutations : sur des copies du dépôt dans le scratchpad, jamais sur le dépôt. Ni `mvnw`, ni `docker`, ni réseau.

## 1. Clés de somme du wrapper Maven (AUD-DEP-16)

- [ ] 1.1 AUD-DEP-16 — Rouge (état actuel) : rejouer sur des copies les trois mutations du design (`distributionSha256Sum =`, `:`, clé indentée) ; vérifier rc=0 pour chacune. Exigence : socle-d-execution › La somme du wrapper Maven est écrite sous la forme que lit mvnw.
- [ ] 1.2 AUD-DEP-16 — Rouge (auto-test) : dans `scripts/quality/test_check_toolchain_baseline.py`, à côté de `test_a_doubled_sum_is_refused` (`:215`), cas refusés `Sum =`, `Sum:`, clé indentée, et `distributionUrl :…` pour le wrapper Maven ; cas accepté `distributionSha256Sum= <somme>` (Maven) et `distributionUrl=https\://…` (Gradle). Vérifier : les cas refusés échouent avant correction.
- [ ] 1.3 AUD-DEP-16 — Correction (D1 a) : dans `scripts/quality/check-toolchain-baseline.py`, pour `MAVEN_WRAPPER` seulement, refuser toute ligne non commentaire où `distributionUrl` ou `distributionSha256Sum` apparaît comme clé sans être en tête de ligne suivie aussitôt de `=`, message avec fichier, ligne et forme attendue ; garder `properties_of` pour Gradle. Docstring (anglais) : l'analyseur de `mvnw` comme référence.
- [ ] 1.4 AUD-DEP-16 — Preuve : `python scripts/quality/test_check_toolchain_baseline.py -v` vert ; gate vert sur le dépôt ; rc=1 sur chaque copie de 1.1 avec la ligne nommée.

## 2. Contrôle des archives de l'image (AUD-DEP-17)

- [ ] 2.1 AUD-DEP-17 — Rouge (état actuel) : rejouer les deux mutations du design (contrôle Node après `tar`, `set +o errexit` Maven) et un contrôle Coursier sur `/tmp/other.gz` ; vérifier rc=0. Relever pour chacun des six `RUN` la cible du `curl` (`-o`, `-Lo`, `-fsSLo`, ligne de continuation du `curl` Maven) et la consigner dans la PR. Exigence : socle-d-execution › Le contrôle d'une archive de l'image porte sur le fichier téléchargé avant tout usage.
- [ ] 2.2 AUD-DEP-17 — Rouge (auto-test) : dans `scripts/quality/test_check_tools_manifest.py`, classe `ArchiveCheckTest` (`:405`), cas refusés : `cd /tmp; tar -xJf "${archive}"` avant le contrôle, `set +o errexit`, contrôle d'un fichier autre que la cible du `curl` ; cas accepté : `cd` avant le `curl`, contrôle sur la cible. `ShippedTreeTest` (`:478`) : contrôle Node déplacé après `tar` sur le Dockerfile réel. Vérifier : échec avant correction ; le cas existant `|| true` reste vert.
- [ ] 2.3 AUD-DEP-17 — Correction (D2 a) : dans `scripts/quality/check-tools-manifest.py`, `use_before_check_failures` compare aussi le nom de base du fichier (variable shell comprise), quand aucun `/` ne le précède (la boucle d'URL du `RUN` Maven, `Dockerfile.mcp.release:165-167`, doit rester acceptée ; cas accepté dans l'auto-test) ; `DISARMING` reconnaît `set +o errexit` ; une fonction relève les cibles `-o` des `curl` du `RUN` (commande continuée comprise, `shell_commands`) et refuse un contrôle qui n'en vise aucune. Docstring : limites restantes. Vérifier : `python scripts/quality/test_check_tools_manifest.py -v` vert.
- [ ] 2.4 AUD-DEP-17 — Preuve : gate vert sur le dépôt, six `ARG` reconnus ; rc=1 sur chaque copie de 2.1, `ARG` et ligne nommés. Gates : `python scripts/release/sync-tools-manifest.py` (sans `--write`), `python scripts/release/test_build_embedded_tools.py`, `python scripts/quality/check-image-pins.py`, `python scripts/ci/test_docker_gate.py`.

## 3. Épinglage des fichiers batch (AUD-DEP-18)

- [ ] 3.1 AUD-DEP-18 — Rouge (auto-test) : dans l'auto-test de `scripts/quality/check-posix-script-eol.py` (`--self-test`, table des cas `:222-258`), cas refusés : `*.cmd text` sans `eol=crlf` avec `mvnw.cmd` en `i/lf` ; `*.bat binary` avec `gradlew.bat` ; `*.cmd text eol=lf` (pin inversé). Cas accepté : `*.cmd text eol=crlf`. Garder vert le cas « CRLF file without eol attribute » (fichier `notes.txt`, hors règle). Vérifier : les cas refusés échouent avant correction. Exigence : controles-de-fusion › Les fichiers batch suivis ont leur fin de ligne épinglée.
- [ ] 3.2 AUD-DEP-18 — Correction (D3 a) : dans `check_counts`, exiger `eol=crlf` pour chaque fichier suivi en `.cmd` ou `.bat` (casse ignorée, hors `fixtures/`), message avec le fichier et `*.cmd text eol=crlf` ou `*.bat text eol=crlf` ; compter ces fichiers dans la ligne de succès. Docstring mise à jour (anglais, nom et libellé du gate conservés).
- [ ] 3.3 AUD-DEP-18 — Preuve : `python scripts/quality/check-posix-script-eol.py --self-test` et le gate verts sur le dépôt ; `git ls-files --eol -- '*.cmd' '*.bat'` relevé dans la PR.
- [ ] 3.4 AUD-DEP-16, AUD-DEP-17, AUD-DEP-18 — Gates communs : `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.

## 4. Documentation et ADR

- [ ] 4.1 AUD-DEP-16, AUD-DEP-17, AUD-DEP-18 — `docs/developer/quality-gates.md` : une phrase par gate sur les formes refusées ; `docs/developer/TOOLCHAIN_POLICY.md` si elle décrit la ligne de somme. Vérifier : `python scripts/docs/check-current-docs.py` et `python scripts/quality/check-toolchain-baseline.py` verts, liens vérifiés.
- [ ] 4.2 AUD-DEP-16, AUD-DEP-17 — Après la fusion : compléter « Compliance and Enforcement » de GEN-0077 (règle 13) et de BE-0065 (`check-tools-manifest.py`), décision inchangée. Vérifier : `archgate check` vert si l'outil est disponible, sinon le dire dans la PR.

## 5. Clôture

- [ ] 5.1 AUD-DEP-16, AUD-DEP-17, AUD-DEP-18 — Mettre à jour la matrice de traçabilité du design (preuves exécutées ; non rejoués : `mvnw`, `mvnw.cmd`, construction Docker).
- [ ] 5.2 AUD-DEP-16, AUD-DEP-17, AUD-DEP-18 — **(manuelle)** Sur la PR, constater que le job `invariants` exécute les trois gates et leurs auto-tests, et que `Verify (ubuntu-24.04)` et `Verify (windows-2022)` gardent `M29DockerAdministrationContractTest` vert (Dockerfile inchangé).
- [ ] 5.3 AUD-DEP-16, AUD-DEP-17, AUD-DEP-18 — Valider : `openspec validate --all --strict` vert ; après archivage, lignes des exigences ajoutées dans `docs/architecture/diagrams/capacites/socle-d-execution.md` et `controles-de-fusion.md` ; `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 5.4 AUD-DEP-16, AUD-DEP-17, AUD-DEP-18 — Documents à mettre à jour avant archivage : `quality-gates.md` (4.1), ADR (4.2), dossiers (5.3), lignes AUD-DEP-16 à 18 du suivi de l'audit (coordinateur, après fusion). Vérifier : chacun est fait ou nommé comme reste dans la PR.
