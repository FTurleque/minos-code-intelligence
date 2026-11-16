# Capacité `analyse-git` — dossier durable

Spec de référence : [openspec/specs/analyse-git/spec.md](../../../../openspec/specs/analyse-git/spec.md)
· Relevé au commit `3b516b1e` (2026-11-03).

## Objet et périmètre

[Exigence] MINOS lit l'état et l'historique d'un dépôt Git analysé (commande `git-activity`, API Java
`inspectGit` / `analyzeGitActivity`) sans exécuter de code fourni par ce dépôt, sans révéler d'identifiant ni de
chemin local, et en déclarant ce que la neutralisation des filtres change. Acteurs : l'utilisateur de la CLI ou
de l'API ; le dépôt analysé est **non fiable** (`.claude/rules/securite.md`, point 5). Le clone d'un dépôt distant
passe par la même ouverture, sans exécuter aucun pilote de filtre (ADR SEC-0083). Hors périmètre : MCP (aucun
outil Git n'est exposé).

Composants : `GitActivityCommand` (`minos-cli`), `LocalMinosMultiRepositoryApi` (`minos-api`),
`LocalProjectGitQuery` (`minos-application`), port `GitIntelligence` (`minos-engine`), implémentation
`GitIntelligenceService`, ouverture de confiance `TrustedRepositoryOpening` et `PassThroughFilter`
(`minos-integration-git`), clone distant `JGitRemoteGitClient` et `JGitRemoteRepositoryMaterializer`, résultat `RepositoryView`
(`minos-domain`), câblage par `DefaultMinosApplicationComposer` (`minos-bootstrap`).

## Vues

### Dans quel ordre un dépôt analysé est-il ouvert, contrôlé et lu ?

Type : sequenceDiagram (UML, séquence) · Portée : existant vérifié · Exigences : « Le propriétaire du dépôt
trouvé est contrôlé avant toute lecture de son contenu », « Aucun filtre déclaré par le dépôt analysé ne
s'exécute », « L'environnement Git du processus ne redirige toujours pas l'analyse », « L'e-mail d'un auteur
n'est rendu que sur demande explicite »

```mermaid
sequenceDiagram
  autonumber
  participant C as "GitActivityCommand / LocalMinosMultiRepositoryApi"
  participant Q as "LocalProjectGitQuery"
  participant S as "GitIntelligenceService"
  participant O as "TrustedRepositoryOpening"
  participant J as "JGit (FileRepositoryBuilder, Repository)"
  participant FS as "Système de fichiers"
  C->>Q: inspect ou analyze(projectRoot)
  Q->>S: inspect / analyze
  S->>O: openContaining(projectRoot)
  O->>J: findGitDir(root), sans readEnvironment()
  alt aucun dépôt trouvé
    O-->>C: erreur d'usage : pas dans un dépôt Git
  end
  O->>J: build() (ouvre le Repository)
  O->>FS: Files.getOwner(gitDir) puis (commonDir)
  alt propriétaire illisible
    O-->>C: refus fail-closed, message sans chemin
  else autre propriétaire
    O-->>C: refus qui nomme les deux comptes
  else propriétaire attendu
    O->>J: lit commonDir/config, filter.* déclarés vers PassThroughFilter en mémoire
    S->>J: status(), remote, historique borné
    S-->>C: RepositoryView, limitations déclarées, e-mails masqués par défaut
  end
```

L'ouverture se fait dans `TrustedRepositoryOpening.openContaining`
(`minos-integration-git/src/main/java/com/minos/integration/git/TrustedRepositoryOpening.java:65`), qui rend le
dépôt après `adopt` (`:98`). Le contrôle du propriétaire porte sur le répertoire Git **et** sur le répertoire
commun d'un worktree lié (`TrustedRepositoryOpening.java:110-111`). Les pilotes `filter.<nom>.clean|smudge`
déclarés par la configuration du dépôt sont redirigés en mémoire vers le filtre neutre avant tout `status()`, avec
`useJGitBuiltin` forcé à faux ; la limitation `REPOSITORY_FILTERS_NOT_APPLIED` le déclare. L'appel `build()`
précède le contrôle du propriétaire : voir l'écart E1.

### Dans quel ordre un dépôt distant est-il cloné, extrait puis revalidé ?

Type : sequenceDiagram (UML, séquence) · Portée : existant vérifié · Exigences : « La matérialisation d'un dépôt
distant n'exécute aucun pilote de filtre », « La revalidation d'une extraction distante passe par l'ouverture de
confiance » (ajoutées par le changement `couvrir-le-clone-distant-par-l-ouverture-de-confiance`, ADR SEC-0083)

```mermaid
sequenceDiagram
  autonumber
  participant M as "JGitRemoteRepositoryMaterializer"
  participant C as "JGitRemoteGitClient"
  participant O as "TrustedRepositoryOpening"
  participant R as "Dépôt cloné"
  M->>C: cloneRepository(request, destination, secret, budget)
  C->>R: clone sans extraction (objets et HEAD)
  C->>O: openExact(destination)
  alt propriétaire inattendu ou illisible
    O-->>C: refus, répertoire temporaire supprimé par le matérialiseur
  else propriétaire attendu
    O->>R: tous les filter.* de la configuration effective vers PassThroughFilter, useJGitBuiltin à faux
    C->>R: extraction de HEAD (contenu brut), moniteur de budget
    M->>O: openExact(destination) pour la revalidation
    M->>R: HEAD, état propre, origine
  end
```

`JGitRemoteGitClient.cloneFrom` clone avec `setNoCheckout(true)` puis extrait par `checkOutHead`, sur le dépôt
ouvert par `openExact` (`JGitRemoteGitClient.java:70`, `:84`). `JGitRemoteRepositoryMaterializer.validateCheckout`
ouvre par la même ouverture après le clone et à chaque réutilisation du cache (`JGitRemoteRepositoryMaterializer.java:304`).
Le gate `scripts/quality/check-remote-distributed-consistency.py` refuse toute autre ouverture JGit dans le code
principal du module. Un dépôt distant à git-lfs est indexé sur ses pointeurs.

## Traçabilité

| Exigence | Scénarios couverts | Vue | Preuves | Statut |
|---|---|---|---|---|
| Aucun filtre déclaré par le dépôt analysé ne s'exécute | 4/4 (champ `clean` de la vue non asserté) | V1 | `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aParentRepositoryTrapIsNotExecutedWhenTheProjectIsBelowIt`, `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aTrapFilterReachedThroughAnIncludedFileIsNotExecuted`, `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aRepositoryShippedInsideTheProjectIsNotExecutedByAnalyze`, `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aTrapOfTheMainRepositoryIsNotExecutedFromALinkedWorktree` | partielle |
| Les filtres de l'utilisateur ne sont pas retirés | 1/1 (non-modification de la configuration non assertée) | V1 | `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aFilterOfTheUserConfigurationIsStillApplied` | couverte |
| Le propriétaire du dépôt trouvé est contrôlé avant toute lecture de son contenu | 2/3 (refus depuis un sous-dossier non testé) | V1 | `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aRepositoryOfAnotherOwnerIsRefusedBeforeAnyStatus`, `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#anUnreadableOwnerFailsClosedWithADistinctMessage`, `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aRepositoryOfTheExpectedOwnerIsAnalysedAsBefore` | partielle |
| La remontée vers un dépôt parent légitime reste possible et visible | 2/2 | V1 | `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aProjectBelowTheRepositoryRootIsReportedAsSuch` | couverte |
| L'écart de fidélité dû au retrait des filtres est déclaré | 2/2 | V1 | `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aTrapFilterInTheRepositoryConfigurationIsNotExecuted`, `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java#aRepositoryOfTheExpectedOwnerIsAnalysedAsBefore` | couverte |
| L'environnement Git du processus ne redirige toujours pas l'analyse | 1/1 (`GIT_DIR` seulement) | V1 | `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceServiceTest.java#theGitEnvironmentCannotRedirectTheAnalysedRepository` | couverte |
| L'e-mail d'un auteur n'est rendu que sur demande explicite | 5/5 | V1 | `minos-app/src/test/resources/characterization/cli-git.golden`, `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceServiceTest.java#authorEmailsAreMaskedUnlessRequestedAndStillCountDistinctAuthors`, `minos-api/src/test/java/com/minos/api/LocalMinosMultiRepositoryApiIntegrationTest.java`, `minos-app/src/test/java/com/minos/app/mcp/McpAuthorEmailGuardTest.java#noToolOfTheCatalogueRendersTheAuthorEmailOfAProject` | couverte |
| Le remote rendu ne contient ni identifiant ni chemin local | 5/5 sur `inspect` (sorties `git-activity` CLI et API non testées) | — | `minos-integration-git/src/test/java/com/minos/integration/git/GitRemoteDisclosureTest.java#aNetworkRemoteIsDisclosedWithoutCredentials`, `minos-integration-git/src/test/java/com/minos/integration/git/GitRemoteDisclosureTest.java#aLocalOrUnparsableRemoteIsWithheldAndDeclared`, `minos-integration-git/src/test/java/com/minos/integration/git/GitRemoteDisclosureTest.java#theIdentityOfARepositoryWithALocalRemoteDoesNotChange` | partielle |

Le gate `scripts/quality/check-remote-distributed-consistency.py` garde l'ouverture unique du clone distant ; les
autres preuves sont des tests Java, exécutés par les jobs `Verify` de `pr-ci.yml`.

## Écarts

| Id | Écart | Preuve | Suite proposée |
|---|---|---|---|
| E1 | Le titre de l'exigence dit « avant toute lecture de son contenu » ; `builder.build()` ouvre le `Repository` avant `requireExpectedOwner`. Aucun index ni statut n'est lu avant le contrôle, et rien n'est exécuté | `minos-integration-git/src/main/java/com/minos/integration/git/TrustedRepositoryOpening.java:101` | [Question] Vérifier si l'ouverture JGit lit déjà `.git/config` (comportement de la bibliothèque, non relu ici). Si oui : contrôler le propriétaire sur le répertoire trouvé avant `build()`, ou préciser l'exigence (« avant tout statut et toute lecture d'objet ») par un changement |
| E2 | Le contrôle du propriétaire porte aussi sur le répertoire commun d'un worktree lié ; aucun test ne prouve le refus d'un worktree lié dont le répertoire commun appartient à un autre compte | `minos-integration-git/src/main/java/com/minos/integration/git/TrustedRepositoryOpening.java:110` | [Proposition] Test de refus, puis scénario par un changement |
| E3 | Le scénario « Propriétaire illisible » décrit une `IOException` du système de fichiers ; le test l'injecte par le fournisseur de propriétaire attendu | `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java:202` | [Proposition] Test complémentaire sur `Files.getOwner` en échec |
| E4 | Une configuration de dépôt invalide est refusée (`IOException`), cas fail-closed non spécifié | `minos-integration-git/src/main/java/com/minos/integration/git/TrustedRepositoryOpening.java:213` | [Proposition] Scénario de refus à ajouter par un changement |
| E5 | Un pilote déclaré par le dépôt est neutralisé même si l'utilisateur définit un pilote du même nom | `minos-integration-git/src/main/java/com/minos/integration/git/TrustedRepositoryOpening.java:200` | [Question] Comportement voulu ? Non spécifié et non testé |

## Questions ouvertes

- `GIT_WORK_TREE` et `GIT_CEILING_DIRECTORIES` ne sont couverts que par construction (aucun `readEnvironment()`) :
  faut-il un test par variable ?
