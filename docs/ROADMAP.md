# Feuille de route — MINOS

Statut : **C0 à M12 terminés et livrés — M13 implémenté, qualification inter-dépôt en cours**

L’état opérationnel courant est résumé dans [`STATUS.md`](STATUS.md). Les documents `mX/` et ADR conservent les preuves et décisions détaillées de chaque jalon.

## Principes de roadmap

- chaque jalon doit fermer une question produit identifiable ;
- une capacité n’est déclarée acquise qu’avec une preuve reproductible ;
- les limitations fournisseur restent explicites ;
- un nouveau commit invalide la validation exacte d’un SHA précédent ;
- les surfaces CLI/API/MCP ne doivent pas dupliquer le métier.

---

## C0 — Cadrage fonctionnel et architectural

État : **TERMINÉ**

Définition du rôle de MINOS, de ses frontières et de sa place dans l’écosystème.

---

## M0 — Faisabilité technique

État : **TERMINÉ ET LIVRÉ — ADOPTER_AVEC_CONTRAINTES**

Acquis : qualification SCIP Java/TypeScript, baseline SCIP → MINOS, backend local léger, Glean optionnel et frontière fournisseur.

Décision : `m0/DECISION_M0.md`.

---

## M1 — Découverte des projets et orchestration

État : **TERMINÉ ET LIVRÉ**

Acquis : registre projets/workspaces, découverte langages/builds/modules/racines, ignores, négociation des indexeurs, lifecycle et promotion atomique.

---

## M2 — Intelligence des symboles

État : **TERMINÉ ET LIVRÉ**

Acquis : symboles normalisés, identités stables, emplacements, externes/non résolus, recherche et snapshots persistants.

Décision : `m2/DECISION_M2.md`.

---

## M3 — Intelligence des relations

État : **TERMINÉ ET LIVRÉ**

Acquis : références, implémentations, héritage, appels lorsqu’ils existent, dépendances dérivées, provenance, preuves, confiance et requêtes directionnelles.

Décision : `m3/DECISION_M3.md`.

---

## M4 — Recherche et contexte compact

État : **TERMINÉ ET LIVRÉ**

Acquis : recherche structurée, sorties compactes, bornes résultats/tokens/profondeur, extraits pertinents et benchmark de latence.

Décision : `m4/DECISION_M4.md`.

---

## M5 — Tests liés et dérivations explicables

État : **TERMINÉ ET LIVRÉ**

Acquis : `RELATED_TEST`, signaux de nommage/référence/appel/proximité, score, raisons et preuves structurées.

Décision : `m5/DECISION_M5.md`.

---

## M6 — Intelligence d’architecture

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Acquis : topologie modules/namespaces, dépendances inter-modules, concentration, centralité relative directionnelle, technologies factuelles et contexte de module.

Porte : **162/162 tests PASS**.

Décision : `m6/DECISION_M6.md`.

---

## M7 — Indexation incrémentale

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

```text
merge         c66382705880158b9ccac63b5662b81bf2d8d255
tests         196/196 PASS
BUILD SUCCESS
```

Acquis : fingerprints, ChangeSet, snapshots d’empreintes, invalidation conservatrice, capacité incrémentale explicite, plans `NONE/INCREMENTAL/FULL` et fallback complet.

Porte : **OUI, sous preuve explicite de capacité fournisseur.**

Décision : `m7/DECISION_M7.md`.

---

## M8 — Analyse d’impact

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

```text
head validé   08bbdeab18873a2209f02b58bc8d7e547443ea0f
merge         8147db5c246c7bad92c9b6ab21be81084dc64f59
tests         203/203 PASS
```

Acquis : impact direct/indirect, chemins explicatifs, confiance conservatrice, profondeur/résultats bornés, cycles, tests potentiellement impactés et limitations runtime explicites.

Porte :

> **OUI, comme estimation potentielle fondée sur le graphe observé — jamais comme preuve d’exhaustivité runtime.**

Décision : `m8/DECISION_M8.md`.

---

## M9 — CLI stabilisée

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

```text
head validé   ae82f24897ea925f04f450f793541b39d13b6d47
merge         22afe31339dc3a75dc51c491a725330c6d433ecc
tests         207/207 PASS
```

Acquis : administration du registre, import SCIP explicite, statut d’index, recherche/symboles/relations/tests liés, architecture, impact, formats `text/json`, codes de sortie stables et replay end-to-end.

Frontière : aucun runner absent n’est simulé par `minos index`.

Décision : `m9/DECISION_M9.md`.

---

## M10 — Serveur MCP

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

```text
head validé   3f3657a6e5c1a783993348c892f97138d990feff
merge         eb042852a936ad2e62e337ee35ed8a349096e794
tests         210/210 PASS
```

Acquis : serveur MCP STDIO Java officiel 2.0.0, 15 tools read-only, schemas bornés, erreurs structurées et shaded JAR.

Décision : `m10/DECISION_M10.md`.

---

## M11 — API publique

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

```text
head validé   fae552e8e6f2aa66c327fb80485f5bad448d7520
merge         3780785f167cf373dfe0e9cf34f3c3862e87b868
tests         214/214 PASS
```

Contrat :

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

Surface : projets, import SCIP, symboles, usages, relations, architecture, contexte module et impact.

Décision : `m11/DECISION_M11.md`.

---

## M12 — Multi-dépôts et intelligence Git

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

```text
head validé   6c771909e0b97b49fbd8e49090522d8a6c0b53aa
merge         3bc6cc364b6d7d651c1c9ab3a93ecac28ce02e86
tests         221/221 PASS
```

Acquis : workspaces publics, inspection Git via JGit, historique borné, activité fichiers/auteurs/zones, résolution cross-repository exacte et contrat M12 additif.

Porte :

> **OUI, à condition de séparer strictement les faits Git des signaux architecturaux et de ne promouvoir une relation cross-repository que sur une identité fournisseur exacte, unique et traçable.**

Documents : `m12/MULTI_REPO_GIT.md`, `m12/DECISION_M12.md`.

---

## M13 — Intégration NEXUS

État : **IMPLÉMENTÉ — QUALIFICATION INTER-DÉPÔT EN COURS**

Suivi MINOS : issue #37 / PR #38.

Compagnon NEXUS : `FTurleque/nexus-context-engine` issue #11 / PR #12.

### Objectif

Permettre à NEXUS de consommer les faits MINOS sans couplage Java direct et sans déplacer le ranking, la sélection ou le budget de contexte dans MINOS.

### Décision technique actuelle

```text
MINOS Java 24
  NexusExportContract v1
  nexus-export --root <project>
        |
        | JSON stdout
        v
NEXUS Java 21
  minos-import <project> < stdin
        |
        v
  IndexRepository -> SearchService -> ranking -> ContextBuilder
```

Le shell, l’IDE, JARVIS ou un script orchestre l’échange. NEXUS ne doit pas lancer MINOS depuis son cœur M13.

### Surface MINOS

```text
NexusExportContract
NexusExportService
NexusExportCommand
```

L’export reconstruit les `fileId` SCIP stables vers des chemins relatifs sûrs et conserve provenance, nature, confiance, preuves et limitations.

### Surface NEXUS compagnon

Le compagnon M13 fournit un adaptateur JSON pur et une importation explicite. Les faits persistés conservent :

```text
sourceProvider = minos
```

Les kinds/relations sans équivalence explicite sont ignorés plutôt que reclassés.

### Qualification

Replay MINOS attendu :

```text
M13 MINOS export: contract=1, project=<uuid>, snapshot=<snapshot>, symbols=<n>, relations=<n>
```

Replay inter-dépôt attendu :

```text
M13 MINOS->NEXUS: symbols=<n>, relations=<n>, nexus-symbols=<n>, search=<n>
M13 MINOS -> NEXUS replay SUCCESS
```

La preuve finale doit vérifier `GreetingPort` dans NEXUS avec `sourceProvider=minos` puis dans les résultats de `SearchService`.

### Porte finale M13

1. validation Java 24 du head exact MINOS ;
2. validation Java 21 du head exact NEXUS ;
3. replay réel Java 24 → JSON → Java 21 sur ces heads.

Verdict préparé :

> **OUI, via un contrat JSON local versionné et un import NEXUS explicite : MINOS reste la source de faits de Code Intelligence, tandis que NEXUS reste seul responsable du classement, de la sélection et du budget du contexte.**

Documents : `m13/NEXUS_INTEGRATION.md`, `m13/DECISION_M13.md`.

---

## Explorations futures

Non engagées dans la roadmap principale : Code Property Graph, flux de données, sécurité avancée, recherche sémantique, embeddings, plugins IDE, indexation distante GitHub/GitLab, indexation distribuée et mode service hébergé.
