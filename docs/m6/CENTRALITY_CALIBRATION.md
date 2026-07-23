# M6.4 — Calibration des indicateurs de centralité

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE EN ATTENTE**

Suivi : issue #13.

Base : M6.3 a livré des mesures descriptives de concentration, mais la première
fixture multi-module observée ne contenait qu'une seule arête inter-module.

## Objectif

Élargir l'échantillon de topologies afin de vérifier comment les indicateurs M6.3
réagissent à des distributions structurellement différentes avant de définir un
quelconque score ou seuil de « composant central ».

M6.4 reste un lot de **calibration**. Il ne produit encore aucune classification
métier ou architecturale.

## Profils contrôlés

`ArchitectureCentralityCalibrationTest` construit quatre modules et plusieurs
graphes aux résultats mathématiques connus.

### Cycle équilibré

```text
A -> B -> C -> D -> A
```

Quatre contributions uniformes :

```text
HHI-in  = 0.25
HHI-out = 0.25
max-in  = 0.25
max-out = 0.25
```

### Chaîne dirigée

```text
A -> B -> C -> D
```

Trois contributions réparties sur trois sources et trois cibles :

```text
HHI-in  = 1/3
HHI-out = 1/3
max-in  = 1/3
max-out = 1/3
```

### Fan-in

```text
A --\
B ---> D
C --/
```

Toute la part entrante converge vers `D` :

```text
HHI-in  = 1.0
HHI-out = 1/3
max-in  = 1.0
max-out = 1/3
```

### Fan-out

```text
    /-> A
D ---> B
    \-> C
```

Toute la part sortante part de `D` :

```text
HHI-in  = 1/3
HHI-out = 1.0
max-in  = 1/3
max-out = 1.0
```

### Fan-in pondéré

```text
A -(8)-> D
B -(1)-> D
C -(1)-> D
```

La cible reste totalement concentrée tandis que la distribution des sources est
elle-même dominée par `A` :

```text
HHI-in  = 1.0
HHI-out = 0.66
max-in  = 1.0
max-out = 0.8
```

Ce profil vérifie que les compteurs pondérés ne sont pas réduits à un simple
nombre d'arêtes.

## Fixture Java réelle

`ArchitectureJavaFixtureMeasurementTest` relit réellement :

```text
fixtures/java/java-simple/.minos-m0/scip-java/index.scip
```

puis exécute la même chaîne que M6.3 :

```text
SCIP versionné
 -> snapshot v2
 -> ProjectDiscovery
 -> ArchitectureOverview
 -> ArchitectureDependencyGraph
 -> ArchitectureConcentrationReport
```

`java-simple` est un projet Maven mono-module. La porte M6.4 exige donc :

```text
moduleCount = 1
interModuleDependencyCount = 0
moduleEdgeCount = 0
HHI-in = 0
HHI-out = 0
```

Le nombre total de `DEPENDS_ON`, ainsi que leur répartition intra-module / non
attribuable, restent mesurés et imprimés sans être figés a priori.

## Pourquoi cette calibration est nécessaire

La fixture TypeScript M6.3 donne :

```text
HHI-in = 1
HHI-out = 1
```

mais ce résultat découle d'une seule arête module → module. Sans profils
contrastés, transformer `HHI = 1` en règle de centralité confondrait concentration
du graphe et centralité d'un composant.

Les profils M6.4 permettent de distinguer :

- concentration globale du trafic ;
- orientation fan-in / fan-out ;
- distribution équilibrée ;
- effet du poids des dépendances ;
- absence structurelle de dépendances inter-modules.

## Porte locale

```powershell
.\mvnw.cmd clean verify
```

La sortie doit notamment contenir :

```text
M6.4 calibration balanced-cycle: ...
M6.4 calibration directed-chain: ...
M6.4 calibration fan-in: ...
M6.4 calibration fan-out: ...
M6.4 calibration weighted-fan-in: ...
M6.4 java-simple: ...
```

## Décision attendue après validation

Après la porte M6.4, un éventuel indicateur de composant central devra :

1. distinguer explicitement centralité entrante et sortante lorsqu'elles divergent ;
2. rester dérivé et explicable ;
3. conserver les métriques source dans sa preuve ;
4. éviter un seuil absolu choisi arbitrairement ;
5. ne pas classifier un projet mono-module comme ayant un composant central sur
   la seule base d'une absence de concurrence.

Aucune formule finale n'est engagée avant validation de ces profils.
