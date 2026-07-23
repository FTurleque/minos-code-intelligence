# M6.5 — Classement explicable des composants centraux

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE EN ATTENTE**

Suivi : issue #13.

Base : M6.4 a montré qu'un HHI global ne suffit pas à qualifier un composant
central et que fan-in et fan-out doivent rester distincts.

## Objectif

Identifier les modules relativement les plus centraux sans introduire :

- de seuil absolu arbitraire ;
- de score composite mélangeant les directions ;
- de rôle architectural inventé ;
- de classification binaire `central / non central`.

M6.5 transforme les mesures M6.3 en **rangs relatifs directionnels**.

## Contrats ajoutés

- `ArchitectureModuleCentrality` ;
- `ArchitectureCentralityReport` ;
- `ArchitectureCentralityService` ;
- `ProjectArchitectureQuery.getArchitectureCentrality(...)`.

Le bootstrap `LocalProjectArchitectureQuery` réutilise exactement la chaîne :

```text
registre projet
 -> découverte
 -> snapshot actif
 -> ArchitectureOverview
 -> ArchitectureDependencyGraph
 -> ArchitectureConcentrationReport
 -> ArchitectureCentralityReport
```

Aucun store, graphe ou chemin de chargement parallèle n'est introduit.

## Règle de classement

Les rangs entrants et sortants sont calculés séparément à partir des compteurs
de dépendances inter-modules déjà agrégés par M6.3.

Les compteurs entiers sont utilisés pour le classement plutôt que les `double`
de part, car tous les modules d'un même rapport partagent le même dénominateur.
Cela évite une comparaison flottante inutile tout en produisant exactement le
même ordre que les shares.

### Rang dense

Pour chaque direction :

1. conserver uniquement les compteurs strictement positifs ;
2. prendre les valeurs distinctes ;
3. les trier par ordre décroissant ;
4. attribuer les rangs denses `1, 2, 3, ...` ;
5. attribuer `rank = 0` aux modules sans signal dans cette direction.

Exemple :

```text
outgoingDependencyCount
A = 8  -> rank 1
B = 1  -> rank 2
C = 1  -> rank 2
D = 0  -> rank 0
```

Les ex æquo restent donc explicitement ex æquo.

## Sémantique

`incomingRank` répond à :

> Quels modules reçoivent relativement le plus de dépendances inter-modules ?

`outgoingRank` répond à :

> Quels modules émettent relativement le plus de dépendances inter-modules ?

Les deux réponses peuvent diverger. M6.5 ne les fusionne jamais.

Le rapport expose également :

- `topIncomingModuleIds` : tous les modules de rang entrant `1` ;
- `topOutgoingModuleIds` : tous les modules de rang sortant `1` ;
- `rankedIncomingModuleCount` ;
- `rankedOutgoingModuleCount` ;
- les compteurs, nombres de voisins et shares sources pour chaque module.

Un graphe sans dépendance inter-module produit :

```text
rankedIncomingModuleCount = 0
rankedOutgoingModuleCount = 0
topIncomingModuleIds = []
topOutgoingModuleIds = []
```

Il n'invente donc aucun composant central par défaut.

## Preuves et nature

Le rapport et les profils module restent `DERIVED`.

Chaque `ArchitectureModuleCentrality` conserve les preuves de la métrique M6.3
et ajoute une preuve `DERIVATION_PATH` décrivant les rangs calculés.

Aucun type SCIP, Glean ou fournisseur n'entre dans les contrats M6.5.

## Tests codés

`ArchitectureCentralityServiceTest` couvre :

- fan-in : cible top entrante, sources ex æquo top sortantes ;
- pondération `8/1/1` : rang sortant `1,2,2,0` ;
- absence totale de signal : tous les rangs à `0` ;
- ordre de sortie déterministe par ID de module ;
- cohérence des rapports de concentration utilisés comme entrée.

`LocalProjectArchitectureQueryTest` couvre désormais la chaîne file-backed
jusqu'au ranking sur une dépendance `app -> api` :

```text
api : incomingRank=1, outgoingRank=0
app : incomingRank=0, outgoingRank=1
```

## Replay réel TypeScript

`ArchitectureRealFixtureMeasurementTest` continue de relire le vrai index :

```text
fixtures/typescript/typescript-modules/.minos-m0/scip-typescript/index.scip
```

Après reconstruction M6.1 à M6.3, M6.5 exige :

```text
packages/api  -> top entrant, incomingRank=1, outgoingRank=0
packages/app  -> top sortant, incomingRank=0, outgoingRank=1
module racine -> incomingRank=0, outgoingRank=0
```

La sortie Maven ajoute :

```text
M6.5 typescript-modules centrality: top-in=..., top-out=..., root-in-rank=0, root-out-rank=0
```

## Pourquoi aucun score composite

La calibration M6.4 a donné notamment :

```text
fan-in  : HHI-in=1.0,      HHI-out=0.333333
fan-out : HHI-in=0.333333, HHI-out=1.0
```

Fusionner ces deux directions dans un seul nombre détruirait précisément
l'information que la calibration a montré comme significative.

M6.5 qualifie donc les « composants centraux » comme des **leaders relatifs par
direction**, pas comme une classe absolue.

## Porte locale

```powershell
.\mvnw.cmd clean verify
```

La porte doit vérifier :

- compilation Java 24 ;
- suite complète verte ;
- aucune régression M2 à M6.4 ;
- tests M6.5 verts ;
- replay réel TypeScript avec la ligne `M6.5 typescript-modules centrality:`.

## Hors périmètre

- score composite unique ;
- seuil `central si score > X` ;
- PageRank, betweenness ou eigenvector centrality ;
- rôles architecturaux (`core`, `service`, `repository`, etc.) ;
- technologies détectées ;
- CLI `architecture` ;
- `get_module_context` ;
- MCP/API.

La prochaine étape M6 pourra s'appuyer sur ce ranking pour enrichir la vue
d'architecture, sans perdre la distinction entre fait, agrégat et interprétation.
