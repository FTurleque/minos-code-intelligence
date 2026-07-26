# Feuille de route — MINOS

Statut : **C0 à M14 terminés, validés et livrés ; M15 démarré — 2/11 ; M16 à M20 planifiés.**

L’état courant livré reste résumé dans [`STATUS.md`](STATUS.md). Les décisions architecturales durables sont dans [`adr/`](adr/README.md). Les preuves détaillées des jalons terminés sont archivées sous [`history/milestones/`](history/milestones/README.md).

La trajectoire détaillée de la nouvelle phase produit est décrite dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md).

## Principes de roadmap

- chaque jalon ferme une question produit identifiable ;
- une capacité n’est acquise qu’avec une preuve reproductible ;
- les limitations fournisseur restent explicites ;
- un nouveau commit invalide la validation exacte d’un SHA précédent ;
- les surfaces CLI/API/MCP/NEXUS ne dupliquent pas le métier ;
- les décisions durables sont formalisées en ADR ;
- les logs, mesures et validations historiques restent dans les archives de jalon ;
- les optimisations et choix de backend sont gouvernés par des mesures ;
- la recherche sémantique et les heuristiques complètent les faits MINOS sans les remplacer.

---

## C0 — Cadrage fonctionnel et architectural

**TERMINÉ.** Définition du rôle de MINOS, de ses frontières et de sa place dans l’écosystème.

---

## M0 — Faisabilité technique

**TERMINÉ ET LIVRÉ — ADOPTER_AVEC_CONTRAINTES.**

Acquis : qualification SCIP Java/TypeScript, baseline SCIP → MINOS, backend local léger, Glean optionnel et frontière fournisseur.

- historique : [`history/milestones/m0/`](history/milestones/m0/)
- décisions : ADR-0001 à ADR-0005, ADR-0008

---

## M1 — Découverte des projets et orchestration

**TERMINÉ ET LIVRÉ.**

Acquis : registre projets/workspaces, découverte langages/builds/modules/racines, ignores, négociation des indexeurs, lifecycle et promotion atomique.

- historique : [`history/milestones/m1/`](history/milestones/m1/)
- décisions : ADR-0006 à ADR-0008

---

## M2 — Intelligence des symboles

**TERMINÉ ET LIVRÉ.**

Acquis : symboles normalisés, identités stables qualifiées, emplacements, externes/non résolus, recherche et snapshots persistants.

- historique : [`history/milestones/m2/`](history/milestones/m2/)
- décision : [ADR-0009](adr/0009-normalized-symbol-identity.md)

---

## M3 — Intelligence des relations

**TERMINÉ ET LIVRÉ.**

Acquis : références, implémentations, appels lorsqu’ils existent, dépendances dérivées, provenance, preuves, confiance et requêtes directionnelles.

- historique : [`history/milestones/m3/`](history/milestones/m3/)
- décision : [ADR-0010](adr/0010-normalized-relationship-semantics.md)

---

## M4 — Recherche et contexte compact

**TERMINÉ ET LIVRÉ.**

Acquis : recherche structurée, sorties compactes, bornes résultats/tokens/profondeur, extraits pertinents et récupération explicite de source complète.

- historique : [`history/milestones/m4/`](history/milestones/m4/)
- décision : [ADR-0011](adr/0011-bounded-code-search-context.md)

---

## M5 — Tests liés et dérivations explicables

**TERMINÉ ET LIVRÉ.**

Acquis : `RELATED_TEST`, signaux de nommage/référence/appel/proximité, score, raisons et preuves structurées.

- historique : [`history/milestones/m5/`](history/milestones/m5/)
- décision : [ADR-0012](adr/0012-explainable-related-tests.md)

---

## M6 — Intelligence d’architecture

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : topologie modules/namespaces, dépendances inter-modules, concentration, centralité relative directionnelle, technologies factuelles et contexte de module.

- historique : [`history/milestones/m6/`](history/milestones/m6/)
- décision : [ADR-0013](adr/0013-factual-architecture-intelligence.md)

---

## M7 — Indexation incrémentale

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : fingerprints, ChangeSet, snapshots d’empreintes, invalidation conservatrice, plans `NONE/INCREMENTAL/FULL` et fallback complet.

Porte : **incrémental uniquement sous preuve explicite de capacité fournisseur**.

- historique : [`history/milestones/m7/`](history/milestones/m7/)
- décision : [ADR-0014](adr/0014-safe-incremental-indexing.md)

---

## M8 — Analyse d’impact

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : impact direct/indirect, chemins explicatifs, confiance conservatrice, profondeur/résultats bornés, cycles, tests potentiellement impactés et limitations runtime.

Porte : **estimation potentielle fondée sur le graphe observé, jamais preuve d’exhaustivité runtime**.

- historique : [`history/milestones/m8/`](history/milestones/m8/)
- décision : [ADR-0015](adr/0015-conservative-impact-analysis.md)

---

## M9 — CLI stabilisée

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis M9 : administration, import SCIP explicite, statut, recherche/symboles/relations/tests liés, architecture, impact, formats `text/json` et codes de sortie stables.

La frontière M9 « ne pas simuler un runner absent » reste historiquement correcte ; M14 ajoute désormais le runner réel derrière les ports prévus.

- historique : [`history/milestones/m9/`](history/milestones/m9/)
- décision : [ADR-0016](adr/0016-stable-cli-contract.md)

---

## M10 — Serveur MCP

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis M10 : serveur MCP STDIO Java officiel, 15 tools read-only, schémas bornés, erreurs structurées et shaded JAR. Les évolutions post-M14 ont porté le catalogue courant à 16 tools avec l’exposition dédiée du graphe d’architecture.

- historique : [`history/milestones/m10/`](history/milestones/m10/)
- décision : [ADR-0017](adr/0017-mcp-stdio-read-only.md)

---

## M11 — API publique

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Contrat public versionné autour de `MinosApi` et `LocalMinosApi`, sans exposition de SCIP, du stockage ou des modèles internes.

- historique : [`history/milestones/m11/`](history/milestones/m11/)
- décision : [ADR-0018](adr/0018-versioned-public-java-api.md)

---

## M12 — Multi-dépôts et intelligence Git

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : workspaces publics, JGit, activité Git bornée, résolution cross-repository exacte et contrat public additif.

Porte : **faits Git séparés des signaux architecturaux ; relation cross-repository uniquement sur identité fournisseur exacte, unique et traçable**.

- historique : [`history/milestones/m12/`](history/milestones/m12/)
- décision : [ADR-0019](adr/0019-cross-repository-identity-and-git-facts.md)

---

## M13 — Intégration NEXUS

**TERMINÉ, VALIDÉ ET LIVRÉ.**

MINOS Java 24 exporte un contrat JSON v1 local ; NEXUS Java 21 l’importe explicitement. MINOS reste propriétaire des faits de Code Intelligence et NEXUS du ranking, de la sélection et du budget de contexte.

Validation finale livrée : MINOS `7c5eda4727cda3d46cab24037e4f1276ff0b4a25`, NEXUS head validé `df61c9c07b5ec3271aba27f54da272b4689fb017`.

- historique : [`history/milestones/m13/`](history/milestones/m13/)
- décision : [ADR-0020](adr/0020-minos-nexus-json-boundary.md)

---

## M14 — Indexation autonome et installation PROD

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Parcours utilisateur livré :

```text
minos doctor
minos tools install <provider>
minos project add <root> --name <project>
minos index <project>
```

MINOS prend désormais en charge découverte, négociation provider, diagnostic runtime, fingerprints, exécution, normalisation, staging et promotion sans demander à l’utilisateur de préparer `index.scip`.

Sous-incréments :

```text
M14-S1  runtime providers + ProcessIndexerExecutor        ✅
M14-S2  provider scip-typescript autonome                 ✅
M14-S3  provider scip-java autonome Windows               ✅
M14-S4  staging projet multi-provider                     ✅
M14-S5  CLI autonome + doctor/tools/import-scip           ✅
M14-S6  distribution Windows native + runtime embarqué    ✅
M14-S7  release/Docker/docs alignés                       ✅
```

Qualification finale enregistrée :

```text
236 tests PASS
ShadedJarSmokeIT PASS
TypeScript FULL → SUCCEEDED → NONE
Java FULL → SUCCEEDED → NONE
Java refresh invalide → STALE avec snapshot précédent conservé
Java recovery --force-full → SUCCEEDED / READY
jpackage + ZIP + SHA-256 PASS
installation vierge PASS
MCP natif handshake PASS
Docker MCP durci PASS
```

La distribution Windows sépare le programme de `MINOS_HOME`, embarque le runtime Java MINOS et conserve Docker comme mode MCP durci optionnel.

La publication utilisateur des artefacts est industrialisée après M14 par :

```text
scripts/release/publish-windows-release.ps1
.github/workflows/release-windows.yml
```

La publication reste volontairement explicite : elle est déclenchée manuellement depuis `main`, vérifie et smoke-teste le ZIP, crée `v<version>` puis attache le ZIP et son SHA-256 à GitHub Releases.

- roadmap opérationnelle : [`roadmap/M14_EXECUTION.md`](roadmap/M14_EXECUTION.md)
- issue : #42
- PR : #43
- décision : [ADR-0021](adr/0021-native-runtime-autonomous-indexing.md)

---

# Nouvelle phase — Industrialisation et complétion de MINOS

**M15 est démarré ; M16 à M20 restent planifiés et non acquis.** Le document détaillé [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md) définit leurs sous-incréments, gates et dépendances.

```text
M15  Industrialiser le Core Engine
  ↓
M16  Prouver la scalabilité
  ↓
M17  Généraliser discovery + providers
  ↓
M18  Intégrer MINOS à IntelliJ
  ↓
M19  Ajouter l'intelligence de programme avancée
  ↓
M20  Ajouter la recherche sémantique hybride
```

M18 peut avancer en parallèle de M17 une fois les contrats M15 stabilisés. M19 et M20 peuvent être explorés plus tôt, mais leur promotion produit dépend des garanties de scalabilité M16.

---

## M15 — Industrialisation du Core Engine

**EN COURS — 2/11 sous-incréments terminés.**

Objectif : transformer le socle M14 en plateforme modulaire et durable sans régression fonctionnelle.

Acquis :

- **M15-S1 ✅** — baseline de non-régression et coût des requêtes répétées, PR #56 ;
- **M15-S2 ✅** — reactor Maven multi-module à 12 modules enfants, sources/tests physiquement relocalisés, frontières imposées par compilation, PR #57 / merge `7b064196b31a0676852a5f7effb552beb396cc8a` ;
- qualification fonctionnelle S2 sur `637402782c29526b926968e0b8b525a2fa6fdc2c` : **238 PASS**, M14 replay, Windows/install/doctor/MCP et ownership PASS.

Acquis encore visés :

- `MinosApplication` comme composition root commun ;
- suppression du chemin MCP → CLI → moteur ;
- résolution projet mutualisée ;
- persistance séparant repository, codecs, active pointer, intégrité et rétention ;
- cache du snapshot actif identifié par `(projectId, snapshotId)` ;
- indexes mémoire dédiés pour symboles, fichiers, occurrences et relations ;
- couverture ciblée par JaCoCo ;
- CI automatique bloquante sur les PR ;
- contrôles de cohérence documentaire.

Porte : **les contrats et replays M14 restent fonctionnellement identiques ; les requêtes répétées ne rechargent plus systématiquement tout le snapshot ; les frontières principales deviennent imposées par le build.**

- roadmap opérationnelle : [`roadmap/M15_EXECUTION.md`](roadmap/M15_EXECUTION.md)
- décision S2 : [ADR-0022](adr/0022-maven-reactor-and-module-boundaries.md)
- issue : #55

Sous-incréments : M15-S1 à M15-S11 dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md#m15--industrialisation-du-core-engine).

---

## M16 — Scalabilité et performance à grande échelle

**PLANIFIÉ.**

Objectif : mesurer puis prouver le comportement de MINOS sur de grands codebases avant de sélectionner un backend plus complexe.

Acquis visés :

- benchmark harness reproductible ;
- datasets synthétiques et réels gradués ;
- p50/p95/p99 sur les requêtes structurantes ;
- profil mémoire/disque ;
- benchmark MCP sous séquences de requêtes ;
- benchmark d'indexation ;
- décision backend par ADR fondée sur mesures ;
- politique de rétention/compaction des snapshots et runs.

Porte : **aucun backend n'est adopté parce qu'il semble plus industriel ; le choix doit démontrer un gain mesurable sur les goulots observés sans dégrader déterminisme ou exactitude.**

Sous-incréments : M16-S1 à M16-S9 dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md#m16--scalabilité-et-performance-à-grande-échelle).

---

## M17 — Provider & Discovery Platform

**PLANIFIÉ.**

Objectif : rendre réellement extensibles les langages, systèmes de build et providers sans introduire de branches spécifiques dans le cœur.

Acquis visés :

- SPI de discovery (`ProjectDetector`, `BuildSystemDetector`, `SourceRootDetector`, `LanguageDetector`) ;
- SPI provider/indexer ;
- modèle de capacités plus fin ;
- provider conformance kit reproductible ;
- support Gradle ;
- qualification des workspaces npm/pnpm/yarn selon disponibilité ;
- qualification d'au moins un nouvel écosystème/langage au-delà du périmètre M14 ;
- installation et diagnostic providers extensibles.

Porte : **ajouter un provider ou un build system ne doit pas nécessiter de modifier le modèle métier MINOS ni un orchestrateur central par branchement spécifique.**

Sous-incréments : M17-S1 à M17-S9 dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md#m17--provider--discovery-platform).

---

## M18 — MINOS for IntelliJ

**PLANIFIÉ.**

Objectif : rendre les capacités MINOS directement exploitables dans IntelliJ sans dépendre d'un agent IA.

Acquis visés :

- plugin IntelliJ versionné ;
- état projet/provider/snapshot ;
- navigation symboles/usages/implémentations ;
- graphe d'architecture interactif ;
- vues impact et related tests avec preuves ;
- déclenchement contrôlé de l'indexation/doctor ;
- intelligence Git ;
- actions contextuelles depuis l'éditeur.

Porte : **le plugin reste un client du moteur ; il ne duplique pas l'intelligence de code et le graphe affiché correspond aux mêmes faits que CLI/API/MCP.**

Sous-incréments : M18-S1 à M18-S9 dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md#m18--minos-for-intellij).

---

## M19 — Advanced Code Intelligence

**PLANIFIÉ.**

Objectif : enrichir le modèle avec structure d'exécution et flux de données afin d'améliorer analyse d'impact et sécurité.

Trajectoire :

```text
Call Graph v2
  ↓
Control Flow Graph
  ↓
Data Flow
  ↓
Code Property Graph
  ↓
Impact v2 / Security Intelligence
```

Acquis visés :

- modèle de graphe de programme provider-independent ;
- call graph enrichi et qualifié ;
- CFG ;
- data-flow local puis interprocédural borné ;
- CPG si son bénéfice aval est démontré ;
- impact v2 ;
- primitives de sécurité/taint explicables ;
- exposition API/MCP versionnée.

Porte : **aucun chemin de flux incomplet n'est présenté comme preuve d'absence ; les capacités sont qualifiées par précision/rappel et conservent provenance, confiance et limitations.**

Sous-incréments : M19-S1 à M19-S9 dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md#m19--advanced-code-intelligence).

---

## M20 — Semantic & Hybrid Code Intelligence

**PLANIFIÉ.**

Objectif : ajouter une recherche par intention/concept et un ranking hybride sans remplacer les faits déterministes MINOS.

Acquis visés :

- modèle de documents sémantiques ;
- SPI d'embeddings locaux ;
- abstraction de vector store reconstructible ;
- recherche sémantique ;
- ranking hybride lexical + graph + semantic ;
- context builder v2 borné ;
- index sémantique incrémental ;
- contrats API/MCP explicites ;
- intégration NEXUS v2 préservant les responsabilités respectives.

Porte : **les embeddings restent optionnels et ne sont jamais présentés comme preuve structurelle ; le ranking hybride doit démontrer un gain mesurable sur une vérité terrain.**

Sous-incréments : M20-S1 à M20-S9 dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md#m20--semantic--hybrid-code-intelligence).

---

## Après M20 — Explorations non engagées

Restent volontairement hors engagement de cette phase :

- indexation distante directe GitHub/GitLab ;
- indexation distribuée ;
- mode service MINOS hébergé ;
- collaboration multi-utilisateur ;
- analyse runtime/dynamique ;
- support massif de langages sans provider qualifié.

Ces thèmes devront chacun disposer d'une question produit, de métriques et d'une décision avant d'entrer dans la roadmap principale.