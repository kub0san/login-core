# login-core

login-core securely verifies Mojang account ownership on offline-mode Paper servers and connects verified players to AuthMe without a password prompt. Cracked-name collisions are blocked, while other cracked accounts continue through the normal AuthMe flow.

**Author:** hizeric

## Project Layout

- `src/main/java/` — `org.authmePAL` plus the `authme`, `command`, `decision`,
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

- Paper 26.2 and Java 25
- PacketEvents 2.13.0 installed as a server plugin
- AuthMeReloaded 6.0.1 installed as a server plugin
- `online-mode=false` in `server.properties`
- AuthMe `settings.enablePremium: false` (AuthMePAL will disable itself if AuthMe's premium handshake is enabled)

AuthMePAL is for a direct Paper server, not a proxy network. An offline-mode server accepts unverified names for accounts that are not linked to premium identities; restrict network access appropriately and do not expose an offline-mode backend to untrusted clients.

## Install and configure

1. Back up the server and AuthMe data.
2. Install PacketEvents and AuthMe, remove any old AuthmePAL jar, and place `AuthMePAL-*.jar` in the server's `plugins` directory.
3. Set `online-mode=false` and set AuthMe `settings.enablePremium: false`.
4. Start the server once to create `plugins/AuthMePAL/config.yml`.
5. Start with `mode: SHADOW` and review the server log before enabling enforcement. SHADOW performs name lookups and logs decisions but does not start the premium handshake or block the login.
6. After checking the intended behavior, change `mode` to `ENFORCE` and restart.

This release starts with a fresh `plugins/AuthMePAL` data folder. Previous plugin configuration and link/exemption data are not preserved; back up the old folder first if you may need its contents.

The generated config controls Mojang lookup endpoints, lookup-failure behavior, existing AuthMe account policy, throttles, premium UUID mode, timeouts, and kick messages. Skin forwarding is intentionally not provided; use a dedicated skin-restoration plugin.

### Premium UUID mode

`use-premium-uuid` defaults to `true`. Successfully verified premium players use their Mojang UUID; cracked, exempt, and normal AuthMe logins keep Paper's offline UUID. This does not migrate existing player data: inventories and other plugin state stored under a premium player's former offline UUID may appear missing. Back up the server and plan any needed data conversion before deployment.

### Existing AuthMe accounts

`existing-account-policy` controls a verified premium owner whose username is already registered in AuthMe but has no AuthMePAL link:

- `REQUIRE_PASSWORD_ONCE` (default): the owner must complete AuthMe password login once; AuthMePAL saves the link after successful login.
- `ADOPT`: link and auto-login the verified premium identity. Use only after reviewing existing accounts and migration results.
- `DENY`: reject the verified premium login until an administrator resolves the account.

Newly encountered premium identities are registered with AuthMe and auto-logged-in using an automatically generated password the player does not know. If Mojang verification is unavailable they cannot use a password fallback; administrators must use AuthMe's supported password-reset process if recovery is needed.

## Administration

All commands require `authmepal.admin` (default: operators). The command is `/authmepal`; tab completion is available for subcommands, options, and online player names.

```text
/authmepal status [name]
/authmepal exempt add <name>
/authmepal exempt remove <name>
/authmepal exempt list
/authmepal link <name> <uuid|auto>
/authmepal unlink <name>
/authmepal migrate audit
/authmepal migrate apply --exempt-conflicts [--except name,name]
```

`migrate audit` checks registered AuthMe names and writes a CSV under `plugins/AuthMePAL/migration/`; it does not change account state. `migrate apply --exempt-conflicts` exempts names classified as premium-name conflicts, optionally excluding the comma-separated names. Review the audit and keep a backup before applying changes. Actual migration of existing player UUID-keyed server data is not performed.

AuthMePAL stores links and exemptions in `plugins/AuthMePAL/data.db`.

## Build and tests

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

The built plugin jar is in `build/libs/`. The automated suite covers the decision engine, Mojang lookup/session parsing, cache/rate-budget behavior, and SQLite persistence.

## Verification limits

The user reports that the previous build's login features worked on their server. Premium UUID mode is new in this build and still needs a real join test; Paper profile propagation and compatibility with UUID-keyed plugins are not covered by automated tests. See [docs/SPIKE_RESULTS.md](./docs/SPIKE_RESULTS.md) and [docs/DEVIATIONS.md](./docs/DEVIATIONS.md).

## Credits

AuthMePAL's login flow design was adapted from [FastLogin](https://github.com/games647/FastLogin) (MIT) and [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded) (GPL-3.0), implemented from scratch against Mojang's public session protocol. No source code was copied from either project. Full provenance, dependency versions, and licences are in [CREDITS.md](./CREDITS.md).

## License

AuthMePAL is licensed under the GNU General Public License v3.0 (GPL-3.0). See [LICENSE](./LICENSE) for the full text.
