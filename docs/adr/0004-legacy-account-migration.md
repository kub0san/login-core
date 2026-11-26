# 0004 — Legacy account migration

- **Status:** accepted (2026-11-26)

## Context

Existing AuthMe accounts cannot be safely auto-classified. Old enrollment model cannot prove OFFLINE. Evidence rules: existing valid premium UUID = strong PREMIUM evidence; missing premium UUID = insufficient evidence for OFFLINE; confirmed Mojang profile + successful handshake = PREMIUM; confirmed absence = OFFLINE; inconclusive lookup = no permanent classification.

## Decision

- Legacy accounts enter a temporary unclassified state resolved explicitly.
- Migration evidence rules applied per account; never silently assume OFFLINE for missing premium UUID.
- Collision handling: if an existing OFFLINE username later becomes premium, do not convert; fail closed for unverified clients; document consequence.
- Existing premium UUID records interpreted as PREMIUM.
- Migration is idempotent, transactional, backed up before run, with useful logging.
- No automatic reclassification through normal login or admin actions.

## Consequences

- ADR documents migration rules; implementation must follow.
- Legacy accounts without premium UUID remain password-authenticated until resolved.
- Premium username changes: associate by UUID not name; reject ambiguous attempts safely.
