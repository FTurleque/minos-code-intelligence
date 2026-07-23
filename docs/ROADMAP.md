# Feuille de route — MINOS

Statut : **C0 à M6 terminés — M7 en cours**

L’état opérationnel et la porte active sont maintenus dans [`STATUS.md`](STATUS.md).
Cette feuille conserve la séquence produit, le périmètre attendu de chaque jalon
et ses portes de décision.

La roadmap reste guidée par les preuves : un jalon peut être ajusté si une
expérimentation invalide une hypothèse d’architecture.

---

## C0 — Cadrage fonctionnel et architectural

État : **TERMINÉ**

### Objectif

Définir précisément ce que MINOS doit être avant les implémentations produit.

### Livrables

- cahier des charges ;
- vision et positionnement ;
- frontière MINOS / NEXUS ;
- cas d’usage prioritaires ;
- périmètre MVP ;
- modèles de domaine, symboles et relations ;
- stratégies d’indexation, SCIP, Glean et `CodeKnowledgeStore` ;
- critères de validation ;
- ADR structurantes ;
- plan d’expérimentations M0.

---

## M0 — Faisabilité technique

État : **TERMINÉ ET LIVRÉ — verdict ADOPTER_AVEC_CONTRAINTES**

### Objectif

Valider les choix structurants avec des expérimentations réelles et mesurables.

### Acquis

- qualification SCIP Java / TypeScript ;
- baseline SCIP → MINOS sur artefacts réels ;
- benchmark du backend mémoire ;
- qualification de Glean sous contraintes ;
- backend MINOS léger retenu par défaut ;
- frontière fournisseur préservée.

Décision : `m0/DECISION_M0.md`.

---

## M1 — Découverte des projets et orchestration des indexeurs

État : **TERMINÉ ET LIVRÉ**

### Objectif

Détecter un projet et sélectionner les fournisseurs d’indexation adaptés.

### Périmètre livré

- registre local projets/workspaces ;
- détection des langages ;
- détection des systèmes de build ;
- racines de sources et de tests ;
- `.gitignore` et `.minosignore` ;
- `IndexerRegistry` ;
- négociation des capacités ;
- cycle de vie de l’indexation ;
- état de l’index ;
- promotion atomique du snapshot projet.

---

## M2 — Intelligence des symboles

État : **TERMINÉ ET LIVRÉ**

### Objectif

Exposer une recherche fiable des symboles indépendamment du fournisseur et du backend.

### Périmètre livré

- modèle normalisé des symboles ;
- identité stable et qualité d’identité ;
- fichiers, modules et emplacements ;
- types de symboles ;
- symboles externes et non résolus ;
- `find_symbol` ;
- `get_file_symbols` ;
- recherche lexicale et par nom qualifié ;
- snapshot persistant et résultats compacts.

Décision : `m2/DECISION_M2.md`.

---

## M3 — Intelligence des relations

État : **TERMINÉ ET LIVRÉ**

### Objectif

Exposer les relations entrantes et sortantes entre éléments du code.

### Périmètre livré

- références ;
- implémentations ;
- héritage et appels lorsqu’ils sont disponibles ;
- dépendances dérivées ;
- provenance, preuves et confiance ;
- requêtes usages/implémentations/appels/dépendances ;
- snapshot de connaissance v2.

Décision : `m3/DECISION_M3.md`.

---

## M4 — Recherche et contexte compact

État : **TERMINÉ ET LIVRÉ**

### Objectif

Rendre MINOS directement exploitable par des outils et agents avant le MCP.

### Périmètre livré

- recherche structurée unifiée ;
- sortie compacte ;
- limites de résultats, profondeur et tokens ;
- plages de code pertinentes ;
- récupération explicite du code source complet ;
- benchmark de latence.

Décision : `m4/DECISION_M4.md`.

---

## M5 — Tests liés et dérivations explicables

État : **TERMINÉ ET LIVRÉ**

### Objectif

Déduire des relations utiles que les indexeurs ne fournissent pas forcément directement.

### Périmètre livré

- détection des tests liés ;
- conventions de nommage ;
- références directes ;
- appels lorsqu’ils sont disponibles ;
- proximité package/namespace ;
- score de confiance ;
- raisons et preuves structurées ;
- requête et CLI `related-tests`.

Décision : `m5/DECISION_M5.md`.

---

## M6 — Intelligence d’architecture

État : **TERMINÉ, VALIDÉ LOCALEMENT ET LIVRÉ**

### Objectif

Produire une vue de haut niveau de la topologie d’un projet.

### Périmètre livré

- topologie des modules ;
- topologie des packages/namespaces ;
- composants centraux sous forme de rangs relatifs directionnels ;
- concentration des dépendances ;
- technologies détectées factuellement ;
- `get_architecture_overview` métier ;
- `get_module_context` métier ;
- vue composée `ArchitectureIntelligenceView` ;
- distinction explicite entre faits, dérivations et preuves.

### Incréments

```text
M6.1 topologie modules / namespaces             PR #14
M6.2 dépendances inter-modules                  PR #15
M6.3 concentration                              PR #16
M6.4 calibration centralité                     PR #17
M6.5 classement composants centraux             PR #18
M6.6 technologies factuelles                    PR #19
M6.7 vue composée + contexte de module          PR #20
consolidation finale                            PR #21
```

Décision : `m6/DECISION_M6.md`.

---

## M7 — Indexation incrémentale

État : **EN COURS — M7.1 ET M7.2 LIVRÉS, M7.3 EN VALIDATION**

Suivi : issue #22.

### Objectif

Éviter les réindexations complètes lorsque cela n’est pas nécessaire, sans
jamais promouvoir une exécution partielle qui n’est pas prouvée sûre.

### Périmètre

- empreintes de fichiers ;
- empreintes du projet et du build ;
- fichiers ajoutés, modifiés et supprimés ;
- snapshots d’empreintes associés aux snapshots d’index ;
- règles d’invalidation ;
- capacités incrémentales propres aux fournisseurs ;
- décision sûre `INCREMENTAL` vs `FULL` ;
- repli explicite vers une indexation complète.

### M7.1 — Empreintes reproductibles et ChangeSet — LIVRÉ

PR #23, merge `34b57dfadad962b98c2d5c028957595cee575400`.

Acquis :

- `FileFingerprint` ;
- `ProjectFingerprint` ;
- `ProjectChangeSet` ;
- `ProjectFingerprintService` ;
- empreintes déterministes ;
- distinction projet/build ;
- classification added/modified/deleted/unchanged.

### M7.2 — Snapshots persistants d’empreintes — LIVRÉ

PR #24, merge `379b5a28a92cb58b340dc8801d66fad1b853e4ce`.

Acquis :

- association `projectId + indexSnapshotId` ;
- historique immuable ;
- publication et promotion séparées ;
- pointeur actif atomique ;
- contrôle d’intégrité ;
- alignement explicite avec `ProjectIndexState.activeSnapshotId`.

### M7.3 — Invalidation conservatrice — EN VALIDATION

Objectif : déterminer la portée fournisseur-indépendante avant négociation avec
un indexeur.

Portées :

```text
NONE
PARTIAL_CANDIDATE
FULL_REQUIRED
```

Règles :

- pas d’index actif → complet ;
- baseline absente/désalignée → complet ;
- définition de build modifiée → complet ;
- politique d’ignore modifiée → complet ;
- fichier changé non qualifiable → complet ;
- uniquement sources/tests reconnus → candidat partiel ;
- aucun changement → aucune réindexation.

`PARTIAL_CANDIDATE` ne constitue pas une preuve de capacité fournisseur.

### Suite prévue

M7.4 devra introduire une capacité fournisseur explicite d’indexation
incrémentale et combiner cette capacité avec M7.3 afin de produire un plan
`INCREMENTAL` ou `FULL` avec fallback sûr.

### Porte de décision M7

> MINOS sait-il déterminer de manière sûre ce qui peut être réindexé partiellement, et revenir explicitement à une indexation complète lorsqu’il ne peut pas le prouver ?

---

## M8 — Analyse d’impact

### Objectif

Estimer la propagation potentielle d’une modification à partir des relations connues.

### Périmètre

- impact direct ;
- impact indirect ;
- chemin explicatif ;
- score de confiance ;
- contrôle de profondeur ;
- tests potentiellement impactés ;
- limites liées au comportement dynamique.

---

## M9 — CLI stabilisée

### Objectif

Stabiliser l’interface en ligne de commande destinée aux développeurs et automatisations.

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

Le MCP reste une couche d’exposition. Aucune logique d’analyse métier ne doit
résider dans les handlers MCP.

---

## M11 — API

### Objectif

Permettre à des systèmes externes de consommer MINOS sans dépendre de Glean ou
des adaptateurs internes.

### Périmètre

- opérations sur les projets et index ;
- requêtes de symboles ;
- requêtes de relations ;
- architecture ;
- impact ;
- contrats DTO stables.

---

## M12 — Multi-dépôts et intelligence Git

### Objectif

Étendre MINOS des dépôts isolés vers des workspaces et une compréhension de l’historique.

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

Permettre à NEXUS de consommer la Code Intelligence de MINOS pour sélectionner
un contexte adapté à une tâche.

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
