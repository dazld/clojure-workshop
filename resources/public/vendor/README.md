# Vendored editor libraries

Kept in the repo so the editor works with no network at the venue.

| File | Source | Licence |
| --- | --- | --- |
| codemirror.js, codemirror.css, matchbrackets.js, show-hint.js, show-hint.css | codemirror@5.65.21 | MIT |
| clojure.js | codemirror@5.65.21 mode/clojure, **patched**: `(comment ...)` is highlighted as code (the `symbol === "comment"` branch in `base` and the `inComment` tokenizer are removed) | MIT |
| parinfer.js | parinfer@3.13.1 | MIT |
| parinfer-codemirror.js | parinfer-codemirror@1.4.2, **patched**: `fixText` reads the scroll position before `setValue`, not after, so typing that changes the brackets no longer scrolls the line to the bottom | MIT |
