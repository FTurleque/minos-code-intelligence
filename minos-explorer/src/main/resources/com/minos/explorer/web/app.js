"use strict";
// MINOS explorer: read-only page. Every value that comes from the index is inserted as text, never as markup,
// and every request goes to the origin that served this page.

const $ = id => document.getElementById(id);
const el = (tag, text, className) => {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = String(text);
  if (className) node.className = className;
  return node;
};
const clear = node => { while (node.firstChild) node.removeChild(node.firstChild); };

const TOKEN_KEY = "minos-explorer-token";
const API = "api/v1/";
const STATE_HELP = {
  DISABLED: "La couche sémantique est désactivée : aucun provider d'embeddings n'est configuré. Renseignez les clés minos.semantic.* puis lancez « minos ide semantic-index-sync <projet> ».",
  MISSING: "Ce projet n'a pas d'index sémantique. Lancez « minos ide semantic-index-sync <projet> ».",
  NO_ACTIVE_SNAPSHOT: "Ce projet n'a pas de snapshot actif. Indexez-le avec « minos index <projet> ».",
  STALE: "L'index sémantique est obsolète : la liste, le détail et les voisins restent consultables, la recherche est refusée. Lancez « minos ide semantic-index-sync <projet> »."
};
const STALE_CAUSES = {
  SEMANTIC_INDEX_SNAPSHOT_STALE: "le snapshot actif a changé depuis l'indexation sémantique",
  SEMANTIC_INDEX_MODEL_STALE: "le modèle configuré n'est plus celui de l'index"
};
const KIND_ORIGIN = {
  SYMBOL: "texte de synthèse du symbole",
  CHUNK: "extrait lu à l'indexation",
  FILE: "résumé du fichier"
};
const DIVERGENCE = {
  IDENTIQUE: "La source actuelle est identique au contenu indexé.",
  DIFFERENTE: "La source a changé depuis l'indexation.",
  SOURCE_INTROUVABLE: "Source introuvable : le fichier est absent ou illisible. Le contenu indexé est conservé.",
  NON_COMPARABLE: "Le contenu indexé est une synthèse : il ne se compare pas à la source."
};

let token = null;
let projects = [];
let project = null;
let view = "list";          // "list" or "search"
let nextCursor = null;
let selectedKey = null;
let requestNumber = 0;
let tabStorageRefused = null;

// The tab storage keeps the token across a reload of the page. A browser may refuse it (private mode,
// storage disabled): the page then works until it is reloaded, and says so instead of failing later.
function tabStorage(operation) {
  try {
    return operation(window.sessionStorage);
  } catch (unavailable) {
    tabStorageRefused = unavailable.name || "storage unavailable";
    return null;
  }
}

function readToken() {
  const match = /^#token=([0-9a-f]{64})$/.exec(window.location.hash);
  if (match) {
    token = match[1];
    tabStorage(storage => storage.setItem(TOKEN_KEY, token));
    window.history.replaceState(null, "", window.location.pathname);
    return;
  }
  token = tabStorage(storage => storage.getItem(TOKEN_KEY));
}

class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function api(path, body) {
  const options = {
    method: body === undefined ? "GET" : "POST",
    headers: { Authorization: "Bearer " + token },
    cache: "no-store",
    credentials: "omit",
    redirect: "error",
    referrerPolicy: "no-referrer"
  };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(API + path, options);
  if (response.status === 401 || response.status === 403) {
    refuse();
    throw new ApiError(response.status, "REFUSED", "Accès refusé.");
  }
  let payload = null;
  try { payload = await response.json(); } catch (ignored) { payload = null; }
  if (!response.ok) {
    const error = payload && payload.error ? payload.error : {};
    throw new ApiError(response.status, error.code || "ERROR", error.message || "La requête a échoué.");
  }
  return payload;
}

function refuse() {
  token = null;
  tabStorage(storage => storage.removeItem(TOKEN_KEY));
  $("explorer").hidden = true;
  $("banner").hidden = true;
  $("refused").hidden = false;
}

function banner(text) {
  $("banner").textContent = text || "";
  $("banner").hidden = !text;
}

function report(error) {
  if (error instanceof ApiError && error.code === "REFUSED") return;
  if (error instanceof ApiError && error.status === 409) {
    banner("L'index a changé pendant la consultation : les résultats affichés ont été écartés. La lecture reprend à la première page.");
    loadProjects();
    return;
  }
  banner(error instanceof ApiError ? error.message : "La surface d'exploration ne répond pas. Vérifiez que « minos explorer serve » est toujours lancé.");
}

function shortId(value) {
  if (!value) return "—";
  return value.length > 14 ? value.slice(0, 12) + "…" : value;
}

function lines(document) {
  return document.startLine === 0 && document.endLine === 0 ? "—" : document.startLine + "–" + document.endLine;
}

function symbolName(document) {
  if (document.symbol) return document.symbol.qualifiedName || document.symbol.name;
  return document.kind === "FILE" ? "—" : "non résolu";
}

function filters() {
  const values = {};
  for (const name of ["kind", "module", "language", "filePrefix"]) {
    const value = $(name).value.trim();
    if (value) values[name] = value;
  }
  return values;
}

function projectPath() {
  return "projects/" + encodeURIComponent(project.projectId);
}

function renderHeader() {
  const select = $("project");
  clear(select);
  for (const candidate of projects) {
    const option = el("option", candidate.name);
    option.value = candidate.projectId;
    option.selected = project && candidate.projectId === project.projectId;
    select.appendChild(option);
  }
  select.disabled = projects.length === 0;
  const state = $("state");
  state.textContent = project ? "index : " + project.indexState : "";
  state.className = "badge " + (!project ? "" : project.indexState === "READY" ? "ok" : project.indexState === "STALE" ? "warn" : "bad");
  $("model").textContent = project && project.providerId
    ? project.providerId + " · " + project.modelId + " · " + project.dimensions + " dimensions" : "";
  $("snapshots").textContent = project
    ? "snapshot indexé : " + shortId(project.indexedSnapshotId) + " · actif : " + shortId(project.activeSnapshotId) : "";
  $("count").textContent = project ? project.documentCount + " documents" : "";
  $("localHash").hidden = !(project && project.limitations.includes("LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL"));
}

function stateBanner() {
  const storageNote = tabStorageRefused
    ? "Ce navigateur refuse le stockage de l'onglet (" + tabStorageRefused + ") : recharger la page demandera de rouvrir l'adresse imprimée par « minos explorer serve »."
    : "";
  if (!project) return storageNote;
  let text = STATE_HELP[project.indexState] || "";
  if (storageNote) text = text ? text + "\n" + storageNote : storageNote;
  if (project.indexState === "STALE") {
    const causes = project.limitations.filter(name => STALE_CAUSES[name]).map(name => STALE_CAUSES[name]);
    if (causes.length) text += "\nCause : " + causes.join(" ; ") + ".";
  }
  return text;
}

function renderLimitations(limitations) {
  const list = $("limits");
  clear(list);
  for (const limitation of limitations || []) list.appendChild(el("li", limitation));
}

function renderColumns(columns) {
  const head = $("thead");
  clear(head);
  for (const column of columns) head.appendChild(el("th", column));
}

function row(document, cells) {
  const line = el("tr");
  line.tabIndex = 0;
  line.dataset.key = document.stableKey;
  if (document.stableKey === selectedKey) line.className = "selected";
  for (const cell of cells) {
    const cellNode = el("td", cell.text, cell.number ? "number" : cell.className || "");
    cellNode.title = String(cell.text);
    line.appendChild(cellNode);
  }
  const open = () => showDetail(document.stableKey);
  line.addEventListener("click", open);
  line.addEventListener("keydown", event => {
    if (event.key === "Enter" || event.key === " ") { event.preventDefault(); open(); }
  });
  return line;
}

function documentCells(document) {
  return [
    { text: document.fileId || "—" },
    { text: symbolName(document) },
    { text: document.kind },
    { text: document.moduleId || (document.symbol || document.language ? "—" : "non résolu") },
    { text: document.language || "non résolu" },
    { text: lines(document) }
  ];
}

function showEmpty(text) {
  $("empty").textContent = text || "";
  $("empty").hidden = !text;
}

async function loadProjects() {
  const number = ++requestNumber;
  try {
    const answer = await api("projects");
    if (number !== requestNumber) return;
    projects = answer.projects;
    const previous = project ? project.projectId : null;
    project = projects.find(candidate => candidate.projectId === previous) || projects[0] || null;
    renderHeader();
    if (!project) {
      clear($("tbody"));
      renderColumns([]);
      $("tableTitle").textContent = "";
      $("more").hidden = true;
      showEmpty("Aucun projet n'est inscrit. Inscrivez-en un avec « minos project add <dossier> », indexez-le, puis rechargez cette page.");
      return;
    }
    if ($("banner").hidden) banner(stateBanner());
    await loadDocuments(true);
  } catch (error) {
    report(error);
  }
}

async function loadDocuments(first) {
  const number = ++requestNumber;
  view = "list";
  if (first) {
    nextCursor = null;
    clear($("tbody"));
    renderLimitations([]);
    $("scoreNote").textContent = "";
  }
  try {
    const parameters = new URLSearchParams(filters());
    if (nextCursor) parameters.set("cursor", nextCursor);
    const page = await api(projectPath() + "/documents?" + parameters.toString());
    if (number !== requestNumber) return;
    renderColumns(["Fichier", "Symbole", "Nature", "Module", "Langage", "Lignes"]);
    const body = $("tbody");
    for (const document of page.documents) body.appendChild(row(document, documentCells(document)));
    nextCursor = page.nextCursor;
    $("more").hidden = !nextCursor;
    const filtered = Object.keys(filters()).length > 0;
    $("tableTitle").textContent = body.children.length + " documents affichés sur " + page.totalDocuments
      + " dans l'index" + (filtered ? " (filtrés)" : "") + ", par clé stable.";
    $("filterNote").textContent = page.limitations.includes("SEMANTIC_DOCUMENT_SYMBOLS_UNRESOLVED_INDEX_NOT_ALIGNED")
      ? "Module et langage ne sont pas résolus : l'index n'est pas aligné sur le snapshot actif. Un filtre sur ces champs ne retient aucun document."
      : "";
    renderLimitations(page.limitations);
    markRowsOnMap();
    if (body.children.length === 0) {
      showEmpty(page.index === null ? "Aucun index sémantique à lister pour ce projet."
        : page.totalDocuments === 0 ? "L'index existe et ne contient aucun document. Vérifiez l'indexation du projet."
        : nextCursor ? "Aucun document ne correspond aux filtres dans la partie lue ; « Page suivante » poursuit la lecture."
        : "Aucun document ne correspond aux filtres. Élargissez-les.");
    } else {
      showEmpty("");
    }
  } catch (error) {
    report(error);
  }
}

// Why a search shows no line, or an empty text when it shows some.
function emptySearchMessage(result) {
  if (result.hits.length > 0) return "";
  if (result.limitations.includes("SEMANTIC_SEARCH_REQUIRES_READY_INDEX")) {
    return "La recherche exige un index prêt (READY). La liste reste consultable.";
  }
  if (result.limitations.includes("SEMANTIC_INDEX_CHANGED_DURING_QUERY")) {
    return "L'index a changé pendant la recherche. Relancez-la.";
  }
  return result.rankedCount > 0 ? "Aucun résultat ne correspond aux filtres actifs. Élargissez-les." : "Aucun résultat.";
}

async function search() {
  const query = $("query").value.trim();
  if (!query || !project) return;
  const number = ++requestNumber;
  view = "search";
  const hybrid = $("mode").value === "hybrid";
  banner(stateBanner());
  try {
    const request = Object.assign({ query: query, mode: $("mode").value, limit: 50, minimumScore: hybrid ? 0 : -1 }, filters());
    const result = await api(projectPath() + "/search", request);
    if (number !== requestNumber) return;
    renderColumns(["Rang", hybrid ? "Score hybride (0 à 1)" : "Similarité cosinus (−1 à 1)", "Signaux", "Fichier", "Symbole", "Nature", "Lignes", "Carte"]);
    const body = $("tbody");
    clear(body);
    for (const hit of result.hits) {
      const signals = hit.signals.map(signal => signal.type.toLowerCase() + " " + signal.score.toFixed(4)).join(" · ");
      body.appendChild(row(hit.document, [
        { text: hit.rank, number: true },
        { text: hit.score.toFixed(4), number: true },
        { text: signals },
        { text: hit.document.fileId || "—" },
        { text: symbolName(hit.document) },
        { text: hit.document.kind },
        { text: lines(hit.document) },
        { text: "—", className: "onmap" }
      ]));
    }
    markRowsOnMap();
    $("more").hidden = true;
    $("tableTitle").textContent = result.hits.length + " résultats affichés sur " + result.rankedCount + " classés.";
    $("scoreNote").textContent = hybrid
      ? "Score hybride : sémantique 50 %, lexical 35 %, graphe 15 %. Signal heuristique, pas un fait structurel."
      : "Similarité cosinus exacte avec le vecteur de la requête. Signal heuristique, pas un fait structurel.";
    renderLimitations(result.limitations);
    showEmpty(emptySearchMessage(result));
  } catch (error) {
    if (error instanceof ApiError && error.status === 500) {
      banner("La recherche a échoué : le provider d'embeddings est peut-être indisponible. La liste, le détail et les voisins restent disponibles.");
      return;
    }
    report(error);
  }
}

function definition(list, term, value) {
  list.appendChild(el("dt", term));
  list.appendChild(el("dd", value === null || value === undefined || value === "" ? "—" : value));
}

async function showDetail(stableKey) {
  const number = ++requestNumber;
  selectedKey = stableKey;
  for (const line of $("tbody").children) line.className = line.dataset.key === stableKey ? "selected" : "";
  drawMap();
  const panel = $("detail");
  try {
    const detail = await api(projectPath() + "/documents/" + encodeURIComponent(stableKey));
    if (number !== requestNumber) return;
    const document = detail.document;
    clear(panel);
    panel.appendChild(el("h2", "Identité"));
    const identity = el("dl");
    definition(identity, "Fichier", document.fileId);
    definition(identity, "Lignes", lines(document));
    definition(identity, "Symbole", symbolName(document));
    definition(identity, "Nature", document.kind);
    definition(identity, "Module", document.moduleId);
    definition(identity, "Langage", document.language);
    definition(identity, "Clé stable", document.stableKey);
    definition(identity, "Empreinte", document.contentChecksum);
    panel.appendChild(identity);

    panel.appendChild(el("h2", "Contenu indexé — " + (KIND_ORIGIN[document.kind] || document.kind)));
    panel.appendChild(el("pre", detail.indexedContent));

    panel.appendChild(el("h2", "Source actuelle"));
    const divergence = el("p", DIVERGENCE[detail.divergence] || detail.divergence,
      detail.divergence === "DIFFERENTE" || detail.divergence === "SOURCE_INTROUVABLE" ? "warnbox" : "note");
    panel.appendChild(divergence);
    if (detail.currentSource) {
      panel.appendChild(el("p", detail.currentSource.fileId + ":" + detail.currentSource.startLine + "–"
        + detail.currentSource.endLine + (detail.currentSource.truncated ? " (extrait tronqué)" : ""), "note"));
      panel.appendChild(el("pre", detail.currentSource.content));
    }

    panel.appendChild(el("h2", "Provenance"));
    const provenance = el("dl");
    definition(provenance, "Provider", detail.index.providerId);
    definition(provenance, "Modèle", detail.index.modelId);
    definition(provenance, "Dimensions", detail.index.dimensions);
    definition(provenance, "Snapshot indexé", detail.index.snapshotId);
    panel.appendChild(provenance);

    const similar = el("button", "Trouver les documents similaires");
    similar.type = "button";
    similar.addEventListener("click", () => showNeighbors(stableKey, panel));
    panel.appendChild(similar);
    const reference = el("button", "Copier la référence");
    reference.type = "button";
    reference.disabled = !document.fileId || !navigator.clipboard;
    reference.addEventListener("click", () => {
      navigator.clipboard.writeText(document.fileId + (lines(document) === "—" ? "" : ":" + document.startLine + "-" + document.endLine));
    });
    panel.appendChild(reference);
    const neighbors = el("div");
    neighbors.id = "neighbors";
    panel.appendChild(neighbors);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      clear(panel);
      panel.appendChild(el("p", "Ce document n'est plus dans l'index. Rechargez la liste.", "warnbox"));
      return;
    }
    report(error);
  }
}

async function showNeighbors(stableKey, panel) {
  const target = panel.querySelector("#neighbors");
  try {
    const answer = await api(projectPath() + "/documents/" + encodeURIComponent(stableKey) + "/neighbors?limit=20");
    if (selectedKey !== stableKey) return;
    clear(target);
    target.appendChild(el("h2", "Documents similaires — similarité cosinus (−1 à 1)"));
    target.appendChild(el("p", "Classés depuis le vecteur stocké du document, sans appel au provider. Signal heuristique, pas un lien d'appel ni de dépendance.", "note"));
    if (answer.neighbors.length === 0) target.appendChild(el("p", "Aucun autre document dans cet index.", "note"));
    const table = el("table");
    const body = el("tbody");
    for (const neighbor of answer.neighbors) {
      body.appendChild(row(neighbor.document, [
        { text: neighbor.score.toFixed(4), number: true },
        { text: neighbor.document.kind },
        { text: (neighbor.document.fileId || "—") + (lines(neighbor.document) === "—" ? "" : ":" + lines(neighbor.document)) }
      ]));
    }
    table.appendChild(body);
    target.appendChild(table);
  } catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 409)) {
      clear(target);
      target.appendChild(el("p", "L'index a changé depuis l'affichage de ce document : l'action est refusée. Rechargez la liste.", "warnbox"));
      return;
    }
    report(error);
  }
}

// ---- 2D map. The page draws the points of a projection that was prepared by a command; it never asks for a
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
  scale: 1, offsetX: 0, offsetY: 0, hover: null, drag: null, frame: 0
};

function mapMessage(text) {
  $("mapMessage").textContent = text || "";
  $("mapMessage").hidden = !text;
}

function percent(share) {
  return (share * 100).toFixed(1).replace(".", ",") + " %";
}

function visiblePoints() {
  const kind = $("kind").value;
  return kind ? map.points.filter(point => point.kind === kind) : map.points;
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

async function loadMap() {
  if (!map.open || !project) return;
  const number = ++map.request;
  map.status = null;
  map.points = [];
  map.hover = null;
  $("mapShowStale").hidden = true;
  mapMessage("");
  renderMapTexts();
  try {
    const status = await api(projectPath() + "/projection");
    if (number !== map.request) return;
    map.status = status;
    if (status.state !== "PRETE") mapMessage(PROJECTION_HELP[status.state] || status.state);
    if (status.state === "PERIMEE" && !map.showStale) {
      $("mapShowStale").hidden = false;
      return;
    }
    if (status.state !== "PRETE" && status.state !== "PERIMEE") return;
    const points = await readMapPoints(number, status.state);
    if (points === null) return;
    map.points = points;
    resetMapView();
    renderMapTexts();
    markRowsOnMap();
    drawMap();
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

function drawMap() {
  if (map.frame) return;
  map.frame = window.requestAnimationFrame(() => {
    map.frame = 0;
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
  });
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
  let best = null;
  let bestDistance = 64;
  for (const point of visiblePoints()) {
    const dx = geometry.originX + point.x * geometry.half - x;
    const dy = geometry.originY - point.y * geometry.half - y;
    const distance = dx * dx + dy * dy;
    if (distance < bestDistance) { best = point; bestDistance = distance; }
  }
  return best;
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

function hoverMap(event) {
  const point = pointAt(event);
  if (point === map.hover) return;
  map.hover = point;
  const text = point ? point.kind + " · " + point.stableKey : "";
  $("mapHover").textContent = text;
  $("mapCanvas").title = text;
  drawMap();
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

function start() {
  readToken();
  if (!token) { refuse(); return; }
  startMap();
  $("project").addEventListener("change", () => {
    project = projects.find(candidate => candidate.projectId === $("project").value) || null;
    selectedKey = null;
    clear($("detail"));
    renderHeader();
    banner(stateBanner());
    map.showStale = false;
    loadDocuments(true);
    loadMap();
  });
  $("apply").addEventListener("click", () => (view === "search" ? search() : loadDocuments(true)));
  $("reset").addEventListener("click", () => {
    for (const name of ["kind", "module", "language", "filePrefix"]) $(name).value = "";
    if (view === "search") search(); else loadDocuments(true);
  });
  $("go").addEventListener("click", search);
  $("query").addEventListener("keydown", event => { if (event.key === "Enter") search(); });
  $("clear").addEventListener("click", () => { banner(stateBanner()); loadDocuments(true); });
  $("more").addEventListener("click", () => loadDocuments(false));
  loadProjects();
}

start();
