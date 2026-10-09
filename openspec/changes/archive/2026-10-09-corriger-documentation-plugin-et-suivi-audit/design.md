# Design

## Context

- `docs/user/intellij-plugin.md:69` : `C:\Users\<user>\AppData\Local\Programs\MINOS\bin\minos.cmd`.
- `scripts/release/build-windows-distribution.ps1:354` écrit `minos.cmd` à la racine de la distribution ; `scripts/install/install-windows.ps1:66` et `packaging/windows/minos-installer.iss.template:16` installent dans `Programs\MINOS` ; la liste des entrées gérées (`minos-installer.iss.template:1282`) n'a pas de dossier `bin`.
- Commits fusionnés de l'audit d'octobre : `82dc354b` (B01–B04), `f87f2250` (C01–C03, C05, C06, C16), `9bc1129d` (A01, A02), `9f3e2911` (C04), `14596db2` (B07–B09, B13), `93e10c3a` (D01, D06, D10).

## Decisions

1. Corriger la documentation plutôt que l'installateur : l'emplacement racine est celui de toutes les autres documentations et des scripts d'intégration.
2. Le contrôle lit le chemin dans l'installateur et la distribution (`DefaultDirName`, `Join-Path $Distribution 'minos.cmd'`) et exige que tout chemin de lanceur Windows cité dans `docs/user/` s'y termine ; il échoue en citant le fichier et la ligne. Les gates documentaires affirment des chaînes littérales : rejouer `check-current-docs.py`, `product-facts.py --check` et `check-milestone-artifact-references.py`.
3. `run-minos.ps1` : redirection de la compilation (`*> $log` ou `2>&1 | Out-File`) uniquement si l'exécution montre une pollution de la sortie standard.

## Risks / Trade-offs

- Les documents de `docs/audit/` contiennent des fins de ligne CRLF : conserver le format de chaque fichier.
- Windows seulement pour le chemin ; aucun effet Linux.
