---
name: clojure
description: Editing Clojure/ClojureScript/Babashka code (.clj .cljs .cljc .cljd .edn .bb), evaluating it in a running nREPL server, and running its tests. Registers a hook that repairs unbalanced delimiters and reports clj-kondo errors after every Edit/Write.
paths:
  - "**/*.clj"
  - "**/*.cljs"
  - "**/*.cljc"
  - "**/*.cljd"
  - "**/*.edn"
  - "**/*.bb"
hooks:
  PostToolUse:
    - matcher: "Edit|Write"
      hooks:
        - type: command
          command: "bb --config ~/.claude/skills/clojure/bb.edn -m clojure-skill.guard"
          timeout: 30
---

# Clojure

## After every Edit/Write

A hook checks each Clojure file you change:

- **Unbalanced delimiters** are repaired from indentation, and the changed lines are reported.
  Check they match your intent — the inference trusts your indentation. The file on disk
  changed, so re-read it before the next Edit to it.
- **Unrepairable** (unterminated string, or the repair would touch too many lines): the edit is
  reported with the unclosed delimiter's location and the file is left as written. Rewrite the
  whole enclosing top-level form; don't patch single parens or count them by hand.
- **clj-kondo errors** (unresolved symbol, wrong arity, …) are reported; warnings are not. Fix
  them unless they're transient, e.g. a function you're about to add.

## Evaluating code

Use the PowerShell tool with a single-quoted here-string. The Bash tool collapses `\\` to `\`,
which corrupts Clojure strings, regexes and the `\\` char literal.

```powershell
@'
(require '[my.ns :as n] :reload)
(n/f 42)
'@ | bb --config $HOME/.claude/skills/clojure/bb.edn -m clojure-skill.nrepl
```

- Without `-p PORT` it uses `.nrepl-port` in the working directory or above, else the one
  server running from there. `--ports` lists running servers with their directories.
- `-f FILE` evaluates a file's contents — the safe route for code written with the Write tool.
- The session keeps `*ns*`, `*1` and `*e` per port; `--new-session` starts fresh. Vars are
  global to the server regardless. `:reload` a namespace after editing it.
- `--timeout MS` (default 60000) interrupts a runaway evaluation. Exit codes: 0 ok, 1 the code
  threw, 2 no server / timeout.

## Tests

Run the whole suite with the project's own runner — the `test` alias in `deps.edn` or task in
`bb.edn`. For one namespace in a running REPL:
`(require 'my.ns-test :reload) (clojure.test/run-tests 'my.ns-test)`.

## Finding code

Search the bare, unqualified name with `rg -w my-fn`: that catches aliased (`x/my-fn`),
`:refer`'d and fully-qualified uses alike. In a REPL, `(doc sym)`, `(source sym)` and
`(apropos "part")` answer questions about libraries.
