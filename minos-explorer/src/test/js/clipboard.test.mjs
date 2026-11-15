// "Copier la référence": a refused copy is said in the page and leaves no unhandled rejection; a copy that
// succeeds writes the file identifier and its lines and changes nothing else.
import assert from "node:assert/strict";
import test from "node:test";
import { createPage, detailOf, documentOf, findButton, listOf, project, routes, settle, TOKEN } from "./harness.mjs";

async function detailPage(clipboard) {
  const document = documentOf();
  const page = createPage({
    hash: "#token=" + TOKEN,
    clipboard,
    api: routes([
      ["GET", /^projects$/, { body: { projects: [project()] } }],
      ["GET", /^projects\/p1\/documents\?/, { body: listOf([document]) }],
      ["GET", /^projects\/p1\/documents\/key-1$/, { body: detailOf(document) }]
    ])
  });
  await settle();
  page.$("tbody").children[0].dispatch("click");
  await settle();
  return page;
}

async function withRejectionsRecorded(action) {
  const rejections = [];
  const record = reason => rejections.push(reason);
  process.on("unhandledRejection", record);
  try {
    await action();
    await settle();
  } finally {
    process.off("unhandledRejection", record);
  }
  return rejections;
}

test("a refused copy shows a message in the banner and leaves no unhandled rejection", async () => {
  const written = [];
  const page = await detailPage({
    writeText: text => {
      written.push(text);
      const error = new Error("Write permission denied.");
      error.name = "NotAllowedError";
      return Promise.reject(error);
    }
  });
  const button = findButton(page.$("detail"), "Copier la référence");
  assert.equal(button.disabled, false);

  const rejections = await withRejectionsRecorded(() => button.dispatch("click"));

  assert.deepEqual(written, ["src/A.java:3-7"]);
  assert.deepEqual(rejections, [], "the rejection of the clipboard is handled");
  assert.equal(page.$("banner").hidden, false);
  assert.match(page.$("banner").textContent, /copie de la référence a échoué/);
});

test("a copy that succeeds writes the file and its lines and leaves the banner unchanged", async () => {
  const written = [];
  const page = await detailPage({ writeText: text => { written.push(text); return Promise.resolve(); } });
  const before = [page.$("banner").hidden, page.$("banner").textContent];

  const rejections = await withRejectionsRecorded(() => findButton(page.$("detail"), "Copier la référence").dispatch("click"));

  assert.deepEqual(written, ["src/A.java:3-7"]);
  assert.deepEqual(rejections, []);
  assert.deepEqual([page.$("banner").hidden, page.$("banner").textContent], before);
});

test("without a clipboard the button is disabled", async () => {
  const page = await detailPage(undefined);
  assert.equal(findButton(page.$("detail"), "Copier la référence").disabled, true);
});
