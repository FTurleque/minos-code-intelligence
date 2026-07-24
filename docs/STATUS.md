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
```

## Dernière porte MINOS validée

M13 a été fusionné dans `main` puis revalidé sous Java 24 :

```text
main exact      7c5eda4727cda3d46cab24037e4f1276ff0b4a25
sources main    163
sources test    86
Surefire        227 PASS
Failsafe        1 ShadedJarSmokeIT PASS
BUILD SUCCESS
```

Export interne M13 :

```text
M13 MINOS export: contract=1, symbols=19, relations=14
```

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

Preuve finale :

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
- CLI, API, MCP et export NEXUS exposent le même cœur métier.

Voir l’index des décisions dans [`adr/README.md`](adr/README.md).

## Documentation

- portail : [`README.md`](README.md) ;
- utilisateur : [`user/README.md`](user/README.md) ;
- développeur : [`developer/README.md`](developer/README.md) ;
- décisions : [`adr/README.md`](adr/README.md) ;
- preuves historiques : [`history/milestones/README.md`](history/milestones/README.md).

## Source de vérité

`STATUS.md` décrit l’état livré. `ROADMAP.md` décrit la progression produit. Les ADR décrivent les décisions durables. Les rapports sous `history/milestones/` restent des archives et peuvent contenir des états intermédiaires propres à leur date de validation.
