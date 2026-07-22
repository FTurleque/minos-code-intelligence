# Métriques et seuils de validation — MINOS

- Statut : **Référence C0 pour M0**
- Date : **22 juillet 2026**

## 1. Objectif

Ce document définit les métriques permettant d'évaluer objectivement les choix techniques de MINOS.

Les seuils sont séparés en deux catégories :

- **porte bloquante** : le résultat doit être atteint pour valider la capacité ;
- **objectif initial** : cible à mesurer pendant M0, révisable uniquement avec justification documentée.

Une technologie ne doit pas être retenue parce qu'elle « semble fonctionner ».

---

# 2. Règles générales

## 2.1 Vérité terrain

Les mesures de précision doivent être réalisées sur des fixtures dont les résultats attendus sont explicitement décrits.

Chaque fixture doit fournir au minimum :

```text
expected_symbols
expected_relationships
expected_usages
expected_implementations
expected_unresolved
```

## 2.2 Dépôts réels

Les dépôts réels servent à mesurer :

- robustesse ;
- performances ;
- comportement sur build réel ;
- dépendances externes ;
- cas framework ;
- complexité opérationnelle.

Ils ne remplacent pas les fixtures pour la mesure de précision.

## 2.3 Répétabilité

Chaque benchmark doit documenter :

```text
machine
OS
CPU
RAM
runtime
versions des outils
commit du projet
configuration
commande exécutée
```

Les mesures de latence doivent être répétées suffisamment pour calculer au minimum :

```text
p50
p95
max
```

---

# 3. Précision des symboles

## Porte bloquante — fixtures contrôlées

- **100 %** des déclarations attendues du périmètre supporté sont détectées ;
- **100 %** des méthodes surchargées attendues sont distinguables ;
- **0 doublon** normalisé pour une même déclaration ;
- **0 symbole inventé** présenté comme déclaration résolue.

Le périmètre supporté doit être explicitement annoncé par le fournisseur.

---

# 4. Précision des références

Les métriques sont calculées uniquement sur les références **statiquement résolvables** de la fixture.

## Objectifs initiaux

```text
precision >= 99 %
recall    >= 98 %
```

Une référence non résolue est préférable à une référence fausse.

## Porte bloquante

Toute référence connue comme non résolue doit être représentable comme telle ; elle ne doit pas être transformée silencieusement en relation résolue.

---

# 5. Implémentations et héritage

Pour les fixtures contrôlées :

```text
precision >= 99 %
recall    >= 98 %
```

Les cas doivent couvrir :

- interface → implémentation ;
- classe → sous-classe ;
- héritage indirect ;
- symboles externes ;
- plusieurs implémentations.

---

# 6. Graphe d'appels

Le graphe d'appels n'est pas considéré automatiquement comme capacité obligatoire d'un fournisseur SCIP.

Pour un fournisseur déclarant :

```text
CALL_RELATIONSHIPS = FULL
```

les objectifs initiaux sont :

```text
precision >= 98 %
recall    >= 95 %
```

Si ces seuils ne sont pas atteints, la capacité doit être déclarée `PARTIAL`, `EXPERIMENTAL` ou `UNSUPPORTED`.

Aucun résultat partiel ne doit être présenté comme complet.

---

# 7. Exactitude des requêtes MINOS

Sur un graphe de fixture connu, les résultats des opérations obligatoires doivent correspondre exactement aux ensembles attendus.

## Porte bloquante MVP

```text
find_symbol
find_usages
find_implementations
find_dependencies
find_dependents
```

Pour une fixture déterministe :

```text
expected == actual
```

Les différences dues à une capacité fournisseur absente doivent produire une information de capacité ou de résolution, pas une réponse trompeuse.

---

# 8. Explicabilité

## Porte bloquante

**100 %** des relations dérivées ou heuristiques doivent exposer :

- origine ;
- statut de résolution ;
- niveau de confiance ;
- preuves ;
- chemin de relations lorsque pertinent.

Une heuristique ne peut jamais être exposée comme fait déterministe.

---

# 9. Isolation architecturale

## Porte bloquante

Les contrats publics du domaine MINOS ne doivent contenir :

```text
0 type SCIP
0 type Glean
0 prédicat Angle
0 type Thrift Glean
0 type propre à un indexeur
```

Les mêmes services métier doivent fonctionner avec :

- `InMemoryCodeKnowledgeStore` ;
- backend léger de test ;
- adaptateur Glean expérimental.

---

# 10. Validation multi-langages

## Porte bloquante

Le passage de Java à TypeScript ne doit nécessiter aucune modification des concepts métier fondamentaux suivants :

```text
Project
Module
SourceFile
Symbol
SymbolLocation
Relationship
Evidence
IndexSnapshot
```

Les différences doivent être absorbées par :

- fournisseur ;
- configuration ;
- mapping ;
- métadonnées extensibles ;
- profil de capacités.

Si le domaine doit être modifié pour un concept légitime générique manquant, la modification doit être analysée comme évolution du modèle et non comme branchement spécifique au langage.

---

# 11. Latence des requêtes

Mesures sur index déjà construit, cache chaud, machine de référence documentée.

## Objectifs initiaux

```text
find_symbol              p95 <= 100 ms
find_usages              p95 <= 250 ms
find_dependencies depth1 p95 <= 250 ms
find_dependents   depth1 p95 <= 250 ms
traversée depth3         p95 <= 1000 ms
```

Ces seuils concernent le service MINOS complet, sérialisation du résultat comprise, hors démarrage du processus CLI.

---

# 12. Indexation

M0 doit mesurer :

```text
wall_clock_duration
cpu_time
peak_memory
index_size
files_per_second
loc_per_second
facts_or_occurrences
```

Aucun seuil absolu n'est fixé avant la première campagne réelle.

L'objectif de M0 est de créer la baseline permettant de fixer les seuils M1/M7.

---

# 13. Empreinte mémoire et disque

M0 doit comparer les backends sur le même dataset.

Mesures :

```text
peak_memory_indexing
peak_memory_query
idle_memory
index_disk_size
working_files_size
```

## Règle de décision Glean

Une consommation significativement supérieure à la baseline légère doit être justifiée par une capacité ou une performance réellement utile.

Une différence de coût seule ne rejette pas Glean ; une différence de coût **sans bénéfice mesurable** le rejette du chemin par défaut.

---

# 14. Démarrage et exploitation

Mesures :

```text
cold_start_time
warm_start_time
number_of_processes
installation_steps
runtime_dependencies
recovery_steps
```

## Porte bloquante pour le backend par défaut

Le backend par défaut de MINOS ne doit pas imposer à un utilisateur Windows une installation manuelle d'une toolchain Linux complexe.

Si Glean nécessite encore cette contrainte à l'issue de M0, il reste un backend avancé optionnel.

---

# 15. Reconstruction et résilience

MINOS doit pouvoir reconstruire les données dérivées depuis les sources et/ou index reproductibles.

M0 doit tester :

- suppression du store ;
- reconstruction ;
- index partiellement corrompu lorsque testable ;
- échec d'un fournisseur ;
- dépendance manquante ;
- interruption d'indexation.

## Porte bloquante

Un échec d'indexation ne doit jamais remplacer silencieusement un index valide par un état incomplet considéré comme sain.

---

# 16. Déterminisme

À données, versions et configuration identiques :

- les identités de symboles doivent être stables ;
- les ensembles de résultats doivent être reproductibles ;
- l'ordre de sortie doit être déterministe lorsqu'il est exposé ;
- les scores heuristiques doivent être reproductibles.

---

# 17. Efficacité pour les agents IA

MINOS doit mesurer son bénéfice par rapport à une exploration naïve du code.

## 17.1 Code Exploration Reduction

Définition candidate :

```text
1 - (nombre de lignes effectivement retournées par MINOS
     / nombre de lignes qui auraient dû être explorées sans MINOS)
```

### Objectif initial

```text
médiane >= 70 %
```

sur un benchmark de tâches représentatives.

## 17.2 Estimated Tokens Avoided

Comparer :

```text
tokens_baseline
-
tokens_minos
```

### Objectif initial

```text
réduction médiane >= 60 %
```

pour les tâches où MINOS dispose de l'index nécessaire.

## 17.3 Average Context Size

Mesurer :

```text
nombre de fichiers
nombre de plages de code
nombre de lignes
tokens estimés
```

par requête ou tâche.

## Porte bloquante

La réduction de contexte ne doit pas supprimer une information indispensable connue de la vérité terrain.

La précision prime sur l'économie de tokens.

---

# 18. Recherche structurée

Lorsque la recherche générique sera évaluée :

- precision@k ;
- recall@k ;
- MRR lorsque pertinent ;
- latence ;
- nombre de résultats explorés.

Le benchmark doit distinguer :

- nom exact ;
- nom partiel ;
- nom qualifié ;
- symbole ;
- chemin ;
- type de symbole.

---

# 19. Critères spécifiques à Glean

Pour passer de « backend avancé candidat » à « backend recommandé », Glean doit satisfaire :

## Fonctionnel

- mêmes résultats MINOS obligatoires que la baseline ;
- valeur ajoutée démontrée pour des requêtes complexes ;
- aucun concept Glean dans les contrats publics.

## Opérationnel

- installation automatisable ;
- stratégie Windows crédible ;
- reconstruction maîtrisée ;
- cycle de vie du processus maîtrisé ;
- schémas versionnables ;
- protocole client maintenable.

## Performance

- latence compatible avec les objectifs MINOS ;
- coût mémoire/disque documenté ;
- avantage ou capacité justifiant ce coût.

---

# 20. Critères spécifiques aux indexeurs

Chaque `IndexerProvider` doit produire un `ProviderQualityProfile` contenant au minimum :

```text
providerId
providerVersion
language
buildSystems
capabilities
precisionMetrics
recallMetrics
indexingMetrics
knownLimitations
supportedEnvironment
lastValidatedAt
fixtureVersion
```

Un changement majeur de version d'indexeur doit pouvoir déclencher une requalification.

---

# 21. Niveaux de verdict M0

```text
ADOPTER
ADOPTER_AVEC_CONTRAINTES
REVOIR
REMPLACER
```

Toute décision M0 doit citer :

- mesures obtenues ;
- fixtures utilisées ;
- dépôts réels utilisés ;
- environnement ;
- écarts aux seuils ;
- limites connues ;
- impact sur la roadmap.
