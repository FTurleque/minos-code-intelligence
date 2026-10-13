# Tasks

Ordre dans chaque section : rouge, correction, preuve. Prérequis : `lire-l-identite-du-snapshot-actif-sans-le-charger` (AUD-PERF-01) fusionné. Tout `-pl` s'accompagne de `-am`. Dire pour chaque preuve PostgreSQL si elle a tourné sur le poste ou seulement en CI.

## 1. Sortie anticipée (AUD-PERF-06)

- [ ] 1.1 [AUD-PERF-06] Rouge : test de `SemanticIndexService` avec store et fabrique à compteurs — index prêt et cible identique → 0 document construit, 0 embedding, 0 écriture ; modèle différent → chemin complet. **Preuve du rouge : tous les documents sont reconstruits au HEAD.**
- [ ] 1.2 [AUD-PERF-06] `SemanticIndexService.synchronize` : comparaison d'identité complète (D1). Relever si le compte rendu d'indexation doit signaler la synchronisation sautée (additif) et le faire le cas échéant. Preuve : tests verts ; scope JaCoCo `semantic-hybrid-retrieval` ; `./mvnw -B -ntp -pl minos-cli -am test`.

## 2. Réutilisation décidée sans vecteurs (AUD-PERF-06)

- [ ] 2.1 [AUD-PERF-06] Rouge : test à compteurs — un document modifié sur N → 1 embedding demandé, aucun chargement complet de l'index précédent. **Preuve du rouge : `store.load` appelé au HEAD.**
- [ ] 2.2 [AUD-PERF-06] Lecture (clé, empreinte) sur le port vectoriel en `default`, redéfinie sous PostgreSQL ; marque de réutilisation sur le document transmis au store (D3). Preuve : tests verts ; `python scripts/remediation/check-mne.py`, `check-mnd.py`, `check-semantic-retrieval-consistency.py` (mis à jour dans cette tâche si un littéral change).

## 3. Écriture différentielle PostgreSQL (AUD-PERF-06)

- [ ] 3.1 [AUD-PERF-06] Rouge : tests d'intégration PostgreSQL — équivalence ligne à ligne avec un remplacement complet sur les sept cas (D4) ; un vecteur écrit pour un document modifié sur N (compteur d'instructions ou statistiques de table) ; document sans vecteur non marqué → échec, état inchangé ; échec injecté après suppression → état d'avant.
- [ ] 3.2 [AUD-PERF-06] `PostgresSemanticWriteQueries` / `PostgresSemanticVectorStore` : séquence D2 dans la transaction conditionnelle, mise à jour des positions des lignes réutilisées (écart 2). Preuve : tests de 3.1 verts ; test de migration existant vert (aucune version de schéma ajoutée) ; scope `m30-postgresql-pgvector`.
- [ ] 3.3 [AUD-PERF-06] Mesure indicative avant/après sur un index réel : durée de synchronisation et volume écrit pour 1 document modifié. Optionnel : essai d'envoi binaire des vecteurs, consigné comme mesuré ou non faisable, sans l'adopter ici.

## 4. Clôture

- [ ] 4.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-06 : statut, écarts 1 à 3, mesures, ce qui n'a pas été mesuré).
- [ ] 4.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, documentation d'exploitation PostgreSQL si elle décrit la synchronisation.
