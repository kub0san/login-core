# 0002 — Runtime plugin identity

- **Status:** accepted (2026-11-26)
- **Related:** `C:\Projects\login-core\docs\adr\0001-project-structure-and-github.md`

## Context

login-core is a derivative fork of AuthMeReloaded. AuthMe's runtime plugin identity (`name: AuthMe` in `plugin.yml`) drives its data folder (`plugins/AuthMe/authme.db`), its public API singleton (`fr.xephi.authme.api.v3.AuthMeApi.getInstance()`), and the `depend: [AuthMe]` declarations of downstream plugins.

Agent F compatibility audit: renaming the runtime plugin to `login-core` would orphan all existing `plugins/AuthMe` data and break every downstream `depend: [AuthMe]`. The repository, artifact, and development project are consistently `login-core`; only the runtime plugin identity stays `AuthMe` for compatibility.

## Decision

- Keep `plugin.yml` `name: AuthMe` (runtime identity unchanged) so existing data folders, API singletons, and downstream dependencies continue to work.
- Rename repository, artifact, group, and development project to `login-core` / `com.hizeric.logincore`.
- Preserve the `AuthMeApi` façade and lifecycle events as a compatibility surface; drop only implementations of removed features (email, 2FA, GeoIP, etc.) while keeping class names as no-op stubs where referenced.

## Consequences

- Existing servers upgrading from AuthMe keep their `plugins/AuthMe/authme.db`.
- Downstream plugins depending on `AuthMe` continue to load.
- Repository/artifact naming is `login-core`; runtime plugin name remains `AuthMe`. Document this distinction clearly in README.
- If a clean break is ever desired, it requires a separate migration ADR.
