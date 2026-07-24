# Décision M8 — Analyse d’impact

Date : **23 juillet 2026**

Statut : **DÉCISION PRÉPARÉE — VALIDATION LOCALE FINALE EN ATTENTE**

Suivi : issue #27.

## Question de porte

> MINOS sait-il produire une estimation déterministe, bornée et explicable des éléments et tests potentiellement impactés par une modification, tout en exposant les limites de couverture du graphe observé ?

## Verdict préparé

> **OUI, comme estimation potentielle fondée sur le graphe observé — jamais comme preuve d’exhaustivité runtime.**

## Acquis

M8 introduit une analyse de propagation fournisseur-indépendante sur `CodeKnowledgeSnapshot` :

- impact direct et indirect ;
- traversée inverse des relations de dépendance pertinentes ;
- profondeur et volume de résultats bornés ;
- protection contre les cycles ;
- chemin explicatif complet ;
- choix déterministe du meilleur chemin ;
- confiance conservatrice par minimum des confiances d’arêtes ;
- tests potentiellement impactés via les relations `RELATED_TEST` de M5 ;
- conservation d’un chemin `RELATED_TEST` spécifique même lorsqu’un meilleur chemin général est factuel ;
- limites dynamiques explicitement retournées ;
- façade locale `ProjectImpactQuery` / `LocalProjectImpactQuery` ;
- replay sur l’index TypeScript multi-module versionné.

## Frontière de vérité

M8 ne classe pas un impact comme certain.

Le résultat signifie :

> un chemin de dépendance admissible relie le symbole modifié au symbole retourné dans le snapshot analysé.

L’absence de chemin ne signifie pas :

> le symbole ne peut pas être impacté.

Les relations non résolues, les comportements dynamiques, la réflexion et la configuration runtime restent hors preuve.

## Confiance

Pour un chemin :

```text
confidence = minimum des confiances des relations du chemin
```

Un fait sans score fournisseur explicite contribue `1.0`.

Cette règle est monotone et conservatrice : ajouter une étape ne peut pas augmenter la confiance.

## Tests potentiels

Les tests M5 sont traités comme des impacts potentiels distinctement expliqués.

Les relations `RELATED_TEST` peuvent être :

- dérivées à partir d’un appel/référence directe ;
- heuristiques à partir d’une convention de nommage.

M8 conserve leur nature et leur confiance au lieu de les transformer en faits.

## Limites structurelles

Le rapport rend visibles :

- dispatch dynamique non prouvé ;
- réflexion non prouvée ;
- configuration runtime non prouvée ;
- relations non résolues ignorées ;
- entités externes non traversées ;
- profondeur atteinte ;
- limite de résultats atteinte.

## Décision d’architecture

L’analyse reste une **vue dérivée à la demande** du snapshot actif. M8 ne persiste pas de nouvelles relations `IMPACT_PATH` dans le store.

Raisons :

- le chemin dépend des bornes de la requête ;
- le meilleur chemin peut dépendre de la confiance et de la profondeur demandées ;
- le snapshot de relations reste la source de vérité ;
- éviter de dupliquer des dérivations qui peuvent être recalculées déterministiquement.

`RelationshipKind.IMPACT_PATH` reste disponible dans le modèle de domaine mais n’est pas utilisé comme fait persistant par M8.

## Validation attendue

La porte locale finale doit confirmer :

```powershell
.\mvnw.cmd clean verify
```

avec :

- toute la suite historique verte ;
- tests M8 unitaires verts ;
- façade locale verte ;
- replay réel `typescript-modules` vert.

Le head exact validé devra être enregistré dans la PR et l’issue avant passage Ready.

## Après fusion

Une fois la porte acquise et la PR M8 fusionnée :

- issue #27 → `completed` ;
- M8 → terminé, validé et livré ;
- M9 — CLI stabilisée → prochain jalon.
