# Tasks

Ordre : mesure sur les deux systèmes, décisions, puis par section rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Aucun plancher n'est écrit avant la mesure de la section 1. Les littéraux exigés par d'autres gates (design, D6) restent au caractère près.

## 1. Mesure et décisions (AUD-TST-01, AUD-TST-02)

- [ ] 1.1 [AUD-TST-01] Relever les rapports JaCoCo de deux runs `Verify` réussis de `develop` par système (artefacts `jacoco-modules-Linux`, `jacoco-modules-Windows` et rapport agrégé de `pr-ci.yml`, en lecture seule) : lignes et branches de `com/minos/` avec et sans `com/minos/app/`, par système. Consigner dans `design.md` les chiffres, les run ids et la date.
- [ ] 1.2 [AUD-TST-02] Rejouer l'appariement « fichiers de production sous un scope » (attendu au HEAD : 221 sur 527) et relever, par système, lignes et branches de chaque classe de D4 et des quatre autres gros absents. Consigner dans `design.md`.
- [ ] 1.3 [AUD-TST-01, AUD-TST-02] **Décision à obtenir** : D3 (marge de 2 points recommandée, cliquet à chaque clôture de sprint) ; scope ciblé ou non pour `ProjectIgnoreRules`, `IdeIntelligenceCommand`, `ScipIngestionAdapter`, `SandboxLauncherScript` au vu de 1.2. Consigner dans `design.md`.
- [ ] 1.4 [AUD-TST-04] **Décision à obtenir** : D5 (pas de suite de contrat commune dans ce changement, recommandé ; ou classe de contrat dans le `test-jar` de `minos-engine`). Consigner dans `design.md`.

## 2. Le gate porte un plancher par plateforme et des exclusions (AUD-TST-01)

- [ ] 2.1 [AUD-TST-01] Rouge : scénarios ajoutés à l'auto-test de `scripts/quality/check-jacoco.py` — plancher par système respecté ; mesure sous le plancher du système courant ; plancher absent pour le système courant (échec, pas `SKIPPED`) ; exclusion nommée appliquée ; exclusion sans objet refusée. **Preuve du rouge : `python scripts/quality/check-jacoco.py --self-test` échoue sur ces scénarios au HEAD.**
- [ ] 2.2 [AUD-TST-01] Étendre le gate (D1, D2) sans changer la forme ni les valeurs des 28 scopes existants ; mettre à jour le décompte du message de succès de l'auto-test. Preuve : auto-test vert ; `python scripts/docs/check-current-docs.py`, `python scripts/remediation/check-p0-p2.py`, `python scripts/remediation/check-audit-remediation-v2.py`, `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-ci-wiring.py` et les six `check-*-consistency.py` de `scripts/quality/` qui lisent le gate, tous verts.

## 3. Scope `global` (AUD-TST-01)

- [ ] 3.1 [AUD-TST-01] Rouge : sur un rapport local (`./mvnw -B -ntp verify`, long ; `clean` à la racine supprime `target/`), exécuter le gate avec un scope `global` au plancher provisoirement supérieur à la mesure. **Preuve du rouge : le gate nomme `global`, la mesure et le plancher.**
- [ ] 3.2 [AUD-TST-01] Déclarer `global` (`com/minos/`, exclusion nommée `com/minos/app/`, planchers Linux et Windows issus de 1.1 et de D3), mesure et date en commentaire. Preuve : gate vert sur le rapport local du système courant ; `jacoco-gate.json` contient la mesure, le plancher et l'exclusion ; dire quel système n'a pas été rejoué localement.

## 4. Scopes du cœur communs (AUD-TST-02)

- [ ] 4.1 [AUD-TST-02] Rouge puis test : aucun test ne nomme `LeaseDeadline`. Écrire dans `minos-engine` un test de comportement et de refus (échéance dépassée, bornes). **Preuve du rouge : la classe est sous le plancher visé dans le rapport de 1.2.** Preuve : `./mvnw -B -ntp -pl minos-engine -am test`.
- [ ] 4.2 [AUD-TST-02] Déclarer `storage-codec-and-state` et `confined-io-primitives` (D4), planchers issus de 1.2, plancher de classe pour `LeaseDeadline`. Rouge : chaque scope déclaré d'abord avec un préfixe volontairement faux. **Preuve du rouge : « declared prefix(es) match no class ».** Preuve : gate vert ; scopes JaCoCo `storage-codec-and-state`, `confined-io-primitives`, `persistence-cache-indexes` (inchangé).
- [ ] 4.3 [AUD-TST-02] Déclarer `run-directory-retention` et rattacher `ManagedScipProviderRuntimeManager` à `m24-polyglot-provider-platform` avec un plancher de classe ; conserver au caractère près les deux lignes exigées par `check-p0-p2.py:338-341`. Preuve : gate vert sur Linux ; sur poste Windows, comparer `m24-polyglot-provider-platform` à `develop` (rouge hors régression) ; `python scripts/remediation/check-p0-p2.py` et `python scripts/quality/check-polyglot-provider-consistency.py` verts.

## 5. Classes propres à une plateforme (AUD-TST-02, AUD-TST-12)

- [x] 5.1 [AUD-TST-12] Rouge : sous Windows, `provider-sandbox-windows` avec un plancher de classe pour `WindowsJobObjectProcessOwnership` provisoirement supérieur à la mesure. **Preuve du rouge : le gate nomme la classe.** Correction : préfixe ajouté au scope, plancher de classe issu de 1.2 (rapport Windows). Preuve : gate vert sous Windows ; scope `SKIPPED` sous Linux.
- [ ] 5.2 [AUD-TST-02] Même démarche pour `CgroupJobOwnership` dans `provider-sandbox-linux` (rapport Linux). Preuve : gate vert sous Linux ; scope `SKIPPED` sous Windows. Dire quel système n'a été vérifié qu'en CI.

## 6. Plancher de classe du store PostgreSQL (AUD-TST-04)

- [x] 6.1 [AUD-TST-04] Rouge : `prefixMinimums` pour `com/minos/storage/postgresql/snapshot/PostgresRuntimeObservationStore` **sans** déclarer le préfixe. **Preuve du rouge : « prefix minimum references undeclared prefix ».** Correction : préfixe de classe ajouté à côté du préfixe de paquet, plancher issu du rapport Linux de 1.1. Preuve : gate vert sur un rapport Linux avec PostgreSQL ; scope JaCoCo `m30-postgresql-pgvector` ; `python scripts/docs/check-current-docs.py` vert (nom du scope conservé).
- [ ] 6.2 [AUD-TST-04] Si D5 retient la suite de contrat : l'écrire dans cette tâche avec ses deux héritiers, puis `./mvnw -B -ntp -pl minos-storage-postgresql -am verify` et `./mvnw -B -ntp -pl minos-storage-local -am verify` ; dire si les tests PostgreSQL ont tourné localement ou seulement en CI.

## 7. Preuve en CI

- [ ] 7.1 **(manuelle)** [AUD-TST-01, AUD-TST-02, AUD-TST-04, AUD-TST-12] À la PR : `Verify (ubuntu-24.04)` et `Verify (windows-2022)` verts ; relever dans les journaux la ligne `JaCoCo global` de chaque système et les scopes `SKIPPED` attendus ; noter les run ids dans `SUIVI.md`.

## 8. Clôture

- [ ] 8.1 `docs/developer/quality-gates.md` et `docs/developer/testing.md` : scope `global`, planchers par système, règle du cliquet (D3), exclusions nommées. Rejouer `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`.
- [ ] 8.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-TST-01, AUD-TST-02, AUD-TST-04, AUD-TST-12 : statuts, décomptes recalculés, mesures par système, décisions D3 et D5, run ids).
- [ ] 8.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/quality-gates.md`, `docs/developer/testing.md`.
