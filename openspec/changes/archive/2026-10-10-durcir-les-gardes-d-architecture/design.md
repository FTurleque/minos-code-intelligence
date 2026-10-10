# Design

## Context

Sprint 1 de l'audit du 10 octobre 2026 : AUD-ARC-10, AUD-ARC-06, AUD-ARC-03. Le HEAD analysé est `816cdd0c`. Les trois constats portent sur la même chaîne : POM → règles Python (`check-module-boundaries.py`) → règles de bytecode (`ModuleArchitectureTest`, ArchUnit, sortie du changement actif `etendre-audit-outille-a-tout-le-perimetre`) → documentation.

## État vérifié au HEAD

### AUD-ARC-10 — confirmé (preuve à une réserve près)

- `python scripts/architecture/test_check_module_boundaries.py` → `Ran 13 tests … OK`. Répartition : `CleanTreeTest` 1, `SplitPackageTest` 6 (A3), `ReactorTest` 4 (A7), `MainWiringTest` 2 (câblage de `main()` sur A7/A3). Aucun test n'appelle `check_pom_layout`, `check_no_hidden_internal_dependencies`, `check_hexagonal_boundaries`, `check_dependency_policy`, `check_source_boundaries`, `check_java_layout` ni le contrôle du document généré.
- Le littéral : `check-module-boundaries.py:149` (`!= "com.minos"` dans `minos_dependencies`) et `:182` (même test dans `check_no_hidden_internal_dependencies`) ; `SOURCE_QUALIFIED` (`:276`) code aussi `com\.minos`. Un module interne déclaré avec `<groupId>${project.groupId}</groupId>` ou un `groupId` autre échappe à toutes les règles (la dépendance n'est pas reconnue comme interne).
- Les fonctions A2 lisent `ROOT` global (`parse_pom`, `adapter_classes`, `check_source_boundaries`, `check_java_layout`) ; seules `check_package_ownership` et `check_reactor_modules` acceptent une racine, d'où l'absence d'auto-test.
- **Preuve à revoir** — l'action de l'audit « refuser tout `<build>` dans un `<profile>` » aurait fait échouer le dépôt : six profils légitimes déclarent un `<build>` (`audit-spotbugs`, `audit-mutation`, `audit-dependency-check`, `audit-dependency-check-tests` dans `pom.xml`, et `windows-docker-desktop-testcontainers` dans `minos-bootstrap` et `minos-storage-postgresql`, tous avec des `plugins`). Ce qui échappe réellement aux règles est un `sourceDirectory`, un `testSourceDirectory` ou un `includes`/`excludes` du compilateur placés dans un profil : `check_pom_layout` ne lit que le `<build>` racine. C'est cette règle-là qui est retenue (D2).
- Atténuation (inchangée) : `ModuleArchitectureTest` rattraperait un usage réel de classe ou une source dupliquée au `verify`.

### AUD-ARC-06 — confirmé (15 packages)

Recalcul indépendant (script jetable, imports + noms qualifiés des sources de production, composantes fortement connexes) : 45 packages et 264 arêtes dans les 14 modules ; trois composantes dans le reactor, une dans le plugin, soit **15 packages**, le chiffre de l'audit :

| Composante | Module | Packages |
|---|---|---|
| 1 | `minos-engine` | `com.minos.discovery`, `com.minos.discovery.spi` |
| 2 | `minos-engine` | `com.minos.incremental`, `com.minos.orchestration` |
| 3 | `minos-application` | `com.minos.application`, `.application.dynamic`, `.application.semantic`, `com.minos.architecture`, `.impact`, `.output`, `.program.analysis`, `.workspace` |
| 4 | `minos-intellij` (hors reactor) | `com.minos.intellij.protocol`, `.service`, `.ui` |

`grep -n "cycle" scripts/architecture/check-module-boundaries.py` ne trouve que le cycle Maven entre modules ; `ModuleArchitectureTest` n'a pas de règle de cycle. Le pivot de la composante 3 est confirmé : `ProjectResolver` est importé par 6 packages de la composante (et par `api`, `cli`, `mcp`) tandis que `MinosApplication`, dans le même package, les importe tous ; `output` importe six de ses voisins (`application`, `.application.dynamic`, `.application.semantic`, `architecture`, `impact`, `.program.analysis`) et `application.semantic` importe `output.DeterministicJson`.

### AUD-ARC-03 — confirmé (7 modules sur 14)

Comparaison des lignes « Dépendances » d'`arc42/05-vue-blocs.md` aux dépendances directes des POM (toutes portées, comme `module-dependencies.md`) :

| Module | Écart (document / POM) |
|---|---|
| `minos-runtime-local`, `minos-storage-local` | manque `minos-domain` |
| `minos-nexus` | en trop `minos-storage-local` ; manque `minos-bootstrap`, `minos-engine` |
| `minos-cli` | en trop `minos-integration-git`, `minos-provider-scip`, `minos-runtime-local`, `minos-storage-local` ; manque `minos-bootstrap` |
| `minos-api` | en trop `minos-cli`, `minos-integration-git`, `minos-storage-local` ; manque `minos-bootstrap` |
| `minos-mcp` | manque `minos-bootstrap`, `minos-domain`, `minos-engine` (ces deux derniers ajoutés par l'ADR 0058) |
| `minos-storage-postgresql` | en trop `minos-application` (le POM ne le déclare pas) |

Autres preuves relues : `arc42/05:67` range `InMemoryCodeKnowledgeStore` sous `minos-storage-local` alors qu'il est dans `minos-engine` (`minos-engine/src/main/java/com/minos/store/`) ; `SYNTHESE.md:80` et `:117` affirment toujours « `minos-storage-postgresql` dépend de `minos-application` — confirmé intentionnel » ; `python scripts/docs/check-current-docs.py` → SUCCESS. Le document généré `module-dependencies.md` est déjà exact et gardé par `check-module-boundaries.py` (« generated … is stale »), ce qui montre que le défaut est la **prose** à côté. `c4-container.md` se déclare « volontairement simplifiée » et renvoie au fichier généré ; il omet néanmoins deux familles d'arêtes réelles (surfaces → moteur, adaptateur → adaptateur).

## Goals / Non-Goals

**Goals** : qu'une régression d'une règle A2 fasse échouer un auto-test et non plus seulement le `verify` ; qu'aucun nouveau cycle de packages n'apparaisse sans qu'un gate le dise ; que les dépendances de modules décrites dans la documentation courante soient confrontées aux POM.

**Non-Goals** : casser les cycles ; changer une règle de dépendance entre modules ; vérifier la prose ; remplacer ArchUnit.

## Decisions

### D1. Rendre les règles A2 testables, puis les tester

Les fonctions qui lisent le disque reçoivent un paramètre `root` (défaut `ROOT`), comme `check_package_ownership` : `parse_pom`, `adapter_classes`, `check_source_boundaries`, `check_java_layout`. Les fonctions pures (`check_hexagonal_boundaries(scoped)`, `check_dependency_policy(scoped)`) se testent avec des dictionnaires ; `check_pom_layout` et `check_no_hidden_internal_dependencies` avec `ET.fromstring`. Comportement du script sur le dépôt inchangé (sortie identique, `--write-doc` sans diff).

Couverture exigée, au moins un cas refusé **et** un accepté :

| Règle | Refusé | Accepté |
|---|---|---|
| A2 application → adaptateur | `minos-application` → `minos-storage-local` | → `minos-engine` |
| A2 surface → adaptateur | `minos-cli` → `minos-provider-scip` (portée `test`) | `minos-cli` → `minos-application` |
| A2 surface → racine de composition | portée `compile` | portées `runtime`, `test` |
| A2 adaptateur → couche haute | `minos-runtime-local` → `minos-application`, `-bootstrap`, `-cli` | → `minos-engine` |
| A2 bootstrap | → `minos-storage-postgresql` hors `test` ; → une surface | → `minos-storage-postgresql` en `test` |
| A2 domaine / moteur | `minos-engine` → un adaptateur | `minos-app` → tous (assemblage) |
| Politique de dépendances | arête hors `ALLOWED_DEPENDENCIES` ; `minos-domain` non vide ; table ≠ `MODULES` | arête permise |
| Cycle Maven | `a → b → a` | graphe acyclique |
| Dépendance cachée | module interne dans `<profile>`, `<dependencyManagement>`, `<profile><dependencyManagement>` | dépendance tierce au même endroit |
| Mise en page (D2) | `sourceDirectory`, `testSourceDirectory`, `includes`, `excludes` — à la racine **et** dans un profil | profil avec `plugins` seuls |
| Sources A2 | import simple, statique, joker, nom qualifié, nom simple du même package d'une classe d'adaptateur | mention en commentaire ou en littéral ; module adaptateur et bootstrap exemptés |
| Disposition Java | source de production dupliquée ; package ≠ chemin | arbre propre |
| Document généré | fichier absent ; fichier périmé | fichier exact |

Deux garde-fous : des **mutations témoins** sur une copie des 14 POM réels (ajouter à `minos-cli` une dépendance vers `minos-storage-local`, à `minos-application` une dépendance vers `minos-provider-scip`, à `minos-api` une dépendance `minos-bootstrap` en portée `compile` : chacune doit faire échouer le script ; retirer une dépendance doit rester permis) pour que les tests ne valident pas seulement des arbres jouets ; et un test `EveryRuleHasATest` qui échoue si une fonction `check_*` du module n'est référencée par aucune méthode de test.

### D2. E02 : le groupId interne vient du POM racine

Une dépendance est **interne** si son `artifactId` figure dans `ARTIFACT_TO_MODULE`, quel que soit son `groupId`. Elle est refusée si son `groupId` diffère du `groupId` du POM racine (lu, plus de littéral) ou contient `${` (non résolu), ce qui ferme le contournement par un nom de groupe voisin. Le même test sert à `check_no_hidden_internal_dependencies`. `SOURCE_QUALIFIED` est construit à partir du `groupId` racine.

Mise en page : `check_pom_layout` s'applique au `<build>` racine **et** à `profiles/profile/build` (et `pluginManagement`) pour `sourceDirectory`, `testSourceDirectory` et `includes`/`excludes` du compilateur. Les `plugins` de profil restent permis (six profils existants).

### D3. A8 : cycles de packages en cliquet, dans le garde Python

**Livrer d'abord le cliquet, puis les déplacements** (réponse à la question posée) : le cliquet ferme la moitié « aucune garde » du constat et le critère de sortie du sprint (« la garde de frontières refuse un cycle de packages ») sans toucher au code de production ; les déplacements, seul effort M du sprint, passent dans un changement séparé (D7) qui retirera des lignes de la table.

Pourquoi le garde Python plutôt que `slices().matching("com.minos.(**)").should().beFreeOfCycles()` (proposé par l'audit et prévu comme option par l'ADR 0057 § 7) :

1. `minos-intellij` (cycle 4) est hors du reactor Maven et ne dépend d'aucun artefact `com.minos:*` : `ModuleArchitectureTest` ne l'importe pas.
2. Le garde tourne dans le job `invariants` en quelques secondes, sans compilation, alors que la règle ArchUnit n'est évaluée qu'au `verify` (environ 21 minutes en local).
3. Le garde dispose déjà du lecteur de sources (commentaires et littéraux retirés) et de la table des modules.

Coût assumé : une approximation par imports et noms qualifiés, non par bytecode. La concordance des deux calculs indépendants (audit et recalcul : 15 packages) la valide aujourd'hui ; ce qu'elle ne voit pas (réflexion, `ServiceLoader`, références uniquement par type générique non importé) est dit dans la docstring. Une règle ArchUnit complémentaire reste possible plus tard ; elle devrait alors lire la **même** table, pour qu'il n'existe pas deux listes de cycles.

Algorithme : packages déclarés par les sources de production des 14 modules plus `minos-intellij/src/main/java` ; arête `a → b` pour tout import (simple, statique, joker) ou nom qualifié d'une classe/package connu de `b ≠ a` ; composantes fortement connexes de taille > 1 (Tarjan itératif).

### D4. Sémantique du cliquet

`KNOWN_PACKAGE_CYCLES` : table nommée, une entrée par composante (ensemble de packages, module, raison, changement de levée). Le garde échoue :

- sur toute composante **absente** de la table (nouveau cycle) ou **plus grande** qu'une entrée (cycle étendu) ;
- sur toute entrée qui **n'est plus exactement** une composante (cycle levé ou réduit) : le message demande de retirer ou de resserrer l'entrée, de sorte que la table ne puisse que rétrécir par une édition visible.

Limite : une arête ajoutée **à l'intérieur** d'une composante déjà listée ne change pas l'ensemble des packages et n'est pas vue. Acceptable : la composante est déjà cyclique ; les arêtes internes sont l'objet du changement de levée.

### D5. A9 : la documentation courante est confrontée aux POM

Dans `check-module-boundaries.py` (et non `check-current-docs.py`, contrairement au libellé de l'audit) : le script détient déjà le graphe, le lecteur de POM et le contrôle du document généré, et `check-current-docs.py` n'a aucun lecteur de POM. Éviter un second lecteur de POM évite une nouvelle copie parallèle.

Règle, ancrée sur la structure du document (ADR 0043 § 3) : pour chaque titre de niveau 3 `### minos-…` d'`arc42/05-vue-blocs.md` (suffixe « (optionnel) » ignoré), une ligne à puce d'étiquette `Dépendances` doit exister, et l'ensemble de ses jetons `` `minos-…` `` doit égaler les dépendances internes directes du POM (toutes portées). Le texte « tous les modules » n'est admis que pour `minos-app`, dont le POM doit alors déclarer les 13 autres. Un module sans section, une section sans module, une section sans ligne « Dépendances » sont des échecs ; le message imprime la ligne attendue. Les jetons tiers (`postgresql 42.7.13`…) sont ignorés.

Hors de la garde, par décision : les diagrammes de couches (`c4-container.md`, `arc42/05 § 5.1`) se déclarent simplifiés et renvoient au fichier généré ; leur compléter (arêtes pointillées « dette déclarée, ADR 0058 » surfaces → moteur, et adaptateur → adaptateur) est une tâche de rédaction, sans garde — contrôler des flèches de couches demanderait de coder la correspondance des identifiants de nœuds et les styles de trait. `SYNTHESE.md` passe au statut historique (bandeau, lignes I-3 et hypothèse corrigées) ; ses affirmations en prose restent hors garde.

### D6. Aucun ADR

- A8 et A9 appliquent l'ADR 0057 § 7 ; E02 et D1 ne changent aucune règle de dépendance ; la table `KNOWN_PACKAGE_CYCLES` suit le modèle `KNOWN_GAPS`/`GAP_CEILING` de `check-partial-result-consumers.py`.
- Les déplacements de D7 restent **dans** leur module : l'ADR 0044 (un package, un module) est respecté, aucun ADR n'est requis. Déplacer `com.minos.output` hors de `minos-application` vers les surfaces, comme le suggérait l'audit, créerait une frontière de module (ADR 0022, 0044) : un ADR serait nécessaire, et la simulation ci-dessous montre que ce n'est pas utile.
- Une ligne dans la section « Mise en œuvre » de l'ADR 0057 (« cycles de packages : garde A8, table `KNOWN_PACKAGE_CYCLES` ») est recommandée ; elle est prévue en tâche mais ne conditionne rien.

### D7. Le changement suivant : `casser-les-cycles-de-packages` (non écrit ici)

Simulation sur le graphe de classes (mêmes entrées que A8, déplacement hypothétique de classes entre packages) :

| Déplacement | Cycles restants |
|---|---|
| `ProjectResolver` → nouveau package `com.minos.application.resolution` | composante 3 réduite de 8 à 3 packages (`application`, `.semantic`, `output`) |
| + `DeterministicJson` → package neutre (sans import interne) | **composante 3 supprimée** |
| `ProjectDiscovery` + `ProjectIgnorePolicy` → `com.minos.discovery.spi` | composante 1 supprimée (mais place le modèle dans l'SPI : à discuter) |
| `IncrementalIndexingPlan` (+ `ProjectFingerprintService`) → `orchestration` | **sans effet** : la composante 2 reste |
| `MinosRegistryNotice` → `service` | composante 4 réduite de 3 à 2 packages |

Conclusions pour la suite : deux déplacements de classes suffisent à lever la plus grosse composante, sans déplacer `com.minos.output` ni créer de module ; les composantes 2 et 4 demandent une vraie conception. Gates littéraux à rejouer pour ce lot : `check-jacoco.py:22` (scope `project-resolution`, préfixe `com/minos/application/ProjectResolver`), `check-post-mne.py:103,254` (chemin de `DeterministicJson.java`), `check-mnd.py:57` (chemin de `ProjectIgnorePolicy.java`), `check-polyglot-provider-consistency.py:97` (chemin de `ProjectDiscovery.java`). Surfaces à traiter : `ProjectResolver` figure dans des constructeurs publics de `minos-application` (`ProjectQueryService`, `HybridSearchService`, `LocalProjectImpactQuery`, `ProgramGraphService`) et dans `docs/user/java-api.md:359` ; un changement de package est une rupture pour tout appelant externe de ces constructeurs, donc à encadrer (façade ou décision explicite). Les goldens de caractérisation ne citent ni `ProjectResolver` ni `DeterministicJson` : l'identité des sorties à l'octet reste vérifiable.

## Qualification des capacités nouvelles

| Capacité | Statut | Raison |
|---|---|---|
| Auto-tests A2 et E02 | **Qualifiée localement** | Commande Python, mutations témoins sur POM réels. |
| A8, cycles de packages en cliquet | **Partielle** | Approximation par imports ; 15 packages = audit ; réflexion et `ServiceLoader` invisibles ; pas de bytecode. |
| A9, listes de dépendances d'`arc42/05` | **Qualifiée localement** (7 écarts reproduits en rouge avant correction) | Ne couvre ni les diagrammes de couches ni la prose de `SYNTHESE.md`. |

## Windows et Linux

Scripts en Python pur ; chemins comparés via `as_posix()`, fins de ligne normalisées à la lecture, sortie triée : le résultat est identique sur les deux OS. Aucun comportement propre à une plateforme.

## Gates littéraux concernés

| Script | Raison | Action |
|---|---|---|
| `check-module-boundaries.py` + `test_check_module_boundaries.py` | objet du changement | rejouer `python scripts/architecture/check-module-boundaries.py`, l'auto-test, et `--write-doc` (aucun diff de `module-dependencies.md`) |
| `check-current-docs.py` | lit `docs/` et workflows | non modifié ; rejouer |
| `check-milestone-artifact-references.py` | tout script référencé | aucun script nouveau |
| `ModuleArchitectureTest` | règles de bytecode | non modifié ; à rejouer avec `mvnw -pl minos-app -am test` si l'on touche aux tables `ALLOWED_DEPENDENCIES` |

## Risks / Trade-offs

- [Faux positif de A8 sur une référence de package non importée] → la table est éditable et chaque échec nomme l'arête témoin ; l'approximation est documentée.
- [A9 fige la forme « `### minos-…` + puce `Dépendances` » d'`arc42/05`] → conséquence voulue : une restructuration du document est un échec explicite, jamais un succès par défaut.
- [Un contributeur résout un cycle et oublie la table] → échec « entrée périmée » : c'est le cliquet.
- [Deux sources de vérité si ArchUnit ajoute un jour sa règle] → imposer la lecture de la table commune (D3).

## Direction des dépendances (ADR 0022)

Aucune dépendance Maven ajoutée ni modifiée ; la direction domain → engine → runtime/storage → adapters → application → surfaces est celle que ces gardes protègent.

## Décisions qui vous attendent

1. **Cliquet d'abord, déplacements dans un changement séparé** (recommandé). AUD-ARC-06 ne sera alors fermé qu'à moitié par ce lot : la garde est livrée, les 4 cycles subsistent, listés.
2. **Garde Python plutôt qu'ArchUnit** pour A8 (recommandé : le plugin est hors reactor, le gate est rapide).
3. **Emplacement d'A9** dans `check-module-boundaries.py` (recommandé) plutôt que `check-current-docs.py`.
4. **Cycle du plugin** inclus dans la table (recommandé) malgré son statut hors reactor.
