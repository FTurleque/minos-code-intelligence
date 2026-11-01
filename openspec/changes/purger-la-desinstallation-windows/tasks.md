# Tasks

Ordre : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Aucun code Java de production ni méthode renommée : aucun scope JaCoCo concerné. Gates qui citent les fichiers touchés, à rejouer : `scripts/install/test_mcp_lifecycle_query_image.py`, `scripts/quality/check-twin-script-functions.py`, `scripts/quality/check-partial-result-consumers.py`, `scripts/remediation/check-audit-remediation-v2.py` (`mcp-lifecycle.ps1`) ; `scripts/quality/check-tools-manifest.py`, `scripts/docs/check-current-docs.py` (distribution, gabarit) ; `scripts/quality/check-workflow-pins.py` (`build-windows-installer.ps1`).

## 1. Retrait du projet Compose par étiquette

- [x] 1.1 Rouge : `scripts/install/verify-docker-instance-removal.ps1` (nouveau, ASCII) — faux `docker` sur le `PATH` qui garde l'état en JSON et refuse ce que refuse le démon (volume ou réseau utilisé, conteneur en marche sans `-f`, image utilisée) ; scénarios purge, sans purge, volume refusé, démon arrêté, images avec `-RemoveImages`, script ASCII. **Preuve du rouge : le script de retrait n'existe pas au HEAD.**
- [x] 1.2 `docker/scripts/remove-docker-instance.ps1` (ASCII, Windows PowerShell 5.1) : conteneurs par `com.docker.compose.project`, `stop --time 10` puis `rm -f` ; réseaux par étiquette ; avec `-Purge`, volumes par étiquette et volumes externes `<instance>-postgres-data`, `<instance>-ollama-models` ; `-RemoveImages` ; codes 0/1/3. Preuve : vérificateur vert sous `powershell.exe` 5.1 et `pwsh`.
- [x] 1.3 `docker/scripts/mcp-lifecycle.ps1`, action `Uninstall` : `compose down` avec `--profile postgresql --profile ollama`. Livraison : `build-windows-distribution.ps1` copie le script et exécute `verify-docker-instance-removal.ps1` et `verify-codex-mcp-integration.ps1` ; `build-windows-installer.ps1` exige le script.

## 2. Bloc Codex

- [x] 2.1 Rouge : `scripts/install/verify-codex-mcp-integration.ps1` — bloc adopté (`preexisting`) retiré à la désinstallation ; bloc sans fichier d'état retiré, autres sections et sauvegarde vérifiées ; section `[mcp_servers.minos]` sans marqueurs inchangée. **Preuve du rouge : « Adopted Codex TOML block bounded by the MINOS markers remained after uninstall. »**
- [x] 2.2 `scripts/install/configure-codex-mcp.ps1` : `Remove-ManagedTomlBlock` (sauvegarde, retrait borné par les marqueurs), appelé sans état, en mode `cli` et pour un bloc `preexisting` ; bloc modifié toujours préservé. Preuve : vérificateur vert sous 5.1 et `pwsh`.

## 3. Désinstalleur

- [x] 3.1 Rouge : `minos-app/src/test/java/com/minos/packaging/UninstallCleanupContractTest.java` (ordre `usPostUninstall`, `-Purge` conditionné, `LogScriptOutput`, `WarningRecord`, `UninstallLogging=yes`, `dirifempty`, profils de `compose down`, livraison ; sous Windows, exécute les deux vérificateurs avec PowerShell 5.1) et `Require`/`Forbid` dans `scripts/install/verify-installer-template.ps1`. **Preuve du rouge : 4 tests sur 4 en échec au HEAD ; le vérificateur échoue sur « The uninstaller must always write its log. »**
- [x] 3.2 Gabarit : `UninstallLogging=yes`, `LogScriptOutput`, `RemoveManagedDockerInstance` (avant `RemoveMinosProgramPayload`, `-Purge` sur purge, boîte d'échec hors mode silencieux), suppression de `PurgeManagedDockerVolumes`, script de retrait des clients MCP qui journalise ses avertissements et sort en code 1, `[UninstallDelete] Type: dirifempty` du groupe, texte de la question de purge. Preuve : `./mvnw -B -ntp -pl minos-app -am test "-Dtest=com.minos.packaging.*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` (38 tests, 0 échec) ; `verify-installer-template.ps1` vert.
- [ ] 3.3 Images `minos-code-intelligence:*` selon la décision D4 du propriétaire : passer `-RemoveImages` et l'annoncer, ou poser la question, ou ne rien changer ; test de contrat correspondant.

## 4. Clôture

- [x] 4.1 `docs/user/troubleshooting.md` (ressources Docker restées après une désinstallation : message, journal, commandes de recherche) ; `python scripts/docs/check-current-docs.py` ; `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict`.
- [ ] 4.2 **(manuelle)** Construire l'installeur par `scripts/release/build-local-windows-candidate.ps1 -Version 1.3.0 -SkipMavenVerify`, installer avec le backend Docker, PostgreSQL et Ollama gérés et Codex Desktop, désinstaller avec purge : aucun conteneur, volume, réseau `minos-mcp-prod*`, aucun bloc MINOS dans `~/.codex/config.toml`, aucun dossier `MINOS` du menu Démarrer ; le journal de désinstallation contient les lignes `MINOS-DOCKER-REMOVAL`.
