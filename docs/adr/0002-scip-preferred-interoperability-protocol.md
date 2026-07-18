# ADR-0002 — Utiliser SCIP comme protocole d'interopérabilité sémantique privilégié

- Statut : **Proposée — à valider pendant C0**
- Date : 19 juillet 2026

## Contexte

MINOS a besoin d'informations précises au niveau des symboles : définitions, références, implémentations et autres relations sémantiques.

Réimplémenter pour chaque langage un frontend complet, un résolveur de symboles et toute la logique de navigation sémantique consommerait une part très importante du projet avant même de produire la valeur propre à MINOS.

SCIP fournit un protocole agnostique du langage permettant d'échanger de la Code Intelligence sémantique produite par des indexeurs spécialisés.

Cependant, certains langages ou certaines analyses peuvent être mieux couverts par :

- des indexeurs Glean natifs ;
- des API de compilateur ;
- LSIF ;
- des serveurs de langage ;
- des analyseurs AST ;
- des moteurs spécialisés futurs.

## Décision proposée

SCIP doit être le **protocole d'interopérabilité sémantique privilégié** lorsqu'un indexeur suffisamment fiable et maintenu existe.

SCIP ne doit pas être obligatoire.

Architecture candidate :

```text
Indexeur SCIP spécifique au langage
          │
          ▼
      Index SCIP
          │
          ▼
Adaptateur d'ingestion SCIP MINOS
          │
          ▼
   Modèle normalisé MINOS
```

Les services principaux de MINOS ne doivent pas exposer directement les types protobuf SCIP.

Le registre des indexeurs doit permettre à des fournisseurs non-SCIP d'offrir leurs capacités via le même modèle MINOS.

## Pourquoi SCIP est envisagé

- évite de reconstruire un parser et un résolveur sémantique complets pour chaque langage ;
- fournit un format d'échange commun entre plusieurs écosystèmes ;
- permet de représenter des identités de symboles et occurrences sémantiques ;
- peut servir de pont vers Glean ;
- conserve la possibilité de changer le backend en aval ;
- correspond à l'objectif multi-langages de MINOS.

## Avantages

- accélération du support multi-langages ;
- réduction du code d'analyse spécifique à maintenir ;
- adoption plus simple de nouveaux indexeurs ;
- séparation claire entre indexation et intelligence spécifique à MINOS.

## Inconvénients

- qualité dépendante de chaque indexeur SCIP ;
- couverture de capacités différente selon les langages ;
- certaines relations devront être dérivées après ingestion ;
- MINOS devra gérer l'installation, l'exécution et les erreurs des outils externes.

## Validation requise

Avant acceptation définitive, il faudra mesurer sur des dépôts réels :

- taux de réussite de l'indexation ;
- précision des symboles ;
- précision des références ;
- résolution des implémentations ;
- comportement multi-module ;
- taille des index ;
- durée d'indexation ;
- fonctionnement hors ligne ;
- comportement lorsque les dépendances sont indisponibles.

Si un indexeur SCIP n'atteint pas la qualité attendue pour un langage, MINOS doit pouvoir sélectionner un autre fournisseur sans modifier son domaine.

## Condition d'acceptation

Cette ADR ne pourra passer au statut **Acceptée** qu'après :

1. validation du rôle de SCIP dans le cahier des charges ;
2. comparaison avec les alternatives pertinentes ;
3. définition de critères de qualité des fournisseurs ;
4. validation expérimentale pendant M0.