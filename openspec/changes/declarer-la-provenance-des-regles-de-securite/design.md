# Design

## Context

Sprint 7 de l'audit du 10 octobre 2026 : **AUD-SEC-11**, limité à la déclaration de provenance. HEAD analysé : `949e1569` (`develop`). `PGA` = `minos-application/src/main/java/com/minos/program/analysis`. Principe de référence : capability-honest ; `.claude/rules/securite.md`, point 5 (un dépôt analysé n'est pas de confiance). `.minos/java-advanced-provider.properties` désigne un fichier du **dépôt analysé**, relatif à sa racine.

## État vérifié au HEAD

### AUD-SEC-11 — confirmé, impact à corriger

- `PGA/JavaSourceProgramGraphProvider.java:23` : `SECURITY_CONFIG = ".minos/java-advanced-provider.properties"`.
- `PGA/JavaSecurityRules.java:20-33` : `load(Path root)` lit ce fichier dans la racine du projet par `BoundedProperties.load` ; sans fichier, règles vides et `configured = false` (`:22-24`). Aucune autre source de règles.
- `PGA/JavaProgramGraphAssembler.java:73-79` : `JAVA_SECURITY_FLOW_INTRAPROCEDURAL_CONFIGURED_RULES_ONLY` (`:75`) quand un flux source→puits existe ; `JAVA_SECURITY_RULES_NOT_CONFIGURED` (`:78`) quand aucun fichier n'existe. Rien sur la provenance.
- `PGA/SecurityAnalysisService.java:59` : les limitations de la réponse partent de `graph.limitations()` ; une limitation du graphe est donc relayée par les chemins de sécurité. `:63` : `SECURITY_SOURCE_OR_SINK_ANNOTATIONS_UNAVAILABLE` quand il manque des sources ou des puits.
- `PGA/SecurityAnalysisService.java:105-112` : un nœud assainisseur traversé est ajouté à la liste du chemin, et le chemin est rapporté avec `!sanitizers.isEmpty()` — il **n'est pas retiré**.
- `minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java:123` : description de `minos_security_paths`.
- La clé de cache du graphe inclut déjà l'empreinte du fichier de règles du dépôt (`PGA/FingerprintConstrainedJavaProgramGraphProvider.java:65`).
- Gate : `scripts/quality/check-java-ast-provider-consistency.py:70-74` exige le littéral `SECURITY_CONFIG = ".minos/java-advanced-provider.properties"` (`:72`) ; `:91` exige `JAVA_SECURITY_FLOW_INTRAPROCEDURAL_CONFIGURED_RULES_ONLY`. Ce script n'a pas d'auto-test.
- Tests qui affirment ces limitations : `minos-application/src/test/java/com/minos/program/analysis/JavaSourceProgramGraphProviderTest.java:114` et `:153` (par `contains`). Fixture avec règles : `fixtures/m22/java-advanced-provider/project/.minos/java-advanced-provider.properties`. Golden : `minos-app/src/test/resources/characterization/java-program-graph.golden`. Documentation : `docs/developer/java-advanced-provider.md` (`:120`, `:182`). ADR 0028, décision 9 (`docs/architecture/adr/0028-capability-honest-program-graph-and-bounded-advanced-analysis.md:27`).

**Non vérifié** : aucun build ni test lancé. Non relevé : le relais des limitations du graphe par `minos_program_graph` et `minos_impact_v2` (seul le service de sécurité a été relu) ; les tests qui comparent une liste **exacte** de limitations ; la tolérance du plugin IntelliJ à une limitation inconnue.

### Relevés (tâche 1.1, 2026-10-17, HEAD `6620f2a4`)

- **Relais des limitations du graphe** : `minos_program_graph` rend `graph.limitations()` ; `minos_impact_v2` reprend celles du graphe dans ses limitations avancées ; `minos_security_paths` part de `graph.limitations()` (`SecurityAnalysisService.java:59`). Les trois lignes de `java-program-graph.golden` (premier cycle) portaient déjà `JAVA_SECURITY_FLOW_INTRAPROCEDURAL_CONFIGURED_RULES_ONLY` : le relais est acquis, aucun code de relais n'est à ajouter (tâche 3.2 : golden seul).
- **Tests et goldens à liste exacte de limitations** : aucun test ; seul `java-program-graph.golden` (trois lignes MCP, première série d'appels). Les assertions de `JavaSourceProgramGraphProviderTest` (`:114`, `:153`) sont des `contains`.
- **Gate** : `check-java-ast-provider-consistency.py` exigeait les littéraux de l'implémentation (`:70-95`) ; le nouveau littéral y est ajouté. Aucun gate de `scripts/` ne cite la description de `minos_security_paths`.
- **Plugin IntelliJ** : aucun fichier de `minos-intellij/src/main` ne lit de limitation de graphe de programme ni ne consomme `JAVA_SECURITY_*` ; tolérance à une limitation inconnue sans objet (non rejoué : plateforme IntelliJ absente du cache hors ligne).
- **Rouge (2.1)** : `ProgramGraphSecurityRulesProvenanceTest` — 3 tests sur 6 échouent au HEAD (provenance absente avec règles ; dépôt hostile ; fichier vide) ; le cas sans fichier de règles et les deux cas d'assainisseur (4.1) sont verts au HEAD : l'écart 1 est confirmé (aucune requalification nécessaire).
- **Non mesuré** : la sortie CLI des trois analyses (même rendu que le MCP par `AdvancedAnalysisResultRenderer`, non rejoué commande par commande) ; l'API Java.

### Écarts avec l'action proposée par l'audit

1. **Un assainisseur déclaré ne fait pas disparaître un chemin** (`SecurityAnalysisService.java:105-112`). Le risque réel est l'**omission** de sources ou de puits, pas la déclaration d'assainisseurs.
2. **« Refuser par défaut les assainisseurs du dépôt » change trois surfaces publiques** et casse la fixture, des tests et le golden cités ci-dessus : ce n'est pas un détail d'implémentation, et l'écart 1 en réduit l'intérêt.
3. **Des règles d'opérateur devraient entrer dans la clé de cache** : aujourd'hui seule l'empreinte du fichier du dépôt y figure (`FingerprintConstrainedJavaProgramGraphProvider.java:65`). Sans cela, un changement de règles d'opérateur servirait un graphe périmé.
4. **La limitation seule est additive et suffisante pour être honnête** : elle dit au lecteur sur quoi repose la conclusion. C'est le périmètre retenu.

## Goals / Non-Goals

**Goals** : toute réponse fondée sur des règles écrites par le dépôt analysé le déclare ; aucune sortie existante n'est retirée ni modifiée ; le comportement des assainisseurs est écrit.

**Non-Goals** : règles d'opérateur hors dépôt ; refus des assainisseurs du dépôt ; changement de la clé de cache ; modification du sidecar.

## Decisions

### D1. Une limitation, émise par l'assembleur

`SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY` est ajoutée par `JavaProgramGraphAssembler` quand `securityRules.configured()` est vrai, à côté des deux limitations existantes (`:73-79`). Elle est exclusive de `JAVA_SECURITY_RULES_NOT_CONFIGURED`. Elle est émise que le fichier déclare ou non des sources : un fichier présent mais vide de sources est précisément le cas hostile.

### D2. Relais par les trois outils, sans nouveau champ

La limitation voyage dans la liste `limitations` existante. Pour `minos_security_paths`, le relais est acquis (`SecurityAnalysisService.java:59`). Pour `minos_program_graph` et `minos_impact_v2`, il est relevé en tâche 1.1 ; s'il manque, il est ajouté par le même mécanisme que celui retenu par le changement `ne-plus-hacher-les-sources-avant-le-cache-du-graphe` (D3 de ce changement), pour ne pas créer deux chemins.

### D3. Description de l'outil MCP inchangée

La provenance est portée par chaque réponse et par la documentation. La description (`MinosMcpTools.java:123`) est un texte publié, cité par des gates littéraux ; la modifier n'apporte rien que la limitation ne dise déjà.

### D4. Gate et documentation dans la même tâche

Le nouveau littéral est ajouté à la liste exigée par `check-java-ast-provider-consistency.py`, et `docs/developer/java-advanced-provider.md` le documente, dans la tâche qui l'introduit.

### D5. La suite est nommée, pas faite

Les règles d'opérateur et le traitement des assainisseurs du dépôt sont renvoyés à un changement ultérieur, inscrit dans `SUIVI.md` avec les écarts 2 et 3 comme contraintes d'entrée.

## Direction des dépendances

Changement interne à `minos-application` (`com.minos.program.analysis`). Aucune arête nouvelle.

## Qualification des capacités

- Chemins de sécurité Java avec règles du dépôt : **partielle et déclarée** (bornée, intraprocédurale, et désormais « règles fournies par le dépôt analysé »).
- Chemins de sécurité avec règles d'opérateur : **non supportée**.
- Absence de chemin : jamais une preuve de sûreté (`ABSENCE_OF_PATH_IS_NOT_PROOF_OF_SAFETY`, inchangé).

## Windows et Linux

Aucune différence de comportement.

## Risks / Trade-offs

- Une limitation de plus dans le cas nominal de la fixture : le golden `java-program-graph.golden` change, ainsi que tout test comparant une liste exacte (relevé 1.1).
- Conflit de fusion probable sur les goldens avec `ne-plus-hacher-les-sources-avant-le-cache-du-graphe` : fusionner l'un, mettre à jour la branche de l'autre, régénérer.
- La limitation informe mais n'empêche rien : un lecteur qui l'ignore reste trompé par un dépôt hostile. La protection réelle est le changement ultérieur.
