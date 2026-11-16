// Test harness of the explorer page: loads the real app.js into a node:vm context with a minimal fake DOM.
// Nothing is exported by app.js and nothing is changed in it. Function declarations become properties of the
// context; the let/const state is read and written by expressions evaluated in the same context.
//
// The fake DOM refuses every way to write markup (innerHTML, outerHTML, insertAdjacentHTML, document.write,
// createContextualFragment, DOMParser, srcdoc, an on* or srcdoc attribute), records the attempt and throws. The context refuses code
// generation from strings (eval, new Function). A rendering that reaches a sink fails whatever the sink is named.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const WEB = new URL("../../main/resources/com/minos/explorer/web/", import.meta.url);
export const APP_JS = fileURLToPath(new URL("app.js", WEB));
export const INDEX_HTML = fileURLToPath(new URL("index.html", WEB));
export const HOSTILE = "<script>alert(1)</script>";
export const TOKEN = "0123456789abcdef".repeat(4);

const ELEMENT_WITH_ID = /<([a-zA-Z0-9]+)\b([^>]*?)\sid="([^"]+)"([^>]*)>/g;

class FakeNode {
  constructor(page, tagName) {
    this.page = page;
    this.tagName = tagName.toUpperCase();
    this.children = [];
    this.parentNode = null;
    this.listeners = {};
    this.dataset = {};
    this.attributes = {};
    this.ownText = "";
    this.className = "";
    this.hidden = false;
    this.disabled = false;
    this.value = "";
    this.id = "";
    this.type = "";
    this.tabIndex = -1;
    this.selected = false;
    this.titleText = "";
  }

  get firstChild() { return this.children[0] || null; }

  appendChild(child) {
    if (child.parentNode) child.parentNode.removeChild(child);
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  removeChild(child) {
    const index = this.children.indexOf(child);
    if (index < 0) throw new Error("not a child");
    this.children.splice(index, 1);
    child.parentNode = null;
    return child;
  }

  get textContent() { return this.ownText + this.children.map(child => child.textContent).join(""); }

  set textContent(value) {
    this.children = [];
    this.ownText = String(value);
    this.page.log.texts.push(this.ownText);
  }

  get title() { return this.titleText; }

  set title(value) {
    this.titleText = String(value);
    this.page.log.attributes.push(["title", this.titleText]);
  }

  set innerHTML(value) { this.page.refuseMarkup("innerHTML", value); }
  set outerHTML(value) { this.page.refuseMarkup("outerHTML", value); }
  set srcdoc(value) { this.page.refuseMarkup("srcdoc", value); }
  insertAdjacentHTML(position, value) { this.page.refuseMarkup("insertAdjacentHTML", value); }

  setAttribute(name, value) {
    const lower = String(name).toLowerCase();
    if (lower.startsWith("on") || lower === "srcdoc") this.page.refuseMarkup("setAttribute(" + name + ")", value);
    this.attributes[name] = String(value);
    this.page.log.attributes.push([name, String(value)]);
  }

  getAttribute(name) { return name in this.attributes ? this.attributes[name] : null; }

  addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }

  dispatch(type, event = {}) {
    const full = { preventDefault() {}, ...event, type, target: this };
    return (this.listeners[type] || []).map(listener => listener(full));
  }

  *descendants() {
    for (const child of this.children) {
      yield child;
      yield* child.descendants();
    }
  }

  querySelector(selector) {
    const byId = /^#([\w-]+)$/.exec(selector);
    const byTagClass = /^([a-z]+)\.([\w-]+)$/.exec(selector);
    for (const node of this.descendants()) {
      if (byId && node.id === byId[1]) return node;
      if (byTagClass && node.tagName === byTagClass[1].toUpperCase()
        && node.className.split(/\s+/).includes(byTagClass[2])) return node;
    }
    return null;
  }

  // Canvas part: the size of the element, a recording 2D context.
  getContext(kind) {
    if (kind !== "2d") throw new Error("only the 2D context is faked");
    return this.page.canvas;
  }

  getBoundingClientRect() { return { left: 0, top: 0, width: this.clientWidth, height: this.clientHeight }; }

  setPointerCapture() {}
}

function recordingContext() {
  const calls = [];
  const context = {
    calls,
    fillStyle: "", strokeStyle: "", lineWidth: 1, globalAlpha: 1,
    setTransform(...values) { calls.push(["setTransform", ...values]); },
    clearRect(...values) { calls.push(["clearRect", ...values]); },
    fillRect(x, y, width, height) {
      calls.push(["fillRect", x, y, width, height, context.fillStyle, context.globalAlpha]);
    },
    strokeRect(x, y, width, height) { calls.push(["strokeRect", x, y, width, height, context.lineWidth]); }
  };
  return context;
}

function memoryStorage(refused) {
  const values = new Map();
  if (refused) {
    const error = new Error("refused");
    error.name = "SecurityError";
    return { getItem() { throw error; }, setItem() { throw error; }, removeItem() { throw error; } };
  }
  return {
    values,
    getItem: key => (values.has(key) ? values.get(key) : null),
    setItem: (key, value) => { values.set(key, String(value)); },
    removeItem: key => { values.delete(key); }
  };
}

// Answers of the fake API: a list of [method, regular expression on the path after "api/v1/", answer].
// An answer is { status, body } or a function (match, request) returning one; no route answers 404.
export function routes(table) {
  return (url, options) => {
    const path = url.startsWith("api/v1/") ? url.slice("api/v1/".length) : url;
    for (const [method, pattern, answer] of table) {
      const match = pattern.exec(path);
      if (method === options.method && match) {
        return typeof answer === "function" ? answer(match, { url, options, path }) : answer;
      }
    }
    return { status: 404, body: { error: { code: "NOT_FOUND", message: "no route " + path } } };
  };
}

export function createPage(options = {}) {
  const {
    hash = "", api = routes([]), clipboard = undefined, storageRefused = false,
    canvasWidth = 800, canvasHeight = 600
  } = options;
  const page = {
    log: { markup: [], texts: [], attributes: [], requests: [], frames: 0, replaced: [] },
    elements: {},
    frames: [],
    canvas: recordingContext(),
    refuseMarkup(sink, value) {
      page.log.markup.push([sink, String(value)]);
      throw new Error("markup sink refused by the test DOM: " + sink);
    }
  };

  const html = readFileSync(INDEX_HTML, "utf8");
  for (const match of html.matchAll(ELEMENT_WITH_ID)) {
    const [, tag, before, id, after] = match;
    const node = new FakeNode(page, tag);
    node.id = id;
    node.hidden = /\shidden(\s|$|\/)/.test(" " + before + " " + after + " ");
    page.elements[id] = node;
  }
  page.elements.mode.value = "semantic";
  const canvas = page.elements.mapCanvas;
  canvas.clientWidth = canvasWidth;
  canvas.clientHeight = canvasHeight;
  canvas.width = 300;
  canvas.height = 150;

  const document = {
    getElementById: id => page.elements[id] || null,
    createElement: tag => new FakeNode(page, tag),
    write: value => page.refuseMarkup("document.write", value),
    writeln: value => page.refuseMarkup("document.writeln", value),
    createRange: () => ({ createContextualFragment: value => page.refuseMarkup("createContextualFragment", value) })
  };
  const location = { hash, pathname: "/" };
  const window = {
    location,
    history: { replaceState: (state, title, url) => { page.log.replaced.push(url); location.hash = ""; } },
    sessionStorage: memoryStorage(storageRefused),
    devicePixelRatio: 1,
    listeners: {},
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); },
    requestAnimationFrame(callback) {
      page.log.frames += 1;
      page.frames.push(callback);
      return page.log.frames;
    }
  };
  function DOMParser() { page.refuseMarkup("DOMParser", ""); }
  const fetch = async (url, init) => {
    page.log.requests.push({ url, method: init.method, headers: { ...init.headers }, body: init.body });
    const answer = await api(url, init);
    const status = answer.status || 200;
    return { status, ok: status >= 200 && status < 300, json: async () => answer.body };
  };
  const sandbox = {
    document, window, fetch, DOMParser, URLSearchParams, console,
    navigator: clipboard === undefined ? {} : { clipboard }
  };
  page.context = vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
  page.$ = id => page.elements[id];
  page.evaluate = expression => vm.runInContext(expression, page.context);
  page.assign = (name, value) => {
    page.context.__harnessValue = value;
    vm.runInContext(name + " = __harnessValue", page.context);
    delete page.context.__harnessValue;
  };
  page.flushFrames = () => {
    const pending = page.frames.splice(0);
    for (const callback of pending) callback(0);
    return pending.length;
  };
  vm.runInContext(readFileSync(APP_JS, "utf8"), page.context, { filename: APP_JS });
  return page;
}

// Lets the promises of the page (fake fetch, awaits) run to completion.
export async function settle(rounds = 30) {
  for (let round = 0; round < rounds; round += 1) await new Promise(resolve => setImmediate(resolve));
}

export function findButton(container, text) {
  for (const node of container.descendants()) if (node.tagName === "BUTTON" && node.textContent === text) return node;
  return null;
}

// Deterministic generator (mulberry32) for the point sets.
export function seeded(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

export const KINDS = ["CHUNK", "SYMBOL", "FILE"];

export function pointSet(seed, count) {
  const random = seeded(seed);
  const points = [];
  for (let index = 0; index < count; index += 1) {
    points.push({ stableKey: "k" + index, kind: KINDS[Math.floor(random() * 3)], x: random() * 2 - 1, y: random() * 2 - 1 });
  }
  return points;
}

// Points whose abscissa is a getter that counts its reads: the reads of one lookup are the points it examined,
// whatever the way the page searches; `counter.byKind` splits them by kind. The caller resets the counter.
class CountingPoint {
  constructor(plain, counter) {
    this.stableKey = plain.stableKey;
    this.kind = plain.kind;
    this.counter = counter;
    this.abscissa = plain.x;
    this.y = plain.y;
  }

  get x() {
    this.counter.reads += 1;
    this.counter.byKind[this.kind] = (this.counter.byKind[this.kind] || 0) + 1;
    return this.abscissa;
  }
}

export function countingPoints(plain) {
  const counter = { reads: 0, byKind: {} };
  return { counter, points: plain.map(point => new CountingPoint(point, counter)) };
}

// A project and documents as the v1 contract returns them.
export function project(overrides = {}) {
  return {
    projectId: "p1", name: "demo", indexState: "READY", providerId: "local", modelId: "hash", dimensions: 8,
    indexedSnapshotId: "s1", activeSnapshotId: "s1", documentCount: 1, limitations: [], ...overrides
  };
}

export function documentOf(overrides = {}) {
  return {
    stableKey: "key-1", fileId: "src/A.java", kind: "CHUNK", moduleId: "core", language: "java",
    startLine: 3, endLine: 7, symbol: { qualifiedName: "com.example.A", name: "A" }, contentChecksum: "c1",
    ...overrides
  };
}

export function detailOf(document, overrides = {}) {
  return {
    document, indexedContent: "class A {}", divergence: "IDENTIQUE",
    currentSource: { fileId: document.fileId, startLine: 3, endLine: 7, truncated: false, content: "class A {}" },
    index: { providerId: "local", modelId: "hash", dimensions: 8, snapshotId: "s1" }, ...overrides
  };
}

export function listOf(documents, overrides = {}) {
  return { documents, nextCursor: null, totalDocuments: documents.length, limitations: [], index: {}, ...overrides };
}
