# Scénarios qualité

> Référence : [Section 10 — Exigences qualité](../arc42/10-exigences-qualite.md)

Ce fichier liste les scénarios qualité en format tabulaire condensé pour les revues.

---

## Synthèse

| Réf | Qualité | Stimulus | Seuil | Vérification |
|-----|---------|---------|-------|-------------|
| QS-1 | Exactitude | Relation `CALLS` retournée par provider | 0 relation sans `Evidence` | Test unitaire `RelationshipNormalizerTest` |
| QS-2 | Confidentialité | Requête `find-symbol` en mode natif | 0 connexion réseau sortante | Test d'intégration M14 + capture réseau |
| QS-3 | Extensibilité | Nouveau provider Python | 0 fichier modifié dans `minos-domain`/`minos-engine` | Diff git + compilation Maven |
| QS-4 | Stabilité surfaces | Script CLI M14 sur distribution M29 | 0 breaking change | Tests Failsafe `minos-app` replay M14 |
| QS-5 | Performance | 20 requêtes `find-symbol` successives | p50 ≤ 5 ms | Benchmark E1-in-memory-repeat |
| QS-6 | Résilience | Snapshot tronqué au démarrage | 0 crash JVM ; exit 1 avec message | Test `SnapshotIntegrityServiceTest` |
| QS-7 | Maintenabilité | Dépendance interdite `engine → cli` ajoutée | Échec de compilation Maven | Reactor Maven + CI |

---

## Scénarios manquants identifiés

| # | Scénario à créer | Qualité | Priorité |
|---|-----------------|---------|---------|
| SM-1 | Chiffrement tenant : données illisibles sans clé | Q-2 Confidentialité | Haute |
| SM-2 | Rotation de clé tenant sans perte d'audit | Q-2 Confidentialité | Haute |
| SM-3 | Indexation incrémentale sur fichier modifié unique | Q-5 Performance | Moyenne |
| SM-4 | Requête MCP après redémarrage sans snapshot | Q-6 Résilience | Moyenne |
| SM-5 | Export NEXUS sur snapshot vide | Q-1 Exactitude | Faible |
