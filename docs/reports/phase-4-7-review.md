# Phase 4–7 Review — login-core

## 1. Headline finding: NOT standalone (user's concern confirmed)

**Status: NOT standalone.** The plugin still hard-depends on AuthMe being installed:

- `plugin.yml:13` → `depend: [packetevents, AuthMe]`
- `build.gradle.kts:23` → `compileOnly("fr.xephi:authme-core:6.0.1")`
- `LoginCore.onEnable()` (lines 42–52) → `getPlugin("AuthMe")` null/enabled check; disables itself if absent.
- `AuthMeBridge` → `AuthMeApi.getInstance()` (hard runtime singleton).
- `AuthMeLoginListener` → registers AuthMe events, calls `forceLogin`/`forceLoginFromProxy`/`forceRegister`.

The spec (§4, §16) requires a standalone plugin that "must not depend on a separately installed AuthMe plugin" and "incorporate the required upstream source into login-core itself." The plan's "thin vendor" strategy was not executed — zero AuthMe source was vendored (only `BCrypt.java` for hashing). The plugin cannot enable on a server without AuthMe installed, which the test server does NOT have by default (AGENTS.md: "AuthMe / AuthMePAL are not installed by default").

**Impact**: Acceptance criterion "standalone jar (no AuthMe plugin dep) builds green" is FALSE. The jar builds, but it won't enable standalone.

**Recommendation**: Either (a) vendor the minimal AuthMe data layer (schema-compatible `authme` table already created; add hash verify + registration insert + authenticated-state tracking, ~1–2k LOC) and remove the `AuthMe` depend + compileOnly, or (b) formally amend the spec to accept the bridge model and document AuthMe as a hard runtime dependency. Option (a) matches the spec and the ADR 0008 "thin vendor" decision that was never executed.

## 2. What was done well

- Build baseline fixed: Paper 26.3, PacketEvents 2.14.0, MockBukkit v26.3:4.126.0, Java 25 bytecode, `checkSourceEncoding` wired into `check`. `clean check shadowJar` green.
- Package renamed `org.authmePAL` → `com.hizeric.logincore` (atomic commit).
- Immutable classification implemented in SQLite: `account_classification` table with CHECK + `BEFORE UPDATE` trigger (immutable), unique index on `mojang_uuid`, `commitPremiumIdentity` transactional (commit-before-grant).
- Fail-closed default: `on-lookup-failure` now defaults DENY (was ALLOW). Mojang failure semantics pinned (404/204=NOT_PREMIUM; 403/429/5xx/timeout/malformed=UNKNOWN).
- K-1 fix: verified-session handoff fail-closed.
- 2048-bit RSA, write-once link UUID, no ADOPT policy, never exempt premium.
- Legacy migration: idempotent, backup before DDL, schema version stamp, `REQUIRE_PASSWORD_ONCE` requires password proof before commit.
- BCrypt vendored with provenance in NOTICE; licensing claims corrected.
- 19 test files covering classification cases, backup/restore, throttling, cleanup, outage.
- ADRs 0002–0008, plan + handoff docs.

## 3. Issues found

### A. Standalone gap (critical) — see §1.

### B. plugin.yml still says `name: AuthMePAL`
- `plugin.yml:1` → `name: AuthMePAL`. ADR 0008 says runtime name should be `AuthMe`. The repo/artifact is `login-core`. The data folder is `plugins/AuthMePAL` (per DEVIATIONS.md). Inconsistent: plugin name `AuthMePAL`, main class `com.hizeric.logincore.LoginCore`, command `logincore`, permission `logincore.admin`. Either commit to `AuthMe` (compat) or `login-core` (clean). Document the choice. Currently a confusing hybrid.

### C. Config default for `on-lookup-failure`
- `LoginCore.validConfiguration()` accepts both ALLOW and DENY; default is DENY (good). But the config.yml default (if present) should be DENY. Verify `config.yml` default value.

### D. Test coverage gaps vs 18 cases
- Tests exist for cases 4, 9, 11, 15 (per commit message) plus others. Need to verify all 18 cases have dedicated tests. The 18-case list is in the plan; map each to a test method.

### E. BCrypt vendoring scope
- `BCrypt.java` is 28KB vendored. AuthMePasswords wraps it. This is a hash algorithm, not the data layer. Acceptable for thin vendor. But the broader "thin vendor" (schema-compatible authme table + hash verify + registration insert + authenticated-state tracking + events façade) is only partially done: schema compat done, hash verify done, registration insert (via AuthMeBridge → AuthMe plugin) NOT vendored, authenticated-state tracking (via AuthMe plugin) NOT vendored, events façade (via AuthMe plugin) NOT vendored.

### F. `AuthMeBridge` still uses `AuthMeApi.getInstance()`
- This is the runtime coupling. Until vendored, the plugin cannot be standalone.

### G. `workers.shutdownNow()` in onDisable
- shenanigans-core pattern: drain before shutdown. `shutdownNow()` is abrupt. Could lose in-flight writes. Consider `shutdown()` + awaitTermination. (Minor.)

### H. Storage single-thread executor not awaited before connection close
- `close()` shuts down executor then awaits 5s, then closes connection. OK but `shutdownNow()` on workers in LoginCore.onDisable happens before storage.close() — workers may still reference storage. Ordering seems OK (packetListener unregistered first). But workers.shutdownNow() is abrupt.

### I. No live E2E evidence
- Plan requires live premium + offline login E2E on test server. No evidence recorded. Test server has no AuthMe installed by default. To test, AuthMe must be restored alongside. This is an external dependency.

### J. `depend: [packetevents, AuthMe]` in plugin.yml
- Should be `depend: [packetevents]` only if standalone. Currently lists AuthMe.

## 4. Verdict

**Partial completion.** Phases 4, 5, 6a, 6b, 6c, 7 are substantially done with strong quality on classification, immutability, fail-closed defaults, and test coverage. However, the **standalone requirement is unmet** — the plugin still hard-depends on AuthMe as a runtime plugin, and the "thin vendor" strategy (vendoring minimal AuthMe data layer) was not executed. This is the single largest gap between the plan/ADR 0008 and the actual implementation.

## 5. Recommended next steps

1. **Decide**: vendor AuthMe data layer (option A) or formally accept bridge model (option B). If A, scope ~1–2k LOC: replace AuthMeBridge with vendored DataSource/PlayerAuth/registration; remove `AuthMe` from `depend` and `compileOnly`; add `AuthMe` data migration.
2. **Fix plugin.yml name**: commit to `AuthMe` (compat) per ADR 0008, or `login-core`. Update data folder references.
3. **Verify all 18 classification cases** have dedicated tests; fill gaps.
4. **Live E2E**: restore AuthMe on test server, deploy, run Craftwire scenarios, record evidence.
5. **Independent review**: launch security + architecture review agents (Phase 9) before final acceptance.

## 6. Files to examine
- `plugin.yml` (name, depend)
- `build.gradle.kts` (compileOnly authme-core)
- `LoginCore.java` (onEnable AuthMe check)
- `AuthMeBridge.java` (runtime coupling)
- `AuthMeLoginListener.java` (AuthMe event coupling)
- `Storage.java` (schema + migration — well done)
- `docs/plans/phase-4-7-plan.md` (original plan)
- `docs/adr/0008-identity-licensing-vendoring.md` (vendoring decision not executed)