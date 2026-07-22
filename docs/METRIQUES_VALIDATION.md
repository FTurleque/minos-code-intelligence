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

## 20.1 Premières mesures scip-java M0

La campagne A1 à A5 du 22 juillet 2026 fournit la première baseline réelle :

| Mesure | `java-simple` | `java-24-smoke` | `ariane-chatbot` | `java-multi-module` |
|---|---:|---:|---:|---:|
| Documents | 6 | 2 | 220 | 5 |
| Lignes | 59 | 16 | 12 070 | 39 |
| Faits de symboles catalogue | 32 | 10 | 4 587 | 21 |
| Occurrences | 128 | 22 | 25 956 | 94 |
| Occurrences résolues MINOS | 64 | 18 | 14 000 | 42 |
| Occurrences non résolues MINOS | 64 | 4 | 11 956 | 52 |
| Taux de non-résolution | 50 % | 18,18 % | 46,06 % | 55,32 % |
| Doublons de catalogue | 0 | 0 | 0 | 0 |

Pour `java-simple`, les 13 symboles obligatoires sont présents, mais le record
`User` est publié avec un kind non spécifié. Les cinq cibles d'usage attendues
sont retrouvées. L'implémentation d'interface est explicite ; les trois appels
attendus existent comme occurrences cibles mais pas comme relations `CALLS`.

Les taux bruts de non-résolution incluent les symboles JDK/JUnit et segments de
package que l'index ne catalogue pas. Ils ne constituent donc pas directement
une mesure de rappel sur les seules références workspace statiquement
résolvables. Le détail et les contraintes Windows/SCIP CLI sont consignés dans
`docs/m0/RAPPORT_SCIP_JAVA_A1_A2.md` et
`docs/m0/RAPPORT_SCIP_JAVA_A3_ARIANE.md`.

A4 confirme 10/10 symboles obligatoires, l'implémentation cross-module et les
deux cibles d'appels attendues. Son index de 10 987 octets est byte-identique
sur deux runs ; la durée mesurée d'un run est 9 723 ms.

A5 échoue comme prévu sur `MissingClient` après compilation du module sain :
durée 7 811 ms, code 1, aucun index final et deux shards intermédiaires pour
4 195 octets. Ces shards sont diagnostiquables mais ne satisfont pas la porte
de résilience comme index sain. Le détail A4/A5 est dans
`docs/m0/RAPPORT_SCIP_JAVA_A4_A5.md`.

## 20.2 Première mesure scip-typescript M0

D1 du 22 juillet 2026 qualifie `scip-typescript 0.4.0` sur la fixture
`typescript-simple` :

| Mesure | Résultat |
|---|---:|
| Documents | 6 |
| Lignes | 49 |
| Faits de symboles catalogue | 32 |
| Occurrences | 100 |
| Symboles normalisés MINOS | 24 |
| Occurrences résolues MINOS | 70 |
| Occurrences non résolues MINOS | 30 |
| Taux de non-résolution | 30 % |
| Doublons de catalogue | 0 |
| Symboles obligatoires présents | 12 / 12 |
| Kinds exacts | 0 / 12 |
| Cibles d'usage présentes | 9 / 9 |
| Implémentations attendues | 1 / 1 |
| Cibles d'appels observables | 3 / 3 |
| Relations `CALLS` explicites | 0 / 3 |

Le fournisseur omet le langage, le nom d'affichage, le kind et l'encodage de
position dans les structures concernées. MINOS récupère uniquement le langage
et le nom par un repli borné dans l'adaptateur ; il conserve le kind `OTHER` et
l'encodage `UNKNOWN`. Le détail est dans
`docs/m0/RAPPORT_SCIP_TYPESCRIPT_D1.md`.

## 20.3 Qualification avancée scip-typescript D2

D2 du 22 juillet 2026 mesure trois fixtures ciblées avec
`scip-typescript 0.4.0` :

| Mesure | `typescript-modules` | `typescript-inheritance` | `typescript-unresolved` |
|---|---:|---:|---:|
| Documents | 4 | 6 | 1 |
| Lignes | 38 | 40 | 13 |
| Faits de symboles fournisseur | 29 | 25 | 10 |
| Occurrences | 67 | 57 | 18 |
| Symboles normalisés MINOS | 19 | 18 | 9 |
| Occurrences résolues MINOS | 44 | 39 | 14 |
| Occurrences non résolues MINOS | 23 | 18 | 4 |
| Taux de non-résolution | 34,33 % | 31,58 % | 22,22 % |
| Relations fournisseur | 4 | 11 | 2 |
| Doublons de catalogue | 2 | 0 | 0 |
| Symboles obligatoires présents | 11 / 11 | 12 / 12 | 6 / 6 |
| Kinds exacts | 0 / 11 | 0 / 12 | 0 / 6 |

Les références cross-project attendues sont toutes observables. Les deux
surcharges déclarées de `GreetingService.greet` et leur implémentation sont
toutefois fusionnées sous un seul identifiant SCIP : trois définitions, deux
répétitions de catalogue et aucune identité de surcharge distincte. La porte
« symboles surchargés distingués » n'est donc pas satisfaite.

Les onze relations d'héritage et d'override émises ont une cible cataloguée,
mais `Named extends Identified` n'est pas une relation explicite et le bit SCIP
ne distingue pas `extends` de `implements`. Les quatre appels attendus sur les
fixtures saines restent des références résolues sans relation `CALLS`.

Sur 142 occurrences, aucune n'a un rôle multi-valué : seuls `DEFINITION` et
`REFERENCE` sont observés. Pour le module absent, trois occurrences opaques
`local 0` sont conservées comme non résolues ; l'appel `transform` n'est pas
émis. Le détail est dans `docs/m0/RAPPORT_SCIP_TYPESCRIPT_D2.md`.

## 20.4 Baseline du backend mémoire E1

E1 du 22 juillet 2026 mesure `InMemoryCodeKnowledgeStore` sur les huit index
réels Java et TypeScript. Deux campagnes indépendantes utilisent chacune 100
itérations d'échauffement puis 500 mesures pour trois requêtes par dataset.

| Mesure | Fixtures hors Ariane | Ariane |
|---|---:|---:|
| backend prêt | 134,954–182,820 ms | 431,731 / 444,206 ms |
| `find_symbol` pire p95 individuel | 0,030 ms | 1,443 ms |
| `find_usages` pire p95 individuel | 0,056 ms | 10,249 ms |
| heap retenue après ingestion | 2,21–2,29 MiB | 21,30 MiB |
| pic heap requêtes | 10,67–10,76 MiB | 53,75 MiB |
| disque propre au store | 0 | 0 |

Les objectifs initiaux de 100 ms pour `find_symbol` et 250 ms pour
`find_usages` sont atteints sur ce corpus. Les 48 couples
dataset/opération/requête conservent le même compteur et le même digest entre
les deux JVM.

La heap requête inclut les allocations du harness et ne constitue pas une
mesure RSS. Ariane ne contient que 25 956 occurrences ; ces chiffres ne fixent
donc pas encore un seuil de scalabilité. Le détail méthodologique et les deux
runs sont consignés dans `docs/m0/RAPPORT_BACKEND_MEMOIRE_E1.md`.

## 20.5 Qualification Glean C1 et comparaison E2

Glean 0.2.0.1 a été construit et exécuté sous Ubuntu 24.04 WSL2 sur le même
`index.scip` `java-simple`. L'ingestion native échoue parce que son indexeur
intégré ne décode que les anciennes plages SCIP. Une copie expérimentale a
converti 128 plages et 27 plages englobantes sans modifier l'index source.

| Mesure | Résultat Glean |
|---|---:|
| Ingestion de la copie compatible | 2 095 ms |
| Reconstruction indépendante | 2,01 s |
| Pic RSS ingestion | 204 340 KiB |
| Taille de la base | 436 312 octets |
| Symboles obligatoires présents | 13 / 13 |
| Kinds exacts | 9 / 13 |
| Cibles d'usage présentes | 5 / 5 |
| Implémentations attendues | 1 / 1 |
| Relations `CALLS` explicites | 0 / 3 |
| Latence `SymbolDisplayName` p50 / p95 | 62,409 / 69,364 ms |
| Latence `Reference` p50 / p95 | 60,284 / 72,890 ms |
| Durée processus CLI p95 | 807 / 802 ms |
| Pic RSS requête instrumentée | 155 376 KiB |
| Store Cabal local | 4 516 378 499 octets |

Les temps d'exécution Angle internes respectent les objectifs initiaux, mais
ne constituent pas encore des latences du service MINOS complet. La durée
processus inclut WSL et l'ouverture de base. Un seul processus Glean de cinq
threads a été observé par invocation.

Glean ne démontre sur ce dataset aucune capacité MVP obligatoire qui compense
sa toolchain Linux, l'adaptateur de compatibilité, sa mémoire et son démarrage.
E2 retient donc le chemin MINOS léger par défaut et diffère Thrift/sidecar. Les
détails sont dans `docs/m0/RAPPORT_GLEAN_C1.md` et
`docs/m0/COMPARATIF_BACKENDS.md`.

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

## 21.1 Verdict obtenu

Le verdict du 22 juillet 2026 est :

```text
ADOPTER_AVEC_CONTRAINTES
```

Les contraintes structurantes sont : capacités explicites par fournisseur,
identités de repli tant que le canonique n'est pas prouvé, backend MINOS léger
par défaut, Glean optionnel, et promotion atomique des index. Le détail et la
transition vers M1 sont dans `docs/m0/DECISION_M0.md`.
