# Pre-cutover E2E verification — login-core standalone password auth

- Date: 2026-11-27
- Worktree: `C:\Projects\.worktrees\login-core-standalone`, branch `standalone-password-auth`, HEAD `909678caf736b7b5592c040c70a7e8ad73ed589e` (clean before and after, no tracked source edits).
- Test server: `C:\Tools\global-test-server-26.3` (Leaf `26.3-27-ver/26.3@06c9c91`, API `26.3.build.27-alpha`, Java 25, game port 25566, offline mode).
- Assertion owner: Craftwire 0.11.2 shared HTTP hub at `http://127.0.0.1:7777/mcp` (token read from `~/.craftwire/http.json`, deliberately **not** reproduced here). The stub-run pass verdict and assertions 1–11 and 13 are backed by retained real tool results in `cw-batch1-result.json` and `cw-batch2-result.json` (`list_instances` → `server_info` → command/chat/bot calls), never by handshake or log readiness alone. Strategy 1 and assertion #12 are report-only because their raw result transcripts were not retained (see Limitations).
- Verdict: **DONE_WITH_CONCERNS** — login-core's own password auth passes E2E against an authorized test-only `fr.xephi.*` seam stub. Real AuthMe jar interop is **not** proven and is explicitly out of scope for this verdict (see Limitations).

## 1. Build evidence (real worktree)

Command (from worktree), output captured verbatim:

```text
PS> .\gradlew.bat clean check shadowJar
Reusing configuration cache.
> Task :checkSourceEncoding
> Task :clean
> Task :processTestResources NO-SOURCE
> Task :compileJava FROM-CACHE
> Task :processResources
> Task :classes
> Task :compileTestJava FROM-CACHE
> Task :testClasses UP-TO-DATE
> Task :test FROM-CACHE
> Task :check
> Task :shadowJar

BUILD SUCCESSFUL in 1s
7 actionable tasks: 4 executed, 3 from cache
Configuration cache entry reused.
```

`test` was `FROM-CACHE`; no fresh unit-test execution was forced in this session.

Baseline before any change:

```text
PS> .\TestServer.ps1 snapshot pre-cutover-e2e
OK baseline created: ...\baselines\pre-cutover-e2e-20261127-070654
    (world files=2228, plugin-data files=1401; reason: agent snapshot)
```

## 2. Strategy 1 — real AuthMe jar: attempted, honest interop failure

Handoff rule requires keeping the AuthMe dependency intact, so the real jar was tried first.

Facts:
- `C:\Tools\_archive\removed-auth-plugins-20261125\plugins\AuthMe-6.0.1-Paper.jar` — 1,153,596 bytes, loaded as `AuthMe 6.0.1-b2770`. The adjacent stale `AuthMePAL-1.0-SNAPSHOT.jar` was **not** used.
- The worktree `plugin.yml` declares `name: AuthMe` and `depend: [packetevents, AuthMe]`. To avoid duplicate plugin identity, a throwaway copy was made at `C:\Users\marcus panget\AppData\Local\Temp\opencode\login-core-e2e-copy` and **only** its copied `src/main/resources/plugin.yml` line 1 was changed from `name: AuthMe` to `name: authmePAL`. `depend: [packetevents, AuthMe]` and all command labels were left unchanged. Built with that copy's wrapper:

```text
PS> .\gradlew.bat clean shadowJar
BUILD SUCCESSFUL in 1s
```

Jar `login-core-1.0-SNAPSHOT.jar` (147,069 bytes), verified embedded `plugin.yml`:

```yaml
name: authmePAL
version: '1.0-SNAPSHOT'
main: com.hizeric.logincore.LoginCore
api-version: '26.3'
load: POSTWORLD
depend: [packetevents, AuthMe]
commands: { logincore, login, register, changepassword }
```

Both jars copied into `plugins\` manually (`TestServer.ps1 deploy authmePAL` is broken: it builds the empty `C:\Projects\authmePAL`), then `.\TestServer.ps1 restart`.

Craftwire (instance `server-1`, name `global-test-server-26.3`) `server_info` plugin list proved **both** enabled:
`AuthMe 6.0.1-b2770 enabled=true`, `authmePAL 1.0-SNAPSHOT enabled=true`.

Command ownership via `server_eval` (`server.getCommandMap().getKnownCommands()`):

```text
login -> io.papermc.paper.command.brigadier.PluginVanillaCommandWrapper | AuthMe
register -> io.papermc.paper.command.brigadier.PluginVanillaCommandWrapper | AuthMe
changepassword -> io.papermc.paper.command.brigadier.PluginVanillaCommandWrapper | AuthMe
logincore -> org.bukkit.command.PluginCommand | authmePAL
```

No `authmePAL:login` / `:register` / `:changepassword` fallback aliases existed. Concrete bot result:

```json
{"instance":"server-1","kind":"plugin","count":1,"namePrefix":"E2EBot"}  -> {"spawned":1,"names":"E2EBot1"}
bot_action {bot:"E2EBot1", action:"command", command:"/register RealAuthMeProbe123 RealAuthMeProbe123"}
-> {"command":"register ...","success":true,"messages":[
     {"text":"Successfully registered!"},
     {"text":"Successful login!"},
     {"text":"Welcome E2EBot1 on Your Minecraft Server server"},
     {"text":"This server uses AuthMeReloaded protection!"}]}
```

Server log line: `[AuthMe] E2EBot1 registered 127.0.0.1` / `[AuthMe] E2EBot1 logged in 127.0.0.1`. These are **AuthMe's** messages, not login-core's `Account registered. You are now logged in.`; `exceptions` returned `{"total":0,"exceptions":[]}`. Conclusion: with the real AuthMe jar, `/login`, `/register` and `/changepassword` cannot execute login-core's password logic. That is a real cutover blocker for real-AuthMe coexistence, but per the follow-up ruling it triggers the authorized seam fallback rather than a BLOCKED verdict.

Evidence retention: the real-AuthMe observations above were reported in-session, but their raw Craftwire transcripts (`getKnownCommands`/`server_eval` output, `PluginVanillaCommandWrapper` lines, AuthMe registration messages, and the real-AuthMe `server_info`) were not retained to disk. They are not independently reproducible from retained artifacts; they support the fallback rationale only, not the pass verdict.

## 3. Strategy 2 — test-only `fr.xephi.*` seam stub (used for the pass)

Built entirely in `C:\Users\marcus panget\AppData\Local\Temp\opencode\authme-seam-stub`; **no real-worktree source was patched**. The stub is a Bukkit plugin whose `plugin.yml` declares `name: AuthMe` (so login-core's unchanged `depend: [packetevents, AuthMe]` still resolves) with `version: 6.0.1-e2e-seam-stub`, `api-version: '26.3'`, `load: STARTUP`, an empty `JavaPlugin` main, and no commands/listeners. It provides exactly the FQNs bytecode needs:

- `fr.xephi.authme.api.v3.AuthMeApi` — `getInstance()`, `isRegistered(String)`, `isAuthenticated(Player)`, `forceLogin(Player)`, `forceLoginFromProxy(String)`, `forceRegister(Player,String)`, `registerPlayer(String,String)`, `getRegisteredNames():List`, `getLastIp(String)`, `getLastLoginTime(String):Instant`, `getPlayerInfo(String):Optional<AuthMePlayer>`.
- `fr.xephi.authme.api.v3.AuthMePlayer` — `getLastLoginDate():Optional`, `getLastLoginIpAddress():Optional`, `getRegistrationDate():Instant`.
- `fr.xephi.authme.events.LoginEvent` and `RestoreSessionEvent` — Bukkit `Event` subclasses with `getPlayer()`, static `getHandlerList()` / `getHandlers()`; `RestoreSessionEvent` also implements `Cancellable` (`setCancelled(boolean)`).

State is honest: `isRegistered` is true only after `registerPlayer`/`forceRegister`; `isAuthenticated` is true only after explicit force-login; there is deliberately **no** `checkPassword` shortcut. Password verification stays in login-core `Storage` + BCrypt.

Signatures were derived from `javap -p -c` of the built worktree jar, not guessed. Stub build output:

```text
PS> .\gradlew.bat jar
> Task :compileJava
> Task :processResources
> Task :classes
> Task :jar
BUILD SUCCESSFUL in 2s
```

Stub jar `authme-seam-stub.jar`, 6,031 bytes; contains the five classes above plus `plugin.yml`. Real AuthMe jar and its data folder were removed before this run; the two test jars were copied manually to `plugins\AuthMeSeamStub.jar` and `plugins\login-core-e2e-1.0-SNAPSHOT.jar`. `.\TestServer.ps1 restart` reached `Done (40.807s)!`.

`reset pre-cutover-e2e-20261127-070654` was attempted and **refused** by the script (`FAIL baseline is not sanitized ... other files=4`), so cleanup was limited to files this test created (E2EBot1/PwBot1/BruteBot/TimeoutBot player files and the `plugins\AuthMe` / `plugins\authmePAL` folders). qcart's four player files were never touched.

## 4. Craftwire E2E assertions (stub run)

`list_instances` → `{"id":"server-1","kind":"server","name":"global-test-server-26.3","pid":7664}`; `server_info` → Leaf 26.3, TPS 20, plugins include `AuthMe 6.0.1-e2e-seam-stub enabled=true` and `authmePAL 1.0-SNAPSHOT enabled=true`; `plugin_manage list` agrees. Command ownership re-checked:

```text
login -> authmePAL
register -> authmePAL
changepassword -> authmePAL
logincore -> authmePAL
```

All steps below are `bot_spawn`/`bot_action`/`bot_list`/`logs`/`wait_for`/`exceptions` results on `server-1`. Plugin-bot messages are delivered one tick late in `bot_action` responses, so the authoritative message list is the explicit `action:"messages"` read.

| # | Assertion | Tool call | Concrete result |
|---|---|---|---|
| 1 | Pre-auth chat cancelled | `bot_action {bot:"PwBot1",action:"chat",text:"preauth hello"}` then `messages` | message id 2 `Log in first: /login or /register.` |
| 2 | Non-allowlisted command cancelled | `bot_action {bot:"PwBot1",action:"command",command:"/spawn"}` | `success:false, cancelled:true`, message `Log in first: /login or /register.` |
| 3 | `/logincore` reaches login-core | `bot_action {bot:"PwBot1",action:"command",command:"/logincore status"}` | `success:true`, message `You do not have permission to use this command.` (plugin's own permission check, not the pre-auth denial) |
| 4 | Movement cancelled | `bot_action {bot:"PwBot1",action:"move_to",x:8,y:69,z:8,timeoutMs:3000}` then `state` | `reached:false, reason:"timeout"`, x/y/z stayed `0/69/0` |
| 5 | Offline register | `/register PwTest123 PwTest123` | `Account registered. You are now logged in.` |
| 6 | Repeat registration denied | `/register PwTest123 PwTest123` | `That account is already registered. Use /login.` |
| 7 | Changepassword | `/changepassword PwTest123 PwNew456` | `Your password changed.` |
| 8 | Old password fails | `/login PwTest123` | `Incorrect password. 4 attempts remaining.` |
| 9 | New password works | `/login PwNew456` | `You are now logged in.` |
| 10 | Fresh-session offline login | `bot_remove PwBot1` → `bot_spawn names:["PwBot1"]` → `/login PwNew456` | `{"removed":1}`, `{"spawned":1}`, `You are now logged in.` — proves the password persisted and auth state resets per join |
| 11 | Brute-force counter + kick | `bot_spawn names:["BruteBot"]`, then 5× `/login WrongPass111` | attempts remaining `4,3,2,1` then 5th returned `BOT_NOT_FOUND`; log: `BruteBot lost connection: Too many failed login attempts. Reconnect and try again.` |
| 12 | Timeout kick | `bot_spawn names:["TimeoutBot"]`, real wait via `wait_for {condition:"log_match",pattern:"Login timed out",timeoutMs:75000}`; `bot_list`, `logs` | log: `TimeoutBot lost connection: Login timed out. Reconnect and log in.`; `bot_list` count 1 (only authenticated `PwBot1`). Report-only: the raw result transcript was not retained; only request-only `cw-batch3.json` exists. |
| 13 | Exceptions | `exceptions {instance:"server-1",limit:20}` | `{"total":0,"exceptions":[]}` (twice, before and after timeout) |

Raw tool transcripts are retained at `C:\Users\marcus panget\AppData\Local\Temp\opencode\cw-batch1-result.json` and `cw-batch2-result.json` (assertions 1–11 and 13); `cw-batch3.json` is request-only and `cw-batch3-result.json` is absent, so assertion #12 has no retained result transcript. Build transcripts at `build-main.log`, `build-e2e-copy.log`, `build-authme-seam-stub.log`, `restart-stub.log` in the same temp folder.

Note: the shared Craftwire hub reached its 32-idle-session cap (`{"code":-32000,"message":"Too many sessions"}`, hub source `dist/http/serve.js:64`, 30-minute idle TTL). The hub process was restarted once (`npx -y craftwire@0.11.2 serve`, new PID 18696) to clear abandoned sessions; the server plugin reconnected as `server-1`. No MCP token is included in this report.

## 5. Limitations (must not be read as a real-AuthMe pass)

1. **No real-AuthMe interop.** The real AuthMe jar loads and enables alongside the renamed test jar, but it owns `/login`, `/register` and `/changepassword`; login-core's commands are unreachable in that configuration. This pass uses a test-only seam stub instead. Real AuthMe command interop, AuthMe listener behaviour, and AuthMe event delivery (`LoginEvent`, `RestoreSessionEvent`) are **not** verified.
2. **No migration data fidelity.** The stub's registration state is created only through its API from login-core; importing a real AuthMe database was not tested.
3. **Premium verified-session bypass not attempted.** The test server is offline/cracked with no premium account or real Mojang session available, so no suitable premium path existed. This assertion is explicitly unverified, not passed.
4. **Seam stub is not a production AuthMe.** `getLastIp`/`getLastLoginTime` return `null` and `getPlayerInfo` exposes only a registration timestamp; those methods were not exercised by the password-auth assertions.
5. **`test` was cache-hit** in the recorded build; unit tests were not re-run fresh in this session.
6. FlectonePulse emitted unrelated `ChannelHandlerContext ... null` warnings for plugin bots on every join; `Craftwire exceptions` grouped zero server exceptions, and none involved login-core. They are pre-existing noise on this shared server.
7. **Unretained raw transcripts for Strategy 1 and assertion #12.** The real-AuthMe command-ownership/registration transcript and the timeout result transcript were not retained (only `cw-batch3.json` request exists for #12). These observations must not be treated as independently reproducible evidence: they justify the seam fallback and are report-only, not part of the pass verdict.

## 6. Verdict

Final server state: stopped, original 25 plugin jars restored, qcart's four player files intact and no other player files (`verify` passed every invariant except `no live logs/crash-reports leftovers`: one rotated `logs\2026-11-26-1.log.gz` from **before** this task was deliberately left in place; the two rotated logs created by this task's restarts were removed).

`DONE_WITH_CONCERNS`: login-core's standalone password auth (register, duplicate-registration denial, login, wrong-password counter/kick, changepassword, old-password rejection, new-password acceptance, pre-auth chat/command/move blocking, allowed commands, timeout kick) is proven live with Craftwire assertions while the AuthMe dependency remains intact in source. Real-AuthMe coexistence is demonstrated **not** to work for the three shared command labels and remains the open cutover concern; the stub fallback proves login-core's own logic only.
