# Proposal

## Why

**AUD-QUA-09** (faible, sprint 11 de l'audit du 10 octobre 2026). Des fonctions sont recopiées entre scripts vivants, et rien ne dit si les copies sont encore les mêmes. Relevé au HEAD `5ce7b79b`, corps normalisés comparés un à un (méthode dans `design.md`) :

| Fonction | Copies vivantes | État |
|---|---|---|
| `Read-BoundedUtf8` et les quatre fonctions d'ACL du fichier de secret (`Restrict-FileToCurrentUser`, `Get-FileAccessSecurity`, `Assert-FileRestrictedToCurrentUser`, `Write-PrivateSecretFile`) | `docker/scripts/configure-m30-docker-services.ps1`, `scripts/install/configure-runtime-settings.ps1`, tous deux livrés | identiques |
| `Test-CeilingOverride`, `Get-PreservedCeilingOverrides` | `docker/scripts/mcp-lifecycle.ps1` (livré), `docker/scripts/prod-mcp.ps1` | identiques |
| `Get-UserFolderPath` | `scripts/install/configure-mcp-clients.ps1`, `scripts/install/detect-mcp-clients.ps1`, tous deux livrés | identiques |
| `Test-Case`, `Assert-True` | scripts de test de `scripts/release/` et de `scripts/install/` | identiques |
| `Require-Identifier` | les deux scripts de la première ligne | même validation, messages différents |
| `Invoke-NativeChecked` | `scripts/release/build-windows-distribution.ps1`, `scripts/release/publish-windows-release.ps1` | même logique, mise en forme différente |
| `find_ci_wiring_violations` | `scripts/quality/check-compose-limits.py`, `scripts/quality/check-image-pins.py` | identiques |

Une correction d'une fonction de sécurité ou du câblage CI des gates doit être faite deux fois, et rien n'échoue si elle ne l'est qu'une.

L'action de l'audit (une bibliothèque dot-sourcée `scripts/lib/MinosNative.ps1`) bute sur les fichiers livrés : la distribution Windows copie ces scripts un par un, l'installateur exécute `detect-mcp-clients.ps1` seul depuis un dossier temporaire, et aucun script livré ne dot-source de fichier.

## What Changes

- **Gate d'identité entre copies** : un gate Python compare les corps normalisés des fonctions PowerShell déclarées jumelles dans les scripts livrés, d'installation, de release et de CI ; il refuse aussi une copie identique non déclarée et une entrée morte. Il est rouge dès sa création sur `Require-Identifier` et `Invoke-NativeChecked`.
- **`Require-Identifier`** et **`Invoke-NativeChecked`** (paire de release) : alignés, sans changement de comportement, puis gardés.
- **Câblage CI des gates Python** : la vérification qu'un gate et son auto-test sont des étapes sans condition du job `invariants` est définie une seule fois, dans le squelette commun des gates, et importée par `check-compose-limits.py`, `check-image-pins.py` et le nouveau gate.
- **Bibliothèque PowerShell livrée** : soumise à décision (D1), non réalisée par défaut.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `controles-de-fusion` : deux exigences ajoutées (les fonctions de scripts recopiées restent identiques ; la vérification du câblage CI des gates n'est écrite qu'une fois).

## Hors périmètre

- Les scripts de jalon (`scripts/mNN/`, gelés par assertion ou rejeux manuels, ADR 0043), dont les 8 `Resolve-Python`, et `scripts/history/`.
- Les lanceurs PowerShell embarqués dans les modules (déjà gardés par `check-job-object-launcher-parity.py` et les goldens) et le runner `scip-java-windows-runner.ps1`.
- Les homonymes divergents du périmètre (13 noms, liste dans `design.md`), dont `Invoke-NativeChecked` de `scripts/ci/qualify-docker-upgrade.ps1` ; la divergence de `Find-EmbeddedClaudeCli` entre détection et configuration est relevée, non corrigée.
- Les petites fonctions Python recopiées (`sha256`, `sha256_of`, `read`) et toute mutualisation Python autre que `find_ci_wiring_violations`.
- Toute modification du contenu de la distribution Windows, de l'installateur ou des images Docker, tant que D1 ne retient pas la bibliothèque livrée.

## Impact

- **Modules du reactor** : aucun.
- **Fichiers** : `scripts/quality/gate_cli.py`, `scripts/quality/check-compose-limits.py`, `scripts/quality/check-image-pins.py`, nouveaux `scripts/quality/check-twin-script-functions.py` et `scripts/quality/test_check_twin_script_functions.py`, `.github/workflows/pr-ci.yml` (job `invariants`), `scripts/install/configure-runtime-settings.ps1` (message de `Require-Identifier`), `scripts/release/publish-windows-release.ps1` (mise en forme de `Invoke-NativeChecked`), `docs/developer/quality-gates.md`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune. La distribution Windows garde la même liste de fichiers (option recommandée) ; seul le texte d'une erreur de `configure-runtime-settings.ps1` change.
- **ADR** : aucun créé ni amendé ; l'ADR 0043 fixe déjà le sort des scripts de jalon.
- **Gates à rejouer** : `python scripts/quality/check-twin-script-functions.py` et `test_check_twin_script_functions.py`, `python scripts/quality/check-compose-limits.py` et `test_check_compose_limits.py`, `python scripts/quality/check-image-pins.py` et `test_check_image_pins.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/remediation/check-audit-remediation-v2.py`, `python scripts/quality/check-milestone-artifact-references.py`, `python scripts/remediation/check-post-mne.py`, `python scripts/install/test_secret_file_acl.py` (Windows), `python scripts/docs/check-current-docs.py`. Aucun scope JaCoCo.
- **Plateformes** : le gate est du Python de texte (Linux et Windows) ; les deux scripts PowerShell modifiés se valident sous Windows PowerShell 5.1 et PowerShell 7.
