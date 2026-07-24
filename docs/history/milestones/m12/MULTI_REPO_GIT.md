# M12 — Multi-dépôts et intelligence Git

Statut : **INTÉGRALEMENT IMPLÉMENTÉ — validation locale finale en attente**

Suivi : issue #35. PR : #36.

## Objectif

M12 ajoute deux dimensions factuelles à MINOS :

1. une vue multi-dépôts fondée sur les workspaces persistants de M1 ;
2. une intelligence Git locale et bornée décrivant l’activité observée dans l’historique.

La porte M12 est :

> MINOS peut-il raisonner factuellement sur plusieurs dépôts d’un même workspace et enrichir la Code Intelligence avec l’historique Git, sans inventer de relations inter-dépôts ni confondre activité Git et importance architecturale ?

## Dépendance M11

M12 est développé sur le head M11 validé :

```text
fae552e8e6f2aa66c327fb80485f5bad448d7520
```

La PR #36 est temporairement empilée sur `m11/public-api`. Elle sera retargetée sur `main` après fusion explicitement autorisée de la PR #34.

## Runtime Git

M12 utilise JGit :

```text
org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r
```

Le moteur Git est donc Java pur et ne lance aucune commande `git` native.

## Surface interne

```text
com.minos.git.GitIntelligenceService
com.minos.workspace.WorkspaceIntelligenceService
```

Les responsabilités sont séparées :

- `GitIntelligenceService` lit les faits temporels Git ;
- `WorkspaceIntelligenceService` combine registre M1 et snapshots M2/M3 pour les vues multi-repository.

Aucune fréquence de commit n’est transformée en centralité, importance métier ou criticité architecturale.

## API publique additive

M11 reste inchangé :

```text
com.minos.api.MinosApi
CONTRACT_VERSION = 1
```

M12 ajoute :

```text
com.minos.api.MinosMultiRepositoryApi
com.minos.api.LocalMinosMultiRepositoryApi
MULTI_REPOSITORY_CONTRACT_VERSION = 1
```

Le choix d’une interface additive évite d’ajouter de nouvelles méthodes abstraites au contrat M11 et préserve sa frontière publique déjà validée.

### Workspaces

```java
WorkspaceDto createWorkspace(String name)
List<WorkspaceDto> listWorkspaces()
WorkspaceDto getWorkspace(String workspaceIdentifier)
WorkspaceDto assignProjectToWorkspace(String projectIdentifier, String workspaceIdentifier)
```

Les identifiants peuvent être utilisés directement. Les noms exacts restent acceptés lorsqu’ils sont non ambigus.

### Inspection Git

```java
GitRepositoryDto inspectGit(String projectIdentifier)
```

Le DTO expose :

- identité stable dérivée du remote `origin` assaini, ou du worktree si aucun remote n’existe ;
- worktree ;
- remote `origin` sans credentials ;
- branche ;
- commit HEAD ;
- HEAD détachée ;
- clone shallow ;
- état propre/sale du worktree ;
- limitations explicites.

### Activité Git

```java
GitActivityDto analyzeGitActivity(String projectIdentifier, GitActivityQuery query)
```

Bornes :

```text
maxCommits  1..10000
maxFiles    1..10000
zoneDepth   1..8
```

Le rapport conserve :

- commits récents dans la fenêtre `since` ;
- chemins modifiés par commit ;
- fréquence de modification par fichier ;
- nombre d’auteurs distincts par fichier ;
- dernier commit et dernière date observée ;
- zones d’activité agrégées par préfixe de répertoire ;
- troncatures et limitations.

Pour un commit de merge, les chemins sont calculés par rapport au premier parent. Pour un commit racine, le diff est calculé contre un arbre vide.

## Résolution cross-repository

```java
WorkspaceIntelligenceDto analyzeWorkspace(String workspaceIdentifier, WorkspaceQuery query)
```

La résolution utilise uniquement une preuve d’identité fournisseur exacte :

```text
relationship.origin.providerId
+
relationship.unresolvedTarget
==
localSymbol.providerReference.providerId
+
localSymbol.providerReference.externalId
```

Conditions de promotion :

1. la relation est actuellement non résolue ;
2. la cible candidate appartient à un autre projet du même workspace ;
3. le symbole candidat est local, pas un placeholder externe ;
4. le couple `providerId + externalId` correspond exactement ;
5. une seule cible correspond.

Le résultat public porte :

```text
resolutionBasis = EXACT_PROVIDER_REFERENCE
confidence      = 1.0
```

### Ce qui n’est volontairement pas résolu

MINOS ne promeut pas automatiquement une cible cross-repository sur la seule base :

- du nom ;
- du `qualifiedName` ;
- d’une similarité de chemin ;
- d’une fréquence Git ;
- d’une proximité temporelle.

Zéro cible exacte donne `UNRESOLVED_CROSS_REPOSITORY_TARGETS`.

Plusieurs cibles exactes donnent `AMBIGUOUS_PROVIDER_IDENTITY`.

Le service ne réécrit pas les snapshots M2/M3 : M12 fournit une vue dérivée explicable et réversible.

## Limitations Git explicites

Le moteur peut signaler notamment :

```text
NO_ORIGIN_REMOTE
DETACHED_HEAD
SHALLOW_HISTORY
UNBORN_HEAD
HISTORY_TRUNCATED
FILES_TRUNCATED
```

Un historique shallow reste exploitable pour les faits présents localement, mais MINOS ne revendique jamais une vue complète de l’historique absent.

## Qualification

### Git synthétique

`GitIntelligenceServiceTest` crée un dépôt avec JGit, deux commits et deux auteurs. Il vérifie :

```text
commits observés       2
src/App.java touches   2
src/App.java auteurs   2
README.md touches      1
zone src touches       2
origin absent          explicite
```

### Cross-repository

`WorkspaceIntelligenceServiceTest` publie deux snapshots de projets dans un même workspace :

- une relation non résolue porte exactement l’identité fournisseur du symbole cible du second projet ;
- une seconde relation ne porte que `GreetingPort`.

Résultat attendu :

```text
exact provider resolution  1
name-only resolution        0
remaining unresolved        1
```

### API publique

`LocalMinosMultiRepositoryApiIntegrationTest` vérifie :

```text
contrat M11             1
contrat M12             1
workspace               créé + projet affecté
Git HEAD                disponible
Git commits             1
activité fichier         src/Main.java
projet sans snapshot     limitation explicite
```

Replay attendu :

```text
M12 multi-repo Git: workspace=<uuid>, projects=1, git-commits=1, files=1, exact-cross-repo=0
```

`MinosMultiRepositoryApiContractTest` vérifie par réflexion qu’aucun type interne MINOS ou JGit ne traverse le contrat public M12.

## Volumes attendus

Par rapport au head M11 validé :

```text
M11     154 sources main / 79 sources test / 214 tests
M12     158 sources main / 83 sources test / 221 tests attendus
```

Ces volumes restent à confirmer par la porte locale finale.

## Porte finale

```powershell
.\mvnw.cmd clean verify
```

Le head exact M12 final doit passer sous Java 24 avant passage Ready ou fusion.
