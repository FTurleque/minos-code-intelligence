# État courant — MINOS

Dernière mise à jour : **9 août 2026**.

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
readiness PR #118               MERGED
readiness promotion PR #119     MERGED / QUALIFIÉE
correctifs installateur #122–127 MERGED / QUALIFIÉS
v1.0.0                          PUBLIÉE / IMMUTABLE
v1.0.1                          PUBLIÉE / IMMUTABLE
#98 sandbox OS réelle           OPEN — limitation explicite
```

## Release 1.0.1 — publiée

La release **MINOS v1.0.1** a été publiée le **9 août 2026** après validation utilisateur réelle du setup Windows.

Tag autoritatif :

```text
v1.0.1 → f762025d66e33c40324c811079f1527d122f90f9
```

Release : <https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1>

La publication transactionnelle finale a :

- reconnu et sauvegardé l'ancienne Release/tag 1.0.1 ;
- checkout le commit exact testé par le mainteneur ;
- construit le plugin IntelliJ en version finale `1.0.1` ;
- rejoué tests/build/structure et IntelliJ Plugin Verifier ;
- reconstruit et requalifié la distribution Windows ;
- validé ZIP, setup, MCP handshakes, installation et désinstallation ;
- remplacé l'ancien tag/release seulement après ces gates ;
- publié **10 assets** : 8 artefacts Windows/supply-chain + plugin IntelliJ ZIP/checksum ;
- re-téléchargé les 10 assets ;
- vérifié **5 paires payload/SHA-256** ;
- confirmé le tag final sur `f762025d66e33c40324c811079f1527d122f90f9`.

Workflow de publication final : run `31288322126`, conclusion **success**.

Le commit de release est immuable. Les commits postérieurs de documentation/maintenance ne doivent jamais déplacer `v1.0.1`.

## Qualification acquise

La qualification ayant mené à 1.0.1 comprend :

- Maven Linux avec PostgreSQL/pgvector Testcontainers réel et fail-closed ;
- Maven Windows ;
- JaCoCo ciblé M0–M30 ;
- OSV Scanner bloquant ;
- M19 / M20 ;
- M28 exact-head Linux et Windows ;
- SonarCloud Quality Gate ;
- IntelliJ unit tests / build / structure ;
- IntelliJ Plugin Verifier ;
- build jpackage Windows ;
- ZIP, SBOM CycloneDX, third-party notices et SHA-256 ;
- handshake MCP SDK sur runtime packagé ;
- compilation Inno Setup ;
- installation ZIP + handshake ;
- installation setup + handshake ;
- désinstallation setup ;
- contrôle des artefacts durables ;
- validation utilisateur réelle du wizard et de l'exécutable final.

## M29 — Autonomous Docker Runtime & Native Parity

M29 est terminé et intégré. Le contrat durable reste :

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

M30 est livré. Capacités intégrées :

- wizard Windows **Standard / Avancé** ;
- runtime MCP : `native | docker | none` ;
- stockage : `local | postgresql` ;
- sémantique : `disabled | local-hash | ollama` ;
- PostgreSQL/pgvector réel avec migrations ;
- PostgreSQL/Ollama Docker gérés ;
- intégrations MCP Copilot JetBrains, Copilot CLI, Claude CLI/Code, Claude Desktop et Codex CLI/Desktop ;
- résumé des choix avant installation ;
- upgrade/switch/uninstall transactionnels et ownership-aware.

## Hardening et release engineering

Les PR #113/#117/#118/#119 et les correctifs #122–#127 ont notamment livré :

- Jackson 2/3 corrigés et centralisés ;
- OSV bloquant ;
- PostgreSQL/pgvector obligatoire sur Linux ;
- Testcontainers cohérent Windows/Linux ;
- JaCoCo M29/M30 ;
- handshake MCP SDK du runtime packagé ;
- Plugin Verifier IntelliJ ;
- build/smoke Windows end-to-end ;
- détection/réparation ownership-aware des clients MCP ;
- réutilisation d'une image Docker locale exactement labellisée ;
- génération locale Windows répétable malgré les locks jpackage transitoires ;
- publication fail-closed et vérification post-upload des checksums.

## Release 1.0.0

`v1.0.0` reste immuable. Le défaut Windows historique `NoClassDefFoundError: org/w3c/dom/Node` appartient à cette release et est corrigé par 1.0.1. Le tag 1.0.0 ne doit jamais être déplacé ou recréé.

## Limitation explicitement ouverte — #98

L'issue **#98** reste ouverte. La sandbox OS réelle des workers distants n'est pas implicitement résolue par M29/M30 ou 1.0.1. Les modes qui exigent une isolation non disponible doivent continuer à échouer de façon capability-honest / fail-closed.

## Sources de vérité

- état courant : `docs/STATUS.md` ;
- feuille de route : `docs/ROADMAP.md` ;
- architecture : `docs/architecture/README.md` ;
- guide production Windows : `docs/user/production-installation.md` ;
- release 1.0.1 : `docs/releases/1.0.1.md` ;
- historique de livraison 1.0.1 : issue #106.
