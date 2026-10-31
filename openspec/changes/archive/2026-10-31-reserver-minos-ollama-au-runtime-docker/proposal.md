# Proposal

## Why

**AUD-SEC-07** (faible, confirmé, sprint 7 de l'audit du 10 octobre 2026). Le fournisseur d'embeddings Ollama accepte l'hôte `minos-ollama` quel que soit l'emplacement d'exécution (`OllamaEmbeddingProvider.java:158`). Ce nom n'a de sens que sur le réseau Docker géré par MINOS. Sur un poste **natif** configuré avec cet endpoint (copie d'une configuration Docker, ou `MINOS_HOME` partagé), il est résolu par le DNS du réseau local, LLMNR ou NBT-NS : une machine du réseau peut y répondre et recevoir, en HTTP clair, le code source envoyé pour l'embedding.

Second point du même constat : le corps d'une réponse d'erreur est lu jusqu'à 16 Mio (`:36`, `:176`) puis recopié tel quel dans le message de l'exception (`:91-94`).

## What Changes

- **`minos-ollama` réservé au runtime Docker** : hors Docker, aucune requête ne part vers ce nom ; le refus est une erreur de configuration actionnable.
- **Moment du refus à trancher** (design, D1) : la recommandation est de refuser **à l'usage** et non à l'ouverture, parce que le script d'installation écrit cet endpoint dans `minos.properties` dès que le backend MCP est Docker et qu'un lanceur natif peut ouvrir le même `MINOS_HOME`.
- **Corps d'erreur borné et assaini** : statut HTTP et extrait court, passé par `PublicErrorMessages`.
- **ADR 0031 à amender** : son § 2 dit « loopback endpoints only » ; l'exception `minos-ollama` n'y figure pas.

## Capabilities

### New Capabilities

(aucune : `recherche-semantique` est créée par le changement actif `lire-l-identite-du-snapshot-actif-sans-le-charger`)

### Modified Capabilities

- `recherche-semantique` : trois exigences ajoutées (service géré joint seulement depuis le runtime Docker ; ouverture native d'un `MINOS_HOME` configuré pour Docker non cassée ; corps d'erreur borné et assaini).

## Hors périmètre

- Chiffrer le transport (HTTPS) vers Ollama : en bouclage et sur le réseau Docker géré, HTTP est le contrat de l'ADR 0031 ; non remis en cause ici.
- Unifier la lecture de l'emplacement d'exécution avec `StrongOwnedProcessExecutors.isDockerRuntimeLocation` (privé dans `minos-provider-scip`, précédence et tolérance différentes) : relevé dans le design, à traiter par un constat d'architecture.
- Changer ce que `scripts/install/configure-runtime-settings.ps1` écrit dans `minos.properties`.
- Les autres constats du sprint 7 ; les changements actifs de `recherche-semantique` (coût de requête, synchronisation, lecture des sources).

## Impact

- **Modules du reactor** : `minos-application` (`com.minos.application.semantic.OllamaEmbeddingProvider`, `com.minos.application.MinosApplicationRuntimeConfiguration`) ; lecture de `com.minos.registry.ProjectPathMapping` (`minos-engine`), sans modification attendue.
- **Surfaces publiques** : CLI, API Java et MCP — la recherche sémantique d'un poste natif configuré sur `minos-ollama` échoue par une erreur de configuration au lieu de tenter une connexion ; message d'erreur HTTP raccourci. Aucun champ ni option nouveaux. IntelliJ et NEXUS : sans changement de protocole.
- **ADR** : **0031 amendé** (§ 2 et « Security and operability »), en tâche.
- **Gates à rejouer** : `python scripts/quality/check-semantic-retrieval-consistency.py` (`:58` exige le littéral « Ollama endpoint must use loopback or the managed Docker service minos-ollama », `:59` exige `MAX_RESPONSE_BYTES`), `python scripts/quality/check-jacoco.py` (scope `semantic-learned-provider`), `python scripts/architecture/check-module-boundaries.py`.
- **Plateformes** : Windows et Linux natifs (refus) ; conteneurs Linux (acceptation). LLMNR et NBT-NS sont propres à Windows, mDNS et le domaine de recherche DNS concernent aussi Linux.
