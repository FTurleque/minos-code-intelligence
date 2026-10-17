# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Avant chaque renommage : rechercher l'ancien nom dans `scripts/` et rejouer le gate qui le cite. Indépendant des autres changements du sprint.

## 1. Relevé et décisions (AUD-QUA-08)

- [ ] 1.1 [AUD-QUA-08] Relire les corps des méthodes et constantes à renommer et arrêter les noms cibles (table D1) ; compter les faux positifs d'un motif à un ou deux chiffres dans les déclarations de `*/src/main/**` ; relever toutes les chaînes publiques citant un jalon et, pour chacune, les goldens, tests et gates qui la figent (déjà connus : quatre goldens de `minos-app/src/test/resources/characterization/`, `scripts/quality/check-runtime-dynamic-consistency.py`) ; relever si SonarCloud analyse `minos-intellij`. Consigner dans `design.md`.
- [ ] 1.2 [AUD-QUA-08] **Décision à obtenir** : D2 (motif du gate à un ou deux chiffres et sort de `qualifiedM1Descriptors`) et D3 (chaînes publiques : hors lot, recommandé, ou second lot). Consigner dans `design.md`.

## 2. Gate et renommage du noyau (AUD-QUA-08)

- [ ] 2.1 [AUD-QUA-08] Rouge : créer `scripts/quality/check-milestone-identifiers.py` avec `--self-test` sur arbres temporaires (déclaration à jalon refusée, fichier à jalon refusé, entrée de liste périmée refusée, fichier listé introuvable refusé, littéral de chaîne ignoré) et le câbler dans `invariants` (`.github/workflows/pr-ci.yml`). **Preuve du rouge : avec une liste vide, le gate échoue sur le dépôt réel en nommant chaque identifiant de la table D1.**
- [ ] 2.2 [AUD-QUA-08] `ScipIndexerCatalog` : renommer les méthodes du catalogue (et `qualifiedM1Descriptors` selon D2) avec leurs appelants (`DefaultMinosApplicationComposer`, `ScipProjectSnapshotLifecycle`) et les tests appelants ; mettre à jour `scripts/docs/product-facts.py:60` **dans cette tâche**. Preuve : `python scripts/docs/product-facts.py --check` et `python scripts/docs/check-current-docs.py` verts ; `./mvnw -B -ntp -pl minos-app -am verify` (goldens inchangés) ; scope JaCoCo `m24-polyglot-provider-platform` (rouge hors régression sur poste Windows : comparer à `develop`).
- [ ] 2.3 [AUD-QUA-08] `BuildDescriptorPolicy` : renommer constantes et fabriques avec leurs appelants (`ProjectFingerprintService`, `ProjectInvalidationService`, `FileProjectFingerprintSnapshotStore`) et `FileProjectFingerprintSnapshotStoreTest`. Preuve : `./mvnw -B -ntp -pl minos-storage-local -am verify` ; `python scripts/architecture/check-module-boundaries.py` vert ; baseline SpotBugs rejouée (`quality/spotbugs-baseline/`), sans alerte masquée.

## 3. Plugin IntelliJ (AUD-QUA-08)

- [ ] 3.1 [AUD-QUA-08] Rouge : test du plugin qui lit `plugin.xml` et compare l'ensemble des `id` d'action à la liste attendue (les huit identifiants `Minos.*` du design, plus les autres actions déclarées), et qui échoue si une classe d'action déclarée n'existe pas. **Preuve du rouge : le test échoue sur une copie où un `id` est modifié.**
- [ ] 3.2 [AUD-QUA-08] Renommer `MinosM21Actions`, `MinosM21Client` et `MinosM21ClientTest` (D1) ; dans `plugin.xml`, ne changer que les huit attributs `class=` (D5). Preuve : `minos-intellij/gradlew check` (Java 21) ; gate 2.1 vert sur le plugin ; `python scripts/architecture/check-module-boundaries.py` (A8, plugin compris) ; `grep -rn "catch (Throwable" minos-intellij/src/main/java` vide.

## 4. Variables d'environnement du runner (AUD-QUA-08)

- [ ] 4.1 [AUD-QUA-08] Relever comment `MINOS_M14_*` traversent les processus fils de `scip-java-windows-runner.ps1` ; renommer en `MINOS_SCIP_JAVA_*` aux trois endroits (pose, lecture, restauration), fichier conservé en CRLF (D4). Preuve : `git grep MINOS_M14` vide hors `docs/` et `openspec/` ; tests du runner de `minos-provider-scip` verts (`./mvnw -B -ntp -pl minos-provider-scip -am verify`).
- [ ] 4.2 **(manuelle)** [AUD-QUA-08] Sur un poste Windows : `minos index` d'un projet Java Maven réel par le runner renommé ; relever le code de sortie et l'état de l'index. Sans cette preuve, la tâche 4.1 reste « implémentée, preuve Windows en attente ».

## 5. Chaînes publiques (AUD-QUA-08) — seulement si D3 retient le second lot

- [ ] 5.1 [AUD-QUA-08] Rouge : goldens et tests attendus mis à jour d'abord pour les chaînes retenues. **Preuve du rouge : les tests de caractérisation échouent.** Puis remplacer le jalon par la capacité dans chaque chaîne, mettre à jour `scripts/quality/check-runtime-dynamic-consistency.py` et `docs/user/mcp.md` dans la même tâche. Preuve : `./mvnw -B -ntp -pl minos-app -am verify` ; `python scripts/docs/check-current-docs.py`. Si D3 retient « hors lot », cocher cette tâche comme sans objet et le dire dans `SUIVI.md`.

## 6. Clôture

- [ ] 6.1 Compléter la « Mise en œuvre » de l'ADR 0043 (convention étendue aux identifiants de production, gate, liste restante) ; `docs/developer/quality-gates.md` (gate neuf). Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 6.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-QUA-08 : périmètre réel, décisions D2 et D3, preuve Windows 4.2, entrées restantes de la liste).
- [ ] 6.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, `docs/developer/quality-gates.md`, `docs/user/intellij-plugin.md` si elle nomme une classe, ADR 0043.
