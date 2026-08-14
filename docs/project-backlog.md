# Bitbucket Helper project backlog

This is the durable task register for the product brainstorm. It records work to
scope, design, implement, and verify; it is not an implementation plan. Checked
items should link to the design or plan that completed them.

## Current design session

- [x] Compare two or three concrete repository and package structures, including a
      recommended ports-and-adapters modular monolith.
- [x] Select the layered ports-and-adapters modular monolith as the repository
      structure.
- [x] Define its enforceable dependency directions with application use cases as the
      primary entrypoints.
- [x] Present and approve the architecture, component responsibilities, data flow,
      failure handling, operations, and testing strategy.
- [x] Write and commit the approved design specification under
      `docs/superpowers/specs/`.
- [x] Self-review the specification for placeholders, contradictions, excessive
      scope, and ambiguous requirements.
- [ ] Obtain user approval of the written specification.
- [ ] Create a separate, ordered implementation plan after specification approval.

## Explicit follow-up scoping tasks

These investigations were deliberately deferred from the current architecture
session. Each should produce a focused design or decision record before its
implementation starts.

### Persistence implementation

- [ ] Scope the persistence port, transaction or unit-of-work boundary, migration
      policy, and concurrency guarantees.
- [ ] Design the SQLite adapter and the in-memory reference/test adapter against one
      shared contract-test suite.
- [ ] Define schemas and retention behavior for PR metadata snapshots, sync cursors,
      versioned actionable activity, acknowledgments, notification history, retry
      records, repository configuration, and policy configuration.
- [ ] Confirm that raw comment and thread bodies are never persisted; they are fetched
      live and represented as explicitly unavailable during outages.
- [ ] Define a safe configurable pruning process for inactive PR metadata and
      acknowledgment history, initially retaining it for 30 days.

### Scheduler implementation

- [ ] Scope APScheduler 3.11.x as a replaceable inbound adapter and pin it below 4.
- [ ] Define job registration, service lifecycle integration, coalescing, misfire,
      overlap, retry, shutdown, and observability behavior.
- [ ] Define periodic PR synchronization and reminder/resend jobs without putting
      scheduling concepts in the application core.
- [ ] Specify the scheduler port or application command seam needed to replace
      APScheduler later.

### Generic notification package and CLI

- [ ] Scope a generic top-level `desktop_notifications` package under `src/`, shipped
      in the same distribution as `bitbucket_helper`, that never imports Bitbucket
      application modules and can later move to its own repository.
- [ ] Define the minimal generic notification model and the `terminal-notifier`
      adapter, including links, grouping, replacement behavior, timeouts, and failure
      reporting.
- [ ] Decide whether speech through macOS `say` belongs in the generic package or is
      excluded from v1.
- [ ] Define a narrow AI-friendly CLI with explicit arguments, a versioned JSON result
      schema, and documented exit codes.
- [ ] Scope repository-grouped deduplication so the same logical notification is not
      displayed twice, while still allowing a new green transition for the same PR
      commit to notify again.
- [ ] Separate library-level delivery identity from application-level best-effort
      retries and periodic reminders; document crash-window semantics rather than
      claiming unsupported exactly-once delivery.
- [ ] Define the future repository-extraction checklist and dependency boundary.

### Ignored Bitbucket actors

- [ ] Research which stable actor identifiers and friendly name or email fields are
      actually available from every relevant Bitbucket endpoint.
- [ ] Specify matching, ambiguity, rename, privacy, and migration semantics for an
      ignored-commenter feature.
- [ ] Prefer a stable Bitbucket account identifier internally while investigating a
      CLI that lets the user configure ignored actors by friendly name or email.
- [ ] Decide whether ignored activity is hidden completely, visible but non-actionable,
      or retained only for audit and debugging.

## Product implementation backlog

### Domain and application core

- [ ] Model a single installation, one Bitbucket identity, one configured workspace,
      and an allowlist of repositories addressed by repository slug.
- [ ] Model PR metadata snapshots separately from live comment and thread bodies.
- [ ] Implement open authored PRs as the v1 population and treat draft PRs like other
      open PRs.
- [ ] Keep the PR selection policy extensible so PRs awaiting the user's review can be
      added later without changing the synchronization architecture.
- [ ] Implement the fixed seven-check readiness policy with the agreed denominator and
      explicit unavailable state for unknown or malformed input.
- [ ] Keep readiness checks as domain policy strategies, not infrastructure ports, so
      new check types can be added in Python without redesigning external boundaries.
- [ ] Model the build-green predicate as: at least one build exists and every current
      build is successful.
- [ ] Emit a build-green event on every false-to-true transition. A newly observed
      in-progress build resets the predicate, allowing another green event for the
      same head commit after all current builds succeed.
- [ ] Model actionable external comments, replies, and formal changes-requested events
      as versioned activity.
- [ ] Implement activity evolution: external edits or replies create a newer version;
      the user's reply acknowledges the observed version; resolve or delete closes it;
      reopen or later external activity creates a new actionable version.
- [ ] Make acknowledgment target an explicit activity version and prevent it from
      silently acknowledging a newer version.
- [ ] Return a stale conflict with the newer state when a client acknowledges an old
      version already known to the service.
- [ ] Permit exact-version local acknowledgment during a Bitbucket outage; a later sync
      can reopen the item immediately when it discovers newer activity.
- [ ] Mark closed or removed PRs inactive, stop their polling and notifications, and
      retain their local history until the retention job prunes it.
- [ ] Define explicit command and query use cases as the application entrypoints,
      independently of FastAPI, CLI, APScheduler, SQLite, and `terminal-notifier`.

### Bitbucket integration and synchronization

- [ ] Define a Bitbucket outbound port for PR summaries, changed-PR details, live
      activity bodies, build statuses, tasks, and current-user identity.
- [ ] Implement the Bitbucket Cloud adapter using credentials supplied only through
      the process environment.
- [ ] Persist a stable repository identifier internally while presenting configured
      repository slugs within the single configured workspace.
- [ ] Poll lightweight PR summaries and minimal build, task, and activity change
      probes approximately every five minutes; fetch full details only for changed
      PRs and run a bounded periodic reconciliation for signals without a complete
      upstream cursor.
- [ ] On first synchronization, populate the actionable inbox and coalesce existing
      notification events into exactly one initial digest per configured repository.
- [ ] Preserve the last successful metadata snapshot during outages and expose its age
      plus the latest synchronization error.
- [ ] Make unavailable live bodies explicit instead of presenting missing content as
      an empty comment or a successful refresh.
- [ ] Add bounded backoff for rate limits and transient failures without discarding
      last-known-good state.
- [ ] Add manual synchronous refresh with per-repository single-flight behavior:
      overlapping callers share one refresh, while different repositories may refresh
      concurrently.
- [ ] Ensure sync transactions cannot let a slower response overwrite newer observed
      state or emit duplicate domain transitions.

### Persistence adapters

- [ ] Implement the persistence contracts approved by the separate persistence design.
- [ ] Ship SQLite as the durable embedded adapter with no separately deployed database.
- [ ] Ship an in-memory adapter as a reference implementation and test fixture.
- [ ] Keep mutable non-secret settings, including repository allowlist and retention
      configuration, behind the persistence port.
- [ ] Add migrations, backups or recovery guidance, pruning, and adapter contract tests.

### Service and transports

- [ ] Build one long-running per-user service that owns scheduling, synchronization,
      domain state transitions, notification dispatch, and every durable write.
- [ ] Expose explicit FastAPI request and response models with generated OpenAPI.
- [ ] Serve one application API contract over loopback HTTP for the browser and HTTP
      over a Unix-domain socket for the CLI.
- [ ] Bind browser HTTP to loopback only, validate Host and Origin strictly, avoid
      permissive CORS, and require CSRF protection for mutations.
- [ ] Return snapshot timestamps, staleness, body-availability, and sync-error metadata
      consistently across dashboard and CLI responses.
- [ ] Package and serve the compiled SPA assets from the Python service.
- [ ] Add health, readiness, version, and diagnostic information needed by service
      management commands.

### Bitbucket assistant CLI

- [ ] Make the CLI a service client only; it must never access SQLite or Bitbucket as a
      fallback when the service is unavailable.
- [ ] Implement `pr list`, `pr show`, `inbox`, `ack`, `refresh`, and `open` as v1
      read-and-triage commands.
- [ ] Implement repository allowlist commands, including adding a repository slug in
      the configured workspace.
- [ ] Define human-readable output plus a stable machine-readable JSON mode and
      documented exit behavior.
- [ ] Fail clearly when the service is unavailable and provide status and start
      guidance.

### Scheduling and notifications

- [ ] Integrate the separately designed APScheduler adapter into service startup and
      shutdown.
- [ ] Dispatch generic notification requests through the notification library from the
      application layer.
- [ ] Retry failed delivery with bounded best effort and let periodic reminder jobs
      cover longer-lived actionable items.
- [ ] Group user-visible notifications by repository and retain delivery history needed
      for logical deduplication.
- [ ] Notify when builds become green according to the transition semantics above.
- [ ] Decide in the design whether full PR readiness also gets its own notification
      trigger in v1.
- [ ] Define reminder cadence, quiet-hour behavior, and the conditions that stop
      reminders.

### Web dashboard

- [ ] Build a deliberately small Vue 3, Vite, and TypeScript SPA in a distinct
      workspace directory.
- [ ] Show authored open PRs, the fixed `N of 7` readiness result, actionable external
      activity, acknowledgment controls, and links back to Bitbucket.
- [ ] Show last successful sync age, current sync errors, inactive state where useful,
      and explicit placeholders when live comment bodies cannot be fetched.
- [ ] Detect stale acknowledgment conflicts and present the newer activity without
      implying that it was acknowledged.
- [ ] Keep visual structure and components simple enough for later personal
      customization.

### macOS service management

- [ ] Implement `service install`, `start`, `stop`, `status`, and `logs` commands for a
      per-user LaunchAgent.
- [ ] Have `service install` capture current credential values into the LaunchAgent's
      environment dictionary and write the plist with user-only permissions.
- [ ] Treat plist credential storage as an explicit plaintext-at-rest compromise;
      redact credentials from logs, status, errors, and generated diagnostics.
- [ ] Define safe reinstall and credential-rotation behavior without leaving stale
      secrets or duplicate LaunchAgents.
- [ ] Create and permission the Unix socket, state database, and log locations for one
      macOS user.

### Verification and operations

- [ ] Unit-test domain policies, transition state machines, acknowledgment races, and
      application commands without real infrastructure.
- [ ] Contract-test every outbound port against its in-memory or fake implementation
      and its production adapter where practical.
- [ ] Integration-test the SQLite adapter, fake Bitbucket responses, API security,
      loopback and Unix-socket transport parity, scheduler calls, and notification
      failure paths.
- [ ] Test first-sync digests, repeated green transitions for one commit, service
      outages, Bitbucket outages, rate limiting, pruning, and concurrent refreshes.
- [ ] Add focused SPA component and browser tests for inbox, readiness, staleness,
      unavailable bodies, and stale acknowledgment conflicts.
- [ ] Document installation, credential rotation, service recovery, logs, database
      location, backup, and uninstall behavior.

## Explicit post-v1 opportunities

- [ ] Add PRs where the user is a reviewer as another selection policy.
- [ ] Add runtime enable/disable or parameterization of readiness checks only if the
      fixed v1 policy proves insufficient; do not add a policy DSL preemptively.
- [ ] Extract the generic notification package and CLI to a separate repository.
- [ ] Add another embedded persistence adapter only when a real need validates the
      port.
- [ ] Replace APScheduler only through the application command seam established in v1.
- [ ] Reassess stronger credential storage, such as macOS Keychain, if plaintext plist
      storage becomes unacceptable.
