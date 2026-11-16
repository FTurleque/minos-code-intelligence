"use strict";
// MINOS explorer, 2D map: the points of a prepared projection, their grid, the drawing and the pointer.
// Loaded before app.js; the page script that follows provides $, el, clear, api, report, project, view,
// selectedKey, projectPath and showDetail, which this file only uses once the page has started.

// 2D map. The page draws the points of a projection that was prepared by a command; it never asks for a
// projection to be computed, and a filter only hides points.

const MAP_PAGE = 5000;
const KIND_COLOR = { CHUNK: "#5fb0ff", SYMBOL: "#f2b45a", FILE: "#7fd49b" };
const PROJECTION_HELP = {
  ABSENTE: "Aucune projection n'est préparée pour cet index. Lancez « minos semantic projection prepare <projet> », puis rouvrez la carte. La liste, la recherche et le détail restent disponibles.",
  ECHOUEE: "La projection enregistrée est illisible. Relancez « minos semantic projection prepare <projet> ».",
  PERIMEE: "La projection est périmée : l'index a changé depuis sa préparation. Relancez « minos semantic projection prepare <projet> ». La carte périmée n'est pas affichée par défaut."
};

const map = {
  open: false, request: 0, status: null, points: [], showStale: false, highlights: new Set(),
  scale: 1, offsetX: 0, offsetY: 0, hover: null, drag: null, frame: 0, dirty: false, pendingHover: null
};

function mapMessage(text) {
  $("mapMessage").textContent = text || "";
  $("mapMessage").hidden = !text;
}

function percent(share) {
  return (share * 100).toFixed(1).replace(".", ",") + " %";
}

// The displayed points and the grid over them, rebuilt only when the points of the map or the kind filter change.
const MAP_CAPTURE_PIXELS = 8;
const MAP_GRID_POINTS_PER_CELL = 2;
const MAP_GRID_MAX_SIDE = 1024;
const mapIndex = { points: null, count: -1, kind: null, list: [], grid: null };

// The points shown for the current kind filter. The list is the received array itself without filter, and one
// filtered copy otherwise; it is computed again only when the points of the map or the filter change.
function visiblePoints() {
  const kind = $("kind").value;
  if (mapIndex.points !== map.points || mapIndex.count !== map.points.length || mapIndex.kind !== kind) {
    mapIndex.points = map.points;
    mapIndex.count = map.points.length;
    mapIndex.kind = kind;
    mapIndex.list = kind ? map.points.filter(point => point.kind === kind) : map.points;
    mapIndex.grid = null;
  }
  return mapIndex.list;
}

function mapGrid() {
  const list = visiblePoints();
  if (mapIndex.grid === null) mapIndex.grid = buildPointGrid(list);
  return mapIndex.grid;
}

// The cell of a coordinate of the projection (the square from -1 to 1), clamped so that a point off the square
// belongs to an edge cell.
// Plain arithmetic, no call to a built-in: this runs once per point when the grid is built.
function gridCell(value, side) {
  const scaled = (value + 1) * side / 2;
  if (!(scaled > 0)) return 0;
  if (scaled >= side) return side - 1;
  return scaled | 0;
}

// A regular grid over the square of the projection, in the coordinates of the projection, so that it does not
// depend on the zoom or the offset. `starts` and `items` list, cell after cell, the indices in `list` of the points
// of the cell in ascending order; a point whose coordinates are not finite is in no cell.
function buildPointGrid(list) {
  const side = Math.max(1, Math.min(MAP_GRID_MAX_SIDE, Math.ceil(Math.sqrt(list.length / MAP_GRID_POINTS_PER_CELL))));
  const cellOf = new Int32Array(list.length).fill(-1);
  const starts = new Int32Array(side * side + 1);
  for (let index = 0; index < list.length; index += 1) {
    const point = list[index];
    const x = point.x;
    const y = point.y;
    if (x - x !== 0 || y - y !== 0) continue; // NaN and the infinities are in no cell
    const cell = gridCell(y, side) * side + gridCell(x, side);
    cellOf[index] = cell;
    starts[cell + 1] += 1;
  }
  for (let cell = 0; cell < side * side; cell += 1) starts[cell + 1] += starts[cell];
  const items = new Int32Array(starts[side * side]);
  const filled = starts.slice(0, side * side);
  for (let index = 0; index < list.length; index += 1) {
    if (cellOf[index] >= 0) items[filled[cellOf[index]]++] = index;
  }
  return { side: side, starts: starts, items: items, list: list };
}

// The cells that cover the disk of the capture radius around the pixel (x, y), or null for a position that is not
// a number. The radius is widened by a rounding margin: the exact distance test decides.
function captureCells(grid, geometry, x, y) {
  const half = geometry.half;
  const centreX = (x - geometry.originX) / half;
  const centreY = (geometry.originY - y) / half;
  if (!Number.isFinite(centreX) || !Number.isFinite(centreY)) return null;
  const reach = MAP_CAPTURE_PIXELS / half * (1 + 1e-9) + 1e-12;
  return {
    first: gridCell(centreX - reach, grid.side), last: gridCell(centreX + reach, grid.side),
    rowFirst: gridCell(centreY - reach, grid.side), rowLast: gridCell(centreY + reach, grid.side)
  };
}

// The nearest point strictly inside the capture radius of the pixel (x, y), the lowest index on a tie, or null.
// Only the cells that cover the disk of the radius are read; the distance is the one of a scan of every point.
function searchPointGrid(grid, geometry, x, y) {
  const cells = captureCells(grid, geometry, x, y);
  if (cells === null) return null;
  const list = grid.list;
  let best = -1;
  let bestDistance = MAP_CAPTURE_PIXELS * MAP_CAPTURE_PIXELS;
  const consider = index => {
    const point = list[index];
    const deltaX = geometry.originX + point.x * geometry.half - x;
    const deltaY = geometry.originY - point.y * geometry.half - y;
    const distance = deltaX * deltaX + deltaY * deltaY;
    if (distance < bestDistance || (distance === bestDistance && best >= 0 && index < best)) {
      best = index;
      bestDistance = distance;
    }
  };
  if ((cells.last - cells.first + 1) * (cells.rowLast - cells.rowFirst + 1) > list.length) {
    for (let index = 0; index < list.length; index += 1) consider(index);
  } else {
    for (let row = cells.rowFirst; row <= cells.rowLast; row += 1) {
      const from = grid.starts[row * grid.side + cells.first];
      const to = grid.starts[row * grid.side + cells.last + 1];
      for (let slot = from; slot < to; slot += 1) consider(grid.items[slot]);
    }
  }
  return best < 0 ? null : list[best];
}

function renderMapTexts() {
  const described = map.status && map.status.projection;
  const shown = map.points.length > 0;
  $("mapCanvas").hidden = !shown;
  clear($("mapLegend"));
  $("mapHover").textContent = "";
  if (!described || !shown) {
    $("mapCounter").textContent = "";
    $("mapMethod").textContent = "";
    return;
  }
  const visible = visiblePoints();
  const filtered = visible.length !== map.points.length;
  let counter = visible.length + " points affichés sur " + described.documentCount + " documents";
  if (filtered) counter += " (filtré)";
  if (described.sampled) {
    counter += " — échantillon de " + described.pointCount + " sur " + described.candidateCount
      + " documents de nature " + described.kinds.join(", ") + " (graine " + described.seed + ")";
  } else {
    counter += " — natures projetées : " + described.kinds.join(", ");
  }
  if (map.status.state === "PERIMEE") counter += " — CARTE PÉRIMÉE";
  $("mapCounter").textContent = counter + ".";
  $("mapMethod").textContent = "Méthode : " + described.method.toUpperCase() + " (version " + described.methodVersion
    + ") · graine " + described.seed + " · variance expliquée : axe 1 " + percent(described.explainedVariance[0])
    + ", axe 2 " + percent(described.explainedVariance[1]) + ".";
  const counts = {};
  for (const point of visible) counts[point.kind] = (counts[point.kind] || 0) + 1;
  for (const kind of Object.keys(counts).sort()) {
    $("mapLegend").appendChild(el("li", kind + " — " + counts[kind] + " points", "kind-" + kind));
  }
}

function clearMap() {
  map.status = null;
  map.points = [];
  map.hover = null;
  map.pendingHover = null;
  $("mapShowStale").hidden = true;
  mapMessage("");
  renderMapTexts();
}

// Whether the points of a projection in this state are read now; the page says why when they are not.
function projectionReadable(status) {
  if (status.state !== "PRETE") mapMessage(PROJECTION_HELP[status.state] || status.state);
  if (status.state === "PERIMEE" && !map.showStale) {
    $("mapShowStale").hidden = false;
    return false;
  }
  return status.state === "PRETE" || status.state === "PERIMEE";
}

function showMapPoints(points) {
  map.points = points;
  mapGrid();
  resetMapView();
  renderMapTexts();
  markRowsOnMap();
  drawMap();
}

async function loadMap() {
  if (!map.open || !project) return;
  const number = ++map.request;
  clearMap();
  try {
    const status = await api(projectPath() + "/projection");
    if (number !== map.request) return;
    map.status = status;
    if (!projectionReadable(status)) return;
    const points = await readMapPoints(number, status.state);
    if (points !== null) showMapPoints(points);
  } catch (error) {
    report(error);
  }
}

// Every point of the projection, page after page; null when another reading of the map started meanwhile.
async function readMapPoints(number, state) {
  const points = [];
  let total = 1;
  while (points.length < total) {
    const page = await api(projectPath() + "/projection/points?offset=" + points.length + "&limit=" + MAP_PAGE);
    if (number !== map.request) return null;
    if (page.state !== state || page.points.length === 0) break;
    total = page.total;
    for (const point of page.points) points.push(point);
  }
  return points;
}

function resetMapView() {
  map.scale = 1;
  map.offsetX = 0;
  map.offsetY = 0;
}

function mapGeometry() {
  const canvas = $("mapCanvas");
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  const half = Math.max(10, Math.min(width, height) / 2 - 12) * map.scale;
  return { canvas: canvas, width: width, height: height, half: half,
    originX: width / 2 + map.offsetX, originY: height / 2 + map.offsetY };
}

// Asks for a drawing; every request before the next frame is served by one drawing.
function drawMap() {
  map.dirty = true;
  requestMapFrame();
}

function requestMapFrame() {
  if (map.frame) return;
  map.frame = window.requestAnimationFrame(runMapFrame);
}

// One frame: the last pointer position received since the previous frame is looked up once, then the map is
// drawn if anything asked for it (including a changed hovered point).
function runMapFrame() {
  map.frame = 0;
  if (map.pendingHover) {
    const position = map.pendingHover;
    map.pendingHover = null;
    showHover(pointAt(position));
  }
  if (!map.dirty) return;
  map.dirty = false;
  paintMap();
}

function paintMap() {
  const geometry = mapGeometry();
  const canvas = geometry.canvas;
  if (canvas.hidden || geometry.width === 0) return;
  const ratio = window.devicePixelRatio || 1;
  if (canvas.width !== Math.round(geometry.width * ratio) || canvas.height !== Math.round(geometry.height * ratio)) {
    canvas.width = Math.round(geometry.width * ratio);
    canvas.height = Math.round(geometry.height * ratio);
  }
  const context = canvas.getContext("2d");
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  context.clearRect(0, 0, geometry.width, geometry.height);
  const visible = visiblePoints();
  drawPlainPoints(context, geometry, visible);
  drawMarkedPoints(context, geometry, visible);
}

// The points no search ranked, one colour per kind; they are dimmed while a search highlights others.
function drawPlainPoints(context, geometry, visible) {
  const highlighted = map.highlights.size > 0;
  const size = visible.length > 20000 ? 2 : 3;
  context.globalAlpha = highlighted ? 0.18 : 0.8;
  for (const kind of Object.keys(KIND_COLOR)) {
    context.fillStyle = KIND_COLOR[kind];
    for (const point of visible) {
      if (point.kind !== kind || map.highlights.has(point.stableKey)) continue;
      context.fillRect(geometry.originX + point.x * geometry.half - size / 2,
        geometry.originY - point.y * geometry.half - size / 2, size, size);
    }
  }
  context.globalAlpha = 1;
}

// The points a search ranked, larger, then a frame around the selected and the hovered ones.
function drawMarkedPoints(context, geometry, visible) {
  for (const point of visible) {
    const marked = map.highlights.has(point.stableKey);
    const chosen = point.stableKey === selectedKey || point === map.hover;
    if (!marked && !chosen) continue;
    const x = geometry.originX + point.x * geometry.half;
    const y = geometry.originY - point.y * geometry.half;
    if (marked) {
      context.fillStyle = KIND_COLOR[point.kind] || "#e4e8ee";
      context.fillRect(x - 3, y - 3, 6, 6);
    }
    if (chosen) {
      context.strokeStyle = "#e4e8ee";
      context.lineWidth = point.stableKey === selectedKey ? 2 : 1;
      context.strokeRect(x - 6, y - 6, 12, 12);
    }
  }
}

function pointAt(event) {
  const geometry = mapGeometry();
  const bounds = geometry.canvas.getBoundingClientRect();
  const x = event.clientX - bounds.left;
  const y = event.clientY - bounds.top;
  return searchPointGrid(mapGrid(), geometry, x, y);
}

function zoomMap(factor, x, y) {
  const geometry = mapGeometry();
  const next = Math.min(200, Math.max(0.5, map.scale * factor));
  const applied = next / map.scale;
  map.offsetX = x - (x - geometry.originX) * applied - geometry.width / 2;
  map.offsetY = y - (y - geometry.originY) * applied - geometry.height / 2;
  map.scale = next;
  drawMap();
}

// Which lines of the table are on the map: a search hit outside the sample or of a hidden kind says so.
function markRowsOnMap() {
  const shown = map.open && map.points.length > 0;
  const onMap = new Set(shown ? visiblePoints().map(point => point.stableKey) : []);
  map.highlights = new Set();
  for (const line of $("tbody").children) {
    const cell = line.querySelector("td.onmap");
    if (!cell) continue;
    const present = onMap.has(line.dataset.key);
    cell.textContent = !shown ? "—" : present ? "sur la carte" : "hors carte";
    if (present && view === "search") map.highlights.add(line.dataset.key);
  }
  drawMap();
}

function dragMap(event) {
  const dx = event.clientX - map.drag.x;
  const dy = event.clientY - map.drag.y;
  if (Math.abs(dx) + Math.abs(dy) > 3) map.drag.moved = true;
  map.offsetX += dx;
  map.offsetY += dy;
  map.drag.x = event.clientX;
  map.drag.y = event.clientY;
  drawMap();
}

// A pointer move only records its position; the frame looks the point up (see runMapFrame).
function hoverMap(event) {
  map.pendingHover = { clientX: event.clientX, clientY: event.clientY };
  requestMapFrame();
}

// Called by the frame, which draws right after: a changed point only marks the map as to be drawn.
function showHover(point) {
  if (point === map.hover) return;
  map.hover = point;
  const text = point ? point.kind + " · " + point.stableKey : "";
  $("mapHover").textContent = text;
  $("mapCanvas").title = text;
  map.dirty = true;
}

function zoomMapAtCentre(factor) {
  const geometry = mapGeometry();
  zoomMap(factor, geometry.width / 2, geometry.height / 2);
}

const MAP_KEY_STEP = 40;
const MAP_KEYS = {
  "+": () => zoomMapAtCentre(1.2),
  "=": () => zoomMapAtCentre(1.2),
  "-": () => zoomMapAtCentre(1 / 1.2),
  "0": resetMapView,
  ArrowLeft: () => { map.offsetX += MAP_KEY_STEP; },
  ArrowRight: () => { map.offsetX -= MAP_KEY_STEP; },
  ArrowUp: () => { map.offsetY += MAP_KEY_STEP; },
  ArrowDown: () => { map.offsetY -= MAP_KEY_STEP; }
};

function toggleMap() {
  map.open = !map.open;
  $("map").hidden = !map.open;
  $("mapToggle").setAttribute("aria-pressed", String(map.open));
  if (map.open) loadMap(); else markRowsOnMap();
}

function startMap() {
  const canvas = $("mapCanvas");
  $("mapToggle").addEventListener("click", toggleMap);
  $("mapShowStale").addEventListener("click", () => { map.showStale = true; loadMap(); });
  $("kind").addEventListener("change", () => { renderMapTexts(); markRowsOnMap(); });
  canvas.addEventListener("wheel", event => {
    event.preventDefault();
    const bounds = canvas.getBoundingClientRect();
    zoomMap(event.deltaY < 0 ? 1.2 : 1 / 1.2, event.clientX - bounds.left, event.clientY - bounds.top);
  }, { passive: false });
  canvas.addEventListener("pointerdown", event => {
    map.drag = { x: event.clientX, y: event.clientY, moved: false };
    canvas.setPointerCapture(event.pointerId);
  });
  canvas.addEventListener("pointermove", event => (map.drag ? dragMap(event) : hoverMap(event)));
  canvas.addEventListener("pointerup", event => {
    const moved = map.drag && map.drag.moved;
    map.drag = null;
    if (moved) return;
    const point = pointAt(event);
    if (point) { showDetail(point.stableKey); drawMap(); }
  });
  canvas.addEventListener("pointercancel", () => { map.drag = null; });
  canvas.addEventListener("dblclick", () => { resetMapView(); drawMap(); });
  canvas.addEventListener("keydown", event => {
    const action = MAP_KEYS[event.key];
    if (!action) return;
    action();
    drawMap();
    event.preventDefault();
  });
  window.addEventListener("resize", drawMap);
}
