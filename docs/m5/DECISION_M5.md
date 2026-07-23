# Décision de clôture M5 — Tests liés et dérivations explicables

Date : **23 juillet 2026**

Statut : **M5 TERMINÉ ET VALIDÉ LOCALEMENT**

## Verdict

Le jalon M5 est clôturé. MINOS dérive désormais des relations de tests liés
explicables, persistantes et interrogeables sans transformer les heuristiques en
faits certains. La validation finale locale couvre le build complet, le replay
des quatre artefacts SCIP TypeScript versionnés et la réouverture du snapshot
jusqu'à la CLI `related-tests`.

## Porte de sortie

| Critère | Résultat |
|---|---|
| Détection des tests liés | satisfaite |
| Conventions de nommage | satisfaites comme heuristique explicite |
| Références directes | satisfaites depuis les occurrences résolues |
| Appels de méthodes | satisfaits uniquement depuis les faits `CALLS` disponibles |
| Proximité package/namespace | satisfaite comme renforcement |
| Score de confiance | satisfait, déterministe et documenté |
| Raisons structurées | satisfaites et rendues en TEXT/JSON |
| Persistance snapshot v2 | satisfaite |
| `get_related_tests` métier | satisfait |
| `minos related-tests` | satisfait |
| Replay des quatre index TypeScript | satisfait |
| Snapshot → réouverture → CLI JSON | satisfait |

## Validation locale finale

La porte finale a été exécutée le 23 juillet 2026 sur le working tree ensuite
commité et poussé dans `m2/symbol-intelligence` :

```text
.\mvnw.cmd clean verify
93 sources main compilées en release 24
49 sources test compilées en release 24
140 tests exécutés
0 failure
0 error
0 skipped
BUILD SUCCESS
```

Le launcher produit a également été vérifié :

```text
.\minos.cmd --help
exit 0
```

Le warning `sun.misc.Unsafe` émis par `protobuf-java 4.34.2` sous Java 24 reste
non bloquant et n'altère pas le résultat du build.

## Replay fournisseur réel

`ScipRelatedTestRealFixtureTest` rejoue les quatre index TypeScript versionnés et
valide les attentes de capacité suivantes :

| Fixture | Attendu |
|---|---|
| `typescript-simple` | au moins un `RELATED_TEST` |
| `typescript-inheritance` | au moins un `RELATED_TEST` |
| `typescript-modules` | au moins un `RELATED_TEST` |
| `typescript-unresolved` | zéro `RELATED_TEST` |

Les quatre assertions font partie du `clean verify` final vert. Les relations de
tests liés comptabilisées sont incluses dans les relations dérivées ; aucune
relation n'est exigée sur la fixture sans source de test.

## Persistance et CLI

`ScipRelatedTestSnapshotIntegrationTest` importe un index SCIP, publie le
snapshot, recrée le registre et le store depuis le disque, retrouve le symbole
de production puis appelle :

```text
related-tests <projectId> <productionId> --format json
```

La porte vérifie notamment :

- une relation `RELATED_TEST` persistée ;
- code de sortie CLI `0` ;
- preuve `DIRECT_REFERENCE` ;
- preuve `NAMING_CONVENTION` ;
- confiance persistée `0.887`.

Cette preuve couvre donc la chaîne ingestion → dérivation → snapshot →
réouverture → requête → rendu CLI.

## Limites assumées

- les relations sont des indices de liaison, pas une mesure de couverture ;
- une heuristique de nommage reste explicitement heuristique ;
- une ambiguïté entre plusieurs conteneurs empêche l'attribution par fichier ;
- les appels ne sont pas inférés lorsqu'ils ne sont pas fournis ;
- les relations dérivées conservent leur nature, leur confiance et leurs preuves ;
- GitHub Actions reste hors de cette décision locale.

## Suite

M5 étant terminé et validé localement, le prochain jalon de la roadmap est
**M6 — Intelligence d'architecture**.
