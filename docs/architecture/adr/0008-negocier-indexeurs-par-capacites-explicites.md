---
id: ADR-0008
title: "Négocier les indexeurs par capacités explicites"
domain: architecture
rules: false
---

# ADR-0008 — Négocier les indexeurs par capacités explicites

- Statut : **Accepté**
- Date : **22 juillet 2026**
- Validation : **23 juillet 2026**
- Jalon : M1

## Context

M0 a montré que deux indexeurs SCIP peuvent partager un protocole commun tout en ayant des comportements différents. `scip-java 0.13.1` est qualifié sur Maven multi-module et ne publie pas d'index final lorsque la compilation échoue. `scip-typescript 0.4.0` sait produire un index exploitable malgré certaines erreurs TypeScript, mais ses kinds, surcharges et relations structurelles ont des limites différentes.

Choisir un fournisseur uniquement à partir du langage ou de son nom masquerait ces différences et conduirait MINOS à inventer des garanties que les mesures ne démontrent pas.

## Decision

1. chaque indexeur est décrit par un `IndexerDescriptor` indépendant de son API native ;
2. le descriptor expose version, langages, systèmes de build qualifiés, capacités positives, niveau de qualification, priorité et limitations ;
3. une capacité signifie seulement « scénario observé/qualifié », jamais « résultat sémantiquement complet » ;
4. `IndexerRegistry` sélectionne un indexeur séparément pour chaque langage détecté ;
5. la sélection exige explicitement les capacités demandées par l'appelant ;
6. les indexeurs expérimentaux sont exclus par défaut ;
7. les rejets restent observables : build non qualifié, capacité absente, fournisseur expérimental ou priorité inférieure ;
8. l'ordre de sélection est déterministe : priorité décroissante puis identifiant ;
9. la négociation ne lance aucun processus fournisseur ; l'exécution et son cycle de vie relèvent de M1.4 ;
10. les connaissances propres à SCIP restent dans `adapter.scip` et sont adaptées vers les contrats `orchestration`.

## Do's and Don'ts

### Do

- Décrire chaque indexeur par un `IndexerDescriptor` indépendant de son API native.
- Sélectionner un indexeur séparément pour chaque langage détecté, en exigeant les capacités demandées par l'appelant.
- Garder les rejets observables : build non qualifié, capacité absente, fournisseur expérimental ou priorité inférieure.
- Ordonner la sélection de manière déterministe : priorité décroissante puis identifiant.

### Don't

- Ne pas lire une capacité comme un « résultat sémantiquement complet ».
- Ne pas sélectionner d'indexeur expérimental par défaut.
- Ne lancer aucun processus fournisseur pendant la négociation.
- Ne pas sortir les connaissances propres à SCIP de `adapter.scip`.

## Catalogue M1 issu des preuves M0

### scip-java 0.13.1

Capacités annoncées :

- symboles ;
- références ;
- relations d'implémentation ;
- Maven multi-module ;
- sources de test.

Contraintes conservées : Maven uniquement pour le périmètre qualifié M0, aucun index final sur échec de compilation, aucune relation `CALLS` explicite et kinds partiellement non spécifiés.

### scip-typescript 0.4.0

Capacités annoncées :

- symboles ;
- références ;
- relations structurelles ;
- multi-projet ;
- sources de test ;
- index partiel malgré certains échecs de build/type-check.

Contraintes conservées : surcharges parfois fusionnées sous une identité fournisseur, kinds souvent non spécifiés, relations structurelles incomplètes et aucune relation `CALLS` explicite.

Le descriptor TypeScript n'impose pas npm : M0 a démontré que les références multi-projets sont portées par `tsconfig`, npm servant à l'installation et à la vérité terrain.

## Consequences

- un projet multi-langages peut sélectionner plusieurs indexeurs sans créer de fournisseur global artificiel ;
- un appelant peut demander une capacité forte et obtenir explicitement « non couvert » plutôt qu'un faux succès ;
- un nouveau fournisseur peut être ajouté sans modifier le domaine de découverte ;
- les limites fournisseurs deviennent des données de sélection et de diagnostic ;
- M1.4 exécute uniquement un plan déjà négocié et observable.

## Compliance and Enforcement

- Règle A2 de `scripts/architecture/check-module-boundaries.py` (job `invariants`) — dépendances autorisées entre modules et frontière hexagonale ; `minos-engine` (`orchestration`) ne dépend pas de `minos-provider-scip`.
- Test `IndexerRegistryTest` (`minos-engine`) — enregistrement, sélection et rejets de la négociation.
- Revue : rejets observables, aucun type SCIP dans `orchestration`, aucun lancement de processus pendant la négociation.

## Validation M1.3

M1.3 a confirmé :

- unicité des indexeurs enregistrés ;
- sélection déterministe par priorité ;
- refus explicables pour build/capacités/qualification ;
- sélection réelle `scip-java` sur la fixture Maven ;
- sélection réelle `scip-typescript` sur les fixtures TypeScript ;
- conservation de l'asymétrie d'index partiel Java/TypeScript ;
- absence de types SCIP dans `orchestration`.

La validation locale du head M1.3 a été confirmée par le développeur avant fusion de la PR #9 dans `main` au commit `0125802b364f481e2242c7d2bbb008beb4c2d8d7`.
