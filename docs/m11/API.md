# M11 — API publique MINOS

Statut : **IMPLÉMENTÉ — validation locale finale en attente**

Suivi : issue #33. PR : #34.

## Objectif

M11 fournit une API Java locale et stable pour permettre à un système externe de consommer MINOS sans dépendre :

- de SCIP ou Glean ;
- des stores et adaptateurs internes ;
- de la CLI M9 ;
- du protocole MCP M10 ;
- des records/enums métier internes.

Le contrat externe est concentré dans :

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
```

`MinosApi.CONTRACT_VERSION` vaut actuellement :

```text
1
```

## Principe de frontière

Les signatures publiques de `MinosApi` n'exposent que :

- des types du JDK (`Path`, `List`, `Set`, `String`, primitives) ;
- des DTO et requêtes imbriqués dans `MinosApi` ;
- `MinosApiException` et `ErrorCode`.

Aucun type des packages suivants ne traverse la frontière publique :

```text
com.minos.adapter
com.minos.architecture
com.minos.cli
com.minos.context
com.minos.domain
com.minos.impact
com.minos.query
com.minos.registry
com.minos.store
```

`LocalMinosApi` peut naturellement dépendre de ces packages en interne : il s'agit de l'adaptateur local de la façade publique, pas du contrat consommateur.

## Surface M11

### Projets

```java
ProjectDto addProject(Path rootPath, String displayName)
List<ProjectDto> listProjects()
ProjectDto getProject(String projectIdentifier)
```

`ProjectDto` expose notamment : identité, racine, disponibilité, langages/builds découverts, nombre de modules, état d'index, snapshot actif et provenance du dernier import aligné.

### Index

```java
IndexImportDto importScip(
    String projectIdentifier,
    Path indexFile,
    IndexImportRequest request
)
```

M11 conserve strictement la frontière M9 : l'import d'un artefact SCIP existant est réel et qualifié ; aucun runner automatique `scip-java` ou `scip-typescript` absent du dépôt n'est simulé.

### Symboles et usages

```java
List<SymbolDto> findSymbols(String projectIdentifier, SymbolQuery query)
List<UsageDto> findUsages(String projectIdentifier, String symbolId, int limit)
```

`SymbolDto` conserve identité, qualité d'identité, type, nom, signature, langue, localisation, résolution, provenance et flags `external/generated`.

### Relations

```java
List<RelationshipDto> findRelationships(
    String projectIdentifier,
    RelationshipQuery query
)
```

Le DTO public conserve :

- source/cible normalisées ;
- cible non résolue éventuelle ;
- type de relation ;
- localisation ;
- statut de résolution ;
- nature `FACTUAL / DERIVED / HEURISTIC` sous forme de chaîne ;
- confiance ;
- provenance ;
- preuves structurées.

Les enums internes sont volontairement convertis en chaînes dans le contrat M11. L'ajout futur d'une valeur interne ne force ainsi pas un consommateur à lier ses binaires à un enum métier MINOS.

### Architecture

```java
ArchitectureDto getArchitecture(String projectIdentifier)
ModuleContextDto getModuleContext(String projectIdentifier, String moduleIdentifier)
```

`ArchitectureDto` expose :

- projet/snapshot ;
- langages et systèmes de build ;
- modules ;
- volumes symboles/relations ;
- agrégats de dépendances ;
- modules les plus centraux en entrée/sortie ;
- technologies observées.

`ModuleContextDto` expose le module, ses volumes de dépendance, ses rangs directionnels et ses technologies.

### Impact

```java
ImpactReportDto analyzeImpact(String projectIdentifier, ImpactQuery query)
```

Le rapport public conserve :

- symbole racine ;
- profondeur et limite demandées ;
- impacts directs/indirects ;
- score de confiance ;
- chemin explicatif relation par relation ;
- tests potentiellement impactés ;
- limitations explicites M8.

La sémantique reste celle de M8 : il s'agit d'une estimation d'impact potentiel fondée sur le graphe observé, jamais d'une preuve d'exhaustivité runtime.

## Validation des entrées

Les requêtes publiques bornent les valeurs sensibles :

```text
SymbolQuery.limit             1..10000
RelationshipQuery.limit       1..10000
ImpactQuery.maxDepth          1..32
ImpactQuery.maxResults        1..10000
```

Les noms d'enums acceptés par `LocalMinosApi` sont insensibles à la casse mais doivent correspondre à une valeur MINOS connue.

## Erreurs publiques

Les erreurs de l'implémentation locale sont traduites en `MinosApiException` :

```text
INVALID_REQUEST
UNAVAILABLE
IO_FAILURE
EXECUTION_FAILURE
```

La cause reste attachée pour le diagnostic local, mais le consommateur peut piloter son comportement uniquement avec le code public.

## Exemple minimal

```java
Path home = Path.of(System.getProperty("user.home"), ".minos");
MinosApi api = new LocalMinosApi(home);

MinosApi.ProjectDto project = api.getProject("my-project");

List<MinosApi.SymbolDto> symbols = api.findSymbols(
    project.id(),
    MinosApi.SymbolQuery.lexical("OrderService", 20)
);

MinosApi.ArchitectureDto architecture = api.getArchitecture(project.id());
```

## Qualification réelle

`LocalMinosApiIntegrationTest` rejoue la fixture versionnée :

```text
fixtures/typescript/typescript-modules
```

Le scénario couvre :

```text
registre projet
  -> import SCIP réel
  -> statut READY
  -> recherche GreetingPort
  -> relation IMPLEMENTS entrante
  -> architecture = 3 modules
  -> contexte packages/api
  -> impact GreetingPort = 2 impacts
  -> tests potentiels = 1
  -> enum public invalide => INVALID_REQUEST
```

Replay attendu :

```text
M11 public API: version=1, project=<uuid>, snapshot=<snapshot>, modules=3, impact=2, tests=1
```

## Compatibilité

Le contrat M11 est versionné explicitement. Une modification incompatible de signature publique, de composant de DTO ou de sémantique documentée doit faire évoluer la version de contrat et être traitée comme une évolution d'API, pas comme un détail d'implémentation.

Les implémentations internes peuvent évoluer sans changer la version tant que les mêmes DTO, bornes et sémantiques publiques sont préservés.

## Porte finale

Commande de validation locale :

```powershell
.\mvnw.cmd clean verify
```

La PR #34 reste Draft tant que le head exact final n'a pas passé cette porte.
