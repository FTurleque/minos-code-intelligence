# MINOS — prochaine version (non publiée)

Statut : **EN PRÉPARATION** — ces notes rejoignent le fichier `<version>.md` de la prochaine release.

## Diagnostic des échecs internes

- Un échec interne du serveur MCP répond `error: MINOS tool execution failed (ref <référence>)` : la référence figure
  aussi sur la ligne de stderr du serveur (outil, classes, origine). Un client qui comparait la réponse à l'égalité doit
  comparer son préfixe `error: MINOS tool execution failed`.
- Un journal privé, désactivé par défaut (`MINOS_DIAGNOSTIC_LOG=enabled`), garde la pile de chaque échec interne du MCP,
  de la CLI, de l'API Java et de la voie WSL2 sous `MINOS_HOME/logs/internal-failures/`. Voir
  [Diagnostiquer un échec interne](../diagnostic-d-un-echec-interne.md).

## API Java : reclassement d'une erreur d'argument interne

Un appel de l'API Java ne rend `INVALID_REQUEST` que pour une erreur d'usage voulue pour l'appelant (paramètre invalide,
document inconnu, référence de projet non résolue). Une `IllegalArgumentException` levée par un service pendant un appel
aux paramètres valides, c'est-à-dire un défaut interne, est désormais rendue `EXECUTION_FAILURE` (sans cause, message
redacté) et tracée ; elle était rendue `INVALID_REQUEST`. Aucune valeur de `ErrorCode` n'est ajoutée ni retirée.
Un appelant qui traitait `INVALID_REQUEST` comme « corrigez votre requête » pour tout cas doit aussi traiter
`EXECUTION_FAILURE` comme un échec à signaler.

## MCP : genre de symbole inconnu

Un genre de symbole inconnu (`kind`) est une erreur d'argument qui nomme l'argument sans recopier la valeur envoyée ; il
était rendu par l'erreur générique d'exécution d'outil.

## API Java : accesseurs internes de `MinosApplication` retirés

Rupture d'une classe interne, sans changement de `com.minos.api` ni d'aucune sortie de la CLI, du MCP, du plugin IntelliJ
ou de NEXUS : `com.minos.application.MinosApplication` ne rend plus publiquement sa racine de composition
(`compositionRoot()`) ni les quinze accesseurs qui rendaient un port du moteur (`projectRegistry()`, `snapshotStore()`,
`providerRuntimeManager()`…). Un code qui les appelait passe par les services, les cas d'usage de la façade,
`LocalProjectOperations` ou `ProviderPlatformService`, et doit être recompilé. Table de remplacement :
[API Java, « Accesseurs de `MinosApplication` retirés »](../java-api.md#ruptures).

## API Java : fabriques statiques des cas d'usage retirées

Rupture de classes internes, sans changement de `com.minos.api` : les fabriques statiques de `ProjectIndexingUseCase`,
`RemoteIndexingUseCase` et `KnowledgeExportUseCase` sont retirées, et `com.minos.application.MinosApplication` expose les
cas d'usage. La décoration des exécuteurs (`ProjectIndexingUseCase.of(MinosApplication, UnaryOperator<IndexerExecutor>)`)
n'a pas de remplaçant public. Un code qui appelait ces fabriques doit être modifié et recompilé. Table de remplacement :
[API Java, « Cas d'usage obtenus par la façade »](../java-api.md#ruptures).

Le port interne `com.minos.application.MinosApplicationComposer` gagne aussi des méthodes abstraites, pour les
fournisseurs embarqués et pour l'historique d'import, qu'une implémentation tierce du port doit ajouter (rupture de
source) : voir les sections « Fournisseurs embarqués câblés par la racine de composition » et « Port de l'historique
d'import » de [API Java, « Ruptures »](../java-api.md#ruptures).
