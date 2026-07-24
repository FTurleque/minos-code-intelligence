# État courant — MINOS

Dernière mise à jour : **24 juillet 2026**

Ce document est le tableau de bord opérationnel compact de MINOS. Les preuves détaillées restent dans les documents de jalon, les décisions et les issues GitHub.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET LIVRÉ
M1 — Découverte et orchestration    TERMINÉ ET LIVRÉ
M2 — Intelligence des symboles      TERMINÉ ET LIVRÉ
M3 — Intelligence des relations     TERMINÉ ET LIVRÉ
M4 — Recherche et contexte compact  TERMINÉ ET LIVRÉ
M5 — Tests liés et dérivations      TERMINÉ ET LIVRÉ
M6 — Intelligence d’architecture    TERMINÉ, VALIDÉ ET LIVRÉ
M7 — Indexation incrémentale        TERMINÉ, VALIDÉ ET LIVRÉ
M8 — Analyse d’impact               TERMINÉ, VALIDÉ ET LIVRÉ
M9 — CLI stabilisée                 TERMINÉ, VALIDÉ ET LIVRÉ
M10 — Serveur MCP                   TERMINÉ, VALIDÉ ET LIVRÉ
M11 — API publique                  VALIDÉ — FUSION EN ATTENTE D’AUTORISATION
M12 — Multi-dépôts + Git            IMPLÉMENTÉ — PORTE FINALE EN ATTENTE
M13 — Intégration NEXUS             NON DÉMARRÉ
```

GitHub Actions reste hors de la porte locale courante ; l’anomalie historique est suivie séparément dans #5.

## Portes acquises

```text
M2    86 tests   BUILD SUCCESS
M3   115 tests   BUILD SUCCESS
M4   131 tests   BUILD SUCCESS
M5   140 tests   BUILD SUCCESS
M6   162 tests   BUILD SUCCESS
M7   196 tests   BUILD SUCCESS
M8   203 tests   BUILD SUCCESS
M9   207 tests   BUILD SUCCESS
M10  210 tests   BUILD SUCCESS
M11  214 tests   BUILD SUCCESS
```

## M10 — Serveur MCP — LIVRÉ

Issue #31 clôturée. PR #32 fusionnée.

```text
head validé   3f3657a6e5c1a783993348c892f97138d990feff
merge         eb042852a936ad2e62e337ee35ed8a349096e794
sources       152 main / 77 test
tests         210/210 PASS
```

Choix techniques :

```text
SDK MCP Java officiel   2.0.0
Transport               STDIO
API serveur             synchrone
Framework web           aucun
Tools                    15 read-only
```

Replay :

```text
M10 MCP stdio: tools=15, project=<uuid>, snapshot=<snapshot>, architecture-modules=3, impact-root=GreetingPort
```

Décision : `docs/m10/DECISION_M10.md`.

## M11 — API publique — VALIDÉ

Suivi : issue #33. PR #34 **Ready for review**, non fusionnée.

Branche :

```text
m11/public-api
```

Head exact validé :

```text
fae552e8e6f2aa66c327fb80485f5bad448d7520
```

Porte locale acquise sous Java 24 :

```text
154 sources main
79 sources test
214/214 tests PASS
0 failures
0 errors
0 skipped
BUILD SUCCESS
```

Contrat :

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

Surface : projets, import SCIP explicite, symboles, usages, relations, architecture, contexte module et impact.

La frontière publique n’expose ni SCIP/Glean, ni store, ni CLI/MCP, ni modèle métier interne.

Replay acquis :

```text
M11 public API: version=1, project=<uuid>, snapshot=scip-7f41649a3cdad442a3235c0a, modules=3, impact=2, tests=1
```

Verdict :

> **OUI, via un contrat Java local versionné dont les DTO publics restent indépendants des fournisseurs, protocoles et modèles internes, tout en déléguant l’intelligence au cœur MINOS existant.**

La fusion de #34 reste soumise à une autorisation explicite. Tant qu’elle n’est pas fusionnée, issue #33 reste ouverte.

## M12 — Multi-dépôts et intelligence Git — IMPLÉMENTÉ

Suivi : issue #35.

PR Draft empilée : #36.

Branche :

```text
m12/multi-repo-git
```

Base fonctionnelle : head M11 validé `fae552e8e6f2aa66c327fb80485f5bad448d7520`.

La PR #36 cible temporairement `m11/public-api`. Après fusion autorisée de #34, elle devra être retargetée sur `main` avant livraison finale.

### Porte produit

> MINOS peut-il raisonner factuellement sur plusieurs dépôts d’un même workspace et enrichir la Code Intelligence avec l’historique Git, sans inventer de relations inter-dépôts ni confondre activité Git et importance architecturale ?

### Surface implémentée

```text
workspaces M1 exposés publiquement
assignation projet -> workspace
vue multi-projets
résolution cross-repository exacte et unique
inspection dépôt Git local
HEAD / branche / remote assaini / shallow / detached / clean
historique borné
changements récents
activité par fichier
nombre d’auteurs distincts
zones d’activité
limitations explicites
```

### Contrat public M12

M11 reste inchangé. M12 ajoute une interface additive :

```text
com.minos.api.MinosMultiRepositoryApi
com.minos.api.LocalMinosMultiRepositoryApi
MULTI_REPOSITORY_CONTRACT_VERSION = 1
```

Le contrat M12 ne fuit ni type interne MINOS, ni type JGit.

### Résolution cross-repository

Une relation non résolue n’est promue que si :

```text
relationship.origin.providerId + relationship.unresolvedTarget
```

correspond exactement à :

```text
localSymbol.providerReference.providerId + localSymbol.providerReference.externalId
```

et qu’une seule cible locale d’un autre projet du workspace correspond.

Un nom ou `qualifiedName` identique ne suffit pas.

### Intelligence Git

Runtime :

```text
org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r
```

Aucune commande `git` native n’est lancée.

Bornes publiques :

```text
maxCommits       1..10000
maxFiles         1..10000
zoneDepth        1..8
maxRelationships 1..10000
```

Limitations possibles :

```text
NO_ORIGIN_REMOTE
DETACHED_HEAD
SHALLOW_HISTORY
UNBORN_HEAD
HISTORY_TRUNCATED
FILES_TRUNCATED
PROJECT_WITHOUT_ACTIVE_SNAPSHOT
AMBIGUOUS_PROVIDER_IDENTITY
UNRESOLVED_CROSS_REPOSITORY_TARGETS
RELATIONSHIPS_TRUNCATED
```

### Qualification ajoutée

```text
MinosMultiRepositoryApiContractTest
  -> frontière publique + absence de fuite JGit

GitIntelligenceServiceTest
  -> dépôt JGit synthétique, 2 commits, 2 auteurs
  -> fréquence fichier + zones + absence remote

WorkspaceIntelligenceServiceTest
  -> 2 projets / 2 snapshots
  -> 1 identité fournisseur exacte résolue
  -> cible name-only volontairement non résolue

LocalMinosMultiRepositoryApiIntegrationTest
  -> API publique M11 + M12
  -> workspace + Git + limitation projet non indexé
  -> null query => INVALID_REQUEST
```

Replay attendu :

```text
M12 multi-repo Git: workspace=<uuid>, projects=1, git-commits=1, files=1, exact-cross-repo=0
```

## Porte active — finale M12

Head exact : à figer après les derniers commits de documentation/administration.

Commande :

```powershell
.\mvnw.cmd clean verify
```

Volumes attendus :

```text
158 sources main
83 sources test
221 tests
```

Ces nombres sont **attendus mais non encore validés localement**.

La PR #36 reste Draft jusqu’à validation Java 24 du head exact final.

## Après porte M12 verte

Sans autorisation explicite, aucune fusion n’est effectuée.

Ordre administratif prévu :

1. fusion explicitement autorisée de M11 / PR #34 ;
2. retarget de PR #36 vers `main` ;
3. vérification que le head M12 qualifié reste inchangé et que la diff est correcte ;
4. passage Ready de #36 après preuve locale verte ;
5. fusion M12 uniquement après autorisation explicite ;
6. clôture issue #35 ;
7. M13 — Intégration NEXUS devient le jalon actif.

## Sources de vérité

- roadmap : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- M11 : issue #33 / PR #34 / `docs/m11/API.md` / `docs/m11/DECISION_M11.md` ;
- M12 : issue #35 / PR #36 / `docs/m12/MULTI_REPO_GIT.md` / `docs/m12/DECISION_M12.md`.
