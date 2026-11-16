// Measurement of the map lookups under Node (not a test: it is not named *.test.mjs, so no gate runs it).
// Usage: node minos-explorer/src/test/js/map-bench.mjs [--json]
//
// The point sets are synthetic (uniform in the square -1..1, a third per kind) and the canvas is 800 x 600 at
// zoom 1. The durations are those of the page script under Node (V8, a fake DOM and a fake 2D context): they are
// not browser measurements, and they are noisy (the medians of several runs are reported).
//
// Two sets are built from the same seed. The timing runs on plain objects. The number of points examined by a
// lookup runs on counting points (the abscissa is a getter): it is the number of abscissas read by one lookup after
// a first lookup that built whatever the page builds, so it does not depend on how the page searches.
import { countingPoints, createPage, pointSet } from "./harness.mjs";

const SIZES = [100000, 250000, 1000000];
const FILTERS = ["", "SYMBOL"];
const POSITIONS = 400;
const COUNTED_POSITIONS = 50;
const RUNS = 5;

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

function hundredths(value) {
  return Math.round(value * 100) / 100;
}

function positionsOf(count) {
  return pointSet(7, count).map(position => ({ clientX: (position.x + 1) * 400, clientY: (position.y + 1) * 300 }));
}

function pageOf(points, filter) {
  const page = createPage();
  page.assign("map.points", points);
  page.$("mapCanvas").hidden = false;
  page.$("kind").value = filter;
  page.canvas.fillRect = () => {};
  page.canvas.strokeRect = () => {};
  return page;
}

function examinedPerLookup(plain, filter) {
  const { counter, points } = countingPoints(plain);
  const page = pageOf(points, filter);
  const positions = positionsOf(COUNTED_POSITIONS);
  page.context.pointAt(positions[0]);
  return positions.map(position => {
    counter.reads = 0;
    page.context.pointAt(position);
    return counter.reads;
  });
}

function measure(count, filter) {
  const points = pointSet(20261115, count);
  const page = pageOf(points, filter);
  const positions = positionsOf(POSITIONS);
  const shown = page.context.visiblePoints().length;

  const lookup = [];
  page.context.pointAt(positions[0]);
  for (let run = 0; run < RUNS; run += 1) {
    const start = process.hrtime.bigint();
    for (const position of positions) page.context.pointAt(position);
    lookup.push(Number(process.hrtime.bigint() - start) / POSITIONS);
  }
  const frame = [];
  for (let run = 0; run < RUNS; run += 1) {
    page.context.drawMap();
    const start = process.hrtime.bigint();
    page.flushFrames();
    frame.push(Number(process.hrtime.bigint() - start));
  }
  const examined = examinedPerLookup(points, filter);
  return {
    count, filter: filter || "(none)", shown,
    examinedMedian: median(examined), examinedMax: Math.max(...examined),
    lookupMicroseconds: hundredths(median(lookup) / 1000), frameMilliseconds: hundredths(median(frame) / 1e6)
  };
}

const rows = [];
for (const count of SIZES) for (const filter of FILTERS) rows.push(measure(count, filter));
const context = {
  node: process.version, platform: process.platform, canvas: "800x600", zoom: 1,
  positions: POSITIONS, countedPositions: COUNTED_POSITIONS, runs: RUNS
};
if (process.argv.includes("--json")) {
  console.log(JSON.stringify({ context, rows }, null, 2));
} else {
  console.log(JSON.stringify(context));
  console.table(rows);
}
