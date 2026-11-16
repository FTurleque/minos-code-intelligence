// The lookup of the point under the pointer, the list of the displayed points and the hover work of the 2D map:
// what they must keep (the point rendered, the radius) and what they must not do (examine every point, filter
// again for each event, search once per pointer move). The page is the real app.js in a fake DOM.
import assert from "node:assert/strict";
import test from "node:test";
import { countingPoints, createPage, pointSet, routes, seeded, settle, TOKEN } from "./harness.mjs";

const WIDTH = 800;
const HEIGHT = 600;
const RADIUS = 8;
const HALF = Math.max(10, Math.min(WIDTH, HEIGHT) / 2 - 12);

// Reference selection of the contract: nearest displayed point strictly inside the radius, the first one in
// reception order on a tie.
function expectedPoint(points, clientX, clientY, scale, offsetX, offsetY) {
  const half = HALF * scale;
  let best = null;
  let bestDistance = RADIUS * RADIUS;
  for (const point of points) {
    const deltaX = WIDTH / 2 + offsetX + point.x * half - clientX;
    const deltaY = HEIGHT / 2 + offsetY - point.y * half - clientY;
    const distance = deltaX * deltaX + deltaY * deltaY;
    if (distance < bestDistance) { best = point; bestDistance = distance; }
  }
  return best;
}

function mapPage(points, options = {}) {
  const page = createPage(options);
  page.assign("map.points", points);
  page.$("mapCanvas").hidden = false;
  return page;
}

function pixel(point, scale, offsetX, offsetY) {
  const half = HALF * scale;
  return { clientX: WIDTH / 2 + offsetX + point.x * half, clientY: HEIGHT / 2 + offsetY - point.y * half };
}

test("the grid lookup returns the point of the full scan on 100 000 points, at every zoom and offset", () => {
  const points = pointSet(20261115, 100000);
  const random = seeded(99);
  const page = mapPage(points);
  for (const [scale, offsetX, offsetY] of [[0.5, 0, 0], [1, -150, 90], [20, -3000, 2000], [200, -40000, 25000]]) {
    page.assign("map.scale", scale);
    page.assign("map.offsetX", offsetX);
    page.assign("map.offsetY", offsetY);
    let hits = 0;
    for (let index = 0; index < 1000; index += 1) {
      // Half of the positions are near a real point (a random position hits nothing at a high zoom).
      const near = index % 2 === 0 ? pixel(points[Math.floor(random() * points.length)], scale, offsetX, offsetY) : null;
      const clientX = near ? near.clientX + (random() * 20 - 10) : random() * WIDTH;
      const clientY = near ? near.clientY + (random() * 20 - 10) : random() * HEIGHT;
      const expected = expectedPoint(points, clientX, clientY, scale, offsetX, offsetY);
      assert.equal(page.context.pointAt({ clientX, clientY }), expected, "scale " + scale + " at " + clientX + "," + clientY);
      if (expected) hits += 1;
    }
    assert.ok(hits > 100, "scale " + scale + ": the comparison must include real selections, got " + hits);
  }
});

test("equal distances keep the lowest index whatever the cells, and points off the square or not finite do not break the lookup", () => {
  // Four points at the same pixel distance (4.5 px) of the centre, in four cells of a 2 x 2 grid.
  const e = 1 / 64;
  const ring = [
    { stableKey: "a", kind: "CHUNK", x: e, y: 0 }, { stableKey: "b", kind: "CHUNK", x: 0, y: e },
    { stableKey: "c", kind: "CHUNK", x: -e, y: 0 }, { stableKey: "d", kind: "CHUNK", x: 0, y: -e }
  ];
  for (const order of [[0, 1, 2, 3], [3, 2, 1, 0], [2, 0, 3, 1]]) {
    const points = order.map(index => ring[index]);
    assert.equal(mapPage(points).context.pointAt({ clientX: 400, clientY: 300 }), points[0]);
  }
  const odd = [
    { stableKey: "nan", kind: "CHUNK", x: NaN, y: 0 }, { stableKey: "inf", kind: "CHUNK", x: Infinity, y: 0 },
    { stableKey: "out", kind: "CHUNK", x: 1.5, y: -2 }, { stableKey: "in", kind: "CHUNK", x: 0, y: 0 }
  ];
  const page = mapPage(odd);
  assert.equal(page.context.pointAt({ clientX: 400, clientY: 300 }), odd[3]);
  const outside = pixel(odd[2], 1, 0, 0);
  assert.equal(page.context.pointAt(outside), odd[2], "a point off the square is still found");
  assert.equal(page.context.pointAt({ clientX: NaN, clientY: 3 }), null);
  assert.equal(mapPage([]).context.pointAt({ clientX: 400, clientY: 300 }), null);
});

test("at 800 x 600 and zoom 1 a lookup examines less than a hundredth of the displayed points", () => {
  const { counter, points } = countingPoints(pointSet(20261115, 100000));
  const page = mapPage(points);
  page.context.pointAt({ clientX: 10, clientY: 10 });
  let most = 0;
  for (const position of pointSet(8, 100)) {
    counter.reads = 0;
    page.context.pointAt({ clientX: (position.x + 1) * 400, clientY: (position.y + 1) * 300 });
    most = Math.max(most, counter.reads);
  }
  assert.ok(most < points.length / 100, "the most examined by one lookup: " + most + " of " + points.length);
});

test("a position with no point in the radius selects nothing and a click there requests no detail", async () => {
  const far = { stableKey: "far", kind: "CHUNK", x: 0.5, y: 0.5 };
  const { page } = await hoverPage([far]);
  const requestsBefore = page.log.requests.length;
  const canvas = page.$("mapCanvas");
  const where = pixel(far, 1, 0, 0);

  assert.equal(page.context.pointAt({ clientX: where.clientX + 9, clientY: where.clientY }), null);
  canvas.dispatch("pointerup", { clientX: where.clientX + 9, clientY: where.clientY });
  await settle();
  assert.equal(page.log.requests.length, requestsBefore, "no detail requested for a click on nothing");

  canvas.dispatch("pointerup", { clientX: where.clientX + 2, clientY: where.clientY });
  await settle();
  assert.deepEqual(page.log.requests.slice(requestsBefore).map(request => request.url), ["api/v1/projects/p/documents/far"]);
});

test("the displayed list is computed once per filter and per set of points, and serves lookups and drawings", () => {
  const points = pointSet(5, 3000);
  let filterings = 0;
  points.filter = (...parameters) => { filterings += 1; return Array.prototype.filter.apply(points, parameters); };
  const page = mapPage(points);
  page.$("kind").value = "CHUNK";

  const first = page.context.visiblePoints();
  for (let index = 0; index < 10; index += 1) page.context.pointAt({ clientX: 40 * index, clientY: 30 * index });
  page.context.drawMap();
  page.flushFrames();
  assert.equal(page.context.visiblePoints(), first, "the same list object");
  assert.equal(filterings, 1, "one filtering for twelve uses");

  page.$("kind").value = "SYMBOL";
  const symbols = page.context.visiblePoints();
  assert.notEqual(symbols, first);
  assert.ok(symbols.every(point => point.kind === "SYMBOL"));
  assert.equal(page.context.visiblePoints(), symbols);
  assert.equal(filterings, 2);
});

test("after a change of filter only points of the new kind are examined or selected", () => {
  const { counter, points } = countingPoints(pointSet(21, 30000));
  const page = mapPage(points);
  page.$("kind").value = "CHUNK";
  page.context.pointAt({ clientX: 400, clientY: 300 });
  page.$("kind").value = "SYMBOL";
  const symbols = points.filter(point => point.kind === "SYMBOL");
  const random = seeded(3);
  counter.reads = 0;
  counter.byKind = {};
  let most = 0;
  for (let index = 0; index < 200; index += 1) {
    const near = pixel(symbols[Math.floor(random() * symbols.length)], 1, 0, 0);
    const before = counter.reads;
    const found = page.context.pointAt(near);
    // The first lookup also builds the grid of the new list, which reads each point once.
    if (index > 0) most = Math.max(most, counter.reads - before);
    assert.equal(found && found.kind, "SYMBOL");
  }
  assert.deepEqual(Object.keys(counter.byKind), ["SYMBOL"], "no point of another kind is read");
  assert.ok(most < symbols.length / 50, "and the lookup stays local: " + most + " of " + symbols.length);
});

test("a map read again replaces the points and the lookup follows", async () => {
  const sets = [[{ stableKey: "old", kind: "CHUNK", x: 0, y: 0 }], [{ stableKey: "new", kind: "CHUNK", x: 0, y: 0 }]];
  let reads = 0;
  const description = {
    documentCount: 1, sampled: false, kinds: ["CHUNK"], seed: 1, method: "pca", methodVersion: "1",
    explainedVariance: [0.5, 0.3], pointCount: 1, candidateCount: 1
  };
  const page = createPage({
    hash: "#token=" + TOKEN,
    api: routes([
      ["GET", /^projects$/, { body: { projects: [] } }],
      ["GET", /^projects\/p\/projection$/, { body: { state: "PRETE", projection: description } }],
      ["GET", /^projects\/p\/projection\/points\?offset=0&limit=5000$/,
        () => ({ body: { state: "PRETE", total: 1, points: sets[reads++] } })]
    ])
  });
  await settle();
  page.assign("project", { projectId: "p" });
  page.assign("map.open", true);

  const at = { clientX: 400, clientY: 300 };
  page.context.loadMap();
  await settle();
  assert.equal(page.context.pointAt(at).stableKey, "old");
  page.context.loadMap();
  await settle();
  assert.equal(page.context.pointAt(at).stableKey, "new");
  assert.equal(reads, 2);
});

// A started page (token, project list read) showing `points`, with a spy on the lookups.
async function hoverPage(points) {
  const page = createPage({ hash: "#token=" + TOKEN, api: routes([["GET", /^projects$/, { body: { projects: [] } }]]) });
  await settle();
  page.assign("map.points", points);
  page.assign("project", { projectId: "p" });
  page.$("mapCanvas").hidden = false;
  const lookup = page.context.pointAt;
  const events = [];
  page.context.pointAt = event => { events.push({ clientX: event.clientX, clientY: event.clientY }); return lookup(event); };
  return { page, events };
}

test("twenty pointer moves before the next frame are looked up once, at the last position", async () => {
  const points = pointSet(9, 500);
  const { page, events } = await hoverPage(points);
  const framesBefore = page.log.frames;

  for (let move = 0; move < 20; move += 1) {
    page.$("mapCanvas").dispatch("pointermove", pixel(points[move], 1, 0, 0));
  }
  assert.equal(events.length, 0, "nothing is looked up before the frame");
  assert.equal(page.log.frames - framesBefore, 1, "one frame requested for the burst");

  page.flushFrames();
  assert.deepEqual(events, [pixel(points[19], 1, 0, 0)]);
  assert.equal(page.evaluate("map.hover"), expectedPoint(points, events[0].clientX, events[0].clientY, 1, 0, 0));
  assert.equal(page.$("mapHover").textContent.length > 0, true, "the hover text is written from the frame");
  assert.ok(page.canvas.calls.some(call => call[0] === "clearRect"), "a changed hover is drawn in the same frame");
});

test("a click before the next frame requests the detail of the clicked point at once", async () => {
  const points = pointSet(10, 500);
  const { page, events } = await hoverPage(points);

  page.$("mapCanvas").dispatch("pointermove", pixel(points[3], 1, 0, 0));
  const click = pixel(points[7], 1, 0, 0);
  page.$("mapCanvas").dispatch("pointerup", click);
  await settle();

  const expected = expectedPoint(points, click.clientX, click.clientY, 1, 0, 0);
  assert.ok(expected, "the clicked position holds a point");
  assert.deepEqual(events, [click], "the click looked up its own position, the move is still waiting");
  assert.ok(page.log.requests.some(request => request.url === "api/v1/projects/p/documents/" + expected.stableKey));

  page.flushFrames();
  assert.equal(events.length, 2, "the waiting move is looked up at the frame");
});

test("a frame without a changed hover or a requested drawing paints nothing", async () => {
  const points = pointSet(11, 200);
  const { page } = await hoverPage(points);
  page.$("mapCanvas").dispatch("pointermove", { clientX: 1, clientY: 1 });
  page.flushFrames();
  const painted = page.canvas.calls.length;
  page.$("mapCanvas").dispatch("pointermove", { clientX: 2, clientY: 2 });
  page.flushFrames();
  assert.equal(page.canvas.calls.length, painted, "the hover stayed on nothing: no drawing");
});
