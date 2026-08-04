# M21-S2 — Reprise août 2026 — disposition finale

Statut : **EXÉCUTÉ / TERMINÉ — 1er août 2026.**

Ce document conserve la disposition finale de la reprise M21-S2 après le gel de juillet 2026.

## Résultat

```text
CI recovery                 : PASS
Required checks observés    : PASS
Branch protection readiness : BLOCKED — contrainte plan/API du dépôt privé
Disposition                 : PASS_WITH_CONSTRAINTS
Issue M21 #73               : CLOSED / completed
PR promotion #102           : MERGED
Release v1.0.0              : PUBLIÉE
```

Le blocage branch protection n'a jamais été transformé en faux PASS : l'API GitHub ne permettait pas de configurer/vérifier la protection cible dans les conditions de plan du dépôt. La contrainte plateforme a donc été enregistrée explicitement.

## Séquence réellement fermée

1. reprise après le 1er août 2026 ;
2. diagnostic des workflows/permissions ;
3. récupération des gates Windows/Linux requis ;
4. qualification du candidat `develop` ;
5. disposition `PASS_WITH_CONSTRAINTS` pour M21-S2 ;
6. fermeture de #73 ;
7. promotion `develop → main` via #102 ;
8. publication stable 1.0.0.

## Historique de preuve

Le candidat final de promotion de `develop` était :

```text
ce4b6ba5f28ecbe3273919318cd950adcf6a0d80
```

La promotion vers `main` a produit :

```text
71738c1d65cc0aae9fd5c5b34e898d72e164a4f4
```

La release 1.0.0 a ensuite été publiée sur :

```text
1adbc45339efe37cd26d1937025bfa69d7b57811
```

## État courant

Ce runbook n'est plus une liste de travaux en attente. **#73 est fermé et #102 est mergée.**

Le seul reliquat produit explicitement ouvert dans cette zone de gouvernance est #98, qui suit l'implémentation future d'une vraie sandbox OS Windows/Linux. Il ne doit pas être confondu avec M21-S2.

Le défaut de packaging Windows MCP découvert après la publication 1.0.0 est traité séparément par la maintenance 1.0.1 ; il ne rouvre pas M21-S2.
