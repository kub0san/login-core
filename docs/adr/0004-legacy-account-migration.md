# 0004 — Legacy account migration

- **Status:** accepted (2026-11-26); amended 2026-11-28 for premium-priority rename migration

## Context

Existing AuthMe accounts cannot be safely auto-classified. Old enrollment model cannot prove OFFLINE. Evidence rules: existing valid premium UUID = strong PREMIUM evidence; missing premium UUID = insufficient evidence for OFFLINE; confirmed Mojang profile + successful handshake = PREMIUM; confirmed absence = OFFLINE; inconclusive lookup = no permanent classification.

## Decision

- Legacy accounts enter a temporary unclassified state resolved explicitly.
- Migration evidence rules applied per account; never silently assume OFFLINE for missing premium UUID.
- Collision handling: if an existing OFFLINE username later becomes premium, a **verified** premium session wins — the identity migrates (the cracked squatter is evicted, with their login-core account preserved). Unverified clients still fail closed.
- Existing premium UUID records interpreted as PREMIUM.
- Migration is idempotent, transactional, backed up before run, with useful logging.
- No automatic reclassification through normal login or admin actions.
- Cookie-based carry-over: a stable `account_token` is stored in the `logincore:acct` client cookie; on return, an evicted cracked player's password/regdate/realname are restored under their new name. Eviction without cookie reclaim is refused (`migration-enabled` implies `cookie-enabled`).

## Consequences

- ADR documents migration rules; implementation must follow.
- Legacy accounts without premium UUID remain password-authenticated until resolved.
- Premium username changes: associate by UUID not name; a verified rename migrates automatically (premium priority), and the evicted cracked account's data carries over via the cookie. Ambiguous or unverified attempts still fail closed.
