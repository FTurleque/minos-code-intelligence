#!/usr/bin/env python3
"""Static fail-closed consistency gate for M23 Semantic Retrieval 2.0."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle not in text:
            raise RuntimeError(f"{relative}: missing required contract text: {needle}")


def forbid(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle in text:
            raise RuntimeError(f"{relative}: forbidden M23 regression text: {needle}")


def main() -> int:
    try:
        provider_path = "minos-application/src/main/java/com/minos/semantic/OllamaEmbeddingProvider.java"
        provider = read(provider_path)
        require(
            provider_path,
            provider,
            'return "minos-local-ollama"',
            "DEFAULT_ENDPOINT",
            "127.0.0.1:11434/api/embed",
            "openConnection(Proxy.NO_PROXY)",
            "setInstanceFollowRedirects(false)",
            "Ollama endpoint must be loopback-only",
            "MAX_RESPONSE_BYTES",
            "parseEmbeddingResponse",
            "SemanticVector.fromArray",
        )
        forbid(provider_path, provider, "https://ollama.com", "api/pull")

        app_path = "minos-application/src/main/java/com/minos/application/MinosApplication.java"
        app = read(app_path)
        require(
            app_path,
            app,
            'SEMANTIC_PROVIDER_ENV = "MINOS_SEMANTIC_PROVIDER"',
            'SEMANTIC_MODEL_ENV = "MINOS_SEMANTIC_MODEL"',
            'SEMANTIC_DIMENSIONS_ENV = "MINOS_SEMANTIC_DIMENSIONS"',
            'SEMANTIC_ENDPOINT_ENV = "MINOS_SEMANTIC_ENDPOINT"',
            '"ollama".equals(provider)',
            "new OllamaEmbeddingProvider",
            '"local-hash".equals(provider)',
        )

        store_path = "minos-storage-local/src/main/java/com/minos/store/FileSemanticVectorStore.java"
        store = read(store_path)
        require(
            store_path,
            store,
            "LEGACY_FORMAT_VERSION = 1",
            "FORMAT_VERSION = 2",
            'LEGACY_FILE = "index-v1.bin"',
            'CURRENT_FILE = "index-v2.bin"',
            "input.readDouble() : input.readFloat()",
            "compact(indexed)",
            "values[d] = compact",
            "output.writeFloat",
            "Files.deleteIfExists(directory.resolve(LEGACY_FILE))",
        )
        forbid(store_path, store, "output.writeDouble(indexed.vector()")

        store_test_path = "minos-storage-local/src/test/java/com/minos/store/FileSemanticVectorStoreTest.java"
        store_test = read(store_test_path)
        require(
            store_test_path,
            store_test,
            "cachedV2SnapshotUsesExactlyThePersistedFloat32Values",
            "(double) (float) sourceValue",
            "assertEquals(cached.documents(), reopened.documents())",
            "readsLegacyV1AndMigratesOnNextReplace",
        )

        search_path = "minos-application/src/main/java/com/minos/semantic/SemanticSearchService.java"
        search = read(search_path)
        require(
            search_path,
            search,
            "MAX_QUERY_CACHE_ENTRIES = 256",
            "QueryCacheKey",
            "VECTOR_SEARCH_LINEAR_SCAN",
            "ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND",
            "SEMANTIC_QUERY_VECTOR_CACHE_BOUNDED_256",
        )
        forbid(search_path, search, "HNSW", "approximateNearest")

        index_path = "minos-application/src/main/java/com/minos/semantic/SemanticIndexService.java"
        index = read(index_path)
        require(
            index_path,
            index,
            "LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL",
            "LOCAL_LEARNED_EMBEDDING_LOOPBACK_ONLY",
            "LEARNED_MODEL_QUALITY_IS_CONFIGURATION_SPECIFIC",
            "SEMANTIC_RESULTS_REMAIN_HEURISTIC",
        )

        fixture_path = "fixtures/m23/semantic-quality-v1.json"
        fixture = json.loads(read(fixture_path))
        if fixture.get("version") != 1 or fixture.get("topK") != 3:
            raise RuntimeError("M23 quality fixture version/topK mismatch")
        if len(fixture.get("documents", [])) < 10 or len(fixture.get("queries", [])) < 8:
            raise RuntimeError("M23 quality fixture is too small")
        thresholds = fixture.get("thresholds", {})
        required_thresholds = {"recallAtK": 0.75, "mrr": 0.70, "ndcgAtK": 0.72}
        for metric, minimum in required_thresholds.items():
            actual = float(thresholds.get(metric, -1.0))
            if actual < minimum:
                raise RuntimeError(f"M23 quality threshold lowered: {metric}={actual} < {minimum}")

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
            '"com/minos/semantic/OllamaEmbeddingProvider"',
            '"semantic-vector-store"',
            '"semantic-hybrid-retrieval"',
        )

        roadmap_path = "docs/roadmap/M23_EXECUTION.md"
        roadmap = read(roadmap_path)
        require(
            roadmap_path,
            roadmap,
            "9/9 IMPLÉMENTÉS",
            "ADR-0031",
            "Recall@3 >= 0.75",
            "KEEP_CURRENT_M20_BACKEND",
            "model      embeddinggemma",
            "dimensions 768",
            "Proxy.NO_PROXY",
            "ProxyHandler({})",
            "M23 FINAL SEMANTIC RETRIEVAL 2.0 VALIDATION SUCCESS",
            "M21-S2/CI reste en pause jusqu’en août 2026",
        )

        adr_path = "docs/adr/0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md"
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
            "check-semantic.py",
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
        isolated_calls = runner.count("    Invoke-WithSemanticDisabled {")
        if isolated_calls != 3:
            raise RuntimeError(
                f"{runner_path}: expected exactly three isolated regression gate calls, found {isolated_calls}"
            )
        forbid(
            runner_path,
            runner,
            ".github/workflows",
            "gh workflow",
            "gh run",
            "rerun",
        )

        print(
            "M23 SEMANTIC RETRIEVAL CONSISTENCY SUCCESS "
            f"(documents={len(fixture['documents'])}, queries={len(fixture['queries'])}, topK={fixture['topK']})"
        )
        return 0
    except Exception as exc:
        print(f"M23 SEMANTIC RETRIEVAL CONSISTENCY FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
