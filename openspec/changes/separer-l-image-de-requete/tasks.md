# Tasks

Ordre dans chaque section : rouge, correction, preuve. À faire après `migrer-le-socle-vers-java-25` et `aligner-node-et-l-inventaire-de-l-image-docker` (mêmes fichiers). Les `.ps1` restent en CRLF.

## 1. Relevé et preuve préalable (AUD-DEP-15)

- [ ] 1.1 [AUD-DEP-15] Lire en entier `docker/scripts/prod-mcp-release.ps1`, `scripts/install/update-installation.ps1`, `scripts/ci/qualify-docker-upgrade.ps1` ; relever chaque site qui lit ou écrit `MINOS_IMAGE` (liste `fichier:ligne` dans `design.md`). Classer les 7 services (commande réellement exécutée par chacun) selon la règle D2.
- [ ] 1.2 [AUD-DEP-15] Prouver que le plan de requête n'exécute aucun outil de provider : rechercher les lancements de processus atteignables depuis les outils MCP ; exécuter la suite de requêtes de la qualification dans un conteneur `eclipse-temurin:…-jre` avec le seul jar. Consigner le résultat ; tout outil MCP qui échoue est un écart à traiter avant de continuer.
- [ ] 1.3 [AUD-DEP-15] **Décision à obtenir** : D1 (deux cibles d'un Dockerfile, recommandé, ou réutilisation de `Dockerfile.mcp`). Consigner dans `design.md`.

## 2. Deux cibles et classement des services (AUD-DEP-15)

- [ ] 2.1 [AUD-DEP-15] Rouge : tests de contrat de `minos-app` (`M29Docker*`) — la cible de requête ne copie aucun stage de toolchain et n'installe aucun paquet de compilation ; `minos-mcp` désigne l'image de requête dans les deux compose ; chaque service a une image de l'une des deux variables. **Preuve du rouge sur les fichiers actuels.**
- [ ] 2.2 [AUD-DEP-15] `Dockerfile.mcp.release` : stages `query` et `admin` ; `compose-mcp.connected.yaml` et `compose-mcp.prod.yaml` : `MINOS_QUERY_IMAGE`, un commentaire de classement par service. Preuve : `./mvnw -B -ntp -pl minos-app -am test "-Dtest=M29Docker*"` vert ; `python scripts/quality/check-compose-limits.py`, `check-image-pins.py`, `check-tools-manifest.py` verts.
- [ ] 2.3 [AUD-DEP-15] `scripts/ci/qualify-docker-release.sh` : construire les deux cibles, vérifier l'étiquette de commit sur les deux, commandes absentes de l'image de requête, poignée de main et requête MCP (D3). Mesurer la durée de construction avant et après ; la consigner.

## 3. Cycle de vie et mise à niveau (AUD-DEP-15)

- [ ] 3.1 [AUD-DEP-15] Rouge : test du script de cycle de vie (extraction des définitions réelles, modèle `scripts/lib/test_minos_exit_code.py`) — variable d'image de requête absente → échec avant tout `docker`, message qui nomme la commande de mise à jour. Puis `docker/scripts/mcp-lifecycle.ps1` : deux constructions `--target`, écriture des deux variables, refus D2 ; `docker/scripts/verify-run-configurations.ps1`. Câbler le test dans `invariants` (`python scripts/quality/check-ci-wiring.py`).
- [ ] 3.2 [AUD-DEP-15] `scripts/install/update-installation.ps1` : une installation à une image reçoit la seconde ; rejouer `scripts/install/verify-windows-upgrade-transaction.ps1`.
- [ ] 3.3 [AUD-DEP-15] `scripts/ci/qualify-docker-upgrade.ps1` : scénario A (une image) → B (deux images), plan de requête vérifié sur l'image de requête après mise à niveau.
- [ ] 3.4 **(manuelle)** [AUD-DEP-15] À la PR : lancer `docker-release-validation.yml` et `docker-upgrade-qualification.yml` ; noter les run ids et la taille des deux images dans `SUIVI.md`.

## 4. Clôture

- [ ] 4.1 `docs/user/docker-runtime.md` (deux images, variable, migration), note de mise en œuvre de l'ADR 0021 ; rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-15 : statut, décision D1, classement des services, tailles mesurées, observation `HEALTHCHECK`).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/user/docker-runtime.md`, ADR 0021.
