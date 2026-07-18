# Définition du MVP — MINOS

Statut : **Brouillon — à valider pendant C0**

Le MVP n'est pas encore engagé en développement. Ce document sert à définir ce que le premier produit utile devra démontrer.

---

## 1. Objectif du MVP

Le MVP de MINOS doit prouver qu'un dépôt logiciel local peut être indexé sémantiquement, normalisé et interrogé à travers des contrats MINOS stables, sans demander à un modèle IA de lire l'intégralité du dépôt.

Le MVP est réussi lorsque MINOS peut répondre de manière fiable à des questions sur les symboles et leurs relations tout en réutilisant, lorsque pertinent, une infrastructure d'indexation existante.

---

## 2. Cas d'usage principal

À partir d'un dépôt local, un développeur ou un agent doit pouvoir demander :

```text
Où se trouve ce symbole ?
Qui l'utilise ?
Quelles sont ses implémentations ?
De quoi dépend-il ?
Qu'est-ce qui dépend de lui ?
Qui l'appelle ?
```

MINOS retourne des résultats structurés et compacts contenant notamment :

- les emplacements ;
- les relations ;
- les preuves ;
- le niveau de résolution ;
- les plages de code pertinentes.

MINOS ne retourne pas les fichiers complets par défaut.

---

## 3. Langages de validation

L'architecture du MVP doit être agnostique du langage.

La validation devra utiliser :

1. un premier écosystème principal ;
2. au moins un second écosystème suffisamment différent pour démontrer que le cœur n'est pas spécifique au premier.

Java est le candidat naturel pour le premier écosystème compte tenu des projets disponibles, mais ce choix reste à confirmer pendant C0.

Le second langage sera choisi selon la qualité des indexeurs et la pertinence du test.

---

## 4. Capacités incluses envisagées

### 4.1 Enregistrement d'un projet

MINOS doit pouvoir :

- enregistrer un dépôt local ;
- identifier sa racine ;
- détecter ses langages ;
- détecter ses systèmes de build ;
- suivre l'état de l'index ;
- conserver la date de dernière indexation réussie.

### 4.2 Indexation

MINOS doit pouvoir :

- sélectionner un fournisseur adapté ;
- connaître les capacités de ce fournisseur ;
- exécuter ou orchestrer l'indexation ;
- privilégier SCIP lorsqu'un indexeur approprié existe ;
- accepter d'autres fournisseurs ;
- ingérer les faits produits ;
- interroger ces connaissances via une abstraction MINOS ;
- évaluer Glean comme backend réel privilégié.

### 4.3 Modèle minimal de symboles

Types envisagés :

```text
CLASS
INTERFACE
RECORD
ENUM
ANNOTATION
METHOD
CONSTRUCTOR
FIELD
FUNCTION
```

Le modèle doit rester extensible.

### 4.4 Relations initiales

Relations factuelles envisagées :

```text
DECLARES
CONTAINS
REFERENCES
EXTENDS
IMPLEMENTS
CALLS
```

Relation dérivée possible :

```text
DEPENDS_ON
```

Toute relation dérivée doit conserver ses preuves.

### 4.5 Requêtes obligatoires envisagées

```text
find_symbol
find_usages
find_implementations
find_dependencies
find_dependents
```

Requêtes complémentaires si les données des fournisseurs sont suffisamment fiables :

```text
find_callers
find_callees
```

---

## 5. Format de sortie

Chaque requête doit pouvoir retourner un résultat structuré adapté à une consommation machine.

Les réponses doivent privilégier :

```text
symbol
signature
kind
qualifiedName
location
relationship
relevantSourceRange
evidence
```

plutôt que le contenu complet d'un fichier source.

---

## 6. Hors périmètre du MVP

Le MVP ne doit pas inclure prématurément :

- analyse d'impact complète ;
- embeddings sémantiques obligatoires ;
- base vectorielle obligatoire ;
- analyse LLM obligatoire ;
- intégration NEXUS ;
- serveur MCP de production ;
- API REST de production ;
- service cloud ;
- ingestion distante GitHub/GitLab ;
- plugins IDE ;
- résolution parfaite du dispatch dynamique ;
- analyse complète du comportement runtime ;
- support exhaustif de tous les langages.

---

## 7. Critères techniques de validation

Les objectifs chiffrés devront être confirmés pendant C0 puis affinés après M0.

### 7.1 Précision des symboles

Sur des fixtures contrôlées :

- 100 % des symboles de premier niveau attendus détectés ;
- 100 % des symboles surchargés attendus identifiables sans ambiguïté ;
- aucun doublon normalisé pour une même déclaration.

### 7.2 Précision des références

Objectif initial proposé :

- au moins 99 % des références internes statiquement résolvables correctement reliées sur les fixtures contrôlées.

Cet objectif devra être challengé pendant C0.

### 7.3 Exactitude des requêtes

Sur des graphes de fixtures contrôlés :

- `find_usages` ;
- `find_dependencies` ;
- `find_dependents` ;

doivent retourner des résultats déterministes et vérifiés automatiquement.

### 7.4 Isolation du backend

Exigences :

- aucun type Glean dans le domaine public MINOS ;
- aucune requête Angle exposée aux consommateurs CLI/MCP/API ;
- possibilité de tester les services principaux avec une implémentation mémoire de `CodeKnowledgeStore`.

### 7.5 Local-first

Exigences :

- aucune dépendance cloud obligatoire ;
- aucune source envoyée vers un service externe par défaut.

### 7.6 Latence

Cibles initiales à confirmer :

```text
find_symbol p95 < 100 ms
find_usages p95 < 250 ms
requête de dépendance profondeur 1 p95 < 250 ms
```

Ces valeurs ne seront figées qu'après les premiers benchmarks.

### 7.7 Explicabilité

100 % des relations heuristiques ou dérivées doivent pouvoir exposer :

- leur origine ;
- leur niveau de confiance ;
- leurs preuves.

---

## 8. Critères de sortie du MVP

MINOS pourra être considéré comme MVP uniquement si :

1. un dépôt représentatif du premier écosystème est indexé de bout en bout ;
2. un second écosystème valide l'extensibilité ;
3. `find_symbol` et `find_usages` passent uniquement par des contrats MINOS ;
4. les dépendances et dépendants peuvent être interrogés ou dérivés avec preuves ;
5. Glean, s'il est retenu, reste derrière `CodeKnowledgeStore` ;
6. une sortie structurée compacte est disponible ;
7. des tests automatisés valident les symboles et relations ;
8. les résultats de benchmarks sont documentés ;
9. aucune dépendance obligatoire à un LLM, au cloud ou à NEXUS n'existe.

---

## 9. Condition préalable au développement du MVP

Le MVP ne doit pas être lancé en développement tant que la phase **C0 — Cadrage fonctionnel et architectural** n'a pas validé :

- le besoin ;
- les cas d'usage prioritaires ;
- les langages de validation ;
- les critères de réussite ;
- les ADR structurantes ;
- le rôle de SCIP ;
- le rôle de Glean ;
- le modèle de domaine minimal.