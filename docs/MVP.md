# Définition du MVP — MINOS

- Statut : **Validé pendant C0**
- Date de validation : **22 juillet 2026**

Le MVP définit le premier produit MINOS réellement utile. Il ne doit pas absorber les capacités prévues dans les jalons d'analyse avancée.

---

# 1. Objectif du MVP

Le MVP doit démontrer qu'un dépôt logiciel local peut être :

1. découvert ;
2. indexé sémantiquement ;
3. normalisé dans le domaine MINOS ;
4. persisté derrière un contrat MINOS ;
5. interrogé rapidement ;
6. utilisé sans LLM, cloud, Glean ou NEXUS obligatoire.

Le MVP est réussi lorsqu'un développeur ou un agent obtient des réponses précises sur les symboles et leurs relations sans relire l'intégralité du dépôt.

---

# 2. Écosystèmes de validation

## Principal

```text
Java
```

Dépôt réel de référence :

```text
FTurleque/ariane-chatbot
```

## Second écosystème

```text
TypeScript
```

Objectif : démontrer que le cœur MINOS n'est pas JVM-centric.

## Repli expérimental

```text
Python
```

Python ne remplace TypeScript que si un blocage de l'indexeur TypeScript empêche une validation représentative pendant M0.

---

# 3. Périmètre fonctionnel obligatoire

## 3.1 Registre de projets

MINOS doit pouvoir :

```text
project add
project list
project inspect
```

Informations minimales :

- identifiant ;
- nom ;
- chemin local ;
- langages détectés ;
- builds détectés ;
- état de l'index ;
- snapshot courant ;
- date de dernière indexation réussie.

---

## 3.2 Découverte du dépôt

Le MVP doit détecter :

- modules ;
- sources ;
- tests ;
- fichiers de build ;
- ressources principales ;
- langages ;
- systèmes de build.

Il doit respecter :

```text
.gitignore
.minosignore
```

selon la stratégie définie par MINOS.

---

## 3.3 Registre des fournisseurs

Le MVP doit disposer des concepts validés :

```text
IndexerProvider
IndexerRegistry
IndexerCapabilities
CapabilitySupport
ProviderApplicability
ProviderSelection
ProviderQualityProfile
AnalysisPlan
```

La sélection ne doit pas être codée en dur par langage.

---

## 3.4 Indexation

Le MVP doit :

- exécuter ou orchestrer un fournisseur ;
- utiliser SCIP comme protocole privilégié lorsque pertinent ;
- accepter un fournisseur non-SCIP ;
- conserver le profil de capacités ;
- ingérer les résultats dans le modèle MINOS ;
- produire un `IndexSnapshot` ;
- ne jamais remplacer silencieusement un snapshot sain par un échec partiel.

Glean n'est pas une dépendance obligatoire.

---

# 4. Modèle de domaine MVP

Le périmètre comprend :

```text
Workspace
Project
Module
SourceFile
Symbol
SymbolOccurrence
SymbolLocation
CodeEntityRef
UnresolvedSymbolRef
Relationship
Origin
Evidence
IndexSnapshot
```

Le modèle doit rester indépendant des types SCIP/Glean.

---

# 5. Symboles obligatoires

Le modèle doit être capable de représenter au minimum :

```text
CLASS
INTERFACE
RECORD
STRUCT
ENUM
ANNOTATION
TRAIT
METHOD
CONSTRUCTOR
FUNCTION
FIELD
PROPERTY
VARIABLE
TYPE_ALIAS
PACKAGE
NAMESPACE
OTHER
```

Un fournisseur peut ne pas produire toutes ces catégories pour tous les langages.

---

# 6. Occurrences obligatoires

Le MVP doit distinguer le symbole de ses occurrences.

Rôles communs :

```text
DECLARATION
DEFINITION
REFERENCE
IMPORT
TYPE_USAGE
CALL
READ
WRITE
INSTANTIATION
```

Cette distinction est nécessaire pour les usages localisés et l'explicabilité.

---

# 7. Relations du MVP

## 7.1 Relations factuelles

Le domaine doit savoir représenter :

```text
DECLARES
CONTAINS
IMPORTS
REFERENCES
EXTENDS
IMPLEMENTS
CALLS
RETURNS
ACCEPTS
READS
WRITES
INSTANTIATES
```

Le MVP ne garantit pas que tous les fournisseurs remplissent toutes les relations.

## 7.2 Relation dérivée obligatoire

```text
DEPENDS_ON
```

Elle doit conserver les faits ayant permis sa dérivation.

## 7.3 Relations différées

```text
RELATED_TEST
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
```

Elles appartiennent aux jalons postérieurs.

---

# 8. Requêtes obligatoires du MVP

```text
find_symbol
find_usages
find_implementations
find_dependencies
find_dependents
```

Ces cinq opérations constituent le contrat fonctionnel minimal du MVP.

## Requêtes opportunistes

```text
find_callers
find_callees
```

Elles peuvent être livrées dès le MVP si le fournisseur qualifié atteint les seuils `CALL_RELATIONSHIPS`, mais elles ne bloquent pas la sortie du MVP.

---

# 9. Recherche du MVP

Le MVP doit proposer une recherche structurée minimale sur les symboles :

- nom exact ;
- nom partiel ;
- nom qualifié ;
- type de symbole ;
- module ;
- projet.

Ne sont pas obligatoires :

- embeddings ;
- recherche vectorielle ;
- recherche sémantique par LLM.

---

# 10. Stockage

Le domaine accède au stockage uniquement par :

```text
CodeKnowledgeStore
```

Le MVP doit disposer :

1. d'une implémentation mémoire pour les tests ;
2. d'au moins une stratégie locale persistante retenue à l'issue de M0.

Glean peut être cette stratégie, une stratégie avancée complémentaire ou être écarté du chemin par défaut selon M0.

---

# 11. CLI minimale du MVP

Une CLI simple doit permettre de tester et utiliser le cœur.

Commandes minimales :

```text
minos project add
minos project list
minos project inspect
minos index
minos search
minos find-symbol
minos find-usages
minos find-implementations
minos dependencies
minos dependents
minos index-status
```

La CLI stabilisée et enrichie reste prévue dans un jalon ultérieur.

---

# 12. Sortie compacte

Toute requête doit supporter un format structuré consommable par machine.

Les résultats privilégient :

```text
symbol
signature
kind
qualifiedName
location
occurrences pertinentes
relationships
resolutionStatus
origin
evidence
relevantSourceRange
```

Le fichier complet n'est jamais retourné par défaut.

Formats minimum :

```text
text
json
```

---

# 13. Explicabilité

Toute dérivation doit exposer :

- origine ;
- statut de résolution ;
- confiance ;
- preuves.

Les statuts restent :

```text
RESOLVED
PARTIALLY_RESOLVED
UNRESOLVED
HEURISTIC
```

---

# 14. Tests obligatoires

Le MVP doit comporter :

- tests unitaires ;
- tests de normalisation ;
- tests d'intégration fournisseur ;
- tests end-to-end ;
- fixtures Java ;
- fixtures TypeScript ;
- vérité terrain `expected_*` ;
- cas de dépendances manquantes ;
- cas de références non résolues ;
- cas multi-module ;
- surcharges ;
- héritage et implémentations.

---

# 15. Critères mesurables

Les seuils sont définis dans :

```text
docs/METRIQUES_VALIDATION.md
```

Portes principales :

- 100 % des déclarations attendues du périmètre de fixture ;
- 0 doublon normalisé ;
- références internes statiquement résolvables : précision cible ≥ 99 %, rappel cible ≥ 98 % ;
- 100 % des heuristiques/dérivations explicables ;
- aucun type fournisseur dans le domaine public ;
- mêmes concepts métier pour Java et TypeScript ;
- latences conformes aux objectifs documentés.

---

# 16. Efficacité IA

Le MVP doit instrumenter :

```text
Code Exploration Reduction
Estimated Tokens Avoided
Average Context Size
```

Il n'est pas nécessaire d'intégrer NEXUS pour mesurer ces indicateurs.

---

# 17. Hors périmètre strict du MVP

Les éléments suivants sont volontairement différés :

## M5 — Tests liés avancés

```text
get_related_tests
RELATED_TEST
```

## M6 — Intelligence d'architecture

```text
get_architecture_overview
ARCHITECTURAL_ROLE
CENTRALITY
```

## M7 — Indexation incrémentale complète

Le MVP prépare les hashes et snapshots mais n'implémente pas encore toute la stratégie incrémentale.

## M8 — Analyse d'impact

```text
analyze_impact
IMPACT_PATH
```

## M9 — CLI stabilisée

La CLI MVP reste fonctionnelle mais minimaliste.

## M10 — MCP de production

MCP n'est pas requis pour déclarer le cœur MVP valide.

## M11 — API réseau

Aucune API REST de production n'est requise.

## M12 — Multi-dépôts / Git Intelligence

Pas de résolution cross-repository complète ni d'analyse historique Git dans le MVP.

## M13 — NEXUS

MINOS reste autonome jusqu'à validation de son cœur.

## Autres exclusions

- cloud obligatoire ;
- LLM obligatoire ;
- embeddings obligatoires ;
- base vectorielle obligatoire ;
- plugins IDE ;
- ingestion distante GitHub/GitLab ;
- analyse runtime complète ;
- résolution parfaite du dispatch dynamique ;
- support exhaustif de tous les langages.

---

# 18. Critères de sortie du MVP

MINOS est considéré comme MVP uniquement si :

1. un dépôt Java réel est indexé de bout en bout ;
2. TypeScript valide le même domaine et les mêmes services ;
3. `find_symbol` fonctionne via les contrats MINOS ;
4. `find_usages` fonctionne via les contrats MINOS ;
5. `find_implementations` fonctionne lorsque la capacité fournisseur est qualifiée ;
6. dépendances et dépendants sont interrogeables ou dérivables avec preuves ;
7. la recherche structurée minimale fonctionne ;
8. une persistance locale est disponible derrière `CodeKnowledgeStore` ;
9. une sortie JSON compacte est disponible ;
10. les fixtures valident symboles, occurrences et relations ;
11. les benchmarks sont documentés ;
12. les métriques IA sont mesurables ;
13. aucune dépendance obligatoire à Glean, un LLM, un cloud, JARVIS ou NEXUS n'existe.

---

# 19. Relation M0 / MVP

M0 ne développe pas ce MVP complet.

M0 doit seulement réduire suffisamment les risques pour choisir :

- fournisseurs ;
- backend ;
- intégration SCIP ;
- stratégie Glean ;
- stack ;
- architecture de persistance.

Le développement du MVP commence après la décision M0.
