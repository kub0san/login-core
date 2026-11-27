# Plan: Automatic Login for Premium Users (AuthMe Premium Bypass)

## Goal
Premium (Mojang) accounts auto-login without a password, while cracked clients using a premium name are denied. Prefer AuthMe's built-in premium bypass (`settings.enablePremium`) instead of re-implementing the encryption handshake, falling back to our handshake only where AuthMe's bypass is incomplete.

## Why AuthMe's bypass is incomplete
- Opt-in per player (`/premium` after one password login); no automatic detection.
- Does not block cracked clients from using premium names.
- No Mojang session verification handshake on its own in some flows.

## Approach (phases)

### Phase 1 — Audit AuthMe bypass capabilities
- Read AuthMe source/docs for `PremiumService`, `PremiumPlayer`, `isPremium`, force-login hooks.
- Determine if AuthMe exposes a programmatic "mark premium + force login" API we can call after our own Mojang verification.
- Confirm conflict: AuthMe `enablePremium` must stay false while our handshake runs (P9), OR we disable our handshake and let AuthMe handle encryption.

### Phase 2 — Decision: hybrid
Keep our Mojang name lookup + handshake as the security gate (verified UUID). After verification, instead of registering a random password + forceLogin via AuthMe API, call AuthMe's premium-bypass registration so the account is flagged premium in AuthMe's own tables. This lets AuthMe handle subsequent auto-logins natively.

### Phase 3 — Implementation
- Add `AuthMeBridge.markPremium(name, mojangUuid)` (if API exists) else store premium flag in our links table only.
- In AuthMeLoginListener post-verify: after commitPremiumIdentity, invoke markPremium.
- Keep cracked-name denial via our handshake (AuthMe bypass doesn't do this).
- Config: `use-authme-premium-bypass: false` default; document migration.

### Phase 4 — Data
- No migration needed (DB reset). New premium links written with source `AUTO`.

### Phase 5 — Tests
- Unit: decision engine unchanged.
- Integration: premium client auto-login; cracked client denied; reconnect auto-login via AuthMe bypass.

## Risks
- AuthMe bypass API may be unstable; keep our fallback force-login.
- Double handshake if both enabled; guard remains.
- Requires AuthMe installed (softdepend? currently hard).

## Non-goals
- Velocity/Bungee, Bedrock, MySQL.