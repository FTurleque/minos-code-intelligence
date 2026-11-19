# Tasks

## 1. Commentaires des fichiers de distribution (AUD-DEP-01)

- [x] 1.1 AUD-DEP-01 — Rouge : ajouter à `M29DockerAdministrationContractTest` (`minos-app`) un cas borné au bloc du service `minos-admin` des deux fichiers compose et au passage nommé de `docker/Dockerfile.mcp.release` : il refuse un commentaire présentant ce service comme lançant des providers, exige la mention du refus de l'indexation, et ne signale pas le commentaire du service `minos-provider-probe` (`compose-mcp.connected.yaml:300`, `compose-mcp.prod.yaml:225`) (`distribution-docker › Les fichiers de distribution Docker ne présentent pas le plan d'administration comme lançant des providers`) ; vérifier qu'il échoue en nommant `docker/compose-mcp.connected.yaml:168` et `docker/compose-mcp.prod.yaml:71`. Chercher d'abord la phrase d'origine dans `scripts/` et `minos-app/src/test/` (gates et tests littéraux)
- [x] 1.2 AUD-DEP-01 — Correction : réécrire le commentaire de `minos-admin` dans les deux compose et le passage « handled by the ephemeral admin plane » du Dockerfile (D1, écart E1), en anglais comme le reste de ces fichiers ; dans chaque compose, garder le commentaire sur la ligne juste au-dessus de `image:` et le faire commencer par `# Administration image:` (contrainte de `M29DockerQueryImageContractTest.java:413-415`) ; vérifier que 1.1 passe et que `M29DockerQueryImageContractTest` et `M29DockerReleaseCiGateContractTest` restent verts (`./mvnw -B -ntp -pl minos-app -am test`) ; rejouer `python scripts/quality/check-compose-limits.py`, `python scripts/quality/check-image-pins.py`, `python scripts/quality/check-toolchain-baseline.py`, `python .claude/scripts/run_gates.py --fast`
- [ ] 1.3 AUD-DEP-01 — Preuve **(manuelle : exige une PR et la CI, à la demande du propriétaire)** : la qualification Docker A → B de la PR est verte (refus de `index` et scip-java `UNSUPPORTED_BY_BACKEND` dans le plan d'administration) — **Reste au 2026-11-19** : archivé sans cette tâche, voir `docs/project/audits/2026-11-15/SUIVI.md`.
- [x] 1.4 AUD-DEP-01 — Documentation : aligner `docs/user/docker-runtime.md` et `docs/developer/remote-worker-sandbox-disposition.md` s'ils décrivent `minos-admin` autrement ; vérifier par `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`

## 2. Toolchains de l'image d'administration (AUD-DEP-01)

- [x] 2.1 AUD-DEP-01 — Lire ce que `tools list`, `tools verify`, `tools verify --all` et `doctor` exécutent dans le service `minos-admin` (en plus de la sonde `minos-provider-probe`, déjà établie) et ce que les tests de contrat Docker affirment sur la présence des toolchains ; consigner les `fichier:ligne` dans le design (section « État vérifié au HEAD ») et lever la question ouverte
- [x] 2.2 AUD-DEP-01 — **(manuelle)** Le propriétaire tranche D2 : garder les toolchains et le lanceur scip-java avec leur raison (outils sondés par `minos-provider-probe`, aucun n'indexe), ou les retirer avec la sonde par un changement distinct — décision du propriétaire du 2026-11-19 : recommandation du design retenue (toolchains gardées avec leur raison).
- [x] 2.3 AUD-DEP-01 — Écrire la raison retenue dans le commentaire de la cible `admin` du Dockerfile et dans `docs/user/docker-runtime.md` ; relire R-13 (le lanceur scip-java hors verrou est exécuté par la sonde, pour sa version) dans `docs/architecture/risks/register.md` ; si le retrait est retenu, ouvrir le changement OpenSpec correspondant (il reprendra SEC-0080 décision 7, R-13 et l'écart E2) et le nommer ici ; vérifier par `python .claude/scripts/run_gates.py --fast`

## 3. Clôture

- [x] 3.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [x] 3.2 **(à faire à l'archivage : le gate refuse une ligne dont l'exigence n'est pas encore dans `openspec/specs/`)** Mettre à jour le dossier durable `docs/architecture/diagrams/capacites/distribution-docker.md` (exigence ajoutée, écart E2) ; vérifier par `python scripts/docs/check-capability-dossiers.py` — lignes de traçabilité ajoutées à l'archivage, `check-capability-dossiers.py` vert.
- [x] 3.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [x] 3.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, `docs/user/docker-runtime.md`, registre des risques, `SUIVI.md` de l'audit du 2026-11-15) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive aligner-les-compose-sur-l-absence-de-provider-en-docker -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
