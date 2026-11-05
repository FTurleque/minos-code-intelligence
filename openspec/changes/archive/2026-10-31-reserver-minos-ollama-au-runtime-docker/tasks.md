# Tasks

Ordre : relevé, décision, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les exigences de `specs/` décrivent l'option recommandée (D1-A) ; si le propriétaire choisit B, réviser la deuxième exigence par `/opsx:update` avant d'implémenter.

## 1. Relevé et décision (AUD-SEC-07)

- [x] 1.1 [AUD-SEC-07] Tracer l'ouverture native d'un `MINOS_HOME` écrit par `scripts/install/configure-runtime-settings.ps1` avec `McpBackend=docker` : quel lanceur ouvre `MinosApplication`, avec quel `MINOS_RUNTIME_LOCATION`, et ce que fait aujourd'hui `MinosApplicationRuntimeConfiguration.java:53`. Relever tous les lanceurs Docker et vérifier qu'ils posent l'emplacement. Relever comment `MinosRuntimeSettings` fournit l'environnement et les propriétés à `ProjectPathMapping.resolveRuntimeLocation`. Consigner dans `design.md`.
- [x] 1.2 [AUD-SEC-07] **Décision obtenue (17 octobre 2026)** : D1 = A, refus à l'usage. Consignée dans `design.md`.

## 2. `minos-ollama` réservé au runtime Docker (AUD-SEC-07)

- [x] 2.1 [AUD-SEC-07] Rouge : dans `OllamaEmbeddingProviderTest`, endpoint géré + emplacement natif → `embed` échoue par une erreur de configuration sans connexion ; endpoint géré + emplacement Docker → accepté ; test de configuration avec une valeur d'emplacement inconnue → refus. Réécrire le cas `:28-42`, qui encode l'ancien comportement. **Preuve du rouge : au HEAD, l'endpoint géré est accepté sans emplacement et `embed` tente la connexion.**
- [x] 2.2 [AUD-SEC-07] `OllamaEmbeddingProvider` et `MinosApplicationRuntimeConfiguration` : emplacement résolu par `ProjectPathMapping.resolveRuntimeLocation` et passé au fournisseur (D2) ; refus selon D1. Conserver le littéral exigé par `check-semantic-retrieval-consistency.py:58` ou compléter le gate dans cette tâche (D4). Preuve : tests verts ; `python scripts/quality/check-semantic-retrieval-consistency.py` et `python scripts/architecture/check-module-boundaries.py` verts ; `./mvnw -B -ntp -pl minos-application -am test`.
- [x] 2.3 [AUD-SEC-07] Rouge puis correction (option A) : test d'ouverture d'une application sur un `MINOS_HOME` désignant `minos-ollama` en natif — l'ouverture réussit, une commande sans sémantique réussit, le statut de l'index sémantique signale le fournisseur inutilisable. Preuve : `./mvnw -B -ntp -pl minos-app -am verify` (goldens inchangés).

## 3. Corps d'erreur borné et assaini (AUD-SEC-07)

- [x] 3.1 [AUD-SEC-07] Rouge : serveur HTTP local de test répondant 500 avec un corps de 1 Mio, puis avec un corps contenant un chemin absolu ; attendu : statut nommé, message de moins de 600 caractères, chemin absent. **Preuve du rouge : le corps entier figure dans le message au HEAD (`OllamaEmbeddingProvider.java:91-94`).**
- [x] 3.2 [AUD-SEC-07] Lecture bornée du flux d'erreur, extrait d'au plus 512 caractères passé par `PublicErrorMessages.sanitize` (D3) ; `MAX_RESPONSE_BYTES` conservé pour les réponses 2xx. Preuve : tests verts ; gate de cohérence sémantique vert ; `python scripts/quality/check-jacoco.py` (scope `semantic-learned-provider`).

## 4. Décision écrite et preuve réelle (AUD-SEC-07)

- [x] 4.1 [AUD-SEC-07] Amender l'ADR 0031 (§ 2 et « Security and operability » : exception `minos-ollama`, runtime Docker seulement), ou écrire un ADR au prochain numéro libre de `docs/architecture/adr/README.md` si le propriétaire le juge nécessaire (D5). Rejouer `python scripts/docs/check-current-docs.py`.
- [x] 4.2 **(manuelle)** [AUD-SEC-07] Sur une installation Docker réelle avec le profil Ollama : une recherche sémantique aboutit ; sur un poste natif pointant le même `MINOS_HOME` : le refus s'affiche et aucune requête DNS pour `minos-ollama` n'est émise. Noter le résultat dans `SUIVI.md` ; tant que ce n'est pas fait, AUD-SEC-07 reste « implémenté, preuve Docker en attente ». **Preuve (2026-10-31)**, installation 1.3.0 (`8a113943`, Windows 10, Docker Desktop, PostgreSQL et Ollama gérés) : index `typescript-simple` importé par `minos-admin import-scip` (24 symboles), `minos-admin ide semantic-index-sync` → `READY`, 54 documents embarqués par `minos-local-ollama` / `nomic-embed-text` en 2,9 s ; `minos_semantic_search` par le serveur MCP Docker → 3 résultats, `VECTOR_SEARCH_ENGINE=pgvector-exact-cosine`, 56 ms. Lanceur natif (`minos.cmd`, `MINOS_RUNTIME_LOCATION` absent) sur un `MINOS_HOME` de test dont `minos.properties` porte `minos.semantic.endpoint=http://minos-ollama:11434/api/embed` : `import-scip` réussit, `semantic status` porte `OLLAMA_MANAGED_SERVICE_UNREACHABLE_OUTSIDE_DOCKER`, `ide semantic-index-sync` sort en code 1 sur « Ollama configuration error: the semantic endpoint (minos.semantic.endpoint) designates the managed Docker service minos-ollama, which is only reachable from the Docker runtime (MINOS_RUNTIME_LOCATION=docker) » ; `ipconfig /displaydns` : aucune entrée `minos-ollama` avant ni après (le cache du client DNS Windows, LLMNR et NBT-NS non capturés). Écart : le `MINOS_HOME` natif de l'installation elle-même ne s'ouvre pas en natif quand PostgreSQL est géré par Docker (`missing required PostgreSQL setting: MINOS_POSTGRES_URL`), d'où le dossier de test en stockage local.

## 5. Clôture

- [x] 5.1 `docs/user/docker-runtime.md` et `docs/user/troubleshooting.md` : refus hors Docker et remède ; `docs/developer/semantic-retrieval-2.md` si le texte décrit les hôtes acceptés. Rejouer `check-current-docs.py`.
- [x] 5.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-SEC-07 : statut, décision D1, écart sur `isDockerRuntimeLocation`, résultat de 1.1 et 4.2).
- [x] 5.3 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, ADR 0031, `docs/user/docker-runtime.md`.
