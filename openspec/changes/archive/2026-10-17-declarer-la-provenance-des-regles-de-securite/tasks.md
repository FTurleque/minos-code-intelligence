# Tasks

Ordre : relevé, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Ce changement et `ne-plus-hacher-les-sources-avant-le-cache-du-graphe` touchent les mêmes goldens : fusionner l'un, mettre à jour la branche de l'autre, puis régénérer.

## 1. Relevé (AUD-SEC-11)

- [x] 1.1 [AUD-SEC-11] Relever comment `minos_program_graph` et `minos_impact_v2` relaient les limitations du graphe (MCP, CLI, API Java) ; lister les tests et goldens qui comparent une liste **exacte** de limitations ; relire les littéraux de `scripts/quality/check-java-ast-provider-consistency.py:70-95` et chercher dans `scripts/` les gates qui citent la description de `minos_security_paths`. Consigner dans `design.md`.

## 2. Limitation de provenance (AUD-SEC-11)

- [x] 2.1 [AUD-SEC-11] Rouge : dans `JavaSourceProgramGraphProviderTest` — projet avec fichier de règles : la limitation `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY` est présente ; fichier de règles sans source : présente ; sans fichier : absente et `JAVA_SECURITY_RULES_NOT_CONFIGURED` présente. Test de `SecurityAnalysisService` : fichier sans source → aucun chemin, limitation relayée. **Preuve du rouge : la limitation n'existe pas au HEAD (`JavaProgramGraphAssembler.java:73-79`).**
- [x] 2.2 [AUD-SEC-11] `JavaProgramGraphAssembler` : émettre la limitation quand `securityRules.configured()` est vrai (D1). Ajouter le littéral à `check-java-ast-provider-consistency.py` dans cette tâche (D4). Preuve : tests verts ; `python scripts/quality/check-java-ast-provider-consistency.py` vert ; `python scripts/quality/check-jacoco.py` (scopes `java-advanced-provider`, `advanced-impact-security`) ; `./mvnw -B -ntp -pl minos-application -am test`.

## 3. Relais par les trois outils (AUD-SEC-11)

- [x] 3.1 [AUD-SEC-11] Rouge : tests des trois outils par le MCP et par la CLI sur la fixture `fixtures/m22/java-advanced-provider/project` — même identifiant de limitation dans les trois réponses ; projet sans fichier de règles : limitation absente. **Preuve du rouge : absente des trois réponses au HEAD.**
- [x] 3.2 [AUD-SEC-11] Compléter le relais là où 1.1 montre qu'il manque (D2), sans nouveau champ. Mettre à jour `java-program-graph.golden` dans cette tâche et vérifier que le diff ne contient que la limitation ajoutée. Preuve : `./mvnw -B -ntp -pl minos-app -am verify` ; `./mvnw -B -ntp -pl minos-mcp -am test`.
- [x] 3.3 [AUD-SEC-11] Vérifier que le plugin IntelliJ tolère la limitation inconnue (tests du plugin par `minos-intellij/gradlew`) ; dire si l'exécution a été faite localement ou seulement en CI. **Non exécuté localement (2026-10-17)** : plateforme IntelliJ 2026.1 absente du cache Gradle hors ligne ; aucun code du plugin ne lit de limitation de graphe de programme (relevé 1.1, tolérance sans objet) ; preuve laissée à la CI (`IntelliJ plugin (gate)`). **Non exécuté localement (2026-10-17)** : plateforme IntelliJ 2026.1 absente du cache Gradle hors ligne ; aucun code du plugin ne lit de limitation de graphe de programme (relevé 1.1) ; preuve laissée à la CI (`IntelliJ plugin (gate)`). Preuve CI : `IntelliJ plugin (gate)` et `IntelliJ 2026.1 / Java 21` verts sur la PR #488 (2026-10-17).

## 4. Assainisseurs : comportement fixé (AUD-SEC-11)

- [x] 4.1 [AUD-SEC-11] Tests de caractérisation de `SecurityAnalysisService` (verts au HEAD, à vérifier) : chemin traversant un assainisseur rapporté avec l'indicateur à vrai ; assainisseur déclaré mais non traversé : chemin rapporté avec l'indicateur à faux. Si l'un est rouge, s'arrêter : l'écart 1 du design est faux et le constat doit être requalifié avant toute correction.

## 5. Clôture

- [x] 5.1 `docs/developer/java-advanced-provider.md` : la nouvelle limitation, sa condition d'émission, et la phrase « les règles viennent du dépôt analysé » ; `docs/user/mcp.md` si les limitations y sont listées. Compléter la « Mise en œuvre » de l'ADR 0028 (décision 9). Rejouer `python scripts/docs/check-current-docs.py`.
- [x] 5.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-SEC-11 : limitation livrée ; impact corrigé — un assainisseur ne masque pas un chemin ; **reste ouvert** : règles d'opérateur hors dépôt et traitement des assainisseurs du dépôt, à porter par un changement ultérieur, avec la contrainte « clé de cache »).
- [x] 5.3 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, `docs/developer/java-advanced-provider.md`, ADR 0028.
