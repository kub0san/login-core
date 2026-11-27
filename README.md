# login-core

login-core securely verifies Mojang account ownership on offline-mode Paper servers and handles password login/registration itself. Verified premium players are auto-authenticated without a password prompt; cracked-name collisions are blocked, while other cracked accounts use `/register` and `/login`. AuthMe is not required as a separate server plugin.

**Author:** hizeric

## Project Layout

- `src/main/java/` — `com.hizeric.logincore` plus the `authme`, `command`, `decision`,
  `handshake`, `migration`, `mojang`, `session` and `storage` packages.
- `src/main/resources/` — `plugin.yml` and `config.yml`.
- `src/test/java/` — JUnit 5 tests for the decision engine, Mojang lookup/session
  parsing, cache/rate-budget behaviour and SQLite persistence.
- `docs/` — project notes (`DEVIATIONS.md`, `SPIKE_RESULTS.md`) and the `adr/`,
  `agents/` and `superpowers/` subdirectories.
- `gradle/` — Gradle wrapper.
- `.planning/`, `.scratch/` and `.snapshots/` — agent-managed planning, issues/specs
  and local snapshots; gitignored.
- `AuthMeReloaded-ref/` — gitignored, read-only clone of the upstream project used
  as a behavioural reference; no source from it is copied into `src/`.
- Root files — `AGENTS.md` and `GLOSSARY.md` (local-only agent guidance),
  `mempalace.yaml` (local-only memory config), `PLAN.md`, `CREDITS.md`,
  `LICENSE`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`,
  `gradlew` / `gradlew.bat`.

## Requirements

- Paper 1.21.11+ and a Java 25 runtime. The jar emits Java 25 bytecode, so Java 25 is required even on Paper 1.21.11 (which on its own would allow Java 21). `plugin.yml` has no Java-version field; the requirement is documented here and in the ADRs.
- PacketEvents 2.14.0 is optional (`softdepend: [packetevents]`). Password login/registration works without it; only the premium-client handshake needs it.
- `online-mode=false` in `server.properties`
- Existing AuthMe SQLite `authme` rows are read for password verification; Argon2 hashes are rejected with a reset-password message

login-core is for a direct Paper server, not a proxy network. An offline-mode server accepts unverified names for accounts that are not linked to premium identities; restrict network access appropriately and do not expose an offline-mode backend to untrusted clients.

## Install and configure

1. Back up the server and existing `plugins/AuthMe` data.
2. Remove any old jar and place `login-core-1.0-SNAPSHOT.jar` in the server's `plugins` directory. AuthMe does not need to be installed. Install PacketEvents 2.14.0 only if you want automatic premium handshakes.
3. Set `online-mode=false`.
4. Start the server once to create `plugins/AuthMe/config.yml`. Dev installs that already have `plugins/AuthMe/data.db` (plus `-wal`/`-shm` siblings) must rename those files to `authme.db` before starting; the new database filename is `authme.db`.
5. Start with `mode: SHADOW` and review the server log before enabling enforcement. SHADOW performs name lookups and logs decisions but does not start the premium handshake or block the login.
6. After checking the intended behavior, change `mode` to `ENFORCE` and restart.

The runtime plugin name is `AuthMe` (per ADR 0008), so this build uses the `plugins/AuthMe` data folder and `authme.db`. An existing AuthMe-shaped database is opened in place; back it up before upgrading. Previous `AuthMePAL` configuration is not automatically imported. Legacy AuthMe `premiumUUID` identities are imported once at startup into the classification/link tables; conflicts are skipped and reported, never overwritten. See [docs/DEVIATIONS.md](./docs/DEVIATIONS.md).

The runtime `name: AuthMe` exists only for `plugins/AuthMe` data-folder continuity and downstream `depend: [AuthMe]` resolution. login-core provides no `AuthMeApi` façade and no binary compatibility with `fr.xephi.authme.events.*`; plugins using the AuthMe API or events must be rebuilt against login-core. `com.hizeric.logincore.events.LoginEvent` fires after successful password login and premium auto-login; `RegisterEvent` fires after `/register` and premium auto-registration.

The generated config controls Mojang lookup endpoints, lookup-failure behavior, existing AuthMe account policy, throttles, premium UUID mode, timeouts, and kick messages (`name-reserved`, `verify-failed`, `lookup-unavailable`, `account-conflict`, `rename-conflict`, `throttled`). `rename-conflict` tells a renamed premium player that their UUID is linked under another name and that an administrator must resolve it; the old link is never moved automatically. Skin forwarding is intentionally not provided; use a dedicated skin-restoration plugin.

### Premium UUID mode

`use-premium-uuid` defaults to `true`. Successfully verified premium players use their Mojang UUID; cracked and normal AuthMe logins keep Paper's offline UUID. This does not migrate existing player data: inventories and other plugin state stored under a premium player's former offline UUID may appear missing. Back up the server and plan any needed data conversion before deployment.

Without PacketEvents there is no premium-client handshake: stored PREMIUM identities fail closed (the player is kicked, with no password fallback). In ENFORCE mode a new, unclassified name can only be registered after a PacketEvents-free Mojang lookup returns NOT_PREMIUM; a premium or unavailable lookup is refused.

### Existing AuthMe accounts

`existing-account-policy` controls a verified premium owner whose username already has an account row but no premium link:

- `REQUIRE_PASSWORD_ONCE` (default): the owner must complete one `/login` with their existing password; login-core saves the link only after that succeeds.
- `DENY`: reject the verified premium login until an administrator resolves the account.

Newly encountered premium identities are registered locally in `plugins/AuthMe/authme.db` and auto-logged-in using an automatically generated password the player does not know. If Mojang verification is unavailable they have no password fallback, and administrators must resolve the account locally — for example, by editing the database only with a backup, or by removing the account row and letting the player re-register through the supported flow where applicable.

## Administration

All commands require `logincore.admin` (default: operators). The command is `/logincore`; tab completion is available for subcommands, options, and online player names.

```text
/logincore status [name]
/logincore migrate audit
/logincore migrate classify
```

`migrate audit` checks registered names from the local `authme` table and writes a CSV under `plugins/AuthMe/migration/`; `migrate classify` applies legacy classification from existing links. Neither command writes an exemption, and actual migration of player UUID-keyed server data is not performed. Review the audit and keep a backup before using either command.

login-core stores links, classifications and account state in `plugins/AuthMe/authme.db`. Exemptions and the grandfather bypass are gone: there is no exemption command, table, or report field.

## Build and tests

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

The built plugin jar is in `build/libs/`. The automated suite covers the decision engine, Mojang lookup/session parsing, cache/rate-budget behavior, and SQLite persistence.

## Verification limits

Automated verification for the hardening pass is recorded in [docs/reports/2026-11-27-hardening-verification.md](./docs/reports/2026-11-27-hardening-verification.md). A **live premium-client handshake is unverified** (no premium account available), and **live Paper 1.21.11 end-to-end testing is unverified** (no local 1.21.11 server; the pinned 26.3 `runServer` is a local/dev test server only). Paper profile propagation and compatibility with UUID-keyed plugins are likewise not covered by automated tests. See [docs/SPIKE_RESULTS.md](./docs/SPIKE_RESULTS.md) and [docs/DEVIATIONS.md](./docs/DEVIATIONS.md).

## Credits

login-core's login flow design was adapted from [FastLogin](https://github.com/games647/FastLogin) (MIT) and [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded) (GPL-3.0), implemented against Mojang's public session protocol. The GPL-3.0 licence is inherited from AuthMeReloaded; upstream provenance and any vendored files are recorded in [NOTICE](./NOTICE). Dependency versions and licences are in [CREDITS.md](./CREDITS.md).

## License

login-core (runtime plugin name `AuthMe`) is licensed under the GNU General Public License v3.0 (GPL-3.0), inherited from AuthMeReloaded. See [LICENSE](./LICENSE) for the full text and [NOTICE](./NOTICE) for provenance.
