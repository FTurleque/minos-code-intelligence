# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement mécanique sur `minos-cli` et `minos-app`, plus un test de parité dans `minos-app`. Aucune sortie publique ne change. Windows et Linux : identique.

## État vérifié au HEAD

Relu au commit `89be3fb4`.

| Fait | Preuve |
|---|---|
| Définition réelle : `SUCCESS = 0`, `EXECUTION_ERROR = 1`, `USAGE_ERROR = 2`, `PARTIAL_RESULT = 3` | `minos-cli/src/main/java/com/minos/cli/support/CliExitCodes.java:9-13` |
| Javadoc de `CliExitCodes` en français, qui renvoie à `FindSymbolCommand` | `CliExitCodes.java:3-6`, `:12` |
| Quatre alias publics dans la commande | `minos-cli/src/main/java/com/minos/cli/command/FindSymbolCommand.java:20-28` |
| Fichiers de production qui lisent un alias, hors `FindSymbolCommand` : 25 dans `minos-cli`, 3 dans `minos-app` ; 67 occurrences | recherche `FindSymbolCommand\.(SUCCESS\|EXECUTION_ERROR\|USAGE_ERROR\|PARTIAL_RESULT)` dans `minos-*/src/main` |
| Fichiers de test qui lisent un alias : 30 dans `minos-cli`, 1 dans `minos-app` | même recherche dans `minos-*/src/test` |
| Aucun import statique des alias | recherche `import static com.minos.cli.command.FindSymbolCommand.` : 0 fichier |
| 5 fichiers de production nomment déjà `CliExitCodes` | recherche dans `minos-*/src/main` |
| Sites de `minos-app` | `ExplorerLaunchRouteProvider.java:101` ; `McpBackendRouter.java:78` ; `DockerMcpTransport.java:83` |
| `FindSymbolCommand` rend lui-même `SUCCESS` sans qualification | `minos-cli/src/main/java/com/minos/cli/command/FindSymbolCommand.java:58` |
| Deux pages d'architecture citent `FindSymbolCommand.EXECUTION_ERROR` | `docs/architecture/arc42/06-vue-execution.md:119` ; `docs/architecture/diagrams/seq-erreur-provider.md:35` |
| `ExplorerSession` redéfinit trois codes, publics | `minos-explorer/src/main/java/com/minos/explorer/ExplorerSession.java:18-20` |
| `minos-explorer` n'a pas `minos-cli` dans ses dépendances permises ; `minos-app` dépend des deux | `scripts/architecture/check-module-boundaries.py:83-89` ; `minos-app/pom.xml:26-29` |
| Le plugin déclare `PARTIAL_RESULT_EXIT_CODE = 3` | `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosProjectList.java:20` |
| Aucun gate ne cite `CliExitCodes` ni les alias | recherche dans `scripts/`, `.github/workflows/`, `.claude/scripts/` |

Écart de décompte avec l'audit : son titre dit 28 fichiers, sa preuve « 30 fichiers hors FindSymbolCommand » ; le décompte relu donne 28 en production. Non mesuré : aucun build exécuté.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit propose de « faire rendre à ExplorerSession les constantes de CliExitCodes via la route » | `findings.json`, AUD-ARC-06, action | `minos-explorer` ne peut pas dépendre de `minos-cli` (surface → surface refusée par la politique de dépendances) | D2 : test de parité dans `minos-app`, seconde option de l'audit |
| L'audit cite la constante du plugin IntelliJ | preuve du constat | le plugin est hors reactor et ne dépend d'aucun artefact `com.minos:*` | Hors périmètre ; la valeur 3 est tenue par le contrat CLI (ARCH-0016) et les tests de protocole du plugin |
| L'audit ne compte que la production | titre du constat | 31 fichiers de test lisent aussi les alias | Les tests suivent dans le même changement (les alias disparaissent) |

## Exigences couvertes

- surfaces-publiques › Les codes de sortie de la CLI ont une seule définition — D1.
- surfaces-publiques › Les codes de sortie de la surface d'exploration suivent ceux de la CLI — D2.

## Objectifs et non-objectifs

Objectif : une définition, zéro alias, une parité prouvée pour la seule autre surface qui rend un code de processus. Non-objectif : déplacer `CliExitCodes` dans un autre module.

## Responsabilités et frontières des composants

`CliExitCodes` (`com.minos.cli.support`) reste le détenteur. `minos-app` continue de dépendre de `minos-cli` (arête existante, routes de lancement). Aucune arête ajoutée ; A8 : `cli.support` ne gagne aucun import.

## Modèle de domaine et invariants

Non applicable : quatre constantes entières, inchangées.

## Traitements et interactions

Non applicable : substitution de référence, sans changement de flot.

## États et transitions

Non applicable.

## Contrats d'interface

CLI : codes de sortie 0, 1, 2, 3 inchangés (ARCH-0016). Classe interne `FindSymbolCommand` : quatre constantes publiques retirées ; aucun contrat publié ne les cite.

## Persistance et intégrité

Non applicable.

## Transactions, concurrence et idempotence

Non applicable.

## Sécurité et isolation

Non applicable : aucun chemin d'exécution, message ni donnée ne change.

## Erreurs et reprise

Non applicable.

## Observabilité

Non applicable.

## Performance et mesure

Non applicable.

## Compatibilité, migration et retour arrière

Les constantes sont des `static final int` : elles sont recopiées à la compilation, le bytecode des appelants ne change pas de valeur. Retour arrière : annuler la PR.

## Diagrammes

Non applicable : changement mécanique sans structure nouvelle ; un diagramme n'apporterait aucune réponse.

## Décisions

### D1 — Supprimer les alias sans période de dépréciation [Proposition]

`FindSymbolCommand` est une classe de commande de la CLI, pas une API Java publiée ; tous ses lecteurs sont dans le reactor et changent dans la même PR. Garder des alias dépréciés laisserait la dépendance possible. Alternative écartée : `@Deprecated` puis retrait.

### D2 — Parité de l'explorateur par un test de `minos-app` [Proposition]

`ExitCodeContractParityTest` compare par réflexion les `public static final int` d'`ExplorerSession` aux constantes de même nom de `CliExitCodes` ; la comparaison est une fonction testée aussi sur des tables divergentes (cas de refus). Alternative écartée : descendre les codes dans `minos-application` — ce serait y placer le contrat d'une surface.

### D3 — Aucun gate ajouté [Proposition]

La suppression des alias fait de toute réintroduction une erreur de compilation ; la parité est un test Java. Aucun gate littéral nouveau (`.claude/rules/gates-python.md`).

### D4 — Aucun ADR [Décision de forme]

Application d'ARCH-0016, sans choix nouveau.

## Qualification des capacités

Aucune capacité nouvelle.

## Risques et compromis

- [Risque] Conflits de fusion : 25 fichiers de `minos-cli` et 3 de `minos-app` touchés d'une ligne → fusionner ce changement tôt ; toute PR ouverte sur ces fichiers se rebase par la même substitution. Fichiers partagés avec d'autres changements : `ExplorerLaunchRouteProvider.java:101` (`centraliser-la-politique-de-lancement-de-l-explorateur`), `LocalRemoteIndexOperations` et `LocalAutonomousIndexOperations` ne sont pas concernés. `ExplorerSession` n'est pas modifiée ici : aucun conflit avec `fiabiliser-la-surface-de-l-explorateur` (sprint 3). `distinguer-l-erreur-d-usage-de-l-echec-interne` (sprint 2) convertit des validations du MCP et de l'API Java : pas de fichier commun attendu, à revérifier au rebase.
- [Risque] Javadoc française de `CliExitCodes` et de fichiers touchés → reprise en anglais des seuls commentaires modifiés ; `comment-language.json` abaissé si le maximum de `minos-cli` baisse.

## Vérification

- Tests : `ExitCodeContractParityTest` (nouveau, `minos-app`) ; suite de `minos-cli` et de `minos-app`.
- Gates : `check-comment-language.py`, `check-module-boundaries.py`, `python .claude/scripts/run_gates.py --fast`.
- Commandes : `./mvnw -B -ntp -pl minos-app -am verify`, puis `git diff --exit-code minos-app/src/test/resources/characterization/`.
- Aucun scope JaCoCo ne cite ces constantes.

## Documentation durable à mettre à jour

`docs/architecture/diagrams/capacites/surfaces-publiques.md` (exigences ajoutées) ; `docs/architecture/arc42/06-vue-execution.md:119` et `docs/architecture/diagrams/seq-erreur-provider.md:35` (le code est nommé par `CliExitCodes.EXECUTION_ERROR`). `docs/user/` et `docs/developer/` : recherche par la tâche 1.3.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| surfaces-publiques › Les codes de sortie de la CLI ont une seule définition | Aucun alias dans une commande | D1 | 1.2 | recherche textuelle ; compilation |
| idem | Valeurs inchangées | Contrats | 1.1 | à écrire |
| idem | Sorties caractérisées inchangées | Vérification | 1.3 | `git diff --exit-code` |
| surfaces-publiques › Les codes de sortie de la surface d'exploration suivent ceux de la CLI | Parité ; Valeur divergente refusée ; Code inconnu refusé | D2 | 2.1 | `ExitCodeContractParityTest`, à écrire |

## Questions ouvertes

Aucune.
