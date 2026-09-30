# Suivi — chantier Code et dette technique (lot 1 : Q6, Q7 — sorties JSON)

> Branche : `code/q6-q7-sorties` (depuis `develop`, base `02e490c3`), worktree `minos-wt/code-q6-q7`.
> Constats : **Q6** (ordre des clés JSON tiré au hasard à chaque JVM) et **Q7** (`NaN`/`Infinity` écrits tels quels, échappement JSON dispersé et incomplet), `AUDIT-2026-09.md` § 10.
> Agents : `impl-code` (implémentation), `verif-code` (inspection de chaque commit). Aucun push, aucune PR ouverte par les agents.
> Règles du lot : aucun changement de comportement non voulu ; seul l'**ordre** des clés change (jamais l'ensemble des clés ni les valeurs) ; un golden qui bouge est justifié avant d'être régénéré ; test rouge avant correctif.
> Ce fichier est repris tel quel par les lots suivants (Q11/Q19/Q20, Q10/Q14, Q12/Q13) : les sections « à traiter plus tard » et « constats de verif-code » s'y accumulent.

## 1. Tableau de bord

| Lot | Contenu | Statut | Commits |
|---|---|---|---|
| 1 — Q6, Q7 | Inventaire, encodeur unique (`NaN`/`Infinity` refusés, échappement complet), un seul point d'échappement, ordre des clés stable sur 11 sites, garde-fous, preuve inter-JVM | livré, en attente du verdict final de `verif-code` | `72b3f9b4` … `c74aef8b` (§ 4) |
| 2 — Q11, Q19, Q20 | Un seul parseur d'arguments (`CliOptions`), garde de `team`, `--help` avant `MINOS_HOME`, `--dry-run` sans effet de bord, `--no-resume` | en cours (§ 8 à 11) | § 10 |
| 3 — Q10, Q14 | Cycle de vie des ressources, exceptions avalées | à faire | — |
| 4 — Q12, Q13 | Heuristiques et duplication | à faire | — |

## 2. Inventaire daté (base `02e490c3`, 29 septembre 2026)

Les chemins de l'audit sont antérieurs à A2/A3/A4 ; chaque cible a été relocalisée par recherche dans les sources, puis confirmée par une **sonde** (voir § 2.5).

### 2.1 Cibles de l'audit, relocalisées

| Cible de l'audit | Emplacement réel | Constat |
|---|---|---|
| `HostedControlPlaneRenderer` | `minos-application/src/main/java/com/minos/output/HostedControlPlaneRenderer.java:47,57,63` | `Map.of` à 2 entrées (`renderWorkspaces`, `renderMembers`, `renderAudit`) |
| `RuntimeIntelligenceRenderer` | `minos-application/src/main/java/com/minos/output/RuntimeIntelligenceRenderer.java:35` | `Map.of` à 4 entrées (`renderSessions`) |
| `DeterministicJson` | `minos-application/src/main/java/com/minos/output/DeterministicJson.java` | `Number.toString()` : `NaN`/`Infinity` écrits ; `quote` ne protège pas les substituts isolés ni U+2028/U+2029 |
| `TeamCommand.jsonEscape` | `minos-cli/src/main/java/com/minos/cli/TeamCommand.java:335` | n'échappe que `\` et `"` ; sert au corps de `project-bind` (`snapshotId`, que le service accepte avec des contrôles U+0001–U+001F hors NUL/TAB/LF/CR) |

### 2.2 Sites d'ordre des clés (Q6) — au-delà des deux renderers cités

Les renderers cités par l'audit ne sont pas les seuls : la table de normalisation des golden (`CharacterizationNormalizer.canonicalizeUnorderedMaps`) masquait déjà, en les triant, cinq autres sites, sans jamais les corriger.

| # | Site | Structure | Entrées |
|---|---|---|---|
| 1 | `HostedControlPlaneRenderer.renderWorkspaces` (`:47`) | `Map.of` | 2 |
| 2 | `HostedControlPlaneRenderer.renderMembers` (`:57`) | `Map.of` | 2 |
| 3 | `HostedControlPlaneRenderer.renderAudit` (`:63`) | `Map.of` | 2 |
| 4 | `RuntimeIntelligenceRenderer.renderSessions` (`:35`) | `Map.of` | 4 |
| 5 | `GitActivityCommand.reportMap` → `query` (`:89`) | `Map.of` | 4 |
| 6 | `GitActivityCommand.fileMap` (`:131`) | `Map.of` | 5 |
| 7 | `GitActivityCommand.zoneMap` (`:141`) | `Map.of` | 4 |
| 8 | `MinosApplicationMcpBackend.providerProfiles` (`:343`) | `Map.copyOf(LinkedHashMap)` : l'ordre voulu est détruit | 9 |
| 9 | `ProviderConformanceKit.ConformanceResult.capabilities` (`:47`, `:92`) | `TreeMap` trié, puis `Map.copyOf` | 14 |
| 10 | `ProviderPlatformService.ProviderView.capabilities` (`:109`) | `Map.copyOf` | 14 |
| 11 | `ProviderPlatformApi.ProviderDto.capabilities` (`:33`) | `Map.copyOf` | 14 |
| — | `ProviderConformanceKit.ConformanceResult.counts` (`:48`, `:93`) | `Map.copyOf` d'un `EnumMap` ; jamais rendu | 4 |
| — | `ToolsCommand.render` (`:71`) | `Map.of` à 1 entrée | 1 (sans objet) |

Faux positifs examinés et écartés : `Set.of(options)` des commandes CLI (test d'appartenance), `MinosCli.relationshipCommands` et `IdeIntelligenceCommand.Options` (`Map.copyOf` de tables de recherche), `Set.copyOf` des records du domaine (`Symbol`, `SymbolOccurrence`, `UnresolvedSymbolReference`, `RelationshipSearchCriteria`, `ProgramGraph` — aucun n'est itéré dans une sortie ; `ProgramGraph.capabilities` est trié par `AdvancedAnalysisResultRenderer`), `HashMap` de `RuntimeIntelligenceService` (les listes sont triées avant rendu).

### 2.3 Points d'échappement JSON (Q7) — six au départ

| # | Emplacement | Couverture |
|---|---|---|
| 1 | `DeterministicJson.quote` | `"`, `\`, contrôles, paires de substituts ; **pas** de substituts isolés, ni U+2028/U+2029 |
| 2 | `CodeIntelligenceResultRenderer.quote` (`:284`) | ensemble complet (substituts isolés et U+2028/U+2029 compris) |
| 3 | `CodeSearchRenderer.quote` (`:337`) | idem |
| 4 | `SymbolResultRenderer.quote` (`:176`) | idem |
| 5 | `OllamaEmbeddingProvider.jsonEscape` (`:178`) | contrôles ; pas de substituts isolés |
| 6 | `TeamCommand.jsonEscape` (`:335`) | `\` et `"` seulement |

Hors périmètre, écartés : `ArchitectureResultRenderer.dotText`/`mermaidText` (échappement Graphviz/Mermaid), `ProjectIgnoreRules` (classe de caractères d'expression régulière), `PostgresJsonCodec` (Jackson), `McpToolSchemas` (constantes de schéma).

### 2.4 Écritures de nombres à virgule (Q7)

`DeterministicJson.append` (`Number.toString()`), `CodeIntelligenceResultRenderer:82,164,195` et `CodeSearchRenderer:319` (`Double.toString`). Le domaine borne déjà `confidence` (`ProbabilityInvariant`) ; `observedSymbolRatio`, `weight` et tout `Double` passé à `DeterministicJson` n'ont pas cette garantie.

### 2.5 Méthode de découverte exhaustive

La grep ne suffit pas (des `Map.copyOf` viennent de records, pas des renderers). Une sonde temporaire, jamais commitée, a été insérée dans `DeterministicJson.appendMap/appendIterable` : elle journalisait tout `Map`/`Iterable` de taille ≥ 2 dont la classe est `ImmutableCollections$MapN|SetN|Set12`, `HashMap` ou `HashSet`, puis toute la suite (`./mvnw test`, hors `minos-runtime-local`) a été jouée. Résultat : 161 rendus concernés, tous des `MapN` (tailles 2, 4, 5, 9, 14), aucun `SetN`/`Set12`/`HashMap`/`HashSet` ; ils correspondent exactement aux sites 1 à 11 ci-dessus (2 = renderers hébergés, 4/5 = git-activity et `renderSessions`, 9 = profil de provider MCP, 14 = `capabilities`). La sonde a été retirée avant tout commit.

## 3. Décisions

- **Ordre** : l'encodeur écrit une `Map` dans son ordre d'itération ; l'appelant en est responsable (`LinkedHashMap`, `TreeMap`, `DeterministicJson.object`). Ordre choisi : l'ordre de déclaration du code pour les objets à clés fixes (`isolation` avant `workspaces`, `nature`, `exhaustive`, `sessions`, `limitations`…), l'ordre alphabétique pour `capabilities` (`TreeMap`, déjà l'intention de `ProviderConformanceKit`).
- **`NaN` / `Infinity`** : **refusés** (`DeterministicJson.NonFiniteNumberException`, une `IllegalStateException` comme le dépassement de budget) plutôt que sérialisés en `null`. JSON (RFC 8259) n'a pas de forme pour eux ; les remplacer par `null` masquerait une valeur amont fausse. Un refus n'emprunte pas le code 2 des erreurs d'usage (ce n'est pas une `IllegalArgumentException`).
- **Échappement** : un seul point, `DeterministicJson.quote(String)` / `quote(StringBuilder, String)`. Il prend le sur-ensemble des trois renderers : `"`, `\`, U+0000–U+001F (formes courtes `\b \f \n \r \t`, sinon `\u00xx` minuscule), substituts isolés et U+2028/U+2029 en `\uXXXX`, paires de substituts intactes. **Conséquence observable** : pour `DeterministicJson`, `OllamaEmbeddingProvider` et `TeamCommand`, un substitut isolé ou U+2028/U+2029 sort désormais échappé (avant : octet brut, transformé en `?` à l'encodage UTF-8 pour un substitut isolé) ; les trois renderers de symboles/usages/recherche ne changent pas.

## 4. Journal par commit

Gates rejoués après chaque commit : `check-module-boundaries.py` (« modules=14, sources=499, packages=45 »), `check-current-docs.py` (SUCCESS), `product-facts.py --check` (SUCCESS), `check-milestone-artifact-references.py` (« scripts checked=95 ») — **mêmes chiffres que la base `02e490c3` à chaque commit**. Rejoués aussi, verts : `check-post-mne.py`, `check-hosted-control-plane-consistency.py`, `check-semantic-retrieval-consistency.py`, `check-polyglot-provider-consistency.py`, `check-advanced-provider-consistency.py`, `check-runtime-dynamic-consistency.py`.

| Commit | Contenu | Gates |
|---|---|---|
| `72b3f9b4` | docs : ce suivi, inventaire et décisions | 499 / 45 / SUCCESS / SUCCESS / 95 |
| `063ae8b4` | Q7 : `NaN`/`Infinity` refusés, échappement unique (`DeterministicJson.quote`), suppression de cinq copies, `DeterministicJson.object` (une copie en moins : `IdeIntelligenceCommand.object`), garde `JsonEscapeGuardTest` | idem |
| `cd521fa3` | Q6 : `HostedControlPlaneRenderer` ×3 et `RuntimeIntelligenceRenderer.renderSessions` | idem |
| `ca4c1491` | Q6 : `GitActivityCommand` (`query`, `files`, `zones`) | idem |
| `0814dcb8` | V-L1-01 (`Float` gardait son `toString`), V-L1-02 (U+2028/U+2029 en constantes), V-L1-03 (`Map.of` dans un test) | idem |
| `a8165f3f` | Q6 : `capabilities` (`ProviderConformanceKit.sortedCopy`, `ProviderView`, `ProviderDto`) et `providerProfiles` MCP ; V-L1-04 | idem |
| `c74aef8b` | Q6 : `JsonOrderGuardTest`, retrait du masque d'ordre de `CharacterizationNormalizer`, 4 golden régénérés (ordre seul), ce journal | idem |

## 5. Preuves

### 5.1 Rouge → vert

Chaque test a été joué **avant** le correctif sur le code d'origine (l'API neuve n'y était qu'une façade de l'ancien comportement, pour que le test compile), puis après. La sortie rouge est jointe au message du commit correspondant.

| Défaut | Test | Rouge (code d'origine) | Vert |
|---|---|---|---|
| Q7 `NaN`/`Infinity`, substituts isolés, U+2028/9 | `DeterministicJsonEncodingTest` | 4 échecs sur 8 | 8/8 |
| Q7 `Float` élargi en `double` (V-L1-01) | `…aFloatKeepsItsOwnShortestText…` | `<[0.10000000149011612,…]>` au lieu de `<[0.1,1.1,3.4028235E38]>` | 9/9 |
| Q7 échappement Ollama | `OllamaEmbeddingProviderTest.requestBodyIsValidJson…` | `raw lone surrogate at 203` | vert |
| Q7 échappement `team project-bind` | `TeamCommandTest.projectBindEscapesEveryCharacter…` | `raw control character U+0001 in the JSON` | vert |
| Q7 un seul point d'échappement | `JsonEscapeGuardTest` | 9 signatures dans 5 fichiers (Ollama, 3 renderers, `TeamCommand`) | vert |
| Q6 renderers hébergés / runtime | `RendererKeyOrderTest` | 4 lancements de JVM : 3, 3, 2, 2 échecs sur 4 | 3 lancements : 4/4 |
| Q6 git-activity | `GitActivityCommandTest.jsonKeysComeOutInTheirDeclaredOrder…` | 3 lancements, 3 ordres différents (`[maxCommits, zoneDepth, maxFiles, since]`, `[maxFiles, since, maxCommits, zoneDepth]`) | 3 lancements verts |
| Q6 `capabilities` / `counts` / profils MCP | `ProviderConformanceResultOrderTest` (2 échecs sur 3), `ProviderViewOrderTest`, `ProviderDtoOrderTest`, `ProviderProfilesKeyOrderTest` | 4 classes rouges, 2 lancements | verts |
| Q6 aucune structure sans ordre | `JsonOrderGuardTest` | 11 sites de l'inventaire (avant leurs correctifs) | vert |

### 5.2 Les 12 golden, avant / après

Méthode : les 12 fichiers de la base (`02e490c3`) sont comparés à ceux du commit de garde ligne à ligne ; chaque ligne qui diffère est relue comme du JSON (les jetons de normalisation `:<host>` sont mis entre guillemets des deux côtés) puis comparée sous forme canonique (clés triées récursivement) ; les objets dont la **liste de clés** change d'ordre sont énumérés.

**Ce qui bouge et pourquoi.** Jusqu'ici deux masques cachaient Q6 aux golden : `CharacterizationNormalizer.canonicalizeUnorderedMaps` triait les cinq familles de maps sans ordre (`capabilities`, `providerProfiles`, `query`/`files`/`zones` de git-activity, racine de `renderSessions`) avant l'écriture et la comparaison. Le masque est retiré (aucun test ne le testait directement) ; les golden portent désormais l'ordre réel du code. Ils ne peuvent bouger que là où un objet passe de « clés triées » à « ordre de déclaration » : `cli-git`, `cli-runtime`, `mcp`, `mcp-all-tools`. Les 8 autres ne dépendent d'aucun objet de ces familles dont l'ordre de déclaration diffère de l'ordre trié (`capabilities` reste trié).

| Golden | Résultat | Détail |
|---|---|---|
| `api`, `cli-doctor`, `cli-json`, `cli-semantic`, `cli-text`, `java-program-graph`, `retention`, `team` | **identiques à l'octet** | (`cli-json` et `cli-text` portent `capabilities`, trié avant comme après) |
| `cli-git` | ordre seul | 2 lignes : `query` (`[maxCommits, maxFiles, since, zoneDepth]` → `[since, maxCommits, maxFiles, zoneDepth]`), chaque élément de `files` (`[commitCount, lastChangedAt, lastCommitId, path, uniqueAuthorCount]` → `[path, commitCount, uniqueAuthorCount, lastChangedAt, lastCommitId]`), chaque élément de `zones` (`[commitTouches, distinctFileCount, lastChangedAt, zone]` → `[zone, commitTouches, distinctFileCount, lastChangedAt]`) |
| `cli-runtime` | ordre seul | 1 ligne : racine de `renderSessions` (`[exhaustive, limitations, nature, sessions]` → `[nature, exhaustive, sessions, limitations]`) |
| `mcp` | ordre seul | 2 lignes : chaque profil de `providerProfiles` (7 profils ; `[buildSystems, capabilities, conformanceScorePercent, id, languages, limitations, runtimeDiagnostics, runtimeState, version]` → `[id, version, languages, buildSystems, capabilities, conformanceScorePercent, limitations, runtimeState, runtimeDiagnostics]`) |
| `mcp-all-tools` | ordre seul | 3 lignes : deux fois `providerProfiles` (7 profils) et la racine de `renderSessions` |

Résultat du comparateur : « every difference is a key-order difference » — aucune ligne ne diffère au-delà de l'ordre (ni clé ajoutée ou retirée, ni valeur modifiée). Aucun `capabilities` n'apparaît dans les changements d'ordre : il était et reste trié.

L'ordre des `Map`/`Set` des DTO Java (`api.golden`) reste trié par le rendu du test (`A2SurfaceCharacterizationTest.render`) : à ce niveau c'est un ensemble Java, pas un objet JSON ; l'ordre JSON est couvert par les golden `cli-*` et `mcp*`.

### 5.3 Mesures inter-JVM

Suite de caractérisation (`A2SurfaceCharacterizationTest`, 12 méthodes, golden comparés à l'octet, **ordre des clés non normalisé**), un lancement de JVM par ligne, `-DargLine` vérifié dans le rapport Surefire (`java.vm.compressedOopsMode` = « Zero based » avec `+UseCompressedOops`, absent avec `-UseCompressedOops`) :

| Code | Lancement | Résultat |
|---|---|---|
| **corrigé** (commit de garde) | `-XX:+UseCompressedOops` | 12/12 vert |
| **corrigé** | `-XX:-UseCompressedOops` | 12/12 vert |
| **corrigé** | `-XX:+UseCompressedOops` (2e lancement) | 12/12 vert |
| base `02e490c3` + normalisateur sans masque d'ordre | `-XX:+UseCompressedOops` | **6 échecs** : `cli-git`, `cli-json`, `cli-runtime`, `cli-text`, `mcp`, `mcp-all-tools` |
| base + normalisateur sans masque | `-XX:-UseCompressedOops` | 6 échecs (mêmes golden) |
| base + normalisateur sans masque | `-XX:+UseCompressedOops` (2e lancement) | 6 échecs (mêmes golden) |

Sur le code d'origine, les sorties « actuelles » des trois lancements sont **trois séries différentes** (empreintes distinctes d'un lancement à l'autre, identiques à l'intérieur d'un même lancement) : c'est bien le sel de `ImmutableCollections`, tiré à chaque démarrage, qui change l'ordre. Sur le code corrigé, les trois lancements sont identiques au golden, sous les deux réglages de pointeurs. (`cli-json` et `cli-text` ne rougissent que sans le masque : ils portent `capabilities`.)

Mesure de départ de `verif-code` (base, 12 lancements de `renderWorkspaces`, `renderMembers`, `renderAudit`, `renderSessions`) : **7 sorties distinctes**.

### 5.4 Consommateurs

- **MCP** (`providerProfiles`, `team_*`, `runtime_*`) : clients LLM/JSON-RPC qui lisent par clé ; l'ordre était déjà aléatoire, aucun ne pouvait s'y fier.
- **API Java** (`ProviderDto`) : `capabilities` reste une `Map<String, String>` ; l'itération est désormais triée (avant : aléatoire).
- **Plugin IntelliJ** (`MinosCliClient.gitActivity`, `MinosToolWindowPanel.loadGit`) : lit le JSON avec Gson et l'affiche indenté ; aucune lecture positionnelle, seul l'ordre d'affichage devient stable.
- **CLI** : sortie `--format json` de `git-activity`, `runtime sessions`, `providers`, `team workspaces|members|audit` : mêmes clés et mêmes valeurs, ordre fixe.

### 5.5 Fin de lot

`./mvnw clean verify` complet dans le worktree : **BUILD SUCCESS**, 15 modules, 1439 tests exécutés, 0 échec, 46 ignorés (hypothèses `Assumptions` préexistantes, aucun `@Disabled` ajouté). `python scripts/quality/check-jacoco.py` : 26 portées PASS ; seule la portée `m24-polyglot-provider-platform` échoue (`ManagedPolyglotScipRuntimeManager` line=0.232 < 0.28), échec préexistant sous Windows sans rapport avec ce lot. Aucun script de `scripts/` n'assertait de littéral renommé (`jsonEscape`, `quote`, `Double.toString` : aucune occurrence) ; `check-post-mne.py`, `check-hosted-control-plane-consistency.py` et `check-semantic-retrieval-consistency.py` rejoués verts.

## 6. Constats de verif-code

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| V-L1-01 | à corriger | `DeterministicJson.numberText` élargissait un `Float` fini en `double` (`0.1f` → `0.10000000149011612`) | `0814dcb8` : branche `Float` (refus des non-finis puis `Float.toString`) + test rouge/vert |
| V-L1-02 | remarque | U+2028 et U+2029 écrits bruts dans la source de `DeterministicJson` | `0814dcb8` : constantes `LINE_SEPARATOR`/`PARAGRAPH_SEPARATOR` (`0x2028`/`0x2029`) |
| V-L1-03 | remarque | `JsonEscapeGuardTest` itérait des `Map.of` (ordre du message d'échec variable) | `0814dcb8` : `LinkedHashMap` |
| V-L1-04 | bloquant | `Map.copyOf` sur `providerProfiles` (9 clés) et `capabilities` (14 clés) restés ouverts | `a8165f3f` : `Collections.unmodifiableMap` et `ProviderConformanceKit.sortedCopy` |

## 7. À traiter plus tard

- **Q13** : `CodeSearchRenderer`, `CodeIntelligenceResultRenderer`, `SymbolResultRenderer` écrivent encore leur JSON à la main (structure, virgules) ; seul leur échappement est unifié par le lot 1. `McpToolSchemas` assemble des schémas JSON par concaténation de littéraux (constantes, sans donnée externe) ; à revoir avec Q13.
- **Q13** : `ProviderConformanceKit.sortedCopy` est la seule implémentation de « copie triée » ; si une autre copie apparaît dans un module qui ne voit pas `minos-engine`, la remonter dans `minos-domain` plutôt que la dupliquer.
- `PostgresJsonCodec` (Jackson) et les gabarits `McpToolSchemas` échappent hors de `DeterministicJson` : Jackson est une bibliothèque, pas une réécriture à la main ; laissé en l'état, déclaré ici pour que la règle « un seul point d'échappement » ne soit pas lue comme plus large qu'elle n'est.
- **Sets à un élément** : la garde `JsonOrderGuardTest` n'examine `Set.of`/`Set.copyOf` que sur les renderers et la vue des providers ; les `Set.copyOf` des records du domaine ne sont pas rendus aujourd'hui, mais une garde par flux (type d'objet passé à `DeterministicJson.render`) serait plus sûre qu'une garde de source.

---

# Lot 2 — Q11, Q19, Q20 : un seul parseur d'arguments

> Branche : `code/q11-cli` (depuis `code/q6-q7-sorties`, `00ed1227`), worktree `minos-wt/code-q11`. Agents : `impl-code`, `verif-code`. Aucun push, aucune PR.
> Constats : **Q11** (parsing réimplémenté, règles divergentes, `doctor --help`, `doctor`/`tools verify`, `index --dry-run`, `IllegalArgumentException` d'un service → exit 2), **Q19** (garde de `team`), **Q20** (`--no-resume`, `--dry-run` et drapeaux de reprise), `AUDIT-2026-09.md` § 10.

## 8. Inventaire daté (base `00ed1227`, 30 septembre 2026)

### 8.1 Cibles de l'audit, relocalisées

| Cible de l'audit | Emplacement réel | Constat |
|---|---|---|
| parsing d'arguments « ~15 fois » | `minos-cli/src/main/java/com/minos/cli/` : **22 analyseurs écrits à la main** (mesure : `ArchitectureCommand`, `FindSymbolCommand`, `FindUsagesCommand`, `GetSourceCommand`, `GitActivityCommand`, `IdeCommand.parseFormat`, `IdeIntelligenceCommand.options`, `ImpactCommand`, `ImportScipCommand`, `IndexCommand`, `NexusExportCommand.parseRoot`, `ProjectCommand` ×3 (`parseFormatOnly`, `singleProject`, `AddOptions`), `ProviderCommand`, `RelationshipCommand`, `RemoteIndexCommand`, `RetrievalStatusCommand`, `RuntimeCommand`, `SearchCodeCommand`, `TeamCommand.parseOptions`, `ToolsCommand`, `DoctorCommand.parse`) | plus que les ~15 annoncés ; comptage de départ : 18 « missing value for », 14 « duplicate option », 12 ensembles `seen`, 47 tests de `--help` écrits à la main, 15 `startsWith("--")`, 2 `toLowerCase()` sans `Locale` (`ProviderCommand`, `RetrievalStatusCommand`) |
| `TeamCommand` « 17 `rejectUnknown` » | `TeamCommand.java` | **déjà résolu avant ce lot** par `7b7a571b` (« résidus sprint 1 », Q19) : table unique `OPERATIONS`, `rejectUnknown` en une occurrence dans `execute`, `audit --limit` borné par `MIN/MAX_AUDIT_LIMIT` → exit 2, garde `TeamCommandTest.everyDeclaredOperationRejectsUnknownOptionsBeforeReachingTheService`. Reste à faire pour Q19 : la garde repose sur une liste d'arguments valides recopiée dans le test, ne teste que l'option inconnue, et n'a jamais été prouvée par mutation ; `parseOptions` reste un huitième parseur distinct |
| `doctor --help` | `DoctorCommand.parse` : `--help` seul renvoie `TEXT` puis le diagnostic complet ; `MinosCliRunner.STATELESS_HELP_COMMANDS` ne contient pas `doctor` → `MINOS_HOME` ouvert (mesuré : sondes du § 8.3) | confirmé |
| `doctor` / `tools verify` | `DoctorCommand.run` (`ready` : tout provider `requiredByDefault` doit être `READY`) contre `ToolsCommand.run` (`UNSUPPORTED_BY_BACKEND` exclu du calcul) | confirmé ; le contrat documenté est celui de `tools verify` (`docs/developer/remote-worker-sandbox-disposition.md:38`) |
| `index --dry-run` | `LocalAutonomousIndexOperations.plan` prend le bail de projet (`stateStore.acquireProjectLease`) puis `prepare` → `alignedIndexState` peut sauvegarder un état « jamais indexé » | confirmé par lecture ; mesure par comparaison d'arbre dans les tests du commit correspondant |
| `IllegalArgumentException` d'un service → exit 2 | `IdeIntelligenceCommand.run` : un seul `try` couvre l'analyse et l'appel du service, `catch (IllegalArgumentException)` → `USAGE_ERROR` (mesuré : `ide semantic-index-status inconnu --format json` sort 2 « unknown project ») | confirmé ; les autres commandes séparent déjà analyse et exécution (`CliCommandSupport.run`) |
| `--no-resume` (Q20) | `IndexCommand.Options.parse` : `resumePolicy = NO_RESUME` ; `LocalAutonomousIndexOperations.executeLocked` : `forceFull ? lifecycle.execute : lifecycle.executePlanned` — le mode reste commandé par `--force-full` ; sur un projet sans changement, `--no-resume` répond `NO_CHANGES` | confirmé |
| `--dry-run` + drapeaux de reprise (Q20) | `IndexCommand.Options.parse` accepte `--dry-run --no-resume` et `--dry-run --resume-only`, `run` ne lit que `options.dryRun()` | confirmé |

### 8.2 Invocations réelles des consommateurs (à ne pas casser)

Relevées dans `minos-intellij` (`MinosCliClient`, `MinosM21Client`), `docs/user/cli.md` et les tests existants : `ide handshake --format json`, `project list|add <chemin> --format json`, `index-status|architecture|impact|find-symbol|find-usages|<relation> … --limit N --format json`, `index <projet> --force-full [--dry-run] --format json`, `doctor --format json` (codes 0 **et** 1 acceptés), `git-activity <projet> --days 30 --max-commits 500 --max-files 500 --zone-depth 2 --format json`, `ide program-graph|impact-v2|security-paths|semantic-*|hybrid-* <projet> [<requête>] [--max-nodes …] --format json`. Aucune n'utilise une forme que les règles uniformes refusent ; le plugin traite tout code non accepté de la même façon (échec), donc le passage de 2 à 1 pour une erreur de service sous `ide` ne change rien pour lui.

### 8.3 Matrice AVANT (mesurée, code de `00ed1227`)

Sonde jetable (`ArgumentMatrixProbe`, non commitée) : 29 commandes × formes d'invocation, exécutées par `MinosCliRunner.run` sur une vraie application ; « accepté » = l'analyse passe (le code 1 vient alors d'un projet inexistant). Toutes les autres cellules sont un exit 2 avec un message correct.

| Commande | Valeur manquante | Valeur `--x` | Option répétée | `--help` seul | Défaut relevé |
|---|---|---|---|---|---|
| `find-symbol`, `search`, `find-usages`, 6 relations, `index`, `remote`, `runtime`, `git-activity` (hors `--format`) | 2 « missing value » | 2 | 2 « duplicate option » | usage, `MINOS_HOME` intact | messages de borne divergents : `invalid limit: x`, `invalid value for --limit: x`, `--limit must be an integer`, `limit must be between…` |
| `get-source` | 2 **« unknown option: --format »** (faux) | 2 « unsupported output format » | 2 **« unknown option: --format »** (faux) | usage | `--format` seul jamais analysé comme option |
| `architecture` | 2 | **accepté** (`--module --x` : module = `--x`) | 2 | usage | valeur qui ressemble à une option avalée |
| `impact` | 2 | 2 (« invalid value ») | 2 | usage | idem pour la valeur : contrôle tardif |
| `project add` | 2 | **accepté** (`--name --x`) | 2 | usage | idem |
| `project list` | 2 **« only --format is supported »** (faux) | 2 | 2 message faux | usage | |
| `project inspect`, `inspect`, `index-status` | 2 **« unexpected arguments »** (faux) | 2 | 2 message faux | usage | |
| `import-scip` | 2 | **accepté** (`--module --x`) | 2 **« unknown or duplicate option »** | usage | message mixte |
| `tools list|verify` | 2 **« unexpected tools option: --format »** (faux) | 2 | **accepté** (`--format json --format json`) | usage | option répétée tolérée |
| `doctor` | 2 **« unexpected doctor arguments »** (faux) | 2 | 2 message faux | **diagnostic complet, `MINOS_HOME` OUVERT** | Q11 |
| `providers` | 2 « --format requires a value » | 2 (« unsupported format ») | **accepté** | usage | `toLowerCase()` sans `Locale` |
| `semantic status`, `hybrid status` | 2 « --format requires a value » | 2 | **accepté** | usage | `toLowerCase()` sans `Locale` |
| `nexus-export` | 2 « expected --root <project-root> » | 2 | 2 message faux | usage | |
| `ide handshake` | 2 **« only --format is supported after handshake »** (faux) | 2 | 2 message faux | usage | |
| `ide <opération>` | 2 | 2 | 2 | usage | exit **2** pour une erreur venue d'un service (« unknown project ») |
| `team <opération>` | 2 | 2 | 2 « duplicate team option » | usage | `team <op> --help` : « missing value for --help » |

Règles de comparaison observées : nom d'option sensible à la casse partout (`--FORMAT` inconnu) ; valeur d'un choix (`--format JSON`) insensible partout ; une valeur commençant par un tiret simple est acceptée comme valeur partout où l'analyse ne la refuse pas ensuite (`--module -x` accepté, `--limit -x` refusé par le contrôle numérique).

## 9. Décisions

### 9.1 Règles uniformes (`CliOptions`)

Un seul analyseur, `minos-cli/src/main/java/com/minos/cli/CliOptions.java`, dans `minos-cli` : il n'ajoute aucune dépendance de module (`check-module-boundaries.py` inchangé côté frontières). Chaque commande **déclare** ses options (`CliOptions.spec().text(…).integer(nom, min, max).flag(…)`), l'analyseur ne reçoit jamais de service.

| Règle | Choix | Pourquoi |
|---|---|---|
| Nom d'option | exact, sensible à la casse | déjà le cas partout ; rien à changer |
| Valeur d'un choix (`--format`, `--kind`, `--role`, `--worker-network`) | insensible, comparée avec `Locale.ROOT` | déjà le cas sauf `providers`/`semantic`/`hybrid` (`toLowerCase()` sans `Locale`) |
| Valeur manquante | fin des arguments, valeur vide **ou commençant par `--`** → `missing value for <option>` | règle de la majorité des commandes ; un `--x --format json` ne doit jamais avaler `--format` comme valeur |
| Valeur avec un seul tiret | c'est une valeur (`--module -x`) ; un nombre négatif atteint le contrôle de borne | déjà le cas ; un `-5` doit être signalé comme hors borne, pas comme « valeur manquante » |
| Option répétée | refusée pour toute option, valeur ou drapeau : `duplicate option: <option>` | 4 commandes la tolèrent aujourd'hui ; deux valeurs pour une option sont ambiguës |
| Option inconnue | `unknown option: <argument>` ; un tiret simple en position d'option (`-x`) est inconnu ; un argument nu en trop : `unexpected argument: <argument>` | messages exacts, plus de « unknown option: stray » pour un opérande |
| Bornes | déclarées avec l'option, contrôlées **à l'analyse** : `<option> must be an integer` / `<option> must be between <min> and <max>` (code 2) | Q19 : les bornes documentées dans l'usage sont des erreurs d'usage, jamais des erreurs d'exécution |
| `null` dans les arguments | `argument at index N must not be null` | comportement de `find-symbol` généralisé |
| `--help` / `-h` | argument **unique** : usage sur la sortie standard, code 0, **avant toute ouverture de `MINOS_HOME`**, pour toutes les commandes | Q11 |
| Code de sortie | 2 = erreur d'usage détectée à l'analyse ; 1 = tout échec après l'analyse, y compris une `IllegalArgumentException` venue d'un service | Q11 |

### 9.2 Changements observables assumés (durcissements)

Cette liste est reprise dans le rapport et la description de PR.

1. **Options répétées refusées** (code 2, `duplicate option`) là où elles étaient tolérées : `tools <action> --format a --format b`, `providers --format a --format b`, `semantic|hybrid status <p> --format a --format b`.
2. **Valeur commençant par `--` refusée** là où elle était avalée : `architecture <p> --module --x`, `project add <chemin> --name --x`, `import-scip … --module --x` (et les autres options à valeur de `import-scip`, `git-activity`, `impact`, `ide *`).
3. **Messages d'erreur** : uniformes (`missing value for --format` à la place de « unknown option: --format », « unexpected arguments », « only --format is supported », « unexpected doctor arguments », « expected --root <project-root> », « --format requires a value »…) ; bornes : `--limit must be between 1 and 1000` (au lieu de `limit must be…`), `--limit must be an integer` (au lieu de `invalid limit: x` / `invalid value for --limit: x`). Les codes de sortie ne changent pas.
4. `doctor --help` affiche l'usage (code 0) sans ouvrir `MINOS_HOME` ni lancer le diagnostic.
5. Les autres changements (sorties, codes, refus de formes d'invocation) sont ajoutés au fil des commits, § 10.
