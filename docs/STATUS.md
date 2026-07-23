# État courant — MINOS

Dernière mise à jour : **23 juillet 2026**

Ce document est le tableau de bord opérationnel de MINOS. La feuille de route
conserve la séquence des jalons, les issues GitHub portent les checklists de
travail et les rapports de jalon conservent les preuves détaillées.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET FUSIONNÉ
M1 — Découverte et orchestration     TERMINÉ ET FUSIONNÉ
  M1.1 — découverte locale           VALIDÉ ET FUSIONNÉ
  M1.2 — ignore + registre           VALIDÉ ET FUSIONNÉ
  M1.3 — registre indexeurs          VALIDÉ ET FUSIONNÉ
  M1.4 — cycle de vie / état         VALIDÉ ET FUSIONNÉ
M2 — Intelligence des symboles       AUTORISÉ
M3 à M13 — Jalons produit           NON DÉMARRÉS
```

M0 est livré avec le verdict **ADOPTER_AVEC_CONTRAINTES**. M1 est désormais
livré intégralement. GitHub Actions reste volontairement hors de la porte
courante ; l'anomalie historique est suivie séparément dans #5.

## Résultats acquis de M0

- Java 24.0.1, Maven Wrapper 3.3.4 et Maven 3.9.16 validés localement ;
- 27 tests JUnit réussis sur le head final M0 ;
- `scip-java 0.13.1` qualifié sur fixtures et dépôt Java réel ;
- `scip-typescript 0.4.0` qualifié sur les fixtures TypeScript ;
- huit index réels ingérés par la baseline SCIP vers MINOS ;
- backend mémoire mesuré et déterministe ;
- Glean 0.2.0.1 qualifié sous WSL2 mais non retenu pour le chemin MVP par défaut ;
- frontière fournisseur vérifiée dans le cœur MINOS ;
- promotion atomique des index décidée ;
- backend MINOS léger retenu par défaut.

Preuve finale M0 :

```text
commit validé  2e0b3f19e160d0621898641d0d9cad71bbccb86f
MINOS tests    27 réussis, 0 échec, 0 erreur
java-24-smoke  BUILD SUCCESS
runner         Manual CI: SUCCESS
merge main     6d8376bcfc16dd5ba1c6b691535aa3d8e57cc49a
```

## M1 — clôture du jalon

Suivi : issue #6.

### M1.1 — découverte locale factuelle

La PR #7 a été validée localement sur
`be6ac6872cb289022db671f28094ecb996c8fe71` :

```text
37 sources main
15 sources test
30 tests réussis
0 échec
0 erreur
BUILD SUCCESS
```

Fusion `main` : `fb1ee4b648f5ebee6b9fcac7369ce7574f449877`.

Acquis :

- `ProjectDiscovery` immuable ;
- aucune identité métier dérivée du chemin ;
- détection Java / TypeScript fondée sur des fichiers réels ;
- Maven via `pom.xml`, npm via `package-lock.json` ;
- `package.json` comme marqueur de module Node sans présumer le gestionnaire ;
- modules et racines source/test relatifs et déterministes.

Documentation : `docs/m1/PROJECT_DISCOVERY.md`.

### M1.2 — ignore policy et registre local

La PR #8 a été validée localement sur
`a89ba9b1fc473606afd107b6e9e7f9ea463b6a7d` :

```text
41 sources main
17 sources test
36 tests réussis
0 échec
0 erreur
BUILD SUCCESS
```

Fusion `main` : `b12c4bdc5a6061c6d6b0e4f7ef0ad86db42d9b31`.

Acquis :

- `ProjectIgnorePolicy` ;
- `.gitignore` et `.minosignore` racine ;
- exclusions techniques non ré-includables ;
- glob, ancrage, négation et règles répertoire ;
- politique appliquée aux modules, builds et racines source/test ;
- `RegisteredProject`, `RegisteredWorkspace`, `LocalProjectRegistry` ;
- UUID persistés et non dérivés du chemin ;
- affectation projet/workspace persistée ;
- écritures transactionnelles ;
- ADR-0007 **Accepté**.

Limites maintenues : `.gitignore` imbriqués non interprétés, pas de
réconciliation automatique d'un projet déplacé et pas de verrouillage
multi-processus.

Documentation : `docs/m1/IGNORE_AND_REGISTRY.md`.

### M1.3 — IndexerRegistry et négociation

La validation locale du head
`3b642819ea2d1828ed831f9f53d47604c81233c3` a été confirmée entièrement verte
avant fusion.

Fusion `main` : `0125802b364f481e2242c7d2bbb008beb4c2d8d7`.

Acquis :

- `IndexerCapability` ;
- `IndexerQualification` ;
- `IndexerDescriptor` indépendant des API natives ;
- `IndexingRequirements` ;
- `IndexerNegotiationResult` explicable ;
- `IndexerRegistry` avec IDs uniques et sélection déterministe ;
- une sélection séparée par langage détecté ;
- refus explicites pour build non qualifié, capacité manquante et indexeur expérimental ;
- `adapter.scip.ScipIndexerCatalog` fondé sur les mesures M0 ;
- `scip-java 0.13.1` limité au périmètre Maven réellement qualifié ;
- `scip-typescript 0.4.0` sans fausse dépendance à npm ;
- asymétrie d'index partiel Java/TypeScript conservée ;
- aucune promesse `CALLS` ajoutée ;
- ADR-0008 **Accepté**.

Capacités qualifiées M1 :

```text
SYMBOLS
REFERENCES
IMPLEMENTATION_RELATIONS
STRUCTURAL_RELATIONS
MULTI_MODULE
TEST_SOURCES
PARTIAL_INDEX_ON_BUILD_FAILURE
```

Une capacité exprime un support observé/qualifié et **pas** une garantie de
complétude.

Documentation : `docs/m1/INDEXER_NEGOTIATION.md`.

### M1.4 — cycle de vie et état d'index

La PR #10 a été validée localement sur le head final
`debf19bf4baecfda1e50c9981cbeed857b679a2f` :

```text
54 sources main
20 sources test
47 tests réussis
0 échec
0 erreur
0 skipped
BUILD SUCCESS
```

Fusion `main` : `cf59f43ca6d9927340a889d77c41b375c019f9ba`.

Acquis :

- `ProjectIndexState` ;
- états `NEVER_INDEXED`, `INDEXING`, `REFRESHING`, `READY`, `STALE`, `FAILED` ;
- `IndexingRun` avec statut, phase, exécutions et snapshots avant/après ;
- phases `PROVIDER_EXECUTION`, `STAGING`, `PROMOTION`, `COMPLETED` ;
- `IndexStateStore` et baseline `InMemoryIndexStateStore` ;
- ports runtime fournisseur-indépendants `IndexerExecutor`, `SnapshotStager`, `SnapshotPromoter` ;
- `IndexingLifecycleService` ;
- refus d'une négociation incomplète avant démarrage ;
- un seul run actif par projet dans une instance de service ;
- exécution de toutes les sélections avant staging ;
- staging d'un snapshot projet commun ;
- promotion atomique unique au niveau du run projet complet ;
- ancien snapshot conservé en cas d'échec de refresh ;
- état `STALE` pour distinguer échec récent et snapshot précédent encore actif ;
- ADR-0006 clarifié pour les projets multi-langages / multi-indexeurs.

Tests de porte :

- succès Java + TypeScript avec un seul staging et une seule promotion ;
- échec du second fournisseur bloquant toute promotion ;
- échec de promotion conservant le snapshot précédent en `STALE` ;
- négociation incomplète ne créant aucun run.

Limites explicites conservées :

- aucune annulation forcée d'un processus externe ;
- aucun timeout générique ;
- aucune politique de retry ;
- aucun verrouillage multi-processus ;
- aucune persistance durable imposée pour `IndexStateStore` ;
- aucun mode best-effort promu comme snapshot sain.

Ces limites n'empêchent pas la clôture de M1 : elles ne sont pas des garanties
qualifiées de ce jalon.

Documentation : `docs/m1/INDEXING_LIFECYCLE.md`.

## Verdict M1

**TERMINÉ ET LIVRÉ.**

Les critères de sortie de M1 sont satisfaits :

- découverte reproductible sur fixtures Java et TypeScript ;
- projets multi-modules et racines source/test représentés explicitement ;
- politique d'ignore appliquée ;
- identité projet/workspace persistante et indépendante du chemin ;
- sélection d'indexeurs par capacités explicites ;
- limites fournisseur conservées ;
- cycle de vie et états d'index observables ;
- promotion atomique au niveau projet ;
- aucune fuite SCIP/Glean/Protobuf dans les contrats `discovery`, `registry` ou `orchestration` ;
- validation locale finale verte sur le head fusionné.

## Blocages et décisions

| Sujet | Effet |
|---|---|
| GitHub Actions sans steps ni logs | Issue #5 en pause ; aucun blocage de la validation locale |
| `scip lint` / `snapshot` sur plages typées | Limitation SCIP CLI 0.7.1 documentée |
| Kinds et appels incomplets selon les fournisseurs | Capacités explicites, jamais inventées ; travail ciblé en M2/M3 |
| `qualifiedName` non canonique dans tous les cas | Requalification ciblée en M2 |
| Identité projet | UUID persistant du registre ; chemin = localisation/rapprochement uniquement |
| Ignore imbriqué | Limite M1.2 documentée |
| Sélection indexeur | Par capacités qualifiées, build compatible, qualification et priorité déterministe |
| Promotion | Atomique au niveau du run projet complet, y compris multi-indexeurs |

## Prochaine porte

```text
M0 — ADOPTER_AVEC_CONTRAINTES — livré
        ↓
M1 — découverte + orchestration — livré
        ↓
M2 — Intelligence des symboles — autorisé
```

M2 doit stabiliser le modèle de symbole et sa recherche sans remettre en cause
les frontières fournisseur établies par M0/M1.

## Sources de vérité

- feuille de route : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- décision M0 : `docs/m0/DECISION_M0.md` ;
- suivi M1 clôturé : issue #6 ;
- découverte M1.1 : `docs/m1/PROJECT_DISCOVERY.md` ;
- ignore et registre M1.2 : `docs/m1/IGNORE_AND_REGISTRY.md` ;
- négociation M1.3 : `docs/m1/INDEXER_NEGOTIATION.md` ;
- lifecycle M1.4 : `docs/m1/INDEXING_LIFECYCLE.md` ;
- promotion atomique : ADR-0006 ;
- identité registre : ADR-0007 ;
- négociation indexeurs : ADR-0008.

Ce tableau de bord doit être mis à jour après chaque résultat expérimental ou
décision de porte, sans recopier les mesures détaillées des rapports.
