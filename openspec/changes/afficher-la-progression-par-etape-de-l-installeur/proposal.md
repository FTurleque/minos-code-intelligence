# Proposal

## Why

Après la copie des fichiers, l'installeur Windows (`packaging/windows/minos-installer.iss.template`) configure en série le runtime, le backend MCP (Docker : image, PostgreSQL, Ollama et son modèle, handshake) puis les clients IA. L'utilisateur ne dispose que de la barre d'Inno Setup, déjà pleine à ce stade, et d'un libellé d'état (`WizardForm.StatusLabel.Caption`) par étape. Une préparation Docker prend une dizaine de minutes sans aucun signe d'avancement : l'installeur paraît figé et l'utilisateur ne sait pas quelle étape tourne, laquelle a réussi ni laquelle a échoué.

## What Changes

- **Une rangée par étape sélectionnée** sur la page d'installation (`WizardForm.InstallingPage`) : libellé et barre (`TNewProgressBar`). Une rangée passe d'« en attente » à « en cours », puis à « terminée » (barre pleine, verte) ou « échec » (barre rouge). Seules les étapes que les choix de l'assistant déclenchent ont une rangée.
- **Protocole de progression** : les scripts lancés par l'installeur ajoutent des lignes `MINOS-PROGRESS step=<id> state=<état>[ percent=<n>]` à un fichier dont le chemin leur est passé par `-ProgressPath`. Identifiants d'étape et états viennent d'une liste fermée ; aucun texte libre n'est écrit.
- **Lecture pendant l'exécution** : un minuteur Windows (`SetTimer`, rappel créé par `CreateCallback`) relit ce fichier pendant que `Exec` attend le script ; les processus fils gardent leurs descripteurs actuels. `ExecAndLogOutput` est écarté (mesures au design, D1).
- **Capacité honnête** : un pourcentage n'est affiché que si l'outil en fournit un (téléchargement du modèle Ollama par `ollama pull`). Sinon la barre est indéterminée (défilement) pendant l'étape. L'état final d'une étape découle du code de sortie du script ; une étape en échec reste rouge et le message d'erreur existant garde la cause.
- **Mode smoke inchangé** : aucune rangée, aucun `-ProgressPath`.

## Capabilities

### New Capabilities

- `installeur-windows` : progression par étape de la configuration post-installation (rangées, protocole, honnêteté du pourcentage, absence de secret, mode smoke).

### Modified Capabilities

(aucune)

## Hors périmètre

- La phase `PrepareToInstall` (extraction de l'archive par `Expand-Archive`, mise à jour transactionnelle) : elle précède la page d'installation et garde l'affichage d'Inno Setup.
- Un pourcentage pour la construction de l'image Docker (`docker build`), le démarrage des services Compose et le handshake : ces outils n'exposent pas d'avancement exploitable ; la barre y reste indéterminée.
- La désinstallation et le préflight de détection des clients.
- Le contenu des messages d'erreur existants (`MsgBox`) et des journaux (`backend-switch.log`, `docker-setup.log`, `mcp-clients.log`).
- L'installation ZIP (`scripts/install/install-windows.ps1`) : sans interface, elle n'emploie pas `-ProgressPath`.

## Impact

- **Modules du reactor** : aucun code de production Java. Test de contrat ajouté dans `minos-app` (`com.minos.packaging`).
- **Fichiers livrés** : `packaging/windows/minos-installer.iss.template`, `scripts/install/switch-mcp-backend.ps1`, `scripts/install/configure-mcp-clients-setup.ps1`, `docker/scripts/configure-docker-mcp.ps1`, `docker/scripts/configure-m30-docker-services.ps1`. Aucun fichier livré nouveau : les listes de `build-windows-distribution.ps1` et `update-installation.ps1` ne changent pas.
- **Surfaces publiques** : CLI, API Java, MCP, IntelliJ et NEXUS inchangés. Les scripts gagnent un paramètre facultatif `-ProgressPath` ; sans lui, leur comportement et leur sortie sont identiques.
- **ADR** : aucun nouvel ADR ni amendement. Le changement reste interne à l'installeur et ne modifie ni le cycle transactionnel du backend (S7) ni le confinement.
- **Gates à rejouer** : `scripts/install/verify-installer-template.ps1` (dont `Require 'WizardForm.StatusLabel.Caption :='`), `scripts/docs/check-current-docs.py`, `scripts/quality/check-ci-wiring.py`, `M29InstallerBackendLifecycleContractTest` (dont `shippedPowerShellScriptsAreAsciiOnly`), `check-post-mne.py` (littéraux de `configure-m30-docker-services.ps1`), `scripts/quality/check-twin-script-functions.py` (la fonction d'émission, copiée dans quatre scripts livrés, y est déclarée jumelle) et son auto-test.
- **Plateformes** : Windows seulement (installeur Inno Setup 6.7.1, Windows PowerShell 5.1). Le test des émetteurs tourne aussi sous `pwsh` sur Ubuntu (job `invariants`).
