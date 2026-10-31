# Proposal

## Why

Constat du 31 octobre 2026 sur une installation Windows 1.3.0 avec le backend MCP Docker et PostgreSQL géré par Docker : la commande native de l'installation (`minos.cmd providers`, comme toute autre) sort en code 1 sur `error: MINOS bootstrap failed: missing required PostgreSQL setting: MINOS_POSTGRES_URL`.

L'installeur écrit deux `MINOS_HOME` distincts. Dans le dossier **natif** (`%LOCALAPPDATA%\MINOS\data`), `scripts/install/configure-runtime-settings.ps1` écrit `minos.storage.backend=postgresql` et `minos.postgres.managed=true` sans aucun paramètre de connexion. Les paramètres de connexion ne sont écrits que dans le dossier **Docker** (`docker-data`) par `docker/scripts/configure-m30-docker-services.ps1`, avec l'hôte `minos-postgres` qui n'existe que sur le réseau Docker géré ; PostgreSQL ne publie aucun port sur l'hôte. Le lanceur natif ne peut donc jamais joindre ce PostgreSQL, et l'amorçage du stockage échoue pour toutes les commandes.

La documentation (`docs/user/docker-runtime.md`) affirme qu'un lanceur natif ouvre ce même dossier et que seules les commandes sémantiques sont refusées : c'est faux quand PostgreSQL est géré par Docker.

## What Changes

- **Natif en stockage local** (décision du 31 octobre 2026) : quand PostgreSQL est géré par Docker, l'installeur écrit `minos.storage.backend=local` dans le `MINOS_HOME` natif. Seul le runtime Docker utilise PostgreSQL. Le choix de l'utilisateur reste consigné dans `installer-state.json`.
- **Refus résiduel actionnable** : une configuration native qui sélectionne un PostgreSQL géré sans URL de connexion (installations 1.3.0 déjà faites) est refusée à l'amorçage par une erreur qui nomme le runtime Docker, `minos.storage.backend=local` et la réinstallation comme remèdes. Le refus reste fail-closed : aucun repli silencieux vers le stockage local.
- **Documentation** : `docs/user/docker-runtime.md` décrit ce que contient chaque `MINOS_HOME` et le comportement natif.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : deux exigences ajoutées (le `MINOS_HOME` natif d'une installation PostgreSQL gérée par Docker reste en stockage local ; un PostgreSQL géré sans URL hors Docker est refusé de façon actionnable).

## Hors périmètre

- Publier le port PostgreSQL sur l'hôte ou partager le mot de passe géré avec le natif : élargirait la surface d'exposition du secret et du service.
- Un lanceur natif dont la configuration désigne explicitement l'hôte `minos-postgres` (copie manuelle d'une configuration Docker) : même risque de résolution par le réseau local que `minos-ollama` (AUD-SEC-07) ; à traiter par un constat distinct.
- Le contenu de `docker-data` et la configuration Docker : inchangés.
- Unifier la lecture de l'emplacement d'exécution avec `StrongOwnedProcessExecutors.isDockerRuntimeLocation` (relevé par `reserver-minos-ollama-au-runtime-docker`).

## Impact

- **Modules du reactor** : `minos-engine` (`com.minos.storage.StorageBackendConfiguration`), sans nouvelle dépendance ; l'emplacement est lu par `MinosRuntimeSettings.runtimeLocation()` déjà présent.
- **Scripts livrés** : `scripts/install/configure-runtime-settings.ps1` (même nom, même liste de fichiers de `build-windows-distribution.ps1` et `update-installation.ps1`).
- **Surfaces publiques** : CLI, API Java, MCP — aucune option ni champ nouveau ; la commande native d'une installation PostgreSQL gérée par Docker démarre de nouveau sur le stockage local ; le message d'erreur d'une configuration résiduelle change. IntelliJ et NEXUS : sans changement de protocole.
- **ADR** : aucun nouvel ADR ; la règle prolonge, pour le stockage, la décision « service géré joint seulement depuis le runtime Docker » prise pour `minos-ollama` (ADR 0031 amendé).
- **Gates à rejouer** : `python scripts/remediation/check-post-mne.py` (littéral `'minos.postgres.managed'] = 'false'` dans `configure-runtime-settings.ps1`), `python scripts/quality/check-twin-script-functions.py`, `python scripts/quality/check-ci-wiring.py` (nouveau `test_*.py`).
- **Plateformes** : le script d'installation est propre à Windows ; le refus Java vaut sous Windows et Linux natifs.
