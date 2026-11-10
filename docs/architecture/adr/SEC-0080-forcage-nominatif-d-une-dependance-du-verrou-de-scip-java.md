---
id: SEC-0080
title: "Forçage nominatif d'une dépendance du verrou de scip-java"
domain: security
rules: false
---

# Forçage nominatif d'une dépendance du verrou de scip-java

- **Statut** : Proposed
- **Date** : 2026-11-10
- **Origine** : changement OpenSpec `forcer-guava-dans-le-classpath-de-scip-java` (risque R-29, constat d'audit AUD-DEP-03) ; décisions du propriétaire du 2026-11-10
- **Remplace** : —
- **Remplacé par** : —

## Context

Le classpath de scip-java n'est pas livré par le projet amont sous forme d'archive : MINOS le résout avec le Coursier épinglé du catalogue (`coursier fetch --classpath org.scip-code:scip-java:<version>`), puis compare chaque jar au verrou `scip-java-classpath.lock.json` (nom, taille, SHA-256) avant de garder quoi que ce soit ([BE-0065](BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md) § 12). Le verrou reflète la résolution amont : un avis sur un jar transitif se corrige en montant scip-java.

Quand aucune version de scip-java n'embarque la correction, quatre options existent : attendre l'amont sous dérogation datée, forcer la version du seul module touché dans la résolution, remplacer un jar à la main dans le verrou, ou retirer scip-java. Le cas d'origine est guava 33.6.0-jre (`GHSA-xxph-c9ww-hj94`, corrigé en 33.7.2-jre), tiré par maven-core 3.9.16 et guice 5.1.0, alors que scip-java 0.13.2 est la dernière version publiée. Coursier sait forcer la version d'un module (`--force-version organisation:nom:version`), option acceptée par le lanceur épinglé du catalogue (mesuré le 2026-11-10).

## Decision

1. Un avis sur une dépendance transitive du classpath de scip-java, qu'aucune version de scip-java ne corrige, peut se corriger par un **forçage nominatif** de la version du module touché dans la résolution Coursier.
2. Le forçage est **listé avec sa raison** dans le verrou, que le catalogue déclare : une entrée `forcedVersions` par module (`module` au format `organisation:nom`, `version`, `advisory`, `reason`). Il n'est écrit nulle part ailleurs ; `embedded-tools.json` ne le duplique pas.
3. Le forçage est **vérifié par somme** : le verrou est régénéré par `scripts/release/build-embedded-tools.py --update-classpath-lock`, qui résout avec les versions forcées et recalcule la taille et le SHA-256 de chaque jar. L'installation sur le poste et la construction du paquet passent la même liste d'arguments (`fetch --classpath`, une option `--force-version` par entrée dans l'ordre du verrou, puis la coordonnée), et le verrou continue de refuser tout jar différent.
4. Le forçage est **prouvé par une indexation Java réelle** : l'indexation hors ligne d'un projet Maven sous le bac à sable Linux, avec le classpath installé par `minos tools install scip-java`, réussit avant qu'il soit accepté.
5. Un forçage par module, jamais scip-java lui-même. Les modules voisins suivent la résolution : on ne force pas un module sans avis pour garder son ancienne version.
6. Le forçage se retire à la première version de scip-java qui embarque la correction, par une montée de catalogue qualifiée.
7. Deux résolutions restent hors du verrou, donc sans forçage : le repli de `scip-java-windows-runner.ps1` sans classpath installé (Windows natif est `BLOCKED`, [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md)) et le lanceur autonome de `docker/Dockerfile.mcp.release`, image qui n'exécute aucun provider.

Options écartées : remplacer un jar à la main (aucune résolution ne le prouve, la somme n'est plus reproductible) ; attendre l'amont (le risque reste ouvert sans échéance) ; retirer scip-java (perte de l'indexation Java).

## Do's and Don'ts

### Do

- Déclarer chaque forçage dans `forcedVersions` du verrou de scip-java, avec l'avis corrigé et une raison non vide.
- Régénérer le verrou par `build-embedded-tools.py --update-classpath-lock` et relire son diff jar par jar.
- Construire la commande Coursier depuis le verrou, à l'installation comme à la construction.
- Prouver l'indexation Java réelle sous le bac à sable Linux avant d'accepter un forçage.

### Don't

- Ne pas remplacer ni ajouter un jar à la main dans le verrou.
- Ne pas forcer scip-java lui-même, ni un module sans avis.
- Ne pas garder un forçage quand une version de scip-java embarque la correction.
- Ne pas résoudre le classpath sans les versions forcées quand le verrou en déclare.

## Consequences

### Positives

- Un avis transitif se corrige sans attendre l'amont, avec la même vérification par somme qu'avant.
- Le forçage et le contrôle lisent le même fichier : ils ne peuvent pas diverger.

### Négatives

- Le verrou ne reflète plus la résolution amont pure : scip-java tourne avec une version de dépendance que son projet n'a pas qualifiée.
- Une installation existante ne se vérifie plus contre le nouveau verrou et MINOS la réinstalle.

### Risques

- Une API de la dépendance forcée change et casse scip-java : l'indexation réelle de la décision 4 le détecte avant l'acceptation.
- Le repli Windows et l'image Docker résolvent sans forçage (décision 7) : le premier n'est pas atteint tant que Windows natif est `BLOCKED`, la seconde n'exécute aucun provider.

## Compliance and Enforcement

- Gate `scripts/quality/check-tools-manifest.py` (job `invariants`, auto-test `scripts/quality/test_check_tools_manifest.py`) : refuse une entrée forcée sans module `organisation:nom`, version, avis ou raison, un module forcé deux fois, un jar forcé absent du verrou, et le forçage de scip-java lui-même.
- `ClasspathLock` (`minos-provider-scip`) refuse un verrou dont une entrée forcée est malformée, avant tout lancement de Coursier ; `ClasspathLockTest` fige la commande avec `--force-version`.
- `scripts/release/test_build_embedded_tools.py` : arguments `--force-version`, régénération idempotente, refus d'un module forcé non résolu à sa version, verrou inchangé après un refus.
- Indexation réelle : étape « Offline Maven index under the Linux sandbox (scip-java, designated repository) » de `Verify (ubuntu-24.04)` (`.github/workflows/pr-ci.yml`).
- Revue : le diff du verrou est relu jar par jar ; chaque jar qui change est justifié dans le changement qui le porte. Aucune règle Archgate compagnon : le gate Python lit déjà le verrou.

## Mise en œuvre

Changement `forcer-guava-dans-le-classpath-de-scip-java` : `com.google.guava:guava` forcé en `33.7.2-jre` pour `GHSA-xxph-c9ww-hj94`. La résolution forcée garde 70 jars ; changent `guava` (33.6.0-jre → 33.7.2-jre) et `jspecify` (1.0.0 → 1.0.1, tiré par guava 33.7.2). La preuve d'indexation réelle est celle de la CI Linux de la PR.

## References

- [BE-0065](BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md) § 12 (intégrité du classpath de scip-java, comparé au verrou).
- [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) (scip-java bloqué sous Windows natif).
- Changement OpenSpec `openspec/changes/forcer-guava-dans-le-classpath-de-scip-java/` ; capacité `installation-des-providers`.
- Chaîne d'approvisionnement : [`docs/developer/supply-chain.md`](../../developer/supply-chain.md) ; registre des risques : [`docs/architecture/risks/register.md`](../risks/register.md) (R-29).
