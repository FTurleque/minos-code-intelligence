# Feuille de route — MINOS

Statut : **C0 à M13 terminés, validés et livrés**.

L’état courant est résumé dans [`STATUS.md`](STATUS.md). Les décisions architecturales durables sont dans [`adr/`](adr/README.md). Les preuves détaillées de chaque jalon sont archivées sous [`history/milestones/`](history/milestones/README.md).

## Principes de roadmap

- chaque jalon ferme une question produit identifiable ;
- une capacité n’est acquise qu’avec une preuve reproductible ;
- les limitations fournisseur restent explicites ;
- un nouveau commit invalide la validation exacte d’un SHA précédent ;
- les surfaces CLI/API/MCP/NEXUS ne dupliquent pas le métier ;
- les décisions durables sont formalisées en ADR ;
- les logs, mesures et validations historiques restent dans les archives de jalon.

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

Acquis : administration, import SCIP explicite, statut, recherche/symboles/relations/tests liés, architecture, impact, formats `text/json` et codes de sortie stables.

Frontière : aucun runner absent n’est simulé par `minos index`.

- historique : [`history/milestones/m9/`](history/milestones/m9/)
- décision : [ADR-0016](adr/0016-stable-cli-contract.md)

---

## M10 — Serveur MCP

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : serveur MCP STDIO Java officiel 2.0.0, 15 tools read-only, schémas bornés, erreurs structurées et shaded JAR.

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

```text
MINOS Java 24
  nexus-export --root <project>
        |
        | JSON stdout
        v
NEXUS Java 21
  minos-import <project> < stdin
```

Validation finale : MINOS `7c5eda4727cda3d46cab24037e4f1276ff0b4a25`, NEXUS head validé `df61c9c07b5ec3271aba27f54da272b4689fb017`, replay réel `M13 MINOS -> NEXUS replay SUCCESS`.

- historique : [`history/milestones/m13/`](history/milestones/m13/)
- décision : [ADR-0020](adr/0020-minos-nexus-json-boundary.md)

---

## Explorations futures

Non engagées dans la roadmap principale : Code Property Graph, flux de données, sécurité avancée, recherche sémantique, embeddings, plugins IDE, indexation distante GitHub/GitLab, indexation distribuée et mode service hébergé.
