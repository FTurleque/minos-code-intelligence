# Feuille de route — MINOS

Statut : **C0 à M10 livrés — M11 validé, fusion en attente — M12 implémenté, porte finale en attente**

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

Suivi clôturé : issue #27. PR finale : #28.

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

```text
head validé   3f3657a6e5c1a783993348c892f97138d990feff
merge         eb042852a936ad2e62e337ee35ed8a349096e794
sources       152 main / 77 test
tests         210/210 PASS
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

Porte M10 :

> **OUI, via un serveur MCP STDIO read-only qui délègue à la surface MINOS existante et conserve les mêmes bornes, preuves et limitations.**

Décision : `m10/DECISION_M10.md`.

---

## M11 — API publique

État : **VALIDÉ — FUSION EN ATTENTE D’AUTORISATION EXPLICITE**

Suivi : issue #33. PR #34 **Ready for review**.

Branche :

```text
m11/public-api
```

Head exact validé :

```text
fae552e8e6f2aa66c327fb80485f5bad448d7520
```

Porte acquise le 24 juillet 2026 sous Java 24 :

```text
154 sources main
79 sources test
214/214 tests PASS
0 failures
0 errors
0 skipped
BUILD SUCCESS
```

### Objectif

Permettre à des systèmes externes de consommer MINOS via des DTO stables sans dépendre de Glean, SCIP, des adaptateurs internes, du stockage, de la CLI ou du protocole MCP.

### Contrat public

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

Surface : projets, index SCIP explicite, symboles, usages, relations, architecture, contexte module et impact.

Le champ `SymbolDto.language` conserve l’identifiant lexical normalisé (`typescript`, `java`, etc.), distinct des noms d’enums d’architecture/découverte (`TYPESCRIPT`, `NPM`, etc.).

Replay acquis :

```text
M11 public API: version=1, project=<uuid>, snapshot=scip-7f41649a3cdad442a3235c0a, modules=3, impact=2, tests=1
```

Porte M11 :

> **OUI, via un contrat Java local versionné dont les DTO publics restent indépendants des fournisseurs, protocoles et modèles internes, tout en déléguant l’intelligence au cœur MINOS existant.**

Documents : `m11/API.md`, `m11/DECISION_M11.md`.

La fusion de #34 reste explicitement hors automatisme et nécessite une autorisation utilisateur.

---

## M12 — Multi-dépôts et intelligence Git

État : **INTÉGRALEMENT IMPLÉMENTÉ — VALIDATION LOCALE FINALE EN ATTENTE**

Suivi : issue #35.

PR Draft empilée : #36.

Branche :

```text
m12/multi-repo-git
```

Base fonctionnelle : head M11 validé `fae552e8e6f2aa66c327fb80485f5bad448d7520`.

La PR #36 cible temporairement `m11/public-api`. Après fusion explicitement autorisée de #34, elle devra être retargetée sur `main` avant livraison finale.

### Objectif

Permettre à MINOS de raisonner factuellement sur plusieurs dépôts d’un même workspace et d’exposer l’activité Git locale sans confondre fréquence de modification et importance architecturale.

### Runtime Git

```text
org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r
```

Lecture Java pure ; aucune commande Git native n’est requise au runtime M12.

### Contrat public additif

Le contrat M11 reste inchangé. M12 ajoute :

```text
com.minos.api.MinosMultiRepositoryApi
com.minos.api.LocalMinosMultiRepositoryApi
MULTI_REPOSITORY_CONTRACT_VERSION = 1
```

### Surface implémentée

```text
workspaces : create / list / get / assign project
Git repository inspection
Git bounded activity
recent commits + changed paths
file modification frequency
unique authors per file
last change / last commit
activity zones
workspace intelligence
cross-repository relationships
explicit limitations
```

### Résolution cross-repository

Une relation non résolue n’est promue que sur correspondance **exacte et unique** :

```text
relationship.origin.providerId
+
relationship.unresolvedTarget
==
localSymbol.providerReference.providerId
+
localSymbol.providerReference.externalId
```

La cible doit être locale, appartenir à un autre projet du même workspace et être unique.

Aucune résolution n’est effectuée par nom, `qualifiedName`, chemin, proximité temporelle ou fréquence Git.

La résolution est une vue dérivée : les snapshots M2/M3 ne sont pas réécrits.

### Bornes

```text
Git maxCommits          1..10000
Git maxFiles            1..10000
Git zoneDepth           1..8
Workspace relationships 1..10000
```

### Qualification ajoutée

```text
MinosMultiRepositoryApiContractTest
GitIntelligenceServiceTest
WorkspaceIntelligenceServiceTest
LocalMinosMultiRepositoryApiIntegrationTest
```

Preuves visées :

- contrat public sans fuite interne/JGit ;
- dépôt Git synthétique avec 2 commits / 2 auteurs ;
- activité fichier et zone ;
- résolution cross-repository exacte ;
- refus d’une cible name-only ;
- limitation explicite pour projet sans snapshot ;
- erreur publique `INVALID_REQUEST` pour requête nulle.

Replay attendu :

```text
M12 multi-repo Git: workspace=<uuid>, projects=1, git-commits=1, files=1, exact-cross-repo=0
```

### Porte de décision M12

> MINOS peut-il raisonner factuellement sur plusieurs dépôts d’un même workspace et enrichir la Code Intelligence avec l’historique Git, sans inventer de relations inter-dépôts ni confondre activité Git et importance architecturale ?

Verdict préparé :

> **OUI, à condition de séparer strictement les faits Git des signaux architecturaux et de ne promouvoir une relation cross-repository que sur une identité fournisseur exacte, unique et traçable.**

Documents : `m12/MULTI_REPO_GIT.md`, `m12/DECISION_M12.md`.

Porte locale finale :

```powershell
.\mvnw.cmd clean verify
```

Volumes attendus :

```text
158 sources main
83 sources test
221 tests
```

Ces volumes restent à confirmer sur le **head exact final M12**.

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
