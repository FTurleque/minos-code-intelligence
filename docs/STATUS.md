# État courant — MINOS

Dernière mise à jour documentaire : **24 juillet 2026**

Ce fichier résume l’état produit. Les preuves détaillées par jalon sont archivées dans [`history/milestones/`](history/milestones/) ; les décisions architecturales durables sont indexées dans [`adr/`](adr/README.md).

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET LIVRÉ
M1 — Découverte et orchestration    TERMINÉ ET LIVRÉ
M2 — Intelligence des symboles      TERMINÉ ET LIVRÉ
M3 — Intelligence des relations     TERMINÉ ET LIVRÉ
M4 — Recherche et contexte compact  TERMINÉ ET LIVRÉ
M5 — Tests liés et dérivations      TERMINÉ ET LIVRÉ
M6 — Intelligence d’architecture    TERMINÉ, VALIDÉ ET LIVRÉ
M7 — Indexation incrémentale        TERMINÉ, VALIDÉ ET LIVRÉ
M8 — Analyse d’impact               TERMINÉ, VALIDÉ ET LIVRÉ
M9 — CLI stabilisée                 TERMINÉ, VALIDÉ ET LIVRÉ
M10 — Serveur MCP                   TERMINÉ, VALIDÉ ET LIVRÉ
M11 — API publique                  TERMINÉ, VALIDÉ ET LIVRÉ
M12 — Multi-dépôts + Git            TERMINÉ, VALIDÉ ET LIVRÉ
M13 — Intégration NEXUS             TERMINÉ, VALIDÉ ET LIVRÉ
M14 — Indexation autonome + PROD    TERMINÉ, VALIDÉ ET LIVRÉ
```

## Dernière porte MINOS validée

M14 a fermé l’indexation autonome et l’installation PROD Windows. La PR #43 a été fusionnée dans `main` le 24 juillet 2026.

Qualification Windows enregistrée dans [`roadmap/M14_EXECUTION.md`](roadmap/M14_EXECUTION.md) :

```text
head de qualification        7a3ed0c14c2188b1ef6cbed6eb12a6c57c51bbb7
PR M14 head final            7d5678ca2111d0cdb9e3100edd05bf7bcb1adce7
merge main                   5ed008f4d8a9d3648d3743adfdd018c5cf5a608b
Java                         24.0.1
sources main / test          181 / 92
tests                        236 PASS, 0 failure, 0 error, 0 skipped
ShadedJarSmokeIT             1 PASS
TypeScript                   FULL → SUCCEEDED → NO_CHANGES / NONE
Java                         FULL → SUCCEEDED → NO_CHANGES / NONE
Java invalide                échec attendu → STALE
snapshot après échec         ancien snapshot conservé
Java recovery                --force-full → SUCCEEDED → READY
release                      0.2.0-rc1
jpackage / ZIP / SHA-256     PASS
installation vierge         PASS
minos --version              MINOS 0.2.0-rc1
minos doctor                 READY
MCP natif                    handshake SUCCESS
Docker MCP                   validation durcie SUCCESS
```

M14 livre notamment :

- `minos index <project>` autonome ;
- `doctor` et `tools` pour les runtimes providers ;
- `scip-java 0.13.1` qualifié sous Windows ;
- `scip-typescript 0.4.0` géré localement ;
- staging puis promotion atomique des snapshots ;
- runtime Java MINOS embarqué dans la distribution Windows ;
- ZIP versionné + SHA-256 ;
- installation native CLI/MCP ;
- Docker MCP durci optionnel construit depuis le même JAR de release.

La publication des artefacts vers **GitHub Releases** est maintenant explicitement industrialisée par `scripts/release/publish-windows-release.ps1` et le workflow manuel `Publish Windows Release`.

## Intégration NEXUS livrée

Compagnon : `FTurleque/nexus-context-engine` PR #12, fusionnée dans `main`.

```text
head NEXUS validé   df61c9c07b5ec3271aba27f54da272b4689fb017
merge NEXUS         13fd6970f7350602c7a86aae729ddd4adad771bd
Java NEXUS          21.0.10 LTS
Maven               3.9.11
sources main        128
sources test        41
tests               80
failures            0
errors              0
skipped             6
BUILD SUCCESS
```

Sonar NEXUS : **Quality Gate Passed**, 0 Security Hotspot.

### Replay réel MINOS → NEXUS

```text
MINOS Java 24
  nexus-export --root <project>
        |
        | JSON contract v1
        v
NEXUS Java 21
  minos-import <project> < stdin
```

Preuve finale M13 :

```text
M13 MINOS->NEXUS: symbols=11, relations=6, nexus-symbols=11, search=5
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
M13 MINOS -> NEXUS replay SUCCESS
```

`GreetingPort` est importé avec `sourceProvider=minos` puis retrouvé par `SearchService`.

## Frontières architecturales courantes

- MINOS reste propriétaire des faits de Code Intelligence ;
- NEXUS reste propriétaire du ranking, de la sélection et du budget de contexte ;
- les capacités fournisseur absentes ne sont jamais inventées ;
- l’analyse d’impact reste potentielle, jamais une preuve runtime exhaustive ;
- une relation cross-repository exige une identité exacte et unique ;
- l’activité Git reste distincte de l’importance architecturale ;
- CLI, API, MCP et export NEXUS exposent le même cœur métier ;
- l’incrémental provider n’est activé que lorsqu’un provider le prouve ;
- le runtime Docker MCP ne devient pas un conteneur de build universel.

Voir l’index des décisions dans [`adr/README.md`](adr/README.md).

## Documentation

- portail : [`README.md`](README.md) ;
- utilisateur : [`user/README.md`](user/README.md) ;
- installation PROD Windows : [`user/production-installation.md`](user/production-installation.md) ;
- développeur : [`developer/README.md`](developer/README.md) ;
- décisions : [`adr/README.md`](adr/README.md) ;
- preuves historiques : [`history/milestones/README.md`](history/milestones/README.md).

## Source de vérité

`STATUS.md` décrit l’état livré. `ROADMAP.md` décrit la progression produit. Les ADR décrivent les décisions durables. Les rapports sous `history/milestones/` restent des archives et peuvent contenir des états intermédiaires propres à leur date de validation.
