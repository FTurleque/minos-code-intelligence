---
id: DATA-0019
title: "Résoudre les relations cross-repository uniquement par identité exacte et séparer les faits Git"
domain: data
rules: true
files: ["minos-mcp/src/main/**"]
---

# Résoudre les relations cross-repository uniquement par identité exacte et séparer les faits Git

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M12 — Multi-dépôts et intelligence Git

## Context

Un workspace peut contenir plusieurs dépôts et des références externes potentiellement résolubles entre eux. Une similarité de nom ou une activité Git élevée ne suffit ni à prouver une relation de code ni à établir une importance architecturale.

## Decision

MINOS applique deux frontières strictes.

### Résolution cross-repository

Une relation n’est promue entre dépôts que si `providerId + externalId` correspond exactement à une identité fournisseur locale, avec une cible unique et traçable. Toute ambiguïté reste non résolue.

### Intelligence Git

Les commits, auteurs, fichiers et zones modifiées sont exposés comme faits Git bornés. Ils ne sont pas automatiquement convertis en centralité, criticité ou importance architecturale.

#### Mise en œuvre — adresse e-mail des auteurs (amendement du 17 octobre 2026, AUD-SEC-10)

L'adresse e-mail d'un auteur est une donnée personnelle : elle n'est **pas** un fait Git exposé par défaut. Le composant `authorEmail` reste présent (surfaces publiques versionnées et additives, ADR 0018) mais sa valeur par défaut est un masque fixe (`GitIntelligence.MASKED_AUTHOR_EMAIL`, `[masked]`) ; l'adresse réelle n'est rendue que sur demande explicite de l'appelant (option `--include-author-emails` de `git-activity`, cinquième composant de `GitActivityQuery` de l'API Java). Le masquage s'applique à la source, dans la requête (`ActivityQuery.includeAuthorEmails`) et dans `GitIntelligenceService` : aucune surface, présente ou future, ne reçoit l'adresse sans l'avoir demandée. Le décompte des auteurs distincts continue d'utiliser l'adresse réelle en interne. Aucun outil MCP n'expose l'adresse. Une empreinte de l'adresse a été écartée : elle reste une donnée personnelle pseudonymisée. `authorName`, `workTree` et `originRemote` restent exposés (hors périmètre de cet amendement).

## Do's and Don'ts

### Do

- Ne promouvoir une relation entre dépôts que si `providerId + externalId` correspond exactement à une identité fournisseur locale, avec une cible unique et traçable.
- Laisser non résolue toute ambiguïté.
- Exposer commits, auteurs, fichiers et zones modifiées comme faits Git bornés.
- Masquer par défaut l’adresse e-mail d’un auteur et ne la rendre que sur demande explicite de l’appelant.

### Don't

- Ne pas convertir automatiquement les faits Git en centralité, criticité ou importance architecturale.
- Ne pas exposer l’adresse e-mail d’un auteur par un outil MCP.
- Ne pas remplacer l’adresse par une empreinte : elle reste une donnée personnelle pseudonymisée.

## Consequences

MINOS peut enrichir la connaissance multi-repo sans fabriquer de liens par ressemblance et sans mélanger activité de développement et structure du code.

## Compliance and Enforcement

- Test `McpAuthorEmailGuardTest` (`minos-app`, lancé par `mvnw verify`) — aucun outil du catalogue MCP ne rend l’adresse e-mail d’un auteur.
- Test `GitIntelligenceServiceTest` (`minos-integration-git`) — l’adresse est masquée par défaut (`MASKED_AUTHOR_EMAIL`).
- Règle Archgate `DATA-0019-cross-repository-identity-and-git-facts.rules.ts` / `mcp-exposes-no-author-email` : refuse toute mention de l'adresse e-mail d'auteur (`authorEmail`, `author_email`…, littéraux compris, commentaires exclus) dans `minos-mcp/src/main` ; contrôle propre à Archgate, aucun gate Python ne le fait (un enregistrement sérialisé sans nommer le champ reste couvert par `McpAuthorEmailGuardTest`).
- Aucun contrôle automatique de la résolution cross-repository par identité exacte.
- Revue : une résolution entre dépôts n’utilise que l’identité exacte `providerId + externalId` ; une surface nouvelle ne reçoit l’adresse e-mail que sur demande explicite.

## Preuves

Voir [`../../project/history/milestones/m12/DECISION_M12.md`](../../project/history/milestones/m12/DECISION_M12.md) et [`../../project/history/milestones/m12/MULTI_REPO_GIT.md`](../../project/history/milestones/m12/MULTI_REPO_GIT.md).
