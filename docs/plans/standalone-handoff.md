# Handoff prompt — login-core standalone conversion (continue full implementation)

Paste everything below into a fresh session to continue.

---

You are continuing the transformation of `C:\Projects\authmePAL` → `C:\Projects\login-core` into a **fully standalone** AuthMe-derived authentication plugin. AuthMe is NOT to remain a runtime dependency. All password auth must be implemented in login-core itself.

## Environment
- Working dir: `C:\Projects\login-core`. Git branch: `phase-4-build-baseline` (master @ 98177a6). Origin: `https://github.com/kub0san/login-core.git`. Do NOT push without approval.
- Upstream ref (READ-ONLY): `C:\Projects\login-core\AuthMeReloaded-ref` @ 56e9a66b. Never modify.
- JDK 25 at `C:\Tools\Java\zulu25`. Gradle wrapper in repo. Test server: `C:\Tools\global-test-server-26.3` (Leaf 26.3, port 25566). Read `C:\Projects\GLOBAL_TEST_SERVER.md` first.
- MCPs: caveman, context7, exa, mempalace, minecraft-dev, minecraft-developer, minecraft-paper, minecode, craftwire.
- Skills: planning-with-files (active plan at `C:\Projects\.planning\2026-11-26-standalone-password-auth\task_plan.md`), tdd, subagent-driven-development, verification-before-completion, requesting-code-review.

## Current state (verified)
- Build: `clean check shadowJar` green. JAR `login-core-1.0-SNAPSHOT.jar`.
- Source: 22 Java files under `com.hizeric.logincore.*`. Packages: authme, command, decision, handshake, migration, mojang, session, storage.
- Storage now has standalone password methods: `registerPlayer`, `verifyPassword`, `setLoggedIn`, `setLoggedOut`, `changePassword`, `markLoggedIn` (delegate). Uses vendored BCrypt.
- BCrypt vendored (28KB, provenance in NOTICE). AuthMePasswords verifies BCrypt only.
- Still hard-depends on AuthMe: `plugin.yml depend: [packetevents, AuthMe]`, `build.gradle.kts compileOnly("fr.xephi:authme-core:6.0.1")`, `LoginCore.onEnable()` checks for AuthMe plugin, `AuthMeBridge` calls `AuthMeApi.getInstance()`, `AuthMeLoginListener` uses `LoginEvent`/`RestoreSessionEvent`.
- No `/login`, `/register`, `/changepassword` commands. Only `/logincore` (admin).
- No pre-auth blocking for offline players.
- Plugin.yml name: `AuthMe` (per ADR 0008).
- ADRs 0002–0008, plan + handoff docs, review report in `docs/reports/`.

## What remains (full standalone)

### Phase A: Commands (implement /login, /register, /changepassword)
1. Create `LoginCommand` (implements CommandExecutor + TabCompleter) handling `/login <password>`.
   - Verify password via `storage.verifyPassword(name, password)`.
   - On success: `storage.setLoggedIn(name)`, fire login-core `LoginEvent`, send success message, grant access (remove restrictions).
   - On failure: increment failure counter, kick or message; brute-force protection.
2. Create `RegisterCommand` handling `/register <password> <password>`.
   - Check password match, length policy.
   - `storage.registerPlayer(name, password)`.
   - On success: auto-login (`setLoggedIn`), send success message.
   - On failure (already registered): message.
3. Create `ChangepasswordCommand` handling `/changepassword <old> <new>` (offline accounts only).
   - `storage.changePassword(name, old, newPassword)`.
   - On failure: message.
4. Register commands in `plugin.yml` and `LoginCore.onEnable()`.
5. Tests for each command.

### Phase B: Pre-auth blocking for offline players
1. Create `PreAuthRestrictionListener` (Listener) blocking for unauthenticated offline players:
   - `PlayerMoveEvent`: cancel movement (or teleport back to spawn).
   - `AsyncPlayerChatEvent`: cancel chat.
   - `PlayerCommandPreprocessEvent`: allow only `/login`, `/register`, `/changepassword`, `/logincore`; cancel others.
   - `PlayerInteractEvent`, `PlayerInteractEntityEvent`, `BlockBreakEvent`, `BlockPlaceEvent`: cancel.
   - `EntityDamageEvent`, `EntityDamageByEntityEvent`: cancel.
   - `PlayerDropItemEvent`, `PlayerPickupItemEvent`: cancel.
   - `InventoryClickEvent`: cancel.
2. Track authenticated state per player (Set<String> or check storage.isLogged).
3. Auto-kick on login timeout (`handshake-timeout-seconds` or dedicated login timeout).
4. Premium players bypass restrictions (they're auto-authenticated).
5. Tests (mock or pure logic).

### Phase C: Remove AuthMe dependency
1. Rewrite `AuthMeBridge` to use Storage:
   - `isRegistered(name)` → `storage` check (or add `storage.isRegistered`).
   - `isAuthenticated(player)` → track in-memory authenticated set.
   - `forceLogin(player)` → `storage.setLoggedIn` + grant.
   - `forceLoginFromProxy(name)` → queued (15s expiring map) then force login.
   - `forceRegister(player, password)` → `storage.registerPlayer` + login.
   - `registerPlayer(name, password)` → `storage.registerPlayer`.
   - `registeredNames()` → `storage` query.
   - `lastIp`, `lastLoginTime`, `playerInfo` → storage queries (or drop).
2. Rewrite `AuthMeLoginListener`:
   - Remove `LoginEvent`/`RestoreSessionEvent` imports.
   - Instead of AuthMe events, call storage + grant directly.
   - `REQUIRE_PASSWORD_ONCE` flow: premium session survives pre-login; on password login, commit identity. But now WE handle password login, so this simplifies: when a premium session requires password once, the player uses `/login` with their AuthMe password... wait, there's no AuthMe password anymore. The account is in our `authme` table with a BCrypt hash. So `/login <password>` verifies against our table.
   - Actually, with standalone auth, the "REQUIRES_PASSWORD_ONCE" policy for existing premium accounts becomes: the premium player must /login with their password (which is in our table if they were registered). But new premium accounts are auto-registered with a random password. So "REQUIRES_PASSWORD_ONCE" means: existing account (from migration) requires one password login to link. After that, auto-login.
3. Remove `fr.xephi:authme-core` from `build.gradle.kts` (compileOnly + testImplementation).
4. Remove `AuthMe` from `plugin.yml` `depend` → `depend: [packetevents]`.
5. Remove AuthMe plugin check in `LoginCore.onEnable()`.
6. Fire login-core events (`LoginEvent`, `RegisterEvent`, `AsyncPlayerPreLoginEvent`?) for downstream compat. Create event classes in `com.hizeric.logincore.events` package. (Optional but good for compat; can defer.)
7. Build + test green.

### Phase D: Integration + E2E
1. Live test server: deploy, run Craftwire scenarios (premium login, offline register/login, changepassword, pre-auth blocking).
2. Record evidence.
3. Independent review (security + architecture).

## Key files
- `LoginCore.java` — main plugin, onEnable/onDisable, wiring.
- `Storage.java` — SQLite storage (authme table + links + exempt + account_classification). Already has password methods.
- `AuthMeBridge.java` — replace with Storage-backed implementation.
- `AuthMeLoginListener.java` — remove AuthMe event deps.
- `PremiumLoginListener.java` — premium handshake (keep, it's standalone already).
- `AdminCommand.java` — admin commands (keep).
- `plugin.yml` — commands, permissions, depend.
- `build.gradle.kts` — dependencies.

## Rules
- Use skills: planning-with-files, tdd, subagent-driven-development, verification-before-completion, requesting-code-review.
- Caveman style for internal/agent communication; professional for committed docs/code/comments.
- Conventional commits per batch; no push without explicit user approval.
- Run `.\gradlew.bat clean check shadowJar` after each batch.
- Live E2E via TestServer.ps1 + Craftwire; green handshake ≠ pass.
- Do NOT remove AuthMe dependency until password auth is verified working.

## First action
1. Read `C:\Projects\.planning\2026-11-26-standalone-password-auth\task_plan.md`.
2. Read `C:\Projects\login-core\docs\reports\phase-4-7-review.md` §7 for scope reality.
3. Start Phase A: create `/login`, `/register`, `/changepassword` commands. Begin with `/register` + `/login` (most critical). Use TDD.
4. Keep AuthMe dependency intact during development; remove only after E2E verification.

## Important: AuthMeReloaded-ref is already cloned
`C:\Projects\login-core\AuthMeReloaded-ref` (READ-ONLY, @ 56e9a66b). Use it as reference for:
- AuthMe's login flow (`process/login/AsynchronousLogin.java`, `process/Management.java`)
- AuthMe's registration (`process/register/AsyncRegister.java`)
- AuthMe's pre-auth restrictions (`listener/ListenerService.java`, `data/limbo/LimboService.java`)
- AuthMe's command implementations (`command/executable/authme/LoginCommand.java`, `RegisterCommand.java`, `ChangepasswordCommand.java`)
- AuthMe's events (`events/LoginEvent.java`, `events/RegisterEvent.java`)
- AuthMe's password security (`security/PasswordSecurity.java`)