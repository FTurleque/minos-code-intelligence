# ADR-0001 — Conserver un cœur MINOS agnostique du langage et de l'indexeur

- Statut : **Acceptée**
- Date de décision : **19 juillet 2026**

## Contexte

MINOS doit devenir un moteur de Code Intelligence capable de comprendre des dépôts utilisant plusieurs langages et différents systèmes de build.

Les premiers exemples ont souvent cité Java, TypeScript et Python, mais ces langages ne doivent jamais devenir des frontières architecturales.

De la même manière, aucun parser, compilateur, indexeur SCIP, serveur de langage ou moteur d'analyse unique ne peut être supposé couvrir correctement tous les langages et toutes les capacités attendues.

Le cahier des charges validé établit explicitement deux exigences structurantes :

- MINOS est **agnostique du langage** ;
- MINOS est **agnostique de l'indexeur**.

Cette décision ne préjuge pas de l'adoption future de SCIP, Glean ou d'un autre fournisseur.

## Décision

Le domaine MINOS, son modèle de connaissance et son modèle de requêtes sont conçus pour être :

- **agnostiques du langage** ;
- **agnostiques de l'indexeur**.

L'indexation spécifique à un langage est fournie par des fournisseurs enregistrés.

Les fournisseurs déclarent leurs capacités plutôt que d'être sélectionnés uniquement à partir du nom du langage.

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

Un `IndexerRegistry` et un mécanisme de négociation des capacités permettront de sélectionner les fournisseurs adaptés au projet détecté et à l'analyse demandée.

Les modèles propres aux fournisseurs doivent être normalisés avant de franchir la frontière du domaine MINOS.

## Conséquences positives

- ajout de nouveaux langages sans réécriture des services principaux ;
- combinaison possible de plusieurs moteurs pour un même langage ;
- choix du meilleur fournisseur selon la capacité demandée ;
- coexistence future d'indexeurs sémantiques, CPG, analyseurs de flux ou outils de sécurité ;
- consommateurs CLI, MCP, API, NEXUS et agents indépendants des technologies d'indexation ;
- possibilité de remplacer SCIP ou Glean sans redéfinir le domaine MINOS.

## Conséquences négatives

- nécessité de concevoir soigneusement un modèle normalisé ;
- complexité supplémentaire de négociation des capacités ;
- risque de perdre certaines richesses propres à un fournisseur lors de la normalisation ;
- besoin de tests d'intégration entre fournisseurs ;
- nécessité de définir explicitement les capacités absentes ou partielles.

## Alternatives rejetées

### Un framework de parsing unique pour tous les langages

Rejeté comme principe architectural : la qualité du parsing, de la résolution sémantique et du support écosystème varie fortement selon les langages.

### SCIP comme modèle de domaine interne obligatoire

Rejeté : SCIP est envisagé comme protocole d'interopérabilité possible, pas comme définition du domaine MINOS.

### Les schémas Glean comme modèle de domaine MINOS

Rejeté : cela couplerait directement le domaine et les contrats publics à un backend particulier.

### Une liste fermée de langages supportés dans le cœur

Rejetée : les langages supportés sont une propriété des fournisseurs disponibles et de leurs capacités, pas une constante structurelle du domaine.

## Règles d'architecture résultantes

1. Aucun type propre à un fournisseur ne doit apparaître dans les contrats publics du domaine MINOS.
2. Les capacités doivent être représentées explicitement.
3. Un fournisseur peut fournir un sous-ensemble de capacités.
4. Plusieurs fournisseurs peuvent contribuer à un même projet ou langage.
5. L'absence d'une capacité doit être représentable sans inventer de résultat.
6. Le support d'un nouveau langage ne doit pas imposer une modification des services métier existants.

## Validation

Cette ADR est acceptée à la suite de la validation du cahier des charges MINOS, qui confirme explicitement les principes d'agnosticisme du langage et de l'indexeur.

Les expérimentations M0 devront vérifier que cette abstraction reste praticable avec au moins deux écosystèmes de langage et plusieurs types de fournisseurs.