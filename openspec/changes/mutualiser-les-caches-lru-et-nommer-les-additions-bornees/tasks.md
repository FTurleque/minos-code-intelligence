# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Deux lots : sections 1-2 (renommage, livrable seul) puis 3-5 (cache partagé), ce second lot **après** la fusion des changements actifs `ramener-le-poids-des-vues-a-la-mesure`, `lire-l-identite-du-snapshot-actif-sans-le-charger`, `ne-plus-hacher-les-sources-avant-le-cache-du-graphe`, `synchroniser-l-index-semantique-par-differentiel` et `lire-chaque-source-une-fois-par-fichier`.

## 1. Relevé (AUD-QUA-05)

- [ ] 1.1 [AUD-QUA-05] Relire le corps des 21 helpers et dresser la table nom actuel → comportement au dépassement → nom cible, dont les trois `safeMultiply` et les deux `saturatingMultiply` (non relus à la spécification). Rechercher chaque nom actuel dans `scripts/` (`check-mne.py:86` exige `safeMultiply` dans `FileSymbolSnapshotStore.java`). Consigner la table dans `design.md`.

## 2. Lot 1 — nommer par le comportement (AUD-QUA-05)

- [ ] 2.1 [AUD-QUA-05] Rouge : créer `scripts/quality/check-bounded-helpers.py` avec `--self-test` sur arbres temporaires (déclaration `safeAdd` refusée, `checkedAdd` qui sature refusé, `checkedAdd` qui lève accepté) et le câbler dans `invariants` (`.github/workflows/pr-ci.yml`). **Preuve du rouge : le gate échoue sur le dépôt réel (8 `safeAdd`, 1 `checkedAdd` qui sature).**
- [ ] 2.2 [AUD-QUA-05] Renommer en place les `safeAdd` et `checkedAdd` privés selon la table 1.1 (D1), sans modifier une ligne de calcul ; laisser `safeMultiply` dans `FileSymbolSnapshotStore`. Preuve : gate vert ; `./mvnw -B -ntp verify` (quatre modules touchés : dire ce qui a tourné localement) ; `python scripts/remediation/check-mne.py`, `check-mnd.py`, `check-p0-p2.py` verts ; `python scripts/quality/check-ci-wiring.py` et `check-single-execution.py` verts ; scopes JaCoCo `semantic-hybrid-retrieval`, `semantic-vector-store`, `persistence-cache-indexes`, `m30-postgresql-pgvector`, `m25-remote-distributed-indexing`, `java-advanced-provider`.

## 3. Lot 2 — décisions et classe partagée (AUD-QUA-05)

- [ ] 3.1 [AUD-QUA-05] Relever, pour chacun des sept caches : borne en entrées, plafond de poids, traitement d'une entrée plus lourde que le plafond, moment où les statistiques sont comptées, verrou utilisé, tests existants. Consigner les différences dans `design.md`.
- [ ] 3.2 [AUD-QUA-05] **Décision à obtenir** : D2 (module et package d'accueil ; `minos-engine` recommandé) et D3 (`HostedDenialThrottle` exclu, recommandé). Consigner dans `design.md`.
- [ ] 3.3 [AUD-QUA-05] Rouge : tests de la classe partagée dans son module (éviction par le poids et par le nombre, poids nul après retraits et remplacements, entrée trop lourde refusée, accès concurrents par barrières déterministes). **Preuve du rouge : les tests ne compilent pas tant que la classe n'existe pas.** Puis la classe (D4). Preuve : `./mvnw -B -ntp -pl minos-engine -am verify` ; `python scripts/architecture/check-module-boundaries.py` (A8 à 0 cycle, package à propriétaire unique) ; couverture de la classe ≥ 80 % dans son module.

## 4. Lot 2 — migration des sept caches (AUD-QUA-05)

- [ ] 4.1 [AUD-QUA-05] Rouge : étendre le gate (carte à ordre d'accès hors classe partagée, liste fermée, entrée périmée) et son auto-test. **Preuve du rouge : 7 fichiers signalés sur le dépôt réel.**
- [ ] 4.2 [AUD-QUA-05] `ProgramGraphService` et `FingerprintConstrainedJavaProgramGraphProvider` : tests existants verts avant, migration, verts après ; réécrire dans cette tâche `check-mnd.py:101-104` (chaînes devenues fausses) en conservant `public CacheStats cacheStats()` et les chaînes `buildLock` de `check-p0-p2.py`. Scopes JaCoCo `program-graph-analysis`, `java-advanced-provider`, `advanced-impact-security`.
- [ ] 4.3 [AUD-QUA-05] `SemanticSearchService` et `HybridSearchService` : même méthode ; `check-mnd.py:105-106` (`trimCorpusCache`) réécrit dans cette tâche. Scope `semantic-hybrid-retrieval`.
- [ ] 4.4 [AUD-QUA-05] `FileSemanticVectorStore` et `FileSymbolSnapshotStore` : même méthode ; `check-mne.py` et `check-p0-p2.py:309-312` rejoués. Scopes `semantic-vector-store`, `persistence-cache-indexes`.
- [ ] 4.5 [AUD-QUA-05] `PostgresCodeKnowledgeSnapshotStore` : même méthode ; `check-mnd.py:99-100` (`queryCache`, `MAX_QUERY_CACHE_WEIGHT_BYTES`, `buildLocks`) conservé ou réécrit dans cette tâche. Scope `m30-postgresql-pgvector`. Tests PostgreSQL réels : dire s'ils ont tourné localement ou seulement en CI.
- [ ] 4.6 [AUD-QUA-05] Preuve d'ensemble : gate vert sur le dépôt (0 hors liste) ; `./mvnw -B -ntp verify` ; `python .claude/scripts/run_gates.py`.

## 5. Clôture

- [ ] 5.1 `docs/developer/quality-gates.md` : le gate neuf et sa liste fermée ; `docs/developer/architecture.md` si un package est créé. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 5.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-QUA-05 : décomptes corrigés 7 et 21, double comportement de `checkedAdd`, décisions D2 et D3, état de chaque lot).
- [ ] 5.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, `docs/developer/quality-gates.md`, « Mise en œuvre » de l'ADR 0044 si D2 crée un package.
