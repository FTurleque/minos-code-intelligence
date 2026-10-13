# Tasks

Ordre : rouge, correction, preuve. Changement limité à `minos-storage-postgresql` ; tout `-pl` s'accompagne de `-am`.

## 1. Relevé et décision (AUD-PERF-12)

- [ ] 1.1 [AUD-PERF-12] Lire `PostgresConnectionFactory` en entier : structure des connexions au repos, réutilisation par des `withConnection` imbriqués, gestion actuelle de l'auto-commit et des exceptions à la restitution ; lire `PostgresConnectionFactoryPoolTest`. Consigner dans `design.md` (lève le « non vérifié »).
- [ ] 1.2 [AUD-PERF-12] **Décision à obtenir** : D2 (nouvel essai unique sur la première instruction, recommandé, ou erreur directe). Consigner dans `design.md`.

## 2. Validation bornée (AUD-PERF-12)

- [ ] 2.1 [AUD-PERF-12] Rouge : cas ajoutés à `PostgresConnectionFactoryPoolTest` avec `Connection` espion et horloge injectée — emprunt/restitution immédiats → 0 `isValid` ; emprunt après le seuil → 1 ; invalide après le seuil → fermée et remplacée ; restitution après exception → retirée ; auto-commit désactivé → retirée ; connexion rompue sous le seuil → comportement D2 ; aucune écriture rejouée. **Preuve du rouge : 2 `isValid` par emprunt/restitution au HEAD.**
- [ ] 2.2 [AUD-PERF-12] `PostgresConnectionFactory` : instant de restitution sur horloge monotone injectable, seuil nommé (D1), restitution par contrôle local (D3), comportement D2. Preuve : tests verts ; `./mvnw -B -ntp -pl minos-storage-postgresql -am verify` ; scope JaCoCo `m30-postgresql-pgvector`. Dire si les tests PostgreSQL réels ont tourné sur le poste ou seulement en CI.
- [ ] 2.3 [AUD-PERF-12] Mesure indicative : nombre d'allers-retours d'une requête de symboles avant et après (compteur du pilote ou espion). Ne pas annoncer de gain en durée sans base distante.

## 3. Clôture

- [ ] 3.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-12 : statut, décision D2, compteurs).
- [ ] 3.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, documentation d'exploitation PostgreSQL si le comportement sur coupure y est décrit.
