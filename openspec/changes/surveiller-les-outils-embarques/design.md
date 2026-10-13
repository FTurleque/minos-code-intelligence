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

**Non mesuré** : qu'OSV-Scanner, à la version épinglée, ignore réellement un verrou au nom préfixé, et que `--lockfile` se combine avec `--recursive` dans le workflow réutilisable. C'est le **rouge** de la tâche 1.1 : un run sur un verrou volontairement vulnérable.

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

### D1. `--lockfile` explicite, pas de renommage — **décision en attente du propriétaire**

Recommandation : ajouter deux lignes `--lockfile=package-lock.json:<chemin>` aux `scan-args` du job OSV. Aucun fichier livré ne bouge, aucun contrat de chemin n'est touché.

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
