# Project Guidance

## HTTP API status semantics

- HTTP status codes describe request and transport success or failure; they are
  not a place for business logic.
- Return `200 OK` when a valid API request was processed, including when its
  business outcome is pending, stale, partial, unavailable, rejected by a domain
  rule, or otherwise unsuccessful.
- Represent every business outcome explicitly in the versioned response body.
- Use `4xx` only for client/request errors and `500` for an unexpected server
  error.
- Do not use statuses such as `202 Accepted` or `409 Conflict` to encode refresh
  lifecycle or exact-version acknowledgment outcomes.

## Logging and diagnostics

- The backend service uses Log4j2 and emits accepted events to both the terminal
  and rotating JSON log. Application logging defaults to DEBUG in both.
- Product CLI commands must not initialize service logging or change their
  stdout/stderr contracts.
- Every new or changed lifecycle, inbound, scheduled/asynchronous, persistence,
  or outbound integration boundary must record a stable terminal outcome with
  the correlation IDs needed to investigate it.
- Asynchronous correlations are explicit; do not assume thread-local MDC
  propagation across coroutines.
- Log only allowlisted stable IDs, fixed categories, counts, durations, statuses,
  and sanitized exception diagnostics. Never log credentials, environment
  values, headers, cookies, raw paths/queries, bodies, upstream payloads,
  user-authored content, notification content/provider output, SQL/binds,
  absolute resource paths, exception messages, or arbitrary objects.
- Domain code remains logging-free. Application code emits only typed events
  through the outbound observability port where a failure would otherwise be
  converted or swallowed.
- Do not use backend `println`, `System.out`, `System.err`, `printStackTrace`,
  generic access/wire logging, or catch-and-discard failure handling. The only
  exception is the fixed process-level terminal fallback before safe service
  logging can initialize.
- Tests for every new event must prove its level, name, required correlations,
  both destinations, terminal behavior, and privacy exclusions on success and
  failure paths.
- Logging severity never replaces versioned response-body business outcomes or
  changes the project's HTTP status semantics.

## Worktree preference

- When using the `superpowers:using-git-worktrees` skill, create linked
  worktrees under the project-local `.worktrees/` directory.
- Implement the Kotlin walking skeleton in `.worktrees/kotlin-walking-skeleton`
  on branch `codex/kotlin-walking-skeleton`.
- Verify `.worktrees/` remains ignored before creating another linked worktree.
