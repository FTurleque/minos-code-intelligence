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
| 2 — Q11, Q19, Q20 | Un seul parseur d'arguments (`CliOptions`), garde de `team`, `--help` avant `MINOS_HOME`, `--dry-run` sans effet de bord, `--no-resume` | livré, en attente du verdict final de `verif-code` | `fcbcacd4` … `6debba27` (§ 10) |
| 3 — Q10, Q14 | Application fermée sur tous les chemins (routeur MCP, lanceur), code mort supprimé, garde de propriété, quatre exceptions avalées journalisées | livré, en attente du verdict final de `verif-code` | `eedd3bf8` … `748115f8` (§ 16) |
| 4 — Q12, Q13 | Heuristiques de tests liés (suffixe, répertoires), duplication (`requireText`, `sha256`, JSON à la main, mapping DTO, `LogCapture`), gardes | livré, en attente du verdict final de `verif-code` | `b9d57a3b` … `05228456` (§ 22) |

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
| parsing d'arguments « ~15 fois » | `minos-cli/src/main/java/com/minos/cli/` : **23 analyseurs écrits à la main** (mesure : `ArchitectureCommand`, `FindSymbolCommand`, `FindUsagesCommand`, `GetSourceCommand`, `GitActivityCommand`, `IdeCommand.parseFormat`, `IdeIntelligenceCommand.options`, `ImpactCommand`, `ImportScipCommand`, `IndexCommand`, `NexusExportCommand.parseRoot`, `ProjectCommand` ×3 (`parseFormatOnly`, `singleProject`, `AddOptions`), `ProviderCommand`, `RelationshipCommand`, `RemoteIndexCommand`, `RetrievalStatusCommand`, `RuntimeCommand`, `SearchCodeCommand`, `TeamCommand.parseOptions`, `ToolsCommand`, `DoctorCommand.parse`) | plus que les ~15 annoncés (le « 22 » de `fcbcacd4` et `3cb7ecfd` était un décompte faux d'une unité) ; comptage de départ : 18 « missing value for », 14 « duplicate option », 12 ensembles `seen`, 47 tests de `--help` écrits à la main, 15 `startsWith("--")`, 2 `toLowerCase()` sans `Locale` (`ProviderCommand`, `RetrievalStatusCommand`) |
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

### 9.2 Changements observables assumés

Rapport et description de PR reprennent cette liste. Les changements de **code de sortie** sont dans le tableau du § 11.2 (résultat de la sonde AVANT/APRÈS, pas une liste de mémoire).

1. **Options répétées refusées** (2, `duplicate option`) là où elles étaient tolérées : `tools <action> --format a --format b`, `providers --format a --format b`, `semantic|hybrid status <p> --format a --format b`.
2. **Valeur commençant par `--` refusée** (2, `missing value for …`) là où elle était avalée : `architecture <p> --module --x`, `project add <chemin> --name --x`, `import-scip … --module|--snapshot|--provider-version --x`, valeurs de `git-activity`, `impact`, `ide *`.
3. **`-x` en position d'option** : « unknown option: -x » (avant : opérande ou « unexpected argument ») ; par exemple `providers -x` (avant : identifiant de provider « -x », code 1 ; maintenant 2), `find-usages|<relation> <p> <s> -h` (V-L2-01 : message seulement).
4. **Messages d'erreur uniformes** (codes inchangés) : `missing value for --format` à la place de « unknown option: --format » (`get-source`), « unexpected arguments » (`project inspect`, `inspect`, `index-status`), « only --format is supported » (`project list`), « unexpected doctor arguments », « unexpected tools option », « expected --root … », « --format requires a value » (`providers`, `semantic|hybrid status`), « only --format is supported after handshake » ; bornes `--limit must be between 1 and 1000` / `--limit must be an integer` (avant : `limit must be …`, `invalid limit: x`, `invalid value for --limit: x`, `audit limit must be …`, `token-hours must be …`) ; `import-scip` : « unknown or duplicate option » scindé.
5. **`doctor --help`** affiche l'usage (0) sans ouvrir `MINOS_HOME` ni lancer le diagnostic ; **`<commande> <x> --help|-h`** (`tools install --help`, `team audit --help`, `runtime sessions --help`, `ide program-graph -h`, `find-symbol p --help`, `mcp --help`) sort 0 avec l'usage et sans créer `MINOS_HOME` (avant : erreur d'usage 2, ou `-h` pris pour un opérande, après création de `MINOS_HOME`).
6. **`doctor` et `tools verify`** : même verdict, `UNSUPPORTED_BY_BACKEND` ne bloque plus `doctor` (0 / `READY` au lieu de 1 / `ACTION_REQUIRED`).
7. **`ide <opération>`** : une erreur venue d'un service (projet inconnu…) sort **1** (avant 2, sans usage) ; les bornes hors plage (`--max-nodes 0`, `--minimum-score 2`…) restent 2, avec l'usage. Le plugin IntelliJ n'est pas concerné (`MinosCliClient.runJsonRaw` : tout code hors `{0}` est un échec, `{0,1}` pour `doctor`).
8. **`index --dry-run`** n'écrit plus rien (ni bail `index-state/locks/indexing/<id>.lock`, ni `index-state/projects/<id>.properties`, ni réparation d'état).
9. **`index --no-resume`** force un run complet (avant : `NO_CHANGES` sur un projet inchangé) ; **`index --dry-run --no-resume|--resume-only`** refusé (2) au lieu de l'ignorance silencieuse. `index <p> --dry-run --force-full` (forme du plugin IntelliJ) reste valide.
10. **`doctor`** : un échec du diagnostic lui-même sort 1 avec `error: doctor failed: …` (avant : remontait au lanceur, `error: MINOS bootstrap failed: …`, déjà code 1).
11. **`team … --token x`** : « bearer tokens are accepted only through MINOS_TEAM_TOKEN » est affiché tel quel (avant : « error: UsageException », le mot « bearer » déclenchait la redaction) ; `team retention-set --bogus x` dit « unknown team option » avant « missing required option ».

## 10. Journal par commit (lot 2)

Gates rejoués après chaque commit : `check-module-boundaries.py`, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, plus `scripts/remediation/check-post-mne.py` (qui rejoue les gates de jalon actifs) ; boundaries `modules=14, sources=500, packages=45` à partir de `3cb7ecfd` (**+1 source : `CliOptions.java`**, seul fichier de production ajouté par le lot), les trois autres gates inchangés (`SUCCESS`, `SUCCESS`, `scripts checked=95`).

| Commit | Contenu | Rouge (sortie jointe au message) |
|---|---|---|
| `fcbcacd4` | ce suivi : inventaire, matrice AVANT, règles | — |
| `3cb7ecfd` | `CliOptions` + find-symbol, search, get-source, find-usages, 6 relations | `SymbolCommandsArgumentRulesTest` : search 13, relations 5, find-symbol 5, find-usages 5, get-source 4 |
| `69d33300` | caractérisation des invocations valides des autres commandes (10 tests, verts sur la base) | — |
| `98fb85a0` | architecture, impact, git-activity, project ×3, import-scip, providers, semantic/hybrid status, nexus-export, ide handshake | Analysis/Administration…RulesTest : import-scip 15, project list 7, ide handshake 7, impact 8… |
| `118e7678` | index, tools, doctor, remote, runtime | `ExecutionCommandsArgumentRulesTest` : doctor 7, tools 7… |
| `4ec07c18` | team sur `CliOptions` ; garde Q19 dérivée de la table et de l'usage ; preuve par mutation | `TeamCommandsArgumentRulesTest` : audit 5, token-issue 5, retention-set 7 |
| `b14e0471` | `ide <opération>` : analyse séparée de l'exécution, IAE de service → 1 | `IdeIntelligenceCommandTest` 4/4 rouges |
| `8a115de8` | `--help` avant `MINOS_HOME`, table de routes unique de `MinosCli` (**commit rouge sur `check-post-mne`, voir V-L2-08**) | « doctor --help created MINOS_HOME » |
| `28abcd71` | correction : littéral `remoteIndexCommand.run` exigé par `check-remote-distributed-consistency.py` | — |
| `77ed369c` | verdict commun `doctor` / `tools verify` | `ProviderVerdictConsistencyTest` 2/3 rouges |
| `4d585a33` | `index --dry-run` sans effet de bord | `IndexDryRunSideEffectsTest` 2/2 rouges (bail + état) |
| `a90710bc` | garde Q19 : opération hors table, valeurs mal formées (V-L2-03, V-L2-04) ; retrait de deux exceptions périmées de `JsonOrderGuardTest` | 4 mutations tuées |
| `d9c8b920` | Q20 : `--no-resume` = run complet ; `--dry-run` refuse les drapeaux de reprise | `NoResumeIndexingTest` 1/2, `IndexCommandResumeFlagsTest` 1 |
| `68fe989f` | gardes de source du parseur unique + couverture de toute commande (Q19 étendu) | 3 mutations tuées |
| `d2d1d66f` | `--help` des sous-opérations et de `mcp` (V-L2-06, V-L2-07) | `StableCliHelpTest` 2/2 rouges |
| `1108ec2c` | ce suivi complété (journal, preuves, constats), `docs/user/cli.md` | — |
| `6debba27` | imports inutiles retirés (aucun changement de comportement) | — |

## 11. Preuves

### 11.1 Rouge → vert, et comptages

Comptage dans `minos-cli/src/main/java` (base `00ed1227` → fin de lot) : analyseurs d'options écrits à la main **23 → 0** (un seul : `CliOptions`) ; « missing value for » 18 → 2 (les deux sont dans `CliOptions`) ; définition du test d'aide `--help`/`-h` 47 occurrences → 1 (`CliCommandSupport.isHelp`) ; `startsWith("-…")` 18 → 6 (dont `CliOptions`, `CliCommandSupport.isOperand`, `IdeIntelligenceCommand.requirePositions` : exceptions nommées dans `CliParsingGuardTest`) ; ensembles `seen` de doublons 12 → 0 ; `toLowerCase()` sans `Locale` 2 → 0 ; `Integer.parseInt`/`Double.parseDouble` 8 → 4 (les quatre sont dans `CliOptions`, garde `CliParsingGuardTest`) ; lignes de production de `minos-cli` 6018 → 5734 malgré `CliOptions` et la table de routes (284 lignes en moins).

Les tests de règles (`*ArgumentRulesTest`, toutes les commandes de premier niveau, dérivés par `CliArgumentRules` de la description de chaque commande) ont tous été joués **avant** la migration de leur groupe sur le code d'origine : rouge sortie dans le message de chaque commit (§ 10), vert après.

### 11.2 Matrice APRÈS et changements de code de sortie (sonde AVANT/APRÈS)

Même sonde que le § 8.3 rejouée en fin de lot : 338 cellules comparées ; **12 changements de code de sortie**, 93 changements de message seul, aucun autre écart.

| Commande | Forme | Code AVANT → APRÈS | Raison |
|---|---|---|---|
| `architecture` | `--module --x` | 1 → 2 | valeur ressemblant à une option refusée |
| `project add` | `--name --x` | 1 → 2 | idem |
| `import-scip` | `--module --x` | 1 → 2 | idem |
| `tools list` | `--format json --format json` | 0 → 2 | option répétée refusée |
| `tools verify` | `--format json --format json` | 1 → 2 | idem (l'analyse précède le diagnostic) |
| `providers` | `--format json --format json` | 0 → 2 | idem |
| `semantic status`, `hybrid status` | `--format json --format json` | 1 → 2 | idem |
| `doctor` | `--help` | 1 → 0 | usage, sans diagnostic ni `MINOS_HOME` |
| `ide semantic-index-status`, `ide program-graph` (et toute opération `ide`) | `--format JSON` sur un projet inconnu | 2 → 1 | erreur de service = erreur d'exécution |

Hors sonde, mesurés par tests : `providers -x` 1 → 2, `find-symbol p --help` (et `<commande> <x> --help`) 2 → 0, `index p --dry-run --no-resume|--resume-only` 0 → 2, `doctor` sur un provider requis `UNSUPPORTED_BY_BACKEND` 1 → 0, `index p --no-resume` sur un projet inchangé `NO_CHANGES` → run complet.

### 11.3 Garde Q19 — preuve par mutation (temporaire, annulée à chaque fois)

| Mutation | Résultat |
|---|---|
| 18e opération « audit-export » documentée avec `--limit <1..10000>` mais sans borne à l'analyse | `TeamOperationGuardTest` 2 rouges : « audit-export --limit 0 -> exit 1 error: audit limit must be between 1 and 10000 » |
| analyse qui tolère ce qu'elle refuse (le défaut des 17 `rejectUnknown` oubliés) | `TeamOperationGuardTest` 4 rouges, `TeamCommandTest` 2 : « audit --limit 0 -> exit 0 » |
| opération traitée hors de la table (`if ("ping".equals(arguments[0]))` dans `run`) | `TeamCommandDispatchGuardTest` 2 rouges (V-L2-03) |
| `role()` lève une `IllegalStateException` ; `role()` accepte tout ; UUID jamais contrôlé | `TeamOperationGuardTest` 1 rouge chacune (+ `TeamCommandTest` 2 pour l'UUID) (V-L2-04) |
| route « ghost » ajoutée à `MinosCli` | `CliParsingGuardTest.everyTopLevelCommand…` et `StableCliHelpTest.theCommandTableMatchesTheTopLevelUsage` rouges |
| `startsWith("--limit")` + `Integer.parseInt` dans `ProviderCommand` ; `toLowerCase()` sans `Locale` | `CliParsingGuardTest` 2 et 1 rouges |

Les sorties des mutations sont dans le scratchpad de la session (`lot2-mutation*.log`).

### 11.4 Fin de lot

`./mvnw clean verify` complet dans le worktree (journal dans le scratchpad, pas dans `target/`) : **BUILD SUCCESS**, 15 modules, 12 min 54, **1528 tests exécutés, 0 échec, 0 erreur, 46 ignorés** (hypothèses `Assumptions` préexistantes, aucun `@Disabled` ajouté ; 1439 tests à la fin du lot 1, +89 : `CliOptionsTest` 17, les `*ArgumentRulesTest`, `TeamOperationGuardTest`, `TeamCommandDispatchGuardTest`, `CliParsingGuardTest`, `StableCliHelpTest`, `ProviderVerdictConsistencyTest`, `IndexDryRunSideEffectsTest`, `NoResumeIndexingTest`…). Rejoué une seconde fois sur le SHA final (`6debba27`, qui ne diffère de `1108ec2c` que par des imports retirés) : même résultat. `python scripts/quality/check-jacoco.py` : 26 portées vertes, **seule rouge : `m24-polyglot-provider-platform`** (`ManagedPolyglotScipRuntimeManager` 0,232 < 0,28), préexistante et propre à Windows. Gates : boundaries `modules=14, sources=500, packages=45` (+1 : `CliOptions.java`), current-docs, product-facts, milestone-artifact-references (95), `check-post-mne.py` : verts. Golden : les 12 de `characterization/` **inchangés** (`A2SurfaceCharacterizationTest` 12/12, aucune sortie JSON ne change dans ce lot).

## 12. Constats de verif-code (lot 2)

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| V-L2-01 | remarque | `-h` en position d'option : « unexpected argument: -h » → « unknown option: -h » (`find-usages`, relations), non listé au message de commit | listé au § 9.2 (3) |
| V-L2-02 | remarque | le message de `98fb85a0` (« Aucun code de sortie ne change ») était inexact : 9 invocations changeaient de code | tableau AVANT → APRÈS du § 11.2 (mesuré, 12 codes) |
| V-L2-03 | à corriger | une sous-commande gérée hors de `OPERATIONS` atteignait le service sans être vue de la garde | `a90710bc` : `TeamCommandDispatchGuardTest` (scan du code d'instance : service et jeton uniquement dans l'unique `invocation.run(service, this::token)`, jamais de comparaison à un littéral) ; vérifié par mutation par `verif-code` |
| V-L2-04 | à corriger | valeurs mal formées (`--role bogus`, UUID) non verrouillées en code 2 | `a90710bc` : classement `MALFORMED` / `FREE_TEXT` de toute option texte ; vérifié par mutation |
| V-L2-05 | remarque | la caractérisation « verte sur la base » contenait un échantillon invalide (`ide hybrid-context … --max-tokens 100`, déjà refusé par le domaine) que l'assertion lâche des commandes `ide` laissait passer ; corrigé en 200 par `b14e0471` sans le dire | reconnu ici : `CliValidInvocationsTest` reste vert sur la base pour tout le reste ; l'échantillon corrigé est valide sur la base aussi |
| V-L2-06 | à corriger | `<commande> <sous-opération> --help` ouvrait `MINOS_HOME` | `d2d1d66f` : règle « dernier des trois arguments au plus » |
| V-L2-07 | remarque | `mcp --help` ouvrait `MINOS_HOME` puis sortait 2 | `d2d1d66f` : `mcp --help` affiche « Usage: minos mcp » |
| V-L2-08 | remarque | le message de `8a115de8` annonçait `check-post-mne` vert alors que le commit était rouge (`check-remote-distributed-consistency.py:147` exige `remoteIndexCommand.run`) | `28abcd71` (correction séparée, gate inchangée) ; leçon : rejouer `check-post-mne` **avant** chaque commit ; l'historique n'a pas été réécrit |
| V-L2-09 | à corriger (réserve du verdict) | Javadoc écrite en anglais dans `MinosCli.java` et `ProjectCommand.java` (fichiers en français), et mélange de langues dans `ToolsCommand`, `CliParsingGuardTest`, `IndexDryRunSideEffectsTest`, `NoResumeIndexingTest` | réécrite en français dans la langue de chaque fichier (commit « docs(cli): Javadoc dans la langue du fichier ») ; balayage automatique des fichiers modifiés : plus aucun fichier à Javadoc mixte |

## 13. À traiter plus tard (lot 2)

- **Câblage eager du CLI** : `MinosCliRunner.run(MinosApplication…)` construit `LocalRemoteIndexOperations`, `ProviderPlatformService`… pour toute commande, même en lecture (`distributed-artifacts/.leases` est créé par `project list`) ; un câblage paresseux rendrait tout `MINOS_HOME` intact pour les commandes de lecture. Relevé par `IndexDryRunSideEffectsTest` (le test l'écarte par une première commande de lecture). Concerne le lot 3 (cycle de vie).
- **Erreurs d'usage qui ouvrent `MINOS_HOME`** : seule l'aide est traitée sans état ; `find-symbol --bogus` ouvre `MINOS_HOME` avant de refuser l'option. L'analyse avant ouverture demanderait de séparer analyse et exécution au niveau du dispatcher (l'analyseur est déjà sans service).
- **`RetrievalStatusCommand` et `DoctorCommand`** : l'`IOException` d'un service remonte hors de `run` (pas de code 1 avec `error:`), contrairement aux autres commandes ; comportement conservé (hors périmètre).
- **`--help` au-delà de trois arguments** (`team member-grant --principal x --help`) reste une option inconnue (2) et ouvre `MINOS_HOME` ; un `--help` au milieu d'une ligne n'a jamais été un cas d'aide.
- **Bornes MCP de `minos_team_audit`** (`McpToolSchemas.java:141`, `MinosMcpTools.java:417`) dupliquées hors de `MIN/MAX_AUDIT_LIMIT` : remarque W8 des résidus du sprint 1, toujours ouverte (zone `minos-mcp`).
- **`scripts/history/m21/check-m21-parity.py`** ne résout plus ses chemins (« missing required file: minos-cli/…/IdeCommand.java » alors que le fichier existe) : gate d'historique cassée avant ce lot, non rejouée par `check-post-mne`.
- **Sonde et guides** : la sonde `ArgumentMatrixProbe` (29 commandes × formes) n'est pas commitée ; `CliArgumentRules` en est la version permanente et déclarative.

---

# Lot 3 — Q10, Q14 : cycle de vie des ressources et exceptions avalées

> Branche : `code/q10-q14-cycle-de-vie` (depuis `code/q11-cli`, `5c019e93`), worktree `minos-wt/code-q10`. Agents : `impl-code`, `verif-code`. Aucun push, aucune PR.
> Constats : **Q10** (`McpBackendRouter` et `MinosLauncher` n'ont jamais fermé la `MinosApplication` qu'ils ouvrent ; `LazyAutonomousIndexOperations`, code mort), **Q14** (quatre exceptions avalées), `AUDIT-2026-09.md` § 10.

## 14. Inventaire daté (base `5c019e93`, 30 septembre 2026)

### 14.1 Cibles Q10, relocalisées

| Cible de l'audit | Emplacement réel | Constat mesuré |
|---|---|---|
| `McpBackendRouter` (« aujourd'hui dans `minos-app` ») | `minos-app/src/main/java/com/minos/app/McpBackendRouter.java` ; le constructeur par défaut câblait `home -> MinosMcpServer.run(MinosApplication.open(home))` | `run(MinosApplication)` **ne prend pas** la propriété de l'application (sa Javadoc le dit) : l'application ouverte n'était fermée ni à la fin de la session MCP, ni sur exception. **Durée de vie réelle** : le serveur répond aux requêtes jusqu'à la fin de l'entrée standard (`stdin.awaitEnd()`), `run(application)` ne rend la main qu'à ce moment : l'application doit rester ouverte pendant toute la session et être fermée **une** fois après |
| `MinosLauncher` | `minos-cli/src/main/java/com/minos/cli/MinosLauncher.java` (`main`) | `MinosApplication.open(home)` puis `run(application, …)` sans fermeture, quel que soit le chemin ; `System.exit` n'exécute aucune fermeture. L'application n'est partagée avec aucun serveur (la commande est terminée quand `run` rend la main) |
| `LazyAutonomousIndexOperations` | `minos-cli/src/main/java/com/minos/cli/LazyAutonomousIndexOperations.java` | **code mort confirmé** par `grep -rI "LazyAutonomousIndex"` sur tout le dépôt (sources, tests, `scripts/`, `docs/`, poms, hors `target/`) : aucune référence en dehors d'elle-même et des suivis d'audit (`ARCHI-SUIVI`, `SPRINT-2-SUIVI`, `AUDIT`, historiques). Aucun script n'asserte de littéral de ce fichier |

### 14.2 Tous les sites qui ouvrent une `MinosApplication` (production)

Recherche : `MinosApplication.open|builder`, `MinosApplication::open`, `openApplication`, sur `src/main` de tous les modules, `scripts/`, `benchmarks/`, `minos-intellij/` (aucune occurrence dans ces deux derniers).

| Site | Qui ferme, sur quels chemins | Avant le lot | Après le lot |
|---|---|---|---|
| `McpBackendRouter` (runner natif) | le routeur (`serving()`) : fin de session **et** exception ; l'application vit toute la session MCP | personne | `try`-avec-ressources ; `McpBackendRouterLifecycleTest` |
| `MinosLauncher.main` (commandes ordinaires) | le lanceur (`launch()`) : succès, code d'erreur, exception | personne | `try`-avec-ressources ; `MinosLauncherLifecycleTest` |
| `MinosLauncher.main` (`--version`, `--help`, aide sans état, `ide handshake`, `mcp`) | n'ouvre rien (`mcp` est routé avant toute ouverture ; son application appartient au routeur) | déjà correct | inchangé, verrouillé par `commandsThatNeedNoApplicationNeverOpenOne` |
| `MinosCliRunner.run(Path, …)` | `try`-avec-ressources | déjà correct | inchangé |
| `MinosMcpServer.run(Path)` / `main` | `try`-avec-ressources (le serveur est fermé avant l'application) | déjà correct | inchangé |
| `MinosMcpTools(Path)` | propriétaire : `close()` ferme `ownedApplication` ; le constructeur ne fait plus que `new MinosApplicationMcpBackend(app)` | déjà correct | déclaré dans la garde |
| `LocalMinosApi(Path)`, `LocalMinosMultiRepositoryApi(Path)`, `LocalProviderPlatformApi(Path)` (via `MinosApiSupport.openApplication`) | propriétaires : `close()` ferme l'application (`IO_FAILURE` publique si la fermeture échoue) ; constructeurs : accesseurs seulement | déjà correct | déclarés dans la garde |
| `LocalProjectOperations(Path)`, `LocalAutonomousIndexOperations(Path)` | propriétaires : `close()` ; **aucun appelant de production** de `LocalAutonomousIndexOperations(Path)` une fois `Lazy…` supprimée, un seul test pour `LocalProjectOperations(Path)` | correct | déclarés dans la garde ; constructeurs publics conservés (voir § 19) |
| `LazyAutonomousIndexOperations` | aurait fuité (`new LocalAutonomousIndexOperations(home)` possède l'application, jamais fermée) | code mort | **supprimée** |
| Non propriétaires (`MinosCliRunner.run(MinosApplication, …)`, `MinosMcpServer.run(MinosApplication)`, `MinosMcpApplicationTools`, `Local*(MinosApplication)`, `ProviderPlatformService.defaults`, `LocalRemoteIndexOperations`, `new LocalAutonomousIndexOperations(app[, décorateur])`) | ne ferment pas : ils reçoivent l'application de leur appelant ; aucune ne retient de ressource fermable | correct | inchangé |
| `scripts/history/m16`, `scripts/history/m21` (sondes de performance, hors build) | n'ouvrent pas de `try` | hors compilation | non touchées (§ 19) |

La garde `ApplicationOwnershipGuardTest` (§ 15) transforme cet inventaire en test : une nouvelle création hors `try`-avec-ressources ou hors propriétaire déclaré fait échouer la suite.

### 14.3 Cibles Q14, relocalisées

| Cible de l'audit | Emplacement réel | Ce qui était avalé |
|---|---|---|
| `LocalProjectOperations.writeHistory` | `minos-application/src/main/java/com/minos/application/LocalProjectOperations.java` (appel dans `importScip`) | `catch (IOException ignored) {}` autour de l'écriture de `cli-index-history/<projet>.properties` |
| `DefaultDiscoveryPlugins.visibleFile` | `minos-engine/src/main/java/com/minos/discovery/DefaultDiscoveryPlugins.java` | `catch (IOException \| SecurityException) { return false; }` autour de l'ouverture confinée d'un marqueur |
| `FileIndexStateStore.migrateLegacyRuns` | `minos-storage-local/src/main/java/com/minos/storage/local/orchestration/FileIndexStateStore.java` (l'audit visait le module d'avant A3) | deux `continue` muets : nom de fichier qui n'est pas un UUID, métadonnées corrompues |
| `ExecutionPathAuthorization.tryCapture` | `minos-engine/src/main/java/com/minos/orchestration/IndexingRuntimePorts.java` (record imbriqué de `IndexingRuntimePorts`) | `catch (IOException) { return Optional.empty(); }` |

## 15. Décisions

### 15.1 Q10 — propriété et suppression

- **Propriétaire clair, `try`-avec-ressources** partout où une application est ouverte pour la durée d'une méthode ; **propriétaire nommé** (objet `AutoCloseable` qui la possède) là où elle vit plus longtemps. Aucun `close()` ajouté « pour la forme » : `LazyAutonomousIndexOperations` est **supprimée**, pas rendue `AutoCloseable`.
- **Application partagée avec un serveur en cours** : jamais fermée avant la fin de la session. Le routeur MCP ferme *après* le retour de `MinosMcpServer.run(application)` (fin de l'entrée standard) ; les surfaces qui **reçoivent** une application (`run(MinosApplication)`, `Local*(MinosApplication)`) ne la ferment pas.
- **Fermetures des propriétaires** (`MinosMcpTools`, façades de `minos-api`, `LocalProjectOperations(Path)`, `LocalAutonomousIndexOperations(Path)`, `MinosMcpServer.run(Path)`) : `CloseCountingStorageProvider` (test-jar de `minos-bootstrap`) est un fournisseur de stockage « postgresql » qui enveloppe le stockage local et compte `close()` ; `MinosApplication.open(home)` le sélectionne quand `minos.storage.backend=postgresql` (propriété système, le temps d'un test) et que le module de test déclare le fournisseur dans `META-INF/services` (`minos-api`, `minos-mcp`, `minos-cli` ; jamais `minos-bootstrap`, où le vrai fournisseur PostgreSQL est sur le chemin de test). Aucun point d'injection en production.
- **Ouverture et exécution injectées** (`ApplicationOpener`, `ServerRunner`, `CommandRunner`) pour observer la fermeture : `MinosApplication` est finale et `open` statique, la fermeture est donc observée sur le **magasin de stockage** que `close()` ferme (un `StorageBackend` espion par `Proxy`, comme `A2CompositionCharacterizationTest`). Le câblage de production (`MinosApplication::open`) n'est verrouillé que par la garde de source.
- **Échec de fermeture au lanceur** (nouveau, déclaré) : si `close()` échoue après une commande, le lanceur dit `error: MINOS bootstrap failed: <message>` et sort 1, comme `MinosCliRunner.run(Path, …)` le fait déjà pour ses appelants. Jamais le cas du stockage local (`close()` ne fait rien) ; possible avec PostgreSQL.

### 15.2 Q14 — un choix explicite par site

Règle du lot : une capture délibérée reste **journaliser et continuer** (flux inchangé) ; transformer un silence en échec sur un chemin chaud ou de découverte est une régression. Journalisation : `System.Logger` (idiome des modules `minos-engine`, `minos-runtime-local`), niveau WARNING, message `MINOS …` avec la **classe** de l'exception (jamais son message, qui contient les chemins), sans chemin absolu.

| Site | Intention (lue au code appelant) | Choix | Raison | Bruit |
|---|---|---|---|---|
| `writeHistory` | l'historique est une preuve **secondaire** (`ProjectInspectionService.readHistory` s'en sert pour l'affichage fournisseur/version) ; l'instantané actif et l'état, déjà commités, font foi (commentaire d'origine) | **journaliser et continuer** | propager ferait échouer un import déjà valide et commité (l'utilisateur relancerait un import réussi) pour un champ d'affichage ; le silence cachait disque plein / droits / fichier à la place du répertoire | site froid (une fois par import) : aucune borne |
| `visibleFile` | sonde de **tous** les détecteurs, pour chaque marqueur de chaque répertoire ; « faux » = « pas un marqueur visible » ; un fichier illisible ne doit jamais faire échouer la découverte | **journaliser et continuer** | propager = un seul `pom.xml` sans droits fait échouer la découverte du projet (régression) ; le silence cachait « pourquoi mon module n'est pas détecté » | **site chaud** : `NoSuchFileException` (cas normal) et non-fichier régulier (répertoire, lien : la découverte sonde aussi des entrées de répertoire) restent **muets** ; seul un fichier **régulier** qui existe et ne s'ouvre pas est journalisé, **une fois par fichier**, **dix au plus par découverte**, puis une synthèse |
| `migrateLegacyRuns` | tourne dans le **constructeur** du magasin (chaque ouverture) ; un fichier hérité corrompu ne doit jamais empêcher l'ouverture ni la migration des autres (même règle que `readRunFormatVersion`) | **journaliser et continuer** | propager = un fichier corrompu bloque tout le `MINOS_HOME` | fichier définitivement corrompu retrouvé à chaque ouverture (pas d'état entre processus) : **dix traces au plus par ouverture**, puis une synthèse avec le total |
| `tryCapture` | des tests de contrat construisent volontairement des requêtes sur des chemins non matérialisés ; les ports sans processus gardent le contrat historique ; `ProcessIndexerExecutor` refuse l'absence d'autorisation avant le lancement | **journaliser et continuer** | propager casserait ces ports et l'API historique de la requête ; mais le refus au lancement (« not canonically authorized ») **ne porte aucune cause** : la trace est le seul moyen de distinguer racine supprimée / droits | une fois par requête d'exécution : aucune borne |

## 16. Journal par commit (lot 3)

Gates rejoués après chaque commit : `check-module-boundaries.py`, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, `scripts/remediation/check-post-mne.py`. Boundaries `modules=14, sources=500, packages=45` jusqu'à la suppression de `LazyAutonomousIndexOperations`, puis `sources=499` (un fichier de production en moins, aucun ajouté) ; les quatre autres gates inchangés (`SUCCESS`, `SUCCESS`, `scripts checked=95`, `SUCCESS`).

| Commit | Contenu | Rouge (sortie jointe au message) |
|---|---|---|
| `eedd3bf8` | Q10 : le routeur MCP ferme l'application (`serving()`) | `McpBackendRouterLifecycleTest` 3/4 : `expected: <[served, close]> but was: <[served]>` |
| `faa9ec7b` | Q10 : le lanceur ferme l'application (`launch()`) | `MinosLauncherLifecycleTest` 5/6 : `expected: <[open, close]> but was: <[open]>` |
| `77aacabe` | Q10 : garde de source `ApplicationOwnershipGuardTest` | 2 mutations (fermeture retirée, `open` hors `try`) |
| `b47427c9` | V-L3-01 : la garde verrouille le câblage de production | mutation : constructeur par défaut du routeur remis à la fuite |
| `1f843e3f` | Q10 : suppression de `LazyAutonomousIndexOperations` | — (suppression de code mort ; `sources` 500 → 499) |
| `b94c4a5f` | Q14 : `writeHistory` journalise | `ImportHistoryFailureTest` : `expected: <1> but was: <0>` |
| `1bc9cd59` | V-L3-02 : la garde prouve la fermeture par le code (créations comptées, fragments hors commentaires) | 3 mutations (Ma, Mb, Mc) toutes rouges |
| `d8cf0e5b` | Q14 : `visibleFile` journalise (fichier régulier illisible seulement) | `UnreadableMarkerDiscoveryTest` 2/3 rouges (silence) |
| `d823aea7` | Q14 : `migrateLegacyRuns` journalise (borné) | `LegacyRunMigrationDiagnosticsTest` 2/3 : `expected: <2> but was: <0>`, `<11>` / `<0>` |
| `748115f8` | Q14 : `tryCapture` journalise la cause | `ExecutionPathAuthorizationDiagnosticsTest` : `expected: <1> but was: <0>` |
| `51120629` | ce suivi : inventaire, décisions, journal | — |
| `7e46ef07` | V-L3-03 : fermetures des propriétaires (`minos-api`, `minos-mcp`, `minos-cli`) verrouillées par le comportement, fixture `CloseCountingStorageProvider` | 7 mutations de production toutes rouges (`expected: <1> but was: <0>`) |

## 17. Preuves

### 17.1 Changements observables

1. **Fermeture** : `minos mcp` (natif) ferme l'application à la fin de la session ; toute commande ordinaire la ferme au succès, sur code d'erreur et sur exception. Sans effet visible avec le stockage local (`close()` ne fait rien) ; avec un backend qui tient une ressource (PostgreSQL) elle est désormais libérée.
2. **Échec de fermeture** (nouveau chemin, jamais atteint avec le stockage local) : `error: MINOS bootstrap failed: <message>`, exit 1.
3. **Nouvelles lignes de journal** (WARNING, `System.Logger` → console JUL par défaut, donc sortie d'erreur ; jamais la sortie standard, donc jamais le protocole MCP) : `LocalProjectOperations` (échec d'écriture de l'historique d'import), `ProjectIgnorePolicy` (fichier marqueur illisible, dix au plus par découverte), `FileIndexStateStore` (fichier de run hérité ignoré, dix au plus par ouverture), `ExecutionPathAuthorization` (racines non résolues). Aucune autre sortie ne change.
4. Les **12 golden** de `characterization/` ne bougent pas ; `JsonOrderGuardTest`, `JsonEscapeGuardTest` et les gardes CLI du lot 2 restent verts.

### 17.2 Rouge → vert par site

Chaque test a été joué **avant** le correctif sur le code d'origine (sortie dans le message du commit) ; le flux inchangé (import réussi, découverte aboutie, ouverture du magasin, `Optional.empty()`) est asserté dans le **même** test et passait déjà à l'état rouge : seul le silence était rouge. Les cas normaux qui doivent rester muets (marqueur absent, entrée de répertoire, run hérité valide, racines résolubles, écriture d'historique réussie) sont verts des deux côtés.

Une première version de `visibleFile` journalisait toute exception : mesurée sur un projet ordinaire, elle produisait `could not read the project file 'src'`, `'src\main'`, `'src\main\java'` (`AccessDeniedException`, la découverte sonde des entrées de répertoire). Corrigée avant le commit (fichier régulier seulement) ; le test « marqueur absent et entrées de répertoire → silence » verrouille ce cas.

### 17.3 Fin de lot

`./mvnw clean verify` complet dans le worktree (journal dans le scratchpad, pas dans `target/`) sur `bc2fcf24` : **BUILD SUCCESS**, 15 modules, 11 min 8, **1562 tests exécutés, 0 échec, 0 erreur, 46 ignorés** (hypothèses `Assumptions` préexistantes, aucun `@Disabled` ajouté ; 1528 tests à la fin du lot 2, +34 : routeur 4, lanceur 6, garde de propriété 2, historique 2, découverte 4, migration 3, `tryCapture` 2, fermetures des propriétaires 11). Les commits suivants ne touchent que ce fichier. `python scripts/quality/check-jacoco.py` : 26 portées vertes, **seule rouge : `m24-polyglot-provider-platform`** (`ManagedPolyglotScipRuntimeManager` 0,232 < 0,28), préexistante et propre à Windows. Gates : boundaries `modules=14, sources=499, packages=45`, current-docs, product-facts, milestone-artifact-references (95), `check-post-mne.py` : verts. Golden : les 12 de `characterization/` inchangés.

## 18. Constats de verif-code (lot 3)

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| V-L3-01 | à corriger | le câblage de production du routeur (`this(serving(MinosApplication::open, MinosMcpServer::run), …)`) n'était verrouillé par aucun test : remettre la fuite d'origine laissait tout vert | `b47427c9` : fragments de câblage dans la garde (une vraie application ne s'ouvre pas avec un magasin espion) ; mutation rejouée rouge |
| V-L3-02 | à corriger | la garde exemptait un fichier propriétaire entier et sa « preuve » (`if (!ownsApplication) return;`) ne prouvait pas l'appel à `close()` ; trois mutations restaient vertes | `1bc9cd59` : nombre de créations figé par propriétaire, fragments cherchés dans le code sans commentaires (`ownedApplication.close()`, `application.close()`, création possédée), façades de `minos-api` déclarées fermeurs ; les trois mutations rejouées sont rouges |
| V-L3-03 | à corriger | aucune fermeture propriétaire (`MinosMcpTools`, `LocalMinosApi`, `LocalMinosMultiRepositoryApi`, `LocalProviderPlatformApi`, `LocalProjectOperations`, `LocalAutonomousIndexOperations`) n'était verrouillée par un test de comportement : `if (ownedApplication != null && false) …` et `ownsApplication = false` laissaient les suites vertes | `7e46ef07` : `CloseCountingStorageProvider` (fournisseur « postgresql » de test qui compte les fermetures, sélectionné par la propriété `minos.storage.backend`), un `OwnedApplicationLifecycleTest` par module ; sept mutations de production rejouées, toutes rouges |
| V-L3-04 | remarque (déclaré) | un échec de FERMETURE après une commande déjà exécutée fait sortir le lanceur en 1 (`error: MINOS bootstrap failed`), même si la commande avait réussi ou refusé l'usage | conservé et déclaré (§ 15.1, § 17.1) : aligné sur `MinosCliRunner.run(Path, …)`, jamais atteint avec le stockage local, et un échec de libération d'une ressource de stockage ne doit pas passer pour un succès ; alternative « journaliser et garder le code de la commande » laissée à la décision produit |
| V-L3-05 | remarque (déclaré) | un fichier de run hérité corrompu produit dix WARNING plus une synthèse à chaque ouverture du magasin, indéfiniment | conservé et déclaré (§ 15.2, § 19) : borné, et abaisser le plafond ou mettre le fichier en quarantaine change le flux ou la lisibilité du diagnostic ; à revoir si le bruit est signalé |
| V-L3-06 | remarque (déclaré) | trois copies de `LogCapture` en test | déclaré (§ 19) : pas de test-jar partagé entre `minos-cli`, `minos-engine` et `minos-storage-local` ; à mutualiser avec Q13 |
| V-L3-07 | remarque (info) | comptage des `catch` sans trace dans les quatre classes : 13 → 9 ; aucun silence nouveau hors la branche `NoSuchFileException`, justifiée | rien à corriger ; `containsVisibleMarkerExtension` et `isPhysicalDirectory` restent en § 19 |

## 19. À traiter plus tard (lot 3)

- **Câblage eager du CLI** (relevé au lot 2) : vérifié, il ne retient aucune ressource fermable (`LocalRemoteIndexOperations`, `ProviderPlatformService`, `LocalAutonomousIndexOperations(app)` ne fermeraient rien) ; ce n'est donc **pas** une application non fermée et Q10 n'est pas concerné. Reste ouvert au titre de l'effet de bord (`distributed-artifacts/.leases` créé par `project list`).
- **Autres captures muettes des mêmes classes**, non traitées (hors des quatre sites nommés) : `DefaultDiscoveryPlugins.containsVisibleMarkerExtension` (`IOException` → `false`) et `isPhysicalDirectory` (idem ; cas normal = répertoire absent, sonde chaude) ; `FileIndexStateStore` : `readRunFormatVersion` (version illisible → héritée, documenté V4), les `catch … Optional.empty()` de lecture d'options, `migrateRunLocators` (nom de partition non UUID, nom de fichier corrompu : commentés). Un marqueur qui est un **lien** symbolique refusé par le confinement reste silencieux (refus de politique, pas erreur d'E/S). Le rapporteur `ProjectIgnorePolicy.reportUnreadable` est fait pour être réutilisé.
- **Constructeurs publics à propriétaire sans appelant de production** : `LocalAutonomousIndexOperations(Path)` (plus aucun appelant de production, `Lazy…` supprimée) et `LocalProjectOperations(Path)` (un test). Conservés (constructeurs publics, correctement propriétaires, déclarés dans la garde) ; retrait à décider avec l'API publique.
- **Sites de test** qui ouvrent une `MinosApplication` sans `try`-avec-ressources : 69 sur 97 (`minos-bootstrap` 33, `minos-cli` 8, `minos-api` 11, `minos-app` 7, `minos-application` 3, `minos-mcp` 5, `minos-nexus` 2). Sans effet avec le stockage local ; à traiter si les tests PostgreSQL le demandent.
- **Historique** : `scripts/history/m15/run-s3.ps1` (jamais joué, chemin `minos-app\…\MinosLauncher.java` périmé depuis A3) exige `MinosApplication application = MinosApplication.open(home);` et `MinosMcpServer.run(application);` dans le lanceur : déjà faux avant ce lot, aucun gate ne le lit. `scripts/history/m16` et `m21` (sondes hors build) ouvrent sans fermer.
- **Q13 (helpers de test)** : `LogCapture` existe en trois exemplaires de test (`minos-cli`, `minos-engine/testsupport`, `minos-storage-local`) faute de test-jar partagé ; à mutualiser avec Q13 si un module de support de test apparaît.
- **Bruit du fichier hérité corrompu** : la trace (dix au plus) se répète à chaque ouverture tant que le fichier reste dans `runs/` ; une purge ou une mise en quarantaine automatique changerait le flux, elle est laissée à une décision produit.

---

# Lot 4 — Q12, Q13 : heuristiques de tests liés et duplication

> Branche : `code/q12-q13-duplication` (depuis `code/q10-q14-cycle-de-vie`, `fc213702`), worktree `minos-wt/code-q12`. Agents : `impl-code`, `verif-code`. Aucun push, aucune PR.
> Constats : **Q12** (`RelatedTestDerivationService` : suffixe `it` sans frontière de mot, tout répertoire `/test/` pris pour un répertoire de tests), **Q13** (`requireText`, `sha256`, JSON écrit à la main, mapping DTO, `ProjectView`, `LogCapture`), `AUDIT-2026-09.md` § 10.
> Règle d'or de Q13 : le nombre d'occurrences de chaque helper baisse **strictement**, l'ancienne implémentation disparaît dans le même commit, et le helper commun ne crée **jamais** une dépendance refusée par `check-module-boundaries.py` (A2 / ADR 0042, 0044) ; le script n'est pas modifié.

## 20. Q12 — heuristiques de tests liés

### 20.1 Cible relocalisée

`minos-engine/src/main/java/com/minos/query/RelatedTestDerivationService.java` (l'audit visait le module d'avant A3). Deux défauts, lus au code de `fc213702` :

| Défaut | Code d'origine | Effet |
|---|---|---|
| suffixe sans frontière de mot | `(?i)(?:tests?\|spec(?:ification)?s?\|it)$` appliqué au nom du symbole de test | un symbole de test nommé `Audit`, `Commit`, `Limit`, `Permit`, `Visit`, `Latest`, `Contest`… était rattaché à `Aud`, `Comm`, `Lim`, `Perm`, `Vis`, `La`, `Con` |
| répertoire de tests par présence du mot | `"/" + chemin` contient `/test/`, `/tests/` ou `/__tests__/` | `src/main/java/com/acme/test/Support.java` (paquet de production) était un symbole de test : jamais cible d'un test, et ancre de test à tort ; `src/it/java` n'était pas reconnu |

`latest/` et `contest/` ne déclenchaient déjà pas `/test/` (le mot est précédé d'une lettre) ; ils sont verrouillés par test.

### 20.2 Décisions

- **Suffixe** : retiré à une frontière de mot uniquement. (a) derrière un séparateur `_`, `-` ou `.` (`audit_it`, `foo-test`, `Foo.spec`), sans tenir compte de la casse ; (b) en casse de chameau : `Test`, `Tests`, `Spec`, `Specs`, `Specification(s)` (mot capitalisé, quel que soit le caractère qui précède : `IOTest` → `IO`), et `IT`/`It` derrière une **minuscule ou un chiffre** (`FooIT`, `Http2IT`, `FooIt`). La fin d'un mot en minuscules (`Audit`, `Commit`, `Latest`) n'est jamais un suffixe ; un mot en capitales (`AUDIT`) non plus.
- **`IT` et `It`** : la convention du dépôt est `*IT.java` (Failsafe : `minos-app/src/test/java/com/minos/packaging/ShadedJar*IT.java`, aucun `*It`, aucun répertoire `src/it`). `It` reste accepté : c'est un mot de casse de chameau et la règle d'origine le retirait ; le retirer aurait fait perdre `FooIt` sans rien gagner (le défaut est la minuscule finale, pas la casse `It`).
- **Répertoires de tests** (`isTestPath`) : source sets `src/test`, `src/it`, `src/integrationTest`, `src/integration-test` à toute profondeur (modules) ; `__tests__` ; `test/` ou `tests/` à la racine (Ant, pytest, Cargo) et, hors sources JVM, à toute profondeur (`packages/x/test/…`, monorepos JS) ; pour une source JVM (`.java .kt .kts .scala .groovy`), un `test/` non racine est un **paquet** (`com/acme/test/Support.java`) ; tout ce qui est sous `src/main` est de la production ; les fichiers `*.test.*` / `*.spec.*` restent des tests. Le service ne voit pas les poms du projet analysé : la « convention du projet » est celle des outils (Maven, Gradle, Ant, npm, Cargo, pytest), relevée ici dans les poms et l'arborescence du dépôt (`src/test/java` partout, Failsafe dans `minos-app`).
- **Non touché** : la dérivation à partir du nom du fichier (`stripTestFileSuffix`, séparateur obligatoire, déjà correct), les préfixes `TestFoo` / `ITFoo` (jamais gérés : ce serait un gain, pas un correctif).

### 20.3 Mesure avant / après

Harnais **non commité** (`RelatedTestsMeasure`, scratchpad `lot4/`) : il lit `src/main/java` et `src/test/java` de tous les modules `minos-*` **de l'arbre de base `fc213702`** (`git archive`, pour que l'entrée soit identique avant et après), crée un symbole par type de premier niveau (520 fichiers de production, 454 de test, 976 symboles) et appelle `RelatedTestDerivationService.derive` avec les classes de la base (copiées avant toute modification) puis celles du correctif. Deux modes : **complet** (une référence résolue est simulée pour tout identifiant d'un fichier de test égal au nom simple d'un type de production unique : 2 771 occurrences, approximation d'un index SCIP) et **nommage seul** (aucune occurrence : ne reste que l'heuristique de Q12). Résultats bruts : `lot4-related-tests-{before,after}[-naming].tsv`.

| Mesure | Avant | Après | Gagnés | Perdus | Inchangés |
|---|---|---|---|---|---|
| couples test → production, mode complet | 2 774 | 2 774 | **0** | **0** | 2 774 |
| couples test → production, nommage seul | 174 | 174 | **0** | **0** | 174 |
| classement `isTestPath` de tous les fichiers du dépôt (1 585) | 488 tests / 1 097 autres | idem | 0 | 0 | 1 585 |
| noms de types dont le radical change (972 noms distincts) | | | | | 2 (`JavaParsedUnit`, `ProviderConformanceKit` : noms de **production** qui auraient été tronqués s'ils avaient été côté test) |

**Lecture** : sur ce dépôt, l'ancienne règle ne produisait aucune association fausse (aucun type de test ne se termine en `it`/`test` en minuscules, aucun paquet de production ne s'appelle `test`) : le correctif est **neutre** ici — 0 couple gagné, 0 perdu, 2 774 inchangés — et la mesure sert de preuve d'absence de régression. Il n'y a donc aucune association perdue à justifier sur ce dépôt.

**Effet sur les cas visés** (tests rouges sur le code d'origine, `RelatedTestHeuristicsTest`, 22 échecs avant, 0 après) :

| Cas | Avant | Après |
|---|---|---|
| test `Audit`, `Commit`, `Limit`, `Permit`, `Submit`, `Visit`, `Latest`, `Contest`, `Protest`, `AUDIT` | rattaché à `Aud`, `Comm`, `Lim`, `Perm`, `Subm`, `Vis`, `La`, `Con`, `Pro`, `AUD` | aucun rattachement (10 faux positifs supprimés) |
| `AuditTest`, `CommitTest`, `LimitTest`, `AuditTests`, `CommitIT`, `LimitIT`, `AuditSpec(s)`, `AuditSpecification`, `LatestTest`, `ContestTest`, `HttpClientIT`, `Http2IT`, `IOUtilTest`, `Audit_test`, `audit-it`, `Audit.spec` | rattaché à `Audit`… | inchangé (17 cas verrouillés) |
| `AuditTest` avec `Audit` **et** `Aud` en production | `Audit` et `Aud` | `Audit` seul |
| `src/main/java/com/acme/test/Support.java` (production) | symbole de test | production : peut être la cible de `SupportTest` (gagné) et n'est plus une ancre de test |
| `src/it/java/…`, `src/integrationTest/…`, `src/integration-test/…` | non reconnus | tests (gagné, 4 chemins) |
| `latest/`, `contest/`, `attest/`, `testing/`, `test-support/`, `src/Test.java` | production | production (verrouillé) |

### 20.4 Pertes acceptées et déclarées

| Perte | Raison |
|---|---|
| `FOOTEST`, `FooTEST`, `HTTPIT`, `SQLIT` (mot collé tout en capitales) ne sont plus reliés à `FOO`/`Foo`/`HTTP`/`SQL` (V-L4-01) | indiscernable de `AUDIT` → `AUD` : la garde `AUDIT`/`AUD` impose l'arbitrage ; `fooTest`, `Foo_TEST`, `foo_TEST` restent reliés |
| une source JVM sous un `test/` ou `tests/` **non racine** (agencement Ant multi-module `svc/test/FooTest.java`) n'est plus classée test (V-L4-02) | `com/acme/test/Support.java` est un paquet de production ; sans lire l'arborescence du projet on ne peut pas distinguer les deux ; 0 occurrence dans ce dépôt (1 585 chemins, 0 différence) ; `svc/src/test/…` et `test/…` racine restent reconnus |
| noms tout en minuscules sans séparateur (`usertest`) ne sont plus tronqués | pas de frontière de mot ; jamais rencontré |

## 21. Q13 — duplication : inventaire daté (base `fc213702`, 30 septembre 2026)

Les comptes de l'audit sont périmés (`requireText` « ×15 », `sha256` « ×4 ») : le dépôt en compte plus du double. Ils sont produits par un script reproductible (`lot4/counts.py`, scratchpad ; regex sur les sources de production hors commentaires) et rejoués à chaque étape.

### 21.1 Comptes avant

| Helper | Comptes avant | Détail |
|---|---|---|
| `requireText` | **63** définitions en production (modules Maven) + 2 dans `minos-intellij` (Gradle, hors réacteur) | domain 9, engine 15, application 22, api 4, cli 2, nexus 1, provider-scip 4, runtime-local 1, storage-local 4, storage-postgresql 1 ; intellij 2 (`MinosCliClient`, `MinosM21Client`) ; 293 appels |
| `sha256` | **22** méthodes nommées `sha256` en production ; **31** `MessageDigest.getInstance("SHA-256")` avec 31 `catch (NoSuchAlgorithmException)` | 11 copies « texte UTF-8 → hexadécimal » à l'identique (6 en « is not available », 5 en « is unavailable »), 3 copies de la fabrique de `MessageDigest`, le reste inclus dans des calculs propres (empreinte de fichiers, identifiants) ; 12 sites de test |
| JSON écrit à la main | **3** renderers (`CodeSearchRenderer`, `CodeIntelligenceResultRenderer`, `SymbolResultRenderer`) : 794 lignes, 31 méthodes d'écriture (`stringField`, `numberField`, `booleanField`, `trimComma`, `appendLocation` ×3, `appendOrigin` ×3…), 25 ouvertures d'objet `{` hors encodeur | + 2 littéraux `{"status":"REVOKED"}` / `{"status":"UNBOUND"}` (`TeamCommand`) ; `LockedNpmPackage` écrit un `package.json` npm ; `McpToolSchemas` (gabarits constants, hors périmètre depuis le lot 1) |
| mapping DTO API / MCP / CLI | résultat d'import : 2 blocs de 13 clés identiques (`ImportScipCommand.render`, `IndexCommand.renderImport`) + le DTO `IndexImportDto` de l'API ; vue projet : `ProjectCommand.projectMap` (12 clés) et `MinosApplicationMcpBackend.projectStructure` (12 clés dans le même ordre) ; statut d'index : `ProjectCommand.renderIndexStatus` et `MinosApplicationMcpBackend.indexStatus` (11 clés identiques) + `LocalMinosApi.project` (3 des 4 champs de reprise) | mesuré par `lot4/dup_keys.py` (suites de ≥ 4 clés identiques entre fichiers) ; les autres coïncidences sont des faux positifs (`DoctorCommand`/`ToolsCommand` : mêmes 4 premières clés, valeurs différentes ; `providerProfiles` MCP est un sous-ensemble de `ProviderCommand.map`, diagnostics bruts contre publics) |
| `ProjectView` | **2** enregistrements identiques (12 composants) : `ProjectInspectionService.ProjectView` et `ProjectOperations.ProjectView` + la conversion `LocalProjectOperations.projectView` | l'un est consommé par `minos-mcp` (`MinosApplicationMcpBackend`) et un test de `minos-bootstrap`, l'autre par `minos-api`, `minos-cli` et 12 de ses tests |
| `LogCapture` | **3** copies de test (`minos-cli`, `minos-engine/…/testsupport`, `minos-storage-local`) | identiques au modificateur d'accès près (`public` dans l'engine) |

### 21.2 Où placer chaque helper : graphe des dépendances et règles

Dépendances directes autorisées (`ALLOWED_DEPENDENCIES`) : `domain` ∅ ; `engine` → domain ; `runtime-local`, `storage-local`, `integration-git` → engine ; `provider-scip` → domain, engine, runtime-local, storage-local ; `application` → domain, engine ; `nexus` → domain, application, bootstrap ; `cli` → domain, engine, application, nexus, bootstrap ; `api` → domain, engine, application, bootstrap ; `mcp` → application, bootstrap. Un adaptateur ne dépend jamais de `application`, `bootstrap`, `app` ni d'une surface. Le script ne contrôle que les dépendances **directes** des poms et les noms d'adaptateurs dans les sources ; les modules qui atteignent `domain` ou `engine` par transitivité le font déjà (`minos-mcp` importe `com.minos.domain`, `minos-nexus` importe `com.minos.store`).

| Helper | Lieu retenu | Pourquoi |
|---|---|---|
| `requireText` | `minos-domain`, `com.minos.domain.Preconditions` | `minos-domain` porte 9 copies et doit rester sans dépendance interne : c'est le seul module vu par **tous** les appelants |
| `sha256` (texte, octets, fabrique de `MessageDigest`) | `minos-engine`, `com.minos.io.Sha256` | tous les appelants sont dans un module qui voit l'engine (`nexus` par `application`, comme pour `com.minos.store`) ; aucun appelant dans `domain`, qui reste minimal |
| JSON des 3 renderers, littéraux `TeamCommand` | `DeterministicJson` (`minos-application`, même paquet `com.minos.output`) | les trois renderers sont déjà dans ce paquet ; `TeamCommand` (`cli`) dépend de `application` |
| `LockedNpmPackage` (`package.json`) | **deux implémentations conservées** | `minos-provider-scip` est un adaptateur : il ne peut pas dépendre de `minos-application`, où vit `DeterministicJson` (règle A2, ADR 0042) ; déplacer l'encodeur vers `domain`/`engine` élargirait le lot à une API publique ; les valeurs sont des constantes du catalogue |
| `LogCapture` | test-jar de `minos-engine` (`com.minos.testsupport`), consommé en portée `test` par `storage-local` et `cli` | ces deux modules dépendent déjà de l'engine ; la dépendance en portée `test` sur le même artefact ne crée aucune arête nouvelle (le script garde la portée la plus forte d'un module déclaré deux fois) |
| `ProjectView` | **deux enregistrements conservés** | voir § 21.3 |

### 21.3 `ProjectView` et mapping projet : règle de l'API publique

Les deux enregistrements sont des types **publics** de types publics, et chacun est référencé par un autre module : `ProjectInspectionService.ProjectView` par `minos-mcp` (`MinosApplicationMcpBackend`, l. 87 et 107) et un test de `minos-bootstrap`, `ProjectOperations.ProjectView` par `minos-api` (`LocalMinosApi.project`), `minos-cli` (`ProjectCommand`, `GitActivityCommand`) et 12 de ses tests. En supprimer un change le type de retour de méthodes publiques observées par un autre module : la règle du lot (« dédupliqué seulement si l'API publique n'en est pas changée ») s'y oppose. **Les deux enregistrements sont conservés**, ainsi que la conversion `LocalProjectOperations.projectView`. Conséquence : la vue projet ne peut pas être partagée telle quelle entre la CLI (`ProjectOperations.ProjectView`) et le MCP (`ProjectInspectionService.ProjectView`) ; le mapping projet est traité au § 21.5 sans toucher à ces types.

### 21.4 Décisions Q13

**`requireText` → `com.minos.domain.Preconditions.requireText(value, name)`.** `null` ou blanc → `IllegalArgumentException("<name> must not be blank")`, la valeur est rendue inchangée. 57 des 63 copies étaient identiques (variantes `void`/`String`, une ligne ou accolades). Six divergeaient ; leur comportement a été **caractérisé avant** la migration (`20e7a0d7`, vert sur le code d'origine et après) et **conservé** :

| Copie | Divergence | Traitement |
|---|---|---|
| `SnapshotDescriptor` | `null` → `NullPointerException` (message = champ), blanc → IAE | `requireText(Objects.requireNonNull(x, "x"), "x")` |
| `ProjectIndexState` | message fixe « text value must not be blank » (sans champ) ; utilisée par référence de méthode | lambda `value -> requireText(value, "text value")` |
| `SharedCacheLeaseRegistry` | message fixe « lease key must not be blank », y compris pour la description du registre | nom passé « lease key » (constante) — message historique conservé |
| `IndexingRunExecutor` | `IllegalStateException` (état interne invalide d'un port), et le run persisté porte `IllegalStateException: …` | contrôle **en ligne** (un `if` propre à ce site), plus de méthode `requireText` |
| `OllamaEmbeddingProvider` | rend le texte **rogné** | `requireText(model, "model").trim()` |
| `PostgresCodeKnowledgeSnapshotStore` | message fixe « snapshotId must not be blank » | `requireText(snapshotId, "snapshotId")` ; test de caractérisation ajouté (vert sur le code d'origine : fichier de production remis à `fc213702` le temps de la mesure) |

Aucun changement de message ni de type d'exception. Restent deux copies hors réacteur, **conservées** : `minos-intellij` (`MinosCliClient`, `MinosM21Client`) est un plugin Gradle (`build.gradle.kts` : Gson seul) sans dépendance vers les modules MINOS.

**SHA-256 → `com.minos.io.Sha256`** (`newDigest()`, `hex(String)` en UTF-8, `hex(byte[])`, `hex(MessageDigest)`). Les 31 `MessageDigest.getInstance("SHA-256")` de production tombent à 1. Les calculs incrémentaux (fichiers bornés, empreintes de projet, empreinte de fichiers du graphe de programme) **gardent leur boucle de lecture et leurs bornes** : seule la fabrique et l'hexadécimal sont mutualisés. Quatre méthodes `sha256(Path…)` restent nommées ainsi et hachent un fichier avec leurs bornes propres (`ManagedScipProviderRuntimeManager`, `FileProgramGraphProvider`, `DistributedArtifactBundleStore`, `FileRuntimeObservationStore`) : des scripts de garde assertent deux de ces signatures littérales (`check-mne.py` : `private static String sha256(Path file)` ; `check-advanced-provider-consistency.py` : `sha256(metadata, nodes, edges)`), et `check-runtime-dynamic-consistency.py` exige le mot `sha256` dans `RuntimeObservationEnvelopeCodec` — le premier essai, qui le supprimait, faisait rougir `check-post-mne` ; corrigé avant le commit (variable locale `sha256`), aucun script assoupli.
**Changement observable déclaré** : le message de l'`IllegalStateException` « SHA-256 is not available » (6 copies) devient « SHA-256 is unavailable » (5 copies) ; le cas est impossible sur toute JVM conforme (SHA-256 est obligatoire).
Les tests gardent leur propre `MessageDigest` (7 sites) quand ils recalculent l'empreinte d'un fichier ou d'un artefact produit : c'est un oracle indépendant du code de production. Les cinq copies d'un fournisseur de clés de test (qui fabriquaient une entrée, sans oracle) ont été mutualisées (`DerivedTenantKeys`, V-L4-04).

**JSON à la main → `DeterministicJson`.** Les trois renderers construisent des objets ordonnés (`DeterministicJson.object`) ; l'emplacement, l'origine, l'entité et les rôles, copiés dans chacun (`appendLocation` ×3, `appendOrigin` ×3, `appendEntity` ×2), vivent dans `JsonShapes` (paquet-privé). Ordre des clés, valeurs, échappement : **identiques octet pour octet** (8 références produites par l'ancien code avant migration, `RendererJsonCharacterizationTest`). Deux littéraux de `TeamCommand` passent aussi par l'encodeur ; `CliJson.quote(StringBuilder, String)`, sans appelant, est supprimé.

**Mapping DTO.** Voir § 21.3 pour `ProjectView`. Résorbés : fiche projet et statut d'index (CLI + MCP → `com.minos.output.ProjectJson`, qui s'écrit contre la nouvelle interface `ProjectSummary` implémentée par les deux enregistrements : ajout pur), résultat d'import (2 blocs de la CLI → `CliCommandSupport.importResultMap`). **Non traités** : `ProjectDto`/`IndexImportDto` de `minos-api` (des records construits par constructeur, pas une copie de clés ; les fusionner changerait la surface publique), le sous-ensemble `providerProfiles` du MCP contre `ProviderCommand.map` (clés partielles, diagnostics bruts contre publics), la conversion `LocalProjectOperations.projectView` (12 champs, 3e copie de la forme de `ProjectView`, conservée avec les deux enregistrements).

**`LogCapture`** : test-jar de `minos-engine` consommé en portée `test` par `minos-storage-local` et `minos-cli` (le script de frontières garde la portée la plus forte d'un module déclaré deux fois : aucune arête nouvelle, `check-module-boundaries.py` inchangé).

## 22. Journal par commit (lot 4)

Gates rejoués après chaque commit : `check-module-boundaries.py`, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, `scripts/remediation/check-post-mne.py`. Boundaries `modules=14, packages=45` ; `sources` 499 → 500 (`Preconditions`) → 501 (`Sha256`) → 502 (`JsonShapes`) → 504 (`ProjectSummary`, `ProjectJson`) ; les quatre autres gates inchangés (`SUCCESS`, `SUCCESS`, `scripts checked=95`, `SUCCESS`).

| Commit | Contenu | Rouge / preuve (jointe au message) |
|---|---|---|
| `b9d57a3b` | Q12 : suffixes à frontière de mot, répertoires de tests par convention | `RelatedTestHeuristicsTest` : 22 échecs sur 72 avant, 0 après |
| `e37e7f54` | ce suivi : mesure de Q12, inventaire et décisions de Q13 | — |
| `20e7a0d7` | caractérisation des 6 copies de `requireText` qui divergent | verts sur le code d'origine |
| `3fe66392` | `Preconditions.requireText`, 9 copies du domaine | `PreconditionsTest` : rouge de compilation avant la classe |
| `c218398d` | 15 copies de l'engine (dont 4 divergentes) | caractérisation verte avant/après |
| `1b451e57` | 10 copies des adaptateurs ; V-L4-03 (test PostgreSQL) | test vert sur le code d'origine |
| `9b169dec` | 22 copies de l'application (dont Ollama : `.trim()`) | idem |
| `1471d0c0` | 7 copies de `api`, `cli`, `nexus` | `requireText` : 8 → 1 |
| `dff51d7d` | `Sha256` : 31 sites | `Sha256Test` : rouge de compilation ; suite complète 1 555 tests |
| `e1437272` | caractérisation octet à octet des trois renderers | verte sur le code d'origine |
| `41920418` | renderers sur `DeterministicJson`, `JsonShapes` | 8 références inchangées ; 12 golden inchangés |
| `01c72f67` | `ProjectJson`, `ProjectSummary`, `importResultMap` | `ProjectJsonTest` ; 12 golden inchangés |
| `ffe9c6cd` | `LogCapture` dans le test-jar de l'engine | boundaries inchangé |
| `d8662fec` | fournisseur de clés de test unique ; V-L4-04, V-L4-06 | — |
| `642eaec5` | garde `DuplicationGuardTest` | 5 mutations tuées |
| `bc5cceb8` | ce suivi : décisions Q13, journal, comptes, constats | — |
| `05228456` | V-L4-09 : 9 copies de `requireText` sous un autre nom (`validateText`, `requireFileId`, `requireLabel`, `requireProvider`, `requireBoundary`, + blocs de tête de 4 validations plus larges) ; garde structurelle ; V-L4-10 (Javadoc) | mutation `requireNonBlank` tuée |

## 23. Preuves

### 23.1 Comptes de duplication avant / après (`lot4/counts.py`, regex hors commentaires)

| Helper | Avant (`fc213702`) | Après | Où reste ce qui reste |
|---|---|---|---|
| `requireText`, définitions en production (modules Maven) | **63** | **1** (`Preconditions`) | — |
| `requireText`, `minos-intellij` (Gradle) | 2 | 2 | plugin sans dépendance vers les modules MINOS (conservé, déclaré) |
| `MessageDigest.getInstance("SHA-256")`, production | **31** | **1** (`Sha256`) | — |
| `catch (NoSuchAlgorithmException)`, production | 31 | 1 | — |
| méthodes nommées `sha256`, production | 22 | 4 | hachent un **fichier** avec leurs bornes propres |
| `MessageDigest.getInstance("SHA-256")`, tests | 12 | 7 | oracles indépendants (recalcul d'une empreinte produite) |
| méthodes d'écriture JSON manuelle (3 renderers, `stringField`, `numberField`, `trimComma`, `appendLocation`…) | **29** (+ 2 `appendXxxText` de TEXT) | **0** (les 2 de TEXT restent) | — |
| lignes des 3 renderers | 794 | 494 (+ `JsonShapes` 58) | — |
| sites d'ouverture d'objet JSON à la main (hors encodeur et `McpToolSchemas`) | 25 | 0 | `LockedNpmPackage` (`package.json`) et `McpToolSchemas`, exceptions nommées de la garde |
| `LogCapture` (classes de test) | **3** | **1** | — |
| projection projet CLI/MCP (blocs `put("providerVersion", …)`) | 4 (2 fiches, 2 statuts) | 0 : 1 projection (`ProjectJson`) | — |
| résultat d'import (`put("normalizedSymbolCount"`) | 2 | 1 | — |
| `ProjectView` | 2 | **2** | API publique (§ 21.3) |
| fournisseur de clés de test (hosted) | 5 | 1 | — |

Production (`*/src/main/*`) : 103 fichiers touchés, +786 / −1 368 lignes.

### 23.2 Changements observables (à reprendre en tête de la PR)

1. **Tests liés (Q12)** : sur ce dépôt **0 couple gagné, 0 perdu, 2 774 inchangés** (§ 20.3). Ailleurs : 10 faux positifs de suffixe supprimés (`Audit`→`Aud`…), les classes d'un paquet de production `test` redeviennent des cibles, `src/it` et `src/integrationTest` reconnus ; pertes acceptées (§ 20.4).
2. **Message d'erreur** : « SHA-256 is not available » → « SHA-256 is unavailable » (6 copies) — cas impossible sur toute JVM conforme.
3. **Aucun autre changement de message, de type d'exception, de code de sortie ni de sortie JSON** : 12 golden inchangés, 8 sorties de renderers caractérisées identiques à l'octet, différentiel vivant de `verif-code` (CLI et MCP) identique.
4. **API publique** : ajouts purs (`Preconditions`, `Sha256`, `ProjectSummary`, `ProjectJson`, `implements ProjectSummary` sur les deux `ProjectView`) ; rien de retiré ni de renommé.

### 23.3 Preuves par mutation (garde)

Cinq mutations posées ensemble puis annulées (`lot4-guard-mutations.log`) : `requireText` recréé dans `CliJson`, `MessageDigest.getInstance("SHA-256")` dans `MinosLauncher`, `new StringBuilder("{")` dans `SymbolResultRenderer`, second `put("normalizedSymbolCount"` dans `ProjectCommand`, `LogCapture` recréé dans `minos-cli` : **5 tests rouges sur 8**, fichier fautif dans le message. `verif-code` en a rejoué six (dont un troisième `record ProjectView`) : 6 rouges. Une copie **renommée** (`requireNonBlank` dans `MinosMcpTools`) n'était pas vue par la garde d'origine (V-L4-09) ; la garde structurelle `noMethodIsAJustRenamedRequireText` la tue (`lot4-guard-renamed-mutation.log`).

### 23.4 Fin de lot

`./mvnw clean verify` complet dans le worktree (journal dans le scratchpad, pas dans `target/`) sur `a65d99ff` : **BUILD SUCCESS**, 15 modules, 10 min 58, **1 671 tests exécutés, 0 échec, 0 erreur, 46 ignorés** (hypothèses `Assumptions` préexistantes, aucun `@Disabled` ajouté ; 1 562 tests à la fin du lot 3, +109 : `RelatedTestHeuristicsTest` 67, caractérisations des copies divergentes 12, `PreconditionsTest` 4, `Sha256Test` 5, `RendererJsonCharacterizationTest` 6, `ProjectJsonTest` 5, `DuplicationGuardTest` 9…). Un premier `clean verify` sur `bc5cceb8` (avant V-L4-09) avait donné 1 670 tests, 0 échec. `python scripts/quality/check-jacoco.py` : 26 portées vertes, **seule rouge : `m24-polyglot-provider-platform`** (`ManagedPolyglotScipRuntimeManager` line 0,228 < 0,28), préexistante et propre à Windows. Gates : boundaries `modules=14, sources=504, packages=45` (+5 sources de production : `Preconditions`, `Sha256`, `JsonShapes`, `ProjectSummary`, `ProjectJson`), current-docs, product-facts, milestone-artifact-references (95), `check-post-mne.py` : verts. Golden : les 12 de `characterization/` **inchangés** (`git diff fc213702..HEAD -- minos-app/src/test/resources scripts` vide) ; aucun script de `scripts/` assoupli.

## 24. Constats de verif-code (lot 4)

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| V-L4-01 | remarque | mot collé tout en capitales (`FooTEST`, `HTTPIT`) n'est plus relié | déclaré § 20.4 |
| V-L4-02 | remarque | source JVM sous un `test/` non racine n'est plus classée test | déclaré § 20.4 |
| V-L4-03 | remarque | copie PostgreSQL de `requireText` non caractérisée ; titre du commit `20e7a0d7` : « 58 autres » alors que 57 copies sont identiques | test ajouté (`1b451e57`) ; le titre n'a pas été réécrit : les comptes exacts sont § 21.4 |
| V-L4-04 | remarque | 5 copies d'un fournisseur de clés de test présentées comme « oracle indépendant » à tort | `d8662fec` : `DerivedTenantKeys` ; les 7 `getInstance` de test restants sont de vrais oracles |
| V-L4-05 | remarque | `minos-nexus` importe `com.minos.io.Sha256` par transitivité (`application` → `engine`) | déclaré § 21.2 : `nexus` importait déjà `com.minos.store` et `com.minos.registry` de l'engine ; le script ne contrôle que les dépendances directes |
| V-L4-06 | remarque | `FileRuntimeObservationStore.digest(byte[])` non migré | `d8662fec` |
| V-L4-07 | remarque | les branches TEXT des 3 renderers gardent 3 copies de `addTextLocation`/`addTextOrigin` | déclaré § 25 |
| V-L4-08 | remarque | `LocalProjectOperations.projectView` reste la 3e copie de la forme de `ProjectView` | déclaré § 21.4 / § 25 |
| V-L4-09 | à corriger | des copies de `requireText` sous un autre nom (`InMemoryCodeKnowledgeStore.validateText` ×12 appels, `requireFileId`, `requireLabel`, `requireProvider`, `requireBoundary`) échappaient à la garde, qui cherchait le nom ; une mutation `requireNonBlank` ne la faisait pas rougir | `05228456` : les cinq supprimées, le bloc de tête de `StorageBackendConfiguration`, `CommandLocator`, `SnapshotProjectLease` et `ProjectRegistryLimits.requireName` (API publique, signature inchangée) devient `requireText` ; garde structurelle (corps entier = contrôle de texte blanc, quel que soit le nom) ; mutation rejouée rouge |
| V-L4-10 | à corriger | une ligne de Javadoc en français dans `SharedCacheLeaseRegistry` (fichier anglais) | `05228456` |
| V-L4-11 | remarque | `Sha256` documenté en français dans un paquet (`com.minos.io`) dont d'autres fichiers sont en anglais | conforme à la règle (la langue est celle du fichier, sans mélange) |

## 25. À traiter plus tard (lot 4)

- **Autres helpers « bloc + throw »** de nom différent (`requireToken`, `blankToNull`, validations à message fixe ou à contrôle supplémentaire… : ~50 selon l'inventaire large de `verif-code`, après les 9 traités par V-L4-09) : hors du chiffre de l'audit (`requireText`), non tous équivalents ; la garde ne refuse que les copies dont le corps entier est le contrôle de texte blanc ; le reste est à reprendre helper par helper avec caractérisation. `minos-intellij` : `requireText` ×2 et `requireToken` (plugin Gradle).
- **Mise en forme TEXT** des 3 renderers (`addTextLocation`, `addTextOrigin`, `field`) : 3 copies restantes (V-L4-07).
- **`ProjectView` ×2 et `LocalProjectOperations.projectView`** : à fusionner quand une évolution d'API publique le permettra (changer le type de retour de `ProjectInspectionService` ou de `ProjectOperations` est observable par `minos-mcp` / `minos-api` / `minos-cli`).
- **`ProjectDto`/`IndexImportDto`** (API) et `providerProfiles` (MCP) : mapping par constructeur ou sous-ensemble, non fusionnés (§ 21.4).
- **`LockedNpmPackage`** : `package.json` écrit à la main ; le déplacer sous `DeterministicJson` demanderait de remonter l'encodeur dans `minos-domain` ou `minos-engine`, c'est-à-dire de changer une API publique du lot 1.
- **Empreintes de fichiers** : 4 méthodes `sha256(Path…)` avec des bornes différentes (lecture bornée, `NOFOLLOW_LINKS`, multi-fichiers) ; une abstraction commune `BoundedSha256` serait un chantier de sécurité, pas de duplication.
- **Q12** : préfixes `TestFoo` / `ITFoo` (jamais gérés), suffixes `ITCase`/`TestCase` (Failsafe) ; classement des répertoires de tests **sans** lire le projet analysé (V-L4-02) ; une lecture des poms/`settings.gradle` permettrait de retrouver `svc/test/`.
- **`minos-intellij`** : deux copies de `requireText` (plugin Gradle autonome).
- **Historique** : `scripts/history/m21/check-m21-parity.py` (déjà signalé au lot 2) toujours cassé, non rejoué par `check-post-mne`.
