// No indexed value reaches a markup sink: every rendering of the page is replayed with hostile values in a DOM
// that refuses markup and a context that refuses code generation from strings. The proof is by execution, so a
// sink written under a computed name fails as well.
import assert from "node:assert/strict";
import test from "node:test";
import { createPage, findButton, HOSTILE, routes, settle, TOKEN } from "./harness.mjs";

const GENERIC_FAILURE = "La surface d'exploration ne répond pas";

const hostileDocument = {
  stableKey: HOSTILE, fileId: HOSTILE, kind: "CHUNK", moduleId: HOSTILE, language: HOSTILE, startLine: 1, endLine: 2,
  symbol: { qualifiedName: HOSTILE, name: HOSTILE }, contentChecksum: HOSTILE
};

function hostileApi(searchAnswer) {
  const key = encodeURIComponent(HOSTILE);
  return routes([
    ["GET", /^projects$/, { body: { projects: [{
      projectId: "p1", name: HOSTILE, indexState: "STALE", providerId: HOSTILE, modelId: HOSTILE, dimensions: 8,
      indexedSnapshotId: HOSTILE, activeSnapshotId: "s2", documentCount: 1, limitations: [HOSTILE, "SEMANTIC_INDEX_MODEL_STALE"]
    }] } }],
    ["GET", /^projects\/p1\/documents\?/, { body: {
      documents: [hostileDocument], nextCursor: null, totalDocuments: 1, limitations: [HOSTILE], index: {}
    } }],
    ["GET", new RegExp("^projects/p1/documents/" + key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "/neighbors"),
      { body: { neighbors: [{ score: 0.5, document: hostileDocument }] } }],
    ["GET", /^projects\/p1\/documents\/[^/]+$/, { body: {
      document: hostileDocument, indexedContent: HOSTILE, divergence: "DIFFERENTE",
      currentSource: { fileId: HOSTILE, startLine: 1, endLine: 2, truncated: false, content: HOSTILE },
      index: { providerId: HOSTILE, modelId: HOSTILE, dimensions: 8, snapshotId: HOSTILE }
    } }],
    ["POST", /^projects\/p1\/search$/, searchAnswer],
    ["GET", /^projects\/p1\/projection$/, { body: { state: "PRETE", projection: {
      documentCount: 1, pointCount: 1, candidateCount: 1, sampled: false, kinds: ["CHUNK"], seed: 7, method: "pca",
      methodVersion: HOSTILE, explainedVariance: [0.5, 0.25]
    } } }],
    ["GET", /^projects\/p1\/projection\/points\?/, { body: {
      state: "PRETE", total: 1, points: [{ stableKey: HOSTILE, kind: "CHUNK", x: 0, y: 0 }]
    } }]
  ]);
}

const hostileSearch = { body: {
  hits: [{ rank: 1, score: 0.9, signals: [{ type: "SEMANTIC", score: 0.9 }], document: hostileDocument }],
  rankedCount: 1, limitations: [HOSTILE]
} };

function assertRenderedAsText(node, where) {
  assert.ok(node.textContent.includes(HOSTILE), where + " shows the indexed value as text");
}

function assertNoMarkupAndNoFailure(page) {
  assert.deepEqual(page.log.markup, [], "no markup write was attempted");
  assert.ok(!page.$("banner").textContent.includes(GENERIC_FAILURE), "no rendering failed: " + page.$("banner").textContent);
}

test("list, header, detail and neighbours render hostile values as text", async () => {
  const page = createPage({ hash: "#token=" + TOKEN, api: hostileApi(hostileSearch) });
  await settle();

  assertRenderedAsText(page.$("tbody"), "the list");
  assertRenderedAsText(page.$("project"), "the project selector");
  assertRenderedAsText(page.$("model"), "the model badge");
  assertRenderedAsText(page.$("limits"), "the limitations");
  assert.equal(page.$("tbody").children[0].dataset.key, HOSTILE);

  page.$("tbody").children[0].dispatch("click");
  await settle();
  const detail = page.$("detail");
  assertRenderedAsText(detail, "the detail");
  const preformatted = [...detail.descendants()].filter(node => node.tagName === "PRE").map(node => node.textContent);
  assert.deepEqual(preformatted, [HOSTILE, HOSTILE], "indexed content and current source are text");

  findButton(detail, "Trouver les documents similaires").dispatch("click");
  await settle();
  assertRenderedAsText(detail.querySelector("#neighbors"), "the neighbours");
  assertNoMarkupAndNoFailure(page);
});

test("search results, the map texts and the hover render hostile values as text", async () => {
  const page = createPage({ hash: "#token=" + TOKEN, api: hostileApi(hostileSearch) });
  await settle();

  page.$("query").value = "find";
  page.$("go").dispatch("click");
  await settle();
  assertRenderedAsText(page.$("tbody"), "the search results");
  assertRenderedAsText(page.$("limits"), "the search limitations");

  page.$("mapToggle").dispatch("click");
  await settle();
  assertRenderedAsText(page.$("mapMethod"), "the map method");
  page.$("mapCanvas").dispatch("pointermove", { clientX: 400, clientY: 300 });
  page.flushFrames();
  assertRenderedAsText(page.$("mapHover"), "the map hover");
  assert.ok(page.$("mapCanvas").title.includes(HOSTILE));
  assertNoMarkupAndNoFailure(page);
});

test("an API error code is shown as text in the banner, and its message is not shown", async () => {
  const page = createPage({
    hash: "#token=" + TOKEN,
    api: hostileApi({ status: 400, body: { error: { code: HOSTILE, message: "message " + HOSTILE } } })
  });
  await settle();

  page.$("query").value = "find";
  page.$("go").dispatch("click");
  await settle();
  assert.equal(page.$("banner").textContent, "La requête a échoué (code " + HOSTILE + ").");
  assert.equal(page.$("banner").hidden, false);
  assert.deepEqual(page.log.markup, []);
});

test("the test DOM refuses markup and the context refuses code generation", () => {
  const page = createPage();
  const node = page.evaluate("document.createElement('p')");

  assert.throws(() => { node["inner" + "HTML"] = HOSTILE; });
  assert.throws(() => node.insertAdjacentHTML("beforeend", HOSTILE));
  assert.throws(() => page.evaluate("document.write('x')"));
  assert.throws(() => page.evaluate("new DOMParser()"));
  assert.equal(page.log.markup.length, 4);
  // An event handler or an inline document set as an attribute is markup too, whatever the case of its name.
  assert.throws(() => node.setAttribute("onclick", HOSTILE));
  assert.throws(() => node.setAttribute("OnMouseOver", HOSTILE));
  assert.throws(() => node.setAttribute("srcdoc", HOSTILE));
  assert.equal(page.log.markup.length, 7);
  assert.equal(node.getAttribute("onclick"), null);
  node.setAttribute("aria-pressed", "true");
  assert.equal(node.getAttribute("aria-pressed"), "true");
  // The error comes from the context's realm: its name is compared, not its prototype.
  assert.throws(() => page.evaluate("eval('1')"), { name: "EvalError" });
  assert.throws(() => page.evaluate("new Function('return 1')"), { name: "EvalError" });
});
