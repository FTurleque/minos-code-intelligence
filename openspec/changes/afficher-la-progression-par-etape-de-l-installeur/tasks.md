# Tasks

Ordre : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les scripts livrés restent en ASCII et en CRLF.

## 1. Émetteurs du protocole dans les scripts

- [x] 1.1 Rouge : `scripts/install/test_setup_progress.py` extrait par l'analyseur PowerShell la fonction `Write-SetupProgress` de `switch-mcp-backend.ps1`, `configure-mcp-clients-setup.ps1`, `configure-docker-mcp.ps1` et `configure-m30-docker-services.ps1`, et `Get-OllamaPullPercent` de ce dernier ; il vérifie la ligne exacte, les refus (état, identifiant, pourcentage), l'absence de fichier sans chemin, l'analyse d'une sortie d'Ollama. **Preuve du rouge : les fonctions n'existent pas au HEAD.**
- [x] 1.2 Ajouter `-ProgressPath` et `Write-SetupProgress` aux quatre scripts ; émettre les étapes de D2 ; transmettre `-ProgressPath` de script à script seulement s'il est renseigné ; lire `ollama pull` au fil de l'eau (D4) sans changer la gestion de stderr ni le message d'échec. Preuve : test 1.1 vert sous `pwsh` et sous Windows PowerShell 5.1 (`MINOS_TEST_POWERSHELL`) ; `python scripts/remediation/check-post-mne.py` vert.
- [x] 1.3 Brancher le test dans `.github/workflows/pr-ci.yml` (job `invariants` et étape Windows du job `verify`). Preuve : `python scripts/quality/check-ci-wiring.py` vert.

## 2. Rangées de progression dans l'installeur

- [x] 2.1 Rouge : `minos-app/src/test/java/com/minos/packaging/InstallerSetupProgressContractTest.java` vérifie les rangées et leurs conditions, le minuteur (`SetTimer`, `CreateCallback`, `KillTimer`), `-ProgressPath` passé au backend et aux clients, la grammaire acceptée, l'état final selon le code de sortie, la sortie en mode smoke, l'absence d'`ExecAndLogOutput`, et que chaque `Write-SetupProgress` livré n'a que les paramètres d'étape, d'état et de pourcentage. **Preuve du rouge : `./mvnw -B -ntp -pl minos-app -am test "-Dtest=InstallerSetupProgressContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"` échoue au HEAD.**
- [x] 2.2 `packaging/windows/minos-installer.iss.template` : création et disposition des rangées (D5), minuteur et lecture du fichier (D1), états finaux (D3), `StatusLabel` conservé. Compléter `scripts/install/verify-installer-template.ps1` (`Require` sur les nouvelles constructions, `Forbid 'ExecAndLogOutput'`). Preuve : test 2.1 et `M29InstallerBackendLifecycleContractTest` verts ; `verify-installer-template.ps1` vert sous Windows PowerShell 5.1 ; `python scripts/docs/check-current-docs.py` vert.

## 3. Documentation

- [x] 3.1 Décrire la progression par étape (rangées, pourcentage du modèle Ollama seulement, échec en rouge) dans `docs/user/production-installation.md` (§ 8 bis) ; `docs/developer/` n'a pas de page de l'installeur, le protocole est décrit par le design et par les commentaires du modèle. Preuve : `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` verts.

## 4. Preuve sur un installeur construit

- [x] 4.1 Construire `scripts/release/build-local-windows-candidate.ps1 -Version 1.3.0 -SkipMavenVerify` : SUCCESS au HEAD `f40fa3d0` (compilateur choisi automatiquement : Inno Setup 7). Le modèle compile aussi avec Inno Setup 6.7.3 (`build-windows-installer.ps1 -IsccPath`). La compilation en 6.7.1, version épinglée par la CI de release, n'a pas pu être faite sur le poste ; elle revient à `release-windows.yml`.
- [ ] 4.2 **(manuelle)** Installation interactive du candidat, backend natif et au moins un client IA : une rangée par étape, chacune passe au vert. Noter le résultat dans la PR.

## 5. Recette Docker

- [x] 5.1 **(manuelle)** Installation interactive avec le backend Docker, PostgreSQL et Ollama avec provisionnement : rangées image, PostgreSQL, Ollama, modèle et validation ; indiquer si un pourcentage du modèle a été affiché (capacité partielle de D4). **Fait le 2026-10-31** sur le candidat `8a113943` (poste Windows 10, Docker Desktop), modèle absent du volume : journal de l'installeur, `Setup progress:` `docker-image` running 15:13:29 → done 15:16:52, `postgres` done 15:16:54, `ollama` done 15:16:55, `ollama-model` running 15:16:55 → done 15:17:05 (`nomic-embed-text`, 274 Mo retéléchargé), `handshake` done 15:17:46, `client-claude-code` et `client-claude-desktop` done 15:17:49 ; `backend-switch.log` : `SUCCESS previous=native target=docker`. Pourcentage du modèle : la barre s'est remplie progressivement (constat visuel de l'utilisateur ; le journal n'enregistre que les changements d'état). Une réinstallation de la même version donne les rangées image, PostgreSQL, Ollama et modèle en `skipped` (barre pleine, en pause, jaune) avec `PREPARE target=docker mode=reuse`.

## 6. Clôture

- [ ] 6.1 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict`.
- [ ] 6.2 Docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md` (si l'installeur y est décrit), la page utilisateur de 3.1 ; réécrire le « Purpose » de `installeur-windows` après `openspec archive`.
