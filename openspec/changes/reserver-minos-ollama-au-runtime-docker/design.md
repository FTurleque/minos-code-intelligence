# Design

## Context

Sprint 7 de l'audit du 10 octobre 2026 : **AUD-SEC-07**. HEAD analysé : `949e1569` (`develop`). `OLL` = `minos-application/src/main/java/com/minos/application/semantic/OllamaEmbeddingProvider.java`. Règles de référence : `.claude/rules/securite.md`, points 1 (fail-closed), 7 (sorties) et 8 (réseau). `minos.properties` désigne le fichier de configuration de `MINOS_HOME` à l'exécution, pas un fichier du dépôt.

## État vérifié au HEAD

### AUD-SEC-07 — confirmé

- `OLL:158` : `if (MANAGED_DOCKER_HOST.equals(value)) return true;`, sans condition sur l'emplacement d'exécution. La validation est faite au **constructeur** (`OLL:48`).
- `OLL:102-105` : `http` et `https` acceptés.
- `OLL:91-94` : `throw new IOException("local Ollama embedding request failed with HTTP " + status + … ": " + response)` ; `response` vient de `readBounded` (`OLL:173-180`), borné par `MAX_RESPONSE_BYTES = 16 * 1024 * 1024` (`OLL:36`, `OLL:176`).
- Le fournisseur est construit à la configuration de l'application : `minos-application/src/main/java/com/minos/application/MinosApplicationRuntimeConfiguration.java:53`. Une exception du constructeur y interrompt donc la configuration.
- Emplacement d'exécution : `minos-engine/src/main/java/com/minos/registry/ProjectPathMapping.java:37-45` (`resolveRuntimeLocation` : propriété `minos.runtime.location`, sinon variable `MINOS_RUNTIME_LOCATION`), `:22-29` (valeur vide = `NATIVE`, valeur inconnue = exception). Les services Docker posent `MINOS_RUNTIME_LOCATION: docker` (`docker/compose-mcp.connected.yaml:53`).
- `scripts/install/configure-runtime-settings.ps1:298-299` : avec `McpBackend=docker`, l'endpoint écrit dans `minos.properties` est `http://minos-ollama:11434/api/embed`.
- `minos-application/src/test/java/com/minos/application/semantic/OllamaEmbeddingProviderTest.java:28-42` encode le comportement actuel (l'endpoint géré est accepté sans emplacement).
- ADR 0031 (`docs/adr/0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md:16`) : « loopback endpoints only » ; `:27` ne liste que les hôtes de bouclage.

**Non vérifié** : aucun build ni test lancé. **Non tracé** : le chemin par lequel un lanceur natif ouvre `MinosApplication` sur un `MINOS_HOME` dont `minos.properties` a été écrit pour Docker, et donc l'effet réel d'un refus au constructeur (tâche 1.1). Non relevé : la façon dont `MinosRuntimeSettings` expose l'environnement et les propriétés à passer à `resolveRuntimeLocation`.

### Écarts avec l'action proposée par l'audit

1. **`StrongOwnedProcessExecutors.isDockerRuntimeLocation` n'est pas utilisable.** Il est privé dans `minos-provider-scip` (`minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/StrongOwnedProcessExecutors.java:107-113`) et `application ↛ adaptateurs`. La source à utiliser est `ProjectPathMapping.resolveRuntimeLocation` (`minos-engine`). Les deux lectures diffèrent : la première tolère une valeur inconnue (non-Docker), la seconde la refuse. Le commentaire `:103-105` situe à tort `ProjectPathMapping` dans `minos-application`.
2. **Refuser au constructeur peut casser l'ouverture.** Vu `MinosApplicationRuntimeConfiguration.java:53` et `configure-runtime-settings.ps1:298-299`, un lanceur natif sur un `MINOS_HOME` configuré pour Docker échouerait dès l'ouverture, y compris pour des commandes qui n'utilisent pas la recherche sémantique (D1).
3. **L'ADR 0031 ne prévoit pas `minos-ollama`.** Le code accepte déjà une exception que la décision n'énonce pas : l'ADR est à amender, pas seulement le code.
4. **Tronquer à l'affichage ne suffit pas.** Le corps d'erreur est d'abord lu en mémoire jusqu'à 16 Mio ; la borne doit porter sur la lecture.

## Goals / Non-Goals

**Goals** : aucune requête vers `minos-ollama` hors du runtime Docker ; un `MINOS_HOME` partagé reste ouvrable ; un corps d'erreur ne gonfle ni la mémoire, ni les messages, ni les journaux ; la décision écrite (ADR 0031) dit ce que fait le code.

**Non-Goals** : TLS ; nouveaux hôtes acceptés ; unification des deux lectures de l'emplacement.

## Decisions

### D1. Moment du refus hors Docker — **décision en attente du propriétaire**

**Option A (recommandée) : refuser à l'usage.** Le fournisseur est construit avec l'emplacement résolu ; s'il vise `minos-ollama` hors Docker, chaque `embed` échoue immédiatement, avant toute résolution de nom, par une erreur de configuration qui nomme le paramètre d'endpoint et l'emplacement attendu. L'ouverture de l'application réussit ; les commandes sans recherche sémantique fonctionnent. Fail-closed sur ce qui compte : aucun octet ne sort.

**Option B : refuser à l'ouverture** (constructeur). Plus simple et plus visible, mais rend inutilisable par un lanceur natif tout `MINOS_HOME` configuré pour Docker — à ne retenir que si la tâche 1.1 montre que ce cas n'existe pas.

**Option C : désactiver en silence** le fournisseur hors Docker. Écartée : une capacité configurée qui disparaît sans le dire contredit le principe capability-honest.

### D2. Source unique de l'emplacement

`ProjectPathMapping.resolveRuntimeLocation`, appelé une fois à la configuration et passé au fournisseur. Une valeur inconnue reste une erreur (comportement existant de `RuntimeLocation.parse`). Les constructeurs publics existants sont conservés ; celui qui ne reçoit pas d'emplacement se comporte comme en natif (le plus strict).

### D3. Corps d'erreur

Pour un statut hors 2xx, lecture d'au plus quelques kibioctets du flux d'erreur, puis message « statut HTTP + extrait d'au plus 512 caractères sur une ligne », l'extrait passant par `PublicErrorMessages.sanitize` ; un extrait sensible est omis, le statut reste. `MAX_RESPONSE_BYTES` garde son rôle pour les réponses 2xx (littéral exigé par le gate).

### D4. Littéraux du gate

Le message « Ollama endpoint must use loopback or the managed Docker service minos-ollama » reste celui d'un hôte non reconnu. Le refus hors Docker a un message distinct ; s'il doit être gardé, `check-semantic-retrieval-consistency.py` est complété dans la même tâche.

### D5. ADR 0031

Amendement planifié (tâche 4.1) : § 2 et « Security and operability » énoncent l'exception « service géré `minos-ollama`, runtime Docker seulement ». Si le propriétaire juge qu'il s'agit d'une décision nouvelle, elle prend le prochain numéro libre de `docs/adr/README.md`.

## Direction des dépendances

`minos-application` lit un type de `minos-engine` (`application → engine`, conforme à l'ADR 0022). Aucune dépendance vers `minos-provider-scip`.

## Qualification des capacités

- Fournisseur Ollama en bouclage : qualifié, inchangé.
- Fournisseur Ollama vers `minos-ollama` dans Docker : inchangé ; **non rejoué** par ce changement en dehors des tests unitaires (la qualification Docker réelle est une tâche manuelle).
- Fournisseur Ollama vers `minos-ollama` hors Docker : **non supporté**, refusé et déclaré.

## Windows et Linux

Le refus est identique sur les deux. Le risque de résolution d'un nom à un seul composant est plus large sous Windows (LLMNR, NBT-NS) ; sous Linux il passe par le domaine de recherche DNS ou mDNS. Les tests n'ouvrent aucune connexion réseau réelle vers `minos-ollama`.

## Risks / Trade-offs

- Option A : l'erreur n'apparaît qu'au premier usage sémantique ; le statut de l'index sémantique doit la rendre visible (tâche 2.3).
- Un déploiement Docker qui n'exporterait pas `MINOS_RUNTIME_LOCATION` perdrait la recherche sémantique : les services relus le posent (`docker/compose-mcp.connected.yaml:53,111,159,263`, `docker/compose-mcp.prod.yaml:37,77,124,256`) ; à vérifier pour tout autre lanceur (tâche 1.1).
- `OllamaEmbeddingProviderTest` change dans la même tâche que le code ; le scope `semantic-learned-provider` a un seuil bas (0,52 / 0,32) mais SonarCloud juge le code neuf à 80 %.
