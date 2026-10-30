# Tasks

Ordre : mesure, levée du cycle, déplacement, preuve. Commande de référence : `./mvnw.cmd -B -ntp -pl minos-cli -am test` (outillage Windows : `docs/audit/2026-10-10/SUIVI.md`).

## 1. Lever le cycle

- [x] 1.1 Écrire le test rouge `CliExitCodesTest` (les quatre constantes de `FindSymbolCommand` valent celles de `CliExitCodes` : 0, 1, 2, 3), puis créer `CliExitCodes` et y faire renvoyer `FindSymbolCommand`, `CliCommandSupport` et `DockerRuntimeBootstrap` (D2). Le rouge est un échec de compilation (la classe n'existait pas) ; il n'a pas été rejoué à part.

## 2. Déplacement

- [x] 2.1 Rejouer l'analyse des dépendances du découpage D1 (aucun cycle après 1.1) et la consigner si elle diffère de `design.md`.
- [x] 2.2 Déplacer les classes et leurs tests par `git mv` (historique conservé), réécrire les `package` et les imports, dans `minos-cli` et `minos-app` ; les six classes de la racine ne bougent pas.
- [x] 2.3 Élargir la visibilité au strict nécessaire (D3), guidé par le compilateur ; consigner le nombre de types et de membres rendus publics.

## 3. Preuve

- [x] 3.1 `./mvnw.cmd -B -ntp -pl minos-cli -am test` : même nombre de tests exécutés et sautés qu'avant le déplacement, plus ceux de 1.1.
- [x] 3.2 `./mvnw.cmd -B -ntp -pl minos-app -am test-compile` : les tests de `minos-app` qui nomment des classes de `minos-cli` compilent.
- [x] 3.3 Rejouer `python .claude/scripts/run_gates.py --fast` (dont `check-module-boundaries.py` A3 et A8, `check-jacoco.py`, les gates de cohérence et `remediation` qui citent le module, `product-facts.py --check`), mettre à jour les chemins littéraux.
- [x] 3.4 Régénérer le baseline SpotBugs du module (`-Paudit-spotbugs`) ; une alerte nouvelle se corrige ou se consigne, avec sa raison dans `design.md`.
- [x] 3.5 Mesurer la couverture JaCoCo lignes + conditions des fichiers déplacés ; ajouter dans `minos-cli` les tests nécessaires pour tenir le seuil de 80 % de SonarCloud. Mesure locale : 82,72 %, aucun test ajouté ; la valeur de SonarCloud (Linux) reste à relever en 4.1.
- [x] 3.6 Mettre à jour `docs/architecture/arc42/05-vue-blocs.md` et `docs/developer/README.md` (sous-packages du module) ; rejouer `python scripts/docs/check-current-docs.py`.

## 4. Preuve sur les deux plateformes

- [x] 4.1 **(manuelle)** Relever sur `Verify (ubuntu-24.04)` et `Verify (windows-2022)` que les tests du module et les IT du jar ombré passent, et que `SonarCloud Code Analysis` passe. Relevé le 2026-10-12 sur la PR #415, fusionnée tous checks verts : Quality Gate passé à 82,7 % sur le nouveau code, sans test ajouté.

## 5. Clôture

- [x] 5.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (rangement des modules : module, PR, types rendus publics, écarts), après la fusion de la PR #413 pour éviter le conflit.
- [x] 5.2 `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage.
