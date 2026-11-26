# 0003 — Immutable account classification

- **Status:** accepted (2026-11-26)

## Context

login-core's defining behavior is permanent PREMIUM/OFFLINE classification. PREMIUM requires authoritative Mojang profile + successful cryptographic handshake + UUID match + successful persistence. OFFLINE requires authoritative absence of a premium profile. No command, config, reload, or reclassification may change a type. AuthMe's enrollment model (PendingPremiumCache, /premium, /freemium, admin toggle) is mutable and must be removed.

## Decision

- Each account has exactly one permanent type: `PREMIUM` or `OFFLINE`. Temporary connection states (UNCLASSIFIED, PROFILE_LOOKUP_PENDING, PREMIUM_VERIFICATION_PENDING, VERIFICATION_FAILED, IDENTITY_CONFLICT) are never persisted as permanent types.
- Classification is write-once at the storage layer: `setAccountType` rejects overwrite; DB has a CHECK constraint on type and unique index on premium UUID.
- Remove all enrollment commands (/premium, /freemium, /authme premium, /authme freemium), PendingPremiumCache, admin toggle methods, and related config. Classification cannot be changed by any public or admin command.
- PREMIUM accounts never accept password authentication; no password fallback under any circumstance.
- A failed DB write never grants authenticated access with an unpersisted identity.
- Inconclusive Mojang results (timeout, rate limit, service error, invalid response) never assign OFFLINE; connection is rejected with a retry message.

## Consequences

- Immutable type enforced in storage + service, not just UI.
- Tests must verify no command/config/admin action can change type.
- Legacy accounts without premium UUID are not assumed OFFLINE; handled by ADR 0004.
