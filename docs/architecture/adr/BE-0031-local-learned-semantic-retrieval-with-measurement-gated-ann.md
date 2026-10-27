---
id: BE-0031
title: "Local learned semantic retrieval with measurement-gated ANN"
domain: backend
rules: false
---

# Local learned semantic retrieval with measurement-gated ANN

Status: **Accepted for M23 implementation; final promotion remains exact-head gated.**

Date: 2026-07-28

## Context

M20 established an optional semantic layer whose vectors are reconstructible views and whose results remain `HEURISTIC`. The bundled `local-hash` provider deliberately proves plumbing rather than learned semantic quality. M21-S8 then measured the exact linear-scan backend at production-scale STANDARD and concluded `KEEP_CURRENT_M20_BACKEND`; no ANN/vector database was required to satisfy the measured gate.

M23 must therefore improve semantic quality without rewriting history or converting a similarity score into a code fact.

## Decision

1. MINOS adds a learned embedding provider backed by an operator-managed **local Ollama `/api/embed` endpoint**.
2. The built-in learned-provider transport accepts **loopback endpoints only**. It never points to a cloud host, follows no redirect, downloads no model and manages no Ollama credentials. *(Précisé en octobre 2026 : le code accepte aussi `minos-ollama`, depuis le runtime Docker seulement ; voir « Mise en œuvre ».)*
3. The operator must explicitly configure model identity and dimensions. Provider id + model id + dimensions remain part of semantic-index identity; changing any of them invalidates reuse and forces a safe rebuild.
4. Learned-model promotion is not inferred from successful HTTP calls. M23 contains a controlled corpus and blocking Recall@K/MRR/nDCG gate executed against the model actually configured on the qualification machine.
5. `local-hash` remains available as an opt-in deterministic reference provider and is never relabeled as learned.
6. The file semantic-vector store writes **format v2 with float32 vector components**, while retaining read compatibility with v1 float64 indexes. The next successful synchronize rewrites the index to v2; structured snapshots remain unaffected.
7. Query embeddings are cached only in a bounded in-memory LRU view (256 entries). Cache contents are disposable and never authoritative.
8. Exact cosine linear scan remains the reference retrieval backend. M23 does **not** add HNSW, Lucene, a vector database or another ANN backend because M21-S8 produced `KEEP_CURRENT_M20_BACKEND`. A future ANN change requires a fresh measured bottleneck and a separate decision.
9. Public Java API, MCP and IntelliJ contracts remain additive/compatible. Existing semantic status and search responses already expose provider/model identity and continue to mark semantic results `HEURISTIC`.

## Do's and Don'ts

### Do

- Adosser le provider learned à un endpoint Ollama local `/api/embed` géré par l’opérateur.
- Exiger une configuration explicite du modèle et des dimensions, qui font partie de l’identité de l’index sémantique.
- Écrire le format v2 float32 et garder la lecture des index v1 float64.
- Garder le scan linéaire cosinus exact comme backend de référence.
- Faire échouer chaque `embed` vers `minos-ollama` hors du runtime Docker, avant toute résolution de nom, par une erreur qui nomme `minos.semantic.endpoint`.

### Don't

- Ne pas pointer vers un hôte cloud, suivre une redirection, télécharger un modèle ni gérer d’identifiants Ollama.
- Ne pas déduire la promotion d’un modèle du succès des appels HTTP.
- Ne jamais présenter `local-hash` comme un modèle appris.
- Ne pas ajouter d’ANN (HNSW, Lucene, base vectorielle) sans nouveau goulot mesuré ni décision séparée.
- Ne jamais lire l’emplacement d’exécution dans `minos.properties`.

## Security and operability

- accepted endpoint hosts: `localhost`, IPv4 loopback (`127.0.0.0/8`) or IPv6 loopback; plus `minos-ollama`, the managed Docker service, joined only from the Docker runtime (see « Mise en œuvre »);
- request redirects are disabled;
- response size and timeouts are bounded;
- missing model/dimensions are configuration errors, not implicit defaults;
- no model is pulled automatically by MINOS;
- the blocking quality gate fails when the local model is unavailable.

## Consequences

### Positive

- semantic quality can come from a real learned local model without introducing a mandatory cloud dependency;
- model quality becomes measured rather than claimed;
- float32 halves raw vector component storage compared with v1 float64;
- repeated queries avoid redundant embedding work within a bounded process-local cache;
- the proven M21-S8 exact backend remains stable.

### Trade-offs

- operators who enable M23 learned embeddings must install/run Ollama and select a model themselves;
- quality and dimensions are model-specific, so a model that fails the controlled gate cannot be used as M23 promotion evidence;
- float32 introduces bounded quantization relative to the in-memory double representation;
- exact scan remains O(n) until measurements justify another index.

## Compliance and Enforcement

- Gate `scripts/quality/check-semantic-retrieval-consistency.py` — contrat sémantique, guide et cet ADR (`loopback`, `float32`, `256`, `KEEP_CURRENT_M20_BACKEND`) ; lancé seulement par `scripts/m23/run-final.ps1` et `scripts/m24/run-final.ps1` (aucun workflow).
- Tests `OllamaEmbeddingProviderTest` et `OllamaEmbeddingProviderRuntimeLocationTest` (`minos-application`, lancés par `mvnw verify`) — bouclage et `minos-ollama` acceptés, hôtes distants refusés ; `minos-ollama` hors Docker refusé à l’usage avant toute résolution de nom.
- Test `FileSemanticVectorStoreTest` (`minos-storage`) — écriture v2 float32, lecture v1 et migration au remplacement suivant.
- Revue : aucun hôte accepté en plus du bouclage et de `minos-ollama` ; aucun backend ANN sans mesure.

## Rejected alternatives

### Rename `local-hash` as a production semantic model

Rejected. It is signed feature hashing, not a learned model.

### Automatically download a model

Rejected. It would weaken local-first reproducibility, licensing control and offline operation.

### Permit arbitrary HTTP embedding endpoints

Rejected. The built-in provider is deliberately local-only; remote/cloud providers require a separate explicit contract.

### Add ANN immediately

Rejected. M21-S8 already measured the backend and returned `KEEP_CURRENT_M20_BACKEND`. Architecture does not outrank measurements.

## Mise en œuvre (AUD-SEC-07, octobre 2026)

Ajout, sans rien retirer à la décision ci-dessus : le § 2 (« loopback endpoints only ») et la liste « accepted endpoint hosts » ne disaient pas que le code accepte aussi le nom `minos-ollama`, le service du réseau Docker géré par MINOS. Cet écart entre la décision et le code est levé ici, et la borne est resserrée.

- **Exception énoncée** : `minos-ollama` est le seul hôte non bouclé accepté, et **seulement depuis le runtime Docker** (`MINOS_RUNTIME_LOCATION=docker`, posé par les images et les services de `docker/compose-mcp.*.yaml`). Ce nom à un seul composant est, hors du réseau Docker, résolu par le domaine de recherche DNS, LLMNR, NBT-NS ou mDNS : une machine du réseau qui y répond recevrait en HTTP clair le code envoyé pour l'embedding.
- **Refus à l'usage, pas à l'ouverture** (décision du propriétaire, changement `reserver-minos-ollama-au-runtime-docker`) : `scripts/install/configure-runtime-settings.ps1` écrit cet endpoint dans `minos.properties` dès que le backend MCP est Docker, et un lanceur natif peut ouvrir le même `MINOS_HOME`. Le fournisseur est donc construit, l'ouverture réussit, mais chaque `embed` échoue avant toute résolution de nom par une erreur de configuration qui nomme le paramètre `minos.semantic.endpoint` et l'emplacement attendu, et le fournisseur déclare la limite `OLLAMA_MANAGED_SERVICE_UNREACHABLE_OUTSIDE_DOCKER` aux statuts et rapports sémantiques. Aucun autre endpoint n'est essayé à la place.
- **Source de l'emplacement** : `MinosRuntimeSettings.runtimeLocation()`, qui lit la propriété système `minos.runtime.location` puis `MINOS_RUNTIME_LOCATION` comme `ProjectPathMapping.resolveRuntimeLocation` pour les registres, **jamais** le fichier `minos.properties` (partagé par tous les lanceurs d'un même `MINOS_HOME`). Une valeur inconnue est refusée en nommant la valeur.
- **Corps d'erreur** : pour un statut hors 2xx, au plus 4 Kio sont lus, 512 caractères au plus sont retenus sur une ligne et passent par `PublicErrorMessages.sanitize` ; un extrait sensible (chemin, justificatif) est omis, le statut reste. La limite de 16 Mio ne vaut plus que pour les réponses valides.
- **Inchangé** : HTTP en clair vers Ollama (le contrat reste le bouclage et le réseau Docker interne), pas de redirection, aucun modèle téléchargé.
- **Limite** : la qualification sur une installation Docker réelle avec le profil Ollama, et l'absence de requête DNS pour `minos-ollama` depuis un poste natif, n'ont pas été rejouées (tâche manuelle 4.2 du changement).

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- Le § 2 dit « loopback endpoints only » ; le code accepte aussi `minos-ollama`, depuis le runtime Docker seulement (voir « Mise en œuvre (AUD-SEC-07, octobre 2026) ») — preuve : `minos-application/src/main/java/com/minos/application/semantic/OllamaEmbeddingProvider.java:41,43,144`.
- Le cache des embeddings de requête est borné aussi en octets (8 Mio), ce que le § 7 ne dit pas — preuve : `minos-application/src/main/java/com/minos/application/semantic/SemanticSearchService.java:22`.
