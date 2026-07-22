# M0 — Plan détaillé des expérimentations

- Statut : **Prêt pour validation de sortie C0**
- Date : **22 juillet 2026**
- Objectif : **valider la faisabilité technique sans construire prématurément le produit**

## 1. Question centrale

M0 doit répondre à la question suivante :

> **MINOS peut-il construire une Code Intelligence précise, multi-langages, locale et compacte en réutilisant SCIP et éventuellement Glean, tout en restant indépendant de ces technologies ?**

M0 n'a pas pour but de développer le MVP complet.

---

# 2. Hypothèses à tester

## H1 — SCIP peut fournir le socle sémantique principal

À tester :

- identités de symboles ;
- définitions ;
- références ;
- implémentations ;
- héritage ;
- informations cross-file ;
- informations cross-module ;
- symboles externes ;
- qualité des informations d'appel lorsqu'elles existent.

## H2 — Le domaine MINOS peut être indépendant du fournisseur

À tester :

- aucun type SCIP dans le domaine ;
- même requête MINOS sur plusieurs fournisseurs/backends ;
- représentation explicite des capacités partielles ;
- représentation des références non résolues.

## H3 — MINOS peut fonctionner sans Glean

À tester :

```text
Repository
    ↓
SCIP
    ↓
Adaptateur MINOS
    ↓
Modèle normalisé
    ↓
CodeKnowledgeStore léger
    ↓
find_symbol / find_usages
```

Ce chemin constitue le **groupe de contrôle**.

## H4 — Glean apporte une valeur suffisante pour justifier son coût

À tester :

- requêtes complexes ;
- traversées relationnelles ;
- faits dérivés ;
- performance ;
- stockage ;
- opérabilité ;
- intégration ;
- distribution.

## H5 — L'architecture n'est pas Java-centric

À tester avec un second écosystème.

---

# 3. Écosystèmes de validation

## 3.1 Écosystème principal — Java

Java est retenu comme premier environnement de validation M0.

Raisons :

- projets réels disponibles dans l'écosystème ;
- cas complexes Maven / frameworks ;
- surcharge de méthodes ;
- héritage et interfaces ;
- dépendances externes ;
- `scip-java` disponible.

### Dépôt réel principal

```text
FTurleque/ariane-chatbot
```

Ce dépôt privé est utilisé comme projet réel représentatif.

### Fixtures Java contrôlées

Créer des fixtures couvrant au minimum :

```text
java-simple
java-overloads
java-inheritance
java-multi-module
java-missing-dependency
java-generated-like
```

## 3.2 Second écosystème — TypeScript

TypeScript est retenu comme second écosystème de validation initial.

Raisons :

- écosystème sensiblement différent de la JVM ;
- modules et packages différents ;
- fonctions et classes ;
- résolution dynamique plus difficile ;
- `scip-typescript` disponible ;
- chemin Glean documenté via SCIP.

Fixtures minimales :

```text
typescript-simple
typescript-modules
typescript-inheritance
typescript-unresolved
```

### Repli

Si `scip-typescript` présente un blocage rendant le test non représentatif, Python devient le second écosystème de repli.

Ce repli n'affecte pas le domaine MINOS.

---

# 4. Environnements d'exécution

M0 doit distinguer explicitement :

## Windows

Environnement utilisateur cible majeur.

À valider :

- indexeurs SCIP ;
- CLI MINOS de spike ;
- stockage léger ;
- fonctionnement sans Glean.

## Linux

Environnement de référence pour Glean.

À valider :

- build / installation Glean ;
- `scip-to-glean` ;
- Glean CLI ;
- serveur local éventuel ;
- requêtes Angle.

## WSL2 / virtualisation locale

Peut être utilisée comme **expérience de distribution Glean**, mais ne doit pas être considérée comme transparente ou acceptable avant mesure de l'expérience utilisateur.

## macOS

Pas nécessaire pour exécuter tous les spikes pendant M0 si l'environnement n'est pas disponible, mais les décisions de packaging doivent conserver macOS comme cible.

---

# 5. Expérience A — Qualifier SCIP et scip-java

## Objectif

Déterminer exactement ce que `scip-java` fournit à MINOS.

## Pipeline

```text
Projet Java
    │
    ▼
scip-java
    │
    ▼
index.scip
    │
    ├── scip lint
    ├── scip stats
    ├── scip snapshot
    └── scip test
```

## Cas obligatoires

### Symboles

- classe ;
- interface ;
- enum ;
- record ;
- annotation ;
- méthode ;
- constructeur ;
- champ ;
- surcharge.

### Relations

- définition ;
- référence ;
- import ;
- extends ;
- implements ;
- appels disponibles ;
- référence inter-fichiers ;
- référence inter-modules.

### Résolution

- dépendance disponible ;
- dépendance manquante ;
- symbole externe ;
- code partiellement compilable ;
- Maven multi-module.

## Mesures

```text
indexing_success_rate
indexing_duration
index_size
symbol_precision
reference_precision
implementation_precision
unresolved_count
memory_peak
```

## Sortie

Créer un `ProviderQualityProfile` réel pour `scip-java`.

---

# 6. Expérience B — Baseline directe SCIP → MINOS

## Objectif

Prouver que MINOS n'a pas besoin de Glean pour exister.

## Pipeline

```text
index.scip
    │
    ▼
ScipIngestionAdapter
    │
    ▼
MINOS Model
    │
    ▼
CodeKnowledgeStore léger
    │
    ├── find_symbol
    └── find_usages
```

## Implémentation minimale

Le spike ne doit implémenter que les concepts nécessaires :

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

Backends autorisés :

1. `InMemoryCodeKnowledgeStore` obligatoire pour les tests ;
2. stockage embarqué minimal uniquement si nécessaire pour mesurer la persistance.

## Interdictions

Ne pas développer pendant ce spike :

- moteur de graphe général ;
- query language ;
- analyse d'impact complète ;
- MCP ;
- API REST ;
- NEXUS ;
- indexation incrémentale complète.

## Critère de réussite

Les deux requêtes suivantes doivent fonctionner uniquement avec des contrats MINOS :

```text
find_symbol
find_usages
```

---

# 7. Expérience C — SCIP → Glean

## Objectif

Mesurer la valeur réelle de Glean par rapport à la baseline.

## Pipeline

```text
index.scip
    │
    ▼
scip-to-glean
    │
    ▼
Glean DB
    │
    ▼
GleanCodeKnowledgeStore
    │
    ▼
MINOS Query Services
```

## Requêtes à mesurer

- définition d'un symbole ;
- références ;
- implémentations ;
- appelants si disponibles ;
- relations transitives ;
- exemple de prédicat dérivé ;
- exemple de fait MINOS spécifique.

## Intégrations à comparer

### C1 — CLI

Utilisation de :

```text
glean query
```

Objectif : validation fonctionnelle rapide.

### C2 — Thrift

Évaluer :

- génération cliente ;
- compatibilité avec la stack MINOS envisagée ;
- sérialisation ;
- maintenance ;
- gestion du serveur local.

### C3 — Sidecar

Évaluer un processus Glean isolé derrière un adaptateur MINOS.

## Mesures

```text
install_complexity
startup_time
query_latency
index_ingestion_time
disk_size
memory_peak
process_count
failure_recovery
rebuild_time
```

---

# 8. Expérience D — TypeScript

Statut au 22 juillet 2026 : **D1 `typescript-simple` exécutée**. Le pipeline
complet fonctionne avec un repli de métadonnées limité à l'adaptateur ; les
kinds, le lint strict, l'encodage de position et les relations `CALLS` restent
des limitations qualifiées dans `docs/m0/RAPPORT_SCIP_TYPESCRIPT_D1.md`.

### D2 — qualification ciblée avant comparaison des backends

Statut au 22 juillet 2026 : **D2 exécutée**. Les trois vérités terrain ont été
écrites avant l'indexation :

```text
typescript-modules      références de projets, cross-module, surcharges, tests
typescript-inheritance héritage, implémentations et overrides
typescript-unresolved  module absent et cibles non résolues
```

Le cas multi-projet utilise les références TypeScript `tsconfig`. La release
officielle `scip-typescript 0.4.0` les parcourt récursivement ; elle expose des
options dédiées à Yarn et pnpm workspaces, mais pas à npm workspaces. npm reste
utilisé uniquement pour installer TypeScript et exécuter les builds de vérité
terrain.

Mesures supplémentaires D2 :

```text
overloadDeclarationsExpected
overloadProviderSymbols
providerMultiValuedRoleOccurrences
inheritanceRelationships
implementationRelationships
workspaceCrossProjectUsages
expectedUnresolvedTargets
observedUnresolvedTargets
```

Un échec de compilation attendu dans `typescript-unresolved` est une donnée de
qualification. Il ne doit être ni corrigé artificiellement, ni présenté comme
un build réussi.

Résultat : les références de projets et les relations d'héritage sont
exploitables avec des limitations de lint. Les surcharges partagent un même
identifiant fournisseur, aucun rôle multi-valué n'est émis et l'appel d'un
membre sur le type absent n'est pas indexé. Le détail mesuré se trouve dans
`docs/m0/RAPPORT_SCIP_TYPESCRIPT_D2.md`.

Reproduire le chemin conceptuel :

```text
TypeScript repo
      │
      ▼
scip-typescript
      │
      ▼
index.scip
      │
      ▼
ScipIngestionAdapter
      │
      ▼
MINOS Model
```

## Critère essentiel

Aucune modification du domaine ou des services de requêtes ne doit être nécessaire pour supporter le second écosystème.

Seuls doivent pouvoir changer :

- fournisseur ;
- configuration ;
- mapping spécifique d'ingestion ;
- profil de capacités.

---

# 9. Expérience E — Comparaison des backends

Même dataset, mêmes requêtes, mêmes résultats attendus.

| Critère | Backend léger | Glean |
|---|---:|---:|
| `find_symbol` | mesurer | mesurer |
| `find_usages` | mesurer | mesurer |
| relations transitives | mesurer | mesurer |
| temps de démarrage | mesurer | mesurer |
| latence | mesurer | mesurer |
| mémoire | mesurer | mesurer |
| disque | mesurer | mesurer |
| installation | noter | noter |
| Windows | valider | valider / qualifier |
| reconstruction | mesurer | mesurer |
| complexité code MINOS | mesurer | mesurer |

Le résultat fonctionnel doit être comparé en utilisant des DTO MINOS identiques.

## E1 — Baseline reproductible du backend mémoire

E1 mesure d'abord `InMemoryCodeKnowledgeStore` sans Glean. Cette étape fixe la
référence légère à laquelle tout backend candidat devra être comparé.

Corpus réel :

```text
java-simple
java-24-smoke
ariane-chatbot
java-multi-module
typescript-simple
typescript-modules
typescript-inheritance
typescript-unresolved
```

Le corpus et les requêtes sont figés dans
`benchmarks/m0/e1-in-memory.json`. Chaque dataset est exécuté dans un JVM neuf
afin d'isoler la lecture, l'ingestion et le store. Pour chaque requête :

```text
warmupIterations       100
measurementIterations  500
operations             find_symbol, find_usages
serialization          représentation canonique incluse dans la mesure
```

Mesures E1 :

```text
indexReadDuration
ingestionDuration
backendReadyDuration
processWallClockDuration
find_symbol p50 / p95 / max
find_usages p50 / p95 / max
peakHeapIndexing
retainedHeapAfterIngestion
peakHeapQuery
indexDiskSize
workingStoreDiskSize
resultDigestStable
```

La latence de requête est mesurée après échauffement du JVM, sur index déjà
ingéré. Le temps `backendReadyDuration` représente la reconstruction complète
du backend mémoire (`indexReadDuration + ingestionDuration`) mais exclut le
démarrage du processus Java. Le temps processus est mesuré séparément par le
runner PowerShell.

La mémoire E1 est la heap Java observée dans le processus. Elle ne constitue
pas encore une mesure RSS complète du système ; cette limite doit être
conservée dans le rapport et appliquée symétriquement aux futurs backends.

Le store mémoire n'écrit aucun fichier de travail : son coût disque propre est
donc `0`, distinct de la taille de l'`index.scip` source et des résultats du
benchmark.

E1 ne modifie ni `CodeKnowledgeStore`, ni les DTO, ni les services de requêtes.
Le harness reste expérimental dans les sources de test et ne devient pas une
CLI produit.

---

# 10. Fixtures et vérité terrain

Les tests M0 ne doivent pas dépendre uniquement de grands dépôts réels.

Chaque fixture doit inclure un manifeste attendu décrivant :

```text
expected_symbols
expected_relationships
expected_usages
expected_implementations
expected_unresolved
```

Le manifeste devient la vérité terrain des métriques de précision.

Le CLI SCIP `snapshot` / `test` peut être utilisé en complément pour vérifier les index bruts.

---

# 11. Critères de décision SCIP

Un fournisseur SCIP peut être recommandé lorsque :

- les définitions attendues sont correctement identifiées ;
- les références statiquement résolvables atteignent le seuil fixé ;
- les symboles surchargés sont distingués ;
- les limitations sont détectables ;
- les erreurs partielles ne produisent pas de faux faits silencieux ;
- les performances sont acceptables.

Les seuils chiffrés définitifs seront fixés dans le document de métriques C0.

---

# 12. Critères de décision Glean

Glean doit démontrer une valeur supérieure ou complémentaire sur au moins un des axes suivants :

- requêtes complexes ;
- traversées ;
- dérivations ;
- gros volumes ;
- persistance ;
- multi-dépôts ;
- simplification du code MINOS.

Cette valeur doit compenser :

- installation ;
- runtime ;
- mémoire ;
- disque ;
- processus supplémentaire ;
- contraintes OS ;
- maintenance du client.

Sans bénéfice mesurable, Glean reste hors du chemin par défaut.

---

# 13. Livrables M0

```text
docs/m0/RESULTATS_SCIP_JAVA.md
docs/m0/RESULTATS_SCIP_TYPESCRIPT.md
docs/m0/RESULTATS_BASELINE.md
docs/m0/RESULTATS_GLEAN.md
docs/m0/COMPARATIF_BACKENDS.md
docs/m0/DECISION_M0.md
```

Ainsi que :

- fixtures ;
- scripts reproductibles ;
- mesures brutes ;
- profils de capacités ;
- ADR mises à jour selon les résultats.

---

# 14. Condition de sortie M0

M0 est terminé lorsque :

1. `scip-java` est qualifié sur fixtures et dépôt réel ;
2. un second écosystème est qualifié ;
3. le domaine MINOS fonctionne sans type SCIP ;
4. `find_symbol` et `find_usages` fonctionnent sans Glean ;
5. Glean a été testé sur les mêmes cas d'usage ;
6. les coûts Windows/Linux sont documentés ;
7. les performances sont mesurées ;
8. la précision est mesurée ;
9. une décision backend est prise ;
10. les limites connues sont documentées.

Décision finale :

```text
ADOPTER
ADOPTER_AVEC_CONTRAINTES
REVOIR
REMPLACER
```

---

# 15. Ce que M0 ne doit pas devenir

M0 ne doit pas dériver vers :

- implémentation complète de MINOS ;
- serveur MCP de production ;
- API de production ;
- intégration NEXUS ;
- moteur d'impact complet ;
- support exhaustif des langages ;
- architecture distribuée ;
- UI ;
- plugin IDE.

M0 doit rester une **expérience de réduction de risque**.
