# Tasks

Ordre : mesure, déplacement, preuve. Commande de référence : `./mvnw.cmd -B -ntp -pl minos-runtime-local -am test` (outillage Windows : `docs/audit/2026-10-10/SUIVI.md`).

## 1. Déplacement

- [x] 1.1 Rejouer l'analyse des dépendances du découpage D1 (aucun cycle, liste des types à rendre publics) et la consigner si elle diffère de `design.md`.
- [x] 1.2 Déplacer les 31 classes et leurs tests par `git mv` (historique conservé), réécrire les `package` et les imports (dans `minos-bootstrap`, `minos-cli`, `minos-provider-scip`, `minos-app`) ; les huit classes de la racine, `META-INF/services` et les ressources ne bougent pas.
- [x] 1.3 Élargir la visibilité au strict nécessaire (D2), guidé par le compilateur ; consigner le nombre de types et de membres rendus publics.

## 2. Preuve

- [x] 2.1 `./mvnw.cmd -B -ntp -pl minos-runtime-local -am test` : même nombre de tests exécutés et sautés qu'avant le déplacement.
- [x] 2.2 `./mvnw.cmd -B -ntp -pl minos-app -am test-compile` : tous les modules qui importent des classes déplacées compilent, tests compris.
- [x] 2.3 Rejouer `python .claude/scripts/run_gates.py --fast` (dont `check-module-boundaries.py` A3 et A8, `check-private-io.py`, `check-jacoco.py`, les gates `remediation` et de cohérence qui citent le module), mettre à jour les chemins littéraux.
- [x] 2.4 Régénérer le baseline SpotBugs du module (`-Paudit-spotbugs`) ; une alerte nouvelle se corrige ou se consigne, avec sa raison dans `design.md`.
- [x] 2.5 Mesurer la couverture JaCoCo lignes + conditions des fichiers déplacés ; ajouter dans le module les tests nécessaires pour tenir le seuil de 80 % de SonarCloud.
- [x] 2.6 Faire relire le diff par l'agent `minos-security-reviewer` (visibilité des primitives de confinement et d'environnement) et traiter ses constats.
- [x] 2.7 Mettre à jour `docs/architecture/arc42/05-vue-blocs.md` (sous-packages du module) ; rejouer `python scripts/docs/check-current-docs.py`.

## 3. Preuve sur les deux plateformes

- [ ] 3.1 **(manuelle)** Relever sur `Verify (ubuntu-24.04)` et `Verify (windows-2022)` que les tests de confinement s'exécutent (aucun saut silencieux) et que `SonarCloud Code Analysis` passe.

## 4. Clôture

- [ ] 4.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (rangement des modules : module, PR, types rendus publics, écarts).
- [ ] 4.2 `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage.
