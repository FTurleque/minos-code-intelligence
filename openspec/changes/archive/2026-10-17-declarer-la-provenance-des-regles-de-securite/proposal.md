# Proposal

## Why

**AUD-SEC-11** (faible, confirmé, sprint 7 de l'audit du 10 octobre 2026). Les règles source, puits et assainisseur de l'analyse de sécurité Java viennent **exclusivement du dépôt analysé** : le fichier `.minos/java-advanced-provider.properties` (`JavaSourceProgramGraphProvider.java:23`), lu dans la racine du projet par `JavaSecurityRules.load` (`JavaSecurityRules.java:20-33`). Les réponses ne le disent pas : les deux limitations existantes (`JavaProgramGraphAssembler.java:75` et `:78`) décrivent la portée de l'analyse et l'absence de règles, pas leur provenance, et la description de l'outil MCP annonce des « observed source-to-sink paths and sanitizers » (`MinosMcpTools.java:123`).

Or un dépôt analysé n'est pas de confiance. Un dépôt hostile qui **omet ses sources ou ses puits** fait conclure `minos_security_paths` à l'absence de chemin ; le modèle qui lit la réponse ne peut pas savoir que la conclusion repose sur des règles écrites par le dépôt lui-même.

L'audit surestime un point : un assainisseur déclaré **ne masque pas** un chemin, il est rapporté avec `sanitized=true` (`SecurityAnalysisService.java:105-112`).

## What Changes

- **Une limitation additive** `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY`, émise quand les règles sont lues dans le dépôt analysé, relayée par `minos_program_graph`, `minos_impact_v2` et `minos_security_paths` (MCP, CLI, API Java).
- **Le comportement des assainisseurs est fixé par une exigence** : un chemin assaini reste rapporté, marqué comme tel.
- **Rien d'autre** : aucune règle n'est refusée, aucun fichier n'est lu ailleurs.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `program-graph-et-architecture` : deux exigences ajoutées (provenance des règles de sécurité déclarée par une limitation ; un chemin assaini reste rapporté et marqué).

## Hors périmètre

- **Des règles d'opérateur hors du dépôt** (sous `MINOS_HOME`) qui priment, et **le refus par défaut des assainisseurs déclarés par le dépôt** : seconde moitié de l'action de l'audit. Elle change la sortie de la CLI, de l'API et du MCP, casse une fixture, des tests et un golden, et exige que les règles d'opérateur entrent dans la clé de cache du graphe. Elle relève d'un changement ultérieur, à créer (nom proposé : `lire-les-regles-de-securite-de-l-operateur-hors-du-depot`), avec sa propre décision.
- Le sidecar `.minos/program-graph-v1`, lui aussi fourni par le dépôt : il porte déjà la limitation `ADVANCED_PROGRAM_FACTS_PROVIDER_ASSERTED` (`FileProgramGraphProvider.java:105`).
- La description de l'outil MCP : inchangée (design, D3).
- Les autres constats du sprint 7.

## Impact

- **Modules du reactor** : `minos-application` (`com.minos.program.analysis.JavaProgramGraphAssembler`, lecture de `JavaSecurityRules`) ; `minos-app` (golden `java-program-graph.golden`).
- **Surfaces publiques** : MCP, CLI et API Java — une **limitation ajoutée** dans les trois sorties quand le dépôt fournit un fichier de règles ; aucun champ retiré ni modifié. Le golden `minos-app/src/test/resources/characterization/java-program-graph.golden` change, parce que sa fixture fournit des règles. IntelliJ : tolérance à une limitation inconnue à vérifier. NEXUS : non relevé.
- **ADR** : ADR 0028 complété par une note de mise en œuvre (§ 9, « sources→sink paths and sanitizers »), en tâche ; aucun nouvel ADR.
- **Changement actif voisin** : `ne-plus-hacher-les-sources-avant-le-cache-du-graphe` ajoute lui aussi une limitation aux mêmes trois outils, par le même mécanisme additif ; les deux touchent les mêmes goldens et se fusionnent l'un après l'autre.
- **Gates à rejouer** : `python scripts/quality/check-java-ast-provider-consistency.py` (`:72` et `:91` exigent des littéraux du provider et des limitations ; à compléter du nouveau littéral dans la même tâche), `python scripts/quality/check-jacoco.py` (scopes `java-advanced-provider` et `advanced-impact-security`), `python scripts/docs/check-current-docs.py`.
- **Plateformes** : Windows et Linux, sans différence.
