# Bitbucket Helper project backlog

This is the durable task register for the product design. It records work to
scope, design, implement, and verify; it is not an implementation plan. Checked
items should be backed by a committed design or completed implementation.

## Current architecture session

- [x] Re-evaluate the original Python architecture after choosing Kotlin for
      Bitbucket Helper.
- [x] Select two repositories: Kotlin `bitbucket-helper` and Python
      `desktop-notifications`, with the Vue SPA remaining in `bitbucket-helper`.
- [x] Define one Kotlin/JVM module with package-enforced DDD and ports-and-adapters
      boundaries.
- [x] Define application use cases as the only business entrypoints.
- [x] Approve the strategic context, aggregates, repository-grouped read model,
      use-case boundary, runtime ownership, failure behavior, and testing strategy.
- [x] Commit and self-review the revised architecture specification.
- [ ] Obtain user approval of the committed written specification.
- [ ] Create an ordered implementation plan after written-spec approval.

## Repository transition

- [ ] Establish `../desktop-notifications` as an independent Git repository before
      removing any Python scaffold from `bitbucket-helper`.
- [ ] Move or recreate the reusable UV/Hatchling project structure, lockfile,
      quality configuration, tests, and structure guide in the notification
      repository.
- [ ] Rename the Python distribution and import package to
      `desktop-notifications` and `desktop_notifications` respectively.
- [ ] Verify the standalone Python package and CLI before removing the obsolete
      Python application scaffold from `bitbucket-helper`.
- [ ] Scaffold the Kotlin/JVM Gradle Wrapper project in `bitbucket-helper` using one
      module and JDK 25.
- [ ] Keep the untracked `source/` shell prototype byte-for-byte unchanged during
      the repository migration.
- [ ] Replace the root Python setup documentation with Kotlin/Gradle documentation
      only when the physical migration occurs.
- [ ] Keep `docs/uv-project-structure.md` accurate for the current root until it is
      moved or archived as part of that migration.

## Required follow-up designs

These investigations were deliberately deferred. Each must produce a focused
design or decision record before its implementation starts.

### Persistence

- [ ] Define persistence ports and the transaction or unit-of-work boundary.
- [ ] Select the Kotlin SQLite access and migration approach.
- [ ] Design SQLite and in-memory adapters against one shared behavioral contract
      suite.
- [ ] Define schemas for installation configuration, repository metadata, PR
      observations, action versions, acknowledgments, synchronization checkpoints,
      notification intents and attempts, and retention metadata.
- [ ] Define concurrency guarantees, backup/recovery guidance, and migrations.
- [ ] Define safe configurable pruning, initially retaining inactive PR history for
      30 days.
- [ ] Confirm structurally that raw comment and thread bodies cannot be persisted.

### Scheduler

- [ ] Design Quartz 2.5.x with `RAMJobStore` as a replaceable inbound adapter.
- [ ] Define job registration, overlap prevention, misfire behavior, shutdown,
      observability, and failure reporting.
- [ ] Define how Quartz workers invoke and await coroutine-based use cases without
      detached work.
- [ ] Define periodic synchronization, reminder, retry, and pruning triggers while
      keeping durable business state outside Quartz.
- [ ] Define the seam needed to replace Quartz without modifying domain behavior.

### Desktop notifications

- [ ] Define the minimal generic Python notification model with no Bitbucket
      imports or terminology.
- [ ] Define the `terminal-notifier` adapter, including links, grouping,
      replacement, timeouts, and failure reporting.
- [ ] Decide whether macOS speech through `say` belongs in the generic package.
- [ ] Define a narrow AI-friendly CLI with explicit arguments, versioned JSON
      results, and documented exit codes.
- [ ] Define installation and executable discovery through persistent
      `uv tool install`.
- [ ] Define a CLI protocol/version compatibility check for the Kotlin adapter.
- [ ] Scope repository-grouped logical deduplication so identical content is not
      shown twice while later green transitions remain distinct events.
- [ ] Separate library-level delivery identity from application retries and
      periodic reminders; document crash-window behavior before claiming
      exactly-once delivery.
- [ ] Define notification-package updates, compatibility, rollback, and uninstall.

### Ignored Bitbucket actors

- [ ] Research stable actor identifiers and available name or email fields across
      every relevant Bitbucket endpoint.
- [ ] Define matching, ambiguity, rename, privacy, and migration semantics.
- [ ] Prefer a stable Bitbucket account identifier internally while exploring
      configuration by friendly name or email.
- [ ] Decide whether ignored activity is hidden, shown as non-actionable, or
      retained only for diagnostics.

### Kotlin application installation and updates

- [ ] Define a stable installation path for the fat JAR.
- [ ] Define JDK 25 discovery, compatibility checks, and failure guidance.
- [ ] Define service replacement, database migration, health validation, rollback,
      and cleanup of old JARs.
- [ ] Define how `service install` repairs absolute Java, JAR, and notification CLI
      paths after upgrades.
- [ ] Define credential rotation without leaving stale secrets or duplicate
      LaunchAgents.

## Product implementation backlog

### Kotlin project and architecture boundaries

- [ ] Add the Gradle Wrapper and one Kotlin/JVM application module.
- [ ] Pin mutually compatible Kotlin, Gradle, Ktor 3.5.x, serialization, coroutine,
      Clikt, Quartz 2.5.x, and test dependency versions.
- [ ] Produce one executable fat JAR with one main entrypoint.
- [ ] Create `domain`, `application`, `adapter`, `cli`, and `bootstrap` packages.
- [ ] Add architecture tests enforcing
      `domain <- application <- adapters <- bootstrap`.
- [ ] Prevent the CLI business-command packages from importing application,
      persistence, or Bitbucket implementations directly.

### Domain and application core

- [ ] Model one installation, one Bitbucket identity, one workspace, and a
      repository allowlist.
- [ ] Implement workspace configuration as an explicit application use case and
      persist it as mutable non-secret installation state.
- [ ] Model `InstallationConfiguration`, `PullRequest`, and `ActionItem` aggregate
      roots with stable value-object identities.
- [ ] Keep PR observations separate from live comment and thread bodies.
- [ ] Implement open authored PRs as the v1 population and treat drafts normally.
- [ ] Keep PR selection replaceable in Kotlin so review-assigned PRs can be added
      later without changing synchronization architecture.
- [ ] Implement the fixed seven-check readiness policy and explicit unavailable
      state for unknown or malformed input.
- [ ] Implement build-green as at least one build with every current build
      successful.
- [ ] Emit a distinct event on every false-to-true green transition, even for the
      same commit after an in-progress build resets the predicate.
- [ ] Implement versioned external comments, replies, and changes-requested action
      items.
- [ ] Implement external edits/replies, own replies, resolution/deletion, reopen,
      and later-activity lifecycle rules.
- [ ] Make acknowledgment target the exact displayed activity version.
- [ ] Return the current version and newer-state flag for stale acknowledgment.
- [ ] Permit exact local acknowledgment during an upstream outage and allow a later
      sync to reopen it.
- [ ] Mark closed or removed PRs inactive only after authoritative observation.
- [ ] Implement explicit command and query use-case interfaces as suspendable
      application entrypoints.
- [ ] Keep synchronization checkpoints and notification intents as supporting
      application state rather than forcing them into PR aggregates.

### Bitbucket integration and synchronization

- [ ] Define the `BitbucketGateway` for current identity, repository resolution, PR
      summaries, changed-PR detail, activity metadata and bodies, builds, and tasks.
- [ ] Implement an anti-corruption adapter that prevents Bitbucket DTOs from
      entering the domain.
- [ ] Supply Bitbucket credentials only through the process environment.
- [ ] Resolve repository slugs to stable internal repository identities.
- [ ] Poll lightweight summaries and mutable change probes approximately every five
      minutes.
- [ ] Fetch detail only for new or changed PRs and perform bounded periodic
      reconciliation for signals without a complete upstream cursor.
- [ ] Populate existing actionable activity on first sync and create exactly one
      initial digest per repository.
- [ ] Preserve last-known-good snapshots through network, authentication,
      rate-limit, malformed, or partial-detail failures.
- [ ] Expose synchronization age, current error, and explicit live-body
      unavailability.
- [ ] Implement bounded upstream backoff without retry storms.
- [ ] Implement per-repository single-flight: same-repository callers share one
      result while different repositories refresh concurrently.
- [ ] Prevent slower observations from overwriting newer state or duplicating
      domain transitions.

### Persistence adapters

- [ ] Implement the contracts approved by the focused persistence design.
- [ ] Ship SQLite as the durable embedded adapter with no separately deployed
      database.
- [ ] Ship an in-memory adapter as a reference implementation and test fixture.
- [ ] Keep mutable non-secret settings behind persistence ports.
- [ ] Add migrations, pruning, recovery guidance, and adapter contract tests.

### Ktor service and local transports

- [ ] Build one long-running service that owns scheduling, synchronization, domain
      transitions, notification dispatch, and every durable write.
- [ ] Implement Ktor 3.5.x with CIO and `kotlinx.serialization`.
- [ ] Expose explicit JSON request and response models plus OpenAPI.
- [ ] Serve one application contract over loopback HTTP for the browser and HTTP
      over a user-only Unix socket for the CLI.
- [ ] Validate browser Host and Origin, avoid permissive CORS, and protect mutations
      against CSRF.
- [ ] Package and serve Vue static assets from the fat JAR.
- [ ] Expose health, version, persistence, scheduler, path, and per-repository sync
      diagnostics without secrets.
- [ ] Implement orderly startup and structured coroutine shutdown.

### Product CLI

- [ ] Implement one Clikt command tree with a reserved service-run entrypoint.
- [ ] Make business commands service clients only; never fall back to persistence or
      Bitbucket access.
- [ ] Implement `pr list`, `pr show`, `inbox`, `ack`, `refresh`, and `open`.
- [ ] Implement one-time workspace configuration through the service API.
- [ ] Implement repository allowlist management, including repository addition by
      slug.
- [ ] Implement explicit human output and `--output json` machine mode.
- [ ] Keep JSON stdout to one versioned document for success and expected failures;
      reserve stderr for diagnostics.
- [ ] Document stable exit behavior and the minimal AI-supported command surface.
- [ ] Fail clearly when the service socket is unavailable and provide status/start
      guidance.

### Scheduling and notification integration

- [ ] Register Quartz schedules at service startup using `RAMJobStore`.
- [ ] Have Quartz call and await the same use cases as manual requests.
- [ ] Maintain service-owned structured coroutine scope; prohibit detached global
      jobs.
- [ ] Commit notification intents before invoking the external CLI.
- [ ] Resolve and validate the installed notification executable to an absolute
      path and invoke it without a shell.
- [ ] Retain failed intents for bounded best-effort retry and periodic recovery.
- [ ] Send one first-sync digest, new/advanced action notifications, every green
      transition, and hourly reminders for still-actionable items.
- [ ] Group user-visible notifications by repository while respecting the deferred
      logical-deduplication contract.

### Web dashboard

- [ ] Create a small Vue 3, Vite, and TypeScript workspace under `web/`.
- [ ] Display repository-grouped PR cards without creating repository-sized domain
      aggregates.
- [ ] Show fixed `N of 7` readiness, builds, actionable activity, acknowledgment
      controls, and Bitbucket links.
- [ ] Show last-success age, current sync errors, inactive state where useful, and
      explicit unavailable-body placeholders.
- [ ] Present stale acknowledgment conflicts as newer activity rather than implying
      acknowledgment succeeded.
- [ ] Keep components and styling simple enough for later personal customization.
- [ ] Build and package static assets into JVM resources.

### macOS service management

- [ ] Implement `service install`, `start`, `stop`, `status`, and `logs`.
- [ ] Have installation resolve absolute paths to JDK 25 `java`, the fat JAR, and
      the notification executable.
- [ ] Write a per-user LaunchAgent using user-only permissions.
- [ ] Capture current Bitbucket credentials into the LaunchAgent environment while
      explicitly documenting plaintext-at-rest risk.
- [ ] Redact credentials from logs, status, errors, and diagnostics.
- [ ] Create and permission the Unix socket, embedded database, and log locations.
- [ ] Detect broken runtime paths and recommend safe reinstallation.

### Verification and operations

- [ ] Unit-test domain policies, aggregate transitions, acknowledgment races, and
      application use cases without real infrastructure.
- [ ] Contract-test production and reference persistence adapters.
- [ ] Integration-test fake Bitbucket responses, both API transports, API security,
      Quartz invocation, notification failures, and LaunchAgent generation.
- [ ] Test first-sync digests, repeated green transitions, service and Bitbucket
      outages, backoff, pruning, and concurrent refreshes.
- [ ] Add focused SPA component and browser tests for grouping, readiness,
      staleness, unavailable bodies, and stale acknowledgment.
- [ ] Add end-to-end tests using fake external processes; keep real-account tests
      explicitly opt-in.
- [ ] Document installation, credential rotation, upgrades, recovery, logs,
      database location, backup, and uninstall behavior.

## Post-v1 opportunities

- [ ] Add PRs where the user is a reviewer through another selection policy.
- [ ] Add runtime readiness configuration only if the fixed source-code policy
      proves insufficient; do not add a DSL preemptively.
- [ ] Add another embedded persistence adapter only when a real need validates the
      port.
- [ ] Reassess macOS Keychain-backed credentials if plaintext LaunchAgent storage
      becomes unacceptable.
- [ ] Add quiet hours if hourly reminders become disruptive.
