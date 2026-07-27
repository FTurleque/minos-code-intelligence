# M18 — MINOS for IntelliJ — exécution

Statut de branche : **0/9 qualifiés ; implémentation en cours**.

Issue : #67.

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

### M18-S1 — Contrat IDE 🚧

- `minos ide handshake --format json` ;
- protocol id `minos-ide`, version `1` ;
- capabilities explicites ;
- client refuse toute version différente de `1`.

Gate : fixture client + tests CLI du handshake.

### M18-S2 — Plugin bootstrap 🚧

Projet autonome `minos-intellij/`, IntelliJ Platform Gradle Plugin 2.x, Java 21, plugin.xml, tool window et settings MINOS.

Gate : `gradle test buildPlugin verifyPlugin`.

### M18-S3 — Project status 🚧

Résolution du projet MINOS par égalité de root normalisée, état `READY/STALE/FAILED`, provider, snapshot et date de dernière indexation.

### M18-S4 — Navigation symboles 🚧

Actions : usages, dependents, implementations, related tests, impact, show architecture, copy identity. Les destinations sont ouvertes via VFS + ligne/colonne MINOS.

### M18-S5 — Architecture graph 🚧

Graphe Swing borné, filtre module, sélection de nœud, détails d'arêtes et navigation.

### M18-S6 — Impact + tests 🚧

Vues dédiées conservant `nature`, `confidence`, `limitations`, profondeur et chemins explicatifs.

### M18-S7 — Index lifecycle 🚧

`index`, `--force-full`, `--dry-run` et `doctor` depuis des tâches de fond. L'IDE n'écrit jamais directement les snapshots/pointeurs actifs.

### M18-S8 — Git intelligence 🚧

Commande `git-activity` branchée sur `GitIntelligenceService`, affichage commits/fichiers/zones et avertissement permanent : activité != importance.

### M18-S9 — Packaging 🚧

ZIP plugin versionné, GitHub Actions dédiée, Plugin Verifier, documentation installation/configuration/dépannage.

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
5. build/tests/plugin verification Java 21 ;
6. protocole incompatible rejeté par le client ;
7. tests de conversion position MINOS -> offset IDE ;
8. graphe borné et déterministe à entrée identique ;
9. indexation/reindex uniquement via CLI MINOS ;
10. documentation et packaging cohérents.

Verdict unique :

```text
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
```
