# Design

## Context

Constat **AUD-ARC-01**, lot 6 de l'inventaire du design archivé `openspec/changes/archive/2026-10-24-faire-consommer-l-indexation-aux-surfaces/design.md` (« Lots suivants » et décision D5). Premier lot de la phase 3. ADR applicables :

- 0058 § 3 : un usage du moteur par une surface est une dette ;
- 0068 § 2 : direction des dépendances, et `ALLOWED_DEPENDENCIES` comme ensemble maximal ;
- 0068 § 3 : un paquet, un module ; table des emplacements ; ordre de repli « déplacement, puis renommage du côté interne » ;
- 0068 § 5 : racines de paquets.

## État vérifié au HEAD

HEAD : `develop` `9b710cbe`.

### Les six types et la règle D5

| Type (moteur) | Lignes | Dépendances | E/S | Autres classes du paquet | Verdict D5 |
|---|---|---|---|---|---|
| `com.minos.diagnostics.PublicErrorMessages` | 147 | JDK (`Path`, `Locale`, `Pattern`) | aucune | aucune : seul dans son paquet | descend, paquet entier, nom complet inchangé |
| `com.minos.registry.UnreadableRegistryException` | 56 | JDK | aucune | ports du registre | descend dans `com.minos.domain.registry` |
| `com.minos.registry.DegradedEntry` | 64 | `PublicErrorMessages`, `com.minos.domain.Preconditions` | aucune | ports du registre | descend dans `com.minos.domain.registry`, une fois `PublicErrorMessages` dans le domaine |
| `com.minos.registry.ProjectPathMapping` | 154 | JDK (`Path`, `Properties`, `Map`) | aucune : `Path.of` et des propriétés reçues en paramètre | ports du registre | descend dans `com.minos.domain.registry` |
| `com.minos.registry.ProjectPathMappings` | 16 | `ProjectPathMapping` | **port d'E/S** (`loadOptional`, `save`, `throws IOException`), implémenté par `ProjectPathMappingStore` (stockage local) | — | reste : justifié |
| `com.minos.runtime.MinosVersion` | 15 | aucune (lit `Package.getImplementationVersion`) | aucune | ports du runtime | sans dépendance, mais **placé par l'ADR 0068 § 3** (« `com.minos.runtime` (dont `MinosVersion`) ») : Q1 |

Aucun de ces types n'apparaît dans une signature publique de `minos-api`. `docs/user/java-api.md:361` cite seulement `ProjectPathMappings` comme port resté dans `com.minos.registry`, ce qui reste vrai. `MinosVersionContractTest` (`minos-app`) affirme que le contrat de `MinosVersion` ne dépend pas du module qui porte la classe.

### Utilisateurs de production, par module

- **`PublicErrorMessages`** : 47 fichiers dans 11 modules, dont `minos-integration-git` (`GitIntelligenceService`). Ce module ne déclare que `minos-engine` (`ALLOWED_DEPENDENCIES`, `check-module-boundaries.py:71` ; `ModuleArchitectureTest.java:66` ; arc42 § 5, ligne 85). Tous les autres modules utilisateurs déclarent déjà `minos-domain`.
- **`UnreadableRegistryException`** : application 4, CLI 2, moteur 1, MCP 1, NEXUS 1.
- **`DegradedEntry`** : application 5, moteur 3, API 1, CLI 1, stockage local 1.
- **`ProjectPathMapping`** : moteur 3, stockage local 2, application 1, CLI 1, SCIP 1, PostgreSQL 1.

### Tests

- Dans le moteur : `com/minos/diagnostics/PublicErrorMessagesTest`, `PublicErrorMessagesLeafNameTest`, `com/minos/registry/UnreadableRegistryExceptionTest` et `DegradedEntryTest`.
- `ProjectPathMapping` n'a pas de test propre. Il est couvert par des tests d'autres modules (`MinosRuntimeSettingsRuntimeLocationTest` dans le moteur, `OllamaEmbeddingProviderRuntimeLocationTest` dans l'application, `DefaultMinosApplicationComposerTest` dans bootstrap).

### Gates et bases

- **A14** : `PACKAGE_ROOTS_BY_MODULE` donne `diagnostics` au moteur ; le domaine a `domain`, `program` et `semantic`.
- **JaCoCo** : la portée `domain-invariants` (`com/minos/domain/`, 0,80 / 0,60) reçoit `com/minos/domain/registry/`.
- **Langue des commentaires** : `minos-domain` a 21 fichiers hors langue, au maximum de son cliquet. `DegradedEntry` a des commentaires français ; `MinosVersion` a une Javadoc française.
- **SpotBugs** : `minos-engine.xml` fige `EI_EXPOSE_REP` sur `UnreadableRegistryException.unreadable()`.
- **Mentions littérales** : `check-private-io.py:34,178` et son test citent `PublicErrorMessages.leafName` par son nom simple, inchangé. `check-post-mne.py` lit `ProjectPathMappingStore.java`, inchangé.

## Goals / Non-Goals

**Goals** : retirer d'A11 les entrées des types que D5 fait descendre ; justifier par écrit celles qui restent ; ne changer ni sortie ni format.

**Non-Goals** : déplacer un port du moteur ; renommer un paquet existant ; changer `MinosVersion` sans décision.

## Decisions

### D1 — Ordre de repli de l'ADR 0068 § 3, type par type

- **`PublicErrorMessages`** : étape 1, déplacement sans changer le nom complet. Le paquet entier passe dans `minos-domain`, le module le plus bas qui peut l'accueillir. La racine `diagnostics` passe de `minos-engine` à `minos-domain` dans `PACKAGE_ROOTS_BY_MODULE` ; c'est un déplacement, et la modification de la table en cite la raison.
- **Types du registre** : `com.minos.registry` garde ses ports dans le moteur, donc un déplacement du paquet entier est impossible. On applique l'étape 3 : le côté interne prend l'espace de noms de son module, `com.minos.domain.registry`. Le dernier segment reprend le nom du paquet d'origine, pour que la correspondance soit évidente.
- **Code de production** : aucune classe ne change, hors paquet, imports et langue des commentaires, à une exception près. `UnreadableRegistryException` portait une fabrique statique `explaining(ProjectRegistry, String)`, qui interroge le port du moteur. Elle était dans le même paquet, sans import, et l'inventaire au HEAD ne l'a pas vue. Cette fabrique devient la méthode par défaut `ProjectRegistry.explainUnreadable(String)`, au même comportement, et ses trois appelants (`LocalProjectOperations`, `ProjectResolver`, `NexusExportService`) l'appellent sur le registre. L'exception reste ainsi sans dépendance au moteur, et son test (`ProjectRegistryExplainUnreadableTest`) vit dans le moteur avec la méthode.

### D2 — `minos-integration-git` déclare `minos-domain`

Le domaine est sous le moteur : l'arête va dans le sens permis par l'ADR 0068 § 2. `everyModuleOnlyUsesItsAllowedModules` refuse un usage non déclaré, même transitif. Elle s'ajoute donc dans `ALLOWED_DEPENDENCIES`, `ModuleArchitectureTest.ALLOWED`, le POM, l'arc42 § 5 (ligne « Dépendances ») et le document généré (`--write-doc`).

Alternative écartée : garder un relais de `PublicErrorMessages` dans le moteur. Ce serait deux classes pour un seul contrat.

### D3 — Les commentaires des fichiers déplacés passent en anglais

C'est la règle de tout commentaire modifié (`CONTRIBUTING.md`, `check-comment-language.py`). Sans elle, le maximum de `minos-domain` serait dépassé. Le maximum du moteur baisse d'autant, car le cliquet ne fait que baisser.

### D4 — Tests avec leurs classes, et un test pour `ProjectPathMapping`

SonarCloud juge la couverture du nouveau code module par module, et une classe déplacée compte comme du code neuf.

- Les quatre tests du moteur suivent leurs classes. Les deux tests de `PublicErrorMessages` gardent `com.minos.diagnostics`, puisque le paquet appartient désormais au domaine (A3).
- `ProjectPathMappingTest` est ajouté dans le domaine : résolution de l'emplacement d'exécution par l'environnement et les propriétés, chemins Windows et POSIX, refus.
- `MinosVersion` ne bouge pas (Q1) ; son test reste dans `minos-app`.

## Questions ouvertes

### Q1 — `MinosVersion` : le garder dans le moteur, ou remplacer ce point de l'ADR 0068 ?

- **A (recommandée)** : garder `com.minos.runtime.MinosVersion` dans le moteur. Ses deux entrées A11 (CLI `MinosLauncher`, MCP `MinosMcpServer`) restent, justifiées par l'ADR 0068 § 3 : la version de l'artefact est un fait du runtime, placé par décision. Pas d'ADR pour une classe de 15 lignes.
- **B** : la descendre dans `com.minos.domain.runtime`. L'ADR 0068 nomme son emplacement ; il faudrait un ADR qui remplace ce point (règle des ADR acceptés : on ne réécrit pas, on remplace). A11 perdrait 2 entrées de plus (31 au lieu de 33).

Les compteurs ci-dessous supposent A.

## Compteurs

| Table A11 | Avant | Après (Q1 = A) | Entrées restantes du noyau transverse |
|---|---|---|---|
| Garde Python | 41 (API 16, CLI 15, MCP 4, NEXUS 6) | **33** (API 15, CLI 11, MCP 2, NEXUS 5) ; retirés : `PublicErrorMessages` ×3, `UnreadableRegistryException` ×3, `DegradedEntry`, `ProjectPathMapping` | `registry.ProjectPathMappings` (CLI) et `runtime.MinosVersion` (CLI, MCP), justifiés dans la table |
| Bytecode | 36 (API 14, CLI 12, MCP 4, NEXUS 6) | **28** (API 13, CLI 8, MCP 2, NEXUS 5) ; mêmes retraits | idem |

## Direction des dépendances (ADR 0068)

Les classes descendent du moteur vers le domaine. Elles ne dépendent que du JDK et du domaine (`Preconditions`) : le domaine reste sans dépendance (A2). Une seule arête de module s'ajoute, `minos-integration-git` → `minos-domain`, dans le sens permis (D2). Aucun cycle de paquets (A8) : `com.minos.domain.registry` ne dépend que de `com.minos.diagnostics` et `com.minos.domain`, et `com.minos.registry` (moteur) dépend du domaine, jamais l'inverse.

## Capacités

Aucune capacité n'est ajoutée ni requalifiée.

## Windows et Linux

Aucun comportement ne dépend du système. `ProjectPathMapping` normalise les chemins hôte Windows et POSIX ; son nouveau test couvre les deux formes sur les deux systèmes.

## Coût

| Poste | Estimation |
|---|---|
| Couverture du nouveau code (SonarCloud, 80 % par module) | `minos-domain` : 421 lignes déplacées (147 + 56 + 64 + 154), couvertes par les quatre tests déplacés et par `ProjectPathMappingTest`. Lignes modifiées (imports) dans les autres modules : couvertes par leurs tests existants |
| Tests déplacés | `PublicErrorMessagesTest`, `PublicErrorMessagesLeafNameTest`, `UnreadableRegistryExceptionTest` et `DegradedEntryTest` vers `minos-domain` ; un test ajouté |
| Portées de `scripts/quality/check-jacoco.py` | `domain-invariants` (0,80 / 0,60) reçoit `com/minos/domain/registry/` et `com/minos/diagnostics/` sort de toute portée du moteur ; mesure sur les deux systèmes, aucun plancher abaissé sans mesure |
| Gates littéraux | `check-module-boundaries.py` (A2, A11, A14) et son auto-test ; `ModuleArchitectureTest` (`ALLOWED`, table bytecode). Aucun `scripts/remediation/check-*.py` ne cite les types déplacés par leur paquet |
| Bases SpotBugs | `minos-engine.xml` → `minos-domain.xml` : `EI_EXPOSE_REP` de `UnreadableRegistryException.unreadable()`, réenregistré sous le nouveau nom par le profil `audit-spotbugs` |
| Langue des commentaires | maximums du moteur et du domaine recalculés (D3) |
| Documentation | ADR 0058 (mise en œuvre, lot 6) ; ADR 0068 (mise en œuvre : racine `diagnostics`, arête `minos-integration-git` → `minos-domain`) ; arc42 § 5 ; document généré ; `SUIVI.md` ; plan du chantier |

## Risks / Trade-offs

- **Import oublié** : la compilation le refuse. Un fichier qui garderait l'ancien nom complet échoue au build.
- **Sortie modifiée** : les goldens de caractérisation (CLI, API, MCP) sont rejoués, et aucun n'est régénéré.
- **Cliquet de langue** : D3.

## Recouvrements avec les changements actifs

`decouper-les-classes-de-plus-de-800-lignes` (`PrivateLocalStorage`, manager SCIP) et `lire-le-registre-sans-ecrire` (registre local) peuvent toucher des fichiers qui importent `DegradedEntry` ou `UnreadableRegistryException`. Celui qui fusionne en second met ses imports à jour au rebase. La compilation le signale.

## Migration Plan

Une PR vers `develop`, branche `refactor/descendre-le-noyau-transverse`. Le premier commit archive le lot 2 et met à jour le suivi. Retour arrière : revert de la PR, sans effet sur les données.
