// Translation of the v1 contract constants into the page's labels, the pure helpers, and how an API error is shown.
import assert from "node:assert/strict";
import test from "node:test";
import { createPage, detailOf, documentOf, listOf, project, routes, settle, TOKEN } from "./harness.mjs";

// The constants of the v1 contract the page translates, in one table (SemanticIndexService.State, the projection
// states, the divergences and the stale causes of the semantic API). A renamed constant must change here and in
// the page's label table together.
const CONTRACT = {
  indexStates: ["DISABLED", "MISSING", "NO_ACTIVE_SNAPSHOT", "STALE"],
  projectionStates: ["ABSENTE", "ECHOUEE", "PERIMEE"],
  divergences: ["IDENTIQUE", "DIFFERENTE", "SOURCE_INTROUVABLE", "NON_COMPARABLE"],
  staleCauses: ["SEMANTIC_INDEX_SNAPSHOT_STALE", "SEMANTIC_INDEX_MODEL_STALE"]
};
// States with nothing to explain: the page shows no message for them.
const SILENT = { indexStates: ["READY"], projectionStates: ["PRETE"] };
const LABEL_TABLES = {
  indexStates: "STATE_HELP", projectionStates: "PROJECTION_HELP", divergences: "DIVERGENCE", staleCauses: "STALE_CAUSES"
};

function labelKeys(page, table) {
  return Array.from(page.evaluate("Object.keys(" + table + ")"));
}

test("every contract constant has a label in the page, and every label a contract constant", () => {
  const page = createPage();
  for (const [group, constants] of Object.entries(CONTRACT)) {
    const table = LABEL_TABLES[group];
    const labelled = labelKeys(page, table);
    for (const constant of constants) {
      assert.ok(labelled.includes(constant), "no label for the contract constant " + constant + " in " + table);
    }
    for (const key of labelled) assert.ok(constants.includes(key), table + "." + key + " is not a contract constant");
    for (const constant of SILENT[group] || []) assert.ok(!labelled.includes(constant), constant + " is silent");
  }
});

test("the state banner shows the label of each index state and stale cause, never the raw constant", () => {
  const page = createPage();
  for (const state of CONTRACT.indexStates) {
    page.assign("project", project({ indexState: state }));
    const text = page.context.stateBanner();
    assert.equal(text, page.evaluate("STATE_HELP")[state]);
    assert.ok(!text.includes(state), state + " is shown raw");
  }
  for (const cause of CONTRACT.staleCauses) {
    page.assign("project", project({ indexState: "STALE", limitations: [cause, "OTHER"] }));
    const text = page.context.stateBanner();
    assert.ok(text.endsWith("\nCause : " + page.evaluate("STALE_CAUSES")[cause] + "."), text);
    assert.ok(!text.includes(cause), cause + " is shown raw");
  }
  page.assign("project", project({ indexState: "READY" }));
  assert.equal(page.context.stateBanner(), "");
});

test("the header badge shows the raw index state (characterised: the contract language is decided later)", () => {
  const page = createPage();
  page.assign("project", project({ indexState: "READY" }));
  page.assign("projects", [project()]);
  page.context.renderHeader();
  assert.equal(page.$("state").textContent, "index : READY");
  assert.equal(page.$("state").className, "badge ok");
});

async function mapMessageFor(state) {
  const page = createPage({
    api: routes([
      ["GET", /^projects\/p1\/projection$/, { body: { state, projection: null } }],
      ["GET", /^projects\/p1\/projection\/points\?/, { body: { state, total: 0, points: [] } }]
    ])
  });
  page.assign("project", project());
  page.assign("map.open", true);
  await page.context.loadMap();
  return page.$("mapMessage");
}

test("the map message shows the label of each projection state, never the raw constant", async () => {
  for (const state of CONTRACT.projectionStates) {
    const message = await mapMessageFor(state);
    assert.equal(message.hidden, false);
    assert.ok(message.textContent.startsWith(createPage().evaluate("PROJECTION_HELP")[state].slice(0, 30)));
    assert.ok(!message.textContent.includes(state), state + " is shown raw");
  }
  assert.equal((await mapMessageFor("PRETE")).hidden, true);
  assert.equal((await mapMessageFor("UNKNOWN_STATE")).textContent, "UNKNOWN_STATE", "an unknown state is shown raw");
});

test("the detail shows the label of each divergence, never the raw constant", async () => {
  for (const divergence of CONTRACT.divergences) {
    const document = documentOf();
    const page = createPage({
      hash: "#token=" + TOKEN,
      api: routes([
        ["GET", /^projects$/, { body: { projects: [project()] } }],
        ["GET", /^projects\/p1\/documents\?/, { body: listOf([document]) }],
        ["GET", /^projects\/p1\/documents\/key-1$/, { body: detailOf(document, { divergence }) }]
      ])
    });
    await settle();
    page.$("tbody").children[0].dispatch("click");
    await settle();
    const texts = [...page.$("detail").descendants()].filter(node => node.tagName === "P").map(node => node.textContent);
    assert.ok(texts.includes(page.evaluate("DIVERGENCE")[divergence]), divergence);
    assert.ok(!page.$("detail").textContent.includes(divergence), divergence + " is shown raw");
  }
});

test("pure helpers: identifiers, lines, symbol names, percentages and filters", () => {
  const page = createPage();
  const c = page.context;

  assert.equal(c.shortId(null), "—");
  assert.equal(c.shortId("abcdefghijklmn"), "abcdefghijklmn");
  assert.equal(c.shortId("abcdefghijklmno"), "abcdefghijkl…");
  assert.equal(c.lines({ startLine: 0, endLine: 0 }), "—");
  assert.equal(c.lines({ startLine: 3, endLine: 7 }), "3–7");
  assert.equal(c.symbolName({ symbol: { qualifiedName: "a.B", name: "B" } }), "a.B");
  assert.equal(c.symbolName({ symbol: { name: "B" } }), "B");
  assert.equal(c.symbolName({ kind: "FILE" }), "—");
  assert.equal(c.symbolName({ kind: "CHUNK" }), "non résolu");
  assert.equal(c.percent(0.1234), "12,3 %");
  assert.equal(c.percent(1), "100,0 %");

  page.$("kind").value = "SYMBOL";
  page.$("module").value = "  core ";
  page.$("language").value = "   ";
  page.$("filePrefix").value = "src/";
  assert.deepEqual({ ...c.filters() }, { kind: "SYMBOL", module: "core", filePrefix: "src/" });
});

test("empty search messages and the storage note of the state banner", () => {
  const page = createPage({ storageRefused: true });
  const c = page.context;

  assert.equal(c.emptySearchMessage({ hits: [{}], limitations: [], rankedCount: 1 }), "");
  assert.equal(c.emptySearchMessage({ hits: [], limitations: ["SEMANTIC_SEARCH_REQUIRES_READY_INDEX"], rankedCount: 0 }),
    "La recherche exige un index prêt (READY). La liste reste consultable.");
  assert.equal(c.emptySearchMessage({ hits: [], limitations: ["SEMANTIC_INDEX_CHANGED_DURING_QUERY"], rankedCount: 0 }),
    "L'index a changé pendant la recherche. Relancez-la.");
  assert.equal(c.emptySearchMessage({ hits: [], limitations: [], rankedCount: 3 }),
    "Aucun résultat ne correspond aux filtres actifs. Élargissez-les.");
  assert.equal(c.emptySearchMessage({ hits: [], limitations: [], rankedCount: 0 }), "Aucun résultat.");

  c.readToken();
  assert.equal(page.evaluate("tabStorageRefused"), "SecurityError");
  assert.ok(c.stateBanner().startsWith("Ce navigateur refuse le stockage de l'onglet (SecurityError)"));
});

test("an API error is shown by its message whatever its code (characterised), other failures by a fixed text", async () => {
  const page = createPage({ api: routes([["GET", /^projects$/, { body: { projects: [] } }]]) });
  const ApiError = page.evaluate("ApiError");

  page.context.report(new ApiError(400, "INVALID_REQUEST", "Requête invalide."));
  assert.equal(page.$("banner").textContent, "Requête invalide.");
  page.context.report(new ApiError(400, "A_CODE_THE_PAGE_DOES_NOT_KNOW", "Message du serveur."));
  assert.equal(page.$("banner").textContent, "Message du serveur.", "an unknown code is shown by its message");
  page.context.report(new Error("internal"));
  assert.equal(page.$("banner").textContent,
    "La surface d'exploration ne répond pas. Vérifiez que « minos explorer serve » est toujours lancé.");
  page.context.report(new ApiError(403, "REFUSED", "Accès refusé."));
  assert.ok(page.$("banner").textContent.startsWith("La surface d'exploration ne répond pas"), "a refusal changes nothing");

  page.context.report(new ApiError(409, "INDEX_CHANGED", "changed"));
  await settle();
  assert.ok(page.$("banner").textContent.startsWith("L'index a changé pendant la consultation"));
  assert.deepEqual(page.log.requests.map(request => request.url), ["api/v1/projects"], "the projects are read again");
});
