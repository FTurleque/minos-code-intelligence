# Tasks

Ordre : mesure, déplacement, preuve. Commande de référence : `./mvnw.cmd -B -ntp -pl minos-storage-postgresql -am test` (outillage Windows : `docs/audit/2026-10-10/SUIVI.md`).

## 1. Déplacement

- [x] 1.1 Rejouer l'analyse des dépendances du découpage D1 (aucun cycle, liste des types à rendre publics) et la consigner si elle diffère de `design.md`.
- [x] 1.2 Déplacer les 16 classes et leurs tests par `git mv` (historique conservé), réécrire les `package` et les imports ; `PostgresStorageBackend` et `PostgresStorageBackendProvider` ne bougent pas.
- [x] 1.3 Élargir la visibilité au strict nécessaire (D2), guidé par le compilateur ; consigner le nombre de types et de membres rendus publics.

## 2. Preuve

- [x] 2.1 `./mvnw.cmd -B -ntp -pl minos-storage-postgresql -am test` : même nombre de tests exécutés et sautés qu'avant le déplacement.
- [x] 2.2 Rejouer `python .claude/scripts/run_gates.py --fast` (dont `check-module-boundaries.py` A3 et A8, `check-private-io.py`), mettre à jour les chemins littéraux et les scopes JaCoCo qui citent le module.
- [x] 2.3 Régénérer le baseline SpotBugs du module (`-Paudit-spotbugs`). Constat : 4 alertes réellement nouvelles (`EI_EXPOSE_REP` ×1, `EI_EXPOSE_REP2` ×3), conséquence directe du passage en `public` (ce motif ne vise que les membres publics) ; consignées au baseline, qui passe de 4 à 8 alertes, selon la règle de `quality/spotbugs-exclude.xml` (« une alerte réelle se corrige ou se consigne »). Voir `design.md`, « Écart constaté à l'implémentation ».
- [x] 2.4 Mettre à jour `docs/architecture/arc42/05-vue-blocs.md` (sous-packages du module) ; rejouer `python scripts/docs/check-current-docs.py`.

## 3. Preuve sur les deux plateformes

- [x] 3.1 **(manuelle)** Relever sur `Verify (ubuntu-24.04)` et `Verify (windows-2022)` que les tests PostgreSQL du module s'exécutent, et que `SonarCloud Code Analysis` passe (80 % sur le code déplacé). Relevé le 2026-10-12 sur la PR #413, fusionnée tous checks verts : Quality Gate passé à 90,4 % sur le nouveau code, après l'ajout de 126 tests (68,7 % au premier passage).

## 4. Clôture

- [x] 4.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (rangement des modules : module, PR, types rendus publics, écarts). Fait dans les PR #413 et #415.
- [x] 4.2 `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage (`docs/architecture/arc42/05-vue-blocs.md`). Document mis à jour dans la PR #413.
