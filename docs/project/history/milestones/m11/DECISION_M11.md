# Décision M11 — API publique

Statut : **VERDICT PRÉPARÉ — porte locale finale en attente**

Suivi : issue #33. PR : #34.

## Question de décision

> Des systèmes externes peuvent-ils consommer les capacités MINOS via un contrat Java public stable, sans dépendre de Glean, SCIP, du stockage local, de la CLI, du MCP ni des modèles internes ?

## Éléments de preuve

M11 introduit un contrat versionné `MinosApi` et une implémentation locale `LocalMinosApi`.

La surface couvre :

```text
projets
index
symboles
usages
relations
architecture
contexte module
impact
```

Les DTO publics ne référencent que des types JDK et les types `MinosApi` eux-mêmes.

`LocalMinosApi` délègue aux services déjà qualifiés par M1–M8 et aux opérations locales stabilisées par M9. Aucune analyse métier n'est dupliquée dans `com.minos.api`.

## Frontières maintenues

- pas de dépendance publique à Glean ;
- pas de dépendance publique à SCIP ;
- pas de fuite des stores ou adaptateurs ;
- pas de dépendance publique à la CLI ou au MCP ;
- pas de serveur HTTP ni framework web ajouté ;
- import SCIP explicite uniquement, sans runner de production fictif ;
- limitations runtime de l'analyse d'impact conservées.

## Qualification

`LocalMinosApiIntegrationTest` utilise la fixture réelle `typescript-modules` et doit confirmer :

```text
contract version       1
project modules        3
index state            READY
relation               IMPLEMENTS
architecture modules   3
impact GreetingPort    2
potential tests        1
invalid enum           INVALID_REQUEST
```

Replay attendu :

```text
M11 public API: version=1, project=<uuid>, snapshot=<snapshot>, modules=3, impact=2, tests=1
```

## Verdict préparé

> **OUI, via un contrat Java local versionné dont les DTO publics restent indépendants des fournisseurs, protocoles et modèles internes, tout en déléguant l'intelligence au cœur MINOS existant.**

Ce verdict devient définitif uniquement après succès de :

```powershell
.\mvnw.cmd clean verify
```

sur le head exact final de la PR #34.
