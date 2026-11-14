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
    const cellNode = el("td", cell.text, cell.number ? "number" : "");
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
    renderColumns(["Rang", hybrid ? "Score hybride (0 à 1)" : "Similarité cosinus (−1 à 1)", "Signaux", "Fichier", "Symbole", "Nature", "Lignes"]);
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
        { text: lines(hit.document) }
      ]));
    }
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

function start() {
  readToken();
  if (!token) { refuse(); return; }
  $("project").addEventListener("change", () => {
    project = projects.find(candidate => candidate.projectId === $("project").value) || null;
    selectedKey = null;
    clear($("detail"));
    renderHeader();
    banner(stateBanner());
    loadDocuments(true);
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
