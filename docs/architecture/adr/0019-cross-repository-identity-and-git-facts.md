# ADR-0019 — Résoudre les relations cross-repository uniquement par identité exacte et séparer les faits Git

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M12 — Multi-dépôts et intelligence Git

## Contexte

Un workspace peut contenir plusieurs dépôts et des références externes potentiellement résolubles entre eux. Une similarité de nom ou une activité Git élevée ne suffit ni à prouver une relation de code ni à établir une importance architecturale.

## Décision

MINOS applique deux frontières strictes.

### Résolution cross-repository

Une relation n’est promue entre dépôts que si `providerId + externalId` correspond exactement à une identité fournisseur locale, avec une cible unique et traçable. Toute ambiguïté reste non résolue.

### Intelligence Git

Les commits, auteurs, fichiers et zones modifiées sont exposés comme faits Git bornés. Ils ne sont pas automatiquement convertis en centralité, criticité ou importance architecturale.

#### Mise en œuvre — adresse e-mail des auteurs (amendement du 17 octobre 2026, AUD-SEC-10)

L'adresse e-mail d'un auteur est une donnée personnelle : elle n'est **pas** un fait Git exposé par défaut. Le composant `authorEmail` reste présent (surfaces publiques versionnées et additives, ADR 0018) mais sa valeur par défaut est un masque fixe (`GitIntelligence.MASKED_AUTHOR_EMAIL`, `[masked]`) ; l'adresse réelle n'est rendue que sur demande explicite de l'appelant (option `--include-author-emails` de `git-activity`, cinquième composant de `GitActivityQuery` de l'API Java). Le masquage s'applique à la source, dans la requête (`ActivityQuery.includeAuthorEmails`) et dans `GitIntelligenceService` : aucune surface, présente ou future, ne reçoit l'adresse sans l'avoir demandée. Le décompte des auteurs distincts continue d'utiliser l'adresse réelle en interne. Aucun outil MCP n'expose l'adresse. Une empreinte de l'adresse a été écartée : elle reste une donnée personnelle pseudonymisée. `authorName`, `workTree` et `originRemote` restent exposés (hors périmètre de cet amendement).

## Conséquences

MINOS peut enrichir la connaissance multi-repo sans fabriquer de liens par ressemblance et sans mélanger activité de développement et structure du code.

## Preuves

Voir [`../../project/history/milestones/m12/DECISION_M12.md`](../../project/history/milestones/m12/DECISION_M12.md) et [`../../project/history/milestones/m12/MULTI_REPO_GIT.md`](../../project/history/milestones/m12/MULTI_REPO_GIT.md).
