# 0040 — Livrer les indexeurs dans le paquet, pas après l'installation

Status: Proposed — conception, en attente de validation avant implémentation.

Complète [0032](0032-evidence-gated-polyglot-scip-providers.md) (providers SCIP sous preuve), [0037](0037-first-class-native-and-docker-runtime-backends.md) et la §Supply-chain de [ROADMAP.md](../ROADMAP.md).

## Contexte

**Exigence produit, jusqu'ici jamais écrite** : après avoir lancé le setup Windows (ou déplié le ZIP), MINOS doit être utilisable immédiatement. L'utilisateur ne doit pas avoir à installer lui-même les indexeurs dont MINOS a besoin pour fonctionner.

Vérification de l'état actuel du dépôt (24 septembre 2026) : cette exigence n'est notée **nulle part** — ni dans `docs/ROADMAP.md`, ni dans `docs/STATUS.md`, ni dans un ADR, ni dans les scripts de release. Le comportement actuel est même l'inverse :

- `scripts/release/build-windows-distribution.ps1` ne copie dans la distribution que l'image `jpackage` (runtime Java + `minos.jar`), les scripts d'intégration Docker/MCP, le SBOM et les notices. **Aucun indexeur n'est embarqué.**
- le `README.txt` généré dans le ZIP donne comme démarrage rapide `minos.cmd tools install scip-java`, c'est-à-dire une installation manuelle, en ligne, à la charge de l'utilisateur ;
- `packaging/windows/minos-installer.iss.template` ne contient aucune étape d'installation d'outil (aucune occurrence de `tools`, `scip`, `node`, `dotnet`) ;
- `docs/user/production-installation.md` annonce seulement que le runtime **Java** est embarqué ;
- `docs/user/polyglot-providers.md` répartit même les providers en deux régimes : installables par MINOS sous `MINOS_HOME/tools` (`scip-dotnet`, `scip-go`) et « binaire/toolchain opérateur » (`scip-clang`, `rust-analyzer`).

Seule l'image Docker release fait déjà mieux : elle embarque ses toolchains avec des téléchargements vérifiés par SHA-256. Le paquet Windows est donc en retrait par rapport au conteneur.

### Périmètre retenu

**Dans le périmètre** : les 7 indexeurs qualifiés (`scip-java`, `scip-typescript`, `scip-python`, `scip-go`, `scip-dotnet`, `scip-clang`, `rust-analyzer`) et les outils propres à MINOS, livrés prêts à l'emploi.

**Hors périmètre** : les toolchains du projet analysé (JDK du projet, Node.js, Go, SDK .NET, `cargo`/`rustc`). Elles appartiennent au poste de développement, MINOS les détecte mais ne les installe pas. Cette limite doit être dite explicitement à l'utilisateur, au lieu d'être confondue avec « il manque un outil MINOS ».

## Décision

### 1. Le paquet contient une charge d'outils versionnée

La distribution gagne un répertoire :

```text
<installation>/tools/
    TOOLS-MANIFEST.json
    scip-java/<version>/...
    scip-typescript/<version>/...
    ...
```

`TOOLS-MANIFEST.json` décrit, pour chaque composant : identifiant du provider, version exacte, plateforme, chemin relatif, taille, SHA-256, licence, URL d'origine. Les versions sont **exactement** celles de `ScipIndexerCatalog` : un écart entre le catalogue et le manifeste fait échouer le build.

### 2. Amorçage (« seed ») à la première utilisation, sans réseau

Au premier lancement — et à chaque fois que `MINOS_HOME/tools` s'avère incomplet — MINOS copie les composants manquants depuis `<installation>/tools/` vers `MINOS_HOME/tools/`, en vérifiant le SHA-256 avant et après copie, de façon idempotente, atomique et durable (`DurableAtomicFile`), sous permissions privées (`PrivateLocalStorage`). Les marqueurs de provider gérés existants (`StampManagedProviderMarkers`) restent l'autorité qui décrit ce qui est prêt.

Le répertoire d'installation reste en lecture seule : rien n'est exécuté depuis un emplacement modifiable sans élévation, conformément au principe déjà en vigueur pour l'autorité de sandbox.

### 3. Repli par téléchargement automatique, épinglé et vérifié

Si un composant est absent du paquet (plateforme non couverte), corrompu, ou qu'une version plus récente est explicitement demandée, MINOS le télécharge automatiquement, à ces conditions strictes :

- **la version et le SHA-256 attendus viennent du catalogue**, jamais du serveur ; un artefact dont le hash diffère est rejeté et l'erreur est explicite ;
- le téléchargement est fait par MINOS lui-même, jamais par un provider en cours d'exécution : la politique `DENY` d'egress des providers est inchangée ;
- il vise une liste blanche d'hôtes de distribution, en HTTPS, sans redirection vers un hôte hors liste ;
- il est borné (taille, délai) et journalisé comme un événement d'installation ;
- en cas d'échec réseau, le provider est déclaré indisponible avec la raison, sans jamais dégrader l'index : le reste de MINOS continue de fonctionner.

Un mode `--offline` (et la variable `MINOS_TOOLS_OFFLINE=1`) désactive ce repli pour les environnements cloisonnés, qui n'utilisent alors que la charge embarquée.

### 4. Le diagnostic dit la vérité, outil par outil

`minos doctor` distingue quatre états par provider : **embarqué et vérifié**, **téléchargé**, **absent** (avec la raison), **dépendance externe manquante** (Node, Go, SDK .NET, `cargo`) — ce dernier cas nommant clairement l'outil que l'utilisateur doit installer, puisqu'il ne relève pas de MINOS. La sortie JSON est stable pour être utilisable en CI et par l'installeur.

### 5. L'installeur et la documentation cessent de demander une étape manuelle

Le `README.txt` généré, `docs/user/production-installation.md` et `docs/user/installation.md` ne présentent plus `tools install` comme une étape de démarrage. `minos tools install` reste disponible, mais seulement pour un ajout ou une mise à niveau explicite.

Le setup Windows vérifie en fin d'installation, via `doctor --format json`, que les indexeurs embarqués sont amorçables, et affiche le résultat dans son écran de validation — sans bloquer l'installation si une dépendance externe du poste manque.

### 6. Supply-chain : un composant embarqué est un composant tracé

Chaque élément de la charge entre dans le SBOM CycloneDX et dans `THIRD-PARTY-NOTICES.txt`, avec sa licence. `scripts/release/check-supply-chain.py` est étendu d'un gate `check-tools-manifest.py` qui vérifie la cohérence catalogue ↔ manifeste ↔ fichiers réellement présents (hash et taille). Une release dont la charge d'outils diverge du catalogue est refusée.

La mise à jour transactionnelle de l'installation (`integration/update-installation.ps1`) traite la charge d'outils comme le reste du payload : staging, bascule, rollback.

## Conséquences

### Positives

- installer MINOS suffit pour indexer : plus d'étape manuelle, plus d'échec au premier `index` sur un poste neuf ;
- un poste sans accès Internet reste pleinement utilisable pour les langages couverts par la charge ;
- la distinction « outil MINOS » / « toolchain de ton projet » devient explicite au lieu d'être découverte par l'erreur ;
- la promesse d'installation est enfin alignée sur celle de l'image Docker release.

### Coûts et risques

| Risque | Atténuation |
|---|---|
| Taille du setup et du ZIP en forte hausse | Mesurer avant de figer ; ne pas embarquer une plateforme non ciblée par le paquet ; publier au besoin un ZIP « sans outils » pour les environnements qui les gèrent eux-mêmes |
| Composants tiers à jour dans le paquet | Les versions sont dans `ScipIndexerCatalog` et vues par le gate de release ; un composant obsolète bloque la publication au même titre qu'une dépendance Maven |
| Licences de redistribution | Vérification licence par licence avant embarquement ; un composant non redistribuable reste en téléchargement automatique et n'entre pas dans la charge |
| Le repli réseau contredit l'esprit hors ligne | Épinglage version + SHA-256, liste blanche d'hôtes, `--offline` disponible, jamais de téléchargement par un provider |
| Faux sentiment de complétude | `doctor` sépare explicitement « outil MINOS manquant » et « toolchain du projet manquante » |

## Plan d'implémentation

**Lot 1 — manifeste et amorçage.** `TOOLS-MANIFEST.json`, composant d'amorçage (copie vérifiée vers `MINOS_HOME/tools`), lecture par les runtime managers existants. Tests : amorçage sur `MINOS_HOME` vide, charge corrompue, amorçage concurrent, idempotence.

**Lot 2 — construction du paquet.** `build-windows-distribution.ps1` récupère les composants épinglés, vérifie leur hash, les place dans `tools/`, met à jour SBOM et notices ; gate `check-tools-manifest.py`. Tests : build reproductible, détection d'un écart catalogue/manifeste.

**Lot 3 — repli téléchargement.** Client de téléchargement épinglé (hôtes en liste blanche, hash obligatoire, bornes), mode `--offline`, messages d'erreur nettoyés. Tests : hash divergent, hôte non autorisé, redirection, coupure réseau, mode offline.

**Lot 4 — diagnostic, installeur, documentation.** États par provider dans `doctor` (texte + JSON), écran de validation du setup, réécriture du `README.txt` généré et des deux guides d'installation.

**Lot 5 — qualification release.** Ajout à la qualification Windows d'un scénario **machine vierge, sans réseau** : installer, `doctor`, `project add`, `index` d'un projet Java et d'un projet TypeScript de `fixtures/`, sans aucune commande `tools install`. Ce scénario devient un critère de publication.
