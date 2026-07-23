# M6.7 — Vue d’architecture composée et contexte de module

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE EN ATTENTE**

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

La porte M6.7 exige :

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

La sortie Maven doit contenir :

```text
M6.7 typescript-modules architecture: ...
```

## Nature et preuves

La vue composée et le contexte de module sont `DERIVED` parce qu’ils agrègent des
résultats existants.

Ils ne convertissent pas :

- un module factuel en inférence ;
- une technologie factuelle en heuristique ;
- une dépendance dérivée en fait brut ;
- un rang relatif en rôle architectural.

## Hors périmètre

- CLI `minos architecture` ;
- serveur MCP ;
- API HTTP ;
- nouveaux rôles architecturaux ;
- nouveaux algorithmes de centralité ;
- détection de frameworks/runtimes non qualifiés ;
- analyse d’impact M8.

## Porte locale

```powershell
.\mvnw.cmd clean verify
```

La porte doit confirmer :

- compilation Java 24 ;
- suite complète verte ;
- chaîne file-backed ;
- replay réel TypeScript ;
- ligne `M6.7 typescript-modules architecture:` ;
- aucune régression M2 à M6.6.

Après validation et livraison de M6.7, il restera à faire la consolidation finale
du jalon M6 : documentation opérationnelle, vérification de la porte globale et
décision de clôture avant M7.