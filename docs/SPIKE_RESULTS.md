# Spike results

These results distinguish compile-time/source inspection from live Paper/client verification. The user reports that the previously delivered login features worked on their server. The premium UUID mode added afterward has not yet received a live-server test.

## S1: PacketEvents 2.13.0 interception
- **Status**: ⚠️ API verified; live packet test pending
- **Evidence**: The project compiles against PacketEvents 2.13.0. Its `PacketReceiveEvent` inherits `setCancelled(boolean)`, `getConnectionState()`, `getPacketType()`, `getUser()`, and `getSocketAddress()` from `ProtocolPacketEvent`; login wrappers are used by `PremiumLoginListener`.
- **Not yet verified**: Receiving and cancelling LOGIN_START and ENCRYPTION_RESPONSE from a real 26.2 client.

## S2: LOGIN_START re-injection
- **Status**: ✅ User-reported runtime test passed
- **Implementation**: Uses `User.receivePacketSilently(...)` so reinjected packets do not re-enter the PacketEvents listener.
- **Evidence**: The user reports successful end-to-end operation of the prior build.

## S3: Netty pipeline handler names and order
- **Status**: ✅ Functionally exercised by the user-reported successful handshake; exact pipeline names not recorded
- **Implementation assumption**: `CipherInstaller` inserts decryption before `splitter` and encryption before `prepender`.
- **Not yet verified**: A pipeline dump was not recorded, and the new premium UUID profile override has not been tested live.

## S4: AuthMe 6.0.1 API
- **Status**: ✅ API/source analysis complete
- **Evidence**: The installed AuthMe API/source exposes `AuthMeApi#getInstance`, `isRegistered`, `registerPlayer(String, String)`, `forceLogin(Player)`, `forceLoginFromProxy(String)`, `getRegisteredNames`, and `getPlayerInfo`; `LoginEvent` and cancellable `RestoreSessionEvent` are used by the bridge/listener.
- **Reference**: FastLogin's AuthMe hook informed the session-restore suppression and force-login flow. No upstream implementation code was copied.
- **Not yet verified**: Runtime scheduling/TTL behavior for `forceLoginFromProxy`, or actual event ordering on a live server.

## S5: Cracked-launcher UUID behavior
- **Status**: User reports the prior login features worked; launcher/version matrix not recorded
- **Not yet verified**: UUID hints from at least two identified cracked clients versus a real premium client.

## S6: AuthMe premium-listener conflict
- **Status**: Source/config requirement recorded; live conflict test pending
- **Requirement**: Keep AuthMe's `settings.enablePremium: false`; AuthMePAL refuses startup when this option is enabled.
- **Not yet verified**: Listener registration behavior with both plugins enabled on the target server.

## S7: Paper profile and SQLite behavior
- **Status**: Profile API compiles and SQLite unit tests pass; premium UUID propagation and live server tests pending
- **Evidence**: AuthMePAL sets the pre-login player profile UUID only for a verified session when `use-premium-uuid` is enabled (enabled by default). SQLite persistence is covered by unit tests. The descriptor declares the SQLite JDBC library for runtime resolution.
- **Not yet verified**: A real join receives the Mojang UUID on Paper 26.2; the declared JDBC library loads correctly from a production server; secure-profile interaction. Skin restoration is delegated to another plugin.

## Automated verification
- `.\gradlew.bat test build --console=plain` — **passed** after the latest code changes.
- Build/test success does not verify the pending live-server spikes above.
