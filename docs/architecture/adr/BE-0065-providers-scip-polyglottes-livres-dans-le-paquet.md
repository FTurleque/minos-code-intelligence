---
id: BE-0065
title: "Providers SCIP polyglottes sous preuve, livrés dans le paquet"
domain: backend
rules: false
---

# Providers SCIP polyglottes sous preuve, livrés dans le paquet

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : consolidation du registre (décision du propriétaire du 2026-10-23)
- **Remplace** : [ADR-0032](archive/0032-evidence-gated-polyglot-scip-providers.md) (« Evidence-gated polyglot SCIP providers »), [ADR-0040](archive/0040-distribution-auto-portante-indexeurs-embarques.md)
- **Remplacé par** : —

## Context

Un provider qui émet des symboles et des références ne fournit pas pour autant CFG, def-use, data-flow ou faits de sécurité ; « M23 keeps structured snapshots authoritative », la recherche sémantique restant optionnelle et `HEURISTIC` (ADR 0032, Context). L'ajout de C/C++, C#, Go et Rust ne doit ni créer de branche par langage dans le cœur ni traiter la disponibilité d'un parser ou d'un indexeur amont comme un support MINOS (ADR 0032). Les écosystèmes évalués ont des contraintes opératoires très différentes : binaires amont limités à certaines plateformes, SDK imposé, installation par `go install`, Rust émis par `rust-analyzer scip` (ADR 0032, Context ; versions et plateformes : `ScipIndexerCatalog` et [`polyglot-providers.md`](../../user/polyglot-providers.md)).

Exigence produit (ADR 0040) : après le setup Windows ou le dépliage du ZIP, MINOS est utilisable immédiatement ; l'utilisateur n'installe pas lui-même les indexeurs dont MINOS a besoin. Dans le périmètre : les indexeurs qualifiés et les outils propres à MINOS. Hors périmètre : les toolchains du projet analysé (JDK du projet, Node.js, Go, SDK .NET, `cargo`/`rustc`), que MINOS détecte sans les installer.

L'ADR 0040 reprend et modifie le régime d'installation de l'ADR 0032 (§ 5, 7 et 8) : la charge d'outils embarquée et le catalogue épinglé s'ajoutent aux installations locales sous `MINOS_HOME/tools`. Le cœur agnostique et le rôle de SCIP sont fixés par l'[ADR 0064](ARCH-0064-coeur-agnostique-du-langage-et-de-l-indexeur.md).

## Decision

### Intégration d'un provider (ADR 0032)

1. **Pipeline commun.** Toute intégration de langage alimente l'ingestion SCIP et le pipeline de snapshot existants ; aucun modèle de domaine ni chemin de stockage propre à un langage n'est introduit (ADR 0032 § 1). La découverte reste pilotée par SPI : les vocabulaires `Language` et `BuildSystem` peuvent s'étendre, `ProjectDiscoveryService` ne branche sur aucun langage (ADR 0032 § 2, repris par l'ADR 0064).
2. **Profil exhaustif.** Chaque provider a un profil de capacités et opératoire explicite et exhaustif : identifiant et version, langages, build systems, niveau de chaque capacité, limitations, plateformes de qualification, prérequis d'exécution, comportement d'installation et de disponibilité, stabilité des identités, provenance (ADR 0032 § 3).
3. **Disposition sur preuve.** `QUALIFIED`, `QUALIFIED_WITH_CONSTRAINTS` ou `EXPERIMENTAL` ne s'attribuent qu'après le passage des fixtures et des gates de plateforme correspondants ; un provider qui ne tient pas son contrat d'exécution ou de plateforme reste expérimental ou est documenté non supporté sur cette plateforme (ADR 0032 § 4). Le support de découverte n'est pas un support d'exécution : C/C++ reste non supporté à l'exécution sous Windows tant qu'une voie Windows reproductible n'est pas prouvée (ADR 0032 § 6). Une promotion se fonde sur le même HEAD exact sous Windows et Linux ; un provider inapplicable à une plateforme y rapporte une limitation documentée, jamais un faux PASS (ADR 0032 § 14).
4. **Identité et provenance.** La stabilité des identités se mesure par indexations répétées de fixtures, sans collision triviale entre espaces de noms ni entre surcharges ; les symboles bruts restent conservés comme `ProviderReference` (ADR 0032 § 9). Chaque symbole, occurrence et relation garde l'identifiant et la version du provider, le run d'indexation et l'origine SCIP ; un fait amont absent reste non résolu, aucune relation n'est fabriquée (ADR 0032 § 10).
5. **Pas de capacité avancée sans preuve.** Un nouveau provider ne revendique par défaut ni CFG, ni def-use, ni data-flow interprocédural, ni analyse de sécurité ; ces capacités exigent une preuve propre au provider (ADR 0032 § 11).
6. **Sémantique subordonnée.** Les snapshots polyglottes peuvent alimenter les documents sémantiques par le chemin existant ; les résultats sémantiques restent `HEURISTIC`, optionnels et subordonnés aux faits structurés ; `KEEP_CURRENT_M20_BACKEND` reste en vigueur ([ADR 0031](BE-0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md) ; ADR 0032 § 12).
7. **Surfaces additives.** CLI, API Java, MCP, IntelliJ et NEXUS exposent les données de provider et de découverte par les contrats partagés du cœur, sans tables de capacités par langage codées en dur et divergentes (ADR 0032 § 13).

### Installation et livraison des outils (ADR 0032 § 5, 7, 8, modifiés par ADR 0040)

8. **Une seule description.** Le paquet contient une charge d'outils versionnée sous `<installation>/tools/`, décrite par `TOOLS-MANIFEST.json` (identifiant, version exacte, plateforme, chemin relatif, taille, SHA-256, licence, URL d'origine) (ADR 0040 § 1). La description unique est le catalogue `minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/embedded-tools.json` : les gestionnaires Java le lisent, `docker/Dockerfile.mcp.release` porte exactement ses valeurs, la construction de `tools/` part de lui, et `ScipIndexerCatalog` annonce les mêmes versions ; le moindre écart fait échouer le build (ADR 0040 § 1 et « Mise en œuvre »). Node.js porte une seule version majeure entre plateformes (ADR 0040, « Mise en œuvre »).
9. **Amorçage sans réseau.** Au premier lancement, et chaque fois que `MINOS_HOME/tools` est incomplet, MINOS copie les composants manquants depuis `<installation>/tools/` en vérifiant le SHA-256 avant et après copie, de façon idempotente, atomique et durable, sous permissions privées ; le répertoire d'installation reste en lecture seule et rien n'est exécuté depuis un emplacement modifiable sans élévation (ADR 0040 § 2). Un artefact embarqué altéré est refusé, jamais remplacé par un téléchargement (ADR 0040, « Mise en œuvre »).
10. **Installations locales, toolchains intactes.** Une installation gérée par MINOS reste confinée sous `MINOS_HOME/tools` ; aucune installation globale npm, dotnet, go ou rust n'est faite et aucun service distant obligatoire n'est introduit (ADR 0032 § 5). scip-dotnet s'installe par un chemin d'outil local et scip-go par un `GOBIN` propre, tous deux à version épinglée sous `MINOS_HOME/tools` (ADR 0032 § 7). Le provider Rust exige un `cargo`/`rustc` compatible et la version épinglée de `rust-analyzer` ; MINOS ne modifie pas la toolchain Rust de l'utilisateur, et l'installation reste explicitement non supportée s'il ne peut gérer l'indexeur de façon reproductible (ADR 0032 § 8). Les toolchains du projet analysé ne sont pas installées ; aucun JDK n'est embarqué : l'indexation Java exige un JDK complet, Git Bash, PowerShell 5.1 et `csc.exe` sur le poste (ADR 0040, « Mise en œuvre », décision du propriétaire). Sous Windows natif, scip-java est `BLOCKED` et aucune voie n'indexe aujourd'hui un projet Java depuis Windows ([ADR 0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md)).
11. **Téléchargements épinglés.** Si un composant est absent du paquet (plateforme non couverte), corrompu, ou qu'une version plus récente est explicitement demandée, MINOS le télécharge automatiquement aux conditions suivantes : version et SHA-256 attendus pris dans le catalogue, jamais auprès du serveur ; téléchargement fait par MINOS, jamais par un provider en cours d'exécution, la politique d'egress `DENY` des providers restant inchangée ; liste blanche d'hôtes en HTTPS sans redirection hors liste ; téléchargement borné et journalisé ; en cas d'échec, provider déclaré indisponible avec sa raison, sans dégrader l'index. Un mode `--offline` et la variable `MINOS_TOOLS_OFFLINE=1` désactivent ce repli (ADR 0040 § 3 ; réalisation partielle : voir « Écarts constatés »).
12. **Intégrité de ce qui est exécuté.** Trois natures de composant : les artefacts épinglés sont comparés au SHA-256 du catalogue ; les arbres assemblés à la construction sont comparés au manifeste du paquet, marqués (`.minos-integrity.sha256`) et revérifiés à chaque inspection ; le classpath de scip-java résolu à l'installation (`coursier fetch --classpath`, jamais `launch`) est comparé jar par jar au verrou `scip-java-classpath.lock.json`, tout écart faisant échouer l'installation avant l'écriture de `classpath.txt`, et le plan d'indexation revérifie chaque jar contre le verrou avant chaque exécution (ADR 0040, « Mise en œuvre »).
13. **Diagnostic par outil.** `minos doctor` distingue un outil embarqué et vérifié, téléchargé, absent (avec sa raison) et une dépendance externe du poste manquante, en nommant l'outil à installer (ADR 0040 § 4). L'origine est le diagnostic `tools origin: <composant>=embedded|downloaded` ; une dépendance externe manquante est préfixée `machine prerequisite (not shipped by MINOS)` ; aucun champ JSON dédié (ADR 0040, « Mise en œuvre »).
14. **Aucune étape manuelle au démarrage.** Le `README.txt` généré et les guides d'installation ne présentent pas `tools install` comme une étape de démarrage ; `minos tools install` reste disponible pour un ajout ou une mise à niveau explicite. Le setup Windows vérifie en fin d'installation, par `doctor --format json`, que les indexeurs embarqués sont amorçables, sans bloquer si une dépendance externe du poste manque (ADR 0040 § 5).
15. **Chaîne d'approvisionnement.** Chaque composant embarqué entre dans le SBOM CycloneDX et dans `THIRD-PARTY-NOTICES.txt` avec sa licence ; une release dont la charge diverge du catalogue est refusée ; la mise à jour transactionnelle de l'installation traite la charge comme le reste du payload (staging, bascule, rollback) (ADR 0040 § 6). Les notices agrégées du jar ombré ne couvrent que les dépendances Maven ; les outils de la charge qui ne viennent pas de Maven en restent hors (ADR 0040, « Mise en œuvre », décision du propriétaire).
16. **Variante lite.** `minos-<version>-windows-x64-lite.zip`, sans `tools/`, est produite par le même script et vérifiée par le même gate ; la mise à jour d'une installation complète par un ZIP lite supprime `tools/` de façon transactionnelle (ADR 0040, « Mise en œuvre », décision du propriétaire).

## Do's and Don'ts

### Do

- Faire passer toute intégration de langage par l'ingestion SCIP et le pipeline de snapshot existants.
- Donner à chaque provider un profil de capacités et opératoire explicite et exhaustif.
- N'attribuer `QUALIFIED`, `QUALIFIED_WITH_CONSTRAINTS` ou `EXPERIMENTAL` qu'après le passage des fixtures et des gates de plateforme.
- Conserver pour chaque symbole, occurrence et relation l'identifiant et la version du provider, le run d'indexation et l'origine SCIP.
- Décrire la charge d'outils dans le seul catalogue `embedded-tools.json` et prendre dans ce catalogue la version et le SHA-256 de tout artefact téléchargé ou embarqué.
- Amorcer `MINOS_HOME/tools` depuis la charge embarquée, sans réseau, en vérifiant le SHA-256 avant et après copie.
- Faire distinguer par `minos doctor` un outil MINOS absent d'une dépendance externe du poste manquante.
- Faire entrer chaque composant embarqué dans le SBOM CycloneDX et dans `THIRD-PARTY-NOTICES.txt`.

### Don't

- Ne pas faire brancher `ProjectDiscoveryService` sur un langage.
- Ne pas revendiquer CFG, def-use, data-flow interprocédural ou analyse de sécurité pour un provider sans preuve propre.
- Ne pas fabriquer de relation absente en amont.
- Ne pas maintenir dans les surfaces des tables de capacités par langage codées en dur.
- Ne pas faire d'installation globale npm, dotnet, go ou rust, ni modifier la toolchain Rust ou les toolchains du projet analysé.
- Ne pas faire télécharger un outil par un provider en cours d'exécution, ni remplacer par un téléchargement un artefact embarqué altéré.
- Ne rien exécuter depuis un emplacement modifiable sans élévation.
- Ne pas présenter `tools install` comme une étape de démarrage.

## Consequences

### Positives

- MINOS gagne des chemins polyglottes sans fragmenter son architecture de providers ; les revendications de capacités sont comparables et vérifiables (ADR 0032).
- Les contraintes de plateforme et d'exécution deviennent des faits produit visibles ; identité stable et provenance restent des gates (ADR 0032).
- Installer MINOS suffit pour indexer les langages couverts par la charge, y compris sur un poste sans accès Internet ; la distinction « outil MINOS » / « toolchain du projet » est explicite (ADR 0040).

### Négatives

- Un transport SCIP commun n'implique pas une complétude sémantique commune ; la qualité des relations structurelles varie fortement d'un provider à l'autre (ADR 0032).
- Les providers .NET, Go et Rust dépendent de toolchains locales dont l'absence bloque la disponibilité (ADR 0032) ; aucun projet Java ne s'indexe depuis Windows (ADR 0074).
- Le setup et le ZIP grossissent ; la variante lite existe pour les environnements qui gèrent leurs outils (ADR 0040).

### Risques

- Composants tiers à jour : les versions vivent dans le catalogue, vu par le gate ; la veille OSV hebdomadaire ne couvre ni les binaires sans nom de paquet (Coursier, scip-clang, rust-analyzer) ni les avis du runtime Node.js (ADR 0040, « Mise en œuvre »).
- Le verrou de classpath de scip-java se régénère à chaque montée de version ; l'image Docker construit son propre classpath sans ce verrou ; une fenêtre reste entre la vérification d'un jar et son ouverture par la JVM (ADR 0040, « Mise en œuvre »).
- Les profils doivent être réévalués quand la version d'un provider change ([ADR 0026](BE-0026-discovery-provider-spi-and-explicit-capability-profiles.md), « Limites »).

## Compliance and Enforcement

- Gate `scripts/quality/check-polyglot-provider-consistency.py`, lancé par `scripts/remediation/check-post-mne.py` (job `invariants`) — vocabulaires `Language` et `BuildSystem`, marqueurs de découverte de `DefaultDiscoveryPlugins`, aucune branche `Language.CSHARP|GO|RUST|CPP|C` dans `ProjectDiscoveryService`, dispositions `QUALIFIED_WITH_CONSTRAINTS` et plateformes de qualification dans `ScipIndexerCatalog`, `CALL_RELATIONS` et `INCREMENTAL_INDEXING` `UNSUPPORTED`, installation de scip-clang et de rust-analyzer laissée à l'opérateur, scip-dotnet par `--tool-path`, scip-go par `GOBIN` isolé, aucun `rustup` implicite ni installation globale ; il exige aussi dans le texte de l'ADR les chaînes « Evidence-gated polyglot SCIP providers » et « structured snapshots authoritative », reprises ci-dessus.
- Gate `scripts/quality/check-tools-manifest.py` (job `invariants`, auto-test `scripts/quality/test_check_tools_manifest.py`) — une seule description de la charge : catalogue, gestionnaires Java sans empreinte en dur, `ARG` du Dockerfile, construction Windows, versions de `ScipIndexerCatalog`, une seule version majeure de Node, verrou de classpath de scip-java aligné sur le catalogue ; avec `--distribution`, manifeste, fichiers, tailles, empreintes, SBOM et notices d'une distribution construite.
- Script `scripts/quality/check-embedded-tools-advisories.py`, lancé chaque semaine par `.github/workflows/embedded-tools-advisories.yml` (hors PR, non exigé) — avis OSV sur les entrées du catalogue et les jars du verrou de scip-java.
- Tests `ManagedPolyglotScipRuntimeManagerTest`, `M24PolyglotIdentityProvenanceTest` (`minos-provider-scip`), `M24PolyglotDiscoveryTest`, `ProviderConformanceKitTest` (`minos-engine`), lancés par `mvnw verify`.
- Aucune règle Archgate compagnon (`rules: false`) : les deux gates Python lisent déjà les sources ; une règle qui lirait les mêmes reste à écrire et à voir en échec.
- Revue : un nouveau provider arrive avec un profil exhaustif et une disposition fondée sur fixtures et gates de plateforme ; un nouveau composant embarqué entre dans le catalogue, le SBOM et les notices dans le même changement.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR), preuves relues au HEAD `2f773e57` ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- Point 11 (ADR 0040 § 3) : le repli par téléchargement automatique n'est pas réalisé ; `minos tools install` télécharge des artefacts épinglés et vérifiés, et seule la variable `MINOS_TOOLS_OFFLINE=1` interdit tout téléchargement ; l'option de CLI `--offline` n'existe pas. Le propriétaire n'a pas tranché entre dette et abandon — preuve : `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ToolsNetworkPolicy.java:15` ; section « Mise en œuvre » de l'[ADR 0040](archive/0040-distribution-auto-portante-indexeurs-embarques.md).
- Point 15 (ADR 0040 § 6) : `check-tools-manifest.py` est un gate autonome du job `invariants`, pas une extension de `scripts/release/check-supply-chain.py`, qui ne le cite pas — preuve : `.github/workflows/pr-ci.yml:118`.
- Point 10 et [ADR 0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) : sous Windows natif, scip-java est `BLOCKED` alors que l'ADR 0040 (« Mise en œuvre ») livre son classpath dans le paquet Windows et tient le critère « machine neuve sans réseau » pour Java si les prérequis du poste sont présents ; aucun des deux textes ne nomme ce rapport — preuve : `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java:104`.

## References

- ADR actifs : [ADR 0064](ARCH-0064-coeur-agnostique-du-langage-et-de-l-indexeur.md) (cœur agnostique, SCIP privilégié), [ADR 0026](BE-0026-discovery-provider-spi-and-explicit-capability-profiles.md) (SPI et profils de capacités), [ADR 0031](BE-0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md) (couche sémantique), [ADR 0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) (scip-java sous Windows natif), [ADR 0061](SEC-0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.md) (E/S privées), [ADR 0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md) (confinement des providers, egress `DENY`), [ADR 0072](ARCH-0072-graphe-de-programme-honnete-sur-ses-capacites.md) (capacités avancées du graphe de programme).
- ADR archivés : [ADR 0032](archive/0032-evidence-gated-polyglot-scip-providers.md) (table « Final M24 disposition », SHA de qualification, PR #82), [ADR 0040](archive/0040-distribution-auto-portante-indexeurs-embarques.md) (contexte daté, plan d'implémentation en lots, « Mise en œuvre » détaillée).
- Exécution M24 : [`M24_EXECUTION.md`](../../project/roadmap/M24_EXECUTION.md) ; providers : [`docs/user/polyglot-providers.md`](../../user/polyglot-providers.md), [`docs/developer/polyglot-providers.md`](../../developer/polyglot-providers.md) ; chaîne d'approvisionnement : [`docs/developer/supply-chain.md`](../../developer/supply-chain.md) ; [`ROADMAP.md`](../../project/governance/ROADMAP.md) (§ Supply-chain).
