// Characterisation of the 2D map: the point under the pointer, the kind filter, the zoom, the paged reading of
// the points, the drawing on the canvas and the work done per burst of pointer moves. These are the behaviours a
// faster map must keep; the counters are the current values, not performance thresholds.
import assert from "node:assert/strict";
import test from "node:test";
import { createPage, pointSet, routes, settle, TOKEN } from "./harness.mjs";

// Current behaviour, in one place: a change of the map code changes this table and the diff is its effect.
const CURRENT = {
  canvas: { width: 800, height: 600 },
  captureRadiusPixels: 8,
  zoom: { minimum: 0.5, maximum: 200 },
  mapPage: 5000,
  burst: { moves: 50, pointLookups: 50, framesRequested: 1 }
};

const HALF = Math.max(10, Math.min(CURRENT.canvas.width, CURRENT.canvas.height) / 2 - 12);

// Reference selection, written from the contract of the map: the nearest visible point strictly inside the capture
// radius, the first one in reception order on a tie, none beyond the radius.
function expectedPoint(points, clientX, clientY, scale = 1, offsetX = 0, offsetY = 0) {
  const half = HALF * scale;
  const originX = CURRENT.canvas.width / 2 + offsetX;
  const originY = CURRENT.canvas.height / 2 + offsetY;
  let best = null;
  let bestDistance = CURRENT.captureRadiusPixels * CURRENT.captureRadiusPixels;
  for (const point of points) {
    const dx = originX + point.x * half - clientX;
    const dy = originY - point.y * half - clientY;
    const distance = dx * dx + dy * dy;
    if (distance < bestDistance) { best = point; bestDistance = distance; }
  }
  return best;
}

function mapPage(points) {
  const page = createPage();
  page.assign("map.points", points);
  page.$("mapCanvas").hidden = false;
  return page;
}

function keys(points) {
  return points === null ? null : Array.from(points, point => point.stableKey);
}

test("the point under the pointer is the nearest one inside the capture radius, on seeded sets", () => {
  for (const [seed, count] of [[1, 10], [2, 200], [3, 5000]]) {
    const points = pointSet(seed, count);
    const page = mapPage(points);
    const random = pointSet(seed + 100, 300);
    for (const position of random) {
      const clientX = (position.x + 1) * CURRENT.canvas.width / 2;
      const clientY = (position.y + 1) * CURRENT.canvas.height / 2;
      assert.equal(page.context.pointAt({ clientX, clientY }), expectedPoint(points, clientX, clientY),
        "seed " + seed + " at " + clientX + "," + clientY);
    }
  }
});

test("on a set of 100 000 points the selection matches the reference, also with a kind filter and a zoom", () => {
  const points = pointSet(20261115, 100000);
  const page = mapPage(points);
  const positions = pointSet(7, 100);
  for (const position of positions) {
    const clientX = (position.x + 1) * 400;
    const clientY = (position.y + 1) * 300;
    assert.equal(page.context.pointAt({ clientX, clientY }), expectedPoint(points, clientX, clientY));
  }
  page.$("kind").value = "SYMBOL";
  page.assign("map.scale", 4);
  page.assign("map.offsetX", -100);
  const symbols = points.filter(point => point.kind === "SYMBOL");
  for (const position of positions) {
    const clientX = (position.x + 1) * 400;
    const clientY = (position.y + 1) * 300;
    assert.equal(page.context.pointAt({ clientX, clientY }), expectedPoint(symbols, clientX, clientY, 4, -100, 0));
  }
});

test("a tie keeps the first point received and a point beyond the radius is not selected", () => {
  // 1/64 of the half width is 4.5 px, 1/32 is 9 px: both are exact in binary floating point.
  const left = { stableKey: "left", kind: "CHUNK", x: -1 / 64, y: 0 };
  const right = { stableKey: "right", kind: "CHUNK", x: 1 / 64, y: 0 };
  const same = { stableKey: "same", kind: "CHUNK", x: -1 / 64, y: 0 };
  const far = { stableKey: "far", kind: "CHUNK", x: 1 / 32, y: 0 };

  assert.equal(mapPage([left, right, same]).context.pointAt({ clientX: 400, clientY: 300 }), left);
  assert.equal(mapPage([right, left]).context.pointAt({ clientX: 400, clientY: 300 }), right);
  assert.equal(mapPage([far]).context.pointAt({ clientX: 400, clientY: 300 }), null, "9 px is beyond the 8 px radius");
  assert.equal(mapPage([far]).context.pointAt({ clientX: 401.5, clientY: 300 }), far, "7.5 px is inside");
});

test("the kind filter keeps the points of that kind in reception order, and all of them without filter", () => {
  const points = pointSet(11, 60);
  const page = mapPage(points);

  assert.equal(page.context.visiblePoints(), points, "without filter the received array itself is visible");
  page.$("kind").value = "FILE";
  assert.deepEqual(keys(page.context.visiblePoints()), keys(points.filter(point => point.kind === "FILE")));
  page.$("kind").value = "CHUNK";
  assert.deepEqual(keys(page.context.visiblePoints()), keys(points.filter(point => point.kind === "CHUNK")));
  page.$("kind").value = "";
  assert.equal(page.context.visiblePoints(), points);
});

test("the zoom stays within its bounds and keeps the point under the cursor in place", () => {
  const page = mapPage(pointSet(5, 10));
  const world = (x, y) => {
    const geometry = page.evaluate("mapGeometry()");
    return [(x - geometry.originX) / geometry.half, (geometry.originY - y) / geometry.half];
  };

  const before = world(250, 120);
  page.context.zoomMap(1.2, 250, 120);
  const after = world(250, 120);
  assert.ok(Math.abs(before[0] - after[0]) < 1e-9 && Math.abs(before[1] - after[1]) < 1e-9, "fixed point");
  assert.equal(page.evaluate("map.scale"), 1.2);

  for (let step = 0; step < 5; step += 1) page.context.zoomMap(10, 400, 300);
  assert.equal(page.evaluate("map.scale"), CURRENT.zoom.maximum);
  for (let step = 0; step < 10; step += 1) page.context.zoomMap(0.01, 400, 300);
  assert.equal(page.evaluate("map.scale"), CURRENT.zoom.minimum);
});

function pointsPage(total, pageAnswers) {
  let call = 0;
  const page = createPage({
    api: routes([["GET", /^projects\/p\/projection\/points\?offset=(\d+)&limit=(\d+)$/, (match, request) => {
      const answer = pageAnswers(call, Number(match[1]), Number(match[2]), page);
      call += 1;
      return answer;
    }]])
  });
  page.assign("project", { projectId: "p" });
  page.assign("map.request", 1);
  return page;
}

function slice(state, offset, count, total) {
  const points = [];
  for (let index = offset; index < offset + count; index += 1) points.push({ stableKey: "k" + index, kind: "CHUNK", x: 0, y: 0 });
  return { body: { state, total, points } };
}

test("the points are read page after page until the total is reached", async () => {
  const page = pointsPage(12000, (call, offset, limit) => slice("PRETE", offset, Math.min(limit, 12000 - offset), 12000));
  const points = await page.context.readMapPoints(1, "PRETE");

  assert.equal(points.length, 12000);
  assert.deepEqual(page.log.requests.map(request => request.url), [0, 5000, 10000]
    .map(offset => "api/v1/projects/p/projection/points?offset=" + offset + "&limit=" + CURRENT.mapPage));
});

test("the reading stops when the state changes or a page is empty, and is abandoned when another one started", async () => {
  const changed = pointsPage(12000, (call, offset, limit) => slice(call === 0 ? "PRETE" : "PERIMEE", offset, limit, 12000));
  assert.equal((await changed.context.readMapPoints(1, "PRETE")).length, 5000);
  assert.equal(changed.log.requests.length, 2);

  const empty = pointsPage(12000, (call, offset, limit) => slice("PRETE", offset, call === 0 ? limit : 0, 12000));
  assert.equal((await empty.context.readMapPoints(1, "PRETE")).length, 5000);
  assert.equal(empty.log.requests.length, 2);

  const stale = pointsPage(12000, (call, offset, limit, page) => {
    if (call === 1) page.assign("map.request", 2);
    return slice("PRETE", offset, limit, 12000);
  });
  assert.equal(await stale.context.readMapPoints(1, "PRETE"), null);
  assert.equal(stale.log.requests.length, 2);
});

test("a drawing clears the canvas, draws each plain point once and frames the selected one", () => {
  const points = [
    { stableKey: "a", kind: "CHUNK", x: 0, y: 0 },
    { stableKey: "b", kind: "SYMBOL", x: 0.5, y: 0.5 },
    { stableKey: "c", kind: "FILE", x: -0.5, y: -0.25 },
    { stableKey: "d", kind: "CHUNK", x: 0.25, y: -0.5 }
  ];
  const page = mapPage(points);
  page.assign("selectedKey", "b");
  page.assign("map.highlights", page.evaluate("new Set(['d'])"));

  page.context.drawMap();
  page.context.drawMap();
  assert.equal(page.log.frames, 1, "two requests in one frame draw once");
  page.flushFrames();

  const calls = page.canvas.calls;
  assert.deepEqual(calls[0], ["setTransform", 1, 0, 0, 1, 0, 0]);
  assert.deepEqual(calls[1], ["clearRect", 0, 0, 800, 600]);
  const plain = calls.filter(call => call[0] === "fillRect" && call[3] === 3);
  assert.deepEqual(plain.map(call => [call[1] + 1.5, call[2] + 1.5]),
    [[400, 300], [400 + 0.5 * HALF, 300 - 0.5 * HALF], [400 - 0.5 * HALF, 300 + 0.25 * HALF]],
    "a, b, c: one pass per kind, CHUNK then SYMBOL then FILE");
  assert.ok(plain.every(call => call[6] === 0.18), "plain points are dimmed while a search highlights others");
  const marked = calls.filter(call => call[0] === "fillRect" && call[3] === 6);
  assert.deepEqual(marked.map(call => [call[1] + 3, call[2] + 3]), [[400 + 0.25 * HALF, 300 + 0.5 * HALF]]);
  const framed = calls.filter(call => call[0] === "strokeRect");
  assert.deepEqual(framed.map(call => [call[1] + 6, call[2] + 6, call[5]]), [[400 + 0.5 * HALF, 300 - 0.5 * HALF, 2]]);
});

test("a burst of pointer moves without a frame looks a point up per move and requests one drawing", async () => {
  const page = createPage({ hash: "#token=" + TOKEN, api: routes([["GET", /^projects$/, { body: { projects: [] } }]]) });
  await settle();
  const points = pointSet(9, 2000);
  page.assign("map.points", points);
  page.$("mapCanvas").hidden = false;
  const lookup = page.context.pointAt;
  let lookups = 0;
  page.context.pointAt = event => { lookups += 1; return lookup(event); };
  const framesBefore = page.log.frames;

  for (let move = 0; move < CURRENT.burst.moves; move += 1) {
    const point = points[move];
    page.$("mapCanvas").dispatch("pointermove", { clientX: 400 + point.x * HALF, clientY: 300 - point.y * HALF });
  }

  assert.equal(lookups, CURRENT.burst.pointLookups);
  assert.equal(page.log.frames - framesBefore, CURRENT.burst.framesRequested);
  assert.equal(page.flushFrames(), 1);
  assert.ok(page.canvas.calls.some(call => call[0] === "clearRect"));
});
