# Design

## Context

Sprint 7 de l'audit du 10 octobre 2026 : **AUD-SEC-10**. HEAD analysé : `949e1569` (`develop`). Règle de référence : `.claude/rules/securite.md`, point 7 (aucun chemin absolu ni e-mail d'auteur dans une sortie publique). `GOLD` = `minos-app/src/test/resources/characterization`.

## État vérifié au HEAD

### AUD-SEC-10, racine absolue — confirmé

- `minos-application/src/main/java/com/minos/output/ProjectJson.java:27-42` : `project(ProjectSummary)` rend `"rootPath", project.rootPath()` (`:32`) et `"rootAvailable"` (`:33`).
- `minos-mcp/src/main/java/com/minos/mcp/MinosApplicationMcpBackend.java:86-91` : `projectStructure` appelle `ProjectJson.project(view)` (`:88`), ajoute `providerProfiles` et rend la table.
- `ProjectJson` est partagé avec la CLI. Le plugin IntelliJ lit `rootPath` dans la sortie de la CLI : `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosProjectList.java:47` (résolution du projet ouvert) et `minos-intellij/src/main/java/com/minos/intellij/service/MinosProjectService.java:40` (`Path.of(…).toAbsolutePath()`).
- Goldens MCP : `GOLD/mcp.golden` (ligne 4 : `"rootPath":"<REPO>/fixtures/…"`) et `GOLD/mcp-all-tools.golden`.
- Aucune occurrence de `rootPath` dans `minos-mcp/src/main` : le champ n'y entre que par `ProjectJson`. Aucun champ `contractVersion` n'a été relevé dans `minos-mcp/src/main`.

### AUD-SEC-10, e-mail des auteurs — confirmé

- `minos-integration-git/src/main/java/com/minos/integration/git/GitIntelligenceService.java:155-162` : `CommitActivity` reçoit `commit.getAuthorIdent().getEmailAddress()` (`:159`). La même adresse est relue en `:508` pour l'activité par fichier, où les auteurs distincts sont comptés (`:605-625`).
- `minos-cli/src/main/java/com/minos/cli/command/GitActivityCommand.java:131-140` : `commitMap` rend `authorName` (`:135`) et `authorEmail` (`:136`) ; `:120-121` rendent `workTree` et `originRemote`.
- `minos-api/src/main/java/com/minos/api/LocalMinosMultiRepositoryApi.java:266-271` : `GitCommitDto` construit avec `value.authorEmail()` (`:268`) ; composant déclaré en `minos-api/src/main/java/com/minos/api/MinosMultiRepositoryApi.java:183`.
- `GOLD/cli-git.golden` : 4 occurrences de l'adresse de test `a2@example.invalid`.
- ADR 0019 (`docs/architecture/adr/DATA-0019-cross-repository-identity-and-git-facts.md`, « Intelligence Git ») : « Les commits, auteurs, fichiers et zones modifiées sont exposés comme faits Git bornés. »

**Non vérifié** : aucun build ni test lancé. Non relevé : si le format texte de `git-activity` affiche l'adresse ; si `GOLD/api.golden` en contient une ; les autres réponses MCP qui portent un chemin absolu ; la consommation de `rootPath` par NEXUS. **Non vérifié** : la rédaction de l'URL `originRemote` (identifiants éventuels dans l'URL). Repris de la contre-vérification sans relecture exhaustive : « aucun outil MCP n'expose l'e-mail ».

### Écarts avec l'action proposée par l'audit

1. **« Un chemin relatif à MINOS_HOME » est inapplicable.** Un projet n'est pas sous `MINOS_HOME` ; il n'existe pas de base commune dont la racine du projet serait un descendant.
2. **`ProjectJson` ne peut pas changer.** Le plugin IntelliJ dépend de `rootPath` absolu dans la sortie de la CLI. La correction se fait dans le backend MCP seul.
3. **Retirer une clé d'une réponse MCP est une rupture d'une surface additive** ; en changer la valeur est une rupture de sens. L'audit présente le choix comme anodin (D1).
4. **« Hacher l'e-mail » n'anonymise pas.** L'empreinte d'une adresse connue se retrouve par dictionnaire ; elle reste une donnée personnelle pseudonymisée et crée un identifiant stable entre dépôts. Un masque fixe est retenu.
5. **L'e-mail sert au calcul interne.** Le décompte des auteurs distincts (`GitIntelligenceService.java:508`, `:605-625`) en dépend : le masquage ne doit toucher que la valeur rendue.
6. **`GitCommitDto.authorEmail` est un composant de l'API versionnée** (ADR 0018) : il reste ; seule sa valeur par défaut change, et l'ADR 0019 doit le dire.

## Goals / Non-Goals

**Goals** : le modèle connecté au MCP ne reçoit plus la racine absolue d'un projet ; l'e-mail d'un auteur ne sort que sur demande explicite ; le plugin IntelliJ et les signatures de l'API Java restent compatibles ; les décomptes Git sont inchangés.

**Non-Goals** : masquer `authorName`, `workTree`, `originRemote` ; modifier `ProjectJson` ou la sortie CLI de la fiche projet ; anonymiser l'historique.

## Decisions

### Relevés (2026-10-17, HEAD `6620f2a4`)

- **Réponses MCP portant un chemin absolu** : dans `mcp.golden` et `mcp-all-tools.golden`, une seule occurrence de `<REPO>` : le `rootPath` de `minos_project_structure`. Les autres réponses rendent des chemins relatifs à la racine.
- **Numéro de version de contrat dans la réponse de `minos_project_structure`** : aucun (aucune occurrence de `contractVersion` dans `minos-mcp/src/main`).
- **`ProjectPathMapping`** : sert à l'indexation Docker (`DefaultMinosApplicationComposer`, `DockerRuntimeBootstrap`), pas à la fiche projet ; la variante « racine relative au mapping » est écartée (rien à rendre de plus utile qu'un nom).
- **NEXUS** : ne lit pas `rootPath` par le MCP (`NexusExportService` lit le registre ; `NexusExportContract.rootPath` est son propre contrat d'export).
- **Format texte de `git-activity`** : n'affiche aucun commit, donc aucune adresse. `api.golden` ne contient aucune adresse. Le plugin IntelliJ ne lit pas `authorEmail`. Type porteur du paramètre de l'API : `MinosMultiRepositoryApi.GitActivityQuery`.

### D1. Forme de `rootPath` dans la réponse MCP — **décision du propriétaire : option A** (2026-10-17)

**Option A (recommandée) : garder la clé, avec une valeur non absolue.** `rootPath` porte le nom du répertoire racine (dernier segment) ; `rootAvailable` reste. La structure de la réponse ne change pas, aucun client ne casse sur une clé absente, et l'absence de numéro de version de contrat dans cette réponse rend un retrait impossible à annoncer. Limite : un client qui utilisait la valeur comme chemin obtient une valeur inutilisable, sans erreur — à écrire dans `docs/user/mcp.md` et les notes de version. Variante à examiner en tâche 1.1 : la racine relative au mapping hôte/conteneur quand il existe (`ProjectPathMapping`).

**Option B : retirer la clé**, avec montée de version du contrat MCP. Plus net (échec visible), mais c'est une rupture d'une surface additive et il faut d'abord définir où cette version est publiée.

**Option C : ne rien changer et documenter** que le MCP expose la racine. Contredit le point 7 de la règle de sécurité ; non recommandé.

**Mise en œuvre** : `MinosApplicationMcpBackend.rootDirectoryName` (dernier segment, séparateurs `/` et `\` quelle que soit la plateforme : un chemin Windows ou UNC enregistré depuis un conteneur Linux est aussi traité) ; une racine de volume (`/`, `D:\`, `D:`) rend `(filesystem root)`. Preuve : `MinosMcpProjectRootExposureTest` (rouge au HEAD : la racine absolue figurait dans la réponse), goldens `mcp.golden` et `mcp-all-tools.golden` mis à jour (`"rootPath":"typescript-modules"`), `ProjectJsonTest` et `cli-json.golden` inchangés (la CLI garde le chemin absolu).

### D2. E-mail masqué par défaut — **décision du propriétaire** (2026-10-17)

**Recommandé** : par défaut, `authorEmail` est présent et vaut un masque fixe ; l'adresse réelle n'est rendue que si l'appelant la demande — option nouvelle de `git-activity` et paramètre additif de la requête de l'API Java. C'est un **changement de la sortie par défaut** de deux surfaces versionnées : un consommateur qui lisait l'adresse doit ajouter l'option.

**Alternative** : défaut inchangé et option de masquage. Compatible à l'identique, mais la donnée personnelle continue de sortir sans avoir été demandée.

**Écartée** : empreinte de l'adresse (écart 4).

**Valeurs retenues** : masque `[masked]` (`GitIntelligence.MASKED_AUTHOR_EMAIL`) ; option CLI `--include-author-emails` ; cinquième composant `includeAuthorEmails` de `ActivityQuery` (engine) et de `GitActivityQuery` (API Java), constructeur à quatre composants conservé (compatibilité binaire et source). Preuves : `GitIntelligenceServiceTest` (masque par défaut, adresse sur demande, deux adresses = deux auteurs), `CliValidInvocationsTest` (option transmise), `LocalMinosMultiRepositoryApiIntegrationTest`, `McpAuthorEmailGuardTest` (aucun outil du catalogue ne rend l'adresse), `cli-git.golden` (4 masques par défaut, 2 adresses sur l'invocation explicite ajoutée). Non mesuré : la compilation d'un appelant tiers compilé contre l'ancienne version de l'API (le constructeur à quatre composants est conservé, non rejoué contre un jar tiers).

### D3. Masquer à la source

La demande d'affichage est un attribut de la requête Git (`ActivityQuery`, `minos-engine/src/main/java/com/minos/git/GitIntelligence.java:23`, record à quatre composants), faux par défaut — ajouté avec un constructeur à l'ancienne signature pour ne casser aucun appelant ; `GitIntelligenceService` ne place l'adresse dans `CommitActivity` que s'il est vrai. Aucune surface, présente ou future, ne reçoit l'adresse sans l'avoir demandée. Le calcul des auteurs distincts continue d'utiliser l'adresse réelle, en interne.

### D4. La correction MCP reste dans le backend MCP

`MinosApplicationMcpBackend.projectStructure` remplace la valeur (ou retire la clé) après l'appel de `ProjectJson.project`. Un test affirme que la sortie CLI correspondante est inchangée.

### D5. ADR

ADR 0019 amendé en tâche (« Mise en œuvre » : l'adresse d'un auteur n'est rendue que sur demande). Si D1-B est retenue, la version du contrat MCP relève d'une décision à écrire (ADR 0017 complété, ou ADR au prochain numéro libre de `docs/architecture/adr/README.md`).

## Direction des dépendances

`engine` (type de requête) → `integration-git` (adaptateur) → `cli` / `api` (surfaces) ; `mcp` → `application` (`ProjectJson`). Aucune arête nouvelle, aucune surface vers un adaptateur.

## Qualification des capacités

- Fiche projet par le MCP : qualifiée ; son champ `rootPath` devient **non absolu** (A) ou absent (B).
- Activité Git avec e-mail : qualifiée **sur demande explicite** ; masquée par défaut.
- Masquage de `workTree` et `originRemote` : **non supporté**, déclaré hors périmètre.

## Windows et Linux

Le dernier segment d'une racine se calcule par `Path`, pas par découpage de chaîne (séparateurs, lecteur seul comme `D:\`, chemin UNC). Les tests couvrent une racine de lecteur et un chemin UNC sous Windows, `/` sous Linux.

## Risks / Trade-offs

- D1-A : changement de sens silencieux d'une valeur ; D1-B : rupture visible. Les deux touchent `mcp.golden` et `mcp-all-tools.golden`.
- D2 : `cli-git.golden` change (4 valeurs) ; un script d'utilisateur qui lisait l'adresse doit ajouter l'option.
- Un paramètre additif de l'API Java ne doit pas casser un appelant compilé contre la version précédente : surcharge ou valeur par défaut, pas de changement de signature existante.
- Le code neuf de `minos-api` et de `minos-mcp` est jugé par SonarCloud module par module : tests dans chaque module.
