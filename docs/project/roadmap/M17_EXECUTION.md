# M17 — Provider & Discovery Platform — exécution

Statut de branche : **9/9 implémentés ; qualification exact-head requise avant livraison**.

Issue : #65.

## Question produit

> MINOS peut-il ajouter de nouveaux langages, systèmes de build et providers sans ajouter de branches spécifiques dans son cœur ?

## Invariants

- `ProjectDiscoveryService` orchestre des SPI et ne connaît aucun marqueur/langage/build spécifique ;
- `IndexerProvider` ne modifie pas le domaine ;
- chaque `IndexerCapability` reçoit explicitement `FULL`, `PARTIAL`, `EXPERIMENTAL` ou `UNSUPPORTED` ;
- aucune capacité upstream n'est automatiquement promue en capacité MINOS ;
- un runtime provider s'ajoute derrière `ProviderRuntimeManager` ;
- `MinosApi` v1 et les 16 tools MCP historiques restent stables ;
- Java et TypeScript M14 sont rejoués avant toute qualification M17 ;
- toute qualification est liée à un SHA exact et à un worktree propre.

## Sous-incréments

### M17-S1 — Discovery SPI ✅ implémenté

Contrats : `ProjectDetector`, `BuildSystemDetector`, `SourceRootDetector`, `LanguageDetector`.

Gate : un plugin injecté avec un marqueur inconnu doit être découvert sans modification de `ProjectDiscoveryService`.

### M17-S2 — Provider SPI ✅ implémenté

`IndexerProvider` + `IndexerProviderRegistry`. Le moteur historique `IndexerRegistry` reste le moteur de négociation dérivé des descriptors.

### M17-S3 — Capability model v2 ✅ implémenté

Profil exhaustif par capability. Toute entrée manquante est une erreur de construction.

### M17-S4 — Gradle ✅ implémenté

Fixtures Java mono-module et Kotlin multi-module. Discovery `GRADLE` + source roots qualifiée. Aucun runtime Gradle n'est déclaré supporté tant qu'il n'est pas prouvé.

### M17-S5 — JS workspaces ✅ implémenté

- npm workspaces : fixture historique `typescript-modules` ;
- pnpm : fixture `typescript-pnpm-workspace`, build system hérité aux packages ;
- yarn : même SPI, test workspace dédié.

`scip-typescript` reste provider-neutral vis-à-vis du package manager lorsque le projet TypeScript est indexable.

### M17-S6 — Kotlin ✅ implémenté

- discovery Kotlin ;
- négociation `KOTLIN + MAVEN -> scip-java` ;
- fixture `kotlin-maven-simple` ;
- qualification finale exige indexation réelle + `find-symbol` + `find-usages`.

Gradle Kotlin est découvert mais reste explicitement non couvert par le runtime MINOS scip-java Windows actuel.

### M17-S7 — Python ✅ implémenté

Provider `scip-python` géré, version épinglée `0.6.6`, installation npm confinée sous `MINOS_HOME/tools`, Python 3.10+ requis. Fixture `python-simple`.

Qualification finale exige installation READY, indexation réelle, snapshot actif, `find-symbol` et `find-usages`.

### M17-S8 — Provider conformance kit ✅ implémenté

`ProviderConformanceKit` produit un profil déterministe : capabilities exhaustives, compte par niveau, score, limitations, langages/build systems et version.

### M17-S9 — Installation provider extensible ✅ implémenté

`CompositeProviderRuntimeManager` compose des runtimes indépendants sans `switch` dans CLI/doctor/index. `scip-python` est le troisième runtime géré.

## Exposition des limitations

- CLI : `minos providers [provider-id] [--format text|json]` ;
- Java : `ProviderPlatformApi` v1 séparée de `MinosApi` v1 ;
- MCP : `minos_project_structure` et `minos_index_status` incluent `providerProfiles` sans changer le catalogue de 16 tools.

## Qualification finale

Commande de référence :

```text
scripts/m17/run-final.ps1
```

Le runner doit prouver :

1. branche/head/worktree exacts ;
2. structure SPI et absence de branches d'écosystème dans l'orchestrateur central ;
3. product facts cohérents ;
4. Java 24, Maven Wrapper, reactor complet ;
5. `clean verify`, JaCoCo et tests sans échec ;
6. replay M14 Java/TypeScript/STALE/Windows/install/doctor/MCP ;
7. profils provider exhaustifs et limitations visibles ;
8. installation `scip-python` READY ;
9. Kotlin/Maven end-to-end ;
10. Python end-to-end ;
11. aucune salissure des fixtures ;
12. HEAD inchangé.

Verdict unique de fermeture :

```text
M17 FINAL PROVIDER PLATFORM VALIDATION SUCCESS
```

Un échec Kotlin/Python ne doit pas être contourné en abaissant un profil de capacité : il faut corriger le runtime/fixture ou documenter et replanifier explicitement la capacité avant de pouvoir fermer M17.
