# Décision de clôture M3 — Intelligence des relations

Date : **23 juillet 2026**

Statut : **M3 TERMINÉ ET VALIDÉ LOCALEMENT**

## Verdict

Le jalon M3 est clôturé. MINOS persiste et interroge les usages et relations
normalisés, expose les implémentations, appels disponibles et dépendances dans
la CLI, et conserve pour chaque résultat la résolution, la provenance, la
nature, la confiance et les preuves nécessaires à son explication.

## Porte de sortie

| Critère | Résultat |
|---|---|
| Références et usages résolus | satisfait |
| Implémentations entrantes | satisfait selon la sémantique SCIP qualifiée |
| Appelants/appelés | contrat et CLI satisfaits lorsque `CALLS` est disponible |
| Dépendances et dépendants | satisfaits par dérivation directe explicable |
| Relations entrantes/sortantes | satisfaites avec isolation projet |
| Provenance, preuves et confiance | satisfaites et persistées |
| Cibles non résolues | représentées explicitement |
| Snapshot rechargeable | format v2 satisfait, v1 rétrocompatible |
| CLI en nouveau processus | satisfaite |
| Frontière fournisseur | satisfaite, aucun type SCIP hors adaptateur |
| Validation sur artefacts réels | satisfaite avec limites documentées |

## Preuves techniques

```text
.\mvnw.cmd clean verify
80 sources main compilées
37 sources test compilées
115 tests réussis
0 échec
0 erreur
0 skipped
BUILD SUCCESS
```

Le test du launcher publie un snapshot complet, ferme le contexte producteur et
démarre une nouvelle JVM qui exécute `find-implementations`. Les tests couvrent
également les six commandes M3, les deux formats, les erreurs, les limites,
l'ordre, l'immutabilité, la corruption et la compatibilité v1/v2.

## Rejeu fournisseur

Les quatre index réels `scip-typescript 0.4.0` totalisent :

| Mesure | Résultat |
|---|---:|
| messages relationnels | 19 |
| faits booléens SCIP | 26 |
| faits MINOS normalisés/résolus | 26 / 26 |
| faits ignorés / doublons | 0 / 0 |
| dépendances directes dérivées | 19 |
| faits `CALLS` inventés | 0 |
| faits `EXTENDS` inventés | 0 |

Une preuve persistante plus complète a été exécutée sur `typescript-simple` :
24 symboles, 100 occurrences, 3 faits SCIP, 2 dépendances dérivées et 5
relations rechargées. Dans des processus CLI distincts, `UserRepository`
retourne 4 usages et 1 implémentation ; `InMemoryUserRepository` retourne 1
dépendance ; la vue inverse retourne 1 dépendant.

## Limites assumées

- `scip-typescript 0.4.0` n'émet aucun fait `CALLS` dans les quatre artefacts
  qualifiés. Les commandes appelants/appelés sont donc prouvées par tests
  contrôlés et retournent correctement zéro sur ce fournisseur réel.
- `is_implementation` signifie « Find implementations » dans SCIP. Il peut
  couvrir implémentation, héritage ou override ; MINOS ne le rebaptise pas
  `EXTENDS` sans preuve plus précise.
- les kinds TypeScript absents restent `OTHER` et les identités de surcharge
  non distinguées par le fournisseur ne sont pas inventées.
- GitHub Actions n'a pas été relancé et ne fait pas partie de cette décision
  locale.

Ces limites sont des qualifications de capacité, pas des données silencieusement
fabriquées ou perdues.

## Suite

Cette suite est désormais livrée par M4. Le jalon suivant est M5 — Tests liés
et dérivations explicables. Voir [`../m4/DECISION_M4.md`](../m4/DECISION_M4.md).
