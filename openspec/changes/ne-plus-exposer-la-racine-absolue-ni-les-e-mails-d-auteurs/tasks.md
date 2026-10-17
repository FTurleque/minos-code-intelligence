# Tasks

Deux lots indépendants : 1-2 (racine absolue par le MCP) et 3-4 (e-mail des auteurs). Dans chaque lot : relevé et décision, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les exigences de `specs/` décrivent les options recommandées (D1-A, D2 recommandé) ; si le propriétaire en choisit une autre, les réviser par `/opsx:update` avant d'implémenter.

## 1. Racine du projet par le MCP : relevé et décision (AUD-SEC-10)

- [x] 1.1 [AUD-SEC-10] Relever toutes les réponses MCP qui portent un chemin absolu (occurrences de `<REPO>` dans `mcp.golden` et `mcp-all-tools.golden`) ; relever si la réponse de `minos_project_structure` porte un numéro de version de contrat ; examiner la variante « racine relative au mapping hôte/conteneur » (`ProjectPathMapping`) ; relever si NEXUS lit `rootPath` par le MCP. Consigner dans `design.md`.
- [x] 1.2 [AUD-SEC-10] **Décision à obtenir** : D1 (A, recommandé : clé conservée, valeur non absolue ; B : clé retirée avec version de contrat ; C : statu quo documenté). Consigner dans `design.md`.

## 2. Racine du projet par le MCP : correction (AUD-SEC-10)

- [x] 2.1 [AUD-SEC-10] Rouge : dans `minos-mcp`, test de `MinosApplicationMcpBackend.projectStructure` sur un projet de racine temporaire — la réponse ne contient pas le chemin du répertoire temporaire, `rootPath` est présent et non absolu, `rootAvailable` inchangé ; cas racine disparue ; cas racine de lecteur et UNC (Windows). Test de non-régression : la fiche projet de la CLI garde le chemin absolu (`ProjectJsonTest`). **Preuve du rouge : le chemin absolu figure dans la réponse au HEAD (`MinosApplicationMcpBackend.java:88`).**
- [x] 2.2 [AUD-SEC-10] `MinosApplicationMcpBackend.projectStructure` : valeur remplacée après `ProjectJson.project` (D4) ; `ProjectJson` non modifié. Mettre à jour `mcp.golden` et `mcp-all-tools.golden` dans cette tâche. Preuve : tests verts ; `python scripts/quality/check-jacoco.py` (scope `mcp-mapping`) ; `./mvnw -B -ntp -pl minos-app -am verify` ; `cli-json.golden` inchangé.
- [ ] 2.3 [AUD-SEC-10] Vérifier que le plugin IntelliJ n'est pas touché : tests du plugin par `minos-intellij/gradlew` (`test`), en particulier la résolution par `rootPath`. Dire si l'exécution a été faite localement ou seulement en CI. **Non exécuté localement (2026-10-17)** : `gradlew test --offline` échoue faute de la plateforme IntelliJ 2026.1 en cache (`Could not download idea-2026.1.jar`) ; aucun fichier du plugin n'est modifié et la sortie CLI lue par le plugin (`cli-json.golden`, `ProjectJsonTest`) est inchangée ; la preuve reste à la CI (`IntelliJ plugin (gate)`).

## 3. E-mail des auteurs : relevé et décision (AUD-SEC-10)

- [x] 3.1 [AUD-SEC-10] Relever : le format texte de `git-activity` affiche-t-il l'adresse ; `api.golden` en contient-il une ; quels types de `MinosMultiRepositoryApi` et quelle requête porteraient le paramètre additif ; confirmer qu'aucun outil MCP ne rend l'adresse. Consigner dans `design.md`.
- [x] 3.2 [AUD-SEC-10] **Décision à obtenir** : D2 (masqué par défaut avec option d'affichage, recommandé ; ou défaut inchangé avec option de masquage), valeur du masque, nom de l'option CLI et du paramètre de l'API. Consigner dans `design.md`.

## 4. E-mail des auteurs : correction (AUD-SEC-10)

- [x] 4.1 [AUD-SEC-10] Rouge : `GitIntelligenceServiceTest` — sans demande, `CommitActivity.authorEmail` vaut le masque ; avec demande, l'adresse ; deux auteurs de même nom et d'adresses différentes comptent pour 2 sans demande. `GitActivityCommandTest` — JSON par défaut sans adresse, avec l'option : adresse. Test de l'API Java dans `minos-api` — `GitCommitDto.authorEmail()` masqué par défaut. **Preuve du rouge : l'adresse sort au HEAD (`GitActivityCommand.java:136`, `LocalMinosMultiRepositoryApi.java:268`).**
- [x] 4.2 [AUD-SEC-10] Attribut additif de la requête Git dans `com.minos.git.GitIntelligence` ; `GitIntelligenceService` ne renseigne l'adresse que sur demande (D3) ; option de `GitActivityCommand` et paramètre additif de l'API, signatures existantes conservées. Mettre à jour `cli-git.golden` (4 valeurs) dans cette tâche. Preuve : tests verts ; `python scripts/architecture/check-module-boundaries.py` vert ; `python scripts/quality/check-jacoco.py` (scope `public-api`) ; `./mvnw -B -ntp -pl minos-app -am verify`.
- [x] 4.3 [AUD-SEC-10] Garde MCP : test sur le catalogue — aucune réponse d'outil ne contient l'adresse de test. Preuve : `./mvnw -B -ntp -pl minos-mcp -am test`.

## 5. Décisions écrites

- [x] 5.1 [AUD-SEC-10] Amender l'ADR 0019 (« Mise en œuvre » : adresse rendue sur demande seulement) ; si D1-B, écrire où la version du contrat MCP est publiée (ADR 0017 complété ou ADR au prochain numéro libre de `docs/architecture/adr/README.md`) (D5). Rejouer `python scripts/docs/check-current-docs.py`.
- [x] 5.2 [AUD-SEC-10] Ouvrir dans `docs/project/audits/2026-10-10/SUIVI.md` une ligne de suite pour `workTree` et `originRemote` (exposition de même nature, hors périmètre ; rédaction de l'URL non vérifiée).

## 6. Clôture

- [x] 6.1 `docs/user/mcp.md` (valeur de `rootPath`), `docs/user/cli.md` (option de `git-activity`, valeur par défaut), `docs/user/java-api.md` (paramètre et valeur par défaut). Rejouer `check-current-docs.py` et `python scripts/docs/product-facts.py --check`.
- [x] 6.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-SEC-10 : statut par lot, décisions D1 et D2, écarts « relatif à MINOS_HOME » et « hacher »).
- [ ] 6.3 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, ADR 0019, `docs/user/mcp.md`, `docs/user/cli.md`, `docs/user/java-api.md`.
