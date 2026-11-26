# 0001 — Project structure and GitHub

- **Status:** accepted (2026-11-26)

## Context

authmePAL is being published as a public repository. Its project structure and
agent conventions had drifted from the other Paper plugins in the workspace.
This record captures the baseline alignment and the reference arrangement.

## Decision

- Publish as the public repository `kub0san/authmePAL`, with an HTTPS `origin`
  remote (`https://github.com/kub0san/authmePAL.git`).
- License the project under GPL-3.0 (GNU General Public License v3.0); the full
  text is in the root `LICENSE` file, copied verbatim from the workspace
  reference (identical SHA-256 to pvp-core and shenanigans-core).
- Align project structure and conventions with both `pvp-core` and
  `shenanigans-core`: shared `.gitignore` exclusions, a UTF-8 source encoding
  guard, `docs/adr`, `docs/agents`, `docs/superpowers`, and local-only
  `GLOSSARY.md` / `mempalace.yaml`.
- Keep Java 25 bytecode: the toolchain stays at 25 and no `options.release` is
  set (unlike pvp-core, which emits Java 21).
- Keep a clone of AuthMeReloaded in `AuthMeReloaded-ref/` as a gitignored,
  read-only behavioural reference. No source from it is copied into `src/`.

## Consequences

- Agent metadata and scratch stay out of the tracked tree via `.gitignore`;
  architectural records under `docs/adr` are published.
- The repository is public: anything committed is visible to anyone, so
  machine-specific paths, credentials and local configuration stay out of the
  tracked tree.
- Pushing is an explicit, deliberate step — a push publishes immediately.

## Non-goals

- This task does not start the plugin rewrite. No Java source under `src/`,
  `PLAN.md`, `plugin.yml`, `config.yml`, or dependency versions were changed.
