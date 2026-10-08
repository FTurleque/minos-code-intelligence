# Tasks

## 1. Chemin du lanceur

- [ ] 1.1 Contrôle documentaire d'abord (rouge sur `docs/user/intellij-plugin.md:69`), avec son auto-test.
- [ ] 1.2 Corriger `docs/user/intellij-plugin.md` ; rejouer `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`.

## 2. Suivi de l'audit d'octobre

- [ ] 2.1 `docs/audit/constats.md` § 2 et § 6 : statut fusionné et commit de chaque changement ; reporter les statuts revérifiés du § 6 de `docs/quality/code-audit-constats.md` (corrigés, partiels, toujours présents, décisions).

## 3. Script de lancement IntelliJ

- [ ] 3.1 Lancer `scripts/intellij/run-minos.ps1` depuis un client MCP (ou en capturant la sortie standard) et vérifier qu'aucune ligne précède le premier message JSON-RPC ; corriger si besoin (H19).
