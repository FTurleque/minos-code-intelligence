# M6.4 — Calibration des indicateurs de centralité

Date : **23 juillet 2026**

Statut : **TERMINÉ, VALIDÉ LOCALEMENT ET LIVRÉ**

Suivi : issue #13.

Livraison : PR #17 fusionnée dans `main` au commit
`612e0907850219376cdc5bd6c6d5401831e96450` après validation locale du head
`b7864aa9f739dbf652fca54be2580115c40f6cf7`.

## Objectif

Élargir l'échantillon de topologies afin de vérifier comment les indicateurs M6.3
réagissent à des distributions structurellement différentes avant de définir un
quelconque score ou seuil de « composant central ».

M6.4 est un lot de **calibration**. Il ne produit aucune classification métier ou
architecturale.

## Profils contrôlés validés

### Cycle équilibré

```text
A -> B -> C -> D -> A
HHI-in  = 0.25
HHI-out = 0.25
max-in  = 0.25
max-out = 0.25
```

### Chaîne dirigée

```text
A -> B -> C -> D
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

HHI-in  = 1.0
HHI-out = 0.66
max-in  = 1.0
max-out = 0.8
```

Ce dernier profil confirme que le poids des dépendances reste visible et que
l'analyse ne se réduit pas au nombre d'arêtes.

## Calibration Java sur fixture versionnée

La première version M6.4 supposait à tort que cet artefact était versionné :

```text
fixtures/java/java-simple/.minos-m0/scip-java/index.scip
```

La première porte locale a correctement échoué avec :

```text
SCIP index does not exist or is not a regular file
```

Aucun faux artefact SCIP n'a été généré ou simulé.

La correction utilise la fixture réellement versionnée :

```text
fixtures/java/java-multi-module
```

Sa vérité terrain documente notamment les modules `api`, `app` et des relations
inter-modules `app -> api`.

`ArchitectureJavaFixtureMeasurementTest` :

1. redécouvre réellement la structure Maven de `java-multi-module` ;
2. utilise les chemins source réellement versionnés de `app` et `api` ;
3. publie un snapshot MINOS contrôlé contenant une dépendance `app -> api` ;
4. reconstruit `ArchitectureOverview` ;
5. reconstruit `ArchitectureDependencyGraph` ;
6. calcule `ArchitectureConcentrationReport`.

Mesure validée :

```text
modules=3
dependsOn=1
inter=1
intra=0
unassigned=0
edges=1
HHI-in=1.000000
HHI-out=1.000000
max-in=1.000000
max-out=1.000000
```

Cette preuve est une **calibration sur topologie réelle avec snapshot contrôlé**,
et non un replay SCIP Java réel.

## Validation locale acquise

Validation exécutée le **23 juillet 2026** sur le head exact
`b7864aa9f739dbf652fca54be2580115c40f6cf7` :

```text
.\mvnw.cmd clean verify
106 sources main compilées en release 24
56 sources test compilées en release 24
155 tests exécutés
0 failure
0 error
0 skipped
BUILD SUCCESS
```

Profils observés :

```text
balanced-cycle      deps=4   HHI-in=0.250000  HHI-out=0.250000  max-in=0.250000  max-out=0.250000
directed-chain      deps=3   HHI-in=0.333333  HHI-out=0.333333  max-in=0.333333  max-out=0.333333
fan-in              deps=3   HHI-in=1.000000  HHI-out=0.333333  max-in=1.000000  max-out=0.333333
fan-out             deps=3   HHI-in=0.333333  HHI-out=1.000000  max-in=0.333333  max-out=1.000000
weighted-fan-in     deps=10  HHI-in=1.000000  HHI-out=0.660000  max-in=1.000000  max-out=0.800000
```

Le warning `sun.misc.Unsafe` provenant de `protobuf-java 4.34.2` sous Java 24
reste non bloquant et identique aux validations précédentes.

## Décision M6.4

La calibration établit qu'un HHI global ne suffit pas à qualifier un composant
central. La direction est une information structurante : fan-in et fan-out
peuvent diverger fortement.

La suite doit donc :

1. distinguer centralité entrante et sortante ;
2. conserver les métriques source dans les preuves ;
3. éviter tout seuil absolu arbitraire ;
4. ne pas confondre concentration globale et rôle architectural ;
5. privilégier un classement relatif explicable.

Cette décision est mise en œuvre par M6.5.
