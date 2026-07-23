# Feuille de route — MINOS

Statut : **C0 à M9 terminés et livrés — M10 implémenté, validation finale en attente**

L’état opérationnel et la porte active sont maintenus dans [`STATUS.md`](STATUS.md). Cette feuille conserve la séquence produit, le périmètre attendu de chaque jalon et ses portes de décision.

La roadmap reste guidée par les preuves : un jalon peut être ajusté si une expérimentation invalide une hypothèse d’architecture.

---

## C0 — Cadrage fonctionnel et architectural

État : **TERMINÉ**

Objectif : définir précisément MINOS avant les implémentations produit.

---

## M0 — Faisabilité technique

État : **TERMINÉ ET LIVRÉ — ADOPTER_AVEC_CONTRAINTES**

Acquis : qualification SCIP Java/TypeScript, baseline SCIP → MINOS, backend local léger, Glean optionnel et frontière fournisseur.

Décision : `m0/DECISION_M0.md`.

---

## M1 — Découverte des projets et orchestration des indexeurs

État : **TERMINÉ ET LIVRÉ**

Acquis : registre projets/workspaces, découverte langages/builds/modules/racines, ignores, registre/négociation indexeurs, lifecycle et promotion atomique.

---

## M2 — Intelligence des symboles

État : **TERMINÉ ET LIVRÉ**

Acquis : symboles normalisés, identités stables, emplacements, externes/non résolus, recherche et snapshots persistants.

Décision : `m2/DECISION_M2.md`.

---

## M3 — Intelligence des relations

État : **TERMINÉ ET LIVRÉ**

Acquis : références, implémentations, héritage, appels lorsque disponibles, dépendances dérivées, provenance, preuves, confiance et requêtes directionnelles.

Décision : `m3/DECISION_M3.md`.

---

## M4 — Recherche et contexte compact

État : **TERMINÉ ET LIVRÉ**

Acquis : recherche structurée, sorties compactes, limites résultats/tokens/profondeur, extraits pertinents, source explicite et benchmark de latence.

Décision : `m4/DECISION_M4.md`.

---

## M5 — Tests liés et dérivations explicables

État : **TERMINÉ ET LIVRÉ**

Acquis : `RELATED_TEST`, signaux de nommage/référence/appel/proximité, score, raisons et preuves structurées.

Décision : `m5/DECISION_M5.md`.

---

## M6 — Intelligence d’architecture

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Acquis : topologie modules/namespaces, dépendances inter-modules, concentration, centralité relative directionnelle, technologies factuelles, vue composée et contexte de module.

Porte finale : **162/162 tests PASS**.

Décision : `m6/DECISION_M6.md`.

---

## M7 — Indexation incrémentale

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Suivi clôturé : issue #22.

Merge final :

```text
c66382705880158b9ccac63b5662b81bf2d8d255
```

Porte finale :

```text
134 sources main
69 sources test
196/196 tests PASS
BUILD SUCCESS
```

Acquis : fingerprints, ChangeSet, snapshots d’empreintes, invalidation conservatrice, capacité incrémentale explicite, plan `NONE/INCREMENTAL/FULL`, fallback complet et baseline conditionnée à la stabilité du workspace.

Porte M7 : **OUI, sous preuve explicite de capacité fournisseur.**

Décision : `m7/DECISION_M7.md`.

---

## M8 — Analyse d’impact

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Suivi clôturé : issue #27.

PR finale : #28.

Head validé :

```text
08bbdeab18873a2209f02b58bc8d7e547443ea0f
```

Merge final :

```text
8147db5c246c7bad92c9b6ab21be81084dc64f59
```

Porte finale :

```text
143 sources main
72 sources test
203/203 tests PASS
BUILD SUCCESS
```

Acquis : impact direct/indirect, chemins explicatifs, confiance conservatrice, profondeur/résultats bornés, cycles, tests potentiellement impactés et limites runtime explicites.

Replay réel final :

```text
M8 typescript-modules impact: root=GreetingPort, impacts=2, tests=1, max-depth=2, limitations=[DYNAMIC_DISPATCH_NOT_PROVEN, REFLECTION_NOT_PROVEN, RUNTIME_CONFIGURATION_NOT_PROVEN]
```

Porte M8 : **OUI, comme estimation potentielle fondée sur le graphe observé — jamais comme preuve d’exhaustivité runtime.**

Décision : `m8/DECISION_M8.md`.

---

## M9 — CLI stabilisée

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Suivi clôturé : issue #29.

PR finale : #30.

Head exact validé :

```text
ae82f24897ea925f04f450f793541b39d13b6d47
```

Merge final :

```text
22afe31339dc3a75dc51c491a725330c6d433ecc
```

Porte finale :

```text
150 sources main
75 sources test
207/207 tests PASS
BUILD SUCCESS
```

Replay réel :

```text
M9 stable CLI: project=<uuid>, snapshot=scip-7f41649a3cdad442a3235c0a, architecture-modules=3, impact-root=GreetingPort
```

Acquis : administration du registre, import SCIP explicite, statut d’index factuel, recherche/symboles/relations/tests liés, architecture, impact, formats `text/json`, codes de sortie stables, aides et replay end-to-end réel.

Frontière : aucun runner de production absent n’est simulé par `minos index`.

Porte M9 :

> **OUI, sous la frontière d’exécution réellement disponible : administration, requêtes M2–M8 et import SCIP explicite, sans revendiquer un runner automatique absent.**

Décision : `m9/DECISION_M9.md`.

---

## M10 — Serveur MCP

État : **FONCTIONNELLEMENT COMPLET — VALIDATION LOCALE FINALE EN ATTENTE**

Suivi : issue #31.

Branche :

```text
m10/mcp-server
```

### Objectif

Exposer aux agents IA les capacités MINOS M1–M9 via un serveur MCP local, borné et fournisseur-indépendant, sans déplacer la logique métier dans le protocole.

### Choix techniques

```text
SDK MCP Java officiel   2.0.0
Transport               STDIO
API serveur             synchrone
Framework web           aucun
Tools                    15 read-only
```

### Surface implémentée

```text
minos_project_structure
minos_index_status
minos_search_code
minos_find_symbols
minos_find_usages
minos_find_implementations
minos_find_callers
minos_find_callees
minos_dependencies
minos_dependents
minos_related_tests
minos_symbol_context
minos_module_context
minos_architecture
minos_impact
```

### Frontière MCP

Les handlers MCP traduisent les arguments validés vers la surface CLI JSON M9 puis délèguent à `MinosLauncher.run(...)`.

Aucune analyse M1–M8 n’est réimplémentée dans `com.minos.mcp`.

Le serveur M10 est read-only : aucune mutation de projet, aucune indexation et aucune écriture applicative ne sont exposées comme tool.

### Contrat protocolaire

- [x] serveur STDIO ;
- [x] SDK officiel épinglé ;
- [x] négociation et `tools/list` ;
- [x] `tools/call` ;
- [x] JSON Schemas bornés ;
- [x] `additionalProperties=false` ;
- [x] validation d’entrée SDK ;
- [x] erreurs tool explicites ;
- [x] aucune sortie applicative parasite sur stdout ;
- [x] home `minos.home > MINOS_HOME > ~/.minos` ;
- [x] test unitaire du catalogue ;
- [x] test d’intégration protocolaire STDIO ;
- [x] replay réel TypeScript ;
- [x] distribution `-all.jar` ;
- [x] documentation et décision ;
- [ ] validation locale finale du head exact ;
- [ ] fusion et clôture administrative.

### Qualification attendue

Le test d’intégration démarre un vrai sous-processus MCP puis vérifie :

```text
15 tools
architecture modules = 3
impact GreetingPort = 2
related impacted tests = 1
schema impact depth=99 rejeté
```

Replay attendu :

```text
M10 MCP stdio: tools=15, project=<uuid>, snapshot=<snapshot>, architecture-modules=3, impact-root=GreetingPort
```

### Porte de décision M10

> Un client MCP standard peut-il découvrir et appeler les capacités MINOS M1–M9 via un serveur local fiable, borné et fournisseur-indépendant, sans divergence avec le cœur métier ?

Verdict préparé :

> **OUI, via un serveur MCP STDIO read-only qui délègue à la surface MINOS existante et conserve les mêmes bornes, preuves et limitations.**

Documents :

- `m10/MCP_SERVER.md` ;
- `m10/DECISION_M10.md`.

Porte finale :

```powershell
.\mvnw.cmd clean verify
```

Volumes attendus si le head reste inchangé :

```text
152 sources main
77 sources test
210 tests
```

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

Non engagées dans la roadmap principale : Code Property Graph, flux de données, sécurité, recherche sémantique, embeddings, plugins IDE, indexation distante GitHub/GitLab, indexation distribuée et mode service hébergé.
