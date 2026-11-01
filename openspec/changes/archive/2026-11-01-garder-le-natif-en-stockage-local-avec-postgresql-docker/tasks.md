# Tasks

Ordre : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Constat : essai d'installation 1.3.0 du 31 octobre 2026 (sans identifiant d'audit).

## 1. Natif en stockage local à l'installation

- [x] 1.1 Rouge : `scripts/install/test_runtime_settings_storage.py` exécute le vrai `configure-runtime-settings.ps1` (Windows PowerShell 5.1 et `pwsh`) sur un dossier temporaire avec `-StorageBackend postgresql -McpBackend docker -ManagedDockerPostgres` ; attendu : `minos.storage.backend=local`, aucune clé `minos.postgres.`, état `storageBackend=postgresql` et `managedDockerPostgres=true`. **Preuve du rouge : au HEAD, la configuration native contient `minos.storage.backend=postgresql` et `minos.postgres.managed=true`.**
- [x] 1.2 `configure-runtime-settings.ps1` : `-ManagedDockerPostgres` écrit le stockage local (D2). Câbler le test dans `.github/workflows/pr-ci.yml` (déclaré sauté dans `invariants`, exécuté dans `Verify (windows-2022)`). Preuve : test vert ; `python scripts/remediation/check-post-mne.py`, `python scripts/quality/check-twin-script-functions.py`, `python scripts/quality/check-ci-wiring.py` verts.

## 2. Refus résiduel actionnable

- [x] 2.1 Rouge : dans `StorageBackendConfigurationTest`, PostgreSQL géré sans URL en natif → `IOException` nommant `MINOS_RUNTIME_LOCATION=docker` et `minos.storage.backend=local` ; même configuration en Docker → résolue ; géré avec URL de bouclage en natif → résolue ; emplacement inconnu → refusé. Adapter les cas qui résolvaient un PostgreSQL géré sans URL (`resolvesManagedPostgresqlFlagStrictly`, test du BOM) en leur donnant une URL. **Preuve du rouge : au HEAD, la résolution réussit.**
- [x] 2.2 `StorageBackendConfiguration.resolve(MinosRuntimeSettings)` : refus selon D3. Preuve : `./mvnw -B -ntp -pl minos-engine -am test` ; `python scripts/architecture/check-module-boundaries.py` ; `python scripts/remediation/check-post-mne.py`.

## 3. Preuve réelle

- [x] 3.1 **(manuelle)** Sur une installation Windows avec MCP Docker et PostgreSQL géré : `minos.cmd providers` réussit après réinstallation ; sur une configuration 1.3.0 non réinstallée, le refus nomme le runtime Docker. **Preuve (2026-11-01)**, poste Windows 10, installeur construit depuis `develop` (`98dfbcca`), MCP Docker avec PostgreSQL et Ollama gérés : après réinstallation, `config/minos.properties` du `MINOS_HOME` natif porte `minos.storage.backend=local` et `minos.cmd providers` sort en code 0 (il échouait sur `missing required PostgreSQL setting: MINOS_POSTGRES_URL` avec la 1.3.0 précédente) ; le serveur MCP Docker tourne sur `1.3.0-98dfbcca5009` avec PostgreSQL géré. Sur un `MINOS_HOME` de test dont la configuration est celle d'une installation non réinstallée (`minos.storage.backend=postgresql`, `minos.postgres.managed=true`), `minos.cmd providers` sort en code 1 sur « managed PostgreSQL storage is reachable only from the MINOS Docker runtime (MINOS_RUNTIME_LOCATION=docker) … rerun the MINOS installer) to use local storage ».

## 4. Clôture

- [x] 4.1 `docs/user/docker-runtime.md` (contenu des deux `MINOS_HOME`, comportement natif, remède) et `docs/user/troubleshooting.md` (message de refus). Rejouer `python scripts/docs/check-current-docs.py`.
- [x] 4.2 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md` si l'état livré le mentionne, `docs/user/release-notes/` de la prochaine version.
