# Proposal

## Why

Constat **AUD-SEC-01** (sévérité élevée, sprint 2 de l'audit du 10 octobre 2026) : `minos git-activity` et les méthodes `inspectGit` / `analyzeGitActivity` de l'API Java cherchent le dépôt en **remontant sans plafond** depuis la racine du projet (`GitIntelligenceService.open`, `findGitDir`), n'examinent **ni le propriétaire** du dépôt trouvé, puis appellent `git.status().call()`. Pendant ce statut, JGit exécute par un shell (`sh -c` ou `cmd.exe /c`) le filtre `clean` déclaré par la configuration du dépôt (`filter.<pilote>.clean`) pour les entrées dont la date ne permet pas de conclure. JGit ne reproduit pas le `safe.directory` de git (CVE-2022-24765).

Conséquence : un `.git` posé par un autre compte dans un dossier parent (`C:\.git`, `/tmp/.git`) ou livré dans une archive fournit la configuration, `.gitattributes` et l'index, et la commande déclarée s'exécute **sous l'identité de l'utilisateur, hors bac à sable**, au premier `git-activity`. Sans exploitation, l'analyse porte quand même sur un autre dépôt que celui demandé.

Les preuves du constat sont revérifiées au HEAD dans `design.md`, avec une relecture des sources de JGit **7.8.0** (la version réellement embarquée) que l'audit n'avait pas pu faire.

## What Changes

- **Propriétaire du dépôt** : le dépôt trouvé n'est analysé que si son répertoire Git appartient au même propriétaire que ce que le processus MINOS crée lui-même. Sinon l'appel échoue de façon actionnable (fail-closed), sans exécuter quoi que ce soit.
- **Aucun filtre du dépôt n'est exécuté** : avant le statut, les sections `filter` de la configuration **du dépôt** sont retirées de la configuration en mémoire (jamais écrites sur disque). Les filtres de la configuration de l'utilisateur ou du système, qui relèvent de sa confiance, ne sont pas touchés.
- **Honnêteté du résultat** (principe capability-honest) : quand des filtres du dépôt ont été écartés, le résultat porte une limitation additive nommée ; quand le dépôt trouvé n'est pas à la racine du projet (remontée), une seconde limitation le dit. Le champ `clean` reste calculé, avec cette réserve explicite.
- **Tests** : un dépôt parent piégé (filtre qui crée un fichier témoin, configuration directe et par `include`), le cas du `.git` livré dans le projet, le cas du propriétaire différent (graine de test), les cas nominaux inchangés (dépôt à la racine, sous-dossier d'un dépôt).
- Une capacité `analyse-git` est créée pour porter ces garanties.

## Capabilities

### New Capabilities

- `analyse-git` : ce que l'analyse Git factuelle de MINOS (`git-activity`, `inspectGit`, `analyzeGitActivity`) garantit sur le dépôt qu'elle ouvre et sur ce qu'elle exécute. Capacité nouvelle : aucune capacité existante ne couvre l'intégration Git (`surfaces-publiques` traite des erreurs et du statut MCP, `confinement-code-non-fiable` du bac à sable des providers).

### Modified Capabilities

(aucune)

## Hors périmètre

- **Les autres constats du sprint 2** et des suivants : seuls les bénéficiaires sont cités (AUD-SEC-02 traite l'installation des providers dans un autre changement).
- **`JGitRemoteRepositoryMaterializer.java:273`** (`git.status().call()` sur une copie matérialisée) : sa configuration locale est produite par le clone, jamais copiée du dépôt distant ; le même risque n'y est pas établi. Noté dans `design.md`, non traité ici.
- Les **crochets Git** : ni JGit ni MINOS ne les exécutent dans ce chemin.
- Un **plafond de remontée** au dossier personnel ou une interdiction de la remontée : décision D1 de `design.md`, non retenue par défaut (elle casserait les projets situés dans un sous-dossier d'un monorepo).
- Aucun ADR (voir « Impact »).

## Impact

- **Modules du reactor touchés** : `minos-integration-git` (code et tests). `minos-engine` (`GitIntelligence`) est inchangé : les limitations sont des chaînes de la liste existante.
- **Surfaces publiques impactées** : la sortie JSON de `minos git-activity`, l'API Java (`GitRepositoryDto`, `GitActivityDto`) et l'onglet Git du plugin IntelliJ voient une valeur de plus **possible** dans la liste de limitations (additif, aucune rupture) ; un dépôt de propriétaire différent devient une erreur actionnable au lieu d'une analyse. MCP, NEXUS : inchangés.
- **ADR** : aucun nouvel ADR, aucun amendement. La règle de confiance (propriétaire, filtres écartés) est consignée dans `docs/developer/multi-repo-git.md`. Si le propriétaire souhaite une décision d'architecture durable sur le modèle de confiance des dépôts Git, ce serait l'ADR 0059 : non requis par ce changement.
- **Gates à rejouer** : `python scripts/remediation/check-post-mne.py` (cite `RemoteRepositoryCachePolicy`, `JGitRemoteRepositoryMaterializer`, `RemoteCloneBudget`, qui ne sont pas touchés ; rejeu de sûreté), `python scripts/architecture/check-module-boundaries.py`, `python scripts/architecture/check-private-io.py`, `python scripts/docs/check-current-docs.py`. JaCoCo : aucun scope ne cible `GitIntelligenceService` ; la couverture du **nouveau code** est jugée par SonarCloud module par module (seuil 80 %) : les tests vivent dans `minos-integration-git`.
- **Plateformes** : Windows et Linux. Le shell des filtres est `cmd.exe /c` sous Windows et `sh -c` sous Linux ; le propriétaire se lit par `Files.getOwner` (SID sous Windows, uid sous Linux) ; le test du témoin n'emploie ni `sh` ni `cmd`, mais `java` (voir `design.md`).
