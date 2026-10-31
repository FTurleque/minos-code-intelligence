# Design

## Context

Sprint 3 de l'audit du 10 octobre 2026 : **AUD-DEP-03**. HEAD analysé : `2d1cc214` (`develop`).

## État vérifié au HEAD

### AUD-DEP-03 — confirmé

- Verrous : `minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/scip-typescript-package-lock.json` (5 paquets, typescript 5.9.3) et `scip-python-package-lock.json` (61 paquets, dont inflight 1.0.6 et glob 7.2.3), `lockfileVersion` 3 ; 66 paquets. Le rangement de `minos-provider-scip` en sous-packages ne les a pas déplacés.
- `embedded-tools.json` : 9 `artifacts` + 2 `assembled` = 11 composants, 7 `providers`.
- `.github/dependabot.yml` : cinq écosystèmes, pas de `npm`.
- `.github/workflows/pr-ci.yml:19-31` (l'audit citait `:19-27`) : workflow réutilisable d'OSV-Scanner, `scan-args` = `--recursive`, `--experimental-exclude=g:**/fixtures/**`, `./`, `fail-on-vuln: true`. Aucun `osv-scanner.toml`.
- `pom.xml:371-372` : Dependency-Check exclut les outils SCIP embarqués.

**Non mesuré** : qu'OSV-Scanner, à la version épinglée, ignore réellement un verrou au nom préfixé, et que `--lockfile` se combine avec `--recursive` dans le workflow réutilisable. C'est le **rouge** de la tâche 1.1 : un run sur un verrou volontairement vulnérable. **Toujours non mesuré au 2026-10-13** (moteur Docker du poste indisponible) : voir « Mise en œuvre », qui donne ce qui a été lu dans les sources amont et la procédure restant à exécuter.

### Qui lit les verrous aujourd'hui

- Ressources Java : `ManagedScipProviderRuntimeManager.java:88`, `ManagedScipPythonRuntimeManager.java:37`.
- Tests : `LockedNpmPackageTest.java:18,27`, `ScipRuntimeStoragePrivacyTest.java:41`.
- Catalogue : `embedded-tools.json:225` (champ `lock`, lu par `scripts/release/build-embedded-tools.py:151`).
- Image : `docker/Dockerfile.mcp.release:127-128,135,139`.
- Contextes de build : `scripts/ci/qualify-docker-release.sh:53-56` ; `docker/scripts/mcp-lifecycle.ps1:373-374`, qui extrait l'entrée **du jar** par son chemin.
- Tests de contrat littéraux : `M29DockerNpmLockContractTest.java:20-27`, `M29DockerReleaseCiGateContractTest.java:34-35`.

### Écarts avec l'action proposée par l'audit

1. **Le renommage en `<outil>/package-lock.json` casse cinq familles de sites** (liste ci-dessus), dont un lanceur qui lit le jar par chemin sur le poste de l'utilisateur.
2. **Il poserait un `package-lock.json` dans le jar et dans l'arbre source.** `DefaultDiscoveryPlugins.java:47` et `BuildDescriptorPolicy.java:22` reconnaissent ce nom comme un projet npm : l'auto-indexation de MINOS par lui-même et les goldens pourraient changer (non mesuré).
3. **Dependabot `npm` réécrirait l'arbre livré.** Ces verrous **sont** ce qui est installé chez l'utilisateur ; une PR automatique changerait le contenu livré sans la qualification du catalogue (ADR 0040) et proposerait de monter scip-typescript hors du catalogue.
4. **La veille du catalogue n'est pas complète par nature.** OSV connaît des écosystèmes (npm, Maven, Go, crates.io…), pas un binaire téléchargé depuis une page de releases. Certains composants n'ont pas d'identifiant interrogeable : le script doit le **dire**.

## Goals / Non-Goals

**Goals** : une vulnérabilité connue dans un paquet npm livré fait échouer le gate OSV d'une PR ; un verrou ajouté ne peut pas échapper au scan ; une vulnérabilité ou une fin de support d'un composant du catalogue est signalée sans attendre un audit ; la couverture réelle est écrite.

**Non-Goals** : monter automatiquement ; couvrir ce qu'aucune base ne décrit ; bloquer une PR sur une alerte du catalogue apparue ailleurs.

## Decisions

### D1. `--lockfile` explicite, pas de renommage — **décidé par le propriétaire le 2026-10-13**

**Décision du propriétaire (2026-10-13)** : `--lockfile` explicite. Aucun verrou n'est renommé, aucun écosystème `npm` n'est ajouté à Dependabot.

Recommandation retenue : ajouter deux lignes `--lockfile=package-lock.json:<chemin>` aux `scan-args` du job OSV. Aucun fichier livré ne bouge, aucun contrat de chemin n'est touché.

Alternative (action de l'audit) : renommer et ajouter `npm` à Dependabot. Coût : cinq familles de sites, un effet non mesuré sur la découverte, et des PR qui modifient l'arbre livré. À ne retenir que si le propriétaire veut des PR automatiques sur ces verrous.

### D2. Un gate de présence, par liste calculée

Un contrôle statique (dans `check-ci-wiring.py`, qui lit déjà `pr-ci.yml`) énumère les fichiers `*package-lock.json` versionnés sous un `src/main/resources` et exige que chacun figure dans les `scan-args`. Chemins construits depuis l'arbre, pas une liste écrite à la main.

### D3. Veille planifiée : un script, un workflow, une sortie honnête

`scripts/quality/check-embedded-tools-advisories.py` :

- lit `embedded-tools.json` ; pour chaque composant, une table **dans le script** associe l'identifiant du catalogue à un couple (écosystème, nom) interrogeable, ou à `None` avec la raison ;
- interroge l'API OSV (requêtes par lot) et, pour les runtimes (Node, JDK), une source de dates de fin de support ; **un échec réseau est un échec du script**, pas un succès vide ;
- sort : composants touchés (identifiants d'avis), composants en fin de support, composants **non couverts** avec leur raison ; code de sortie non nul si un composant est touché ou hors support ;
- un composant du catalogue absent de la table fait échouer le script (pas de composant oublié).

L'auto-test (`--self-test`, réponses simulées, sans réseau) est câblé dans `invariants`. Le workflow planifié (hebdomadaire, plus `workflow_dispatch`) exécute le script réel, avec des actions épinglées par SHA, `permissions: contents: read` et un délai maximal.

Alternative écartée : faire tourner la veille dans `pr-ci.yml`. Une CVE publiée un mardi rendrait rouges toutes les PR sans rapport ; le gate de PR reste limité à ce que la PR livre (les verrous).

### D4. Une alerte se traite par le catalogue

La veille ne modifie rien. Le traitement est une montée de catalogue qualifiée (skill `minos-dependency-update`) ou une dérogation datée au registre des risques ; `docs/developer/supply-chain.md` le dit.

## Direction des dépendances

Aucune dépendance de module. Le script lit une ressource de `minos-provider-scip` comme le font déjà `check-tools-manifest.py` et `build-embedded-tools.py`.

## Qualification des capacités

- Analyse OSV des deux verrous npm à chaque PR : **qualifiée** après la tâche 1 (preuve par un verrou vulnérable sur une copie).
- Veille du catalogue : **partielle par construction** ; la part non couverte est énumérée par la sortie du script.
- Veille de `scip-java-classpath.lock.json` : **non supportée** tant que la tâche 3.2 ne l'a pas mesurée.

## Windows et Linux

Scripts Python sans dépendance de plateforme ; les workflows tournent sous Ubuntu 24.04. Aucun effet sur les livrables Windows ou Docker.

## Risks / Trade-offs

- Un avis sans correctif disponible rend la veille rouge durablement : prévoir une liste de dérogations **datées** dans le dépôt, chacune avec identifiant d'avis, raison et échéance ; une dérogation échue redevient un échec.
- Dépendance à des services externes (OSV, source de fin de support) : l'échec réseau est distingué d'une alerte dans la sortie.
- Faux sentiment de couverture : contré par la section « non couverts » obligatoire.

## Mise en œuvre (2026-10-13)

### Tâche 1.1 — rouge avec OSV-Scanner : **non mesuré**

OSV-Scanner n'est pas installé sur le poste et le moteur Docker ne répondait pas (`docker version` : `Error response from daemon: Docker Desktop is unable to start`, relancé à plusieurs reprises pendant la séance). Aucun binaire n'a été téléchargé à la place. Aucune sortie d'OSV-Scanner n'est donc consignée ici.

Ce qui a été **lu** (pas exécuté) :

- **Version réellement exécutée.** `pr-ci.yml:21` épingle `google/osv-scanner-action/.github/workflows/osv-scanner-reusable.yml@8ac9e5ce44cc7178e0e04229a91bdcc003166e57`, commenté `# v2.3.8`. Ce SHA n'est pas le tag `v2.3.8` : c'est un commit de la branche principale du 2026-09-14 (« Update github/codeql-action action to v4.38.0 »), dont le workflow appelle `osv-scanner-action@7f58dd67… # v2.6.0`, c'est-à-dire l'image `ghcr.io/google/osv-scanner-action:v2.6.0`. La version à utiliser pour la mesure est donc **2.6.0** ; le commentaire `# v2.3.8` est inexact (non corrigé ici : hors périmètre).
- **Reconnaissance par le nom.** L'extracteur npm d'`osv-scalibr` (`extractor/filesystem/language/javascript/packagelockjson/packagelockjson.go`, `FileRequired`, lu sur la branche principale ; OSV-Scanner 2.6.0 dépend de `osv-scalibr v0.5.3-0.20260911142458-3090dbb7aaa2`) ne retient un fichier que si son nom de base est `package-lock.json` ou `npm-shrinkwrap.json`. Un `scip-python-package-lock.json` n'est donc pas retenu par un parcours récursif. C'est une lecture de source, pas une preuve d'exécution.
- **Paquet témoin.** `scip-python-package-lock.json:585-586` fixe `minimatch` 3.1.5 ; l'API OSV répond pour `npm minimatch 3.0.4` les avis `GHSA-23c5-xmqv-rm74`, `GHSA-3ppc-4f35-3m26`, `GHSA-7r86-cg39-jmmj` et `GHSA-f8q6-p94x-37v3` (appel réel à `https://api.osv.dev/v1/querybatch`, 2026-10-13).

Procédure restant à exécuter (sur une **copie** du dépôt hors des arbres de travail, où la ligne 586 du verrou devient `"version": "3.0.4",`), **commandes non exécutées, à ajuster si l'image attend une autre forme** :

```text
# rouge (1.1) : arguments d'avant le changement, attendu : aucun avis, code 0, le verrou absent des sources lues
docker run --rm -v <copie>:/src -w /src ghcr.io/google/osv-scanner:v2.6.0 scan source --recursive "--experimental-exclude=g:**/fixtures/**" ./

# vert (1.2) : arguments du job, attendu : échec nommant minimatch 3.0.4 et un avis GHSA
docker run --rm -v <copie>:/src -w /src ghcr.io/google/osv-scanner:v2.6.0 scan source --recursive "--experimental-exclude=g:**/fixtures/**" --lockfile=package-lock.json:minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/scip-python-package-lock.json --lockfile=package-lock.json:minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/scip-typescript-package-lock.json ./

# dépôt réel (1.2) : mêmes arguments sur l'arbre non modifié, attendu : code 0, les deux verrous parmi les sources lues
```

Le premier run du job `Dependency vulnerability gate` sur la PR de ce changement est l'autre preuve possible de la moitié « dépôt réel ». Tant que l'une de ces mesures manque, la capacité « analyse OSV des deux verrous » reste **non qualifiée**.

### Tâche 3.2 — les 74 jars du verrou de scip-java : **inclus**

- **Écart avec « Hors périmètre » de la proposition** : le verrou ne contient pas des coordonnées Maven mais des noms de fichiers (`nom`, `taille`, `sha256`). Le groupe de chaque artefact a été relevé dans le cache Coursier du poste (`~/AppData/Local/Coursier/cache/v1/https/repo1.maven.org/maven2`) : les 74 jars y sont, et l'empreinte SHA-256 de chacun est celle du verrou (74/74). La table `CLASSPATH_GROUPS` du script porte ces groupes ; `guice-4.2.2-no_aop.jar` est interrogé en version `4.2.2` (classifieur retiré).
- **Mesure** (`python scripts/quality/check-embedded-tools-advisories.py`, 2026-10-13, poste Windows) : 81 paquets (74 jars + 7 paquets du catalogue) dans **une** requête par lot à `https://api.osv.dev/v1/querybatch`, plus une lecture du calendrier de Node.js ; durée totale mesurée **1,2 s** (`real 0m1.167s`) ; aucune clé ni quota rencontré (la limite documentée est de 1 000 requêtes par lot).
- **Entretien** : un jar du verrou sans groupe dans la table, ou une entrée de table sans jar, fait échouer le script et son auto-test (donc le job `invariants` de la PR qui monte scip-java).

### Ce qu'un passage réel signale aujourd'hui (non traité ici : D4)

Code de sortie `1`, catalogue au HEAD de la branche :

- `scip-java-classpath` 0.13.1 : `GHSA-j288-q9x7-2f5v` (commons-lang3 3.8.1), `GHSA-5mg8-w23w-74h3` et `GHSA-7g45-4rm6-3mm3` (guava 25.1-android), `GHSA-6fmv-xxpf-w3cw` (plexus-utils 4.0.0) ;
- `nodejs` 20.20.2 (linux-x64) : Node.js 20 hors support depuis le 2026-04-30 (porté par `aligner-node-et-l-inventaire-de-l-image-docker`, AUD-DEP-02).

Le workflow planifié sera donc **rouge dès son premier passage**, tant que ces constats n'ont ni montée de catalogue ni dérogation datée.

### Écarts avec ce design

1. **Les 7 `providers` sont aussi dans la table**, pas seulement les 11 composants : `scip-go` et `scip-python` n'ont aucun composant au catalogue et seraient restés invisibles. 18 entrées lues, 14 identifiants dans `WATCHES`.
2. **Source de fin de support** : le calendrier officiel `nodejs/Release` (`schedule.json`). Aucun JDK n'est au catalogue (ADR 0040, « pas de JDK embarqué ») : rien à contrôler de ce côté.
3. **Trois codes de sortie** (`0`, `1`, `2`) plutôt qu'un message seul pour distinguer l'échec réseau ; sur un code `2`, aucune section n'est imprimée.
4. **Une quatrième section de sortie**, « sous dérogation », en plus des trois de D3 ; une dérogation qui ne correspond à aucun constat échoue (cliquet), en plus de la dérogation échue.
5. **Une erreur de table arrête le script avant toute requête** : une veille partielle ne doit pas ressembler à une veille complète.
6. **Maven, scip-dotnet et scip-go sont « partiellement suivis »** : le paquet publié est interrogé, pas les bibliothèques qu'il contient ; la sortie le dit.
7. Le gate de présence ignore aussi `fixtures/`, `target/`, `build/` et `node_modules/`, et refuse un argument `--lockfile` qui nomme un fichier absent.

### Qualification des capacités après mise en œuvre

- Analyse OSV des deux verrous npm à chaque PR : **non qualifiée** (arguments en place, gate de présence vert ; la mesure 1.1/1.2 manque).
- Gate de présence : **qualifié** (auto-test rouge puis vert, cas de refus, dépôt réel).
- Veille du catalogue : **partielle par construction**, part non couverte énumérée par la sortie ; le workflow planifié n'a pas encore tourné sur GitHub (tâche 3.4).
- Veille de `scip-java-classpath.lock.json` : **qualifiée** (74 jars interrogés, mesure ci-dessus).
