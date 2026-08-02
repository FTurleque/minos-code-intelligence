# État courant — MINOS

Dernière mise à jour : **2 août 2026 — MINOS 1.0.0 publié ; correctif Windows 1.0.1 en préparation et non publié ; M29 Docker autonome/paritaire planifié.**

Ce fichier est la synthèse autoritative de l'état courant. Les preuves historiques restent dans [`roadmap/`](roadmap/), [`history/milestones/`](history/milestones/) et [`adr/`](adr/README.md).

## Synthèse

```text
C0 → M28                         TERMINÉS / INTÉGRÉS sur main
M21 #73                          CLOSED / completed
M28 #93                          CLOSED / completed
PR de promotion #102             MERGED
main v1.0.0                      1adbc45339efe37cd26d1937025bfa69d7b57811
tag v1.0.0                       1adbc45339efe37cd26d1937025bfa69d7b57811
GitHub Release v1.0.0            PUBLIÉE
#98 sandbox OS réelle            OPEN — travail futur explicite
v1.0.1 Windows                   EN PRÉPARATION — NON PUBLIÉE
M29 #107                         PLANIFIÉ — Docker autonome & Native Parity
```

`main` et `develop` représentent la ligne produit 1.0.0 publiée. La branche de maintenance `fix/v1.0.1-release-hardening` porte le candidat de correction Windows 1.0.1 ; elle n'est pas une release et aucun tag `v1.0.1` ne doit être créé avant validation utilisateur du setup final.

M29 est planifié sous **#107** avec démarrage prévu le **3 août 2026**. Il ne doit pas être présenté comme livré avant qualification de parité native/Docker exact-head.

## Release 1.0.0

MINOS 1.0.0 est la première release stable publiée après la convergence C0→M28 et la promotion `develop → main` via la PR #102.

La release reste immuable. Elle conserve ses artefacts et son tag historiques.

Un défaut post-publication a été identifié dans la distribution Windows native : l'image Java créée par `jpackage` utilisait une liste de modules trop étroite (`jdk.compiler` uniquement comme racine explicitement demandée). Le serveur MCP peut alors échouer au bootstrap avec :

```text
java.lang.NoClassDefFoundError: org/w3c/dom/Node
```

La classe concernée appartient à `java.xml`. L'analyse du JAR montre que la correction correcte consiste à dériver le runtime complet avec `jdeps`, pas à ajouter uniquement `java.xml` en dur.

Conséquence : **la release 1.0.0 reste historiquement publiée, mais le setup Windows natif ne doit plus être considéré comme le candidat recommandé pour les intégrations MCP natives.** Le correctif est porté par 1.0.1.

Voir [`releases/1.0.0.md`](releases/1.0.0.md) et [`releases/1.0.1.md`](releases/1.0.1.md).

## Candidat 1.0.1

Le correctif 1.0.1 porte les changements suivants :

- version de développement alignée sur `1.0.1-SNAPSHOT` ;
- runtime Windows calculé depuis le JAR final via `jdeps --print-module-deps` ;
- vérification du runtime généré via `java --list-modules` ;
- non-régression explicite `java.xml` ;
- handshake MCP réel `initialize` + `tools/list` sur la distribution Windows ;
- même handshake sur une installation setup isolée ;
- setup de smoke à AppId distinct pour ne pas toucher PATH, Docker ou états MCP d'une installation réelle ;
- page de configuration MCP avec préflight des clients ;
- faux launcher Copilot/VS Code rejeté s'il ne fournit pas l'interface MCP attendue ;
- Claude Code et Codex CLI validés par capability probe ;
- Claude Desktop détecté comme application de bureau ;
- Codex Desktop géré via configuration utilisateur TOML lorsque le CLI MCP n'est pas le mode choisi ;
- sauvegarde et ownership explicites des configurations tierces ;
- désinstallation utilisant les chemins CLI enregistrés à l'installation ;
- provider `slf4j-nop` pour éviter les warnings SLF4J inutiles sur stderr MCP ;
- runner local `scripts/release/build-local-windows-candidate.ps1` qui construit le candidat sans publier ni créer de tag.

Le candidat 1.0.1 ne doit pas prétendre que le runtime Docker actuel est déjà équivalent au natif. Avant M29, le natif reste le parcours MCP client complet/recommandé ; Docker reste un runtime isolé avancé dont l'autonomie et la parité complète sont explicitement planifiées.

### Gate avant publication

La séquence 1.0.1 est volontairement :

```text
code + docs alignés
→ build local Windows
→ runtime module gate
→ MCP handshake sur distribution
→ génération setup.exe production
→ vérification visuelle/utilisateur du setup
→ vérification réelle Copilot/clients sélectionnés
→ autorisation explicite de publication
→ seulement ensuite tag v1.0.1 + GitHub Release
```

Aucun succès de `minos --version` ne suffit plus à qualifier le MCP packagé.

## État des jalons

| Jalons | État |
|---|---|
| C0 → M20 | terminés, validés et livrés |
| M21 — Production Integrity | terminé ; #73 closed/completed |
| M22 — Advanced Provider Intelligence | terminé |
| M23 — Semantic Retrieval 2.0 | terminé |
| M24 — Polyglot Expansion | terminé |
| M25 — Remote & Distributed Indexing | terminé avec disposition sandbox honnête |
| M26 — Runtime & Dynamic Intelligence | terminé |
| M27 — Team / Hosted Mode | terminé avec frontières local-first/no-SaaS explicites |
| M28 — Production Convergence | terminé ; #93 closed/completed ; PR #102 merged |
| M29 — Autonomous Docker Runtime & Native Parity | **planifié ; #107 OPEN ; démarrage prévu le 3 août 2026** |

## M29 — Docker autonome & Native Parity

M29 doit transformer Docker en backend autonome de premier rang.

Le runtime Docker doit pouvoir, sans état natif préalable :

```text
project add
→ provider/runtime qualification
→ index
→ READY
→ snapshots structurés
→ vector store
→ structured / semantic / hybrid queries
→ MCP
→ Copilot / Claude / Codex
```

MINOS possède déjà un vector store sémantique persistant v2 (`index-v2.bin`, float32). M29 réutilise ce store : aucune nouvelle base vectorielle externe ni ANN ne sont introduits sans mesure dédiée.

Le travail M29 couvre :

- contrat backend `native | docker` ;
- identités projet/workspace indépendantes du runtime ;
- mapping de chemins Windows ↔ conteneur ;
- persistance portable registre/snapshots/index state/vector store ;
- plan d'administration Docker autonome ;
- image Docker provider-complete et offline en RUN ;
- lifecycle d'indexation autonome ;
- intégrations MCP backend-agnostic ;
- installer avec choix backend exclusif une fois la parité acquise ;
- qualification comparative native/Docker machine-readable.

Roadmap opérationnelle : [`roadmap/M29_EXECUTION.md`](roadmap/M29_EXECUTION.md).  
Issue : **#107**.

## Limite explicitement ouverte — #98

L'issue #98 reste ouverte : **la sandbox OS réelle Windows/Linux du worker distant n'est pas encore implémentée et qualifiée**.

La disposition produit reste :

```text
network DENY sans backend OS prouvé  → fail-closed
code non fiable                      → non supporté
claim « sandbox OS réelle »          → interdit
```

M29 est indépendant de #98 : rendre Docker MCP autonome et paritaire ne constitue pas, à lui seul, une sandbox OS réelle pour les workers distants.

## Sources de vérité opérationnelles

- état produit : ce fichier ;
- roadmap : [`ROADMAP.md`](ROADMAP.md) ;
- convergence M28 : [`roadmap/M28_EXECUTION.md`](roadmap/M28_EXECUTION.md) ;
- prochain jalon M29 : [`roadmap/M29_EXECUTION.md`](roadmap/M29_EXECUTION.md) / #107 ;
- installation Windows : [`user/production-installation.md`](user/production-installation.md) ;
- release 1.0.0 : [`releases/1.0.0.md`](releases/1.0.0.md) ;
- candidat 1.0.1 : [`releases/1.0.1.md`](releases/1.0.1.md) ;
- publication autoritative : `scripts/release/publish-windows-release.ps1` ;
- construction locale sûre : `scripts/release/build-local-windows-candidate.ps1`.
