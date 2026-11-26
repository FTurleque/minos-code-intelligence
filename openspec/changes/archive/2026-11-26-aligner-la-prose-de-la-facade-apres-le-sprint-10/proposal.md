# Proposal

## Why

Constats **AUD-ARC-15** et **AUD-QUA-08** de l'audit `docs/project/audits/2026-11-15/` (sprint 11, sévérité faible, ouverts le 2026-11-25 au commit `75e10c68`).

- **AUD-ARC-15.** Le sprint 10 a retiré du public la racine de composition et les ports de `MinosApplication`, et le propriétaire a accepté ARCH-0087 le 2026-11-24. Plusieurs textes décrivent encore l'état antérieur : la mémoire d'agent `archgate-developer` dit le groupe 4 « reporté » et ARCH-0087 `Proposed` ; l'index des ADR omet le troisième changement qui met en œuvre ARCH-0087 ; le Javadoc de classe de `MinosApplication` la présente comme une « racine de composition » ; ARCH-0042 fait passer l'application « via `MinosApplication.compositionRoot()` » et qualifie ARCH-0087 de `Proposed`. Un lecteur, humain ou agent, en déduit que la façade rend encore sa racine et que la décision n'est pas acceptée.
- **AUD-QUA-08.** La table de rupture de `docs/user/java-api.md` donne `new LocalProjectOperations(application)` comme remplaçant de `retentionService()`. `LocalProjectOperations` n'expose aucune rétention à la demande : la rétention n'y suit qu'un import publié. Un hôte qui compactait lui-même ses snapshots n'a pas de remplaçant, et la page lui en promet un.

## What Changes

- Javadoc de classe de `MinosApplication` : la façade d'un home et d'un backend, qui rend les services et cas d'usage et garde ports et racine de composition privés au package (design, D4).
- ARCH-0042, sections de mise en œuvre : la parenthèse « via `MinosApplication.compositionRoot()` » (§ 8.1) dit l'état en vigueur, et le statut écrit en dur d'ARCH-0087 (§ 10) disparaît. Décision d'ARCH-0042 inchangée (design, D2).
- Index des ADR (`docs/architecture/adr/README.md`, ligne ARCH-0087) : la colonne Origine cite les trois changements, comme l'en-tête d'ARCH-0087.
- `docs/developer/public-surfaces.md` : le stéréotype `<<composition root long-lived>>` du diagramme de `MinosApplication` devient celui d'une façade (site non cité par l'audit, design, Écarts).
- `docs/user/java-api.md`, ligne `retentionService()` : aucun remplaçant à la demande ; la rétention suit chaque indexation et chaque import publié (design, D1).
- Mémoire d'agent `.claude/agent-memory/archgate-developer/project_audit_2026_11_15_clos.md:19-21` : l'exemple de la règle d'archivage ne contredit plus la ligne 41 du même fichier (design, D3).

Aucun comportement, aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : deux exigences ajoutées (la documentation courante de la façade décrit l'état en vigueur ; la table de rupture ne promet aucun remplaçant que la façade n'offre pas).

## Hors périmètre

- `docs/project/audits/2026-11-15/SUIVI.md:7` : corrigé par le coordinateur dans la section du sprint 11 du suivi.
- Une opération de rétention à la demande dans la façade : option écartée par la recommandation de D1 ; elle demanderait son propre changement.
- La signature `projectIndexing(UnaryOperator<IndexerExecutor>)` et la ligne `java-api.md:358` qui la cite : AUD-ARC-13, changement `tenir-le-contrat-de-la-facade-par-le-garde`.
- L'injection des ports de `LocalProjectOperations` : AUD-ARC-14, changement `injecter-les-ports-des-operations-locales`.
- Le texte des décisions d'ARCH-0042 et d'ARCH-0087 : aucun ADR n'est réécrit ; seules les sections de mise en œuvre d'ARCH-0042 et l'index sont touchés.
- Les relevés figés (`docs/project/history/`, `docs/project/audits/`, archives OpenSpec).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| ARCH-0087 est `Accepted` (2026-11-24) et cite trois changements | [Existant vérifié] | `docs/architecture/adr/ARCH-0087-adaptateurs-embarques-et-facade-reduite.md:10-12` |
| La mémoire d'agent dit « reporté par le propriétaire ; ARCH-0087 reste `Proposed` », puis `Accepted` | [Existant vérifié] | `.claude/agent-memory/archgate-developer/project_audit_2026_11_15_clos.md:19-21`, `:41` |
| L'index cite deux changements sur trois | [Existant vérifié] | `docs/architecture/adr/README.md:92` |
| Javadoc « Long-lived composition root » | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/MinosApplication.java:66` |
| ARCH-0042 « (via `MinosApplication.compositionRoot()`) » ; « ARCH-0087 (Proposed) » | [Existant vérifié] | `docs/architecture/adr/ARCH-0042-racine-de-composition.md:333`, `:401` |
| Stéréotype `<<composition root long-lived>>` | [Existant vérifié] | `docs/developer/public-surfaces.md:12` |
| `java-api.md` promet la rétention par `LocalProjectOperations` | [Existant vérifié] | `docs/user/java-api.md:359` |
| `LocalProjectOperations` ne compacte qu'après un import publié ; aucune méthode publique de rétention | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/LocalProjectOperations.java:59`, `:63-100`, `:140-142` |
| La rétention suit chaque indexation | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/indexing/ProjectIndexingUseCase.java:359`, `:429` ; chaîne exigée par `scripts/remediation/check-post-mne.py:276-277` |
| Rétention documentée seulement, sans opération nouvelle | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : `minos-application` (un commentaire Javadoc, aucun code).
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé ; ARCH-0042, sections « 8. Mise en œuvre » et « 10. Mise en œuvre », deux parenthèses ; ARCH-0087 inchangé ; index des ADR, une cellule.
- **Gates à rejouer** : `scripts/quality/check-comment-language.py` (Javadoc en anglais), `scripts/docs/check-current-docs.py`, `scripts/docs/product-facts.py --check`, `scripts/remediation/check-p0-p2.py` (lit `public-surfaces.md` et `MinosApplication.java`), `archgate check` si disponible (index des ADR, GEN-0073), `python .claude/scripts/run_gates.py --fast`. Aucun scope JaCoCo.
- **Plateformes** : sans objet (texte).
- **Documentation durable** : les fichiers cités ci-dessus ; dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (deux exigences ajoutées, à l'archivage).

## Questions ouvertes

Aucune bloquante : D1 à D4 ont une recommandation.
