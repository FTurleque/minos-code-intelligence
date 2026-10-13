# Proposal

## Why

**AUD-QUA-09** (faible, sprint 11 de l'audit du 10 octobre 2026). Des fonctions sont recopiées entre scripts vivants, sans rien qui dise si les copies sont encore les mêmes. Relevé au HEAD, corps normalisés comparés un à un :

| Fonction | Copies vivantes | État |
|---|---|---|
| `Read-BoundedUtf8` (lecture bornée, fonction de sécurité) | `docker/scripts/configure-m30-docker-services.ps1:51`, `scripts/install/configure-runtime-settings.ps1:134` | identiques |
| `Test-CeilingOverride`, `Get-PreservedCeilingOverrides` | `docker/scripts/mcp-lifecycle.ps1:205,218`, `docker/scripts/prod-mcp.ps1:49,62` | identiques |
| `Require-Identifier` | `docker/scripts/configure-m30-docker-services.ps1:45`, `scripts/install/configure-runtime-settings.ps1:55` | **différentes** (l'audit les disait identiques) |
| `find_ci_wiring_violations` (37 lignes) | `scripts/quality/check-compose-limits.py:162`, `scripts/quality/check-image-pins.py:192` | identiques |
| `Invoke-NativeChecked` | 6 scripts hors `scripts/history/` | **6 corps distincts** (l'audit en comptait 3 variantes) |
| `Resolve-Python` | 8 scripts hors `scripts/history/` | 8 corps distincts, dont 7 dans des `run-final.ps1` de jalons gelés par assertion (ADR 0043) |

Une correction de `Read-BoundedUtf8` ou du câblage CI des gates doit être faite deux fois, et rien n'échoue si elle ne l'est qu'une.

L'action de l'audit (une bibliothèque dot-sourcée à créer, `scripts/lib/MinosNative.ps1`, fichier qui n'existe pas) bute sur un fait : trois des scripts concernés sont **livrés** dans la distribution Windows, copiés un par un, et aucun script livré ne dot-source `scripts/lib` aujourd'hui.

## What Changes

- **Garde d'identité entre copies** : un test Python compare les corps normalisés des fonctions jumelles déclarées et échoue à la première divergence. Il est rouge dès sa création sur `Require-Identifier`.
- **`Require-Identifier`** : les deux corps sont relus, puis alignés ou distingués par leur nom.
- **Câblage CI des gates Python** : `find_ci_wiring_violations` est défini une seule fois, dans un module partagé importé par les deux gates, qui gardent chacun leur `--self-test`.
- **Bibliothèque PowerShell livrée** : soumise à décision, non réalisée par défaut.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `controles-de-fusion` : deux exigences ajoutées (les fonctions de scripts recopiées restent identiques ; le câblage CI des gates Python n'est écrit qu'une fois).

## Hors périmètre

- `Resolve-Python` : un seul consommateur vivant hors des scripts gelés ; rien à mutualiser.
- Les scripts de jalon gelés par assertion (`run-final.ps1` et assimilés, ADR 0043) et `scripts/history/`.
- Unifier `Invoke-NativeChecked` : six corps qui diffèrent par leur signature et leur traitement de la sortie d'erreur ; hors lot, sauf la paire de `scripts/release/` si le relevé montre qu'elle peut être rendue identique (D3).
- Toute modification du contenu de la distribution Windows, tant que D1 ne retient pas la bibliothèque livrée.

## Impact

- **Modules du reactor** : aucun.
- **Fichiers** : `scripts/quality/check-compose-limits.py`, `scripts/quality/check-image-pins.py`, un nouveau `scripts/quality/_ci_wiring.py`, un nouveau `scripts/quality/test_twin_script_functions.py`, `.github/workflows/pr-ci.yml` (câblage), `docker/scripts/configure-m30-docker-services.ps1` et `scripts/install/configure-runtime-settings.ps1` (`Require-Identifier`).
- **Surfaces publiques** : aucune. La distribution Windows garde la même liste de fichiers (option recommandée).
- **ADR** : aucun ; l'ADR 0043 fixe déjà le sort des scripts gelés.
- **Gates à rejouer** : `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-compose-limits.py` et `python scripts/quality/check-image-pins.py` (avec leur `--self-test`), `python scripts/remediation/check-post-mne.py` (`:197-201` exige `Read-BoundedUtf8` dans les deux scripts), `python scripts/install/test_secret_file_acl.py` (`:133` extrait `Read-BoundedUtf8` du script Docker).
- **Plateformes** : la garde est un test Python de texte, exécuté sous Linux et sous Windows ; les scripts PowerShell modifiés se valident sous Windows.
