# M6.1 — Topologie factuelle des projets

Date : **23 juillet 2026**

Statut : **TERMINÉ ET VALIDÉ LOCALEMENT**

Suivi : issue #13. Livraison : PR #14.

Head validé : `2198b3c7ee35b20fc2a4872c2312502d7e33185b`.

Merge `main` : `b509734738f9943f3eb57b3876ea1aad487adf44`.

## Objectif

Établir la première vue d'architecture MINOS sans introduire de rôles ou de
scores non qualifiés. M6.1 compose deux sources déjà acquises :

- `ProjectDiscovery` pour les modules, systèmes de build et racines source ;
- `CodeKnowledgeSnapshot` pour les symboles et relations persistés.

Aucun nouveau graphe de connaissance n'est créé.

## Contrats ajoutés

- `ArchitectureOverview` ;
- `ArchitectureModule` ;
- `ArchitectureNamespace` ;
- `ArchitectureTopologyService` ;
- `ProjectArchitectureQuery` ;
- `LocalProjectArchitectureQuery`.

## Nature des informations

### Faits

L'existence d'un module, son chemin relatif, ses systèmes de build et ses
racines source proviennent directement de `ProjectDiscovery`. Le nœud module est
donc marqué `FACTUAL`.

### Agrégats dérivés

Les compteurs de symboles et les namespaces sont calculés à partir du snapshot
actif. Les agrégats de module sont marqués `DERIVED` séparément de la nature du
module lui-même.

Un namespace est dérivé du chemin d'un fichier relativement à la racine source
la plus spécifique. Sa preuve conserve le fichier et la racine ayant servi à la
dérivation.

Cette règle ne prétend pas reconstruire un `package` Java ou un `namespace`
TypeScript lorsque le fournisseur ou les sources ne permettent pas de le
prouver autrement. Elle expose une topologie de source stable et explicable.

## Attribution des symboles aux modules

L'algorithme choisit d'abord la racine source correspondante la plus spécifique.
Cette règle permet à un module imbriqué comme :

```text
packages/app/src
```

de prendre le pas sur le module racine. À défaut de racine source correspondante,
le chemin du module le plus spécifique est utilisé.

Les symboles externes sont comptés séparément et ne sont attribués à aucun
module. Les symboles locaux dont le `fileId` est absent, absolu, hors racine ou
non interprétable sont exposés via `unassignedLocalSymbolCount` au lieu d'être
silencieusement placés dans un module.

## Déterminisme

- symboles triés par ID avant agrégation ;
- modules triés par chemin relatif ;
- namespaces triés par chemin relatif ;
- langages et systèmes de build triés ;
- normalisation des langages indépendante de la locale JVM ;
- IDs de modules et namespaces dérivés par SHA-256 à partir du projet et de la
  topologie logique ;
- aucune identité fournisseur n'est exposée.

## Validation codée

`ArchitectureTopologyServiceTest` couvre :

- projet Maven multi-module avec module racine, `api` et `app` ;
- sources principales et de test dans le même namespace ;
- attribution au module le plus spécifique ;
- comptage séparé des symboles externes ;
- symbole local sans fichier conservé comme non attribué ;
- namespace par défaut ;
- rejet d'un `fileId` sortant de la racine ;
- égalité stricte de la vue lorsque l'ordre des symboles change.

`LocalProjectArchitectureQueryTest` couvre :

```text
registre local
  -> projet réel temporaire
  -> ProjectDiscoveryService
  -> snapshot v2 actif
  -> ArchitectureTopologyService
  -> ArchitectureOverview
```

La frontière fournisseur est également étendue au package `architecture`.

## Porte locale acquise

Validation exécutée le 23 juillet 2026 sur le head exact M6.1 :

```text
.\mvnw.cmd clean verify
99 sources main compilées
51 sources test compilées
144 tests exécutés
0 failure
0 error
0 skipped
BUILD SUCCESS
```

Tests M6 directement concernés :

```text
ArchitectureTopologyServiceTest      3/3 PASS
LocalProjectArchitectureQueryTest    1/1 PASS
NamespaceConventionTest              1/1 PASS
ProviderBoundaryTest                 1/1 PASS
```

Le warning `sun.misc.Unsafe` de `protobuf-java 4.34.2` sous Java 24 reste
non bloquant et identique aux validations précédentes.

## Hors périmètre M6.1

- rôles `service`, `controller`, `repository`, `core`, etc. ;
- centralité ;
- concentration des dépendances ;
- graphe inter-modules agrégé ;
- détection de technologies au-delà des faits de découverte existants ;
- CLI `architecture` ;
- `get_module_context` ;
- MCP.

Le graphe inter-modules constitue désormais M6.2. Voir
[`MODULE_DEPENDENCIES.md`](MODULE_DEPENDENCIES.md).
