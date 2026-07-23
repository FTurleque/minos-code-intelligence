# État courant — MINOS

Dernière mise à jour : **23 juillet 2026**

Ce document est le tableau de bord opérationnel de MINOS. Les preuves détaillées
restent dans les rapports de jalon, les documents de décision et les issues GitHub.
La feuille de route conserve la séquence produit.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET FUSIONNÉ
M1 — Découverte et orchestration    TERMINÉ ET FUSIONNÉ
M2 — Intelligence des symboles      TERMINÉ ET LIVRÉ
M3 — Intelligence des relations     TERMINÉ ET LIVRÉ
M4 — Recherche et contexte compact  TERMINÉ ET LIVRÉ
M5 — Tests liés et dérivations      TERMINÉ ET LIVRÉ
M6 — Intelligence d’architecture    TERMINÉ, VALIDÉ ET LIVRÉ
M7 — Indexation incrémentale        PROCHAIN JALON
M8 à M13                            NON DÉMARRÉS
```

GitHub Actions reste volontairement hors de la porte locale courante ; l’anomalie
historique est suivie séparément dans #5.

## Portes acquises

### C0 — cadrage

Le cahier des charges, le MVP, les frontières MINOS/NEXUS, le modèle de domaine,
les stratégies SCIP/Glean/store et les ADR structurantes ont été validés.

### M0 — faisabilité technique

Verdict : **ADOPTER_AVEC_CONTRAINTES**.

Acquis principaux :

- Java 24 + Maven Wrapper qualifiés ;
- SCIP Java et TypeScript mesurés sur artefacts réels ;
- backend MINOS léger retenu par défaut ;
- Glean optionnel ;
- frontière fournisseur préservée.

Décision : `docs/m0/DECISION_M0.md`.

### M1 — découverte et orchestration

Acquis :

- découverte Java / TypeScript ;
- Maven / npm factuels ;
- modules et racines source/test ;
- `.gitignore` / `.minosignore` ;
- registre projets/workspaces ;
- `IndexerRegistry` et négociation de capacités ;
- cycle de vie d’indexation et promotion atomique.

Suivi clôturé : issue #6.

### M2 — intelligence des symboles

Acquis :

- modèle de symbole normalisé ;
- identité et qualité d’identité explicites ;
- recherche lexicale et par nom qualifié ;
- DTO compact et rendus TEXT/JSON ;
- snapshot persistant ;
- `find-symbol` et `getFileSymbols`.

Porte finale : **86 tests, BUILD SUCCESS**.

Décision : `docs/m2/DECISION_M2.md`.

### M3 — intelligence des relations

Acquis :

- relations normalisées et isolées par projet ;
- faits SCIP conservés sans surinterprétation ;
- provenance, preuves, résolution et confiance ;
- `DEPENDS_ON` dérivé explicitement ;
- persistance snapshot v2 ;
- requêtes usages/implémentations/appels/dépendances.

Porte finale : **115 tests, BUILD SUCCESS**.

Décision : `docs/m3/DECISION_M3.md`.

### M4 — recherche et contexte compact

Acquis :

- recherche structurée unifiée ;
- profondeur, résultats et tokens bornés ;
- plages source compactes ;
- récupération complète explicite avec `get-source` ;
- benchmark réel de latence.

Porte finale : **131 tests, BUILD SUCCESS**.

Décision : `docs/m4/DECISION_M4.md`.

### M5 — tests liés et dérivations explicables

Acquis :

- `RELATED_TEST` orienté test → production ;
- signaux directs et heuristiques séparés ;
- score déterministe et preuves structurées ;
- persistance et requête `findRelatedTests` ;
- CLI `related-tests`.

Porte finale : **140 tests, BUILD SUCCESS**.

Décision : `docs/m5/DECISION_M5.md`.

## M6 — Intelligence d’architecture — clôture

Suivi : issue #13.

M6 est livré en sept incréments :

```text
M6.1 topologie modules / namespaces             PR #14
M6.2 dépendances inter-modules explicables      PR #15
M6.3 mesures de concentration                   PR #16
M6.4 calibration des distributions              PR #17
M6.5 classement directionnel des composants     PR #18
M6.6 technologies factuelles                    PR #19
M6.7 vue composée + contexte de module           PR #20
```

Acquis :

- `ArchitectureOverview` avec modules factuels et namespaces dérivés ;
- `ArchitectureDependencyGraph` fondé uniquement sur les `DEPENDS_ON` persistés ;
- concentration entrante/sortante avec HHI et parts maximales ;
- calibration cycle / chaîne / fan-in / fan-out / pondérée ;
- rangs de centralité relatifs et directionnels, sans seuil absolu ;
- technologies factuelles actuellement qualifiées : `JAVA`, `TYPESCRIPT`, `MAVEN`, `NPM` ;
- `ArchitectureIntelligenceView` cohérente sur un projet/snapshot unique ;
- `ProjectArchitectureQuery.getArchitectureOverview(...)` ;
- `ProjectArchitectureQuery.getModuleContext(...)` ;
- distinction explicite entre `FACTUAL`, `DERIVED` et les preuves associées.

### Porte finale fonctionnelle M6

Head validé M6.7 :

```text
ba744f41b974432fe33eb617a866ef4c8dcb0ead
```

Validation :

```text
.\mvnw.cmd clean verify
116 sources main compilées en release 24
58 sources test compilées en release 24
162 tests exécutés
0 failure
0 error
0 skipped
BUILD SUCCESS
```

Fusion M6.7 :

```text
f10449681a9010079cc9fe0400aac867dea497d9
```

### Replay réel TypeScript M6

La fixture `fixtures/typescript/typescript-modules` confirme :

```text
modules = 3
DEPENDS_ON = 4
inter = 4
module edges = 1

packages/api
  incomingDependencyCount = 4
  incomingRank = 1
  technologies = [TYPESCRIPT]

packages/app
  outgoingDependencyCount = 4
  outgoingRank = 1
  technologies = [TYPESCRIPT]

root
  incomingRank = 0
  outgoingRank = 0
  technologies = [NPM]
```

Décision : `docs/m6/DECISION_M6.md`.

La branche `m6/finalize-milestone` ne modifie que la documentation de clôture et
doit encore passer la porte locale sur son SHA exact avant fusion.

## Porte active

La porte active est la **consolidation documentaire finale M6** :

```powershell
.\mvnw.cmd clean verify
```

Après fusion de cette consolidation et clôture de l’issue #13, le jalon actif
devient **M7 — Indexation incrémentale**.

## Prochain jalon — M7

Objectif : éviter les réindexations complètes lorsqu’elles ne sont pas nécessaires.

Périmètre roadmap :

- empreintes de fichiers ;
- empreintes projet/build ;
- fichiers ajoutés, modifiés et supprimés ;
- snapshots d’index ;
- règles d’invalidation ;
- capacités incrémentales propres aux fournisseurs ;
- repli explicite vers une indexation complète.

## Sources de vérité

- roadmap : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- décision M0 : `docs/m0/DECISION_M0.md` ;
- décision M2 : `docs/m2/DECISION_M2.md` ;
- décision M3 : `docs/m3/DECISION_M3.md` ;
- décision M4 : `docs/m4/DECISION_M4.md` ;
- décision M5 : `docs/m5/DECISION_M5.md` ;
- décision M6 : `docs/m6/DECISION_M6.md` ;
- topologie M6.1 : `docs/m6/ARCHITECTURE_TOPOLOGY.md` ;
- dépendances M6.2 : `docs/m6/MODULE_DEPENDENCIES.md` ;
- concentration M6.3 : `docs/m6/ARCHITECTURE_CONCENTRATION.md` ;
- calibration M6.4 : `docs/m6/CENTRALITY_CALIBRATION.md` ;
- centralité M6.5 : `docs/m6/CENTRAL_COMPONENT_RANKING.md` ;
- technologies M6.6 : `docs/m6/TECHNOLOGY_DETECTION.md` ;
- vue composée M6.7 : `docs/m6/ARCHITECTURE_VIEW_AND_MODULE_CONTEXT.md` ;
- suivi M6 : issue #13 ;
- promotion atomique : ADR-0006 ;
- identité registre : ADR-0007 ;
- négociation indexeurs : ADR-0008.

Ce tableau de bord doit rester compact : les mesures détaillées appartiennent aux
documents de jalon et aux décisions de porte.
