# Plan: login-core Phases 4–7 — Build, Prune, Identity, SQLite

## Scope
Transform the renamed `login-core` repo (currently an authmePAL-derived plugin) into a minimal standalone AuthMe-derived auth plugin: Java 25 bytecode, Shadow packaging, Paper 26.3 baseline (the 1.21.11 minimum claim was false — Java 25 bytecode means runtime 26.1+), SQLite only, immutable PREMIUM/OFFLINE classification, automatic premium verification, no enrollment/DB-backend/importer sprawl. Standalone/vendoring is deferred per ADR 0008; see `docs/DEVIATIONS.md`.

## Current state (verified)
- Repo: `C:\Projects\login-core`, master @ 9b1dedc, origin login-core.git.
- Upstream ref: `AuthMeReloaded-ref` @ 56e9a66b (read-only).
- Build: Java 25 toolchain, shadow 9.6.1, `compileJava` + `shadowJar` green; JAR `login-core-1.0-SNAPSHOT.jar`.
- Source: 18 Java files under `org.authmePAL.*` (handshake, decision, mojang, session, storage, authme, command, migration).
- ADRs 0002–0007 accepted. AGENTS.md/README renamed.

## Phase 4 — Build & source integration (1–2 sessions)
Goal: lock the build baseline and decide the source-integration strategy (fork AuthMe core vs keep authmePAL layer).

Tasks:
1. ~~Confirm Paper API pin.~~ **Resolved (judge verdict §3, ADR 0007):** pin `paper-api:26.3.build.200-stable` (local `26.3.build.27-alpha`); the "min 1.21.11" claim was false (Java 25 bytecode ⇒ runtime 26.1+).
2. Add `checkSourceEncoding` task (verbatim from pvp-core) to `build.gradle.kts`, wire into `check`.
3. Add JUnit 5 + MockBukkit (v1.21 4.116.3) test deps; repeat Paper API as `testImplementation`.
4. Decide packaging: keep `org.xerial:sqlite-jdbc` in `plugin.yml libraries` (current) OR shade. ADR 0006 says do not relocate; keep libraries. Verify native binaries for prod/test platforms (Linux/Windows x86_64).
5. Smoke build: `clean check shadowJar` green; record baseline commit.

Exit: clean build, encoding guard active, test harness wired.

## Phase 5 — Controlled pruning (3–5 sessions, batched)
Prune in this order; compile + test after each batch; never mass-delete.

Batch 1 — Platform/identity naming: **Resolved (ADR 0008):** packages are `com.hizeric.logincore`; runtime plugin name stays `AuthMePAL` until the vendoring commit (flip to `AuthMe` deferred — see `docs/DEVIATIONS.md`).

Batch 2 — Remove redundant custom infra that AuthMe already provides:
- `handshake/` RSA/AES/hasJoined → keep crypto core but rewire to automatic classification (Phase 6). Do NOT delete crypto.
- `storage/` link table → reconcile with AuthMe `premiumUuid` column; keep only if no AuthMe API setter.
- `migration/` audit → keep resumable audit pattern, fix F-06 (never refreshes).
- `mojang/` cache/budget → keep (AuthMe has none); port as-is.
- `decision/` LoginDecisionEngine → keep, extend with classification states.

Batch 3 — Remove unused commands/permissions/config for deleted features:
- `/authmepal exempt`, `link`, `unlink`, `migrate` (keep audit/apply).
- Remove `ChatColor` usage → MiniMessage (AdminCommand.java).
- Remove legacy Spigot/Bungee/Velocity/Folia refs (none in source yet; verify).

Batch 4 — Dependencies: **Resolved (ADR 0008):** thin-vendor AuthMe core (schema, hash verify, registration insert, authenticated-state tracking, events façade; ~1–2k LOC), not the full fork. Until that lands, AuthMe remains a runtime plugin dependency and the exit criterion at line 44 is waived — see `docs/DEVIATIONS.md`. Phase 7 has shipped schema/hash compatibility only.

Exit: no external AuthMe plugin dependency; standalone jar builds.

## Phase 6 — Authentication & identity (4–6 sessions)
Goal: immutable PREMIUM/OFFLINE classification + automatic premium verification.

Tasks:
1. Vendor minimal AuthMe core (Agent A component list): DataSource/PlayerAuth/PlayerCache, PasswordSecurity+one hash, Management→AsynchronousJoin→AsynchronousLogin→ProcessSync, PlayerListener+OnJoinVerifier+ListenerService, SessionService (optional), events (Login/Register/AsyncPreLogin), SettingsProvider+3-4 property classes, one Paper platform adapter. Drop Jalu injector → explicit constructor wiring (shenanigans pattern).
2. Add classification column to SQLite schema + write-once setter + unique UUID index + conflicted state (ADR 0003/0005).
3. Rewire PremiumLoginListener to automatic classification: name validate → exempt → Mojang lookup → LoginDecisionEngine.decide → handshake (PREMIUM) or password (OFFLINE). Remove enrollment commands/cache (Agent B list).
4. Fix K-1: key VerifiedSessions by connection identity, not name@ip.
5. Fix F-01: defer registration to PlayerLoginEvent (or keep pre-login with ban-check ordering).
6. Fix F-04: IP throttle counts distinct names.
7. Handle identity conflicts: existing OFFLINE username becomes premium → fail closed; document.
8. Legacy migration: unclassified state + evidence rules + idempotent migration (ADR 0004).
9. No password fallback for PREMIUM (categorical rejection in login pipeline).

Exit: classification tests pass; premium verification E2E on test server.

## Phase 7 — SQLite compatibility (2–3 sessions)
Goal: existing AuthMe data preserved; migrations safe.

Tasks:
1. Verify schema compat: table/column names match AuthMe `authme` table.
2. Add classification + premium_uuid columns via ALTER TABLE (idempotent).
3. Hash compat: keep BCrypt/Argon2; legacy hashes rehash on login (AuthMe pattern).
4. Transaction + locking + shutdown tests.
5. Backup before migration; log.
6. Test with a real AuthMe SQLite db (copy from test server).

Exit: migration idempotency tests green; real-db smoke test.

## Cross-cutting
- Security review (Agent R1) + architecture review (Agent R2) after Phase 6.
- Live test server: deploy via TestServer.ps1, Craftwire E2E, record evidence.
- Conventional commits per batch; no push without approval.

## Judge verdicts (2026-11-26) — NO-GO as written; conditional GO after fixes

Three independent judges (feasibility, security, build/compat) returned NO-GO. Consolidated blockers:

1. **Scope**: thin vendor only (~1–2k LOC), not the 10-component fork. Drop Jalu removal (nothing to remove), SessionService (D5), reflection shims (D1), MockBukkit (replace with v26.3 artifact).
2. **Identity contradiction (ADR 0008 required)**: runtime name AuthMe vs AuthMePAL vs login-core; data folder; `depend`. Must resolve before any code moves.
3. **Paper pin**: compile against `paper-api:26.3.build.200-stable` (or 26.3.build.27-alpha local). Drop false "min 1.21.11" claim (Java 25 bytecode ⇒ min runtime 26.1+). Fix ADR 0007.
4. **PacketEvents**: 2.14.0 (not 2.13.0) — ref 2.12.1, compile 2.13.0, runtime 2.14.0.
5. **MockBukkit**: `mockbukkit-v26.3:4.126.0` (not v1.21).
6. **Fail-closed default**: `on-lookup-failure` must default DENY (currently ALLOW). Pin Mojang failure semantics (404/204=NOT_PREMIUM; 403/429/5xx/timeout/malformed=UNKNOWN).
7. **Immutability**: DB trigger `BEFORE UPDATE … WHEN OLD.type != NEW.type → RAISE(ABORT)`, not just CHECK. Unique index on premium UUID.
8. **K-1 handoff**: Bukkit has no channel ID at pre-login; design needed (alias index + mismatch fail-closed, or move to Player stage).
9. **Concurrency/atomicity**: per-account CAS, insert-or-ignore read-back, one-shot consume before grant, txn commit-before-grant, WAL/busy_timeout, shutdown drain.
10. **Legacy migration**: evidence checklist, backup+restore drill, no concurrent-login race, REQUIRE_PASSWORD_ONCE must not become password fallback.
11. **Security review moved earlier**: before pruning/vendoring, not after Phase 6.
12. **Licensing ADR missing**: vendoring GPL-3.0 source requires retaining headers/notices, updating CREDITS (currently claims "no source copied").
13. **18 classification cases**: must be enumerated in plan (not just referenced).
14. **Drop SessionService**; keep VerifiedSessions (fix K-1 separately).

## Revised phases (4–7)

### Phase 4 — Build baseline (1 session)
- Pin Paper 26.3, PacketEvents 2.14.0, MockBukkit v26.3:4.126.0.
- Fix api-version 26.3, runServer 26.3, README/CREDITS/PLAN.
- Fix ADR 0007 (Java 25 hard floor, no 1.21.11 claim).
- Create ADR 0008 (identity + licensing).
- Extend checkSourceEncoding to test sources + .properties/.json/.txt.
- `clean check shadowJar` green.

### Phase 5 — Identity + package + storage (2–3 sessions)
- Resolve ADR 0008 (runtime name, data folder, depend).
- Rename own packages `org.authmePAL` → `com.hizeric.logincore` (one atomic commit).
- Add classification column + unique UUID index + BEFORE UPDATE trigger to SQLite schema (idempotent ALTER).
- Flip on-lookup-failure default to DENY; pin Mojang failure semantics.
- Update CREDITS.md for GPL vendoring.

### Phase 6a — Storage/classification/conflict (2 sessions)
- Write-once setter, conflict state, atomic persistence (txn + commit-before-grant).
- Real-db smoke test.
- Security review (moved earlier).

### Phase 6b — K-1 + pipeline rewire (2–3 sessions)
- Design + implement connection-keyed handoff (alias index + fail-closed mismatch).
- Rewire PremiumLoginListener to automatic classification.
- Remove enrollment commands/cache.
- Fix F-01 (registration ordering), F-04 (IP throttle).

### Phase 6c — Legacy migration (1–2 sessions)
- Unclassified state + evidence rules + idempotent migration.
- Backup+restore drill.
- R1/R2 security + architecture review.

### Phase 7 — SQLite compat (1 session)
- Hash compat (BCrypt/Argon2 + legacy rehash).
- Idempotent migration ×2, real AuthMe db copy.
- Defer locking/transaction soak.

## 18 classification test cases (enumerated)

Status per the final review (2026-11-27): 7 covered, 7 partial, 4 uncovered.

1. Valid premium session creates PREMIUM. — **Covered**
2. Confirmed Mojang profile absence creates OFFLINE. — **Partial**
3. Lookup timeout does not create OFFLINE. — **Partial**
4. Session-server outage does not create OFFLINE. — **Uncovered**
5. Invalid premium verification never authenticates. — **Partial**
6. Cracked client cannot claim premium username. — **Partial**
7. PREMIUM cannot become OFFLINE. — **Covered**
8. OFFLINE cannot become PREMIUM. — **Covered**
9. No public command changes identity type. — **Uncovered**
10. No admin command changes identity type. — **Partial**
11. No config option changes identity type. — **Uncovered**
12. Failed persistence cannot grant unpersisted premium login. — **Covered**
13. Simultaneous first logins cannot produce conflicting records. — **Partial**
14. Expired verification cannot be reused. — **Partial**
15. Disconnects clean up pending verification. — **Uncovered**
16. Existing premium UUID records interpreted correctly. — **Covered**
17. Legacy AuthMe accounts classified per migration strategy. — **Covered**
18. Username conflicts do not cause silent account adoption. — **Covered**

## Thin vendor scope (not full fork)
Only: schema-compatible authme table, hash verify (BCrypt/Argon2), registration insert, authenticated-state tracking, events façade for downstream. ~1–2k LOC. Keep Jalu out (nothing to remove). AuthMeBridge/AuthMeLoginListener remain the only AuthMe-touching files until vendoring lands.

## Acceptance (phases 4–7)
- Standalone jar (no AuthMe plugin dep) builds green.
- `clean check shadowJar` green.
- All 18 classification cases pass.
- SQLite migration idempotent + real-db smoke.
- Live premium + offline login E2E on test server with evidence (premium account = external dependency; mock Mojang + Craftwire substitute allowed).

## Risks
- Vendoring AuthMe core is ~thousands of lines; may exceed session budget. Mitigation: vendor only the 10 components in Agent A §4; defer non-essential.
- MockBukkit version skew with Paper 26.3.
- Real premium E2E needs a premium account + PacketEvents on test server (AuthMe not installed by default per AGENTS.md).

## Acceptance (phases 4-7)
- Standalone jar (no AuthMe plugin dep) builds green.
- `clean check shadowJar` green.
- Immutable classification tests (18 cases from spec §18) pass.
- SQLite migration idempotency + real-db smoke.
- Live premium + offline login E2E on test server with evidence.
