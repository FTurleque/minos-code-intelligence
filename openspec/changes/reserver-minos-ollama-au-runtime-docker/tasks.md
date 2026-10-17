# Tasks

Ordre : relevé, décision, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les exigences de `specs/` décrivent l'option recommandée (D1-A) ; si le propriétaire choisit B, réviser la deuxième exigence par `/opsx:update` avant d'implémenter.

## 1. Relevé et décision (AUD-SEC-07)

- [ ] 1.1 [AUD-SEC-07] Tracer l'ouverture native d'un `MINOS_HOME` écrit par `scripts/install/configure-runtime-settings.ps1` avec `McpBackend=docker` : quel lanceur ouvre `MinosApplication`, avec quel `MINOS_RUNTIME_LOCATION`, et ce que fait aujourd'hui `MinosApplicationRuntimeConfiguration.java:53`. Relever tous les lanceurs Docker et vérifier qu'ils posent l'emplacement. Relever comment `MinosRuntimeSettings` fournit l'environnement et les propriétés à `ProjectPathMapping.resolveRuntimeLocation`. Consigner dans `design.md`.
- [ ] 1.2 [AUD-SEC-07] **Décision à obtenir** : D1 (A, recommandé : refus à l'usage ; B : refus à l'ouverture). Consigner dans `design.md`.

## 2. `minos-ollama` réservé au runtime Docker (AUD-SEC-07)

- [ ] 2.1 [AUD-SEC-07] Rouge : dans `OllamaEmbeddingProviderTest`, endpoint géré + emplacement natif → `embed` échoue par une erreur de configuration sans connexion ; endpoint géré + emplacement Docker → accepté ; test de configuration avec une valeur d'emplacement inconnue → refus. Réécrire le cas `:28-42`, qui encode l'ancien comportement. **Preuve du rouge : au HEAD, l'endpoint géré est accepté sans emplacement et `embed` tente la connexion.**
- [ ] 2.2 [AUD-SEC-07] `OllamaEmbeddingProvider` et `MinosApplicationRuntimeConfiguration` : emplacement résolu par `ProjectPathMapping.resolveRuntimeLocation` et passé au fournisseur (D2) ; refus selon D1. Conserver le littéral exigé par `check-semantic-retrieval-consistency.py:58` ou compléter le gate dans cette tâche (D4). Preuve : tests verts ; `python scripts/quality/check-semantic-retrieval-consistency.py` et `python scripts/architecture/check-module-boundaries.py` verts ; `./mvnw -B -ntp -pl minos-application -am test`.
- [ ] 2.3 [AUD-SEC-07] Rouge puis correction (option A) : test d'ouverture d'une application sur un `MINOS_HOME` désignant `minos-ollama` en natif — l'ouverture réussit, une commande sans sémantique réussit, le statut de l'index sémantique signale le fournisseur inutilisable. Preuve : `./mvnw -B -ntp -pl minos-app -am verify` (goldens inchangés).

## 3. Corps d'erreur borné et assaini (AUD-SEC-07)

- [ ] 3.1 [AUD-SEC-07] Rouge : serveur HTTP local de test répondant 500 avec un corps de 1 Mio, puis avec un corps contenant un chemin absolu ; attendu : statut nommé, message de moins de 600 caractères, chemin absent. **Preuve du rouge : le corps entier figure dans le message au HEAD (`OllamaEmbeddingProvider.java:91-94`).**
- [ ] 3.2 [AUD-SEC-07] Lecture bornée du flux d'erreur, extrait d'au plus 512 caractères passé par `PublicErrorMessages.sanitize` (D3) ; `MAX_RESPONSE_BYTES` conservé pour les réponses 2xx. Preuve : tests verts ; gate de cohérence sémantique vert ; `python scripts/quality/check-jacoco.py` (scope `semantic-learned-provider`).

## 4. Décision écrite et preuve réelle (AUD-SEC-07)

- [ ] 4.1 [AUD-SEC-07] Amender l'ADR 0031 (§ 2 et « Security and operability » : exception `minos-ollama`, runtime Docker seulement), ou écrire un ADR au prochain numéro libre de `docs/architecture/adr/README.md` si le propriétaire le juge nécessaire (D5). Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 **(manuelle)** [AUD-SEC-07] Sur une installation Docker réelle avec le profil Ollama : une recherche sémantique aboutit ; sur un poste natif pointant le même `MINOS_HOME` : le refus s'affiche et aucune requête DNS pour `minos-ollama` n'est émise. Noter le résultat dans `SUIVI.md` ; tant que ce n'est pas fait, AUD-SEC-07 reste « implémenté, preuve Docker en attente ».

## 5. Clôture

- [ ] 5.1 `docs/user/docker-runtime.md` et `docs/user/troubleshooting.md` : refus hors Docker et remède ; `docs/developer/semantic-retrieval-2.md` si le texte décrit les hôtes acceptés. Rejouer `check-current-docs.py`.
- [ ] 5.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-SEC-07 : statut, décision D1, écart sur `isDockerRuntimeLocation`, résultat de 1.1 et 4.2).
- [ ] 5.3 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, ADR 0031, `docs/user/docker-runtime.md`.
