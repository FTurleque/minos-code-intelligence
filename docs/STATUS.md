# État courant — MINOS

Dernière mise à jour : **8 août 2026**.

Ce fichier est la synthèse autoritative de l'état produit. Les preuves détaillées et les journaux de qualification restent dans [`roadmap/`](roadmap/), [`history/milestones/`](history/milestones/), [`adr/`](adr/README.md) et [`architecture/`](architecture/README.md).

## Synthèse

```text
C0 → M28                         TERMINÉS / LIVRÉS
M29 — Autonomous Docker Runtime TERMINÉ / INTÉGRÉ
M30 — Advanced Installer        TERMINÉ / INTÉGRÉ
M29 issue #107                  CLOSED / completed
M29 PR #108                     MERGED
M30 PR #110                     MERGED vers develop
M30 promotion PR #111           MERGED vers main
v1.0.0                          PUBLIÉE / IMMUTABLE
v1.0.1 Windows                  EN PRÉPARATION — NON PUBLIÉE
#98 sandbox OS réelle           OPEN — limitation explicite
```

Le code produit courant contient donc M29 et M30. Les anciennes mentions « M29 en cours », « S7 à qualifier », « S8 pending » ou « M30 non livré » ne décrivent plus l'état courant et doivent être lues uniquement comme historique lorsqu'elles apparaissent dans des journaux d'exécution datés.

## M29 — Autonomous Docker Runtime & Native Parity

M29 est terminé et intégré. L'issue **#107** est fermée et la PR **#108** a livré les huit sous-étapes.

Preuves de qualification historiques :

| Sous-étape | Résultat exact-head |
|---|---|
| M29-S1 — Backend contract & ADR | ✅ `c7a4e944...` |
| M29-S2 — Project identity / portable paths | ✅ `c7a4e944...` |
| M29-S3 — Autonomous Docker administration | ✅ `3df1b40...` |
| M29-S4 — Provider-complete Docker image | ✅ `3df1b40...` |
| M29-S5 — Autonomous indexing & vector lifecycle | ✅ `0959fb9...` |
| M29-S6 — Backend-agnostic MCP clients | ✅ `f7ef0e3...` |
| M29-S7 — Installer / switching / lifecycle | ✅ `50b462f...` |
| M29-S8 — Native/Docker parity | ✅ `da6a76f...` |

Le contrat durable reste :

```text
clients IA
   ↓
minos.exe mcp
   ↓
backend router
  ↙      ↘
native   docker
```

Les clients ne contiennent aucune logique Docker. Le choix `native|docker` est résolu derrière le point d'entrée stable et aucun fallback silencieux Docker→native n'est autorisé.

## M30 — Advanced Installer, Ollama Docker & PostgreSQL/pgvector

M30 est livré par la PR **#110**, puis promu vers `main` par la PR **#111**.

Capacités intégrées :

- wizard Windows **Standard / Avancé** ;
- runtime MCP indépendant : `native | docker | none` ;
- stockage indépendant : `local | postgresql` ;
- fournisseur sémantique indépendant : `disabled | local-hash | ollama` ;
- backend PostgreSQL réel avec migrations et pgvector ;
- PostgreSQL/Ollama Docker gérés avec réseau interne et volumes persistants ;
- intégrations MCP Copilot JetBrains, Copilot CLI, Claude CLI/Code, Claude Desktop et Codex CLI/Desktop ;
- résumé des choix avant installation ;
- upgrade/switch/uninstall transactionnels et ownership-aware.

## Hardening post-audit

La PR **#113** (`audit/release-installer-hardening → develop`) porte la convergence post-audit avant le prochain candidat 1.0.1 :

- versions Jackson 2/3 corrigées et centralisées ;
- scan OSV bloquant ;
- PostgreSQL/pgvector obligatoire en CI quand le gate l'exige ;
- configuration Testcontainers spécifique à Windows isolée du Linux CI ;
- validation aussi sur push de `develop`/`main` ;
- JaCoCo étendu aux responsabilités M29/M30 ;
- harmonisation du wizard Windows avec les patterns qualifiés dans NEXUS Context Engine ;
- réconciliation documentaire.

Aucun artefact construit avant cette convergence ne doit être présenté comme candidat final 1.0.1.

## Release 1.0.0

`v1.0.0` reste immuable. Le défaut Windows historique `NoClassDefFoundError: org/w3c/dom/Node` appartient à cette release et est corrigé uniquement sur la ligne 1.0.1. Il ne faut jamais déplacer ni recréer le tag 1.0.0.

## Release 1.0.1

État : **EN PRÉPARATION — NON PUBLIÉE**.

Le prochain candidat doit être reconstruit depuis un HEAD ayant passé les gates Linux + Windows, PostgreSQL/pgvector, sécurité dépendances, JaCoCo, MCP, IntelliJ et packaging Windows. La publication reste une opération explicite ; aucun tag `v1.0.1` ne doit être créé avant qualification exacte et validation du setup final.

## Limitation explicitement ouverte — #98

L'issue **#98** reste ouverte. La sandbox OS réelle des workers distants n'est pas implicitement résolue par M29/M30. Les modes qui exigent une isolation non disponible doivent continuer à échouer de façon capability-honest / fail-closed.

## Sources de vérité

- état courant : `docs/STATUS.md` ;
- feuille de route : `docs/ROADMAP.md` ;
- architecture : `docs/architecture/README.md` ;
- exécution M29 : `docs/roadmap/M29_EXECUTION.md` ;
- exécution M30 : `docs/roadmap/M30_EXECUTION.md` ;
- guide production Windows : `docs/user/production-installation.md` ;
- release 1.0.1 : `docs/releases/1.0.1.md`.
