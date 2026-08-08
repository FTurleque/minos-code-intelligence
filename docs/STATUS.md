# État courant — MINOS

Dernière mise à jour : **8 août 2026**.

Ce fichier est la synthèse autoritative de l'état produit. Les preuves détaillées et les journaux de qualification restent dans [`roadmap/`](roadmap/), [`history/milestones/`](history/milestones/), [`adr/`](adr/README.md) et [`architecture/`](architecture/README.md).

## Synthèse

```text
C0 → M30                         TERMINÉS / LIVRÉS
M29 issue #107                  CLOSED / completed
M29 PR #108                     MERGED
M30 PR #110                     MERGED vers develop
M30 promotion PR #111           MERGED vers main
hardening PR #113               MERGED
M28 Windows CI PR #117          MERGED
promotion develop → main #112   MERGED
main                             c2593ad7f9efa3d14a6fcfe7062811f64da425b3
v1.0.0                          PUBLIÉE / IMMUTABLE
v1.0.1 Windows                  PRÉ-PUBLICATION — NON PUBLIÉE
#98 sandbox OS réelle           OPEN — limitation explicite
```

Le code produit courant contient M29, M30 et le hardening post-audit. Aucun ancien candidat 1.0.1 construit avant les PR #113/#117/#112 ne doit être présenté comme candidat final.

## Qualification acquise avant décision de publication

Le HEAD `develop` promu par #112 a passé :

- Maven Linux avec PostgreSQL/pgvector Testcontainers réel et fail-closed ;
- Maven Windows ;
- JaCoCo ciblé M0–M30, avec le scope PostgreSQL qualifié sur Linux ;
- OSV Scanner bloquant ;
- M19 et M20 ;
- M28 exact-head Linux ;
- M28 exact-head Windows ;
- build jpackage Windows ;
- ZIP, SBOM, notices et SHA-256 ;
- handshake MCP SDK sur le runtime packagé ;
- compilation Inno Setup ;
- installation ZIP isolée + handshake ;
- installation setup isolé + handshake ;
- désinstallation setup ;
- vérification des artefacts durables ;
- SonarCloud Quality Gate.

Le gate IntelliJ/Plugin Verifier est désormais exigé sur les PR/push `develop|main` pertinents et est également intégré au workflow manuel de publication.

## M29 — Autonomous Docker Runtime & Native Parity

M29 est terminé et intégré. L'issue **#107** est fermée et la PR **#108** a livré les huit sous-étapes.

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

## Hardening post-audit — intégré

Les PR **#113** et **#117**, puis la promotion **#112**, ont livré et qualifié :

- Jackson 2/3 corrigés et centralisés ;
- scan OSV bloquant ;
- PostgreSQL/pgvector obligatoire sur le gate Linux ;
- Testcontainers Windows/Linux cohérent ;
- CI sur PR et push `develop/main` ;
- JaCoCo M29/M30 ;
- handshake MCP SDK du runtime packagé ;
- build/smoke Windows end-to-end ;
- harmonisation du wizard avec les patterns utiles de NEXUS ;
- qualification M28 Windows adaptée à la limite Windows-containers du runner GitHub-hosted.

## Release 1.0.0

`v1.0.0` reste immuable. Le défaut Windows historique `NoClassDefFoundError: org/w3c/dom/Node` appartient à cette release et est corrigé uniquement sur la ligne 1.0.1. Il ne faut jamais déplacer ni recréer le tag 1.0.0.

## Release 1.0.1

État : **PRÉ-PUBLICATION — NON PUBLIÉE**.

Un tag Git historique **`v1.0.1`** existe déjà sur le commit `2de847bdc6bc39e63715f20987a30f07731cc717`, antérieur au hardening final. Il ne constitue pas le candidat final. Le workflow de publication est volontairement fail-closed et refusera de déplacer ou d'écraser ce tag : la situation doit être résolue explicitement avant publication.

Avant décision de publication, il reste seulement les contrôles qui ne peuvent pas être remplacés par les smokes isolés : validation visuelle du wizard, essais sur les clients IA réellement installés chez le mainteneur, parcours utilisateur natif/Docker et vérification interactive preserve/purge. L'autorisation de publication reste explicite.

## Limitation explicitement ouverte — #98

L'issue **#98** reste ouverte. La sandbox OS réelle des workers distants n'est pas implicitement résolue par M29/M30. Les modes qui exigent une isolation non disponible doivent continuer à échouer de façon capability-honest / fail-closed.

## Sources de vérité

- état courant : `docs/STATUS.md` ;
- feuille de route : `docs/ROADMAP.md` ;
- architecture : `docs/architecture/README.md` ;
- exécution M29 : `docs/roadmap/M29_EXECUTION.md` ;
- exécution M30 : `docs/roadmap/M30_EXECUTION.md` ;
- guide production Windows : `docs/user/production-installation.md` ;
- release 1.0.1 : `docs/releases/1.0.1.md` ;
- suivi de publication : issue #106.
