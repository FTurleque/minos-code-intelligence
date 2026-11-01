# Tasks

Ordre : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Aucun nom existant n'est renommé : les gates littéraux `check-*.py` qui citent `DockerRuntimeBootstrap` (`scripts/quality/check-jacoco.py:244-248`) restent valides ; rejouer `check-private-io.py` (écritures du registre par les primitives), `check-module-boundaries.py` et `check-comment-language.py`.

## 1. Rebasage et isolement (Java)

- [x] 1.1 Rouge : `ProjectPathMappingTest` (`minos-domain`) — racine hôte d'un chemin relatif, rebasage dans une racine qui la contient, absence de rebasage hors racine (Windows insensible à la casse). `ProjectRootReplacementTest` (`minos-storage`) — projet rebasé, projet isolé (`rootAvailable` faux : racine absente, différente de `<nouvelle racine>/<même chemin>` même si ce dossier existe), sauvegarde présente, retour à l'ancienne racine qui rattache, inscription illisible → refus sans écriture, mise à jour d'espace de travail d'un projet isolé qui conserve l'isolement. **Preuve du rouge : les méthodes et la classe n'existent pas au HEAD.**
- [x] 1.2 `ProjectPathMapping` : `hostPathOf(String)` et `rebase(String, ProjectPathMapping)` ; port `ProjectPathMappings.replace` et son rapport (`minos-engine`) ; `ProjectRootReplacement` (`com.minos.storage.local.registry`, sous le bail du registre, ordre de D3) ; lecture et réécriture de `detachedHostRoot` dans `LocalProjectRegistry` ; câblage dans `DefaultMinosApplicationComposer`. Preuve : `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=ProjectRootReplacementTest,ProjectPathMappingTest,LocalProjectRegistry*Test" "-Dsurefire.failIfNoSpecifiedTests=false"`.

## 2. Service et bootstrap

- [x] 2.1 Rouge : `ProjectPathMappingServiceTest` (`replace` : `CREATED`, `UNCHANGED`, `REPLACED` avec comptes, refus PostgreSQL) et `DockerRuntimeBootstrapTest` (`--replace` accepté et rapporté, conflit sans option qui nomme `--replace`, option inconnue → code 2). **Preuve du rouge : `replace` et `--replace` n'existent pas.**
- [x] 2.2 `ProjectPathMappingService.replace`, `Outcome.REPLACED` ; `DockerRuntimeBootstrap` accepte `--replace` et nomme l'option dans le message de conflit. Preuve : `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=ProjectPathMappingServiceTest,DockerRuntimeBootstrapTest,PublicCliDiagnosticTest,DefaultMinosApplicationComposerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`.

## 3. Scripts d'installation

- [x] 3.1 Rouge : `DockerProjectsRootReplacementContractTest` (`minos-app`, `com.minos.packaging`) — sous Windows PowerShell 5.1, extraire `Get-RecordedDockerProjectsRoot` de `switch-mcp-backend.ps1` et `install-windows.ps1` par l'analyseur, exiger deux définitions identiques et vérifier : fichier absent → vide, `hostRoot` lu, fichier sans `hostRoot` → vide ; contrat textuel : `-ReplaceDockerProjectsRoot` dans `SW` et `ZIP`, refus avant `PREPARE` et avant l'extraction, transmission `-ReplaceProjectsRoot` dans `CFG` et `prod-mcp-release.ps1`, `'--replace'` dans `LC`, instantané et restauration des données Docker dans `SW`. **Preuve du rouge : la fonction et les paramètres n'existent pas.**
- [x] 3.2 Implémenter (ASCII, 5.1) ; déclarer la fonction dans `TWINS` de `scripts/quality/check-twin-script-functions.py` et ses comptes dans `test_check_twin_script_functions.py`. Preuve : test vert ; `powershell -File scripts/install/verify-mcp-backend-lifecycle.ps1` ; `python scripts/quality/test_check_twin_script_functions.py`.

## 4. Installeur Inno Setup

- [x] 4.1 Rouge : contrat de `minos-installer.iss.template` dans le même test — lecture de `project-paths.properties`, question de confirmation, `/REPLACEDOCKERPROJECTSROOT`, `-ReplaceDockerProjectsRoot` ajouté aux paramètres du commutateur ; `Require` correspondants dans `scripts/install/verify-installer-template.ps1`. **Preuve du rouge : contrat et vérificateur échouent au HEAD.**
- [x] 4.2 Modifier le gabarit (messages en français, sans `&&`). Preuve : test vert, `powershell -File scripts/install/verify-installer-template.ps1` vert.

## 5. Clôture

- [x] 5.1 `docs/user/docker-runtime.md` (changer de racine des projets, effet sur les projets) et `docs/user/troubleshooting.md` (message de refus et remède). Rejouer `python scripts/docs/check-current-docs.py`.
- [x] 5.2 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; tests ciblés des sections 1 à 4.
- [x] 5.3 **(manuelle)** `scripts/release/build-local-windows-candidate.ps1 -Version 1.3.0 -SkipMavenVerify`, puis, avec le ZIP candidat sur un runtime Docker réel isolé (instance distincte, dossiers temporaires) : installation sur une racine A, projet inscrit, racine B refusée sans confirmation, racine B confirmée par `-ReplaceDockerProjectsRoot` : installation et poignée de main réussies, correspondance remplacée, projet isolé (`rootAvailable: false`, sauvegarde présente).
- [ ] 5.4 **(manuelle)** Parcours de l'assistant de l'installeur candidat sur une installation Docker existante : la page Docker pose la question Oui/Non quand la racine choisie diffère de la racine enregistrée ; Non reste sur la page ; Oui installe.
