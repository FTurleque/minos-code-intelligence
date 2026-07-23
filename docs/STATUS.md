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
M2 — Intelligence des symboles       TERMINÉ ET VALIDÉ LOCALEMENT
M3 — Intelligence des relations      TERMINÉ ET VALIDÉ LOCALEMENT
M4 — Recherche et contexte compact   TERMINÉ ET VALIDÉ LOCALEMENT
M5 — Tests liés et dérivations       IMPLÉMENTÉ, VALIDATION FINALE EN ATTENTE
M6 à M13 — Jalons produit            NON DÉMARRÉS
```

M0 est livré avec le verdict **ADOPTER_AVEC_CONTRAINTES**. M1 est livré
intégralement. M2, M3 et M4 sont terminés et validés localement sur le worktree
courant ; ils ne sont pas encore présentés comme fusionnés. M5 est implémenté,
mais reste ouvert tant que la validation complète et le replay réel ajoutés à
sa porte n'ont pas été exécutés. GitHub Actions reste
volontairement hors de la porte courante ; l'anomalie historique est suivie
séparément dans #5.

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
| Kinds et appels incomplets selon les fournisseurs | Capacités explicites, jamais inventées ; limites conservées pour M3 |
| `qualifiedName` non canonique dans tous les cas | Extraction M2 livrée ; fallback conservé sans preuve inter-fournisseurs |
| Identité projet | UUID persistant du registre ; chemin = localisation/rapprochement uniquement |
| Ignore imbriqué | Limite M1.2 documentée |
| Sélection indexeur | Par capacités qualifiées, build compatible, qualification et priorité déterministe |
| Promotion | Atomique au niveau du run projet complet, y compris multi-indexeurs |

## M2 — clôture du jalon

```text
M0 — ADOPTER_AVEC_CONTRAINTES — livré
        ↓
M1 — découverte + orchestration — livré
        ↓
M2 — Intelligence des symboles — terminé
        ↓
M3 — Intelligence des relations — terminé
        ↓
M4 — Recherche et contexte compact — terminé
        ↓
M5 — Tests liés et dérivations — validation finale en attente
        ↓
M6 — Intelligence d'architecture — prochain après clôture M5
```

M2 stabilise le modèle de symbole et sa recherche sans remettre en cause les
frontières fournisseur établies par M0/M1.

Premier incrément implémenté :

- critères structurés `text`, `qualifiedName`, `kind`, `moduleId`, `limit` ;
- classement lexical déterministe avec priorité aux correspondances exactes ;
- filtres combinables par nom qualifié, type et module ;
- `getFileSymbols` trié dans l'ordre source ;
- compatibilité conservée pour `findSymbol` et les mesures M0.

Validation locale de l'incrément : `51` tests réussis, `0` échec, `0` erreur,
`BUILD SUCCESS` avec Java 24 et Maven Wrapper 3.9.16.

Deuxième incrément implémenté :

- parseur des descripteurs SCIP globaux conforme à la grammaire standard ;
- `qualifiedName` Java et TypeScript alimenté sans fuite fournisseur ;
- modules-fichiers TypeScript retirés des noms métier ;
- identité structurelle stable après déplacement lorsqu'une signature existe ;
- aucune promotion automatique à `CANONICAL` sans preuve inter-fournisseurs.

Validation locale cumulée : `56` tests réussis, `0` échec, `0` erreur,
`BUILD SUCCESS`. Les quatre index TypeScript réels conservent leurs nombres de
symboles et d'occurrences et répondent aux recherches qualifiées ciblées.

Troisième incrément implémenté :

- DTO public `SymbolResult` distinct de l'entité stockée ;
- identité, déclaration, résolution et provenance compactes ;
- références fournisseur opaques et contenu source complet non exposés ;
- méthodes DTO lexicales, structurées et par fichier ;
- compatibilité maintenue pour les contrats et benchmarks M0.

Validation locale cumulée : `57` tests réussis, `0` échec, `0` erreur,
`BUILD SUCCESS`.

Quatrième incrément implémenté :

- formats publics `TEXT` et `JSON` ;
- renderer déterministe de `SymbolResult` sans dépendance JSON externe ;
- valeurs optionnelles explicites et ordre des champs stable ;
- échappement des contrôles, Unicode et UTF-16 malformé ;
- aucune lecture source ni exposition de référence fournisseur opaque.

Validation locale cumulée : `64` tests réussis, `0` échec, `0` erreur,
`BUILD SUCCESS`. La chaîne requête → DTO → JSON est couverte de bout en bout et
vérifie l'absence d'identifiant fournisseur opaque.

Documentation : `docs/m2/SYMBOL_OUTPUT.md`.

Cinquième incrément implémenté :

- dispatcher minimal `MinosCli` et commande `find-symbol` ;
- syntaxe historique `<projet> <symbole>` et filtres structurés optionnels ;
- sorties TEXT/JSON, limites bornées et codes de sortie stables ;
- port `ProjectSymbolQuery` isolant le registre et le snapshot actif ;
- aucune dépendance fournisseur dans la couche CLI.

Validation locale cumulée : `72` tests réussis, `0` échec, `0` erreur,
`BUILD SUCCESS`.

Documentation : `docs/m2/FIND_SYMBOL_CLI.md`.

Sixième incrément et clôture M2 :

- `FileSymbolSnapshotStore` binaire, déterministe, versionné et vérifié par
  checksum ;
- publication du fichier puis promotion atomique du pointeur actif ;
- conservation des anciens snapshots et republication sûre d'un même ID ;
- fidélité UTF-16, y compris surrogates isolés ;
- `LocalProjectSymbolQuery` par UUID ou nom d'affichage unique ;
- contrats persistants `findSymbols` et `getFileSymbols` ;
- pont `ScipSymbolSnapshotImporter` sans fuite de type fournisseur ;
- `MinosLauncher`, manifest Main-Class et wrapper Windows `minos.cmd` ;
- bootstrap paresseux : aucune écriture pour l'aide ou une syntaxe invalide ;
- preuve de relecture du même snapshot dans un nouveau processus Java.

Validation locale finale :

```text
.\mvnw.cmd clean verify
69 sources main compilées
29 sources test compilées
86 tests réussis
0 échec
0 erreur
BUILD SUCCESS

.\minos.cmd --help
exit 0
```

Les quatre index TypeScript locaux ont été rejoués. Les nombres de symboles et
d'occurrences restent identiques aux preuves antérieures. Les limites
`scip-typescript 0.4.0` restent qualifiées : kinds `OTHER`, trois définitions de
`GreetingService.greet` fusionnées sous un identifiant et `MissingClient`
absent du catalogue malgré quatre occurrences non résolues. MINOS ne fabrique
ni kind, ni symbole, ni identité de surcharge.

## Verdict M2

**TERMINÉ ET VALIDÉ LOCALEMENT.**

Le critère `minos find-symbol <projet> <symbole>` est satisfait avec un résultat
MINOS compact, un snapshot persistant et un nouveau processus. Aucune identité
n'est promue à `CANONICAL` sans preuve d'équivalence inter-fournisseurs ; les
qualités de fallback restent visibles et font partie du contrat.

Documentation :

- `docs/m2/SYMBOL_SNAPSHOTS.md` ;
- `docs/m2/DECISION_M2.md`.

## M3 — incrément 1 : requêtes relationnelles normalisées

Le premier incrément M3 établit la frontière de requête avant de brancher les
faits propres aux fournisseurs :

- chaque `Relationship` porte désormais son `projectId` ;
- les incohérences cible/statut sont refusées ;
- toute relation dérivée ou heuristique doit conserver une confiance et au
  moins une preuve ;
- `RelationshipSearchCriteria` combine ancre, direction, kinds, résolution,
  nature et limite ;
- `CodeKnowledgeStore.findRelationships` garantit l'isolation projet et un
  ordre déterministe ;
- `RelationshipQueryService` expose les recherches entrantes, sortantes et
  `findImplementations` ;
- `RelationshipResult` conserve résolution, nature, confiance, origine et
  preuves sans type fournisseur.

Validation locale cumulée :

```text
.\mvnw.cmd clean verify
73 sources main compilées
31 sources test compilées
95 tests réussis
0 échec
0 erreur
0 skipped
BUILD SUCCESS
```

La sémantique reste volontairement prudente : MINOS ne transforme pas une
simple occurrence en `CALLS`, `EXTENDS` ou `IMPLEMENTS` sans fait fournisseur
explicite et interprétable. Le mapping SCIP factuel est décrit dans l'incrément
suivant et la persistance complète dans la décision de clôture.

Documentation : `docs/m3/RELATIONSHIP_SEARCH.md`.

### M3 — incrément 2 : faits relationnels SCIP

Le deuxième incrément branche les relations réellement présentes dans les
`SymbolInformation` SCIP :

- `is_reference` devient `REFERENCES` ;
- `is_implementation` devient `IMPLEMENTS` au sens navigation « Find
  implementations », sans prétendre identifier un mot-clé de langage ;
- `is_type_definition` devient `TYPE_DEFINITION` ;
- `is_definition` devient `DEFINITION` ;
- chaque drapeau vrai devient une relation factuelle distincte avec origine et
  preuve structurée ;
- une cible cataloguée utilise son ID MINOS, sinon son nom qualifié récupérable
  reste une cible `UNRESOLVED` ;
- les identités sont stables par projet, fournisseur, source, cible SCIP et
  kind ;
- les doublons et faits ignorés sont mesurés séparément.

Rejeu des quatre index `scip-typescript 0.4.0` locaux :

| Dataset | Messages SCIP | Faits | Relations MINOS | Résolues | Ignorées | Doublons |
|---|---:|---:|---:|---:|---:|---:|
| `typescript-simple` | 2 | 3 | 3 | 3 | 0 | 0 |
| `typescript-inheritance` | 11 | 14 | 14 | 14 | 0 | 0 |
| `typescript-modules` | 4 | 6 | 6 | 6 | 0 | 0 |
| `typescript-unresolved` | 2 | 3 | 3 | 3 | 0 | 0 |
| **Total** | **19** | **26** | **26** | **26** | **0** | **0** |

Validation locale cumulée :

```text
.\mvnw.cmd clean verify
74 sources main compilées
31 sources test compilées
98 tests réussis
0 échec
0 erreur
0 skipped
BUILD SUCCESS
```

Aucun fait `CALLS` ou `EXTENDS` n'est fabriqué. Les quatre index réels ne
portent aucun drapeau `is_type_definition` ou `is_definition`, mais ces deux
cas sont couverts par les tests synthétiques.

Documentation : `docs/m3/SCIP_RELATIONSHIPS.md`.

### M3 — clôture : persistance, dérivation et CLI

La porte finale M3 est acquise :

- le snapshot v2 persiste symboles, occurrences, relations, confiance,
  provenance et preuves tout en lisant les snapshots M2 v1 ;
- `DependencyDerivationService` coalesce les faits directs par paire et produit
  des `DEPENDS_ON` explicables, sans supprimer les faits d'origine ;
- la CLI expose `find-usages`, `find-implementations`, `find-callers`,
  `find-callees`, `dependencies` et `dependents` en TEXT/JSON ;
- un test lance une nouvelle JVM sur le snapshot complet actif ;
- un replay persistant de `typescript-simple` est interrogé par le JAR produit
  dans des processus séparés.

Preuve finale :

```text
.\mvnw.cmd clean verify
80 sources main compilées
37 sources test compilées
115 tests réussis
0 échec
0 erreur
0 skipped
BUILD SUCCESS
```

Replay des quatre index TypeScript : 19 messages, 26 faits normalisés et
résolus, 0 ignoré, 0 doublon et 19 dépendances dérivées. Le snapshot réel
`typescript-simple` contient 24 symboles, 100 occurrences et 5 relations ; la
CLI recharge 4 usages, 1 implémentation, 1 dépendance et 1 dépendant. Les vues
d'appels retournent zéro sur cet artefact, car le fournisseur n'émet aucun fait
`CALLS`.

Verdict : **M3 TERMINÉ ET VALIDÉ LOCALEMENT**. Documentation :

- `docs/m3/CODE_KNOWLEDGE_SNAPSHOTS.md` ;
- `docs/m3/DEPENDENCY_DERIVATION.md` ;
- `docs/m3/CLI_RELATIONSHIPS.md` ;
- `docs/m3/DECISION_M3.md`.

## M4 — clôture : recherche et contexte compact

M4 livre le premier flux directement consommable par un outil ou un agent :

- `CodeSearchService` compose symbole, relations, preuves, usages et source ;
- la traversée en largeur est bornée à une profondeur 0–3 et déduplique entités
  et relations ;
- les limites racines/nœuds/usages utilisent une sonde supplémentaire pour
  rendre `truncated` explicite ;
- le budget de 256 à 32 768 tokens estimés privilégie les faits avant la plage
  source ;
- `LocalSourceReader` confine les chemins à la racine réelle du projet ;
- `minos get-source` est la seule commande retournant le fichier complet ;
- les imports SCIP persistants conservent désormais les chemins documentaires
  relatifs sûrs comme `fileId` par défaut.

Preuve complète :

```text
.\mvnw.cmd clean verify
92 sources main compilées
45 sources test compilées
131 tests réussis
0 échec
0 erreur
0 skipped
BUILD SUCCESS
```

Sur le snapshot réel `typescript-simple`, la recherche exacte de
`InMemoryUserRepository.findById` retourne 1 symbole, 3 relations et une plage
de 3 lignes : 19 tokens estimés contre 101 pour le fichier, soit 82 évités. La
réponse complète tient en 540 tokens sans troncature. Le benchmark de 200 runs
mesure p50 3,232 ms, p95 5,421 ms et p99 5,969 ms.

Verdict : **M4 TERMINÉ ET VALIDÉ LOCALEMENT**. Documentation :

- `docs/m4/CODE_SEARCH.md` ;
- `docs/m4/SOURCE_CONTEXT.md` ;
- `docs/m4/TOKEN_POLICY_AND_BENCHMARK.md` ;
- `docs/m4/DECISION_M4.md`.

## M5 — implémentation : tests liés explicables

Le périmètre M5 est implémenté :

- relation orientée `test --RELATED_TEST--> production` ;
- détection des emplacements de test et ancrage prudent du symbole de test ;
- preuves `DIRECT_REFERENCE`, `DIRECT_CALL`, `NAMING_CONVENTION`,
  `PACKAGE_PROXIMITY` et `TEST_LOCATION` ;
- score par complément d'incertitude, arrondi et déterministe ;
- nature `DERIVED` en présence d'une référence/appel, sinon `HEURISTIC` ;
- persistance dans le snapshot v2 et compteur dédié ;
- requête `findRelatedTests` et commande `minos related-tests` ;
- raisons complètes dans les rendus TEXT et JSON.

La première suite ciblée a réussi 16 tests. Les tests de replay des quatre index
TypeScript réels et de réouverture snapshot → CLI sont codés, mais leur
exécution finale et `clean verify` sont en attente d'un environnement autorisant
la lecture du JDK local. Le jalon reste donc ouvert.

Documentation :

- `docs/m5/RELATED_TEST_DERIVATION.md` ;
- `docs/m5/CLI_RELATED_TESTS.md` ;
- `docs/m5/DECISION_M5.md`.

## Sources de vérité

- feuille de route : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- décision M0 : `docs/m0/DECISION_M0.md` ;
- suivi M1 clôturé : issue #6 ;
- découverte M1.1 : `docs/m1/PROJECT_DISCOVERY.md` ;
- ignore et registre M1.2 : `docs/m1/IGNORE_AND_REGISTRY.md` ;
- négociation M1.3 : `docs/m1/INDEXER_NEGOTIATION.md` ;
- lifecycle M1.4 : `docs/m1/INDEXING_LIFECYCLE.md` ;
- recherche structurée M2 : `docs/m2/SYMBOL_SEARCH.md` ;
- noms qualifiés SCIP M2 : `docs/m2/SCIP_QUALIFIED_NAMES.md` ;
- résultats compacts M2 : `docs/m2/SYMBOL_RESULTS.md` ;
- rendus de symboles M2 : `docs/m2/SYMBOL_OUTPUT.md` ;
- commande de recherche M2 : `docs/m2/FIND_SYMBOL_CLI.md` ;
- snapshots persistants M2 : `docs/m2/SYMBOL_SNAPSHOTS.md` ;
- décision de clôture M2 : `docs/m2/DECISION_M2.md` ;
- requêtes relationnelles M3 : `docs/m3/RELATIONSHIP_SEARCH.md` ;
- relations SCIP M3 : `docs/m3/SCIP_RELATIONSHIPS.md` ;
- snapshots de connaissance M3 : `docs/m3/CODE_KNOWLEDGE_SNAPSHOTS.md` ;
- dépendances dérivées M3 : `docs/m3/DEPENDENCY_DERIVATION.md` ;
- CLI relationnelle M3 : `docs/m3/CLI_RELATIONSHIPS.md` ;
- décision de clôture M3 : `docs/m3/DECISION_M3.md` ;
- recherche unifiée M4 : `docs/m4/CODE_SEARCH.md` ;
- sources locales M4 : `docs/m4/SOURCE_CONTEXT.md` ;
- tokens et benchmark M4 : `docs/m4/TOKEN_POLICY_AND_BENCHMARK.md` ;
- décision de clôture M4 : `docs/m4/DECISION_M4.md` ;
- dérivation des tests liés M5 : `docs/m5/RELATED_TEST_DERIVATION.md` ;
- CLI tests liés M5 : `docs/m5/CLI_RELATED_TESTS.md` ;
- porte de clôture M5 : `docs/m5/DECISION_M5.md` ;
- promotion atomique : ADR-0006 ;
- identité registre : ADR-0007 ;
- négociation indexeurs : ADR-0008.

Ce tableau de bord doit être mis à jour après chaque résultat expérimental ou
décision de porte, sans recopier les mesures détaillées des rapports.
