# M6.7 — Vue d’architecture composée et contexte de module

Date : **23 juillet 2026**

Statut : **LIVRÉ — VALIDÉ LOCALEMENT ET FUSIONNÉ**

Suivi : issue #13.

Base : M6.6 a livré la détection factuelle des technologies.

## Objectif

Assembler les résultats M6 déjà qualifiés dans une vue métier cohérente et
fournir un contexte compact ciblé sur un module sans recalculer ou réinterpréter
les signaux.

M6.7 ne crée aucune nouvelle heuristique architecturale.

## Contrats ajoutés

- `ArchitectureIntelligenceView` ;
- `ArchitectureModuleContext` ;
- `ArchitectureIntelligenceService` ;
- `ProjectArchitectureQuery.getArchitectureIntelligence(...)` ;
- `ProjectArchitectureQuery.getModuleContext(...)`.

## Vue d’architecture composée

`ArchitectureIntelligenceView` compose :

```text
ArchitectureOverview
ArchitectureDependencyGraph
ArchitectureConcentrationReport
ArchitectureCentralityReport
ArchitectureTechnologyReport
```

La vue impose que tous ces éléments appartiennent au même :

```text
projectId
snapshotId
```

Elle vérifie également que les nombres de modules des rapports de concentration
et de centralité correspondent à la topologie.

Le service de composition vérifie en plus :

- l’unicité des IDs de modules ;
- l’égalité exacte des ensembles de modules entre topologie, concentration et centralité ;
- l’existence des modules source/cible de chaque dépendance agrégée ;
- l’existence des modules associés aux technologies.

La vue est `DERIVED` : elle ne transforme pas la nature des faits qu’elle contient.

## Chargement unique

`LocalProjectArchitectureQuery.getArchitectureIntelligence(...)` charge une seule
fois :

```text
projet enregistré
 -> snapshot actif
 -> ProjectDiscovery
```

puis calcule dans ce contexte unique :

```text
overview
 -> dependency graph
 -> concentration
 -> centrality
 -> technologies
 -> composed view
```

Cela évite qu’une vue composée mélange plusieurs snapshots ou plusieurs états de
découverte.

## `getModuleContext`

`ArchitectureModuleContext` contient :

- le module factuel ;
- ses dépendances inter-modules entrantes ;
- ses dépendances inter-modules sortantes ;
- ses métriques de concentration ;
- ses rangs de centralité entrant/sortant ;
- les technologies réellement observées sur ce module ;
- une preuve de composition.

Les listes de dépendances sont triées par ID d’arête. Les technologies conservent
l’ordre canonique du rapport M6.6 : `LANGUAGE` puis `BUILD_SYSTEM`.

## Résolution d’un module

`getModuleContext(project, moduleIdentifier)` résout dans cet ordre :

1. ID exact du module ;
2. chemin relatif exact ;
3. nom exact s’il est unique.

La racine peut être demandée avec :

```text
.
```

Un nom ambigu est refusé et impose l’utilisation de l’ID ou du chemin relatif.
Un identifiant inconnu est également refusé explicitement.

## Chaîne file-backed

`LocalProjectArchitectureQueryTest` couvre désormais :

```text
registre
 -> snapshot actif
 -> découverte
 -> vue M6 complète
 -> ArchitectureIntelligenceView
 -> ArchitectureModuleContext
```

Sur une dépendance `app -> api`, la porte exige notamment :

```text
api : 1 arête entrante, incomingRank=1
app : 1 arête sortante, outgoingRank=1
root: aucune arête, technologie MAVEN uniquement
```

## Replay réel TypeScript

La fixture :

```text
fixtures/typescript/typescript-modules
```

continue d’utiliser son vrai index SCIP versionné.

La porte M6.7 confirme :

```text
api:
  incoming edges = 1
  incoming dependency count = 4
  incoming rank = 1
  technologies = [TYPESCRIPT]

app:
  outgoing edges = 1
  outgoing dependency count = 4
  outgoing rank = 1
  technologies = [TYPESCRIPT]

root:
  incoming edges = 0
  outgoing edges = 0
  technologies = [NPM]
```

Sortie observée :

```text
M6.7 typescript-modules architecture: modules=3, api-in-edges=1, api-in-rank=1, app-out-edges=1, app-out-rank=1, root-technologies=[NPM]
```

## Nature et preuves

La vue composée et le contexte de module sont `DERIVED` parce qu’ils agrègent des
résultats existants.

Ils ne convertissent pas :

- un module factuel en inférence ;
- une technologie factuelle en heuristique ;
- une dépendance dérivée en fait brut ;
- un rang relatif en rôle architectural.

## Validation locale acquise

Head validé : `ba744f41b974432fe33eb617a866ef4c8dcb0ead`.

```text
.\mvnw.cmd clean verify
116 sources main compilées en release 24
58 sources test compilées en release 24
162 tests exécutés
0 failure
0 error
0 skipped
BUILD SUCCESS
```

Fusion `main` : `f10449681a9010079cc9fe0400aac867dea497d9`.

## Hors périmètre

- CLI `minos architecture` ;
- serveur MCP ;
- API HTTP ;
- nouveaux rôles architecturaux ;
- nouveaux algorithmes de centralité ;
- détection de frameworks/runtimes non qualifiés ;
- analyse d’impact M8.

## Suite

M6.7 étant livré, la branche de consolidation finale aligne la roadmap, le
statut opérationnel et la décision `docs/m6/DECISION_M6.md` avant le passage à
**M7 — Indexation incrémentale**.
