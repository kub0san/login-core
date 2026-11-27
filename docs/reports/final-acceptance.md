# Final Acceptance

> Report for `docs/reports/final-acceptance.md`.
> Evidence baseline: committed HEAD `db78480` (`docs: clarify compatibility and event contract`) on branch `standalone-password-auth` in worktree `C:\Projects\.worktrees\login-core-standalone`, with `792afd5 fix: close pre-auth password oracle and throttle bypass` committed. No push performed.
> Criteria source: `docs/plans/full-implementation-handoff.md:69-92` (Phase G), cross-checked against the available final-acceptance outline at `C:\Projects\.worktrees\login-core-standalone\.superpowers\sdd\full-implementation-handoff\final-acceptance-outline.md`.
> Live standalone E2E completed 2026-11-27 08:43:32 (`RUNNER_EXIT=0`) on Craftwire instance `server-9`; retained transcripts at `C:\Projects\.worktrees\login-core-standalone\docs\evidence\standalone-e2e` and `C:\Users\marcus panget\AppData\Local\Temp\opencode\standalone-e2e-20261127-084332`. Status labels: `PASS` (evidence supplied), `PASS (static)` (no live assertion), `PARTIAL`, `PENDING`.

## 1. Criteria checklist (22 rows)

| # | Criterion | Status | Evidence source |
| --- | --- | --- | --- |
| 1 | Existing repo history preserved | PASS | HEAD `db78480`; history preserved; origin `https://github.com/kub0san/login-core.git`; no push |
| 2 | Repo + local project use login-core naming | PASS | HEAD `db78480`; package `com.hizeric.logincore`; artifact `login-core-1.0-SNAPSHOT.jar`; `plugin.yml` runtime identity `AuthMe` is deliberate per ADR 0008 |
| 3 | JDK 25 and Java 25 bytecode | PASS | Toolchain 25; `javap -verbose` on `com.hizeric.logincore.LoginCore`: minor 0, major 69 |
| 4 | Paper 1.21.11+ (target 26.3) compatibility | PASS (static) | `api-version: '26.3'`; Paper API `26.3.build.200-stable`; target Paper 26.3, local Leaf `26.3-27-06c9c91 (MC: 26.3)` |
| 5 | SQLite only | PASS | SQLite JDBC `3.50.3.0`; forbidden-ref scan for `mysql`, `postgre`, `h2`, `flatfile` = NONE |
| 6 | External importers removed | PASS | Forbidden-ref scan (`fr.xephi`, `AuthMeApi`) = NONE; zero `fr/xephi` jar entries |
| 7 | Alternative DB support removed | PASS | Build file and `src/main` scan show no MySQL/PostgreSQL/H2/flatfile backend |
| 8 | Unnecessary AuthMe features removed | PASS (static) | Commands reduced to 4; scan for `premiumToggle`, `freemium` = NONE; no `/premium` or `/freemium` command live either |
| 9 | `/premium` and `/freemium` do not exist | PASS | `plugin.yml` commands only `logincore`, `login`, `register`, `changepassword`; scan = NONE |
| 10 | No admin identity-toggle commands | PASS | `plugin.yml` command list and `logincore.admin` (default op) only; no toggle command declared |
| 11 | PREMIUM/OFFLINE classification permanent | PASS (static) | Phase G criterion; no identity-toggle command in descriptor or source scan |
| 12 | Premium verification cryptographic | PASS (static) | Phase G criterion; live premium-bypass scenario not runnable on offline test server |
| 13 | Premium auth has no password fallback | PASS (static) | Phase G criterion; live assertion not runnable on offline server |
| 14 | Mojang failures never grant auth | PASS (static) | Phase G criterion; live assertion not runnable on offline server |
| 15 | Offline auth works without PacketEvents (criterion wording unsatisfiable; reported as PARTIAL/static) | PARTIAL | Password paths do not call PacketEvents and no AuthMe is installed, but the plugin hard-depends on PacketEvents as a load dependency, so no PacketEvents-free live run exists. Architecture review flagged the original ✓ wording as unsatisfiable; reported as `PARTIAL`, not `PASS`. |
| 16 | Existing AuthMe account compatibility evaluated + tested | PARTIAL | `docs/reports/pre-cutover-e2e.md`: `DONE_WITH_CONCERNS`; migration fidelity unresolved |
| 17 | Plugin standalone | PASS | No AuthMe/Seam jar or data; zero `fr/xephi` jar entries; `depend: [packetevents]`; live probe returned `authmeClass=ABSENT` with `main=com.hizeric.logincore.LoginCore`; standalone live E2E PASS 2026-11-27 08:43:32 on instance `server-9` |
| 18 | Distributable JAR builds | PASS | `BUILD SUCCESSFUL in 37s`; 7/7 tasks executed; `GRADLE_EXIT=0`; jar 147982 bytes; SHA256 `85BE1C6905BF5361D9973A43D13FA5F68A642939CF3BF9834AE5BBABA1DB10AE` |
| 19 | Automated tests pass | PASS | 28 XML suites; 153 tests; 0 failures, 0 errors, 0 skipped; `git diff --check` exit 0 |
| 20 | Live testing evidence | PASS | `RUNNER_EXIT=0`; preflight 7/7 (0 tool errors), pass1 15/15 (0), pass2 20/20 (5 expected `BOT_NOT_FOUND`, 0 unexpected), pass3 5/5 (0). Exact strings observed for 11 of 12 verification-report rows; row 3 (`Log in to move.`) is PARTIAL — movement blocked but exact string absent. Retained transcripts at the paths in the header. |
| 21 | Independent review findings resolved or documented | PASS (documented) | Phase F security re-review APPROVED (no Critical/Important) after TDD fixes in `792afd5`; architecture APPROVED_WITH_MINORS with Important doc claims fixed and criterion #15 clarified; compatibility events are login-core-owned/source-level only (precompiled AuthMe listeners cannot receive them). Minor findings documented in verification report section 9 |
| 22 | Final report covers all required topics | PASS | Section 3 narrative covers every topic required by `full-implementation-handoff.md:92`: repo migration, final architecture, features retained/removed, premium verification, permanent identity classification, legacy account handling, SQLite compatibility, Java/Paper compat, dependencies, build results, automated tests, live-server evidence, review findings, remaining limitations |

All 22 rows are ready for publication. Open limitations remain: criterion #15 (`PARTIAL`, PacketEvents hard dependency), #16 (`PARTIAL`, migration fidelity), and verification-report section 5 row 3 (movement message `PARTIAL`).

## 2. Limitations

- Live standalone E2E completed 2026-11-27 08:43:32 with `RUNNER_EXIT=0` on instance `server-9`; all four batches ran. One assertion is not a pass: the exact `Log in to move.` string was not observed (verification report section 5 row 3 is `PARTIAL`). The five `BOT_NOT_FOUND` results in pass2 are expected consequences of bot disconnect, not plugin errors. An earlier 08:30:44 attempt was BLOCKED at preflight (stale `plugins\AuthMe` directory) and was superseded by the final run; the runner was fixed.
- Both Phase F reviews are supplied: security APPROVED, architecture APPROVED_WITH_MINORS (see verification report section 9 and narrative section 3). No live sign-off is claimed.
- `TestServer.ps1 verify` still fails only on `no live logs/crash-reports leftovers` (rotated live logs 9, crash reports 0); a fully green `verify` must not be claimed.
- Offline/cracked test server prevents live premium bypass, Mojang-failure and real interop assertions.
- Live event delivery: AuthMe event delivery is unit-covered only; Craftwire eval listeners were cancelled, so no live event-fidelity assertion exists. Compatibility events are login-core-owned and source-level only — precompiled AuthMe listeners cannot receive them without rebuilding against login-core.
- PacketEvents remains a hard load dependency; no PacketEvents-free live run exists (criterion #15 is `PARTIAL`, not `PASS`).
- Task 3 remains `DONE_WITH_CONCERNS`; its concerns remain open: real AuthMe interop/events, migration fidelity, premium bypass, Strategy 1 and timeout raw transcripts. The timeout raw transcript is now retained in the standalone run, but the Task 3 gap remains.
- The final-acceptance outline at `C:\Projects\.worktrees\login-core-standalone\.superpowers\sdd\full-implementation-handoff\final-acceptance-outline.md` was available and cross-checked; its stated limitations (notably #9 on live event delivery) are preserved here.
- No push was performed.

## 3. Final narrative

**Repo migration.** The project is `login-core` at committed HEAD `db78480` on branch `standalone-password-auth` in worktree `login-core-standalone`, origin `https://github.com/kub0san/login-core.git`, with history preserved and no push performed. The package is `com.hizeric.logincore` and the artifact is `login-core-1.0-SNAPSHOT.jar`. The `plugin.yml` name `AuthMe` is a deliberate runtime identity decision recorded in ADR 0008, not a surviving dependency.

**Final architecture.** Storage is SQLite-backed through `Storage`, with handshake/session handling built on PacketEvents 2.14.0 and command/decision/migration/mojang packages under `com.hizeric.logincore`. The plugin declares only `depend: [packetevents]` and exposes only `logincore`, `login`, `register` and `changepassword`, with `logincore.admin` defaulting to op. PacketEvents is a hard load dependency for handshake work.

**Features retained and removed.** Retained: registration, login, password change, pre-auth chat/command/movement blocking, session and timeout handling, brute-force throttling, permanent identity classification and cryptographic premium verification. Removed: external AuthMe API/importer coupling, alternative database backends, `/premium` and `/freemium`, admin identity-toggle commands and other unnecessary AuthMe features. The forbidden-ref scan over `src/main` returned NONE, and the built jar contains zero `fr/xephi` entries.

**Premium verification and permanent classification.** Premium identity is verified cryptographically; there is no password fallback for premium accounts and Mojang lookup failures never grant authentication. Classification is permanent with no runtime toggle. These are confirmed at code/static level; live premium-bypass behavior is not asserted because the local server is offline/cracked.

**Legacy account handling.** Existing AuthMe account compatibility was evaluated and partially tested in Task 3 (`docs/reports/pre-cutover-e2e.md`, `DONE_WITH_CONCERNS`). Migration fidelity remains unresolved and is carried forward as an open limitation, not a pass.

**SQLite compatibility.** SQLite JDBC `3.50.3.0` is the only database dependency; no MySQL, PostgreSQL, H2 or flatfile backend references remain.

**Java and Paper compatibility.** Toolchain 25 produces Java 25 bytecode (major 69, minor 0). The plugin declares `api-version: '26.3'` against Paper API `26.3.build.200-stable`; the target is Paper 26.3 and the local test server is Leaf `26.3-27-06c9c91 (MC: 26.3)`.

**Dependencies and build.** Dependencies are Paper API `26.3.build.200-stable`, PacketEvents `2.14.0` and SQLite JDBC `3.50.3.0`; no `fr.xephi` dependency. A fresh `.\gradlew.bat clean check shadowJar --rerun-tasks --console=plain` returned `BUILD SUCCESSFUL in 37s`, `7 actionable tasks: 7 executed`, `GRADLE_EXIT=0`, producing a 147982-byte jar with SHA256 `85BE1C6905BF5361D9973A43D13FA5F68A642939CF3BF9834AE5BBABA1DB10AE`. `git diff --check` exited 0.

**Automated tests.** 28 XML suites, 153 tests, 0 failures, 0 errors, 0 skipped.

**Live-server evidence.** The standalone E2E completed 2026-11-27 08:43:32 on Craftwire instance `server-9` with `RUNNER_EXIT=0`; it runs in a single shell because the server child dies at the tool-shell boundary. Preflight returned 7/7 results with zero tool errors, pass1 15/15 with zero tool errors, pass2 20/20 with five expected `BOT_NOT_FOUND` errors (bot disconnects after kick) and zero unexpected errors, and pass3 5/5 with zero errors. `list_instances` -> `server_info` and the plugin/command-owner probe ran before assertions: Leaf `26.3-27-06c9c91 (MC: 26.3)`, `AuthMe version 1.0-SNAPSHOT main=com.hizeric.logincore.LoginCore enabled=true`, all four commands owned by `AuthMe`, `authmeClass=ABSENT`, `loginHandler=true registerHandler=true`, exceptions total 0. Exact strings were observed for registration, duplicate registration, password change, login, incorrect-password counts 4/3/2/1, brute-force kick (two `BruteBot lost connection: Too many failed login attempts. Reconnect and try again.` lines, the second after respawn with no fresh window), timeout kick (`TimeoutBot lost connection: Login timed out. Reconnect and log in.` after 61,346 ms), pre-auth chat/command blocking (`Log in first: /login or /register.`) and `/logincore status` permission denial (`You do not have permission to use this command.`). The exact `Log in to move.` string was not observed; movement was blocked (`move_to` timeout, position unchanged) and that row is `PARTIAL`. Cleanup left manifests byte-identical, qcart files byte-identical, player/skin counts matching baseline (advancements 2, data 4, stats 2, skins 74) and only port 7777 listening; `TestServer.ps1 verify` still fails only on the known rotated-log leftover (rotated logs 9, crash reports 0).

**Independent review findings.** Phase F architecture review: APPROVED_WITH_MINORS. Its Important items (false API-singleton compatibility claim in ADR 0002, missing compatibility-event firing contract, and the unsatisfiable "without PacketEvents" wording) are fixed in the docs/event javadocs or explicitly clarified in row 15. Compatibility events are login-core-owned and source-level only; precompiled AuthMe listeners cannot receive them without rebuilding against login-core. Phase F security re-review of the committed fixes: APPROVED with no Critical or Important findings — the two Important defects (unthrottled pre-auth `/changepassword` password oracle and a brute-force counter that reset on kick) were fixed with TDD in `792afd5` (reject unauthenticated `/changepassword` after the PREMIUM check before any old-password verification; retain the five-failure counter through kick, clearing it only on success or window expiry). Minor residuals (failure-counter map growth, no fresh-window-after-60s test, plus earlier minor items) are documented in `full-implementation-verification.md` section 9. Task 1/2/3/4/5 review outcomes and carried-forward Task 3 concerns remain as recorded in the SDD and planning files.

**Remaining limitations.** The movement-blocking row is `PARTIAL` (exact `Log in to move.` not observed); real AuthMe interop/events and migration fidelity remain unresolved (Task 3 `DONE_WITH_CONCERNS`); premium-bypass, Mojang-failure and interop/event fidelity cannot be asserted on an offline/cracked server with Craftwire eval listeners cancelled; the timeout raw transcript is retained in this run but the Task 3 Strategy 1/timeout gap remains; PacketEvents is a hard load dependency (#15 `PARTIAL`); and `TestServer.ps1 verify` retains its rotated-log failure. No push was performed and no fully green acceptance is claimed.
