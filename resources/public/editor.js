(async function () {
  "use strict";

  const F = window.ClojureForms;
  const $ = (id) => document.getElementById(id);

  // Each run of bb editor has a new token. When it is refused, bb editor was
  // restarted: fetch the new one and carry on, so a restart needs no reload.
  let token = null;
  async function refreshToken() {
    token = (await (await fetch("/api/token")).json()).token;
  }
  await refreshToken();

  async function api(path, opts = {}) {
    const send = () => fetch(path, {
      ...opts,
      headers: { "X-Workshop-Token": token, ...(opts.headers || {}) },
    });
    let res = await send();
    if (res.status === 403) {
      await refreshToken();
      res = await send();
    }
    return res;
  }

  function storageGet(k) { try { return localStorage.getItem(k); } catch (_) { return null; } }
  function storageSet(k, v) { try { localStorage.setItem(k, v); } catch (_) {} }

  // -------------------------------------------------------------------------
  // Editor

  const cm = CodeMirror($("editor"), {
    mode: "clojure",
    lineNumbers: true,
    matchBrackets: true,
    indentUnit: 2,
    tabSize: 2,
    indentWithTabs: false,
    // Set from the bindings below, by applyBindings.
    extraKeys: {},
  });

  let mode = storageGet("parinfer-mode") || "smart";
  $("mode").value = mode;
  let parinferReady = false;

  $("mode").addEventListener("change", (e) => {
    mode = e.target.value;
    storageSet("parinfer-mode", mode);
    parinferCodeMirror.setMode(cm, mode);
    cm.focus();
  });

  // What Ctrl/Cmd+Enter evaluates. "top" is Cursive's "send top form": the
  // top-level form, where each form inside (comment ...) counts as top
  // level. "current" is the form at the cursor. Alt+Enter does the other.
  let evalMode = storageGet("eval-mode") === "current" ? "current" : "top";
  const otherEvalMode = () => (evalMode === "top" ? "current" : "top");
  const EVAL_MODE_LABELS = { top: "the top-level form", current: "the form at the cursor" };

  $("eval-mode").value = evalMode;
  $("eval-mode").addEventListener("change", (e) => {
    evalMode = e.target.value;
    storageSet("eval-mode", evalMode);
    renderKeys();
    cm.focus();
  });

  // -------------------------------------------------------------------------
  // Commands and their keys. The Keys panel can change any binding; changes
  // are kept in this browser.

  const COMMANDS = [
    { id: "evaluate", keys: ["Ctrl-Enter", "Cmd-Enter"],
      label: () => "Evaluate " + EVAL_MODE_LABELS[evalMode],
      run: () => evalAtCursor(evalMode) },
    { id: "evaluate-other", keys: ["Alt-Enter"],
      label: () => "Evaluate " + EVAL_MODE_LABELS[otherEvalMode()],
      run: () => evalAtCursor(otherEvalMode()) },
    { id: "load-file", keys: ["Shift-Ctrl-Enter", "Shift-Cmd-Enter"],
      label: () => "Load the whole file",
      run: () => loadFile(currentPath) },
    { id: "expand", keys: ["Alt-Up"],
      label: () => "Expand the selection to the next enclosing form",
      run: () => expandRegion() },
    { id: "contract", keys: ["Alt-Down"],
      label: () => "Contract the selection back",
      run: () => contractRegion() },
    { id: "splice", keys: ["Alt-S"],
      label: () => "Splice: remove the brackets around the cursor",
      run: () => splice() },
    { id: "complete", keys: ["Ctrl-Space", "Alt-Space"],
      label: () => "Complete the name at the cursor",
      run: () => complete() },
    { id: "clear", keys: ["Esc"],
      label: () => "Clear the results shown in the editor",
      run: () => clearResults(files[currentPath]) },
    { id: "save", keys: ["Ctrl-S", "Cmd-S"],
      label: () => "Save now (files also save by themselves)",
      run: () => save(currentPath) },
  ];

  function loadBindings() {
    let saved = {};
    try { saved = JSON.parse(storageGet("key-bindings") || "{}"); } catch (_) {}
    return Object.fromEntries(COMMANDS.map((c) =>
      [c.id, Array.isArray(saved[c.id]) ? saved[c.id] : c.keys]));
  }
  let bindings = loadBindings();
  let recording = null; // the id of the command waiting for a new key
  let refused = null;   // the last key it was offered and could not take

  function applyBindings() {
    const keys = { "Tab": "indentMore", "Shift-Tab": "indentLess" };
    for (const c of COMMANDS) for (const k of bindings[c.id]) keys[k] = () => c.run();
    cm.setOption("extraKeys", keys);
    renderKeys();
  }

  const isMac = /Mac|iPhone|iPad/.test(navigator.platform);
  const KEY_SYMBOLS = { Cmd: "⌘", Alt: isMac ? "⌥" : "Alt", Up: "↑", Down: "↓", Left: "←", Right: "→" };
  function prettyKey(name) {
    return name.split("-").map((part) => KEY_SYMBOLS[part] || part).join(" ");
  }

  function renderKeys() {
    const rows = COMMANDS.map((c) => {
      const tr = document.createElement("tr");
      const what = document.createElement("td");
      what.textContent = c.label();
      const keys = document.createElement("td");
      if (recording === c.id) {
        keys.textContent = refused
          ? prettyKey(refused) + " on its own would stop that key typing. Add " +
            (isMac ? "Ctrl, ⌥ or ⌘" : "Ctrl or Alt") + "… (Esc to cancel)"
          : "Press the new keys… (Esc to cancel)";
        keys.className = "recording";
      } else {
        bindings[c.id].forEach((k, i) => {
          if (i) keys.append(" or ");
          const kbd = document.createElement("kbd");
          kbd.textContent = prettyKey(k);
          keys.append(kbd);
        });
        if (!bindings[c.id].length) keys.textContent = "not bound";
      }
      const change = document.createElement("td");
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = "Change";
      b.addEventListener("click", () => { recording = c.id; refused = null; renderKeys(); });
      change.append(b);
      tr.append(what, keys, change);
      return tr;
    });
    $("keys-table").replaceChildren(...rows);
  }

  // While recording, the next key combination becomes the command's binding.
  // The rest of that key press is swallowed too, so an Enter in it does not
  // also press whichever button has focus.
  let swallowing = false;
  for (const type of ["keypress", "keyup"]) {
    document.addEventListener(type, (e) => {
      if (!swallowing) return;
      e.preventDefault();
      e.stopPropagation();
      if (type === "keyup") swallowing = false;
    }, true);
  }

  // A key without Ctrl, Alt or Cmd would no longer type, so a fumbled A or
  // Backspace could break the editor. Function keys type nothing, so they
  // can stand alone.
  function usable(name) {
    return /(^|-)(Ctrl|Alt|Cmd)-/.test(name) || /(^|-)F\d+$/.test(name);
  }

  document.addEventListener("keydown", (e) => {
    if (!recording) return;
    e.preventDefault();
    e.stopPropagation();
    swallowing = true;
    const name = CodeMirror.keyName(e);
    // A modifier on its own: wait for the rest of the combination.
    if (!name || /(^|-)(Shift|Ctrl|Alt|Cmd|Mod)$/.test(name)) return;
    if (name !== "Esc" && !usable(name)) {
      refused = name;
      renderKeys();
      return;
    }
    if (name !== "Esc") {
      // One key does one thing: take it away from any other command.
      for (const c of COMMANDS) bindings[c.id] = bindings[c.id].filter((k) => k !== name);
      bindings[recording] = [name];
      storageSet("key-bindings", JSON.stringify(bindings));
    }
    recording = null;
    applyBindings();
  }, true);

  $("reset-keys").addEventListener("click", () => {
    bindings = Object.fromEntries(COMMANDS.map((c) => [c.id, c.keys]));
    storageSet("key-bindings", "{}");
    recording = null;
    applyBindings();
  });

  applyBindings();

  // Emacs keys. CodeMirror's Mac keymap already falls through to its
  // "emacsy" map; elsewhere it is a choice, as it takes Ctrl-A from select
  // all. Without it, Ctrl-E falls through to the browser's search. A Mac
  // can spare Ctrl-V (page down) and Ctrl-F (forward a character), but here
  // they are paste and find in page, so not those.
  const notHere = ["Ctrl-V", "Shift-Ctrl-V", "Ctrl-F"];
  const emacsy = Object.fromEntries(Object.entries(CodeMirror.keyMap.emacsy)
    .filter(([k]) => !notHere.includes(k)));
  CodeMirror.keyMap.pcEmacsy = { ...emacsy, fallthrough: "pcDefault" };
  function applyEmacsKeys(on) {
    cm.setOption("keyMap", on ? "pcEmacsy" : "default");
  }
  $("emacs-keys-option").hidden = isMac;
  $("emacs-keys").checked = !isMac && storageGet("emacs-keys") === "on";
  applyEmacsKeys($("emacs-keys").checked);
  $("emacs-keys").addEventListener("change", (e) => {
    storageSet("emacs-keys", e.target.checked ? "on" : "off");
    applyEmacsKeys(e.target.checked);
    cm.focus();
  });
  // Reset keys turns them off too: one button for back to the start.
  $("reset-keys").addEventListener("click", () => {
    $("emacs-keys").checked = false;
    storageSet("emacs-keys", "off");
    applyEmacsKeys(false);
  });

  // -------------------------------------------------------------------------
  // Structural editing

  // Expand region: each press selects the next enclosing form. Contract
  // steps back through the same selections. Moving the cursor starts over.
  let regionStack = [];
  let region = null; // the selection expand or contract last made

  function selectRegion(anchor, head) {
    cm.getDoc().setSelection(anchor, head);
    region = [cm.getDoc().getCursor("from"), cm.getDoc().getCursor("to")];
  }

  // Parinfer fires cursor events of its own, so compare selections rather
  // than counting events: the steps are kept while the selection is ours.
  cm.on("cursorActivity", () => {
    const doc = cm.getDoc();
    const same = region &&
      CodeMirror.cmpPos(doc.getCursor("from"), region[0]) === 0 &&
      CodeMirror.cmpPos(doc.getCursor("to"), region[1]) === 0;
    if (!same) { regionStack = []; region = null; }
  });

  function expandRegion() {
    const doc = cm.getDoc();
    const from = doc.indexFromPos(doc.getCursor("from"));
    const to = doc.indexFromPos(doc.getCursor("to"));
    const node = F.enclosing(F.parse(doc.getValue()), from, to);
    if (!node) return;
    regionStack.push([doc.getCursor("anchor"), doc.getCursor("head")]);
    selectRegion(doc.posFromIndex(node.start), doc.posFromIndex(node.end));
  }

  function contractRegion() {
    const previous = regionStack.pop();
    if (previous) selectRegion(previous[0], previous[1]);
  }

  // Splice, like paredit-splice-sexp: (a (b| c) d) becomes (a b| c d).
  // Parinfer runs in paren mode for the edit, so the brackets stay as edited
  // and the indentation follows them.
  function splice() {
    const doc = cm.getDoc();
    const text = doc.getValue();
    const node = F.innermostList(F.parse(text), doc.indexFromPos(doc.getCursor()));
    if (!node) return;
    // node.start can include a prefix such as #{ or '(: remove it too.
    const openEnd = text.indexOf(node.open, node.start) + 1;
    parinferCodeMirror.setMode(cm, "paren");
    cm.operation(() => {
      doc.replaceRange("", doc.posFromIndex(node.end - 1), doc.posFromIndex(node.end));
      doc.replaceRange("", doc.posFromIndex(node.start), doc.posFromIndex(openEnd));
    });
    parinferCodeMirror.setMode(cm, mode);
  }

  // -------------------------------------------------------------------------
  // Completion, on demand. The runtime lists the names in scope in the
  // file's namespace, so the file's own defs and aliases like str/ are there.
  // While the list is open, typing narrows it; Enter or Tab picks.

  const SYMBOL_CHAR = /[^\s()\[\]{}"',;`@~^\\]/;

  function symbolBefore(doc) {
    const cur = doc.getCursor();
    const line = doc.getLine(cur.line);
    let start = cur.ch;
    while (start > 0 && SYMBOL_CHAR.test(line[start - 1])) start--;
    return { from: { line: cur.line, ch: start }, to: cur, prefix: line.slice(start, cur.ch) };
  }

  function renderCompletion(el, _, c) {
    const name = document.createElement("span");
    name.className = "completion-name";
    name.textContent = c.text;
    el.append(name);
    const detail = c.arglists || (c.macro ? "macro" : "");
    if (detail) {
      const args = document.createElement("span");
      args.className = "completion-args";
      args.textContent = detail;
      el.append(args);
    }
    if (c.doc) {
      const doc = document.createElement("span");
      doc.className = "completion-doc";
      doc.textContent = c.doc;
      el.append(doc);
    }
  }

  async function complete() {
    const f = files[currentPath];
    const doc = f.doc;
    const start = symbolBefore(doc);
    if (!start.prefix) return;
    await ensureLoaded(f);
    const text = doc.getValue();
    const { ns, nsForm } = F.nsOf(F.parse(text), text);
    let candidates = [];
    try {
      const res = await api("/api/complete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prefix: start.prefix, ns, "ns-form": nsForm }),
      });
      candidates = (await res.json()).completions || [];
    } catch (_) {
      return;
    }
    cm.showHint({
      completeSingle: true,
      hint: () => {
        // Called again on each keystroke: narrow the list to what is typed now.
        const now = symbolBefore(cm.getDoc());
        if (!now.prefix.startsWith(start.prefix)) return null;
        const list = candidates
          .filter((c) => c.text.startsWith(now.prefix))
          .map((c) => ({ ...c, render: renderCompletion }));
        return list.length ? { list, from: now.from, to: now.to } : null;
      },
    });
  }

  // -------------------------------------------------------------------------
  // Files: one CodeMirror document per file, so each keeps its own undo
  // history and inline results.

  const files = {};
  let currentPath = null;

  async function openFile(path) {
    if (!files[path]) {
      const { text } = await (await api("/api/file?path=" + encodeURIComponent(path))).json();
      const doc = CodeMirror.Doc(text, "clojure");
      files[path] = { path, doc, savedText: text, results: [], loadedIn: null, saveTimer: null, saveFailed: false };
    }
    currentPath = path;
    cm.swapDoc(files[path].doc);
    if (!parinferReady) {
      parinferCodeMirror.init(cm, mode);
      parinferReady = true;
    } else {
      parinferCodeMirror.setMode(cm, mode);
    }
    location.hash = path;
    renderTabs();
    updateSaved();
    cm.focus();
  }

  cm.on("changes", () => {
    const f = files[currentPath];
    if (!f) return;
    clearTimeout(f.saveTimer);
    f.saveTimer = setTimeout(() => save(f.path), 700);
    updateSaved();
    renderTabs();
  });

  async function save(path) {
    const f = files[path];
    if (!f) return;
    clearTimeout(f.saveTimer);
    const text = f.doc.getValue();
    if (text === f.savedText) return;
    try {
      const res = await api("/api/file?path=" + encodeURIComponent(path), { method: "POST", body: text });
      if (!res.ok) throw new Error(res.status);
      f.savedText = text;
      f.saveFailed = false;
    } catch (_) {
      // The editor is gone or restarting. Keep the edits and try again.
      f.saveFailed = true;
      f.saveTimer = setTimeout(() => save(path), 2000);
    }
    updateSaved();
    renderTabs();
  }

  function dirty(f) { return f.doc.getValue() !== f.savedText; }

  function updateSaved() {
    const f = files[currentPath];
    $("saved").textContent = !f || !dirty(f) ? "Saved"
      : f.saveFailed ? "Not saved yet: waiting for bb editor"
      : "Saving…";
  }

  let fileList = [];
  function renderTabs() {
    const nav = $("tabs");
    nav.replaceChildren(...fileList.map((path) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "tab" + (path === currentPath ? " active" : "");
      b.textContent = path.replace(/^src\//, "") + (files[path] && dirty(files[path]) ? " •" : "");
      b.setAttribute("aria-current", path === currentPath ? "page" : "false");
      b.addEventListener("click", () => openFile(path));
      return b;
    }));
  }

  // -------------------------------------------------------------------------
  // Inline results

  function before(a, b) { return CodeMirror.cmpPos(a, b) < 0; }
  function overlaps(r, from, to) { return !before(r.to, from) && !before(to, r.from); }

  function clearResult(r) {
    r.cleared = true;
    r.range.clear();
    r.bookmark.clear();
    if (r.widget) r.widget.clear();
  }

  function clearResults(f) {
    if (!f) return;
    f.results.forEach(clearResult);
    f.results = [];
  }

  function addResult(f, from, to) {
    const doc = f.doc;
    f.results = f.results.filter((r) => {
      const at = r.range.find();
      if (!at || overlaps(at, from, to)) { clearResult(r); return false; }
      return true;
    });
    const el = document.createElement("span");
    el.className = "result pending";
    el.textContent = "…";
    const lineEnd = { line: to.line, ch: doc.getLine(to.line).length };
    const r = {
      el,
      range: doc.markText(from, to, { clearWhenEmpty: false }),
      bookmark: doc.setBookmark(lineEnd, { widget: el, insertLeft: true }),
      widget: null,
      cleared: false,
    };
    f.results.push(r);
    return r;
  }

  function setResult(f, r, kind, text, pretty) {
    if (r.cleared) return;
    r.el.className = "result " + kind + (pretty ? " expandable" : "");
    r.el.textContent = text;
    r.el.title = pretty ? "Click to show the whole value" : "";
    r.el.onclick = pretty ? () => toggleExpanded(f, r, pretty) : null;
  }

  function toggleExpanded(f, r, pretty) {
    if (r.widget) { r.widget.clear(); r.widget = null; return; }
    const at = r.range.find();
    if (!at) return;
    const pre = document.createElement("pre");
    pre.className = "result-expanded";
    pre.textContent = pretty;
    r.widget = f.doc.addLineWidget(at.to.line, pre, { coverGutter: false });
  }

  cm.on("change", (_, change) => {
    const f = files[currentPath];
    if (!f) return;
    f.results = f.results.filter((r) => {
      const at = r.range.find();
      if (!at || overlaps(at, change.from, change.to)) { clearResult(r); return false; }
      return true;
    });
  });

  function flash(from, to) {
    const m = cm.getDoc().markText(from, to, { className: "evaluated" });
    setTimeout(() => m.clear(), 350);
  }

  // -------------------------------------------------------------------------
  // Evaluation

  // The id of the runtime process, from the editor's status events. A new id
  // means a fresh runtime that knows nothing we evaluated before.
  let runtimeId = null;

  async function evaluate(code, ns, nsForm) {
    let res;
    try {
      res = await api("/api/eval", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code, ns, "ns-form": nsForm }),
      });
    } catch (e) {
      return { error: "The editor is not responding. Is bb editor still running?" };
    }
    if (!res.ok) return { error: `The editor refused this (${res.status}). Reload the page.` };
    return await res.json();
  }

  function oneLine(s) {
    const flat = s.replace(/\s+/g, " ");
    return flat.length > 120 ? flat.slice(0, 120) + " …" : flat;
  }

  function show(f, r, result) {
    if (result.timeout) {
      setResult(f, r, "timeout",
        `⏱ still running after ${result.seconds}s. If it never finishes, press Restart.`);
    } else if (result.error !== undefined) {
      setResult(f, r, "error", "✗ " + oneLine(result.error));
    } else {
      setResult(f, r, "value", "⇒ " + oneLine(result.value), result.pretty);
    }
  }

  // Before evaluating a form, load its file once per runtime, so the names
  // it refers to exist. After a Restart this happens again.
  // Two quick evaluations share one load. A failed load is tried again on the
  // next evaluation, so fixing the broken form is enough.
  async function ensureLoaded(f) {
    const id = runtimeId;
    if (f.loadedIn === id) return;
    if (!f.loading || f.loadingFor !== id) {
      f.loadingFor = id;
      f.loading = (async () => {
        const text = f.doc.getValue();
        const { ns, nsForm } = F.nsOf(F.parse(text), text);
        const result = await evaluate(text, ns, nsForm);
        if (result.error !== undefined || result.timeout) {
          appendOutput(`; loading ${f.path} failed: ${result.error || "it timed out"}. ` +
                       `Forms that use names defined after that point will fail until it is fixed.\n`, "note");
        } else {
          f.loadedIn = id;
        }
      })().finally(() => { f.loading = null; });
    }
    await f.loading;
  }

  async function evalAtCursor(which) {
    const f = files[currentPath];
    const doc = f.doc;
    const text = doc.getValue();
    const forms = F.parse(text);
    const { ns, nsForm } = F.nsOf(forms, text);

    let from, to;
    if (doc.somethingSelected()) {
      from = doc.getCursor("from");
      to = doc.getCursor("to");
    } else {
      const pos = doc.indexFromPos(doc.getCursor());
      const node = which === "top" ? F.topLevelForm(forms, pos) : F.currentForm(forms, pos);
      if (!node) {
        $("saved").textContent = "Put the cursor on a form";
        return;
      }
      from = doc.posFromIndex(node.start);
      to = doc.posFromIndex(node.end);
    }

    const code = doc.getRange(from, to);
    flash(from, to);
    const r = addResult(f, from, to);
    await ensureLoaded(f);
    show(f, r, await evaluate(code, ns, nsForm));
  }

  async function loadFile(path) {
    const f = files[path];
    const doc = f.doc;
    const text = doc.getValue();
    const { ns, nsForm } = F.nsOf(F.parse(text), text);
    flash(doc.posFromIndex(0), doc.posFromIndex(text.length));
    clearResults(f);
    const id = runtimeId;
    const result = await evaluate(text, ns, nsForm);
    if (result.error !== undefined || result.timeout) {
      f.loadedIn = null;
      // Errors from a whole file carry a real line number.
      const line = Math.max(0, Math.min((result.line || 1) - 1, doc.lineCount() - 1));
      const r = addResult(f, { line, ch: 0 }, { line, ch: doc.getLine(line).length });
      show(f, r, result);
      if (result.line) cm.scrollIntoView({ line, ch: 0 }, 100);
    } else {
      f.loadedIn = id;
      appendOutput(`; loaded ${path}\n`, "note");
    }
  }

  // -------------------------------------------------------------------------
  // Output, View and runtime status, streamed from bb

  const output = $("output");
  const MAX_OUTPUT_CHARS = 200000;

  function appendOutput(text, cls) {
    const atBottom = output.scrollHeight - output.scrollTop - output.clientHeight < 30;
    const span = document.createElement("span");
    if (cls) span.className = cls;
    span.textContent = text;
    output.appendChild(span);
    while (output.textContent.length > MAX_OUTPUT_CHARS && output.firstChild) {
      output.removeChild(output.firstChild);
    }
    if (atBottom) output.scrollTop = output.scrollHeight;
  }

  const view = $("view");
  function setView(html) {
    view.innerHTML = html || '<p class="hint">Values you <code>tap&gt;</code> appear here.</p>';
  }

  function setStatus(state, text) {
    const el = $("status");
    el.dataset.state = state;
    el.textContent = state === "ready" ? "Ready" : state === "restarting" ? "Restarting…" : "Problem";
    el.title = text;
  }

  function onEvent(msg) {
    const ev = JSON.parse(msg.data);
    if (ev.type === "out") appendOutput(ev.text);
    else if (ev.type === "view") setView(ev.html);
    else if (ev.type === "status") {
      if (ev.state === "restarting") appendOutput("; " + ev.text + "\n", "note");
      if (ev.runtime && ev.runtime !== runtimeId) {
        // A fresh runtime: old results no longer mean anything, and files
        // load again on their next evaluation.
        if (runtimeId !== null) Object.values(files).forEach(clearResults);
        runtimeId = ev.runtime;
      }
      setStatus(ev.state, ev.text);
    }
  }

  // EventSource reconnects by itself when the connection drops. If the same
  // editor answers, its first status event sets things right. A restarted
  // bb editor refuses the old token, which closes the stream: then fetch the
  // new token and connect again.
  let events = null;
  function connect() {
    if (events) events.close();
    events = new EventSource("/api/events?token=" + encodeURIComponent(token));
    events.onmessage = onEvent;
    events.onerror = () => {
      setStatus("error", "Lost the editor. Waiting for bb editor to come back…");
      if (events.readyState === EventSource.CLOSED) {
        setTimeout(async () => {
          try { await refreshToken(); } catch (_) {}
          connect();
        }, 1000);
      }
    };
  }
  connect();

  $("restart").addEventListener("click", () => {
    api("/api/restart", { method: "POST" });
    cm.focus();
  });

  document.querySelectorAll("[data-clear]").forEach((b) => {
    b.addEventListener("click", () => {
      if (b.dataset.clear === "output") output.replaceChildren();
      else setView("");
      cm.focus();
    });
  });

  $("help-button").addEventListener("click", () => {
    const help = $("help");
    help.hidden = !help.hidden;
    $("help-button").setAttribute("aria-expanded", String(!help.hidden));
  });

  window.addEventListener("beforeunload", (e) => {
    const unsaved = Object.values(files).filter(dirty);
    unsaved.forEach((f) => {
      // keepalive lets the request finish after the page has gone.
      fetch("/api/file?path=" + encodeURIComponent(f.path), {
        method: "POST",
        body: f.doc.getValue(),
        headers: { "X-Workshop-Token": token },
        keepalive: true,
      });
    });
    // If saving is already failing, ask before throwing the edits away.
    if (unsaved.some((f) => f.saveFailed)) e.preventDefault();
  });

  // -------------------------------------------------------------------------

  fileList = (await (await api("/api/files")).json()).files;
  const wanted = decodeURIComponent(location.hash.slice(1));
  await openFile(fileList.includes(wanted) ? wanted : fileList[0]);
})();
