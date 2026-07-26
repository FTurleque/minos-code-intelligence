# Feuille de route — MINOS

Statut : **C0 à M15 terminés, validés et livrés ; M16 à M20 planifiés.**

L'état courant livré est résumé dans [`STATUS.md`](STATUS.md). Les décisions architecturales durables sont dans [`adr/`](adr/README.md). Les preuves historiques restent sous [`history/milestones/`](history/milestones/README.md).

La trajectoire M15 à M20 est détaillée dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md).

## Principes

- chaque jalon ferme une question produit identifiable ;
- une capacité n'est acquise qu'avec une preuve reproductible ;
- un nouveau commit invalide la qualification exacte d'un SHA antérieur ;
- CLI, API, MCP et NEXUS ne dupliquent pas le métier ;
- les décisions durables sont formalisées en ADR ;
- les optimisations et choix de backend sont gouvernés par des mesures ;
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
M16  Prouver la scalabilité                    planifié
  ↓
M17  Généraliser discovery + providers         planifié
  ↓
M18  Intégrer MINOS à IntelliJ                 planifié
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

Acquis structurants :

- reactor Maven à 12 modules enfants + parent ;
- `MinosApplication` comme composition partagée ;
- MCP directement relié aux services typés, sans CLI comme couche métier ;
- `ProjectResolver` unique ;
- persistance séparant repository, pointeur actif, codecs, intégrité et rétention ;
- cache borné des vues de snapshot actif ;
- indexes reconstruisibles pour requêtes structurantes ;
- JaCoCo et quality gates ciblées ;
- CI PR Linux/Windows ;
- facts produit générés depuis le code.

Porte finale : `scripts/m15/run-final.ps1` qualifie le SHA exact et conserve les replays M14/providers/Windows, tout en prouvant la baisse des chargements complets sur requêtes répétées.

- roadmap opérationnelle : [`roadmap/M15_EXECUTION.md`](roadmap/M15_EXECUTION.md)
- qualité : [`developer/quality-gates.md`](developer/quality-gates.md)
- facts générés : [`generated/product-facts.md`](generated/product-facts.md)
- décisions : ADR-0022 à ADR-0024
- issue : #55
- PR finale : #62

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

---

## M17 — Provider & Discovery Platform

**PLANIFIÉ.**

Objectif : rendre réellement extensibles les langages, systèmes de build et providers sans introduire de branches spécifiques dans le cœur.

Acquis visés : SPI discovery/provider, conformance kit, Gradle, workspaces JS qualifiés et au moins un nouvel écosystème/langage.

---

## M18 — MINOS for IntelliJ

**PLANIFIÉ.**

Objectif : exploiter les capacités MINOS directement dans IntelliJ sans dépendre d'un agent IA. Le plugin reste un client du moteur et ne duplique pas l'intelligence.

---

## M19 — Advanced Code Intelligence

**PLANIFIÉ.**

Objectif : ajouter progressivement les analyses de programme avancées lorsque leur coût, leur provenance et leurs limites peuvent être explicités.

---

## M20 — Recherche sémantique hybride

**PLANIFIÉ.**

Objectif : combiner recherche sémantique et faits structurés MINOS sans remplacer les identités, relations et preuves déterministes.

---

## Règle de progression

M16 est le prochain jalon séquentiel. M18 peut ensuite progresser en parallèle de M17 lorsque les contrats le permettent. M19/M20 peuvent être explorés, mais leur promotion produit dépend des garanties de scalabilité et de qualité obtenues en M16.
