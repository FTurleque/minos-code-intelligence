#!/usr/bin/env python3
"""Static fail-closed consistency gate for M23 Semantic Retrieval 2.0."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import literal_gate  # noqa: E402 - after the script directory is added to the path

ROOT = Path(__file__).resolve().parents[2]
QUALITY_FIXTURE = "fixtures/m23/semantic-quality-v1.json"


def _edit_fixture(change):
    """A mutation that applies ``change`` to the parsed quality fixture and writes it back as JSON."""
    def mutate(text: str) -> str:
        fixture = json.loads(text)
        change(fixture)
        return json.dumps(fixture, indent=2)
    return mutate


def _lower(metric: str):
    return _edit_fixture(lambda fixture: fixture["thresholds"].__setitem__(metric, 0.1))


# Structured rules on the parsed quality fixture: one mutation each (one per threshold).
STRUCTURED_MUTATIONS = {
    "quality fixture version and topK": [(QUALITY_FIXTURE, _edit_fixture(lambda fixture: fixture.__setitem__("topK", 4)))],
    "quality fixture size": [(QUALITY_FIXTURE, _edit_fixture(
        lambda fixture: fixture.__setitem__("documents", fixture["documents"][:9])))],
    "quality thresholds": [(QUALITY_FIXTURE, _lower(metric)) for metric in ("recallAtK", "mrr", "ndcgAtK")],
}


def read(relative: str) -> str:
    return literal_gate.read(relative)


def normalize_presentation(value: str) -> str:
    value = value.replace("\u00a0", " ")
    value = re.sub(r"[`*]+", "", value)
    return re.sub(r"\s+", " ", value).strip().casefold()


def require(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        literal_gate.require(relative, text, needle, f"{relative}: missing required contract text: {needle}",
                             normalize_presentation)


def forbid(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        literal_gate.forbid(relative, text, needle, f"{relative}: forbidden M23 regression text: {needle}",
                            normalize_presentation)


def require_pattern(relative: str, text: str, pattern: str, label: str) -> None:
    literal_gate.require_pattern(relative, text, pattern, f"{relative}: missing required contract fact: {label}")


def evaluate() -> str:
    """Assert the facts of the gate; return its success message."""
    provider_path = "minos-application/src/main/java/com/minos/application/semantic/OllamaEmbeddingProvider.java"
    provider = read(provider_path)
    require(
        provider_path,
        provider,
        'return "minos-local-ollama"',
        "DEFAULT_ENDPOINT",
        "127.0.0.1:11434/api/embed",
        "openConnection(Proxy.NO_PROXY)",
        "setInstanceFollowRedirects(false)",
        "Ollama endpoint must use loopback or the managed Docker service minos-ollama",
        "MAX_RESPONSE_BYTES",
        "parseEmbeddingResponse",
        "SemanticVector.fromArray",
    )
    forbid(provider_path, provider, "https://ollama.com", "api/pull")

    app_path = "minos-application/src/main/java/com/minos/application/MinosApplication.java"
    app = read(app_path)
    runtime_config_path = (
        "minos-application/src/main/java/com/minos/application/"
        "MinosApplicationRuntimeConfiguration.java"
    )
    runtime_config = read(runtime_config_path)
    require(
        app_path,
        app,
        'SEMANTIC_PROVIDER_ENV = "MINOS_SEMANTIC_PROVIDER"',
        'SEMANTIC_MODEL_ENV = "MINOS_SEMANTIC_MODEL"',
        'SEMANTIC_DIMENSIONS_ENV = "MINOS_SEMANTIC_DIMENSIONS"',
        'SEMANTIC_ENDPOINT_ENV = "MINOS_SEMANTIC_ENDPOINT"',
        "MinosApplicationRuntimeConfiguration.apply(settings, builder)",
    )
    require(
        runtime_config_path,
        runtime_config,
        '"ollama".equals(provider)',
        "builder.resolvedComposer().embeddingProvider(learned)",
        '"local-hash".equals(provider)',
        '"disabled".equals(provider)',
    )
    forbid(runtime_config_path, runtime_config, "OllamaEmbeddingProvider")
    # ADR 0087: the composition root instantiates the learned provider.
    composer_path = "minos-bootstrap/src/main/java/com/minos/bootstrap/DefaultMinosApplicationComposer.java"
    require(composer_path, read(composer_path), "new OllamaEmbeddingProvider(")

    store_path = "minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java"
    store = read(store_path)
    require(
        store_path,
        store,
        "LEGACY_FORMAT_VERSION = 1",
        "FORMAT_VERSION = 2",
        'LEGACY_FILE = "index-v1.bin"',
        'CURRENT_FILE = "index-v2.bin"',
        "input.readDouble() : input.readFloat()",
        "float compact = (float) indexed.vector().valueAt(d)",
        "Float.isFinite(compact)",
        "output.writeFloat",
        "Files.deleteIfExists(directory.resolve(LEGACY_FILE))",
        "removeCached(snapshot.projectId(), false)",
    )
    forbid(store_path, store, "output.writeDouble(indexed.vector()")

    store_test_path = "minos-storage/src/test/java/com/minos/storage/local/store/FileSemanticVectorStoreTest.java"
    store_test = read(store_test_path)
    require(
        store_test_path,
        store_test,
        "cachedV2SnapshotUsesExactlyThePersistedFloat32Values",
        "(double) (float) sourceValue",
        "assertEquals(cached.documents(), reopened.documents())",
        "readsLegacyV1AndMigratesOnNextReplace",
    )

    search_path = "minos-application/src/main/java/com/minos/application/semantic/SemanticSearchService.java"
    search = read(search_path)
    require(
        search_path,
        search,
        "MAX_QUERY_CACHE_ENTRIES = 256",
        "MAX_QUERY_CACHE_WEIGHT_BYTES = 8L * 1024L * 1024L",
        "QueryCacheKey",
        "VECTOR_SEARCH_LINEAR_SCAN",
        "vectorStore.searchEngine()",
        "SEMANTIC_QUERY_VECTOR_CACHE_WEIGHTED_8_MIB",
    )
    forbid(search_path, search, "HNSW", "approximateNearest")

    # A2: provider limitations are carried by the EmbeddingProvider port; SemanticIndexService
    # appends provider.limitations() and never branches on a concrete provider class.
    index_path = "minos-application/src/main/java/com/minos/application/semantic/SemanticIndexService.java"
    index = read(index_path)
    require(index_path, index, "provider.limitations()")
    forbid(index_path, index, "instanceof LocalHashEmbeddingProvider", "instanceof OllamaEmbeddingProvider")
    port_path = "minos-application/src/main/java/com/minos/application/semantic/EmbeddingProvider.java"
    require(port_path, read(port_path), "default List<String> limitations()")
    local_hash_path = "minos-application/src/main/java/com/minos/application/semantic/LocalHashEmbeddingProvider.java"
    require(local_hash_path, read(local_hash_path), "LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL")
    require(
        provider_path,
        provider,
        "LEARNED_MODEL_QUALITY_IS_CONFIGURATION_SPECIFIC",
        "SEMANTIC_RESULTS_REMAIN_HEURISTIC",
    )

    fixture = json.loads(read(QUALITY_FIXTURE))
    literal_gate.check("quality fixture version and topK", fixture.get("version") == 1 and fixture.get("topK") == 3,
                       "M23 quality fixture version/topK mismatch")
    literal_gate.check("quality fixture size",
                       len(fixture.get("documents", [])) >= 10 and len(fixture.get("queries", [])) >= 8,
                       "M23 quality fixture is too small")
    thresholds = fixture.get("thresholds", {})
    required_thresholds = {"recallAtK": 0.75, "mrr": 0.70, "ndcgAtK": 0.72}
    for metric, minimum in required_thresholds.items():
        actual = float(thresholds.get(metric, -1.0))
        literal_gate.check("quality thresholds", actual >= minimum,
                           f"M23 quality threshold lowered: {metric}={actual} < {minimum}")

    quality_path = "scripts/m23/evaluate-learned-quality.py"
    quality = read(quality_path)
    require(
        quality_path,
        quality,
        'require_env("MINOS_SEMANTIC_MODEL")',
        'require_env("MINOS_SEMANTIC_DIMENSIONS")',
        "require_loopback_endpoint",
        "ProxyHandler({})",
        "NoRedirect",
        "M23 LEARNED SEMANTIC QUALITY SUCCESS",
        "M23 LEARNED SEMANTIC QUALITY FAILED",
        "lexicalBaseline",
    )

    jacoco_path = "scripts/quality/check-jacoco.py"
    jacoco = read(jacoco_path)
    require(
        jacoco_path,
        jacoco,
        '"semantic-learned-provider"',
        '"com/minos/application/semantic/OllamaEmbeddingProvider"',
        '"semantic-vector-store"',
        '"semantic-hybrid-retrieval"',
    )

    roadmap_path = "docs/project/roadmap/M23_EXECUTION.md"
    roadmap = read(roadmap_path)
    require_pattern(roadmap_path, roadmap, r"(?im)^\s*Statut\b[^\n]*9\s*/\s*9\b", "M23 9/9 completion")
    require_pattern(roadmap_path, roadmap, r"(?im)^\s*Issue\b[^\n#]*#\s*78\b[^\n]*\bCLOSED\b[^\n]*\bcompleted\b", "issue #78 closed/completed")
    require_pattern(roadmap_path, roadmap, r"(?im)^\s*PR\b[^\n#]*#\s*79\b[^\n]*\bMERGED\b", "PR #79 merged")
    require_pattern(roadmap_path, roadmap, r"(?im)^\s*-\s*le transport learned[^\n]*contourne[^\n]*proxies système", "learned transport bypasses system proxies")
    require(
        roadmap_path,
        roadmap,
        "7a5fe2b96480a21e063b8ffa537009e5bdf99bc0",
        "ffe12d95ac46c25026661dca51949fb0d39626b4",
        "ADR-0031",
        "Recall@3 >= 0.75",
        "KEEP_CURRENT_M20_BACKEND",
        "model      embeddinggemma",
        "dimensions 768",
        "Proxy.NO_PROXY",
        "M23 FINAL SEMANTIC RETRIEVAL 2.0 VALIDATION SUCCESS",
        "M21-S2/CI reste en pause jusqu’en août 2026",
    )

    adr_path = "docs/architecture/adr/BE-0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md"
    adr = read(adr_path)
    require(
        adr_path,
        adr,
        "loopback",
        "float32",
        "256",
        "KEEP_CURRENT_M20_BACKEND",
        "does **not** add HNSW",
    )

    guide_path = "docs/developer/semantic-retrieval-2.md"
    guide = read(guide_path)
    require(
        guide_path,
        guide,
        "MINOS_SEMANTIC_PROVIDER='ollama'",
        "MINOS_SEMANTIC_MODEL",
        "index-v2.bin",
        "Recall@3 >= 0.75",
        "VECTOR_SEARCH_LINEAR_SCAN",
    )

    runner_path = "scripts/m23/run-final.ps1"
    runner = read(runner_path)
    require(
        runner_path,
        runner,
        "check-semantic-retrieval-consistency.py",
        "evaluate-learned-quality.py",
        "run-s5.ps1",
        "0.2.0-m23",
        "run-s6.ps1",
        "$RequiredSemanticProvider = 'ollama'",
        "$RequiredSemanticModel = 'embeddinggemma'",
        "$RequiredSemanticDimensions = '768'",
        "$RequiredSemanticEndpoint = 'http://127.0.0.1:11434/api/embed'",
        "$SemanticEnvironmentNames = @(",
        "Invoke-WithSemanticDisabled",
        "Remove-Item -Path $Path",
        "Set-Item -Path $Path -Value $Saved[$Name]",
        "M23 FINAL SEMANTIC RETRIEVAL 2.0 VALIDATION SUCCESS",
    )
    for isolated_script in ("run-local.ps1", "run-s5.ps1", "run-s6.ps1"):
        require_pattern(
            runner_path,
            runner,
            rf"(?s)Invoke-WithSemanticDisabled\s*\{{\s*&\s*\(Join-Path\s+\$RepoRoot\s+['\"]scripts\\m21\\{re.escape(isolated_script)}['\"]\)",
            f"{isolated_script} executes with semantic opt-in isolated",
        )
    forbid(
        runner_path,
        runner,
        ".github/workflows",
        "gh workflow",
        "gh run",
        "rerun",
    )

    return (
        "M23 SEMANTIC RETRIEVAL CONSISTENCY SUCCESS "
        f"(documents={len(fixture['documents'])}, queries={len(fixture['queries'])}, topK={fixture['topK']})"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="refuse one in-memory mutation per asserted fact, then exit")
    if parser.parse_args(argv).self_test:
        return literal_gate.self_test(ROOT, evaluate, STRUCTURED_MUTATIONS)
    return literal_gate.run(ROOT, evaluate, "M23 SEMANTIC RETRIEVAL CONSISTENCY FAILED")


if __name__ == "__main__":
    raise SystemExit(main())
