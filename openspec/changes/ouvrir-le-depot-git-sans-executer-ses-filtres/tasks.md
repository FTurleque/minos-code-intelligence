# Tasks

Ordre dans chaque section : d'abord le test qui échoue (rouge attendu), puis la correction, puis la preuve. Chaque tâche porte l'identifiant du constat qu'elle ferme. Commande de référence : `./mvnw.cmd -pl minos-integration-git -am test` (Windows : voir la remarque d'outillage de `docs/audit/2026-10-10/SUIVI.md`, `powershell.exe` 5.1 dans le `PATH`, arguments `-D…` cités).

## 1. Rouge : prouver l'exécution du filtre (AUD-SEC-01)

- [ ] 1.1 [AUD-SEC-01] Écrire dans `minos-integration-git/src/test/java/com/minos/integration/git/` (nouvelle classe `GitIntelligenceTrustTest`) les scénarios de la spec : dépôt parent piégé (filtre dans `.git/config`, dans un fichier inclus, attribut dans `.gitattributes` et dans `info/attributes`), dépôt livré dans le projet, filtre de l'utilisateur laissé intact (configuration de l'utilisateur redirigée par `user.home`/`XDG_CONFIG_HOME` d'un processus fils, comme `GitEnvironmentProbe`). Le filtre est `java -cp <classes> …$Witness <témoin>` (aucun `sh`, aucun `cmd`) ; l'entrée d'index « smudged » est obtenue en modifiant la date du fichier suivi après le commit. **Preuve du rouge : le témoin est créé** avant correction, sur les quatre configurations ; noter la sortie dans `SUIVI.md`.
- [ ] 1.2 [AUD-SEC-01] Écrire les scénarios de propriétaire (différent, illisible, correct) avec un `Supplier<UserPrincipal>` injecté, et les scénarios de limitations (`REPOSITORY_ABOVE_PROJECT_ROOT` présent/absent, `REPOSITORY_FILTERS_NOT_APPLIED` présent/absent). Vérifier que le test existant `theGitEnvironmentCannotRedirectTheAnalysedRepository` reste vert tel quel.

## 2. Correction (AUD-SEC-01)

- [ ] 2.1 [AUD-SEC-01] Retirer les sections `filter` de la configuration **du dépôt** en mémoire avant `git.status().call()` (décision D3-A), sans `save()` ; si l'inclusion résiste, passer à la sous-classe de `FS` dont `runInShell` refuse (repli D3). Preuve : les quatre configurations de la tâche 1.1 ne créent plus le témoin ; le fichier `.git/config` est inchangé octet pour octet après l'appel (assertion).
- [ ] 2.2 [AUD-SEC-01] Contrôler le propriétaire du répertoire Git trouvé contre celui d'une sonde créée dans le répertoire privé de MINOS (D2), avant d'ouvrir l'index ; messages actionnables et distincts (différent / illisible). Messages sans chemin absolu du dossier personnel de l'utilisateur au-delà de la racine demandée.
- [ ] 2.3 [AUD-SEC-01] Calculer et publier les limitations `REPOSITORY_ABOVE_PROJECT_ROOT` et `REPOSITORY_FILTERS_NOT_APPLIED` (décisions D1-A et D4). Si le propriétaire retient D1-B ou D1-C, adapter cette seule tâche.
- [ ] 2.4 [AUD-SEC-01] Documenter dans `docs/developer/multi-repo-git.md` : la remontée, le contrôle de propriétaire, le retrait des filtres du dépôt, les deux limitations, la limite « compte élevé / non élevé ». Rejouer `python scripts/docs/check-current-docs.py`.

## 3. Rejeu des gates et des surfaces

- [ ] 3.1 [AUD-SEC-01] `./mvnw.cmd -pl minos-integration-git,minos-api,minos-cli -am test` : tests de `minos-api` (`LocalMinosMultiRepositoryApiIntegrationTest`, `MinosMultiRepositoryApiContractTest`) et de la CLI inchangés ; goldens de caractérisation (`A2SurfaceCharacterizationTest`) inchangés (aucune limitation nouvelle n'y apparaît).
- [ ] 3.2 [AUD-SEC-01] Rejouer `python scripts/architecture/check-module-boundaries.py`, `python scripts/architecture/check-private-io.py` (le contrôle de propriétaire ne doit pas ajouter d'E/S brute : utiliser les primitives existantes pour la sonde), `python scripts/remediation/check-post-mne.py`, `python scripts/docs/check-current-docs.py`.
- [ ] 3.3 [AUD-SEC-01] Couverture : le nouveau code est jugé module par module par SonarCloud (seuil 80 % sur le nouveau code) ; tous les tests de ce changement vivent dans `minos-integration-git`. Si le seuil échoue, ajouter des tests unitaires dans ce module, pas ailleurs.

## 4. Preuve sur les deux plateformes

- [ ] 4.1 **(manuelle)** [AUD-SEC-01] Ouvrir la PR ; relever sur `Verify (ubuntu-24.04)` et `Verify (windows-2022)` que `GitIntelligenceTrustTest` s'exécute (0 sauté) et passe. Noter les run ids dans `SUIVI.md`.

## 5. Clôture

- [ ] 5.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` : AUD-SEC-01 (statut, commit, commandes de preuve, valeur de la première exécution rouge, décisions D1 et D3 retenues, écart avec l'action de l'audit sur la remontée).
- [ ] 5.2 `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage (`docs/developer/multi-repo-git.md`, `docs/STATUS.md`).
