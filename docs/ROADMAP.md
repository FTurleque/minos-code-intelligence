# Feuille de route — MINOS

Statut : **C0 à M12 terminés et livrés — M13 implémenté, validations finales inter-dépôts en attente**

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

```text
merge         c66382705880158b9ccac63b5662b81bf2d8d255
sources       134 main / 69 test
tests         196/196 PASS
BUILD SUCCESS
```

Acquis : fingerprints, ChangeSet, snapshots d’empreintes, invalidation conservatrice, capacité incrémentale explicite, plan `NONE/INCREMENTAL/FULL`, fallback complet et baseline conditionnée à la stabilité du workspace.

Porte M7 : **OUI, sous preuve explicite de capacité fournisseur.**

Décision : `m7/DECISION_M7.md`.

---

## M8 — Analyse d’impact

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

```text
head validé   08bbdeab18873a2209f02b58bc8d7e547443ea0f
merge         8147db5c246c7bad92c9b6ab21be81084dc64f59
sources       143 main / 72 test
tests         203/203 PASS
```

Acquis : impact direct/indirect, chemins explicatifs, confiance conservatrice, profondeur/résultats bornés, cycles, tests potentiellement impactés et limites runtime explicites.

Replay :

```text
M8 typescript-modules impact: root=GreetingPort, impacts=2, tests=1, max-depth=2, limitations=[DYNAMIC_DISPATCH_NOT_PROVEN, REFLECTION_NOT_PROVEN, RUNTIME_CONFIGURATION_NOT_PROVEN]
```

Porte M8 :

> **OUI, comme estimation potentielle fondée sur le graphe observé — jamais comme preuve d’exhaustivité runtime.**

Décision : `m8/DECISION_M8.md`.

---

## M9 — CLI stabilisée

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

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

```text
head validé   3f3657a6e5c1a783993348c892f97138d990feff
merge         eb042852a936ad2e62e337ee35ed8a349096e794
sources       152 main / 77 test
tests         210/210 PASS
BUILD SUCCESS
```

Acquis : serveur MCP STDIO Java officiel 2.0.0, 15 tools read-only, JSON Schemas bornés, erreurs structurées, stdout réservé au protocole et shaded JAR.

Porte M10 :

> **OUI, via un serveur MCP STDIO read-only qui délègue à la surface MINOS existante et conserve les mêmes bornes, preuves et limitations.**

Décision : `m10/DECISION_M10.md`.

---

## M11 — API publique

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Issue #33 clôturée. PR #34 fusionnée.

```text
head validé   fae552e8e6f2aa66c327fb80485f5bad448d7520
merge         3780785f167cf373dfe0e9cf34f3c3862e87b868
sources       154 main / 79 test
tests         214/214 PASS
BUILD SUCCESS
```

Contrat :

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

Surface : projets, import SCIP explicite, symboles, usages, relations, architecture, contexte module et impact.

Replay :

```text
M11 public API: version=1, project=<uuid>, snapshot=scip-7f41649a3cdad442a3235c0a, modules=3, impact=2, tests=1
```

Porte M11 :

> **OUI, via un contrat Java local versionné dont les DTO publics restent indépendants des fournisseurs, protocoles et modèles internes, tout en déléguant l’intelligence au cœur MINOS existant.**

Documents : `m11/API.md`, `m11/DECISION_M11.md`.

---

## M12 — Multi-dépôts et intelligence Git

État : **TERMINÉ, VALIDÉ ET LIVRÉ**

Issue #35 clôturée. PR #36 fusionnée.

```text
head validé   6c771909e0b97b49fbd8e49090522d8a6c0b53aa
merge         3bc6cc364b6d7d651c1c9ab3a93ecac28ce02e86
sources       158 main / 83 test
tests         221/221 PASS
BUILD SUCCESS
```

Acquis :

```text
workspaces publics
Git repository inspection via JGit
historique borné et changements récents
activité fichiers / auteurs / zones
résolution cross-repository exacte et unique
contrat public M12 additif
limitations explicites
```

Runtime : `org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r`.

Replay :

```text
M12 multi-repo Git: workspace=<uuid>, projects=1, git-commits=1, files=1, exact-cross-repo=0
```

Porte M12 :

> **OUI, à condition de séparer strictement les faits Git des signaux architecturaux et de ne promouvoir une relation cross-repository que sur une identité fournisseur exacte, unique et traçable.**

Documents : `m12/MULTI_REPO_GIT.md`, `m12/DECISION_M12.md`.

---

## M13 — Intégration NEXUS

État : **INTÉGRALEMENT IMPLÉMENTÉ — VALIDATIONS FINALES INTER-DÉPÔTS EN ATTENTE**

Suivi MINOS : issue #37. PR Draft #38.

Compagnon NEXUS : `FTurleque/nexus-context-engine` issue #11 / PR Draft #12.

### Objectif

Permettre à NEXUS de consommer la Code Intelligence de MINOS pour sélectionner un contexte adapté à une tâche, sans fusionner les responsabilités des deux moteurs.

### Frontière

- MINOS fournit faits, relations, provenance, preuves et limitations ;
- NEXUS classe, sélectionne et respecte le budget de contexte ;
- aucun type NEXUS dans MINOS ;
- aucun type MINOS dans NEXUS ;
- aucun réseau requis ;
- aucune dépendance Maven croisée ;
- MINOS reste utilisable sans NEXUS ;
- NEXUS reste utilisable sans MINOS.

### Décision technique

NEXUS reste Java 21 et MINOS Java 24. Le pont est donc inter-processus :

```text
MINOS Java 24
  NexusExportContract v1
  nexus-export --root <project>
        |
        | JSON local
        v
NEXUS Java 21
  MinosCodeIndexImporter opt-in
        |
        v
  SQLite -> SearchService -> ranking -> ContextBuilder
```

### Surface MINOS M13

```text
NexusExportContract
NexusExportService
NexusExportCommand
```

Le service reconstruit aussi les `fileId` SCIP stables :

```text
file:<sha256(projectId + US + relativePath)>
```

vers des chemins relatifs réels et sûrs sous le projet.

### Surface NEXUS compagnon

```text
MinosCodeIndexImporter
NEXUS_MINOS_JAR
NEXUS_MINOS_JAVA
NEXUS_MINOS_HOME
NEXUS_MINOS_TIMEOUT_SECONDS
```

L’importer est désactivé par défaut, valide le contrat/root, borne le processus, conserve `sourceProvider=minos`, mappe seulement les kinds explicitement compatibles et reste placé avant SCIP direct.

Aucune modification du ranking ou du `DefaultContextBuilder`.

### Qualification

MINOS :

```text
NexusExportContractTest
NexusExportIntegrationTest
```

NEXUS :

```text
MinosCodeIndexImporterTest
FakeMinosExportMain
MinosRealIntegrationTest (opt-in)
```

Replay MINOS attendu :

```text
M13 MINOS export: contract=1, project=<uuid>, snapshot=<snapshot>, symbols=<n>, relations=<n>
```

Replay inter-dépôt attendu :

```text
M13 MINOS->NEXUS: symbols=<n>, relations=<n>, nexus-symbols=<n>, search=<n>
```

La preuve finale doit vérifier `GreetingPort` dans NEXUS avec `sourceProvider=minos` puis dans les résultats de recherche NEXUS.

### Porte finale M13

1. `./mvnw clean verify` Java 24 sur le head exact MINOS ;
2. validation NEXUS Java 21 sur son head exact ;
3. replay réel `MinosRealIntegrationTest` avec le shaded JAR du head MINOS qualifié.

Verdict préparé :

> **OUI, via un contrat JSON local versionné et un importer NEXUS optionnel : MINOS reste la source de faits de Code Intelligence, tandis que NEXUS reste seul responsable du classement, de la sélection et du budget du contexte.**

Documents : `m13/NEXUS_INTEGRATION.md`, `m13/DECISION_M13.md`.

---

## Explorations futures

Non engagées dans la roadmap principale : Code Property Graph, flux de données, sécurité, recherche sémantique, embeddings, plugins IDE, indexation distante GitHub/GitLab, indexation distribuée et mode service hébergé.
