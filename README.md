# Clojure from zero, with Babashka

Put together for the 28th October meetballs event in Loulé.

This is a small, contained, and highly opinionated environment to play around 
with Clojure. Its purpose is to facilitate playing with the language before having to 
commit to setting up tooling, IDEs, JDKs and all the other plumbing. 

It is a small server and a  browser based exploration environment that allows an "IDE-like" experience 
out of the box. It offers a code-editor with auto-complete, parinfer, inline evaluation, a `(tap> ...)` view
that knows how to render HTML, and a console for `(prn ...)` all in one view. 

This repo uses [Babashka](https://babashka.org)
(`bb`), a fast-starting Clojure for scripting. It is one binary with no JVM to
install, and it comes with JSON, CSV, HTTP and an HTTP server built in.

## Before the workshop

Please do this at home.

1. **Install Babashka**

   - macOS: `brew install borkdude/brew/babashka`
     (if brew says the tap is not trusted, run `brew trust borkdude/brew` first)
   - Linux:
     ```sh
     curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
     chmod +x install
     ./install
     ```
   - Windows: [Scoop](https://scoop.sh), then
     ```powershell
     scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
     scoop bucket add extras
     scoop install babashka
     ```

3. **Clone this repo and check it works**

   ```sh
   git clone https://github.com/dazld/clojure-workshop.git
   cd clojure-workshop
   bb -e '(+ 1 2)'        # prints 3 (on Windows, use PowerShell)
   bb editor              # then open http://localhost:1234
   ```

   No `git`? Download the ZIP from the repo page (**Code → Download ZIP**),
   unzip it, and `cd` into the folder instead.

   You should see an editor with four files and a green **Ready** in the
   top right. Press `Ctrl+C` in the terminal to stop it.

If anything fails, bring the error message. We will fix it at the start.

## The editor

`bb editor` starts a small editor in your browser. It is made for this
evening: you type Clojure on the left, evaluate it with a key press, and see
the result next to the code.

| Keys | What it does |
| --- | --- |
| `Ctrl+Enter` (or `⌘+Enter`) | Evaluate the top-level form around the cursor |
| `Alt+Enter` | Evaluate the form at the cursor, e.g. `(+ 3 4)` inside `(* 2 (+ 3 4))` |
| `Shift+Ctrl+Enter` (or `Shift+⌘+Enter`) | Load the whole file |
| `Alt+↑` | Expand the selection to the next enclosing form |
| `Alt+↓` | Contract the selection back, one step at a time |
| `Alt+S` | Splice: remove the brackets around the cursor, so `(a (b c) d)` becomes `(a b c d)` |
| `Ctrl+Space` or `Alt+Space` | Complete the name at the cursor. Shows each function's arguments and what it does; keep typing to narrow the list, `Enter` picks |
| `Esc` | Clear the results shown in the editor |

(`Alt` is `⌥ Option` on a Mac.)

Inside `(comment …)`, each form counts as top level, so `Ctrl+Enter`
evaluates the form you are in, never the whole comment. This is how Cursive's
"send top form" works. The **Ctrl/⌘+Enter** setting in the toolbar swaps the
two evaluate keys. With a selection, both evaluate exactly the selection, so
`Alt+↑` a few times then `Ctrl+Enter` evaluates any form you like.

**Changing keys:** open **Keys**, press **Change** next to a command, then
press the new keys. They need `Ctrl`, `Alt` or `⌘` (or be `F1`–`F12`), so
no key you type with is taken. A key can only do one thing, so taking one
from another command leaves that command unbound until you give it a new
one. Your keys are kept in this browser; **Reset keys** puts the defaults
back. On a Mac,
avoid `Ctrl+↑` and `Ctrl+↓`: Mission Control takes them before the browser
sees them. If you have more than one keyboard language on a Mac,
`Ctrl+Space` switches between them, so use `⌥+Space` to complete. On
Windows, `Alt+Space` opens the window menu, so use `Ctrl+Space`.

- **Brackets** are handled by [Parinfer](https://shaunlebron.github.io/parinfer/):
  you indent, it moves the closing brackets to match. Indent a line to move
  it into the form above, dedent it to move it out.
- **Output** from `println`, from any thread, appears in the Output pane.
- **`(tap> x)`** shows `x` in the View pane. Hiccup such as
  `[:h1 "hello"]` renders as HTML, and anything else is pretty-printed.
- **Files save automatically**, so `bb repos` in a terminal runs what you see.
- **Restart** stops everything that is running and starts a fresh Clojure.
  Use it when something never finishes. Your files are not affected.

### If something goes wrong

| You see | Do this |
| --- | --- |
| `Port 1234 is already in use` | `bb editor` is already running in another terminal. Use that one, or run `bb editor --port 4000` and open http://localhost:4000 |
| `Cannot listen on port 1234` | Something else has the port. On Windows, Hyper-V, WSL or Docker can reserve it. Run `bb editor --port 4000` and open http://localhost:4000 |
| A yellow "still running after 10s" | It may never finish, e.g. `(count (range))`. Press **Restart** |
| `Unable to resolve symbol` after a Restart | Restart forgets everything. What is in the file loads again by itself, but anything you only typed and evaluated, then deleted, is gone. Put it back in the file |
| A red **Problem** in the top right | The editor has stopped. Run `bb editor` again: the page reconnects by itself, and unsaved edits are saved then |

## The files

| File | What it is |
| --- | --- |
| `src/workshop/syntax.clj` | A tour of the language, evaluated one form at a time |
| `src/workshop/live.clj` | A program you change while it runs |
| `src/workshop/text.clj` | The main task: read *Os Maias* (1888) by Eça de Queiroz with Clojure |
| `src/workshop/repos.clj` | Another task: answer questions about real GitHub data, then make it a script |
| `data/os-maias.txt` | The novel, from [Project Gutenberg](https://www.gutenberg.org/ebooks/40409), without the licence text and title pages. Public domain |
| `data/repos.json` | A snapshot of the repos of the `clojure` and `babashka` GitHub orgs |

Tasks (`bb tasks` lists them):

```sh
bb editor                         # the workshop editor
bb repos --lang Clojure --top 5   # run your script
bb serve                          # stretch goal: the report as a web page
```

## After the workshop: a real editor

The same files work in any Clojure editor. With
[VS Code](https://code.visualstudio.com) and [Calva](https://calva.io):

1. Open this folder and run **Calva: Start a Project REPL and Connect (aka
   Jack-In)** (`ctrl+alt+c ctrl+alt+j`). Choose **Babashka**.
2. `alt+enter` evaluates the top-level form and `ctrl+enter` the current
   form. That is the other way round from the workshop editor's default.

Calva uses Paredit instead of Parinfer, but it keeps brackets balanced too.

Other editors (Emacs/CIDER, Neovim/Conjure, IntelliJ/Cursive): run
`bb nrepl-server 1667` in this folder and connect to port 1667.

## Where to go next

- [Clojure for the Brave and True](https://www.braveclojure.com), a free book
- [Babashka book](https://book.babashka.org)
- [Exercism Clojure track](https://exercism.org/tracks/clojure)
- [clojure.org guides](https://clojure.org/guides/getting_started): the same
  code runs on the JVM with the Clojure CLI and the `deps.edn` in this repo
