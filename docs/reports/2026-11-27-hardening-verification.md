# 2026-11-27 hardening verification

Authoritative point-in-time verification report for the `login-core-hardening` branch at commit `0bf9876` (base `3a2f058`). It supersedes the platform and dependency claims in [final-acceptance.md](./final-acceptance.md), which is retained as historical evidence.

## Authoritative baseline

- **Production target: Paper 1.21.11+** — `paper-api:1.21.11-R0.1-SNAPSHOT` and `plugin.yml` `api-version: '1.21.11'`. A Java **25 runtime** is still required: the toolchain emits Java 25 bytecode even though Paper 1.21.11 itself permits Java 21. `plugin.yml` has no Java-version field, so the Java requirement is documented in README, AGENTS.md and ADR 0007, not in the descriptor.
- **PacketEvents is optional** — `softdepend: [packetevents]`, not a hard `depend`. Password login/registration works without it. In ENFORCE mode, registration of a new unclassified name requires a PacketEvents-free Mojang lookup returning NOT_PREMIUM; a premium or unavailable lookup is refused. Stored PREMIUM identities fail closed when PacketEvents is absent (kick, no password fallback).
- **Exemptions are gone** — no exemption command, table, report field, or grandfather bypass. The v2 schema migration drops the legacy `exempt` table.
- **Command surface:** `/logincore status [name]` and `/logincore migrate audit | migrate classify`. No `apply` flag and no exemption subcommands exist.
- The 26.3 server is a **local/dev test server only** (`runServer { minecraftVersion("26.3") }` stays pinned). It is not Paper 1.21.11 E2E.

## Implemented fixes (Tasks 1–8)

| Task | Fix | On-branch commit(s) |
| --- | --- | --- |
| 1 | Paper 1.21.11 compile/test baseline, `api-version: '1.21.11'`, MockBukkit 1.21, Java 25 CI workflow; guarded by `PlatformBaselineTest`. | `964e6ea` |
| 2 | UNKNOWN lookups always denied (the `PASS_THROUGH` lookup-failure path removed); exemption bypass removed from the decision engine; deprecated ALLOW config validated. | `b1ab760`, `dcca22e` |
| 3 | Exemption storage, admin commands, report fields and `migrate apply --exempt-conflicts` removed; schema v2 drops the legacy `exempt` table. | `52fca4f` |
| 4 | PacketEvents made optional via `PremiumSupport` / `PremiumSupportFactory` / `NoOpPremiumSupport`, `softdepend`, reflection boundary; `LoginCore` has no PE types. | `566b497` |
| 5 | Registration gated on a verified PE-free offline classification; stored PREMIUM and PE-unavailable paths fail closed. | `d708a19` |
| 6 | Pre-auth restriction coverage extended to block break/place, hand swap, vehicle enter, item pickup and projectile shooter, through one `cancelIfRestricted` seam. | `059a6f0` |
| 7 | One-time startup import of legacy AuthMe `premiumUUID` identities inside the schema-v2 transaction; malformed/conflicting rows skipped and reported. | `5366760` (cherry-picked from `67c4f54`) |
| 8 | Hashing and database waits moved off the main thread; injected ban predicate covers NAME/IP/UUID bans before any commit, registration or grant; event marker inserted only after success. | `e8a1b79`, `0bf9876` (cherry-picked from `551738a`, `ee21e86`) |

Task 9 (this report) updates README.md, AGENTS.md, ADR 0007, the `final-acceptance.md` banner, and adds this report. No source, build or descriptor files were changed by Task 9.

## Full verification (exact mandated command)

Run from the worktree root `C:\Users\marcus panget\.local\share\opencode\worktree\8ee69e\login-core-hardening`:

```text
./gradlew.bat clean check shadowJar --rerun-tasks --console=plain
```

Exact tail of the output:

```text
> Task :test
> Task :check

[Incubating] Problems report is available at: file:///C:/Users/marcus%20panget/.local/share/opencode/worktree/8ee69e/login-core-hardening/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 49s
7 actionable tasks: 7 executed
Configuration cache entry reused.
GRADLE_EXIT=0
```

- **Result:** `BUILD SUCCESSFUL`, 7/7 tasks executed, exit code 0. `--rerun-tasks` forced a full rebuild; the configuration cache entry was reused but every task executed.
- **Tests:** 30 XML suites, **183 tests**, 0 failures, 0 errors, 0 skipped (from `build/test-results/test/*.xml`).
- **Artifact:** `build/libs/login-core-1.0-SNAPSHOT.jar`, 155194 bytes, SHA256 `C1F84F73367DDC0A880A17CA1665242D2D080030A5B787A4AAFF7C003EA0BEFC`.
- **Non-failing warnings:** pre-existing `[dep-ann]` and `[removal]` javac warnings, and SQLite/Netty native-access warnings. They do not fail `check`; they are carried forward, not fixed in this docs-only task.

## Whole-diff review

`git diff --stat 3a2f058..HEAD^` (implementation commits only) reports **37 files changed, 1491 insertions(+), 546 deletions(-)**, while `git diff --stat 3a2f058..HEAD` including Task 9 docs reports **42 files changed, 1590 insertions(+), 569 deletions(-)**. Every changed file was read once. Findings:

- **Exemptions:** no exemption command, admin argument, report field, storage API, or grandfather bypass remains in `src/main`. The only `exempt` occurrence in `src/main` is the intentional one-time `DROP TABLE IF EXISTS exempt` legacy cleanup in `Storage.java` (schema-v2 migration), plus its explanatory comment. This is required by the design and was deliberately retained.
- **PacketEvents dependency:** `src/main/resources/plugin.yml` declares `softdepend: [packetevents]`; no hard `depend: [packetevents]` remains. Older `depend: [packetevents]` strings survive only in historical plans, evidence and review reports (including the now-bannered `final-acceptance.md`) as point-in-time records, not active claims.
- **PE import boundary:** `com.github.retrooper` imports in `src/main/java` exist only in `handshake/PremiumHandshakeSupport.java` and `handshake/PremiumLoginListener.java`. `LoginCore.java` has none. Pre-existing PE imports in `PremiumLoginListenerCleanupTest.java` and `PremiumSessionVerificationTest.java` are test fixtures and are allowed by the controller ruling.
- **Identity/commands unchanged:** runtime plugin name `AuthMe`, commands `logincore`, `login`, `register`, `changepassword`, permission `logincore.admin` (default op). `/logincore` roots are `status` and `migrate`; migration subcommands are `audit` and `classify` only.
- No unrelated files changed; the verified working tree was clean after the Task 8 commits and before this task's doc edits.

## Unverified

- **Live premium-client handshake — UNVERIFIED.** No premium account is available and the local test server is offline/cracked, so no real cryptographic premium join was exercised. Premium verification is covered by unit/static tests only.
- **Live Paper 1.21.11 E2E — UNVERIFIED.** No local Paper 1.21.11 server exists. The 1.21.11 baseline is compile-time (`paper-api:1.21.11-R0.1-SNAPSHOT`) plus MockBukkit 1.21 tests; the pinned `runServer` 26.3 server is a local/dev test server and is not a 1.21.11 E2E result.

No claim in this report should be read as a live-server pass for either item.

## Carried-forward minors

Non-blocking items recorded in the SDD ledger: pre-existing javac/native-access warnings, workflow permissions/cache hardening, a duplicate UNKNOWN-denial test, deprecated test-only constructors, and small test-double/counter redundancies. None changes behavior or the verification result above.
