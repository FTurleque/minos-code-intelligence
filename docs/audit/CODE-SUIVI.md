# Suivi — chantier Code et dette technique (lot 1 : Q6, Q7 — sorties JSON)

> Branche : `code/q6-q7-sorties` (depuis `develop`, base `02e490c3`), worktree `minos-wt/code-q6-q7`.
> Constats : **Q6** (ordre des clés JSON tiré au hasard à chaque JVM) et **Q7** (`NaN`/`Infinity` écrits tels quels, échappement JSON dispersé et incomplet), `AUDIT-2026-09.md` § 10.
> Agents : `impl-code` (implémentation), `verif-code` (inspection de chaque commit). Aucun push, aucune PR ouverte par les agents.
> Règles du lot : aucun changement de comportement non voulu ; seul l'**ordre** des clés change (jamais l'ensemble des clés ni les valeurs) ; un golden qui bouge est justifié avant d'être régénéré ; test rouge avant correctif.
> Ce fichier est repris tel quel par les lots suivants (Q11/Q19/Q20, Q10/Q14, Q12/Q13) : les sections « à traiter plus tard » et « constats de verif-code » s'y accumulent.

## 1. Tableau de bord

| Lot | Contenu | Statut | Commits |
|---|---|---|---|
| 1 — Q6, Q7 | Inventaire, encodeur unique (`NaN`/`Infinity` refusés, échappement complet), un seul point d'échappement, ordre des clés stable, garde-fous, preuve inter-JVM | en cours | voir § 4 |
| 2 — Q11, Q19, Q20 | Un seul parseur d'arguments | à faire | — |
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

| Commit | Contenu | Gates (boundaries / current-docs / product-facts / milestone-refs) |
|---|---|---|
| — | (rempli au fil des commits) | |

## 5. Preuves

(rempli au fil des commits : rouge → vert, comparaison des golden, mesures inter-JVM)

## 6. Constats de verif-code

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| — | — | aucun à ce stade | — |

## 7. À traiter plus tard

- **Q13** : `CodeSearchRenderer`, `CodeIntelligenceResultRenderer`, `SymbolResultRenderer` écrivent encore leur JSON à la main (structure, virgules) ; seul leur échappement est unifié par le lot 1.
- `ProviderConformanceKit.ConformanceResult.counts` : `Map.copyOf` d'un `EnumMap`, jamais rendu ; traité avec les autres `capabilities` pour ne pas laisser d'exception dans la garde.
