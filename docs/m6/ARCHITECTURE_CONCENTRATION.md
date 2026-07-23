# M6.3 — Mesures de concentration d'architecture

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE ET MESURE RÉELLE EN ATTENTE**

Suivi : issue #13.

Base : M6.2 a livré le graphe module → module dérivé exclusivement des relations
`DEPENDS_ON` déjà persistées par MINOS.

## Objectif

Mesurer la distribution des dépendances inter-modules avant d'introduire une
notion de composant central, de hotspot ou de fort couplage.

M6.3 est volontairement descriptif : aucune valeur n'est interprétée comme bonne,
mauvaise, centrale ou critique.

## Contrats ajoutés

- `ArchitectureModuleConcentration` ;
- `ArchitectureConcentrationReport` ;
- `ArchitectureConcentrationService` ;
- `ProjectArchitectureQuery.getArchitectureConcentration(...)`.

Le bootstrap local réutilise le même registre, la même découverte et le même
snapshot actif que les vues M6.1 et M6.2.

## Mesures par module

Pour chaque module découvert :

- `incomingDependencyCount` : somme des contributions `DEPENDS_ON` arrivant au module ;
- `outgoingDependencyCount` : somme des contributions `DEPENDS_ON` quittant le module ;
- `incomingModuleCount` : nombre de modules sources distincts ;
- `outgoingModuleCount` : nombre de modules cibles distincts ;
- `incomingShare` : part des dépendances inter-modules qui arrivent au module ;
- `outgoingShare` : part des dépendances inter-modules qui partent du module.

Avec `D` le nombre total de contributions inter-modules :

```text
incomingShare(module) = incomingDependencyCount(module) / D
outgoingShare(module) = outgoingDependencyCount(module) / D
```

Lorsque `D = 0`, toutes les parts valent explicitement `0`.

## Mesures globales

M6.3 calcule deux indices de Herfindahl :

```text
HHI-in  = somme(incomingShare(module)^2)
HHI-out = somme(outgoingShare(module)^2)
```

Le rapport expose également :

- `maxIncomingShare` ;
- `maxOutgoingShare`.

Ces nombres restent des **mesures dérivées**. Aucun seuil du type :

```text
HHI > X => architecture concentrée
share > Y => module central
```

n'est introduit dans M6.3.

## Invariants

Le calcul refuse :

- un `ArchitectureOverview` et un `ArchitectureDependencyGraph` provenant de projets différents ;
- des snapshots différents ;
- un ID de module dupliqué ;
- une arête qui référence un module absent de la topologie ;
- un graphe dont la somme des `dependencyCount` des arêtes ne correspond pas à
  `interModuleDependencyCount`.

Les modules sont ordonnés par ID avant restitution afin que la sortie reste
indépendante de l'ordre d'entrée.

## Preuves

Le rapport et chaque métrique module sont `DERIVED` et portent une preuve
`DERIVATION_PATH`.

M6.3 n'ajoute aucune donnée fournisseur aux contrats publics d'architecture.

## Validation codée

`ArchitectureConcentrationServiceTest` couvre :

- distribution dirigée sur trois modules ;
- valeurs exactes des parts ;
- valeurs exactes des HHI ;
- modules sans dépendance ;
- graphe sans dépendance inter-module ;
- mismatch de snapshot ;
- incohérence entre compteur inter-module et arêtes.

`LocalProjectArchitectureQueryTest` étend la chaîne file-backed :

```text
registre
  -> découverte multi-module
  -> snapshot v2 actif
  -> ArchitectureOverview
  -> ArchitectureDependencyGraph
  -> ArchitectureConcentrationReport
```

## Mesure réelle versionnée

`ArchitectureRealFixtureMeasurementTest` relit :

```text
fixtures/typescript/typescript-modules/.minos-m0/scip-typescript/index.scip
```

Le test :

1. importe réellement l'index avec `ScipSymbolSnapshotImporter` ;
2. republie un snapshot v2 temporaire ;
3. redécouvre le workspace npm `typescript-modules` ;
4. reconstruit la topologie M6.1 ;
5. agrège les `DEPENDS_ON` M6.2 ;
6. calcule les mesures M6.3 ;
7. vérifie notamment qu'une arête `packages/app → packages/api` existe ;
8. imprime les mesures observées dans la sortie Maven.

La baseline M3 attend `4` relations `DEPENDS_ON` persistées pour cette fixture.
Le nombre exact de dépendances inter-modules, les HHI et les parts maximales ne
seront consignés qu'après exécution locale du head M6.3.

## Porte locale

```powershell
.\mvnw.cmd clean verify
```

La porte doit fournir :

- compilation Java 24 ;
- suite complète verte ;
- sortie de `ArchitectureRealFixtureMeasurementTest` ;
- aucune régression M2 à M6.2.

## Hors périmètre

- score composite de centralité ;
- classement des composants centraux ;
- seuil de concentration ;
- détection de cycle architectural ;
- rôles `service`, `controller`, `repository`, `core`, etc. ;
- CLI `architecture` ;
- `get_module_context` ;
- MCP/API.

La prochaine décision M6 devra se baser sur les chiffres réellement observés,
pas sur un seuil choisi a priori.
