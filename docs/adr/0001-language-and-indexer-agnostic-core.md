# ADR-0001 — Conserver un cœur MINOS agnostique du langage et de l'indexeur

- Statut : **Proposée — à valider pendant C0**
- Date : 19 juillet 2026

## Contexte

MINOS doit devenir un moteur de Code Intelligence capable de comprendre des dépôts utilisant plusieurs langages et différents systèmes de build.

Les premiers exemples ont souvent cité Java, TypeScript et Python, mais ces langages ne doivent jamais devenir des frontières architecturales.

De la même manière, aucun parser, compilateur, indexeur SCIP, serveur de langage ou moteur d'analyse unique ne peut être supposé couvrir correctement tous les langages et toutes les capacités attendues.

## Décision proposée

Le domaine MINOS et son modèle de requêtes doivent être :

- **agnostiques du langage** ;
- **agnostiques de l'indexeur**.

L'indexation spécifique à un langage doit être fournie par des fournisseurs enregistrés.

Les fournisseurs doivent déclarer leurs capacités plutôt que d'être sélectionnés uniquement à partir du nom du langage.

Exemples de capacités :

```text
DEFINITIONS
REFERENCES
IMPLEMENTATIONS
TYPE_RELATIONSHIPS
CALL_RELATIONSHIPS
CROSS_FILE
CROSS_MODULE
CROSS_REPOSITORY
CONTROL_FLOW
DATA_FLOW
```

Le cœur ne doit pas contenir de logique figée telle que :

```text
si Java -> pipeline Java
si Python -> pipeline Python
si TypeScript -> pipeline TypeScript
```

Un `IndexerRegistry` et un mécanisme de négociation des capacités doivent permettre de sélectionner les fournisseurs adaptés au projet détecté et à l'analyse demandée.

Les modèles propres aux fournisseurs doivent être normalisés avant d'entrer dans le domaine MINOS.

## Avantages

- ajout de nouveaux langages sans réécriture des services principaux ;
- combinaison possible de plusieurs moteurs pour un même langage ;
- choix du meilleur fournisseur selon la capacité demandée ;
- coexistence future d'indexeurs sémantiques, CPG, analyseurs de flux ou outils de sécurité ;
- consommateurs MCP, API et NEXUS indépendants des technologies d'indexation.

## Inconvénients

- nécessité de concevoir soigneusement un modèle normalisé ;
- complexité supplémentaire de négociation des capacités ;
- risque de perdre certaines richesses propres à un fournisseur ;
- besoin de tests d'intégration entre fournisseurs.

## Alternatives étudiées

### Un framework de parsing unique pour tous les langages

Non retenu comme direction principale, car la qualité du parsing, de la résolution sémantique et du support écosystème varie fortement selon les langages.

### SCIP comme modèle de domaine interne obligatoire

Non retenu comme principe, car SCIP est avant tout un format d'interopérabilité et ne représente pas nécessairement toutes les connaissances futures de MINOS.

### Les schémas Glean comme modèle de domaine MINOS

Non retenu comme principe, car cela couplerait directement le domaine et les contrats publics à un backend de stockage et de requêtes particulier.

## Validation attendue pendant C0

Cette ADR ne pourra passer au statut **Acceptée** qu'après validation :

- du besoin multi-langages ;
- du modèle de capacités ;
- du rôle de SCIP ;
- du rôle de Glean ;
- du niveau de normalisation attendu ;
- des compromis induits par cette abstraction.