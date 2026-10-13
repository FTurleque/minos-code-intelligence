# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les deux lots (1 : AUD-DEP-02, 2 : AUD-DEP-10) touchent le même bloc du Dockerfile : les faire dans la même PR ou dans cet ordre.

## 1. Node maintenu dans l'image (AUD-DEP-02)

- [ ] 1.1 [AUD-DEP-02] Mesurer : version Node 24 LTS retenue (celle de `windows-x64` si l'archive `linux-x64` existe), taille et SHA-256 de l'archive, fin de support de la ligne. Consigner source et valeurs dans `design.md`.
- [ ] 1.2 [AUD-DEP-02] Qualifier **avant** de modifier le catalogue : construire l'image avec `--build-arg NODE_VERSION=… --build-arg NODE_LINUX_SHA256=…` et rejouer `scripts/ci/qualify-docker-release.sh` (fixtures TypeScript et Python). Consigner le résultat par provider. **Décision à obtenir si scip-python échoue** : D1 (dérogation datée au registre des risques).
- [ ] 1.3 [AUD-DEP-02] Rouge : cas d'auto-test de `scripts/quality/test_check_tools_manifest.py` « majeurs Node différents entre plateformes » (refusé) et « même majeur » (accepté). **Preuve du rouge : le catalogue réel échoue avec la nouvelle règle.** Puis : entrée `linux-x64` de `embedded-tools.json` (`version`, `sha256`, `sizeBytes`, `dockerArgs`), `python scripts/release/sync-tools-manifest.py`, `ARG` du Dockerfile, commentaire `:89-90`, retrait de la limitation `:227` (D4). Preuve : `python scripts/quality/check-tools-manifest.py` vert, auto-test vert.

## 2. Inventaire relu au build (AUD-DEP-10)

- [ ] 2.1 [AUD-DEP-10] Rouge : cas d'auto-test « littéral de version dans le bloc d'inventaire » (refusé) et « valeur issue d'une variable » (accepté). **Preuve du rouge : le Dockerfile réel échoue (trois littéraux).**
- [ ] 2.2 [AUD-DEP-10] `Dockerfile.mcp.release` : lire les quatre versions sur les binaires (D2), échec sur valeur vide ; règle de `check-tools-manifest.py` (D3). Preuve : gate et auto-test verts ; `./mvnw -B -ntp -pl minos-app -am test "-Dtest=M29Docker*"` vert.
- [ ] 2.3 [AUD-DEP-10] `scripts/ci/qualify-docker-release.sh` : comparer chaque version de l'inventaire à la sortie du binaire dans le conteneur, échec nommé. Preuve locale si Docker est disponible ; sinon dire que la preuve est celle de 3.2.

## 3. Preuve sur l'image

- [ ] 3.1 [AUD-DEP-02, AUD-DEP-10] Rejouer `python scripts/quality/check-image-pins.py`, `python scripts/quality/check-ci-wiring.py`, `python .claude/scripts/run_gates.py --fast`.
- [ ] 3.2 **(manuelle)** [AUD-DEP-02, AUD-DEP-10] À la PR, lancer `docker-release-validation.yml` (le gate « Provider-complete image exact-head » porte `paths-ignore` et n'est pas exigé) ; relever dans le journal les versions de l'inventaire et la comparaison aux binaires ; noter le run id dans `SUIVI.md`.

## 4. Clôture

- [ ] 4.1 Amender la « Mise en œuvre » de l'ADR 0040 (Node par plateforme) ; mettre à jour `docs/user/docker-runtime.md` si la version de Node y figure ; rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-02, AUD-DEP-10 : statut, décision D1, mesures, run id).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, ADR 0040, registre des risques si dérogation.
