# M18 — MINOS for IntelliJ — exécution

Statut : **TERMINÉ, VALIDÉ ET LIVRÉ — 9/9 sous-incréments.**

Issue : #67 — fermée. PR : #68 — mergée.

## Question produit

> Un développeur peut-il exploiter MINOS quotidiennement dans IntelliJ sans passer en permanence par la CLI ou un agent IA ?

**Réponse M18 : oui**, via un plugin IntelliJ autonome qui reste client externe de MINOS et préserve les contrats, preuves et limites du moteur.

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
- le graphe d'architecture consomme exactement les faits d'architecture MINOS ;
- impact/tests conservent nature, confiance, limitations et chemin de preuve ;
- reindex appelle `minos index`, sans publication IDE parallèle ;
- activité Git factuelle uniquement ;
- aucun LLM requis.

## Sous-incréments

### M18-S1 — Contrat IDE ✅ VALIDÉ

- `minos ide handshake --format json` ;
- protocol id `minos-ide`, version `1` ;
- capabilities explicites ;
- client refuse toute version différente de `1` ;
- tests CLI + validation client dédiée.

### M18-S2 — Plugin bootstrap ✅ VALIDÉ

Projet autonome `minos-intellij/`, IntelliJ Platform Gradle Plugin 2.18.1, cible Java 21, `plugin.xml`, Tool Window et settings MINOS. Aucune dépendance Maven `com.minos:*`.

### M18-S3 — Project status ✅ VALIDÉ

Résolution du projet MINOS par égalité de racine normalisée, état d'index, provider, snapshot et date de dernière indexation. Enregistrement du projet disponible depuis la Tool Window.

### M18-S4 — Navigation symboles ✅ VALIDÉ

Actions : définition, usages, dependents, implementations, related tests, impact, show architecture, copy identity. Les destinations sont ouvertes via VFS et conversion des positions MINOS UTF-8/16/32 vers les offsets IntelliJ.

### M18-S5 — Architecture graph ✅ VALIDÉ

Graphe Swing borné et déterministe, filtre module, sélection de nœud et arêtes provenant exclusivement de `moduleDependencies` du JSON MINOS. Le filtre s'applique à l'ensemble du graphe avant la borne d'affichage.

### M18-S6 — Impact + tests ✅ VALIDÉ

Les résultats conservent le JSON MINOS et donc `nature`, `confidence`, `limitations`, profondeur, chemins/preuves et positions navigables au lieu de fabriquer un score IDE alternatif.

### M18-S7 — Index lifecycle ✅ VALIDÉ

`index`, `--force-full`, `--dry-run` et `doctor` depuis des tâches de fond. L'IDE n'écrit jamais directement les snapshots/pointeurs actifs ; le lifecycle atomique MINOS reste propriétaire de la publication.

### M18-S8 — Git intelligence ✅ VALIDÉ

Commande `git-activity` branchée sur `GitIntelligenceService`, affichage commits/fichiers/zones et contrat explicite `FACTUAL_ACTIVITY` / `importanceInference=false`.

### M18-S9 — Packaging ✅ VALIDÉ

- `buildPlugin` produit le ZIP installable ;
- workflow `.github/workflows/intellij-plugin.yml` ;
- workflow release `.github/workflows/intellij-plugin-release.yml` ;
- Plugin Verifier ;
- gate Windows exact-head ;
- guide installation/configuration/dépannage ;
- portail utilisateur et documentation développeur réconciliés ;
- version plugin surchargeable avec `-PminosVersion`.

## Qualification finale

Runner :

```text
scripts/m18/run-final.ps1
```

Qualification Windows réussie sur le SHA exact :

```text
0186146668c12027f44b55d0511a45e89e6dee61
```

Gates effectivement validés :

1. worktree propre et HEAD inchangé pendant la qualification ;
2. reactor Maven Java 24 `clean verify` vert ;
3. JaCoCo et product facts verts ;
4. tests `ide handshake` et `git-activity` ;
5. absence de dépendance `com.minos:*` dans le build Gradle du plugin ;
6. tests Gradle et build plugin ciblant Java 21 ;
7. protocole incompatible rejeté par le client ;
8. tests de conversion position MINOS -> offset IDE ;
9. graphe borné, déterministe et filtrable ;
10. indexation/reindex uniquement via CLI MINOS ;
11. `verifyPluginProjectConfiguration` et `verifyPluginStructure` ;
12. Plugin Verifier IntelliJ 2026.1 ;
13. production du ZIP plugin ;
14. stabilité finale du HEAD/worktree.

Verdict obtenu :

```text
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
Validated HEAD: 0186146668c12027f44b55d0511a45e89e6dee61
```

Plugin Verifier :

```text
com.minos.codeintelligence:0.2.0-SNAPSHOT
Compatible with IntelliJ IDEA 2026.1 (IU-261.22158.277)
```

Merge final de la PR #68 :

```text
faa51f63c5967d874a7a6685b6b513b83bb736b4
```

Issue #67 fermée comme `completed`. M18 est livré ; M19 devient le prochain jalon séquentiel.
