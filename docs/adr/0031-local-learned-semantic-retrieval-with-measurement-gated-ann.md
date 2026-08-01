# ADR-0031 — Local learned semantic retrieval with measurement-gated ANN

Status: **Accepted for M23 implementation; final promotion remains exact-head gated.**

Date: 2026-07-28

## Context

M20 established an optional semantic layer whose vectors are reconstructible views and whose results remain `HEURISTIC`. The bundled `local-hash` provider deliberately proves plumbing rather than learned semantic quality. M21-S8 then measured the exact linear-scan backend at production-scale STANDARD and concluded `KEEP_CURRENT_M20_BACKEND`; no ANN/vector database was required to satisfy the measured gate.

M23 must therefore improve semantic quality without rewriting history or converting a similarity score into a code fact.

## Decision

1. MINOS adds a learned embedding provider backed by an operator-managed **local Ollama `/api/embed` endpoint**.
2. The built-in learned-provider transport accepts **loopback endpoints only**. It never points to a cloud host, follows no redirect, downloads no model and manages no Ollama credentials.
3. The operator must explicitly configure model identity and dimensions. Provider id + model id + dimensions remain part of semantic-index identity; changing any of them invalidates reuse and forces a safe rebuild.
4. Learned-model promotion is not inferred from successful HTTP calls. M23 contains a controlled corpus and blocking Recall@K/MRR/nDCG gate executed against the model actually configured on the qualification machine.
5. `local-hash` remains available as an opt-in deterministic reference provider and is never relabeled as learned.
6. The file semantic-vector store writes **format v2 with float32 vector components**, while retaining read compatibility with v1 float64 indexes. The next successful synchronize rewrites the index to v2; structured snapshots remain unaffected.
7. Query embeddings are cached only in a bounded in-memory LRU view (256 entries). Cache contents are disposable and never authoritative.
8. Exact cosine linear scan remains the reference retrieval backend. M23 does **not** add HNSW, Lucene, a vector database or another ANN backend because M21-S8 produced `KEEP_CURRENT_M20_BACKEND`. A future ANN change requires a fresh measured bottleneck and a separate decision.
9. Public Java API, MCP and IntelliJ contracts remain additive/compatible. Existing semantic status and search responses already expose provider/model identity and continue to mark semantic results `HEURISTIC`.

## Security and operability

- accepted endpoint hosts: `localhost`, IPv4 loopback (`127.0.0.0/8`) or IPv6 loopback;
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

## Rejected alternatives

### Rename `local-hash` as a production semantic model

Rejected. It is signed feature hashing, not a learned model.

### Automatically download a model

Rejected. It would weaken local-first reproducibility, licensing control and offline operation.

### Permit arbitrary HTTP embedding endpoints

Rejected. The built-in provider is deliberately local-only; remote/cloud providers require a separate explicit contract.

### Add ANN immediately

Rejected. M21-S8 already measured the backend and returned `KEEP_CURRENT_M20_BACKEND`. Architecture does not outrank measurements.
