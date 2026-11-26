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

- Paper 26.3 and Java 25
- PacketEvents 2.14.0 installed as a server plugin (the only hard plugin dependency)
- `online-mode=false` in `server.properties`
- Existing AuthMe SQLite `authme` rows are read for password verification; Argon2 hashes are rejected with a reset-password message

login-core is for a direct Paper server, not a proxy network. An offline-mode server accepts unverified names for accounts that are not linked to premium identities; restrict network access appropriately and do not expose an offline-mode backend to untrusted clients.

## Install and configure

1. Back up the server and existing `plugins/AuthMe` data.
2. Install PacketEvents, remove any old jar, and place `login-core-1.0-SNAPSHOT.jar` in the server's `plugins` directory. AuthMe does not need to be installed.
3. Set `online-mode=false`.
4. Start the server once to create `plugins/AuthMe/config.yml`. Dev installs that already have `plugins/AuthMe/data.db` (plus `-wal`/`-shm` siblings) must rename those files to `authme.db` before starting; the new database filename is `authme.db`.
5. Start with `mode: SHADOW` and review the server log before enabling enforcement. SHADOW performs name lookups and logs decisions but does not start the premium handshake or block the login.
6. After checking the intended behavior, change `mode` to `ENFORCE` and restart.

The runtime plugin name is `AuthMe` (per ADR 0008), so this build uses the `plugins/AuthMe` data folder and `authme.db`. An existing AuthMe-shaped database is opened in place; back it up before upgrading. Previous `AuthMePAL` configuration and link/exemption data are not automatically imported — see [docs/DEVIATIONS.md](./docs/DEVIATIONS.md).

The generated config controls Mojang lookup endpoints, lookup-failure behavior, existing AuthMe account policy, throttles, premium UUID mode, timeouts, and kick messages (`name-reserved`, `verify-failed`, `lookup-unavailable`, `account-conflict`, `rename-conflict`, `throttled`). `rename-conflict` tells a renamed premium player that their UUID is linked under another name and that an administrator must resolve it; the old link is never moved automatically. Skin forwarding is intentionally not provided; use a dedicated skin-restoration plugin.

### Premium UUID mode

`use-premium-uuid` defaults to `true`. Successfully verified premium players use their Mojang UUID; cracked, exempt, and normal AuthMe logins keep Paper's offline UUID. This does not migrate existing player data: inventories and other plugin state stored under a premium player's former offline UUID may appear missing. Back up the server and plan any needed data conversion before deployment.

### Existing AuthMe accounts

`existing-account-policy` controls a verified premium owner whose username already has an account row but no premium link:

- `REQUIRE_PASSWORD_ONCE` (default): the owner must complete one `/login` with their existing password; login-core saves the link only after that succeeds.
- `DENY`: reject the verified premium login until an administrator resolves the account.

Newly encountered premium identities are registered with AuthMe and auto-logged-in using an automatically generated password the player does not know. If Mojang verification is unavailable they cannot use a password fallback; administrators must use AuthMe's supported password-reset process if recovery is needed.

## Administration

All commands require `logincore.admin` (default: operators). The command is `/logincore`; tab completion is available for subcommands, options, and online player names.

```text
/logincore status [name]
/logincore exempt add <name>
/logincore exempt remove <name>
/logincore exempt list
/logincore migrate audit
/logincore migrate apply --exempt-conflicts [--except name,name]
```

`migrate audit` checks registered names from the local `authme` table and writes a CSV under `plugins/AuthMe/migration/`; it does not change account state. `migrate apply --exempt-conflicts` exempts names classified as premium-name conflicts, optionally excluding the comma-separated names. Review the audit and keep a backup before applying changes. Actual migration of existing player UUID-keyed server data is not performed.

login-core stores links, exemptions and account state in `plugins/AuthMe/authme.db`.

## Build and tests

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

The built plugin jar is in `build/libs/`. The automated suite covers the decision engine, Mojang lookup/session parsing, cache/rate-budget behavior, and SQLite persistence.

## Verification limits

The user reports that the previous build's login features worked on their server. Premium UUID mode is new in this build and still needs a real join test; Paper profile propagation and compatibility with UUID-keyed plugins are not covered by automated tests. See [docs/SPIKE_RESULTS.md](./docs/SPIKE_RESULTS.md) and [docs/DEVIATIONS.md](./docs/DEVIATIONS.md).

## Credits

login-core's login flow design was adapted from [FastLogin](https://github.com/games647/FastLogin) (MIT) and [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded) (GPL-3.0), implemented against Mojang's public session protocol. The GPL-3.0 licence is inherited from AuthMeReloaded; upstream provenance and any vendored files are recorded in [NOTICE](./NOTICE). Dependency versions and licences are in [CREDITS.md](./CREDITS.md).

## License

login-core (runtime plugin name `AuthMe`) is licensed under the GNU General Public License v3.0 (GPL-3.0), inherited from AuthMeReloaded. See [LICENSE](./LICENSE) for the full text and [NOTICE](./NOTICE) for provenance.
