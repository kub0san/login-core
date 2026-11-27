# Handoff prompt — login-core Phases 4–7

Copy everything below into a fresh session to continue.

---

You are continuing the transformation of `C:\Projects\authmePAL` → `C:\Projects\login-core`. The repo migration, research wave, ADRs, and plan are complete. You are picking up at Phase 4.

## Environment
- Working dir: `C:\Projects\login-core` (git repo, master @ 9b1dedc, origin login-core.git).
- Upstream ref (READ-ONLY): `C:\Projects\login-core\AuthMeReloaded-ref` @ 56e9a66b (master, clean). Never modify it.
- JDK 25 at `C:\Tools\Java\zulu25`. Gradle wrapper in repo. Test server: `C:\Tools\global-test-server-26.3` (Leaf 26.3, port 25566). Read `C:\Projects\GLOBAL_TEST_SERVER.md` first.
- MCPs available: caveman, context7, exa, mempalace, minecraft-dev, minecraft-developer, minecraft-paper, minecode, craftwire. Use them.
- Skills: planning-with-files (active), dispatching-parallel-agents, subagent-driven-development, brainstorming, systematic-debugging, verification-before-completion, requesting-code-review, tdd. Use them.

## State of the repo
- Build: Java 25 toolchain, shadow 9.6.1, `compileJava` + `shadowJar` green. JAR `login-core-1.0-SNAPSHOT.jar`.
- Source: 18 Java files under `org.authmePAL.*` (handshake, decision, mojang, session, storage, authme, command, migration). ADRs 0002–0008 in `docs/adr/`.
- Plan: `docs/plans/phase-4-7-plan.md` (revised with judge verdicts).
- Java: 25 bytecode, no `options.release`. Paper pin currently `26.2.build.+` (WRONG — fix to 26.3). PacketEvents `2.13.0` (WRONG — fix to 2.14.0). MockBukkit not yet added.

## Plan (revised, judge-approved scope)
Read `docs/plans/phase-4-7-plan.md` fully before starting. Summary:

**Phase 4 — Build baseline (1 session)**
1. Pin `io.papermc.paper:paper-api:26.3.build.200-stable` (or 26.3.build.27-alpha for local).
2. `compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")`.
3. Add `testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.126.0")` + paper-api test dep.
4. Fix `api-version: '26.3'`, `runServer { minecraftVersion("26.3") }`, README/CREDITS/PLAN.
5. Fix ADR 0007: Java 25 hard floor, remove "min 1.21.11" claim.
6. Create ADR 0008 (done — identity/licensing/vendoring).
7. Extend `checkSourceEncoding` to test sources + `.properties/.json/.txt`.
8. `clean check shadowJar` green.

**Phase 5 — Identity + package + storage (2–3 sessions)**
1. Resolve ADR 0008 identity (already accepted): runtime name `AuthMe`, own package `com.hizeric.logincore`, drop `AuthMePAL`.
2. Rename packages `org.authmePAL` → `com.hizeric.logincore` (one atomic commit).
3. Add classification column + unique UUID index + `BEFORE UPDATE` trigger to SQLite schema (idempotent ALTER).
4. Flip `on-lookup-failure` default to DENY; pin Mojang failure semantics (404/204=NOT_PREMIUM; 403/429/5xx/timeout/malformed=UNKNOWN).
5. Update CREDITS.md for GPL vendoring (remove "no source copied" claim).

**Phase 6a — Storage/classification/conflict (2 sessions)**
1. Write-once setter, conflict state, atomic persistence (txn + commit-before-grant).
2. Real-db smoke test.
3. Security review (moved earlier).

**Phase 6b — K-1 + pipeline rewire (2–3 sessions)**
1. Design + implement connection-keyed handoff (alias index + fail-closed mismatch). Note: Bukkit has no channel ID at pre-login (DEVIATIONS.md:6) — design needed.
2. Rewire `PremiumLoginListener` to automatic classification.
3. Remove enrollment commands/cache.
4. Fix F-01 (registration ordering), F-04 (IP throttle).

**Phase 6c — Legacy migration (1–2 sessions)**
1. Unclassified state + evidence rules + idempotent migration.
2. Backup+restore drill.
3. R1/R2 security + architecture review.

**Phase 7 — SQLite compat (1 session)**
1. Hash compat (BCrypt/Argon2 + legacy rehash).
2. Idempotent migration ×2, real AuthMe db copy.
3. Defer locking/transaction soak.

## Thin vendor scope (NOT full fork)
Only: schema-compatible `authme` table, hash verify (BCrypt/Argon2), registration insert, authenticated-state tracking, events façade. ~1–2k LOC. AuthMeBridge/AuthMeLoginListener remain the only AuthMe-touching files until vendoring lands.

## 18 classification test cases (must all pass)
1. Valid premium session creates PREMIUM.
2. Confirmed Mojang profile absence creates OFFLINE.
3. Lookup timeout does not create OFFLINE.
4. Session-server outage does not create OFFLINE.
5. Invalid premium verification never authenticates.
6. Cracked client cannot claim premium username.
7. PREMIUM cannot become OFFLINE.
8. OFFLINE cannot become PREMIUM.
9. No public command changes identity type.
10. No admin command changes identity type.
11. No config option changes identity type.
12. Failed persistence cannot grant unpersisted premium login.
13. Simultaneous first logins cannot produce conflicting records.
14. Expired verification cannot be reused.
15. Disconnects clean up pending verification.
16. Existing premium UUID records interpreted correctly.
17. Legacy AuthMe accounts classified per migration strategy.
18. Username conflicts do not cause silent account adoption.

## Security blockers (from judge) — do not skip
- `on-lookup-failure` default DENY (currently ALLOW).
- DB trigger for immutability, not just CHECK.
- No password fallback for PREMIUM (categorical rejection).
- Mojang failure semantics pinned (404/204=NOT_PREMIUM; else=UNKNOWN).
- K-1 handoff design before implementation.
- Concurrency: per-account CAS, insert-or-ignore, one-shot consume before grant.
- Atomicity: txn commit-before-grant, WAL, busy_timeout, shutdown drain.
- Migration: evidence checklist, backup+restore, no concurrent race.
- Startup guards: online-mode=true refusal, duplicate handshake refusal, admin permission gating.
- Move security review before pruning.

## Rules
- Use skills: planning-with-files, tdd, subagent-driven-development, verification-before-completion, requesting-code-review.
- Caveman style for internal/agent communication; professional for committed docs/code/comments.
- Conventional commits per batch; no push without explicit user approval.
- Read-only subagents for research; implementer subagents for code. Never let subagents invent conflicting architecture.
- Run `.\gradlew.bat clean check shadowJar` after each batch.
- Live E2E via TestServer.ps1 + Craftwire; green handshake ≠ pass.

## First action
1. Read `docs/plans/phase-4-7-plan.md` and ADRs 0002–0008.
2. Update task_plan.md (planning-with-files) marking phases 0–3 complete, phase 4 in_progress.
3. Execute Phase 4 build baseline fixes. Start with Paper/PacketEvents/MockBukkit pins. Verify build green before proceeding.