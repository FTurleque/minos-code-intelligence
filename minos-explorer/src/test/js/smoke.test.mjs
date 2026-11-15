// The page starts in the fake DOM: without a token it refuses, with one it reads the projects of the API.
import assert from "node:assert/strict";
import test from "node:test";
import { createPage, documentOf, listOf, project, routes, settle, TOKEN } from "./harness.mjs";

test("without a token the page shows the refusal and sends no request", async () => {
  const page = createPage();
  await settle();

  assert.equal(page.$("refused").hidden, false);
  assert.equal(page.$("explorer").hidden, true);
  assert.equal(page.log.requests.length, 0);
});

test("with a token the page removes it from the address and sends it in the Authorization header only", async () => {
  const page = createPage({
    hash: "#token=" + TOKEN,
    api: routes([
      ["GET", /^projects$/, { body: { projects: [project()] } }],
      ["GET", /^projects\/p1\/documents\?$/, { body: listOf([documentOf()]) }]
    ])
  });
  await settle();

  assert.deepEqual(page.log.replaced, ["/"]);
  assert.deepEqual(page.log.requests.map(request => request.url), ["api/v1/projects", "api/v1/projects/p1/documents?"]);
  for (const request of page.log.requests) {
    assert.equal(request.headers.Authorization, "Bearer " + TOKEN);
    assert.ok(!request.url.includes(TOKEN));
  }
  assert.equal(page.$("tbody").children.length, 1);
  assert.equal(page.$("refused").hidden, true);
  assert.deepEqual(page.log.markup, []);
});
