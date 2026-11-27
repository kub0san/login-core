# Full Implementation Verification

> Report for `docs/reports/full-implementation-verification.md`.
> Evidence baseline: committed HEAD `db78480` (`docs: clarify compatibility and event contract`) on branch `standalone-password-auth` in worktree `C:\Projects\.worktrees\login-core-standalone`, with `792afd5 fix: close pre-auth password oracle and throttle bypass` committed. No push performed.
> Final standalone live E2E completed 2026-11-27 08:43:32: `RUNNER_EXIT=0`, Craftwire instance `server-9`. Retained transcripts: `C:\Projects\.worktrees\login-core-standalone\docs\evidence\standalone-e2e` (read-only copy) and `C:\Users\marcus panget\AppData\Local\Temp\opencode\standalone-e2e-20261127-084332` (equivalent run dir). Every row below is recorded from those transcripts; a green handshake alone is not counted as a pass.
> Status labels: `PASS` (exact observed message/result), `PARTIAL` (behaviour observed but exact expected string absent), `PENDING` (no evidence).

## 1. Scope and verdict

Scope: verify that `login-core` at HEAD `db78480` is a standalone, minimal, SQLite-only Paper authentication plugin built for Java 25 / Paper 26.3, and record the evidence required for final acceptance.

Verdict:

- Static, build, artifact, bytecode and source-level verification at HEAD `db78480`: **PASS**.
- Live standalone end-to-end verification (2026-11-27 08:43:32, instance `server-9`): **PASS**, with one `PARTIAL` row — the exact `Log in to move.` string was not observed; movement was blocked (timeout, position unchanged) but the message is absent from retained bot messages.
- Phase F independent security review (re-review of committed fixes): **APPROVED** (no Critical, no Important).
- Phase F independent architecture review: **APPROVED_WITH_MINORS** (Important documentation items addressed; one Phase G wording clarified).

E2E run summary: preflight 7/7 results with zero tool errors; pass1 15/15 with zero tool errors; pass2 20/20 with 5 expected `BOT_NOT_FOUND` errors and 0 unexpected; pass3 5/5 with zero errors. `RUNNER_EXIT=0`.

Task 3 (`docs/reports/pre-cutover-e2e.md`) remains `DONE_WITH_CONCERNS` and is treated as prior evidence, not as a pass for this cutover. Its concerns are carried forward and are not cleared by this run.

## 2. Build evidence

| Item | Evidence |
| --- | --- |
| Fix commit | `792afd5 fix: close pre-auth password oracle and throttle bypass` |
| Final HEAD | `db78480 docs: clarify compatibility and event contract` (branch `standalone-password-auth`, worktree `login-core-standalone`) |
| History / remote | History preserved; origin `https://github.com/kub0san/login-core.git`; no push performed |
| Command | `.\gradlew.bat clean check shadowJar --rerun-tasks --console=plain` |
| Result | `BUILD SUCCESSFUL in 37s`; `7 actionable tasks: 7 executed`; `GRADLE_EXIT=0` |
| XML suites | 28 |
| Tests | 153 total; failures 0; errors 0; skipped 0 |
| Artifact | `build\libs\login-core-1.0-SNAPSHOT.jar`, 147982 bytes |
| Artifact SHA256 | `85BE1C6905BF5361D9973A43D13FA5F68A642939CF3BF9834AE5BBABA1DB10AE` |
| Working-tree hygiene | `git diff --check` exit 0 |

The `--rerun-tasks` flag means all seven actionable tasks executed rather than being served from cache.

## 3. Static jar and plugin evidence

| Check | Result |
| --- | --- |
| Java toolchain | 25 |
| Paper API | `26.3.build.200-stable` |
| PacketEvents | `2.14.0` |
| SQLite JDBC | `3.50.3.0` |
| `fr.xephi` dependency | None declared in build file |
| Bytecode (`javap -verbose com.hizeric.logincore.LoginCore`) | minor 0, major 69 (Java 25) |
| Forbidden-ref scan over `src/main` (`fr.xephi`, `AuthMeApi`, `mysql`, `postgre`, `h2`, `flatfile`, `premiumToggle`, `freemium`) | NONE |
| Jar contents | Zero `fr/xephi` entries |

`plugin.yml` (verified):

- `name: AuthMe` — deliberate runtime identity per ADR 0008; not a dependency on AuthMe.
- `main: com.hizeric.logincore.LoginCore`
- `api-version: '26.3'`
- `depend: [packetevents]`
- Commands only: `logincore`, `login`, `register`, `changepassword`
- Permission `logincore.admin`, default `op`

There is no `/premium`, `/freemium`, or admin identity-toggle command in the descriptor.

## 4. Test-server preflight (standalone gates)

Preconditions checked or required before the standalone live E2E:

1. No AuthMe or Seam jar installed, and no AuthMe/Seam data folder present.
2. Deployed jar contains zero `fr/xephi` entries.
3. `plugin.yml` declares `depend: [packetevents]` only; no AuthMe dependency.
4. Craftwire identity and command-owner probe completed before assertions (bots have no renderer; a green handshake is not a pass). Observed: Leaf `26.3-27-06c9c91 (MC: 26.3)`; plugin `AuthMe version 1.0-SNAPSHOT main=com.hizeric.logincore.LoginCore enabled=true`; all four commands (`login`, `register`, `changepassword`, `logincore`) owned by `AuthMe` (the deliberate runtime identity); `authmeClass=ABSENT`; `loginHandler=true registerHandler=true`; exceptions total 0. `server_info` -> `server_eval` -> plugin/command ownership was completed before assertions.
5. No live premium-bypass scenario is in scope: the local server is offline/cracked.
6. No live AuthMe interop, AuthMe event delivery, or migration-fidelity scenario is in scope. Event delivery is covered only by unit tests; Craftwire eval listeners were cancelled, so no live event assertion was captured.
7. `TestServer.ps1 verify` has a known pre-existing rotated-log failure. It failed only on `no live logs/crash-reports leftovers` (rotated live logs 9, crash reports 0); a fully green `verify` must not be claimed.
8. An earlier attempt at 08:30:44 was correctly BLOCKED at preflight by a stale `plugins\AuthMe` directory. The runner was fixed and the final 08:43:32 run deployed and asserted cleanly; the blocked attempt contributed no result.

## 5. Craftwire E2E assertion table

Statuses are recorded from the retained transcripts of the 2026-11-27 08:43:32 run (`RUNNER_EXIT=0`, instance `server-9`); expected strings are exact and taken from source. `list_instances` -> `server_info` and the identity/command-owner probe (section 4) were completed before assertions.

| # | Scenario | Expected exact message / result | Observed status |
| --- | --- | --- | --- |
| 1 | Chat before authentication | `Log in first: /login or /register.` | **PASS** — exact string on join and pre-auth chat (pass1), again after rejoin (pass2) |
| 2 | Command before authentication | `Log in first: /login or /register.` | **PASS** — `/spawn` cancelled with exact string (pass1, pass2) |
| 3 | Movement before authentication | `Log in to move.` | **PARTIAL** — `move_to` returned `reached:false, reason:timeout` and `state` stayed y=69, but the exact `Log in to move.` string is NOT present in retained bot messages. Not claimed as a pass. |
| 4 | Successful registration | `Account registered. You are now logged in.` | **PASS** — exact string (pass1) |
| 5 | Duplicate registration | `That account is already registered. Use /login.` | **PASS** — exact string (pass1) |
| 6 | Successful changepassword | `Your password changed.` | **PASS** — exact string retained (pass1). The pre-auth `/changepassword PwTest123 PwHack999` returned `Log in first: /login or /register.` and did not change the password: `/login PwTest123` was not accepted afterwards, while `/login PwNew456` succeeded after the change. |
| 7 | Successful login | `You are now logged in.` | **PASS** — exact string (pass1 and pass2 after reconnect) |
| 8 | Incorrect password, first failure | `Incorrect password. 4 attempts remaining.` | **PASS** — exact string (pass1, pass2) |
| 9 | Brute-force kick (5 failures / 60s) | `Too many failed login attempts. Reconnect and try again.` | **PASS** — four wrong logins returned exact `Incorrect password. 4/3/2/1 attempts remaining.`; the fifth caused kick. `logs` has exactly two `BruteBot lost connection: Too many failed login attempts. Reconnect and try again.` lines; the second, after respawn, proves there was no fresh five-guess window. The 5 `BOT_NOT_FOUND` results are expected consequences of bot disconnect, not plugin errors. |
| 10 | Login timeout kick (60s timeout, 5s checker) | `Login timed out. Reconnect and log in.` | **PASS** — `wait_for` matched `TimeoutBot lost connection: Login timed out. Reconnect and log in.` after 61,346 ms; `logs` has the same line; exceptions total 0 |
| 11 | `/logincore status` issued by a non-op bot | `You do not have permission to use this command.` | **PASS** — exact string (pass1) |
| 12 | Craftwire identity / command-owner probe | Bot identity and command ownership confirmed before assertions | **PASS** — see section 4; completed before assertions |

Thresholds asserted: brute force 5 failures within 60s; timeout 60s with a 5s checker. Reconnect after quit was re-restricted with `Log in first: /login or /register.` (pass2). The standalone live E2E runs in a single shell because the server child process dies at the tool-shell boundary. Batch totals: preflight 7/7 (0 tool errors), pass1 15/15 (0), pass2 20/20 (5 expected `BOT_NOT_FOUND`, 0 unexpected), pass3 5/5 (0); `RUNNER_EXIT=0`.

## 6. Comparison with Task 3

Task 3 source: `docs/reports/pre-cutover-e2e.md` — overall `DONE_WITH_CONCERNS`.

| Area | Task 3 (pre-cutover) | This verification (HEAD `db78480`) |
| --- | --- | --- |
| Real AuthMe interop / events | Exercised, concerns recorded | Out of scope for standalone run; no live result; Craftwire eval listeners cancelled |
| Migration fidelity | Unresolved | Unresolved; not re-tested here |
| Premium bypass | Unresolved | Not testable on offline/cracked test server |
| Strategy 1 / timeout raw transcripts | Unresolved | Timeout raw transcript is now retained in this run (`pass3.result.json`, `latest.log` in the evidence dir), but the Task 3 Strategy 1 / timeout gap remains open |
| Standalone operation | Not established | Static gates pass; live E2E PASS at 08:43:32 (instance `server-9`), with the movement-message row PARTIAL |
| Build / unit tests | Prior baseline | Fresh rerun: 28 suites, 153 tests, 0 failures/errors/skipped |

Task 3 concerns are carried forward; they are not cleared by the live standalone run.

## 7. Cleanup

- Protected player data: `qcart`, UUID `befc5d95-fd63-4cae-b0f5-7c2fb271c699`, 4 player files.
- The runner copied the full transcript set to `C:\Projects\.worktrees\login-core-standalone\docs\evidence\standalone-e2e`; the retained equivalent run dir is `C:\Users\marcus panget\AppData\Local\Temp\opencode\standalone-e2e-20261127-084332`.
- Manifest before and after cleanup are byte-identical (`manifest-before.json` vs `manifest-after-cleanup.json`); live player/skin counts match baseline (advancements 2, data 4, stats 2, skins 74); all four qcart files are byte-identical.
- No AuthMe/Seam/deployed-jar artifacts remain; only port 7777 is listening.
- A full `reset` was deliberately not used: live world ~4.79 GB, baseline ~5.01 GB, and C: had ~1.6 GB free, so a restore of that size risked exhausting the volume.
- `TestServer.ps1 verify` still fails only on `no live logs/crash-reports leftovers` (rotated live logs 9, crash reports 0). It is not fully green; no fully green `verify` is claimed.

## 8. Limitations

- The exact `Log in to move.` string was not observed live. Movement was blocked (`move_to` timeout, position unchanged) but the exact string is absent from retained bot messages; section 5 row 3 is `PARTIAL`, not a pass.
- Real AuthMe interop and migration fidelity are out of scope / unresolved; no live interop assertion was captured. Task 3 remains `DONE_WITH_CONCERNS`.
- Premium lookup, premium bypass and Mojang-failure behavior cannot be exercised on the offline/cracked test server.
- AuthMe event delivery is unit-covered only; Craftwire eval listeners were cancelled, so there is no live event-fidelity assertion. Compatibility events are login-core-owned and source-level only — precompiled AuthMe listeners cannot receive them without rebuilding against login-core.
- PacketEvents remains a hard load dependency; no PacketEvents-free live run exists (acceptance criterion #15 is `PARTIAL`/static, not `PASS`).
- Task 3 concerns (Strategy 1 / timeout raw transcripts) remain open: the timeout raw transcript is retained in this run, but the Task 3 gap remains.
- `TestServer.ps1 verify` has a known pre-existing rotated-log failure (rotated live logs 9, crash reports 0); fully green `verify` is not claimed.
- The five `BOT_NOT_FOUND` results in pass2 are expected consequences of the successful post-kick bot disconnect, not plugin errors.
- Both Phase F reviews are supplied (section 9): security APPROVED, architecture APPROVED_WITH_MINORS. No live sign-off is claimed.
- No push was performed; origin is recorded but not updated.

## 9. Phase F independent review

| Review | Verdict | Findings and disposition |
| --- | --- | --- |
| Security (`login-core` @ `db78480`, read-only re-review of committed fixes) | APPROVED; no Critical or Important | The two Important defects from the initial review were fixed with TDD and committed in `792afd5`: (1) unauthenticated `/changepassword` is now rejected after the PREMIUM check before any old-password verification, closing the pre-auth password oracle; (2) the five-failure counter is retained through kick and cleared only on success or window expiry, closing the reconnect throttle bypass. Re-review found no Critical/Important findings. Minor residuals: retained failure-counter map growth (no TTL/disconnect cleanup) and no test for a fresh window after 60s. Earlier minor items (stale pre-join `AuthState`, `RejectedExecutionException` on worker submission, legacy naming/ChatColor, stale `authme.isLogged` on quit) remain carried-forward risks. |
| Architecture and reduction (`login-core` @ `db78480`, read-only) | APPROVED_WITH_MINORS | Important 1: ADR 0002 claimed API-singleton compatibility; fixed by removing the claim and documenting that downstream AuthMe API/event consumers must rebuild against login-core. Important 2: compatibility events lacked a firing contract; fixed in `README.md` and the two event javadocs. Important 3: Phase G's "Offline auth works without PacketEvents" wording is unsatisfiable because PacketEvents remains a hard load dependency; the criterion is clarified in the final acceptance report as `PARTIAL`/static rather than `PASS`. Compatibility events are login-core-owned and source-level only; precompiled AuthMe listeners cannot receive them. Minors (stale pre-join `AuthState`, legacy `ChatColor` in `AdminCommand`, partial command validation, stale pre-cutover docs) are carried forward as documented risks. |

Security controls confirmed by the security review: BCrypt `$2a/$2b/$2y` compatibility with fail-closed unknown hashes, OFFLINE classification cannot be written from an UNKNOWN lookup, immutable classification, commit-before-grant with transactional rollback, premium fail-closed on NOT_VERIFIED or name/UUID mismatch, and broad fail-closed pre-auth event coverage. Architecture confirmed no `fr.xephi` dependency/import/class, the correct `plugin.yml` identity and dependency, removal of external importers and alternative DB support, complete command/listener registration, and login-core-owned compatibility events.
