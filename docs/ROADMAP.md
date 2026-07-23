# Feuille de route — MINOS

Statut : **C0 et M0 livrés — M1 en cours**

L'état opérationnel, la porte active et le reste à faire sont maintenus dans
[`STATUS.md`](STATUS.md). Cette feuille conserve la séquence des jalons et leurs
portes de décision.

La feuille de route est volontairement guidée par les preuves. Un jalon peut être modifié si une expérimentation invalide une hypothèse d'architecture.

---

## C0 — Cadrage fonctionnel et architectural

État : **TERMINÉ**

### Objectif

Définir précisément ce que MINOS doit être avant de développer ses fonctionnalités.

### Livrables

- cahier des charges ;
- vision et positionnement ;
- frontière MINOS / NEXUS ;
- cas d'usage prioritaires ;
- périmètre MVP ;
- modèle de domaine proposé ;
- modèle des symboles ;
- modèle des relations ;
- stratégie d'indexation ;
- stratégie SCIP ;
- stratégie Glean ;
- stratégie `CodeKnowledgeStore` ;
- critères de validation ;
- ADR structurantes ;
- plan des expérimentations M0.

### Porte de décision

> Savons-nous précisément ce que MINOS doit fournir, pourquoi, à qui, avec quelles limites et selon quels critères mesurables ?

Aucune implémentation fonctionnelle significative ne doit commencer avant cette validation.

---

## M0 — Faisabilité technique

État : **TERMINÉ ET LIVRÉ — verdict ADOPTER_AVEC_CONTRAINTES**

Acquis : qualification Java et TypeScript, baseline SCIP vers MINOS sur huit
index réels, benchmark reproductible du backend mémoire, Glean C1 et comparaison
E2. Le backend MINOS léger est retenu par défaut et Glean reste optionnel.
La décision est consolidée dans `m0/DECISION_M0.md`.

La PR #4 a été validée localement puis fusionnée dans `main` au commit
`6d8376bcfc16dd5ba1c6b691535aa3d8e57cc49a`. GitHub Actions reste en pause ;
l'anomalie historique est suivie dans #5 sans bloquer la validation locale.

### Objectif

Valider les choix structurants avec des expérimentations réelles et mesurables.

### Périmètre

- évaluation SCIP ;
- évaluation des indexeurs sélectionnés ;
- évaluation Glean ;
- test d'intégration locale ;
- validation du découplage `CodeKnowledgeStore` ;
- premier projet Java de référence ;
- second écosystème de langage ;
- mesures de précision ;
- mesures de performance ;
- rapport de décision.

### Porte de décision

> SCIP et Glean constituent-ils une fondation viable pour MINOS sans coupler irréversiblement son domaine à ces technologies ?

Décisions possibles :

```text
ADOPTER
ADOPTER_AVEC_CONTRAINTES
REVOIR
REMPLACER
```

---

## M1 — Découverte des projets et orchestration des indexeurs

État : **EN COURS — M1.1 et M1.2 fusionnés, M1.3 en validation**

### Objectif

Détecter un projet et sélectionner les fournisseurs d'indexation adaptés.

### Périmètre

- registre local des projets ;
- concept de workspace ;
- détection des langages ;
- détection des systèmes de build ;
- détection des racines de sources et de tests ;
- stratégie `.gitignore` ;
- stratégie `.minosignore` ;
- `IndexerRegistry` ;
- négociation des capacités ;
- cycle de vie de l'indexation ;
- état de l'index.

Progression :

```text
M1.1 découverte locale factuelle              FUSIONNÉ
M1.2 ignore policy + registre local            FUSIONNÉ
M1.3 IndexerRegistry + négociation             EN VALIDATION
M1.4 cycle de vie + état d'index              À FAIRE
```

---

## M2 — Intelligence des symboles

### Objectif

Exposer une recherche fiable des symboles indépendamment du fournisseur et du backend.

### Périmètre

- modèle normalisé des symboles ;
- identité stable ;
- fichiers, modules et emplacements ;
- types de symboles ;
- symboles externes ;
- symboles non résolus ;
- `find_symbol` ;
- `get_file_symbols` ;
- recherche lexicale ;
- recherche par nom qualifié.

### Critère de sortie

```text
minos find-symbol <projet> <symbole>
```

retourne un résultat MINOS normalisé et compact.

---

## M3 — Intelligence des relations

### Objectif

Exposer les relations entrantes et sortantes entre éléments du code.

### Périmètre

- références ;
- implémentations ;
- héritage ;
- appels lorsque disponibles ;
- dépendances dérivées ;
- provenance ;
- preuves ;
- niveau de confiance ;
- `find_usages` ;
- `find_implementations` ;
- `find_callers` ;
- `find_callees` ;
- `dependencies` ;
- `dependents`.

---

## M4 — Recherche et contexte compact

### Objectif

Rendre MINOS directement exploitable par des outils et agents avant même l'arrivée du MCP.

### Périmètre

- recherche structurée unifiée ;
- sortie JSON compacte ;
- limites de résultats ;
- limites de profondeur ;
- plages de code pertinentes ;
- récupération explicite du code source complet ;
- politiques d'efficacité en tokens ;
- benchmarks de latence.

Ce jalon définit le premier **cœur MINOS réellement utilisable**.

---

## M5 — Tests liés et dérivations explicables

### Objectif

Déduire des relations utiles que les indexeurs ne fournissent pas forcément directement.

### Périmètre

- détection des tests liés ;
- conventions de nommage ;
- références directes ;
- appels de méthodes ;
- proximité de package ou namespace ;
- score de confiance ;
- explication des raisons.

---

## M6 — Intelligence d'architecture

### Objectif

Produire une vue de haut niveau de la topologie d'un projet.

### Périmètre

- topologie des modules ;
- topologie des packages ou namespaces ;
- composants centraux ;
- concentration des dépendances ;
- technologies détectées ;
- `get_architecture_overview` ;
- `get_module_context`.

Les faits détectés et les inférences doivent être distingués.

---

## M7 — Indexation incrémentale

### Objectif

Éviter les réindexations complètes lorsque cela n'est pas nécessaire.

### Périmètre

- empreintes de fichiers ;
- empreintes du projet et du build ;
- fichiers ajoutés, modifiés et supprimés ;
- snapshots d'index ;
- règles d'invalidation ;
- capacités incrémentales propres aux fournisseurs ;
- repli vers une indexation complète.

---

## M8 — Analyse d'impact

### Objectif

Estimer la propagation potentielle d'une modification à partir des relations connues.

### Périmètre

- impact direct ;
- impact indirect ;
- chemin explicatif ;
- score de confiance ;
- contrôle de profondeur ;
- tests potentiellement impactés ;
- limites explicites liées au comportement dynamique.

---

## M9 — CLI stabilisée

### Objectif

Stabiliser l'interface en ligne de commande destinée aux développeurs et aux automatisations.

Commandes envisagées :

```text
minos project add
minos project list
minos index
minos search
minos find-symbol
minos find-usages
minos find-implementations
minos find-callers
minos find-callees
minos dependencies
minos dependents
minos related-tests
minos architecture
minos impact
minos inspect
```

---

## M10 — Serveur MCP

### Objectif

Exposer des outils spécialisés et compacts aux agents IA.

Outils envisagés :

```text
get_project_structure
search_code
find_symbol
find_usages
find_implementations
find_callers
find_callees
find_dependencies
find_dependents
get_related_tests
get_symbol_context
get_file_symbols
get_module_context
get_architecture_overview
analyze_impact
get_index_status
```

Le MCP reste une couche d'exposition. Aucune logique d'analyse métier ne doit résider dans les handlers MCP.

---

## M11 — API

### Objectif

Permettre à des systèmes externes de consommer MINOS sans dépendre de Glean ou des adaptateurs internes.

### Périmètre

- opérations sur les projets et index ;
- requêtes de symboles ;
- requêtes de relations ;
- architecture ;
- impact ;
- contrats DTO stables.

Le choix du framework serveur reste différé jusqu'à l'approche de ce jalon.

---

## M12 — Multi-dépôts et intelligence Git

### Objectif

Étendre MINOS des dépôts isolés vers des workspaces et une compréhension de l'historique.

Périmètre possible :

- résolution inter-dépôts ;
- relations cross-repository ;
- historique Git ;
- fréquence de modification des symboles ;
- changements récents ;
- zones de forte activité.

---

## M13 — Intégration NEXUS

### Objectif

Permettre à NEXUS de consommer la Code Intelligence de MINOS pour sélectionner un contexte adapté à une tâche.

### Frontière

- MINOS fournit des faits, relations, preuves et vues compactes du code ;
- NEXUS classe et sélectionne les informations à injecter dans le contexte IA.

MINOS doit rester pleinement utilisable sans NEXUS.

---

## Explorations futures

Non engagées dans la roadmap principale :

- Code Property Graph ;
- analyse de flux de données ;
- analyse de sécurité ;
- recherche sémantique ;
- embeddings ;
- plugins IDE ;
- indexation distante GitHub/GitLab ;
- indexation distribuée ;
- mode service hébergé.
