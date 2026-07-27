# M18 — MINOS for IntelliJ — exécution

Statut de branche : **9/9 implémentés ; qualification exact-head en attente des runners GitHub Actions**.

Issue : #67. PR : #68.

## Question produit

> Un développeur peut-il exploiter MINOS quotidiennement dans IntelliJ sans passer en permanence par la CLI ou un agent IA ?

## Architecture retenue

```text
IntelliJ IDEA (Java 21)
        │
        │  processus local + JSON
        ▼
MINOS CLI / IDE protocol v1 (Java 24)
        │
        ▼
MinosApplication
        ├── ProjectInspectionService
        ├── ProjectQueryService
        ├── ProjectArchitectureQuery
        ├── ProjectImpactQuery
        ├── GitIntelligenceService
        └── lifecycle indexation/provider existant
```

Le plugin est un client externe. Il ne dépend d'aucun artefact Maven MINOS et ne charge aucune classe interne du moteur. Le découplage de JVM est volontaire : la plateforme IntelliJ cible Java 21 tandis que le moteur MINOS reste qualifié en Java 24.

## Invariants

- protocole `minos-ide` versionné ;
- handshake avant requêtes métier ;
- protocole incompatible = erreur explicite, aucune dégradation silencieuse ;
- processus CLI bornés en durée et annulables ;
- toutes les requêtes MINOS exécutées hors EDT ;
- navigation IDE depuis les `fileId`/positions retournés par MINOS ;
- le graphe d'architecture consomme exactement `minos architecture --format json` ;
- impact/tests conservent nature, confiance, limitations et chemin de preuve ;
- reindex appelle `minos index`, sans publication IDE parallèle ;
- activité Git factuelle uniquement ;
- aucun LLM requis.

## Sous-incréments

### M18-S1 — Contrat IDE ✅ IMPLÉMENTÉ

- `minos ide handshake --format json` ;
- protocol id `minos-ide`, version `1` ;
- capabilities explicites ;
- client refuse toute version différente de `1` ;
- tests CLI + validation client dédiée.

### M18-S2 — Plugin bootstrap ✅ IMPLÉMENTÉ

Projet autonome `minos-intellij/`, IntelliJ Platform Gradle Plugin 2.18.1, cible Java 21, `plugin.xml`, Tool Window et settings MINOS. Aucune dépendance Maven `com.minos:*`.

### M18-S3 — Project status ✅ IMPLÉMENTÉ

Résolution du projet MINOS par égalité de racine normalisée, état d'index, provider, snapshot et date de dernière indexation. Enregistrement du projet disponible depuis la Tool Window.

### M18-S4 — Navigation symboles ✅ IMPLÉMENTÉ

Actions : définition, usages, dependents, implementations, related tests, impact, show architecture, copy identity. Les destinations sont ouvertes via VFS et conversion des positions MINOS UTF-8/16/32 vers les offsets IntelliJ.

### M18-S5 — Architecture graph ✅ IMPLÉMENTÉ

Graphe Swing borné et déterministe, filtre module, sélection de nœud et arêtes provenant exclusivement de `moduleDependencies` du JSON MINOS.

### M18-S6 — Impact + tests ✅ IMPLÉMENTÉ

Les résultats conservent le JSON MINOS et donc `nature`, `confidence`, `limitations`, profondeur, chemins/preuves et positions navigables au lieu de fabriquer un score IDE alternatif.

### M18-S7 — Index lifecycle ✅ IMPLÉMENTÉ

`index`, `--force-full`, `--dry-run` et `doctor` depuis des tâches de fond. L'IDE n'écrit jamais directement les snapshots/pointeurs actifs ; le lifecycle atomique MINOS reste propriétaire de la publication.

### M18-S8 — Git intelligence ✅ IMPLÉMENTÉ

Commande `git-activity` branchée sur `GitIntelligenceService`, affichage commits/fichiers/zones et contrat explicite `FACTUAL_ACTIVITY` / `importanceInference=false`.

### M18-S9 — Packaging ✅ IMPLÉMENTÉ

- `buildPlugin` produit le ZIP installable ;
- workflow `.github/workflows/intellij-plugin.yml` ;
- Plugin Verifier ;
- gate Windows exact-head ;
- guide installation/configuration/dépannage ;
- portail utilisateur et documentation développeur réconciliés.

## Qualification finale

Runner :

```text
scripts/m18/run-final.ps1
```

Il doit prouver sur le SHA exact :

1. worktree propre et HEAD inchangé pendant la qualification ;
2. reactor Maven Java 24 `clean verify` vert ;
3. tests `ide handshake` et `git-activity` ;
4. absence de dépendance `com.minos:*` dans le build Gradle du plugin ;
5. build/tests/plugin verification ciblant Java 21 ;
6. protocole incompatible rejeté par le client ;
7. tests de conversion position MINOS -> offset IDE ;
8. graphe borné et déterministe à entrée identique ;
9. indexation/reindex uniquement via CLI MINOS ;
10. documentation et packaging cohérents.

Verdict unique :

```text
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
```

### État de qualification GitHub

Les premières exécutions GitHub Actions de la PR #68 ont échoué avant publication d'étapes exploitables ; les jobs retournent actuellement zéro étape et les blobs de logs sont indisponibles via l'API. Ce document ne marque donc pas M18 comme **qualifié** tant qu'un run exact-head Maven + plugin n'a pas effectivement produit le verdict attendu.
