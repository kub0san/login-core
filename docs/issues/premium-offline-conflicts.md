# Issues & Proposals: Premium vs Offline Name Conflicts

Status: Scenarios A and B are now handled by automatic premium-rename migration
(see `docs/superpowers/specs/2026-11-28-premium-rename-migration-design.md`).

## Scenario A — Squatter offline account blocks premium owner
RESOLVED (after decision-flow fix). The pre-handshake decision used to disconnect a name with
an OFFLINE classification as soon as Mojang reported PREMIUM; it now proceeds to the verified
handshake, and `decidePostVerify` accepts the premium instead of demanding the squatter's
password. On the premium owner's verified join, `AuthMeLoginListener.commitIdentity` detects the
rename and `Storage.migratePremiumRename` evicts the squatter in one transaction: the squatter's
`authme` row is moved to `evicted_accounts` (keyed by a stable `account_token`), the name is
freed, and the premium identity is written. The squatter keeps their login-core account
(password/regdate) and is recognized on return via the `logincore:acct` cookie, which restores
the account under their new name. An online squatter is kicked best-effort.

## Scenario B — Rename on Mojang side
RESOLVED. Detected via stored-vs-fresh UUID comparison in `commitIdentity` (`linkByUuid`); the
old link is released and the premium identity migrates automatically. No admin re-link needed.

## Scenario C — Offline account claims name after premium link exists
Unchanged and correct: handshake required; offline client cannot complete hasJoined → kick.
Message is `name-reserved` (F-05).

## Scenario D — Concurrent same-name connections
Unchanged: per-name lock not implemented; verified session one-shot already handles it.

## Deferred (tracked as GitHub issues)
- Stable entity UUID for cracked players (full inventory/stat/other-plugin data across rename):
  requires a proxy or NMS — out of scope for this backend plugin. See issue #2.
- Vanilla dialog display on stock Paper 1.21.11: `Player.showDialog` is not in the pinned
  paper-api, so dialogs degrade to the existing `/login` / `/register` command flow. A future
  task can add NMS-based display.

## Security hardening ideas
- Fail closed on any UUID mismatch (unchanged).
- Rate-limit premium-name joins per IP.
- Audit log for conflict kicks.
- Never allow password fallback for stored PREMIUM classification.