// A small Clojure reader that finds form boundaries. It does not build
// values, it only records where each form starts and ends, so the editor
// can pick the form to evaluate.

(function (root) {
  "use strict";

  const OPEN = { "(": ")", "[": "]", "{": "}" };
  const CLOSE = { ")": true, "]": true, "}": true };

  function isWhitespace(c) {
    return c === " " || c === "\t" || c === "\n" || c === "\r" || c === ",";
  }

  function isTokenEnd(c) {
    return isWhitespace(c) || c === undefined || c === '"' || c === ";" || OPEN[c] || CLOSE[c];
  }

  // Returns the top-level forms of text. Each node is
  // {start, end, children, head} for collections or {start, end, atom: text}.
  function parse(text) {
    const top = { children: [] };
    const stack = [top];
    let prefixStart = null; // where quote, deref, #', #_ etc. began
    let i = 0;
    const n = text.length;

    function add(node) {
      if (prefixStart !== null) { node.start = prefixStart; prefixStart = null; }
      stack[stack.length - 1].children.push(node);
    }

    while (i < n) {
      const c = text[i];

      if (c === ";") { while (i < n && text[i] !== "\n") i++; continue; }
      if (isWhitespace(c)) { i++; continue; }

      if (c === "'" || c === "`" || c === "@" || c === "^") {
        if (prefixStart === null) prefixStart = i;
        i++; continue;
      }
      if (c === "~") {
        if (prefixStart === null) prefixStart = i;
        i += text[i + 1] === "@" ? 2 : 1; continue;
      }
      if (c === "#" && (text[i + 1] === "'" || text[i + 1] === "_" || text[i + 1] === "?" || text[i + 1] === "#")) {
        if (prefixStart === null) prefixStart = i;
        i += text[i + 2] === "@" ? 3 : 2; continue;
      }

      let open = null, openStart = i;
      if (OPEN[c]) { open = c; i++; }
      else if (c === "#" && (text[i + 1] === "(" || text[i + 1] === "{")) { open = text[i + 1]; i += 2; }
      if (open) {
        const node = { start: openStart, end: null, children: [], open };
        if (prefixStart !== null) { node.start = prefixStart; prefixStart = null; }
        stack.push(node);
        continue;
      }

      if (CLOSE[c]) {
        i++;
        if (stack.length > 1) {
          const node = stack.pop();
          node.end = i;
          const first = node.children[0];
          node.head = first && first.atom !== undefined ? first.atom : null;
          stack[stack.length - 1].children.push(node);
        }
        continue;
      }

      const start = i;
      if (c === '"' || (c === "#" && text[i + 1] === '"')) {
        i += c === "#" ? 2 : 1;
        while (i < n && text[i] !== '"') i += text[i] === "\\" ? 2 : 1;
        i = Math.min(i + 1, n);
      } else if (c === "\\") {
        i += 2; // a character literal like \( or \newline
        while (i < n && !isTokenEnd(text[i])) i++;
      } else {
        while (i < n && !isTokenEnd(text[i])) i++;
      }
      add({ start, end: i, atom: text.slice(start, i) });
    }

    // Unclosed forms run to the end of the text.
    while (stack.length > 1) {
      const node = stack.pop();
      node.end = n;
      node.unclosed = true;
      stack[stack.length - 1].children.push(node);
    }
    return top.children;
  }

  function contains(node, pos) { return node.start < pos && pos < node.end; }

  // Evaluating a whole (comment ...) is never what you mean: it returns nil.
  // Parinfer puts the comment's closing paren on the last form's line, so the
  // cursor often lands just after it. Use the comment's form before the
  // cursor instead.
  function insteadOfComment(node, pos) {
    if (!node || node.head !== "comment") return node;
    const inside = node.children.slice(1).filter((c) => c.start < pos);
    return inside.length ? inside[inside.length - 1] : null;
  }

  // The form at the cursor, like Calva's "current form": the form that ends
  // right before the cursor, else the atom under it, else the innermost form
  // around it.
  function currentForm(forms, pos) {
    return insteadOfComment(innermostForm(forms, pos), pos);
  }

  function innermostForm(forms, pos) {
    let best = null;
    (function walk(nodes) {
      for (const node of nodes) {
        if (node.end === pos) best = node;
        // Cursor on a form's opening bracket: that form, unless one ends here.
        else if (node.children && node.start === pos) { if (!best || best.end !== pos) best = node; }
        else if (node.atom !== undefined && node.start <= pos && pos < node.end) best = node;
        else if (node.children && contains(node, pos)) {
          if (!best || best.end !== pos) best = node;
          walk(node.children);
          return;
        }
      }
    })(forms);
    return best;
  }

  // The top-level form at the cursor. Inside (comment ...), each form in the
  // comment counts as top level, like Calva.
  function topLevelForm(forms, pos) {
    const at = (nodes) => nodes.find((nd) => nd.start <= pos && pos <= nd.end);
    const form = at(forms);
    if (form && form.head === "comment") {
      return at(form.children.slice(1)) || insteadOfComment(form, pos);
    }
    return form || null;
  }

  // The smallest form around [from, to) that is bigger than it: the next
  // step of expand region. A cursor on a symbol selects the symbol first.
  function enclosing(forms, from, to) {
    let best = null;
    (function walk(nodes) {
      for (const node of nodes) {
        const around = node.start <= from && to <= node.end;
        const same = node.start === from && node.end === to;
        if (around && !same) {
          best = node;
          if (node.children) walk(node.children);
          return;
        }
        // Already selected: its parent, found one level up, is the answer.
        if (same) return;
      }
    })(forms);
    return best;
  }

  // The innermost bracketed form the cursor is inside: what splice removes.
  function innermostList(forms, pos) {
    let best = null;
    (function walk(nodes) {
      for (const node of nodes) {
        if (node.children && !node.unclosed && node.start < pos && pos < node.end) {
          best = node;
          walk(node.children);
          return;
        }
      }
    })(forms);
    return best;
  }

  // The bracketed forms the cursor is inside, innermost first, each with the
  // forms beside it: what slurp and barf move the brackets over.
  function listsAround(forms, pos) {
    const lists = [];
    (function walk(nodes) {
      for (const node of nodes) {
        if (node.children && !node.unclosed && node.start < pos && pos < node.end) {
          lists.unshift({ node, siblings: nodes });
          walk(node.children);
          return;
        }
      }
    })(forms);
    return lists;
  }

  function nsOf(forms, text) {
    const nsForm = forms.find((f) => f.head === "ns");
    if (!nsForm) return { ns: null, nsForm: null };
    const name = nsForm.children[1];
    return {
      ns: name && name.atom !== undefined ? name.atom : null,
      nsForm: text.slice(nsForm.start, nsForm.end),
    };
  }

  root.ClojureForms = { parse, currentForm, topLevelForm, enclosing, innermostList, listsAround, nsOf };
})(this);
