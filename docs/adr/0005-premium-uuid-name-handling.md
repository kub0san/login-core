# 0005 — Premium UUID and name handling

- **Status:** accepted (2026-11-26)

## Context

Premium identity must be associated with Mojang UUID, not username alone, to handle renames and reuse. AuthMe's `premiumUUID` field exists but is mutable and nullable with no unique index. Verified-session state in login-core is keyed by name+IP (K-1 defect).

## Decision

- Premium accounts keyed by verified Mojang UUID (stable). Username stored as display name, may change.
- Verified-session handoff is keyed by connection identity in the PacketEvents layer, with a secondary alias index keyed `name@ip` for the Bukkit pre-login stage; `put()` fails closed on a conflicting live alias (Bukkit exposes no channel at pre-login — `docs/DEVIATIONS.md:6`).
- Name reuse: original premium UUID remains with original account; conflicting new account rejected or requires explicit resolution.
- `setPremiumUuid` is write-once; nulling (disable) removed.
- RSA upgraded to 2048-bit; constant-time token compare; fail-closed on signed-nonce (no unverified resume).
- No password fallback for PREMIUM under any circumstance.

## Consequences

- Requires DB unique index on premium UUID.
- Connection-keyed sessions prevent cross-connection bleed.
- Accepted `shortcut:` limit: the `name@ip` alias index cannot distinguish two simultaneous connections with the same name from the same IP; upgrading it requires a connection identity exposed to Bukkit pre-login (a channel-backed handoff or explicit pre-login id) so the alias index can be replaced.
- Rename support limited to UUID-stable identity; ambiguous attempts rejected safely.
