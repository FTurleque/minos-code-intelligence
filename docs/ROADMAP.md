# Feuille de route — MINOS

Statut : **C0 à M10 terminés et livrés — M11 implémenté, validation finale en attente**

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

Suivi clôturé : issue #27. PR finale : #28.

```text
head validé   08bbdeab18873a2209f02b58bc8d7e547443ea0f
merge         8147db5c246c7bad92c9b6ab21be81084dc64f59
sources       143 main / 72 test
tests         203/203 PASS
```

Acquis : impact direct/indirect, chemins explicatifs, confiance conservatrice, profondeur/résultats bornés, cycles, tests potentiellement impactés et limites runtime explicites.

Replay réel :

```text
M8 typescript-modules impact: root=GreetingPort, impacts=2, tests=1, max-depth=2, limitations=[DYNAMIC_DISPATCH_NOT_PROVEN, REFLECTION_NOT_PROVEN, RUNTIME_CONFIGURATION_NOT_PROVEN]
```

Porte M8 :

> **OUI, comme estimation potentielle fondée sur le graphe observé — jamais comme preuve d’exhaustivité runtime.**

Décision : `m8/DECISION_M8.md`.

---

## M9 — CLI stabilisée

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Suivi clôturé : issue #29. PR finale : #30.

```text
head validé   ae82f24897ea925f04f450f793541b39d13b6d47
merge         22afe31339dc3a75dc51c491a725330c6d433ecc
sources       150 main / 75 test
tests         207/207 PASS
```

Acquis : administration du registre, import SCIP explicite, statut d’index factuel, recherche/symboles/relations/tests liés, architecture, impact, formats `text/json`, codes de sortie stables, aides et replay end-to-end réel.

Frontière : aucun runner de production absent n’est simulé par `minos index`.

Porte M9 :

> **OUI, sous la frontière d’exécution réellement disponible : administration, requêtes M2–M8 et import SCIP explicite, sans revendiquer un runner automatique absent.**

Décision : `m9/DECISION_M9.md`.

---

## M10 — Serveur MCP

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Suivi clôturé : issue #31. PR finale : #32.

Head exact validé :

```text
3f3657a6e5c1a783993348c892f97138d990feff
```

Merge final :

```text
eb042852a936ad2e62e337ee35ed8a349096e794
```

Porte finale :

```text
152 sources main
77 sources test
210/210 tests PASS
BUILD SUCCESS
```

Choix techniques :

```text
SDK MCP Java officiel   2.0.0
Transport               STDIO
API serveur             synchrone
Framework web           aucun
Tools                    15 read-only
```

Acquis : négociation MCP, `tools/list`, `tools/call`, 15 tools read-only, JSON Schemas bornés, validation SDK, erreurs structurées, stdout réservé au protocole, packaging `-all.jar` et replay réel TypeScript.

Frontière : les handlers MCP délèguent à la surface existante et ne réimplémentent aucune intelligence M1–M8.

Replay :

```text
M10 MCP stdio: tools=15, project=<uuid>, snapshot=<snapshot>, architecture-modules=3, impact-root=GreetingPort
```

Porte M10 :

> **OUI, via un serveur MCP STDIO read-only qui délègue à la surface MINOS existante et conserve les mêmes bornes, preuves et limitations.**

Décision : `m10/DECISION_M10.md`.

---

## M11 — API

État : **INTÉGRALEMENT IMPLÉMENTÉ — VALIDATION LOCALE FINALE EN ATTENTE**

Suivi : issue #33.

PR Draft : #34.

Branche :

```text
m11/public-api
```

### Objectif

Permettre à des systèmes externes de consommer MINOS via des DTO stables sans dépendre de Glean, SCIP, des adaptateurs internes, du stockage, de la CLI ou du protocole MCP.

### Contrat public

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

Les signatures publiques n’exposent que des types JDK et des DTO/requêtes `MinosApi`.

### Surface implémentée

```text
projets : add / list / inspect
index : import SCIP explicite + statut
symboles
usages
relations
architecture
contexte module
impact
```

### DTO publics

Le contrat conserve les informations importantes déjà qualifiées :

- identité, localisation et provenance des symboles ;
- rôles et résolution des usages ;
- source/cible, nature, confiance et preuves des relations ;
- topologie, dépendances, centralité, technologies et modules ;
- chemins, confiance, tests potentiels et limitations de l’impact.

Les enums internes sont exposés comme chaînes pour éviter un couplage binaire du consommateur aux enums métier MINOS.

### Contrat d’erreur

```text
INVALID_REQUEST
UNAVAILABLE
IO_FAILURE
EXECUTION_FAILURE
```

### Frontière M11

- aucune analyse M1–M8 réimplémentée dans `com.minos.api` ;
- aucun type interne dans les signatures publiques ;
- aucun serveur HTTP ni framework web ;
- import SCIP explicite uniquement ;
- aucune capacité d’indexation absente n’est inventée ;
- la sémantique conservatrice de M8 reste inchangée.

### Qualification ajoutée

`MinosApiContractTest` vérifie par réflexion que les méthodes et composants des DTO publics ne fuient aucun type interne interdit.

`LocalMinosApiIntegrationTest` rejoue `fixtures/typescript/typescript-modules` et vérifie :

```text
contract version       1
project modules        3
index state            READY
relation               IMPLEMENTS
architecture modules   3
module context          packages/api
impact GreetingPort    2
potential tests        1
invalid enum           INVALID_REQUEST
```

Replay attendu :

```text
M11 public API: version=1, project=<uuid>, snapshot=<snapshot>, modules=3, impact=2, tests=1
```

### Contrôles actuels

SonarQube Cloud sur PR #34 : **Quality Gate passed**, 0 Security Hotspots et 0.0 % duplication sur nouveau code. Trois issues non bloquantes sont signalées.

Aucun workflow GitHub Actions n’est lancé ; la porte Maven locale reste la preuve finale.

### Porte de décision M11

> Des systèmes externes peuvent-ils consommer les capacités MINOS via un contrat Java public stable, sans dépendre de Glean, SCIP, du stockage local, de la CLI, du MCP ni des modèles internes ?

Verdict préparé :

> **OUI, via un contrat Java local versionné dont les DTO publics restent indépendants des fournisseurs, protocoles et modèles internes, tout en déléguant l’intelligence au cœur MINOS existant.**

Documents :

- `m11/API.md` ;
- `m11/DECISION_M11.md`.

Porte finale :

```powershell
.\mvnw.cmd clean verify
```

Volumes attendus sur le code actuel :

```text
154 sources main
79 sources test
214 tests
```

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
