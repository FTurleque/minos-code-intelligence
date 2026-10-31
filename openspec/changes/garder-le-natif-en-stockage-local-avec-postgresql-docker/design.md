# Design

## État vérifié au HEAD (`develop` @ 8c3d4615, 31 octobre 2026)

- `packaging/windows/minos-installer.iss.template:885-888` : quand PostgreSQL et le backend MCP Docker sont choisis, l'installeur appelle `configure-runtime-settings.ps1` avec `-ManagedDockerPostgres` et `-DataRoot` = dossier natif (`%LOCALAPPDATA%\MINOS\data`).
- `scripts/install/configure-runtime-settings.ps1:262-271` : la configuration native reçoit `minos.storage.backend=$StorageBackend` (donc `postgresql`) et `minos.postgres.managed=true`, sans URL, utilisateur ni mot de passe.
- `docker/scripts/configure-docker-mcp.ps1:83` passe `DataRoot = $DockerDataRoot` aux scripts Docker ; `docker/scripts/configure-m30-docker-services.ps1:404-413` y écrit `minos.postgres.url=jdbc:postgresql://minos-postgres:5432/<base>`, l'utilisateur, le schéma et `passwordFile`. Le service `minos-postgres` ne publie aucun port sur l'hôte.
- `minos-engine/src/main/java/com/minos/storage/StorageBackendConfiguration.java:70` : `resolve(MinosRuntimeSettings)` lit la configuration sans contrôle de cohérence ; `minos-storage/src/main/java/com/minos/storage/postgresql/support/PostgresConnectionFactory.java:86` lève `missing required PostgreSQL setting: MINOS_POSTGRES_URL`, que `minos-cli/src/main/java/com/minos/cli/MinosLauncher.java:93-96` rapporte comme `MINOS bootstrap failed`.
- `minos-engine/src/main/java/com/minos/storage/MinosRuntimeSettings.java:109` : `runtimeLocation()` lit `minos.runtime.location` puis `MINOS_RUNTIME_LOCATION`, jamais `minos.properties`. Les images (`docker/Dockerfile.mcp.release:44,97`) et les services compose (`docker/compose-mcp.prod.yaml`, `docker/compose-mcp.connected.yaml`) posent `MINOS_RUNTIME_LOCATION=docker`.
- `scripts/install/update-installation.ps1:265` copie `configure-runtime-settings.ps1` sans le rejouer : une installation 1.3.0 mise à jour garde sa configuration native fautive.

## Décisions

**D1 — Comportement natif (décidé le 31 octobre 2026 : natif local + refus résiduel).** Trois options présentées : (A) natif en stockage local et refus actionnable d'une configuration résiduelle ; (B) refus actionnable seul, `minos.cmd` inutilisable ; (C) natif local sans refus, installations 1.3.0 laissées sur l'erreur actuelle. Retenue : **A**.

**D2 — Où écrire le stockage natif.** Dans `configure-runtime-settings.ps1`, `-ManagedDockerPostgres` écrit `minos.storage.backend=local` et aucune clé `minos.postgres.*`. `installer-state.json` garde `storageBackend=postgresql` et `managedDockerPostgres=true` : c'est le choix de l'utilisateur, relu par l'installeur pour présélectionner l'écran suivant.

**D3 — Où refuser.** Dans `StorageBackendConfiguration.resolve(MinosRuntimeSettings)`, quand le backend est `postgresql`, `managed=true`, l'URL absente et l'emplacement d'exécution natif : `IOException` dont le message nomme le runtime Docker, `minos.storage.backend=local` et la réinstallation. Aucun chemin absolu dans le message. En Docker, le comportement est inchangé (l'erreur de paramètre manquant reste celle de la fabrique). Le refus est fail-closed : aucun basculement automatique vers le stockage local, qui masquerait un index absent.

## Direction des dépendances (ADR 0068)

Le contrôle reste dans `minos-engine` et n'utilise que `MinosRuntimeSettings` et `ProjectPathMapping` (`minos-domain`) : aucune dépendance nouvelle.

## Qualification

- Natif d'une installation PostgreSQL gérée par Docker sur stockage local : **qualifiée** par le test du script d'installation (Windows PowerShell 5.1 et `pwsh`).
- Refus résiduel : **qualifiée** par test unitaire (natif refusé, Docker inchangé, emplacement inconnu refusé).
- Preuve sur une installation réelle : **(manuelle)**, tâche 3.1.

## Plateformes

Le script d'installation ne tourne que sous Windows ; son test est déclaré sauté dans le job `invariants` (Ubuntu) et exécuté dans `Verify (windows-2022)`. Le refus Java vaut sous Windows et Linux.
