# Proposal

## Why

La revue de sécurité de la PR #599 (tests de confinement intermittents, sprint 3 de l'audit du 10 octobre 2026) a relevé que le helper d'identité de chemin Windows (`windows-path-identity-v1.ps1`, lancé par `WindowsExecutionPathIdentityProvider.startHelper`) recevait **tout** l'environnement de la JVM : `MINOS_TEAM_KEY_*`, `MINOS_TEAM_TOKEN`, `*TOKEN*`, `PGPASSWORD` et `PSModulePath` compris. Cela contredit la règle sécurité 3 (`.claude/rules/securite.md`). Le changement archivé `2026-10-13-epingler-et-isoler-l-installation-des-providers` a posé la liste d'autorisation des lanceurs de confiance, mais ce helper ne l'utilisait pas.

## What Changes

- Le helper reçoit la liste d'autorisation des lanceurs de confiance de `ProviderProcessEnvironment` (`applyForTrustedLauncher`), plus ses deux cibles `MINOS_REGISTERED_PATH_IDENTITY_TARGET` et `MINOS_PROJECT_PATH_IDENTITY_TARGET`, et rien d'autre.
- `ProviderProcessEnvironment.applyForTrustedLauncher` devient publique, à l'intérieur de `minos-runtime-local` seulement (règle A10 inchangée).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `installation-des-providers` : une exigence ajoutée (environnement du helper d'identité de chemin Windows).

## Hors périmètre

- La cause de l'échec intermittent du helper en environ 20 ms sans sortie (PR #599) : non établie, et non reproduite par un environnement vide (l'échec mesuré est différent, voir `design.md`).
- Le contenu du message `queryFailureMessage` (PR #599), qui recopie la sortie du helper : remarque de revue à traiter avec #599.
- L'environnement des commandes d'installation et des providers, déjà couvert par la capacité.

## Impact

- **Modules du reactor** : `minos-runtime-local` (`com.minos.runtime.local.sandbox.WindowsExecutionPathIdentityProvider`, `com.minos.runtime.local.process.ProviderProcessEnvironment`).
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés).
- **ADR** : aucun nouveau ni amendé ; application de la règle sécurité 3 et du mécanisme existant.
- **Gates à rejouer** : `python scripts/architecture/check-module-boundaries.py` (A10), `python scripts/quality/check-private-io.py`, `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows seulement à l'exécution (le fournisseur rend `Optional.empty()` ailleurs) ; le test d'environnement, sans processus, s'exécute sur les deux CI.
