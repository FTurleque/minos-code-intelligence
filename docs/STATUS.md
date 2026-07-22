# État courant — MINOS

Dernière mise à jour : **22 juillet 2026**

Ce document est le tableau de bord opérationnel de MINOS. La feuille de route
conserve la séquence des jalons, les issues GitHub portent les checklists de
travail et les rapports de jalon conservent les preuves détaillées.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET FUSIONNÉ
M1 — Découverte et orchestration     EN COURS
  M1.1 — découverte locale           VALIDÉ ET FUSIONNÉ
  M1.2 — ignore + registre           VALIDÉ ET FUSIONNÉ
  M1.3 — registre indexeurs          EN COURS
M2 à M13 — Jalons produit           NON DÉMARRÉS
```

M0 est livré avec le verdict **ADOPTER_AVEC_CONTRAINTES**. M1 est suivi dans
l'issue #6. GitHub Actions reste volontairement hors de la porte courante ;
l'anomalie historique est suivie séparément dans #5.

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

## M1.1 — découverte locale factuelle

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

## M1.2 — ignore policy et registre local

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

## M1.3 — porte active : IndexerRegistry + négociation

Branche :

```text
m1/indexer-registry-capability-negotiation
```

### Implémenté sur le head courant

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
- ADR-0008 proposé.

### Capacités M1 actuelles

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

### Validation requise

```powershell
.\mvnw.cmd clean verify
```

M1.3 doit rester en Draft tant que cette commande n'est pas verte sur le head
courant.

Documentation : `docs/m1/INDEXER_NEGOTIATION.md`.

## Reste du périmètre M1

```text
M1.4 cycle de vie de l'indexation + état de l'index
validation finale M1
```

M1.4 devra partir d'un plan déjà négocié et ajouter les états observables,
l'exécution, l'échec, l'annulation/timeout si retenus et la promotion atomique,
sans déplacer de logique fournisseur dans le cœur.

## Blocages et décisions

| Sujet | Effet |
|---|---|
| GitHub Actions sans steps ni logs | Issue #5 en pause ; aucun blocage de la validation locale |
| `scip lint` / `snapshot` sur plages typées | Limitation SCIP CLI 0.7.1 documentée |
| Kinds et appels incomplets selon les fournisseurs | Capacités explicites, jamais inventées |
| `qualifiedName` non canonique dans tous les cas | Accepté pour M1 ; requalification ciblée en M2 |
| Identité projet | UUID persistant du registre ; chemin = localisation/rapprochement uniquement |
| Ignore imbriqué | Limite M1.2 documentée |
| Sélection indexeur | Par capacités qualifiées, build compatible, qualification et priorité déterministe |

## Prochaines portes

```text
M0 fusionné — ADOPTER_AVEC_CONTRAINTES
        ↓
M1.1 découverte locale — validée et fusionnée
        ↓
M1.2 ignore + registre — validé et fusionné
        ↓
M1.3 IndexerRegistry + négociation — validation locale
        ↓
M1.4 cycle de vie / état d'index + validation finale M1
```

## Sources de vérité

- feuille de route : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- décision M0 : `docs/m0/DECISION_M0.md` ;
- preuves M0 : `docs/m0/` ;
- suivi M1 : issue #6 ;
- découverte M1.1 : `docs/m1/PROJECT_DISCOVERY.md` ;
- ignore et registre M1.2 : `docs/m1/IGNORE_AND_REGISTRY.md` ;
- négociation M1.3 : `docs/m1/INDEXER_NEGOTIATION.md` ;
- identité registre : ADR-0007 ;
- négociation indexeurs : ADR-0008.

Ce tableau de bord doit être mis à jour après chaque résultat expérimental ou
décision de porte, sans recopier les mesures détaillées des rapports.
