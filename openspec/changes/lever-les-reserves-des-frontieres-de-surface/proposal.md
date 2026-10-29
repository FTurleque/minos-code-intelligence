# Proposal

## Why

Le constat **AUD-ARC-01** (audit du 10 octobre 2026) est traité par six lots fusionnés : les surfaces ne nomment plus que 9 types de `minos-engine` (table `ENGINE_TYPES_NAMED_BY_SURFACE`, règle A11 de `scripts/architecture/check-module-boundaries.py`). La revérification du 28 octobre laisse trois réserves :

1. `registry.ProjectPathMappings`, un **port** du moteur, est encore nommé par la CLI : `DockerRuntimeBootstrap` le reçoit de `MinosApplicationComposers.resolve().projectPathMappings(home)`. L'ADR 0057 (mise en œuvre du point 6) n'admet aux surfaces que les modèles de lecture publiés, jamais un port du moteur.
2. `MinosApplication` expose **19 accesseurs publics** qui rendent un type du moteur. Aucun n'est appelé par le code de production d'une surface, mais rien n'empêche d'en ajouter un vingtième.
3. Les POM de `minos-cli`, `minos-api` et `minos-mcp` dépendent de `minos-engine` en portée `compile` ; il faut dire si elle peut passer en `runtime` ou disparaître.

## What Changes

- **Façade applicative** `ProjectPathMappingService` (`com.minos.application`) : elle reçoit le port `ProjectPathMappings` de la racine de composition et applique la règle du plan Docker (créer une seule fois, accepter la même correspondance, refuser d'en remplacer une autre). Elle rend un résultat en type applicatif (`Outcome`). `DockerRuntimeBootstrap` ne fait plus que valider ses arguments, appeler la façade et écrire ses messages : sortie, erreurs et codes de sortie inchangés.
- **Accesseurs de `MinosApplication`** : `gitIntelligence()`, `providerCatalog()`, `scipArtifactImporter()` et `hostedControlPlaneService()` deviennent privés au package `com.minos.application`, où vivent leurs seuls appelants de production. **19 → 15** accesseurs publics typés moteur. Les tests qui les appelaient passent par la façade équivalente (`teamControlPlane()`, `compositionRoot()`, `ProviderPlatformService`).
- **Cliquet des accesseurs** (règle A11) : la table `MINOS_APPLICATION_ENGINE_ACCESSORS` liste les 15 accesseurs restants avec le type rendu. Un accesseur public de plus, un type rendu différent ou une entrée sans accesseur font échouer le garde ; la table ne fait que rétrécir.
- **Tables A11** : `registry.ProjectPathMappings` quitte la liste de la CLI, dans le garde Python (9 → **8** types) et dans la table bytecode de `ModuleArchitectureTest` (6 → **5**).
- **Dépendance `compile` vers le moteur** : conservée pour les trois surfaces, raison écrite dans le design (D3) et dans la PR. Aucune modification de POM.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : deux exigences ajoutées (la correspondance des chemins Docker passe par une façade applicative ; les accesseurs de `MinosApplication` typés moteur sont tenus par un cliquet).

## Hors périmètre

- Les 14 accesseurs restants appelés par les cas d'usage des sous-packages (`ProjectIndexingUseCase`, `RemoteIndexingUseCase`, `KnowledgeExportUseCase`) ou par de nombreux tests d'autres modules, et `runtimeObservationStore()` (amorçage disque d'un test MCP) : les retirer demande de déplacer le câblage de production des cas d'usage, ce qui créerait un cycle de packages (voir D2).
- Les entrées A11 justifiées qui restent : `query.SymbolResult`, `query.UsageResult`, `query.RelationshipResult` (ADR 0057, point 6) et `runtime.MinosVersion` (ADR 0068 § 3).
- La portée Maven des dépendances vers `minos-engine` (D3) ; la scission de `minos-api` (ADR 0057 § 6, option non ratifiée).
- Le suivi d'audit (`SUIVI.md`, `findings.json`, `ETAT-2026-10-13.md`), mis à jour par la session de clôture du sprint 10.
- Les règles A12 à A14 du même garde.

## Impact

- **Modules** : `minos-application` reçoit `ProjectPathMappingService` et son test, et rend quatre accesseurs privés au package ; `minos-cli` change le câblage de `DockerRuntimeBootstrap` ; tests adaptés dans `minos-bootstrap`, `minos-mcp` et `minos-app`.
- **Surfaces publiques** : CLI, MCP, API `com.minos.api`, plugin IntelliJ et NEXUS inchangés. Le point d'entrée `com.minos.cli.DockerRuntimeBootstrap` (images Docker) garde nom, arguments, sorties et codes. Quatre méthodes publiques de la classe interne `MinosApplication` deviennent privées au package : rupture de source et binaire pour un code qui les appelait directement, documentée dans `docs/user/java-api.md` § Ruptures.
- **ADR** : aucun nouvel ADR. Mise en œuvre de l'ADR 0058 § 3 (réserves levées) ; ADR 0042 § 8.2 mis au présent (le port n'est plus nommé par la CLI).
- **Gates** : `check-module-boundaries.py` (A11, nouvelle table) et `test_check_module_boundaries.py` ; `ModuleArchitectureTest` (table bytecode) ; `check-hosted-control-plane-consistency.py` (cite `hostedControlPlaneService`, toujours présent) ; `check-jacoco.py` (portée `m29-docker-runtime-bootstrap` inchangée).
- **Plateformes** : aucun comportement propre au système ; le fichier de correspondance et sa normalisation des chemins Windows sont inchangés.
- **Constat** : AUD-ARC-01, réserves de la revérification du 28 octobre.
