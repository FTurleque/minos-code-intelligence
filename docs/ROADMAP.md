# Feuille de route — MINOS

Statut : **C0 à M7 terminés — M8 implémenté, validation finale en attente**

L’état opérationnel et la porte active sont maintenus dans [`STATUS.md`](STATUS.md). Cette feuille conserve la séquence produit, le périmètre attendu de chaque jalon et ses portes de décision.

La roadmap reste guidée par les preuves : un jalon peut être ajusté si une expérimentation invalide une hypothèse d’architecture.

---

## C0 — Cadrage fonctionnel et architectural

État : **TERMINÉ**

Objectif : définir précisément MINOS avant les implémentations produit.

Livrables : cahier des charges, vision, frontière MINOS/NEXUS, cas d’usage, MVP, modèle de domaine, stratégie d’indexation, critères de validation et ADR structurantes.

---

## M0 — Faisabilité technique

État : **TERMINÉ ET LIVRÉ — ADOPTER_AVEC_CONTRAINTES**

Acquis :

- qualification SCIP Java / TypeScript ;
- baseline SCIP → MINOS sur artefacts réels ;
- backend MINOS léger retenu par défaut ;
- Glean optionnel ;
- frontière fournisseur préservée.

Décision : `m0/DECISION_M0.md`.

---

## M1 — Découverte des projets et orchestration des indexeurs

État : **TERMINÉ ET LIVRÉ**

Acquis : registre projets/workspaces, détection langages/builds/modules/racines, politiques d’ignore, `IndexerRegistry`, négociation des capacités, lifecycle et promotion atomique.

---

## M2 — Intelligence des symboles

État : **TERMINÉ ET LIVRÉ**

Acquis : symboles normalisés, identités stables, emplacements, symboles externes/non résolus, recherche lexicale/qualifiée et snapshots persistants.

Décision : `m2/DECISION_M2.md`.

---

## M3 — Intelligence des relations

État : **TERMINÉ ET LIVRÉ**

Acquis : références, implémentations, héritage, appels lorsque disponibles, dépendances dérivées, provenance, preuves, confiance et requêtes directionnelles.

Décision : `m3/DECISION_M3.md`.

---

## M4 — Recherche et contexte compact

État : **TERMINÉ ET LIVRÉ**

Acquis : recherche structurée unifiée, sorties compactes, limites de résultats/tokens/profondeur, plages pertinentes, source complète explicite et benchmark de latence.

Décision : `m4/DECISION_M4.md`.

---

## M5 — Tests liés et dérivations explicables

État : **TERMINÉ ET LIVRÉ**

Acquis : `RELATED_TEST`, conventions de nommage, références/appels directs, proximité de namespace, score, raisons et preuves structurées.

Décision : `m5/DECISION_M5.md`.

---

## M6 — Intelligence d’architecture

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Acquis : topologie modules/namespaces, dépendances inter-modules, concentration, centralité relative directionnelle, technologies factuelles, vue composée et contexte de module.

Incréments : PR #14 à #20, consolidation PR #21.

Porte finale : **162/162 tests PASS**.

Décision : `m6/DECISION_M6.md`.

---

## M7 — Indexation incrémentale

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Suivi clôturé : issue #22.

### Objectif

Éviter les réindexations complètes lorsque cela n’est pas nécessaire, sans jamais exécuter une portée partielle qui n’est pas prouvée sûre.

### Livraisons

```text
M7.1 — empreintes et ChangeSet             PR #23
M7.2 — snapshots persistants               PR #24
M7.3 — invalidation conservatrice          PR #25
M7.4 — planification/fallback/lifecycle    PR #26
```

Merge final : `c66382705880158b9ccac63b5662b81bf2d8d255`.

Porte finale :

```text
134 sources main
69 sources test
196/196 tests PASS
BUILD SUCCESS
```

Acquis :

- fingerprints fichiers/projet/build ;
- ajout/modification/suppression ;
- snapshots d’empreintes liés aux snapshots d’index ;
- `NONE / PARTIAL_CANDIDATE / FULL_REQUIRED` ;
- `IndexerCapability.INCREMENTAL_INDEXING` ;
- `NONE / INCREMENTAL / FULL` ;
- fallback complet projet ;
- avancement de baseline uniquement sur workspace stable.

Porte M7 : **OUI, sous preuve explicite de capacité fournisseur.**

Décision : `m7/DECISION_M7.md`.

---

## M8 — Analyse d’impact

État : **IMPLÉMENTÉ — VALIDATION LOCALE FINALE EN ATTENTE**

Suivi : issue #27.

### Objectif

Estimer la propagation potentielle d’une modification à partir des relations connues, avec chemins explicatifs et limites de couverture explicites.

### Périmètre implémenté

- [x] impact direct ;
- [x] impact indirect ;
- [x] chemin explicatif ;
- [x] score de confiance ;
- [x] contrôle de profondeur ;
- [x] tests potentiellement impactés ;
- [x] limites liées au comportement dynamique ;
- [x] façade locale fournisseur-indépendante ;
- [x] replay sur fixture TypeScript réelle ;
- [ ] validation locale finale du head exact ;
- [ ] fusion et clôture administrative.

### Sémantique

Une relation de dépendance observée :

```text
source -> target
```

est parcourue en sens inverse pour l’impact : une modification de `target` peut potentiellement impacter `source`.

Relations propagées :

```text
TYPE_DEFINITION IMPORTS REFERENCES EXTENDS IMPLEMENTS CALLS
RETURNS ACCEPTS READS WRITES INSTANTIATES DEPENDS_ON INJECTS RELATED_TEST
```

Le meilleur chemin est choisi par profondeur croissante, confiance décroissante puis ordre lexical stable.

Confiance d’un chemin :

```text
minimum des confiances de ses relations
```

Un fait sans score explicite vaut `1.0` pour ce calcul.

Les tests M5 sont retournés comme **potentiellement impactés** avec leur chemin `RELATED_TEST` spécifique, y compris lorsque la relation M5 est heuristique.

### Limites explicites

```text
UNRESOLVED_RELATIONSHIPS_IGNORED
EXTERNAL_TARGETS_NOT_TRAVERSED
GENERATED_SYMBOLS_NOT_TRAVERSED
DYNAMIC_DISPATCH_NOT_PROVEN
REFLECTION_NOT_PROVEN
RUNTIME_CONFIGURATION_NOT_PROVEN
MAX_DEPTH_REACHED
MAX_RESULTS_REACHED
```

L’absence de chemin observé n’est jamais interprétée comme une preuve d’absence d’impact runtime.

### Porte de décision M8

> MINOS sait-il produire une estimation déterministe, bornée et explicable des éléments et tests potentiellement impactés par une modification, tout en exposant les limites de couverture du graphe observé ?

Verdict préparé :

> **OUI, comme estimation potentielle fondée sur le graphe observé — jamais comme preuve d’exhaustivité runtime.**

Documents :

- `m8/IMPACT_ANALYSIS.md` ;
- `m8/DECISION_M8.md`.

Porte finale :

```powershell
.\mvnw.cmd clean verify
```

---

## M9 — CLI stabilisée

État : **PROCHAIN APRÈS CLÔTURE M8**

Objectif : stabiliser l’interface en ligne de commande destinée aux développeurs et automatisations.

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

État : **NON DÉMARRÉ**

Objectif : exposer des outils spécialisés et compacts aux agents IA.

Outils envisagés : structure projet, recherche code, symboles, usages, implémentations, callers/callees, dépendances, tests liés, contexte de symbole/module, architecture, analyse d’impact et statut d’index.

Le MCP reste une couche d’exposition ; aucune logique métier d’analyse ne doit résider dans ses handlers.

---

## M11 — API

État : **NON DÉMARRÉ**

Objectif : permettre à des systèmes externes de consommer MINOS via des DTO stables sans dépendre de Glean ou des adaptateurs internes.

Périmètre : projets/index, symboles, relations, architecture, impact et contrats publics.

---

## M12 — Multi-dépôts et intelligence Git

État : **NON DÉMARRÉ**

Périmètre possible : résolution inter-dépôts, relations cross-repository, historique Git, fréquence de modification, changements récents et zones d’activité.

---

## M13 — Intégration NEXUS

État : **NON DÉMARRÉ**

Objectif : permettre à NEXUS de consommer la Code Intelligence de MINOS pour sélectionner un contexte adapté à une tâche.

Frontière :

- MINOS fournit faits, relations, preuves et vues compactes du code ;
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
