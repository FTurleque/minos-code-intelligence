# Runtime & Dynamic Intelligence — architecture M26

M26 ajoute une couche d’observations runtime séparée de l’autorité statique. L’architecture préserve les frontières M17/M20/M25 : aucun branchement langage dans la découverte, aucun chemin parallèle de publication statique et aucune promotion de capability.

## Flux

```text
minos-runtime-observation-v1 (strict UTF-8 TSV, PARTIAL)
  ↓ RuntimeObservationEnvelopeCodec
RuntimeObservationSession + SHA-256 source
  ↓ RuntimeIntelligenceService
active CodeKnowledgeSnapshot exact
  ↓ SymbolIndex: symbolKey → qualifiedName → file/line
CorrelatedRuntimeSession
  ↓ RuntimeObservationStore
FileRuntimeObservationStore (.mrt immutable, atomic, bounded, checksum)
  ↓
CLI runtime report/symbol + MCP read-only
```

## Modèle et sémantique

Le package `com.minos.dynamic` distingue :

- `RuntimeObservationSession` : identité, UUID projet, snapshot exact, fenêtre, collector/version, environnement et complétude `PARTIAL` ;
- `RuntimeObservation` : `SYMBOL_EXECUTION`, `CALL` ou `LINE_COVERAGE`, hits et durée déclarée ;
- `RuntimeSymbolReference` : identités provider-neutral et fichier relatif confiné ;
- `RuntimeSymbolResolution` : `RESOLVED`, `AMBIGUOUS` ou `UNRESOLVED`, avec candidats bornés ;
- `CorrelatedRuntimeSession` : observation brute, corrélation, heure d’import et SHA-256 source.

`OBSERVED_PARTIAL` est une nature externe explicite. Elle ne remplace pas `InformationNature` des faits structurés et ne rend jamais une trace exhaustive. `observedSymbolRatio` mesure les identités statiques corrélées dans les sessions sélectionnées ; ce n’est pas une métrique universelle de couverture.

## Corrélation statique

La corrélation est déterministe et suit cet ordre exclusif :

1. `symbolKey` exact ;
2. `qualifiedName` exact si aucune clé n’est fournie ;
3. `fileId`, puis inclusion de la ligne dans `SymbolLocation` si elle est fournie.

Une seule cible produit `RESOLVED`. Aucune cible produit `UNRESOLVED`. Plusieurs cibles produisent `AMBIGUOUS` avec au plus 1 000 IDs candidats et un marqueur de troncature. MINOS ne choisit jamais arbitrairement parmi plusieurs symboles.

L’import exige le snapshot actif exact. Les requêtes globales ignorent les sessions d’anciens snapshots ; la sélection explicite d’une session stale échoue afin d’empêcher une fusion inter-snapshot implicite.

## Persistance

`RuntimeObservationStore` est le port engine. `FileRuntimeObservationStore` implémente un format binaire `MRT1`/v1 :

- session immuable et réimport idempotent par identité + SHA-256 source ;
- nom de fichier construit à partir du hash de l’identité et du checksum du contenu ;
- vérification SHA-256 avant désérialisation ;
- longueurs, comptes, candidats, taille session et taille projet bornés ;
- rejet des fichiers ou répertoires symboliques sur les entrées gérées ;
- verrou inter-processus par projet et publication `ATOMIC_MOVE` fail-closed ;
- aucune éviction implicite : capacité atteinte produit une erreur opérable.

Le store ne supprime jamais une session historique lors d’un changement de snapshot. La conservation et une éventuelle politique de rétention administrée restent distinctes d’une lecture sur le snapshot actif.

## Surfaces

`RuntimeCommand` expose l’import administratif et trois lectures. `MinosApplication` crée une seule instance de store/service par `MINOS_HOME` et permet l’injection pour les tests.

Le MCP passe à 26 tools avec `minos_runtime_sessions`, `minos_runtime_report` et `minos_runtime_symbol`. Les schémas imposent les mêmes bornes que le service et utilisent `sessionId`/`symbolId` explicitement. `MinosApplicationMcpBackend` appelle directement le service, sans réexécuter la CLI.

`RuntimeIntelligenceRenderer` produit un JSON déterministe, incluant toujours nature, exhaustivité, snapshot et limitations. Le MCP ne peut pas importer une session.

## Menaces et refus fail-closed

Les tests couvrent BOM/UTF-8 invalide, format/champs inconnus, `COMPLETE`, traversal, chemins absolus, compteurs invalides, projet ou snapshot discordant, session mutée, stockage corrompu, symlink, capacité, corrélation ambiguë et stale snapshot.

Une corruption de fichier échoue avant désérialisation. Un fichier importé ne peut pas utiliser son `sessionId` comme chemin disque. Les messages CLI nettoient les retours ligne et ne sérialisent pas le fichier source dans les résultats.

## Qualification locale

`scripts/m26/run-runtime-e2e.py` construit un snapshot statique déterministe dans un `MINOS_HOME` temporaire, puis exerce le JAR ombré sur les quatre actions CLI. Le JSON d’évidence vérifie le commit exact, `PARTIAL`, la corrélation, l’autorité du snapshot, l’idempotence et les rejets fail-closed.

Les runners `run-final.ps1` et `run-final.sh` imposent un worktree propre, le même SHA exact, Java 24, Python, `javac`, le reactor complet, JaCoCo, les régressions M24/M25, l’e2e détaillé et l’absence de diff `.github/workflows`. GitHub Actions n’est ni appelée ni utilisée en juillet 2026.
