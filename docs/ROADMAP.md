# Feuille de route — MINOS

Statut : **C0 à M8 terminés et livrés — M9 implémenté, validation finale en attente**

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

État : **FONCTIONNELLEMENT COMPLET — VALIDATION LOCALE FINALE EN ATTENTE**

Suivi : issue #29.

Branche :

```text
m9/stable-cli
```

### Objectif

Stabiliser l’interface en ligne de commande destinée aux développeurs et automatisations, sans dupliquer la logique métier M1 à M8.

### Surface implémentée

```text
minos project add
minos project list
minos project inspect
minos inspect
minos index
minos index-status
minos search
minos find-symbol
minos get-source
minos find-usages
minos find-implementations
minos find-callers
minos find-callees
minos dependencies
minos dependents
minos related-tests
minos architecture
minos impact
```

### Contrat CLI

- [x] formats `text` et `json` ;
- [x] codes de sortie `0 / 1 / 2` ;
- [x] erreurs sur stderr ;
- [x] aide globale et par commande ;
- [x] aide lazy sans création du home ;
- [x] administration du registre projet ;
- [x] inspection factuelle ;
- [x] import d’un artefact SCIP existant ;
- [x] statut de snapshot actif ;
- [x] architecture projet/module ;
- [x] analyse d’impact ;
- [x] tests end-to-end sur fixture réelle ;
- [x] documentation et décision ;
- [ ] validation locale finale du head exact ;
- [ ] fusion et clôture administrative.

### Frontière d’indexation

Aucun `IndexerExecutor` de production ne lance actuellement `scip-java` ou `scip-typescript` depuis le cœur. M9 ne simule pas ce support.

`minos index` stabilise le chemin réellement disponible :

```text
artefact SCIP existant
  -> ScipSymbolSnapshotImporter
  -> normalisation MINOS
  -> FileSymbolSnapshotStore
```

`index-status` n’invente ni date ni provider : les métadonnées de succès sont exposées uniquement lorsqu’un import CLI M9 aligné sur le snapshot actif les a réellement enregistrées.

### Porte de décision M9

> MINOS expose-t-il son cœur déjà validé via une CLI cohérente, scriptable, documentée et stable, avec les mêmes résultats métier que les services sous-jacents ?

Verdict préparé :

> **OUI, sous la frontière d’exécution réellement disponible : administration, requêtes M2–M8 et import SCIP explicite, sans revendiquer un runner automatique absent.**

Documents :

- `m9/CLI.md` ;
- `m9/DECISION_M9.md`.

Porte finale :

```powershell
.\mvnw.cmd clean verify
```

---

## M10 — Serveur MCP

État : **PROCHAIN APRÈS CLÔTURE M9**

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

Non engagées dans la roadmap principale : Code Property Graph, flux de données, sécurité, recherche sémantique, embeddings, plugins IDE, indexation distante GitHub/GitLab, indexation distribuée et mode service hébergé.
