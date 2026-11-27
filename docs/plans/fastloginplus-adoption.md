# Plan: Adopt Minimal Useful Code from FastLoginPlus

Goal: keep login-core lightweight; only borrow patterns/code that fix real gaps (premium vs offline conflicts, security hardening) without dragging in proxy/velocity/bungee modules.

## Candidate borrowings (minimal)
1. Trusted-IP / rate limiting utilities (`core/.../TrustedIpSet`, `TickingRateLimiter`) → adapt our per-IP throttles.
2. Storage upsert/lock patterns (`SQLStorageUpsertLockTest`) → improve Storage concurrency safety.
3. Session server probe (`SessionServerProbe`) → better Mojang availability handling.
4. Login method decider (`LoginMethodDecider`) → clarify premium vs cracked decision paths.
5. Reconnect verification settlement → avoid race on reconnect.

## Non-goals
- Do NOT import proxy modules (velocity/bungee), Bedrock/Floodgate, or multi-backend storage.
- Do NOT replace our handshake with FastLogin's unless clearly better; keep PacketEvents approach.

## Phases
1. Read selected files, extract minimal snippets.
2. Port to login-core packages with tests.
3. Verify with ./gradlew test.

## Risks
- License MIT compatible; keep attribution.
- Scope creep; enforce minimal diff.