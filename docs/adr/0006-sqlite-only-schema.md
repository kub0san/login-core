# 0006 — SQLite-only storage and schema

- **Status:** accepted (2026-11-26)

## Context

AuthMe supports MySQL/MariaDB/PostgreSQL/H2/SQLite. login-core targets a single direct offline-mode Paper server; one database engine reduces complexity and risk. Existing AuthMe SQLite data must be preserved where possible. SQLite JDBC uses JNI and must not be relocated.

## Decision

- SQLite only. Remove MySQL, MariaDB, PostgreSQL, H2, external converters, backend selection, and backend-specific config.
- Preserve AuthMe SQLite table/column names; use `ALTER TABLE ADD COLUMN` for missing columns (e.g. classification, premium UUID).
- Driver: `org.xerial:sqlite-jdbc` declared in `plugin.yml` `libraries` (not shaded); do not relocate `org.sqlite`.
- Evaluate HikariCP usefulness; default to direct JDBC with WAL + busy_timeout unless proven beneficial.
- Migrations: ordered, version-stamped, idempotent, transactional, backed up before run, newer-schema rejected.
- Back up DB before any migration; never silently delete accounts.

## Consequences

- MySQL-hosted servers must migrate to SQLite manually; document loudly.
- Single engine simplifies testing and reduces attack surface.
- Existing AuthMe SQLite data migrates with column additions.
