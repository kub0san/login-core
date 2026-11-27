# 0008 — Runtime identity, licensing, and vendoring policy

- **Status:** accepted (2026-11-26)

## Context

Three contradictions blocked progress:
1. ADR 0002 kept runtime plugin name `AuthMe` for compat, but code ships `name: AuthMePAL`, `main: org.authmePAL.AuthMePAL`, `depend: [packetevents, AuthMe]`. Data folder and downstream `depend` semantics unresolved.
2. CREDITS.md claims "no source code was copied" and "no copyleft obligation"; PLAN.md §13 forbids copying AuthMe source. The standalone requirement (spec §4/§16) implies vendoring AuthMe core source, which is GPL-3.0 (copyleft-compatible but requires attribution/headers/NOTICE).
3. Feasibility judge: full 10-component fork is 25–40 sessions; thin vendor (~1–2k LOC) is viable.

## Decision

**Identity.** Keep runtime plugin name `AuthMe` (preserves `plugins/AuthMe` data folder and downstream `depend: [AuthMe]` semantics; no `AuthMeApi` singleton is provided). Repository/artifact/group remain `login-core`/`com.hizeric.logincore`. Package own code `com.hizeric.logincore`; no `fr.xephi.*` source is vendored — provenance is recorded in `NOTICE`, where BCrypt attribution also remains. Update `plugin.yml` `name: AuthMe`, `main: com.hizeric.logincore.LoginCore`, `depend: [packetevents]` (drop AuthMe once vendored). Remove the `AuthMePAL` name from code/config.

**Licensing.** login-core is GPL-3.0 (inherited from AuthMeReloaded). Vendoring GPL-3.0 source is permitted and requires: retaining upstream copyright headers and LICENSE, adding a NOTICE/PROVENANCE file recording upstream repo + exact commit SHA + modified files, updating CREDITS.md to acknowledge vendored source (remove "no source copied" claim). No relicensing.

**Vendoring (cutover complete).** Thin vendor only: schema-compatible `authme` table, hash verify (BCrypt; Argon2 rejected), registration insert, authenticated-state tracking, events façade for downstream. `AuthMeBridge` was deleted and `AuthMeLoginListener` rewritten to use `Storage` plus `com.hizeric.logincore.events.LoginEvent`/`RegisterEvent`; `fr.xephi:authme-core` and the AuthMe `depend`/startup check are gone. The full AuthMe pipeline is not vendored; login-core owns handshake, Mojang client/cache/budget, decision engine and SQLite links.

## Consequences

- Data folder stays `plugins/AuthMe`; existing AuthMe SQLite data preserved (Phase 7 compat).
- The `AuthMe` name preserves the `plugins/AuthMe` data folder and `depend: [AuthMe]` resolution semantics for downstream plugins; no `AuthMeApi` singleton or binary AuthMe event compatibility is provided.
- CREDITS.md and LICENSE must be updated before any vendored source is committed.
- Vendoring scope capped; full fork deferred indefinitely.
- ADR 0002 amended by this ADR (identity section superseded; compat section retained).
