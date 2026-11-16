---
id: SEC-0083
title: "Filtres Git à la matérialisation d'un dépôt distant"
domain: security
rules: false
---

# Filtres Git à la matérialisation d'un dépôt distant

- **Statut** : Proposed (option B, recommandation du design du changement ; confirmation du propriétaire attendue)
- **Date** : 2026-11-15
- **Origine** : changement OpenSpec `couvrir-le-clone-distant-par-l-ouverture-de-confiance` (constat d'audit AUD-SEC-01, audit du 2026-11-15)
- **Remplace** : —
- **Remplacé par** : —

## Context

Un pilote `filter.<nom>` de Git est une commande : JGit l'exécute pour comparer le contenu d'un fichier (`clean`) et pour l'écrire à l'extraction (`smudge`), quand les attributs du dépôt désignent ce pilote. Un dépôt analysé n'est pas de confiance, mais la commande d'un pilote vient de la configuration **fusionnée** (dépôt, utilisateur, système) : un dépôt choisit, par ses attributs, lequel des pilotes déjà installés sur le poste s'exécute sur son contenu.

Deux chemins lisent un dépôt Git non fiable :

- l'analyse d'un dépôt **local** (`GitIntelligenceService`). La décision du 2026-10-13 (`ouvrir-le-depot-git-sans-executer-ses-filtres`, D3) neutralise les pilotes que la configuration du dépôt déclare et laisse ceux de l'utilisateur et du système, parce que l'utilisateur a désigné lui-même ce dépôt ;
- la matérialisation d'un dépôt **distant** (`JGitRemoteGitClient`, `JGitRemoteRepositoryMaterializer`), que cette décision écartait. Le clone extrait lui-même le contenu, puis la revalidation ouvre le dépôt sans le contrôle de propriétaire ni la neutralisation de l'ouverture de confiance.

Un dépôt distant est désigné par une requête, pas par un répertoire que l'utilisateur a déjà cloné. L'indexation de code n'a pas besoin des objets qu'un filtre produit (binaires de git-lfs).

Deux options réelles :

- **A** — étendre D3 : les pilotes de l'utilisateur et du système s'appliquent au clone distant comme à un `git clone` natif, ceux du dépôt jamais. Aucun changement de code du clone ; le dépôt distant garde la main, par ses attributs, sur le pilote de l'utilisateur qui s'exécute.
- **B** — aucun pilote exécuté : le contenu indexé est le contenu brut des objets Git (un fichier suivi par git-lfs est indexé sur son pointeur), aux conversions de fins de ligne de `core.autocrlf` près, qui ne sont pas des commandes.

Un `SystemReader` dédié pour isoler la configuration de l'utilisateur est écarté : c'est un état statique du processus, il changerait aussi l'analyse locale faite dans la même JVM (serveur MCP), dont l'exigence « Les filtres de l'utilisateur ne sont pas retirés » reste en vigueur.

## Decision

1. La matérialisation d'un dépôt distant (clone, extraction, revalidation) n'exécute **aucun** pilote `filter.*`, quelle que soit la configuration qui le déclare (option B).
2. Le dépôt cloné s'ouvre par **une seule ouverture de confiance** (`TrustedRepositoryOpening`), commune à l'analyse locale et à la matérialisation distante : propriétaire contrôlé avant toute lecture de l'index ou du contenu (la configuration est analysée, jamais exécutée, avant ce contrôle), refus fail-closed. Elle porte deux politiques de filtres : « dépôt local » neutralise les pilotes déclarés par la configuration du dépôt ; « dépôt distant » neutralise tous les pilotes de la configuration effective. Dans les deux, chaque pilote est redirigé en mémoire vers un filtre intégré qui recopie son entrée, et l'indication `useJGitBuiltin` est forcée à faux : un pilote qui demande un filtre intégré enregistré ne contourne pas la redirection.
3. Le clone ne fait **pas** l'extraction : il récupère les objets et positionne HEAD ; l'extraction se fait ensuite sur le dépôt ouvert par l'ouverture de confiance, avec le même moniteur de budget.
4. La revalidation d'une extraction (après clone et à chaque réutilisation du cache) passe par la même ouverture ; une entrée du cache extraite autrefois avec un filtre appliqué n'est pas propre sur le contenu brut et se rematérialise.
5. Aucun appel JGit n'ouvre un dépôt analysé hors de cette ouverture : `scripts/quality/check-remote-distributed-consistency.py` refuse `Git.open(` et `new FileRepositoryBuilder` dans le code principal du module `minos-integration-git`, hors du fichier de l'ouverture.
6. Limite déclarée : un dépôt distant dont des fichiers passent par un filtre est indexé sur leur contenu brut, ce que `docs/user/remote-indexing.md` dit.

## Do's and Don'ts

### Do

- Ouvrir tout dépôt analysé ou matérialisé par `TrustedRepositoryOpening`, avec la politique « dépôt distant » pour un dépôt obtenu par une requête.
- Extraire un dépôt distant après la redirection des pilotes, jamais avant.
- Écrire `useJGitBuiltin = false` sur chaque pilote redirigé.

### Don't

- Ne pas appeler `Git.open` ni `new FileRepositoryBuilder` en dehors de l'ouverture de confiance.
- Ne pas isoler la configuration de l'utilisateur par `SystemReader.setInstance` (état statique du processus).
- Ne pas laisser `CloneCommand` extraire le contenu d'un dépôt distant.
- Ne pas exécuter un filtre de l'utilisateur sur un dépôt distant pour retrouver des objets de git-lfs.

## Consequences

### Positives

- Aucune commande externe ni filtre intégré demandé par un dépôt distant ne s'exécute pendant le clone, l'extraction ou la revalidation, sur Windows comme sur Linux.
- Une seule implémentation du contrôle de propriétaire et de la neutralisation des filtres pour les deux chemins.
- Le gate rend une ouverture directe réintroduite visible en CI.

### Négatives

- Un dépôt distant à git-lfs est indexé sur ses pointeurs : écart de fidélité par rapport à un `git clone` natif.
- Le clone sans extraction puis l'extraction sur le dépôt ouvert diffèrent de `CloneCommand` pour le cas limite de HEAD ; les tests existants du matérialiseur le gardent.

### Risques

- Un comportement de JGit non couvert (nouvel élément de configuration qui désigne un filtre) échapperait à la redirection ; le test de la politique « dépôt distant » parcourt le cas d'un pilote de l'utilisateur et celui d'un filtre intégré.
- Une inversion de la décision (option A) remplace l'exigence « aucun pilote exécuté » par son contraire ; les deux autres exigences et le gate restent.

## Compliance and Enforcement

- Contrôle automatique : `RemoteMaterializationFilterTest` (clone, filtre intégré, revalidation du cache, propriétaire d'un autre compte ; JVM fille au répertoire personnel redirigé) ; `GitIntelligenceTrustTest` (l'analyse locale garde les filtres de l'utilisateur) ; gate `scripts/quality/check-remote-distributed-consistency.py` (job `invariants`) : aucune ouverture JGit directe dans le code principal du module.
- Revue : tout nouvel appel JGit qui lit l'index ou le contenu d'un dépôt analysé passe par l'ouverture de confiance (`.claude/rules/securite.md`, règle 5).

## Mise en œuvre

- `TrustedRepositoryOpening` (`minos-integration-git`) porte `openContaining` (analyse locale, politique « dépôt local ») et `openExact` (dépôt distant, politique « dépôt distant » ; une racine sans répertoire `.git` propre est refusée, jamais cherchée vers le haut). `GitIntelligenceService` et `JGitRemoteRepositoryMaterializer` l'utilisent ; `JGitRemoteGitClient` clone sans extraction puis extrait par `DirCacheCheckout`, avec le moniteur de budget du clone.
- `RemoteMaterializationFilterTest` fixe le comportement dans une JVM fille au répertoire personnel redirigé : pilote de l'utilisateur au clone, pilote qui demande un filtre intégré enregistré, revalidation d'une entrée du cache par un filtre `clean` de l'utilisateur, entrée d'un autre compte refusée. Avant la correction, les quatre échouaient parce que le filtre s'exécutait ou que le propriétaire n'était pas contrôlé.
- `validateCheckout` demande l'état du dépôt en ignorant les sous-modules (`IgnoreSubmoduleMode.ALL`) : le clone n'en initialise aucun, et la revalidation ne descend pas dans un dépôt qu'elle n'a pas ouvert par l'ouverture de confiance.
- Les conversions de fins de ligne de `core.autocrlf` (configuration de l'utilisateur ou du système) s'appliquent encore à l'extraction : ce ne sont pas des commandes, et la revalidation les applique à l'identique.
- Écart de comportement connu : un dépôt distant à git-lfs est indexé sur ses pointeurs (`docs/user/remote-indexing.md`).

## References

- [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) et `.claude/rules/securite.md` (règles 1, 5 et 8).
- Changement OpenSpec `couvrir-le-clone-distant-par-l-ouverture-de-confiance` ; décision du 2026-10-13 `ouvrir-le-depot-git-sans-executer-ses-filtres` (D3, analyse locale).
- Dossier de capacité `docs/architecture/diagrams/capacites/analyse-git.md`.
