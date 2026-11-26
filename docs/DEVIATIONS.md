# Deviations and verification status

- The runtime is wired through the installed PacketEvents API and AuthMe 6 API. AuthMePAL does not initialize or shut down PacketEvents itself.
- `CipherInstaller` uses small local Netty message handlers around JDK `Cipher` instances. The Netty version resolved by Paper has no public `CipherDecoder` or `CipherEncoder` classes at the imports suggested in the original plan.
- `com.hizeric.logincore.handshake.LoginFlowState` names the plugin's per-connection state machine to avoid colliding with PacketEvents' own `ConnectionState` enum.
- AuthMe's `AsyncPlayerPreLoginEvent` exposes an `InetAddress`, not a Netty channel identity. The short-lived verified-session handoff therefore indexes by normalized name and remote IP while retaining the unique connection ID for removal, one-shot consumption, and expiry.
- Existing AuthMe accounts default to `REQUIRE_PASSWORD_ONCE`; `DENY` is the explicit alternative (`ADOPT` was dropped). New premium identities are registered with a randomly generated password that is not disclosed to the player.
- Premium UUID mode defaults on and applies the Mojang UUID only after successful session verification. It does not migrate UUID-keyed player data; cracked and exempt logins retain Paper's offline UUID.
- The premium player profile is applied at `EventPriority.LOWEST` on `AsyncPlayerPreLoginEvent`, separately from the AuthMe registration/auto-login work that stays at `HIGHEST`. LuckPerms pre-loads permission data at `LOW`, keyed by `AsyncPlayerPreLoginEvent#getUniqueId()` (which returns the mutable profile UUID). Applying the profile later made LuckPerms load data under the offline UUID, then look for the Mojang UUID at `PlayerLoginEvent`, and deny the join with "Permissions data for your user was not loaded during the pre-login stage".
- The runtime-name flip to `AuthMe` is deferred to the vendoring commit (ADR 0008); all of these change together, never piecemeal:
  - `plugin.yml`: `name: AuthMePAL` → `name: AuthMe`; `depend: [packetevents, AuthMe]` → `depend: [packetevents]`.
  - Netty handlers in `CipherInstaller`: `authmePAL_decrypt` / `authmePAL_encrypt` → AuthMe-neutral names.
  - `mempalace.yaml`: wing `authmePAL` → `authme`.
  - Docs: `AGENTS.md`, `README.md`, `CREDITS.md`, `GLOSSARY.md`, `PLAN.md`, `docs/plans/*` and this file.
  - Data folder: `plugins/AuthMePAL/data.db` → `plugins/AuthMe/authme.db`. Phase 7 owes DB reconciliation (backup, schema-compatible `authme` table, idempotent migration, real-db smoke test).
  - **Waiver:** the Phase 5 exit criterion "no external AuthMe plugin dependency; standalone jar builds" (`docs/plans/phase-4-7-plan.md:44`) is waived until the vendoring commit; it is not met today.
- Skin forwarding was removed because the server uses a separate skin-restoration plugin. AuthMePAL no longer reads or applies the Mojang `textures` property.
- The user reports that the previously delivered login flow worked on their Paper server. Premium UUID propagation is new in this change and still needs a live join test; see [SPIKE_RESULTS.md](./SPIKE_RESULTS.md).
- FastLogin and AuthMe were used as behavioral/API references only; their implementation source was not copied into this project.
