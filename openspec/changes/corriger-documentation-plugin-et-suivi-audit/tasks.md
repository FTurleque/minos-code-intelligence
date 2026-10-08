# Tasks

## 1. Chemin du lanceur

- [x] 1.1 Contrôle documentaire d'abord (rouge sur `docs/user/intellij-plugin.md:69`), avec son auto-test.
- [x] 1.2 Corriger `docs/user/intellij-plugin.md` ; rejouer `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`.

## 2. Suivi de l'audit d'octobre

- [x] 2.1 `docs/audit/constats.md` § 2 et § 6 : statut fusionné et commit de chaque changement ; reporter les statuts revérifiés du § 6 de `docs/quality/code-audit-constats.md` (corrigés, partiels, toujours présents, décisions).

## 3. Script de lancement IntelliJ

- [x] 3.1 Lancer `scripts/intellij/run-minos.ps1` depuis un client MCP (ou en capturant la sortie standard) et vérifier qu'aucune ligne précède le premier message JSON-RPC ; corriger si besoin (H19).

## Évidence d'implémentation (2026-10-08)

- 1.1 : `validate_intellij_launcher_path` dans `scripts/docs/check-current-docs.py` (lit `DefaultDirName` de l'installateur et l'emplacement de `minos.cmd` dans `build-windows-distribution.ps1`) ; rouge sur `docs/user/intellij-plugin.md:69` avant correction.
- 1.2 : chemin corrigé en `…\AppData\Local\Programs\MINOS\minos.cmd` ; `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py` verts.
- 2.1 : état daté en tête du § 6 de `docs/audit/constats.md` (commit fusionné et statut revérifié de chaque changement) ; attribution corrigée de D01/D06/D10 (`67872cc8`, et non `93e10c3a`).
- 3.1 : H19 **non reproduit** : `mvnw.cmd -q -DskipTests package` n'écrit aucune ligne sur la sortie standard en cas de succès (mesuré) ; en cas d'échec le script s'arrête avant de démarrer le serveur MCP. Aucune modification de `run-minos.ps1`.
