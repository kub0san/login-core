# Issues & Proposals: Premium vs Offline Name Conflicts

## Scenario A — Squatter offline account blocks premium owner
An offline (cracked) player registers username `Notch` before the real premium owner joins.
- Current behavior: premium owner's first join hits `REQUIRE_PASSWORD_ONCE` (policy), cannot auto-login without the squatter's password → dead end.
- Proposal: add admin command to release/re-key the link + require premium owner verification (hasJoined) to override; keep squatter data under a renamed offline UUID? Or deny join with clear message + admin tooling.

## Scenario B — Rename on Mojang side
Premium name moved to a new Mojang UUID. Stored link has old UUID.
- Current: `ACCOUNT_CONFLICT` kick. Proposal: detect rename via stored-vs-fresh UUID comparison; admin command to re-link; or auto-update link if name+new UUID verified and old UUID no longer resolves.

## Scenario C — Offline account claims name after premium link exists
Link exists for premium UUID; an offline client with same name joins.
- Current: handshake required; offline cannot complete hasJoined → kick verify-failed. Good. Proposal: ensure message is `name-reserved` not generic verify-failed (F-05).

## Scenario D — Concurrent same-name connections
Two clients same name simultaneously.
- Proposal: per-name lock; second waits or is rejected; verified session one-shot already handles.

## Security hardening ideas
- Fail closed on any UUID mismatch.
- Rate-limit premium-name joins per IP.
- Audit log for conflict kicks.
- Never allow password fallback for stored PREMIUM classification.