# Feuille de route — MINOS

Statut : **C0 à M18 terminés, validés et livrés ; M19 à M20 planifiés.**

L'état courant livré est résumé dans [`STATUS.md`](STATUS.md). Les décisions architecturales durables sont dans [`adr/`](adr/README.md). Les preuves historiques restent sous [`history/milestones/`](history/milestones/README.md).

La trajectoire M15 à M20 est détaillée dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md).

## Principes

- chaque jalon ferme une question produit identifiable ;
- une capacité n'est acquise qu'avec une preuve reproductible ;
- un nouveau commit invalide la qualification exacte d'un SHA antérieur ;
- CLI, API, MCP et NEXUS ne dupliquent pas le métier ;
- les décisions durables sont formalisées en ADR ;
- les optimisations et choix de backend sont gouvernés par des mesures ;
- les capacités provider absentes ne sont jamais inventées ;
- les facts documentaires calculables sont dérivés du code quand c'est possible.

---

## C0 — Cadrage fonctionnel et architectural

**TERMINÉ.** Définition du rôle de MINOS, de ses frontières et de sa place dans l'écosystème.

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

---

## M2 — Intelligence des symboles

**TERMINÉ ET LIVRÉ.**

Acquis : symboles normalisés, identités stables qualifiées, emplacements, externes/non résolus, recherche et snapshots persistants.

- décision : [ADR-0009](adr/0009-normalized-symbol-identity.md)

---

## M3 — Intelligence des relations

**TERMINÉ ET LIVRÉ.**

Acquis : références, implémentations, appels lorsqu'ils existent, dépendances dérivées, provenance, preuves, confiance et requêtes directionnelles.

- décision : [ADR-0010](adr/0010-normalized-relationship-semantics.md)

---

## M4 — Recherche et contexte compact

**TERMINÉ ET LIVRÉ.**

Acquis : recherche structurée, sorties compactes, bornes résultats/tokens/profondeur, extraits pertinents et récupération explicite de source complète.

---

## M5 — Tests liés et dérivations explicables

**TERMINÉ ET LIVRÉ.**

Acquis : `RELATED_TEST`, signaux de nommage/référence/appel/proximité, score, raisons et preuves structurées.

---

## M6 — Intelligence d'architecture

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : topologie modules/namespaces, dépendances inter-modules, concentration, centralité relative, technologies factuelles et contexte de module.

---

## M7 — Indexation incrémentale

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : fingerprints, ChangeSet, snapshots d'empreintes, invalidation conservatrice, plans `NONE/INCREMENTAL/FULL` et fallback complet.

Porte : **incrémental uniquement sous preuve explicite de capacité fournisseur**.

---

## M8 — Analyse d'impact

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : impact direct/indirect, chemins explicatifs, confiance conservatrice, profondeur/résultats bornés, cycles, tests potentiellement impactés et limitations runtime.

---

## M9 — CLI stabilisée

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : administration, import SCIP explicite, statut, recherche/symboles/relations/tests liés, architecture, impact, formats structurés et codes de sortie stables.

---

## M10 — Serveur MCP

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : serveur MCP STDIO Java officiel, tools read-only, schémas bornés, erreurs structurées et shaded JAR. Le catalogue courant exact est généré dans [`generated/product-facts.md`](generated/product-facts.md).

---

## M11 — API publique

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Contrat public versionné autour de `MinosApi` et `LocalMinosApi`, sans exposition de SCIP, du stockage ou des modèles internes.

---

## M12 — Multi-dépôts et intelligence Git

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : workspaces publics, JGit, activité Git bornée, résolution cross-repository exacte et contrat public additif.

---

## M13 — Intégration NEXUS

**TERMINÉ, VALIDÉ ET LIVRÉ.**

MINOS Java 24 exporte un contrat JSON v1 local ; NEXUS l'importe explicitement. MINOS reste propriétaire des faits de Code Intelligence et NEXUS du ranking, de la sélection et du budget de contexte.

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

Acquis : discovery, négociation provider, diagnostic runtime, fingerprints, exécution, normalisation, staging/promotion, distribution Windows native, runtime Java embarqué, installation CLI/MCP et release explicite.

- roadmap opérationnelle : [`roadmap/M14_EXECUTION.md`](roadmap/M14_EXECUTION.md)
- décision : [ADR-0021](adr/0021-native-runtime-autonomous-indexing.md)

---

# Nouvelle phase — Industrialisation et complétion de MINOS

```text
M15  Industrialiser le Core Engine             ✅
  ↓
M16  Prouver la scalabilité                    ✅
  ↓
M17  Généraliser discovery + providers         ✅
  ↓
M18  Intégrer MINOS à IntelliJ                 ✅
  ↓
M19  Intelligence de programme avancée         planifié
  ↓
M20  Recherche sémantique hybride              planifié
```

## M15 — Industrialisation du Core Engine

**TERMINÉ, VALIDÉ ET LIVRÉ — 11/11 sous-incréments.**

Objectif fermé : transformer le socle M14 en plateforme modulaire et durable sans régression fonctionnelle volontaire.

```text
M15-S1   baseline de non-régression              ✅ PR #56
M15-S2   Maven multi-module                      ✅ PR #57
M15-S3   MinosApplication                        ✅ PR #58
M15-S4   découplage MCP                          ✅ PR #59
M15-S5   résolution projet commune               ✅ PR #60
M15-S6   persistance décomposée                  ✅ PR #61
M15-S7   cache snapshot actif                    ✅ PR #62
M15-S8   indexes de requête                      ✅ PR #62
M15-S9   JaCoCo / qualité continue               ✅ PR #62
M15-S10  CI automatique de PR                    ✅ PR #62
M15-S11  cohérence documentaire                  ✅ PR #62
```

Acquis structurants : reactor Maven multi-module, `MinosApplication`, MCP découplé de la CLI métier, résolution projet commune, persistance décomposée, cache snapshot actif, indexes reconstruisibles, JaCoCo/CI/facts calculables.

- roadmap opérationnelle : [`roadmap/M15_EXECUTION.md`](roadmap/M15_EXECUTION.md)
- décisions : ADR-0022 à ADR-0024
- issue : #55
- PR finale : #62

---

## M16 — Scalabilité et performance à grande échelle

**TERMINÉ, VALIDÉ ET LIVRÉ — 9/9 sous-incréments.**

Objectif fermé : mesurer le backend industrialisé par M15 sur un dataset STANDARD reproductible, protéger les séquences MCP et l'indexation réelle, puis borner la croissance disque sans introduire un backend complexe sans preuve.

```text
M16-S1   harness benchmark                       ✅
M16-S2   datasets d'échelle                      ✅
M16-S3   query benchmark                         ✅
M16-S4   MCP sustained load                      ✅
M16-S5   indexing benchmark                      ✅
M16-S6   memory/disk profile                     ✅
M16-S7   backend decision                        ✅ ADR-0025
M16-S8   optimisations mesurées uniquement       ✅
M16-S9   retention/compaction                    ✅
```

Acquis : profils `SMOKE/STANDARD/EXTENDED/STRESS`, gate STANDARD 10k fichiers/100k symboles/500k occurrences/250k relations, p50/p95/p99, heap/RSS/disque, MCP long-lived, benchmark FULL/NONE, décision backend mesurée et rétention bornée.

- roadmap opérationnelle : [`roadmap/M16_EXECUTION.md`](roadmap/M16_EXECUTION.md)
- décision : [ADR-0025](adr/0025-measurement-gated-storage-backend-evolution.md)
- issue : #63
- PR finale : #64

---

## M17 — Provider & Discovery Platform

**TERMINÉ, VALIDÉ ET LIVRÉ — 9/9 sous-incréments.**

Objectif fermé : rendre discovery, providers et installation runtime extensibles sans branches d'écosystème dans les orchestrateurs centraux, puis qualifier de nouveaux écosystèmes sur les mêmes contrats MINOS.

```text
M17-S1   Discovery SPI                            ✅
M17-S2   Provider SPI                             ✅
M17-S3   Capability model v2                      ✅
M17-S4   Gradle                                   ✅
M17-S5   npm/pnpm/yarn workspaces                 ✅
M17-S6   Kotlin                                   ✅
M17-S7   Python / scip-python 0.6.6               ✅
M17-S8   Provider conformance kit                 ✅
M17-S9   Installation provider extensible         ✅
```

Acquis :

- `ProjectDetector`, `BuildSystemDetector`, `SourceRootDetector`, `LanguageDetector` composables ;
- `IndexerProvider` + registre d'extensions ;
- profils exhaustifs `FULL/PARTIAL/EXPERIMENTAL/UNSUPPORTED` ;
- découverte Gradle Java/Kotlin et workspaces npm/pnpm/yarn ;
- Kotlin/Maven négocié par `scip-java` ;
- Python géré par `scip-python` `0.6.6`, installé sous `MINOS_HOME/tools` ;
- `ProviderConformanceKit` déterministe ;
- `CompositeProviderRuntimeManager` sans logique provider dans CLI/doctor/index ;
- limitations visibles dans CLI, `ProviderPlatformApi` et diagnostics MCP ;
- `MinosApi` v1 et le catalogue historique de 16 tools MCP restent stables.

Porte finale : `scripts/m17/run-final.ps1` rejoue la qualification M14 complète, puis exige installation Python READY et indexation/requêtes end-to-end Python et Kotlin sur le SHA exact.

- roadmap opérationnelle : [`roadmap/M17_EXECUTION.md`](roadmap/M17_EXECUTION.md)
- décision : [ADR-0026](adr/0026-discovery-provider-spi-and-explicit-capability-profiles.md)
- issue : #65

---

## M18 — MINOS for IntelliJ

**TERMINÉ, VALIDÉ ET LIVRÉ — 9/9 sous-incréments.**

Objectif fermé : exploiter les capacités MINOS directement dans IntelliJ sans dépendre d'un agent IA, via un plugin autonome Java 21 qui reste client du moteur MINOS Java 24 et ne duplique pas l'intelligence métier.

```text
M18-S1   Contrat IDE / handshake v1               ✅
M18-S2   Plugin bootstrap                          ✅
M18-S3   Project status                            ✅
M18-S4   Navigation symboles                       ✅
M18-S5   Architecture graph                        ✅
M18-S6   Impact + related tests                    ✅
M18-S7   Index lifecycle                           ✅
M18-S8   Git intelligence factuelle                ✅
M18-S9   Packaging / Plugin Verifier               ✅
```

Acquis : protocole `minos-ide` v1 stateless au handshake, Tool Window et settings projet, navigation définitions/usages/dependents/implementations, conversion UTF-8/16/32 vers offsets IntelliJ, graphe d'architecture borné/filtrable, impact/tests explicables, index/reindex/doctor hors EDT via lifecycle MINOS, activité Git factuelle, ZIP plugin et workflows de validation/release.

Qualification finale Windows exact-head :

```text
Validated HEAD: 0186146668c12027f44b55d0511a45e89e6dee61
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
Plugin Verifier: Compatible with IntelliJ IDEA 2026.1 (IU-261.22158.277)
```

Merge final : `faa51f63c5967d874a7a6685b6b513b83bb736b4`.

- roadmap opérationnelle : [`roadmap/M18_EXECUTION.md`](roadmap/M18_EXECUTION.md)
- décision : [ADR-0027](adr/0027-intellij-external-client-and-versioned-cli-protocol.md)
- issue : #67
- PR finale : #68

---

## M19 — Advanced Code Intelligence

**PLANIFIÉ — PROCHAIN JALON.**

Objectif : ajouter progressivement les analyses de programme avancées lorsque leur coût, leur provenance et leurs limites peuvent être explicités.

---

## M20 — Recherche sémantique hybride

**PLANIFIÉ.**

Objectif : combiner recherche sémantique et faits structurés MINOS sans remplacer les identités, relations et preuves déterministes.

---

## Règle de progression

M19 est le prochain jalon séquentiel. M20 peut être exploré, mais sa promotion produit reste soumise aux garanties de scalabilité, de qualité, d'extensibilité et d'intégration IDE acquises jusqu'à M18.
