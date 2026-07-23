# M6.1 — Topologie factuelle des projets

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE EN ATTENTE**

Suivi : issue #13.

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

- modules triés par chemin relatif ;
- namespaces triés par chemin relatif ;
- langages et systèmes de build triés ;
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
- rejet d'un `fileId` sortant de la racine.

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

## Hors périmètre M6.1

- rôles `service`, `controller`, `repository`, `core`, etc. ;
- centralité ;
- concentration des dépendances ;
- graphe inter-modules agrégé ;
- détection de technologies au-delà des faits de découverte existants ;
- CLI `architecture` ;
- `get_module_context` ;
- MCP.

Ces points doivent être qualifiés après validation de la topologie de base.

## Porte locale attendue

```powershell
.\mvnw.cmd clean verify
```

M6.1 ne sera pas déclaré validé avant un build vert sur le head exact de la
branche `m6/architecture-intelligence`.
