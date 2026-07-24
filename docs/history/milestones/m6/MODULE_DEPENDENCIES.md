# M6.2 — Dépendances inter-modules explicables

Date : **23 juillet 2026**

Statut : **TERMINÉ, VALIDÉ LOCALEMENT ET LIVRÉ**

Suivi : issue #13.

Livraison : PR #15 fusionnée dans `main` au commit
`db5c4ed8e8106c38b56289b3908767458dcf4056` après validation locale du head
`634694242b6a4322594d687175f05c9956e74c0d`.

## Objectif

Construire un graphe compact module → module à partir des dépendances de code
que MINOS a déjà qualifiées et persistées en M3.

M6.2 ne redérive donc jamais une dépendance architecturale directement depuis
une occurrence ou une simple référence. La seule entrée relationnelle est :

```text
RelationshipKind.DEPENDS_ON
```

## Contrats ajoutés

- `ArchitectureDependencyGraph` ;
- `ArchitectureModuleDependency` ;
- `ArchitectureDependencyService` ;
- `ProjectArchitectureQuery.getModuleDependencies(...)`.

Le bootstrap `LocalProjectArchitectureQuery` recharge le même projet enregistré,
la même découverte factuelle et le même snapshot v2 actif que la topologie M6.1.

## Résolution des modules

M6.1 et M6.2 partagent désormais `ArchitectureModuleResolver`.

Pour chaque symbole local :

1. le `fileId` doit être relatif, sûr et rester dans la racine du projet ;
2. la racine source correspondante la plus spécifique est privilégiée ;
3. à défaut, le chemin du module le plus spécifique est utilisé ;
4. l'ID du module reste dérivé de façon déterministe depuis l'ID projet et son
   chemin relatif.

Cette factorisation évite qu'un même symbole soit placé dans un module en M6.1
et dans un autre en M6.2.

## Classification des dépendances

Chaque relation `DEPENDS_ON` persistée appartient exactement à une catégorie :

### Inter-module

La source et la cible sont deux symboles locaux attribuables à deux modules
différents.

La relation contribue alors à une arête `ArchitectureModuleDependency`.

### Intra-module

La source et la cible sont attribuables au même module.

La relation est comptée dans `intraModuleDependencyCount`, mais aucune boucle
module → lui-même n'est créée.

### Non attribuable

Une dépendance est conservée dans `unassignedDependencyCount` notamment si :

- sa source ou sa cible n'est pas un symbole MINOS connu du snapshot ;
- sa cible est non résolue ;
- un symbole est externe ;
- un symbole local n'a pas de `fileId` sûr ;
- le module ne peut pas être déterminé.

Aucune de ces situations ne fabrique une arête architecturale.

## Invariant de comptage

Le graphe impose :

```text
interModuleDependencyCount
+ intraModuleDependencyCount
+ unassignedDependencyCount
= totalDependencyCount
```

`totalDependencyCount` correspond uniquement au nombre de relations
`DEPENDS_ON` persistées dans le snapshot.

Les autres kinds, y compris `REFERENCES`, sont ignorés par M6.2.

## Agrégation d'une arête

Plusieurs dépendances symbole → symbole portant la même paire de modules sont
coalescées en une arête.

L'arête conserve :

- le nombre de dépendances contributrices ;
- le nombre de symboles sources distincts ;
- le nombre de symboles cibles distincts ;
- jusqu'à cinq IDs de dépendances comme échantillon déterministe ;
- une confiance conservatrice égale au minimum des confiances contributrices ;
- une preuve `DERIVATION_PATH` entre deux `CodeEntityRef(MODULE, ...)`.

L'arête est toujours `DERIVED` : l'existence des dépendances de code est acquise,
mais leur agrégation au niveau module est un calcul MINOS.

## Déterminisme

- symboles indexés par ID ;
- relations `DEPENDS_ON` triées par ID avant agrégation ;
- arêtes triées par module source puis module cible ;
- IDs d'arêtes dérivés par SHA-256 de la paire logique ;
- échantillons de dépendances triés et bornés à cinq éléments.

Un changement d'ordre des symboles ou relations du snapshot ne doit donc pas
modifier le résultat.

## Tests codés

`ArchitectureDependencyServiceTest` couvre :

- trois dépendances symbole → symbole coalescées sur une seule arête module ;
- compte des symboles sources et cibles distincts ;
- dépendance intra-module ;
- cible locale non attribuable ;
- cible externe ;
- relation `REFERENCES` ignorée ;
- dépendance `DEPENDS_ON` non résolue comptée comme non attribuable ;
- déterminisme face au changement d'ordre des entrées.

`LocalProjectArchitectureQueryTest` couvre également :

```text
registre
  -> découverte Maven multi-module réelle
  -> snapshot v2 avec DEPENDS_ON
  -> réouverture
  -> getArchitectureOverview
  -> getModuleDependencies
```

Le test vérifie que les IDs source/cible de l'arête correspondent exactement aux
modules `app` et `api` de la topologie M6.1.

## Validation locale acquise

Validation exécutée le **23 juillet 2026** sur le head exact
`634694242b6a4322594d687175f05c9956e74c0d` :

```text
.\mvnw.cmd clean verify
103 sources main compilées en release 24
52 sources test compilées en release 24
148 tests exécutés
0 failure
0 error
0 skipped
BUILD SUCCESS
```

Tests M6 concernés :

```text
ArchitectureDependencyServiceTest    3/3 PASS
LocalProjectArchitectureQueryTest    2/2 PASS
ArchitectureTopologyServiceTest      3/3 PASS
NamespaceConventionTest              1/1 PASS
ProviderBoundaryTest                 1/1 PASS
```

Le warning `sun.misc.Unsafe` provenant de `protobuf-java 4.34.2` sous Java 24
reste non bloquant et identique aux validations précédentes.

## Hors périmètre M6.2

- score de centralité ;
- classement des composants centraux ;
- seuil arbitraire de "fort couplage" ;
- rôle architectural (`service`, `controller`, etc.) ;
- dépendances inter-dépôts ;
- CLI `architecture` ;
- `get_module_context` ;
- MCP/API.

Ces notions sont étudiées à partir des mesures du graphe qualifié en M6.3, pas
avant.
