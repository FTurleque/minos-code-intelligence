# État courant — MINOS

Dernière mise à jour : **1er août 2026 — MINOS 1.0.0 publié ; correctif Windows 1.0.1 en préparation et non publié.**

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
```

`main` et `develop` représentent la ligne produit 1.0.0 publiée. La branche de maintenance `fix/v1.0.1-release-hardening` porte le candidat de correction Windows 1.0.1 ; elle n'est pas une release et aucun tag `v1.0.1` ne doit être créé avant validation utilisateur du setup final.

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
- page dédiée **Intégrations MCP natives** avec préflight des clients ;
- faux launcher Copilot/VS Code rejeté s'il ne fournit pas l'interface MCP attendue ;
- Claude Code et Codex CLI validés par capability probe ;
- Claude Desktop détecté comme application de bureau ;
- Codex Desktop géré via configuration utilisateur TOML lorsque le CLI MCP n'est pas le mode choisi ;
- sauvegarde et ownership explicites des configurations tierces ;
- désinstallation utilisant les chemins CLI enregistrés à l'installation ;
- provider `slf4j-nop` pour éviter les warnings SLF4J inutiles sur stderr MCP ;
- runner local `scripts/release/build-local-windows-candidate.ps1` qui construit le candidat sans publier ni créer de tag.

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

## Limite explicitement ouverte — #98

L'issue #98 reste ouverte : **la sandbox OS réelle Windows/Linux du worker distant n'est pas encore implémentée et qualifiée**.

La disposition produit reste :

```text
network DENY sans backend OS prouvé  → fail-closed
code non fiable                      → non supporté
claim « sandbox OS réelle »          → interdit
```

Ce reliquat ne doit jamais être masqué par la stabilité de la ligne 1.x.

## Sources de vérité opérationnelles

- état produit : ce fichier ;
- roadmap : [`ROADMAP.md`](ROADMAP.md) ;
- convergence M28 : [`roadmap/M28_EXECUTION.md`](roadmap/M28_EXECUTION.md) ;
- installation Windows : [`user/production-installation.md`](user/production-installation.md) ;
- release 1.0.0 : [`releases/1.0.0.md`](releases/1.0.0.md) ;
- candidat 1.0.1 : [`releases/1.0.1.md`](releases/1.0.1.md) ;
- publication autoritative : `scripts/release/publish-windows-release.ps1` ;
- construction locale sûre : `scripts/release/build-local-windows-candidate.ps1`.
