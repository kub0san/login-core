# Handoff prompt — login-core FULL implementation plan (phases 4–10)

Paste everything below into a fresh session to continue the entire remaining implementation.

---

You are completing the transformation of `C:\Projects\authmePAL` → `C:\Projects\login-core` into a **fully standalone**, minimal, AuthMe-derived authentication plugin. This covers ALL remaining phases: 4 (build baseline), 5 (identity/package/storage), 6a/6b/6c (storage/classification, K-1/pipeline, legacy migration), 7 (SQLite compat), 8 (verification), 9 (independent review), 10 (final acceptance). The standalone password-auth work (commands, pre-auth blocking, removing AuthMe dependency) is embedded in phases 5-7.

## Environment
- Working dir: `C:\Projects\login-core`. Git branch: `phase-4-build-baseline`. Origin: `https://github.com/kub0san/login-core.git`. Do NOT push without approval.
- Upstream ref (READ-ONLY): `C:\Projects\login-core\AuthMeReloaded-ref` @ 56e9a66b. Never modify.
- JDK 25 at `C:\Tools\Java\zulu25`. Gradle wrapper in repo. Test server: `C:\Tools\global-test-server-26.3` (Leaf 26.3, port 25566). Read `C:\Projects\GLOBAL_TEST_SERVER.md` first.
- MCPs: caveman, context7, exa, mempalace, minecraft-dev, minecraft-developer, minecraft-paper, minecode, craftwire.
- Skills: planning-with-files (active plan at `C:\Projects\.planning\2026-11-26-standalone-password-auth\task_plan.md`), tdd, subagent-driven-development, verification-before-completion, requesting-code-review, systematic-debugging.

## Current state (verified 2026-11-27)
- Build: `clean check shadowJar` green. JAR `login-core-1.0-SNAPSHOT.jar` (92KB).
- Source: 22 Java files under `com.hizeric.logincore.*`. Packages: authme, command, decision, handshake, migration, mojang, session, storage.
- Storage has standalone password methods: `registerPlayer`, `verifyPassword`, `setLoggedIn`, `setLoggedOut`, `changePassword`, `markLoggedIn`. Uses vendored BCrypt.
- BCrypt vendored (28KB, provenance in NOTICE). AuthMePasswords verifies BCrypt only.
- Still hard-depends on AuthMe: `plugin.yml depend: [packetevents, AuthMe]`, `build.gradle.kts compileOnly("fr.xephi:authme-core:6.0.1")`, `LoginCore.onEnable()` checks for AuthMe plugin, `AuthMeBridge` calls `AuthMeApi.getInstance()`.
- No `/login`, `/register`, `/changepassword` commands. Only `/logincore` (admin).
- No pre-auth blocking for offline players.
- Plugin.yml name: `AuthMe`. api-version: `26.3`.
- ADRs 0002–0008. Plan + handoff docs + review report in `docs/`.
- Git: on branch `phase-4-build-baseline`. Master @ 98177a6 (plan commit). All work is on the feature branch.

## What remains — complete checklist

### Phase A: Commands (standalone password auth)
1. Create `LoginCommand` (`/login <password>`): verify password via storage, on success setLoggedIn + grant access + message; on failure counter + kick/message; brute-force protection.
2. Create `RegisterCommand` (`/register <password> <password>`): check match/length, `storage.registerPlayer`, on success auto-login + message; on failure (already registered) message.
3. Create `ChangepasswordCommand` (`/changepassword <old> <new>`): `storage.changePassword`, message on failure.
4. Register commands in `plugin.yml` and `LoginCore.onEnable()`. Add TabCompleters.
5. Tests for each command.

### Phase B: Pre-auth blocking for offline players
1. Create `PreAuthRestrictionListener`: block chat, commands (except /login, /register, /changepassword, /logincore), movement, inventory, interaction, damage, drop/pickup for unauthenticated offline players.
2. Track authenticated state per player (in-memory Set or storage.isLogged).
3. Auto-kick on login timeout.
4. Premium players bypass restrictions (auto-authenticated).
5. Tests.

### Phase C: Remove AuthMe dependency
1. Rewrite `AuthMeBridge` to use Storage (isRegistered, isAuthenticated, forceLogin, forceLoginFromProxy, forceRegister, registerPlayer, registeredNames, lastIp, lastLoginTime, playerInfo).
2. Rewrite `AuthMeLoginListener` to not depend on AuthMe events (LoginEvent, RestoreSessionEvent). Use direct calls + login-core events.
3. Create login-core event classes (`LoginEvent`, `RegisterEvent`, `AsyncPlayerPreLoginEvent`?) in `com.hizeric.logincore.events` for downstream compat. (Optional but good.)
4. Remove `fr.xephi:authme-core` from `build.gradle.kts` (compileOnly + testImplementation).
5. Remove `AuthMe` from `plugin.yml` `depend` → `depend: [packetevents]`.
6. Remove AuthMe plugin check in `LoginCore.onEnable()`.
7. Build + test green.

### Phase D: Integration + E2E
1. Live test server: deploy via TestServer.ps1, run Craftwire scenarios (premium login, offline register/login, changepassword, pre-auth blocking).
2. Record evidence in docs/reports/.

### Phase E (8): Complete verification
1. `.\gradlew.bat clean check shadowJar` green.
2. All 18 classification test cases pass (verify + fill gaps).
3. Integration tests; live test-server scenarios; record evidence.
4. `docs/reports/` with build results + E2E evidence.

### Phase F (9): Independent review
1. Launch Review Agent 1 (security): registration, login, password verification, premium lookup, crypto verification, identity classification, cracked-name conflicts, race conditions, account persistence, pre-auth restrictions, session cleanup. Concrete findings + reproduction evidence.
2. Launch Review Agent 2 (architecture & reduction): unnecessary retained features, remaining importer refs, remaining DB backend refs, build config, Java 25 bytecode, Paper compat, SQLite, lifecycle, config, deps, licensing, docs, test completeness.
3. Investigate findings, fix, rerun tests.

### Phase G (10): Final acceptance
Verify all acceptance criteria:
- Existing repo history preserved ✓
- Repo + local project use login-core naming ✓
- JDK 25, Java 25 bytecode ✓
- Paper 1.21.11+ (actually 26.3) compat ✓
- SQLite only ✓
- External importers removed ✓
- Alternative DB support removed ✓
- Unnecessary AuthMe features removed (pending)
- `/premium` and `/freemium` don't exist ✓ (never had them)
- No admin identity-toggle commands ✓
- PREMIUM/OFFLINE classification permanent ✓
- Premium verification cryptographic ✓
- Premium auth no password fallback ✓
- Mojang failures never grant auth ✓
- Offline auth works without PacketEvents ✓
- Existing AuthMe account compat evaluated + tested (partial)
- Plugin standalone (pending — Phase C)
- Distributable JAR builds ✓
- Automated tests pass ✓
- Live testing evidence (pending)
- Independent review findings resolved or documented (pending)

Final report explaining: repo migration, final architecture, features retained/removed, premium verification, permanent identity classification, legacy account handling, SQLite compatibility, Java/Paper compat, dependencies, build results, automated tests, live-server evidence, review findings, remaining limitations.

## Key files
- `LoginCore.java` — main plugin, wiring, onEnable/onDisable.
- `Storage.java` — SQLite storage (authme table + links + exempt + account_classification). Has password methods.
- `AuthMeBridge.java` — replace with Storage-backed implementation.
- `AuthMeLoginListener.java` — remove AuthMe event deps.
- `PremiumLoginListener.java` — premium handshake (keep).
- `AdminCommand.java` — admin commands (keep).
- `plugin.yml` — commands, permissions, depend.
- `build.gradle.kts` — dependencies.
- `AuthMePasswords.java` — BCrypt verify.
- `BCrypt.java` — vendored hash (provenance in NOTICE).

## Rules
- Use skills: planning-with-files, tdd, subagent-driven-development, verification-before-completion, requesting-code-review, systematic-debugging.
- Caveman style for internal/agent communication; professional for committed docs/code/comments.
- Conventional commits per batch; no push without explicit user approval.
- Run `.\gradlew.bat clean check shadowJar` after each batch.
- Live E2E via TestServer.ps1 + Craftwire; green handshake ≠ pass.
- Do NOT remove AuthMe dependency until password auth is verified working end-to-end.
- AuthMeReloaded-ref is READ-ONLY reference. Use it for login flow, registration, pre-auth restrictions, commands, events, password security.

## AuthMeReloaded-ref reference pointers (for vendoring)
- Login flow: `authme-core/.../process/login/AsynchronousLogin.java`, `process/Management.java`, `process/SyncProcessManager.java`, `process/join/AsynchronousJoin.java`
- Registration: `process/register/AsyncRegister.java`, `executors/AbstractPasswordRegisterExecutor.java`
- Pre-auth restrictions: `listener/ListenerService.java`, `data/limbo/LimboService.java`, `listener/EntityListener.java`, `listener/BlockListener.java`
- Commands: `command/executable/authme/LoginCommand.java`, `RegisterCommand.java`, `ChangepasswordCommand.java`
- Events: `events/LoginEvent.java`, `events/RegisterEvent.java`, `events/LogoutEvent.java`
- Password security: `security/PasswordSecurity.java`, `security/HashAlgorithm.java`, `security/crypts/BCrypt.java`
- Session service: `service/SessionService.java`
- Limbo service: `data/limbo/LimboService.java`

## First action
1. Read `C:\Projects\.planning\2026-11-26-standalone-password-auth\task_plan.md`.
2. Read `C:\Projects\login-core\docs\reports\phase-4-7-review.md`.
3. Start Phase A: create `/login`, `/register`, `/changepassword` commands using TDD. Begin with `/register` + `/login`.
4. Keep AuthMe dependency intact during development; remove only after E2E verification (Phase C).