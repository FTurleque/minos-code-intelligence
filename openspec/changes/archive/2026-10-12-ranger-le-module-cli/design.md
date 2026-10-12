# Design

## Context

Troisième module du rangement demandé par le propriétaire le 11 octobre 2026 (option « les trois modules restants, en acceptant les classes rendues publiques », un changement OpenSpec par module). HEAD analysé : `13921d11` (`develop`).

## État vérifié au HEAD

- `minos-cli/src/main/java/com/minos/cli/` : 38 classes, un seul package ; 73 fichiers de test dans le même package.
- Classes nommées hors du module (fichiers suivis, hors historique) :
  - `MinosLauncher` : `minos-app/pom.xml` (`Main-Class`), `docker/compose-mcp.*.yaml`, `scripts/ci/qualify-docker-release.sh`, `scripts/release/build-windows-distribution.ps1`, `docs/user/cli.md:3`, `docs/user/java-api.md:323` ;
  - `DockerRuntimeBootstrap` : `docker/compose-mcp.*.yaml`, `docs/user/java-api.md:323` ;
  - `McpLaunchRoute` : SPI fourni par `minos-app` (`McpLaunchRouteProvider`), `docs/user/java-api.md:342` ;
  - `MinosCli`, `MinosCliRunner`, `McpLaunchRoutes`, `LazyApplication` : tests de `minos-app` ;
  - `FindSymbolCommand` : `minos-app/src/main/java/com/minos/app/DockerMcpTransport.java:83` et `McpBackendRouter.java:78`, pour la seule constante `SUCCESS`.
- Codes de sortie : définis par `FindSymbolCommand.java:17-19` ; lus par `CliCommandSupport.java:90,97,106` et `DockerRuntimeBootstrap.java:32,45,55,63,75`.
- Analyse des dépendances internes pour le découpage D1 (outil d'analyse, commentaires retirés ligne par ligne) :
  - arêtes : `command → support` (57 références), `command → operations` (6), `operations → support` (1 : `AutonomousIndexOperations` appelle `CliCommandSupport.publicDiagnostic(s)`), `racine → command` (28), `racine → operations` (7), `racine → support` (5), **`support → command` (1 : les codes de sortie)** ;
  - **cycle** : `command → operations → support → command` ;
  - 7 types package-private référencés depuis un autre groupe : `CliCommandSupport`, `CliJson`, `CliOptions`, `LazyApplication`, `IdeIntelligenceCommand`, `RetrievalStatusCommand`, `TeamCommand` ;
  - 158 usages de membres statiques package-private depuis un autre groupe (principalement `CliCommandSupport.run/operand/isHelp`, `CliOptions.spec`, `CliJson.render`).

## Goals / Non-Goals

**Goals** : un package par responsabilité, aucun cycle, noms des points d'entrée inchangés, aucun comportement modifié.

**Non-Goals** : restaurer une encapsulation par JPMS ; réduire la complexité des commandes ; modifier la surface CLI.

## Decisions

### D1. Découpage

| Sous-package | Classes |
|---|---|
| racine | `MinosLauncher`, `MinosCli`, `MinosCliRunner`, `DockerRuntimeBootstrap`, `McpLaunchRoute`, `McpLaunchRoutes` |
| `command` | les 21 classes `*Command`, `ArchitectureOutputFormat` |
| `operations` | `AutonomousIndexOperations`, `LocalAutonomousIndexOperations`, `RemoteIndexOperations`, `LocalRemoteIndexOperations`, `RemoteIndexLease`, `IndexResumeStatusSource` |
| `support` | `CliCommandSupport`, `CliOptions`, `CliJson`, `LazyApplication`, `CliExitCodes` (nouvelle) |

`LazyApplication` va dans `support` parce que `CliCommandSupport` l'utilise : la laisser à la racine créerait le cycle `racine ↔ support`.

### D2. Lever le cycle par les codes de sortie

Une classe `support.CliExitCodes` définit `SUCCESS`, `EXECUTION_ERROR` et `USAGE_ERROR`. `CliCommandSupport` la lit. `FindSymbolCommand` **garde** ses trois constantes publiques, initialisées depuis `CliExitCodes` : les appelants existants (commandes, `minos-app`, tests) ne changent pas, et une valeur n'est écrite qu'une fois. C'est la seule modification de code qui ne soit pas un déplacement ; elle est faite et testée avant le déplacement.

### D3. Visibilité élargie au strict nécessaire

Une classe ou un membre ne devient `public` que si le compilateur le refuse depuis un autre sous-package. Un test qui n'accède qu'à des membres package-private d'une classe déplacée la suit dans son package plutôt que de faire ouvrir le code de production.

### D4. Direction des dépendances (ADR 0022, ADR 0044)

Aucune dépendance Maven ne change. Les quatre packages appartiennent au seul module `minos-cli` (règle A3) ; la règle A8 (aucun cycle de packages, table vide) est rejouée.

## Risks / Trade-offs

- Encapsulation affaiblie : l'analyse d'options et l'exécution commune des commandes deviennent publiques. Atténuation : ces classes ne sont pas documentées comme API Java.
- Les classes de commande déjà publiques changent de nom qualifié. Seul `minos-app` les importe (deux fichiers) ; il est mis à jour dans le même commit.
- Couverture SonarCloud : les fichiers déplacés comptent comme code neuf ; des tests sont ajoutés dans `minos-cli` si le seuil de 80 % n'est pas atteint.
- Gates littéraux : sept scripts citent des chemins du module ; ils sont réécrits et rejoués.
- Baseline SpotBugs : à régénérer ; le passage en `public` peut faire apparaître des alertes `EI_EXPOSE_REP`, à corriger ou à consigner (constat de la PR #413).

## Windows et Linux

Aucun comportement propre à une plateforme.

## Qualification de la capacité

`frontieres-de-modules` : **qualifiée** quand `check-module-boundaries.py` est vert et que les deux jobs `Verify` passent ; aucune capacité produit n'est ajoutée ni retirée.

## Migration

Aucune donnée. Retour arrière : revert des commits.

## Écart constaté à l'implémentation (2026-10-11)

- **Codes de sortie** : `CliExitCodes` porte aussi `PARTIAL_RESULT` (3), pour que les quatre codes soient définis au même endroit ; `FindSymbolCommand.PARTIAL_RESULT` y renvoie comme les trois autres.
- **Visibilité** : 21 types (les 7 annoncés, plus 14 types imbriqués : interfaces de `CliCommandSupport`, `CliOptions.Spec`/`Declared`/`Kind`/`ForbiddenOptionException`, types de `LazyApplication`, `NexusExportCommand.ExportOperation`, `RetrievalStatusCommand.Mode`/`StatusReader`) et 60 membres deviennent publics. Deux ouvertures viennent des tests et non du code de production : le constructeur `TeamCommand(HostedControlPlaneService, Supplier)` et `TeamCommand.operations()`, lus par des tests qui exercent à la fois la racine et la commande.
- **Placement des tests** : trois tests de commande restent à la racine (`TeamCommandTest`, `IdeCommandTest`, `RetrievalStatusCommandTest`), parce qu'ils appellent des membres package-private de `MinosCli` ou de `MinosCliRunner` ; trois tests transverses vont dans `command` (`TeamOperationGuardTest`, `SymbolCommandsArgumentRulesTest`, `ProviderVerdictConsistencyTest`). Les aides de test `CliArgumentRules`, `TeamFixtures`, `QualifiedSandboxForTests` et `HomeTree` deviennent publiques.
- **Tests de garde** : `CliParsingGuardTest` listait le seul répertoire `com/minos/cli` ; après le déplacement il n'aurait plus surveillé que la racine, sans échouer sur deux de ses cinq règles. Il parcourt maintenant les sous-packages. `TeamCommandDispatchGuardTest` suit la signature `public int run(`.
- **SpotBugs** : le baseline du module passe de 5 à 8 alertes. Les trois nouvelles viennent du passage en `public`, pas d'un changement de code : `EI_EXPOSE_REP` sur `CliOptions.operands()` (la liste est déjà une copie immuable, `List.copyOf` à la construction, ce que SpotBugs ne voit pas), `EI_EXPOSE_REP` sur `LazyApplication.OpenFailure.failure()` (l'exception d'origine est rendue telle quelle à dessein, pour être relancée), `USO_UNSAFE_METHOD_SYNCHRONIZATION` sur `LazyApplication.get()` (même motif que l'alerte déjà consignée sur `close()` de cette classe). Elles sont consignées, selon la règle de `quality/spotbugs-exclude.xml`.
- **Couverture** : mesure locale Windows des fichiers déplacés, lignes + conditions, identique avant et après : 82,72 % (380 lignes manquées sur 2 401, 156 conditions sur 701), soit 84 unités de marge avant le seuil de 80 %. La valeur Linux de SonarCloud n'est pas mesurée localement.
