# Proposal

## Why

La documentation du plugin IntelliJ indique comme lanceur par défaut `…\Programs\MINOS\bin\minos.cmd`, chemin qu'aucune installation ne crée (MINOS-AUD-H10) : un utilisateur qui le recopie configure un exécutable inexistant. Le suivi de l'audit d'octobre décrit encore comme « en local » ou « PR ouverte » des correctifs fusionnés dans `develop` (H11). Le script `scripts/intellij/run-minos.ps1` compile avant de démarrer un serveur MCP sur stdio, ce qui peut polluer le flux JSON-RPC (H19, à vérifier). Constats : `docs/quality/code-audit-constats.md` § 5.

## What Changes

- `docs/user/intellij-plugin.md` : chemin du lanceur par défaut corrigé en `…\Programs\MINOS\minos.cmd`.
- Contrôle documentaire : le chemin documenté du lanceur est confronté à l'emplacement produit par `build-windows-distribution.ps1` et à `DefaultDirName` de l'installateur (extension de `scripts/docs/check-current-docs.py` ou contrôle dédié).
- `docs/audit/constats.md` § 2 et § 6 : statut « fusionné dans `develop` » avec les commits, pour chaque changement de l'audit d'octobre.
- `scripts/intellij/run-minos.ps1` : vérifier la sortie standard ; si Maven y écrit, rediriger la compilation vers l'erreur standard ou un journal.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `client-intellij` : exigence ajoutée sur l'exactitude du chemin de lanceur documenté.

## Hors périmètre

- Changer l'emplacement d'installation ou ajouter un dossier `bin`.
- Les autres dérives documentaires des familles G (décisions d'ADR ouvertes).

## Impact

- Fichiers : `docs/user/intellij-plugin.md`, `docs/audit/constats.md`, `scripts/docs/check-current-docs.py` (ou nouveau contrôle), `scripts/intellij/run-minos.ps1`.
- Modules du réacteur : aucun. Surfaces publiques : documentation du plugin IntelliJ seulement.
- ADR : aucun.
