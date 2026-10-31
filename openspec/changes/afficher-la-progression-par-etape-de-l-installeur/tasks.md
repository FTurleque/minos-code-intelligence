# Tasks

Ordre : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les scripts livrés restent en ASCII et en CRLF.

## 1. Émetteurs du protocole dans les scripts

- [ ] 1.1 Rouge : `scripts/install/test_setup_progress.py` extrait par l'analyseur PowerShell la fonction `Write-SetupProgress` de `switch-mcp-backend.ps1`, `configure-mcp-clients-setup.ps1`, `configure-docker-mcp.ps1` et `configure-m30-docker-services.ps1`, et `Get-OllamaPullPercent` de ce dernier ; il vérifie la ligne exacte, les refus (état, identifiant, pourcentage), l'absence de fichier sans chemin, l'analyse d'une sortie d'Ollama. **Preuve du rouge : les fonctions n'existent pas au HEAD.**
- [ ] 1.2 Ajouter `-ProgressPath` et `Write-SetupProgress` aux quatre scripts ; émettre les étapes de D2 ; transmettre `-ProgressPath` de script à script seulement s'il est renseigné ; lire `ollama pull` au fil de l'eau (D4) sans changer la gestion de stderr ni le message d'échec. Preuve : test 1.1 vert sous `pwsh` et sous Windows PowerShell 5.1 (`MINOS_TEST_POWERSHELL`) ; `python scripts/remediation/check-post-mne.py` vert.
- [ ] 1.3 Brancher le test dans `.github/workflows/pr-ci.yml` (job `invariants` et étape Windows du job `verify`). Preuve : `python scripts/quality/check-ci-wiring.py` vert.

## 2. Rangées de progression dans l'installeur

- [ ] 2.1 Rouge : `minos-app/src/test/java/com/minos/packaging/InstallerSetupProgressContractTest.java` vérifie les rangées et leurs conditions, le minuteur (`SetTimer`, `CreateCallback`, `KillTimer`), `-ProgressPath` passé au backend et aux clients, la grammaire acceptée, l'état final selon le code de sortie, la sortie en mode smoke, l'absence d'`ExecAndLogOutput`, et que chaque `Write-SetupProgress` livré n'a que les paramètres d'étape, d'état et de pourcentage. **Preuve du rouge : `./mvnw -B -ntp -pl minos-app -am test "-Dtest=InstallerSetupProgressContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"` échoue au HEAD.**
- [ ] 2.2 `packaging/windows/minos-installer.iss.template` : création et disposition des rangées (D5), minuteur et lecture du fichier (D1), états finaux (D3), `StatusLabel` conservé. Compléter `scripts/install/verify-installer-template.ps1` (`Require` sur les nouvelles constructions, `Forbid 'ExecAndLogOutput'`). Preuve : test 2.1 et `M29InstallerBackendLifecycleContractTest` verts ; `verify-installer-template.ps1` vert sous Windows PowerShell 5.1 ; `python scripts/docs/check-current-docs.py` vert.

## 3. Documentation

- [ ] 3.1 Décrire la progression par étape (rangées, pourcentage du modèle Ollama seulement, échec en rouge) dans la page utilisateur de l'installeur Windows de `docs/user/`, et le protocole dans la page développeur de l'installeur s'il en existe une. Preuve : `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` verts.

## 4. Preuve sur un installeur construit

- [ ] 4.1 Construire `scripts/release/build-local-windows-candidate.ps1 -Version 1.3.0 -SkipMavenVerify` ; le modèle compile avec Inno Setup 6.7.1.
- [ ] 4.2 **(manuelle)** Installation interactive du candidat, backend natif et au moins un client IA : une rangée par étape, chacune passe au vert. Noter le résultat dans la PR.

## 5. Recette Docker

- [ ] 5.1 **(manuelle)** Installation interactive avec le backend Docker, PostgreSQL et Ollama avec provisionnement : rangées image, PostgreSQL, Ollama, modèle et validation ; indiquer si un pourcentage du modèle a été affiché (capacité partielle de D4).

## 6. Clôture

- [ ] 6.1 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict`.
- [ ] 6.2 Docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md` (si l'installeur y est décrit), la page utilisateur de 3.1 ; réécrire le « Purpose » de `installeur-windows` après `openspec archive`.
