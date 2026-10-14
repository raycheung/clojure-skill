# clojure-skill

A Claude Code skill for Clojure work, in Babashka: a PostToolUse hook that repairs unbalanced
delimiters and reports clj-kondo errors, and an nREPL client. See [SKILL.md](SKILL.md).

## Install

Requires `bb` and `clj-kondo` on PATH (`scoop install babashka clj-kondo`). Check this repo out
as `~/.claude/skills/clojure` — the hook command in SKILL.md refers to that path. Nothing else
to install: both entry points run straight from the checkout with `bb --config`.

## Test

```
bb test
```
