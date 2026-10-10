# Tasks

Ordre dans chaque section : d'abord le test ou le gate qui échoue (rouge attendu), puis la correction, puis la preuve. Chaque tâche porte l'identifiant du constat qu'elle ferme. Commande de référence : `./mvnw.cmd -pl minos-runtime-local,minos-provider-scip -am test` (remarque d'outillage Windows : `docs/audit/2026-10-10/SUIVI.md`). Deux lots dépendants : 1 (environnement) est indépendant ; 2 (verrou) suppose la décision D1 ; 3 supprime le sondage.

## 1. Environnement minimal des commandes d'installation (AUD-SEC-02)

- [ ] 1.1 [AUD-SEC-02] **Décisions à obtenir avant d'écrire** : D1 (verrou par jar, option A recommandée), D2 (plan d'indexation Linux hors changement, recommandé), D3 (façade publique dans `minos-runtime-local`). Consigner les réponses dans `design.md`.
- [ ] 1.2 [AUD-SEC-02] Rouge : dans `minos-runtime-local`, tests de la façade (liste d'autorisation, `HOME` conservé sous Linux, proxys et caches conservés, motif de secret retiré même déclaré ou dans la liste, insensibilité à la casse sous Windows, variables `=C:` de Windows conservées). Dans `minos-provider-scip`, trois tests qui lancent par chaque gestionnaire (via la méthode de lancement mutualisée) un petit programme Java imprimant son environnement, avec `MINOS_TEAM_KEY_ALPHA`, `MINOS_TEAM_TOKEN`, `GITHUB_TOKEN`, `NPM_TOKEN` posés dans l'environnement du test (processus fils du test) : **preuve du rouge : les quatre variables sont visibles** dans la sortie avant correction. Même test pour les sondes `dotnet`/`go` (programme de remplacement).
- [ ] 1.3 [AUD-SEC-02] Créer la façade publique (nom proposé `InstallationProcessEnvironment`) dans `minos-runtime-local`, fondée sur `ProviderProcessEnvironment.sanitize` (une seule liste de base) ; l'appliquer dans `ManagedScipProviderRuntimeManager.run` (`:747`), `ManagedScipPythonRuntimeManager.run` (`:251`) et `ManagedPolyglotScipRuntimeManager.run` (`:448-455`, y compris les sondes). Preuve : les tests de 1.2 sont verts ; `python scripts/architecture/check-module-boundaries.py` vert (aucune arête nouvelle : `minos-provider-scip` dépend déjà de `minos-runtime-local`) ; `python scripts/remediation/check-mnd.py` vert (il lit `ManagedScipProviderRuntimeManager.java`).
- [ ] 1.4 [AUD-SEC-02] Couverture : rejouer `python scripts/quality/check-jacoco.py` (scope `provider-execution-trust-boundary`) ; SonarCloud juge le nouveau code module par module : les tests de la façade vivent dans `minos-runtime-local`, ceux des gestionnaires dans `minos-provider-scip`.

## 2. Verrou du classpath de scip-java (AUD-SEC-02)

- [ ] 2.1 [AUD-SEC-02] Mesure préalable : résoudre `org.scip-code:scip-java:0.13.1` par la recette `coursier-fetch` sous Linux (`linux-x64`, Coursier épinglé du catalogue) et comparer la liste de jars et leurs empreintes à celles du zip Windows (`assembled/scip-java-classpath-0.13.1-windows-x64.zip`) ; consigner dans `SUIVI.md` : identique ou différent (verrou unique ou par plateforme).
- [ ] 2.2 [AUD-SEC-02] Rouge : tests du vérificateur de verrou dans `minos-provider-scip` (conforme, jar modifié, jar en trop, jar manquant, verrou absent/illisible, messages sans chemin absolu, aucun `classpath.txt` avant la fin, répertoire partiel supprimé) avec un faux `coursier` ; tests de l'installation (aucun `launch`, aucun `--main`, seule la sous-commande `fetch`).
- [ ] 2.3 [AUD-SEC-02] `scripts/release/build-embedded-tools.py` : la recette `coursier-fetch` écrit le verrou (nom, taille, SHA-256 de chaque jar) à côté du `classpath.txt` ; `embedded-tools.json` déclare le composant « verrou » (version de scip-java, plateforme[s] selon 2.1) ; `scripts/release/sync-tools-manifest.py` et `scripts/quality/check-tools-manifest.py` le connaissent ; cas ajoutés à `test_check_tools_manifest.py` (concordant, version changée sans verrou régénéré, verrou illisible).
- [ ] 2.4 [AUD-SEC-02] Générer le verrou (`build-embedded-tools.py`) et le placer en ressource de `minos-provider-scip` ; preuve : `python scripts/quality/check-tools-manifest.py` vert, et rouge prouvé sur une copie dont la version est changée.
- [ ] 2.5 [AUD-SEC-02] Remplacer le chemin Linux de `installJava` : `coursier fetch --classpath` (jamais `launch`), vérification contre le verrou, puis écriture atomique de `classpath.txt` ; le chemin Windows (classpath livré) n'est pas modifié (assertion de test : aucun appel à Coursier quand `classpath.txt` existe).

## 3. Suppression du sondage de version (AUD-SEC-02)

- [ ] 3.1 [AUD-SEC-02] Supprimer `scipJavaInstallationProbe` et `requireExpectedScipJavaVersion` et leurs tests (`ManagedScipProviderRuntimeManagerTest.java:37`, `:134-140`), remplacés par les tests de 2.2. Rejouer `python scripts/remediation/check-mnd.py` et `python scripts/quality/check-polyglot-provider-consistency.py`.
- [ ] 3.2 [AUD-SEC-02] Rejouer `./mvnw.cmd -pl minos-app -am verify` (goldens de caractérisation inchangés, `ShadedJar*IT` verts) : aucune sortie JSON ni schéma ne change.

## 4. Documentation, ADR et preuve réelle

- [ ] 4.1 **(manuelle)** [AUD-SEC-02] Sous Linux (hôte ou conteneur avec réseau), `minos tools install scip-java` avec `MINOS_TEAM_KEY_TEST` posé dans l'environnement : l'installation réussit, `classpath.txt` correspond au verrou, et un `strace -f -e trace=execve -s 200` (ou `/proc/<pid>/environ` d'un processus fils pendant l'installation) ne montre pas la variable. Puis une installation avec un jar remplacé dans le cache (copie de test) échoue. Noter la sortie dans `SUIVI.md`.
- [ ] 4.2 [AUD-SEC-02] Amender l'ADR 0040 (§ Mise en œuvre, troisième nature de composant : classpath résolu à l'installation, vérifié contre un verrou ; limites : D2, build Docker). Mettre à jour `docs/user/polyglot-providers.md` (résidu D2, miroirs par fichier de configuration, D4) et `docs/user/production-installation.md` si l'installation Linux y est décrite. Rejouer `python scripts/docs/check-current-docs.py`.

## 5. Clôture

- [ ] 5.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` : AUD-SEC-02 (statut, commits, commandes de preuve, décisions D1 à D4, résidu D2, écarts avec l'action de l'audit : liste Windows non réutilisable, sondes et `npm ci` inclus).
- [ ] 5.2 `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage (`docs/STATUS.md`, `docs/user/polyglot-providers.md`, ADR 0040) ; noter comme suite : changement de confinement « plan d'indexation Linux sur classpath vérifié » (D2).
