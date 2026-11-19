# Credits and third-party notices

AuthMePAL is an independent project. This file records where its design came from,
which third-party components it uses, and what was *not* taken from anyone.

> This document describes the project's own review of its provenance. It is not legal advice.

---

## 1. Provenance: no source code was copied

AuthMePAL's login flow was designed by reading how other plugins do premium
verification, then implementing it from scratch against Mojang's public session
protocol and the PacketEvents API.

A statement-level comparison of this repository's sources against the upstream
sources of AuthMeReloaded and FastLogin was run before publication. Comments and
whitespace were normalised away and every statement of 35 characters or more was
checked for a verbatim match. The only statements shared with upstream were:

- `import` lines for shared APIs (Bukkit, Netty, PacketEvents, JDK),
- `Cipher.getInstance("AES/CFB8/NoPadding")`, which the Minecraft protocol requires,
- a `private final SecureRandom secureRandom;` field declaration, and
- one `else if (event.getPacketType() == ...)` packet-dispatch line.

No method bodies, classes, messages, tests, or database schemas were taken from
AuthMeReloaded or FastLogin. All log messages, kick messages, and configuration
text in this repository were written for it. `ServerHashTest` vectors are values
from the Minecraft protocol documentation, not from either project.

**Conclusion:** no third-party copyrightable expression was incorporated, so no
copyleft obligation arises from those projects. What was borrowed is *design* —
the shape of the handshake and the order of operations — which the project
credits below.

---

## 2. Design references

| Project | Author | Licence | What was referenced |
|---|---|---|---|
| [FastLogin](https://github.com/games647/FastLogin) | games647 / TuxCoding | MIT | The overall premium-login sequence: hold the connection at `LOGIN_START`, prove ownership through Mojang's `hasJoined` endpoint, then continue the login. PLAN.md §4's flow diagram is explicitly an adaptation of FastLogin's. |
| [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded) | Xephi and contributors | GPL-3.0 | Behavioural reference for the packet-phase handshake (cancel → verify → re-inject `LOGIN_START`), the verify-token pattern, and how a premium account is registered and force-logged-in without a `Player` object. AuthMe's own premium bypass was studied only to confirm AuthMePAL must require `settings.enablePremium: false`. |
| [Minecraft protocol documentation](https://wiki.vg/Protocol) | Mojang / community | — | The session-hash algorithm (`SHA-1(serverId + sharedSecret + publicKey)` rendered as signed hex), AES/CFB8 key = IV = shared secret, and the `LOGIN_START` / `ENCRYPTION_REQUEST` / `ENCRYPTION_RESPONSE` packet exchange. |
| [LuckPerms](https://github.com/LuckPerms/LuckPerms) | LuckPerms contributors | MIT | Read to diagnose the pre-login permission-loading conflict. AuthMePAL now applies the verified profile at `EventPriority.LOWEST` so that LuckPerms' `LOW` handler reads the correct UUID. No LuckPerms code is used. |

Attribution above is given as a courtesy and as documentation of provenance. It
is not an assertion that any copyleft licence applies to AuthMePAL.

---

## 3. Dependencies

Every item below is used as an **API or runtime dependency only**. None of them
is compiled into, shaded into, or redistributed with the AuthMePAL jar.

### Compile-time (`compileOnly`)

| Component | Version | Licence | How it is used |
|---|---|---|---|
| [PacketEvents](https://github.com/retrooper/packetevents) (`packetevents-spigot`) | 2.13.0 | GPL-3.0 | Installed as a separate server plugin. AuthMePAL calls its login-phase packet listeners and wrappers. Not bundled. |
| [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded) (`authme-core`) | 6.0.1 | GPL-3.0 | Installed as a separate server plugin. AuthMePAL touches AuthMe types only through `AuthMeBridge`. Not bundled. |
| [Paper API](https://papermc.io/) (`paper-api`) | 26.2 | See [PaperMC's licence](https://papermc.io/license) | Server API: plugin lifecycle, events, player profiles. |
| Gson | provided by server | Apache-2.0 | JSON parsing for Mojang responses. |
| Netty | provided by server | Apache-2.0 | Cipher handlers installed into the login channel pipeline. |

### Runtime (server-side, optional/indirect)

| Component | Licence | Relationship to AuthMePAL |
|---|---|---|
| [LuckPerms](https://github.com/LuckPerms/LuckPerms) | MIT | Optional. AuthMePAL does not depend on it; it only orders its own event handler so LuckPerms loads permissions under the correct UUID. |
| SQLite JDBC | Apache-2.0 | Backing store for `data.db`, obtained through Paper's library resolver. |
| MiniMessage | MIT | Message formatting, provided by Paper. |

### Test-only

JUnit 5 (EPL-2.0), Netty (Apache-2.0), Gson (Apache-2.0), SQLite JDBC (Apache-2.0).

---

## 4. Licence of AuthMePAL itself

**To be decided.** The project is intended to be published as open source; the
specific licence will be chosen before the repository is made public.

Until a `LICENSE` file exists, all rights are reserved by the author.
