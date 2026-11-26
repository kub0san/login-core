# Credits and third-party notices

login-core (runtime plugin name `AuthMe`) records here where its design came
from, which third-party components it uses, and what provenance is owed. AuthMe
is a design reference and data-format compatibility target, not a runtime or
compile dependency; see `docs/DEVIATIONS.md`.

> This document describes the project's own review of its provenance. It is not legal advice.

---

## 1. Provenance and licence

login-core (runtime plugin name `AuthMe`) is licensed under **GPL-3.0,
inherited from [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded)**. It
is not a licence chosen independently of that upstream; ADR 0008 records the
decision.

The login flow was designed by reading how other plugins do premium
verification (§2), then implementing it against Mojang's public session
protocol and the PacketEvents API.

Any source vendored from AuthMeReloaded keeps its upstream copyright and licence
headers verbatim. The root [NOTICE](./NOTICE) records the upstream repository,
the exact commit, and every vendored file; provenance is tracked there and must
be updated in the same change that adds a vendored file. One file is currently
vendored: a self-contained public-domain BCrypt implementation (jBCrypt 0.4,
Damien Miller, ISC) recorded in NOTICE, used because AuthMe's own BCrypt
wrappers depend on BouncyCastle, which login-core does not ship.

---

## 2. Design references

| Project | Author | Licence | What was referenced |
|---|---|---|---|
| [FastLogin](https://github.com/games647/FastLogin) | games647 / TuxCoding | MIT | The overall premium-login sequence: hold the connection at `LOGIN_START`, prove ownership through Mojang's `hasJoined` endpoint, then continue the login. PLAN.md §4's flow diagram is explicitly an adaptation of FastLogin's. |
| [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded) | Xephi and contributors | GPL-3.0 | Behavioural reference for the packet-phase handshake (cancel → verify → re-inject `LOGIN_START`), the verify-token pattern, and how a premium account is registered and force-logged-in without a `Player` object. AuthMe's own premium bypass was studied only to confirm AuthMePAL must require `settings.enablePremium: false`. |
| [Minecraft protocol documentation](https://wiki.vg/Protocol) | Mojang / community | — | The session-hash algorithm (`SHA-1(serverId + sharedSecret + publicKey)` rendered as signed hex), AES/CFB8 key = IV = shared secret, and the `LOGIN_START` / `ENCRYPTION_REQUEST` / `ENCRYPTION_RESPONSE` packet exchange. |
| [LuckPerms](https://github.com/LuckPerms/LuckPerms) | LuckPerms contributors | MIT | Read to diagnose the pre-login permission-loading conflict. login-core applies the verified profile at `EventPriority.LOWEST` so that LuckPerms' `LOW` handler reads the correct UUID. No LuckPerms code is used. |

Attribution above documents the design references. The GPL-3.0 licence and its
obligations are recorded in §1, §4 and the root [NOTICE](./NOTICE).

---

## 3. Dependencies

Every item below is used as an **API or runtime dependency only**. None of them
is compiled into, shaded into, or redistributed with the AuthMePAL jar.

### Compile-time (`compileOnly`)

| Component | Version | Licence | How it is used |
|---|---|---|---|
| [PacketEvents](https://github.com/retrooper/packetevents) (`packetevents-spigot`) | 2.14.0 | GPL-3.0 | Installed as a separate server plugin. AuthMePAL calls its login-phase packet listeners and wrappers. Not bundled. |
| [Paper API](https://papermc.io/) (`paper-api`) | 26.3 | See [PaperMC's licence](https://papermc.io/license) | Server API: plugin lifecycle, events, player profiles. |
| Gson | provided by server | Apache-2.0 | JSON parsing for Mojang responses. |
| Netty | provided by server | Apache-2.0 | Cipher handlers installed into the login channel pipeline. |

### Runtime (server-side, optional/indirect)

| Component | Licence | Relationship to AuthMePAL |
|---|---|---|
| [LuckPerms](https://github.com/LuckPerms/LuckPerms) | MIT | Optional. AuthMePAL does not depend on it; it only orders its own event handler so LuckPerms loads permissions under the correct UUID. |
| SQLite JDBC | Apache-2.0 | Backing store for `authme.db`, obtained through Paper's library resolver. |
| MiniMessage | MIT | Message formatting, provided by Paper. |

### Test-only

JUnit 5 (EPL-2.0), Netty (Apache-2.0), Gson (Apache-2.0), SQLite JDBC (Apache-2.0).

---

## 4. Licence of AuthMePAL itself

login-core (runtime plugin name `AuthMe`) is licensed under the **GNU General
Public License v3.0 (GPL-3.0)**, inherited from AuthMeReloaded as recorded in
ADR 0008 (2026-11-26). The full licence text is in the root
[LICENSE](./LICENSE) file.

Vendored upstream files keep their original copyright and licence headers; the
root [NOTICE](./NOTICE) records the upstream commit and every vendored file. No
relicensing is performed.
